package app.docscan.core.store

import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.ArtifactId
import app.docscan.core.model.DeleteBatchId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.model.SaveManifestId
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId
import app.docscan.core.time.Clock
import app.docscan.core.time.TimeReading

/** Product metadata authority. Android/provider I/O must happen before these transactions. */
class StateStore(
    private val database: DocScanDatabase,
    private val clock: Clock,
) {
    private val queries get() = database.schemaQueries

    private fun String.jsonEscape(): String = buildString(length) {
        this@jsonEscape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private fun expectedExportPath(artifactId: ArtifactId, extension: String): String =
        "objects/${artifactId.value.take(2)}/${artifactId.value}.$extension"

    fun firstWorkspaceId(): WorkspaceId? =
        queries.firstWorkspace().executeAsOneOrNull()?.workspace_id?.let(WorkspaceId::parse)

    fun workspaceBootstrapState(): WorkspaceBootstrapState = when (queries.workspaceBootstrapState().executeAsOne()) {
        "EMPTY" -> WorkspaceBootstrapState.EMPTY
        "EXISTING" -> WorkspaceBootstrapState.EXISTING
        "ORPHANED" -> WorkspaceBootstrapState.ORPHANED
        else -> throw StoreInvariantViolation("Unknown workspace bootstrap state")
    }

    /** Persists a URI only after Android has durably taken its persistable tree grant. */
    fun recordDestinationGrant(treeUri: String, wallMillis: Long) {
        val grant = DestinationGrantSnapshot(treeUri, wallMillis, wallMillis)
        database.transaction {
            queries.deleteDestinationGrant()
            queries.insertDestinationGrant(grant.treeUri, grant.grantedWallMillis, grant.lastVerifiedWallMillis)
        }
    }

    fun destinationGrant(): DestinationGrantSnapshot? = queries.activeDestinationGrant().executeAsOneOrNull()?.let {
        DestinationGrantSnapshot(it.tree_uri, it.granted_wall, it.last_verified_wall)
    }

    /** Updated only after caller revalidates the OS grant outside this transaction. */
    fun markDestinationGrantVerified(treeUri: String, wallMillis: Long) {
        require(treeUri.startsWith("content://") && wallMillis >= 0L)
        database.transaction {
            val current = queries.activeDestinationGrant().executeAsOneOrNull()
                ?: throw StoreConflict("Destination grant is missing")
            if (current.tree_uri != treeUri) {
                throw StoreConflict("Destination grant changed while verification was in progress")
            }
            val monotonicWall = maxOf(wallMillis, current.last_verified_wall)
            if (queries.updateDestinationGrantVerified(monotonicWall, treeUri, monotonicWall).value != 1L) {
                throw StoreConflict("Destination grant changed or verification time precedes grant")
            }
        }
    }

    fun clearDestinationGrant(treeUri: String) {
        require(treeUri.startsWith("content://"))
        database.transaction {
            val current = queries.activeDestinationGrant().executeAsOneOrNull()
                ?: throw StoreConflict("Destination grant is missing")
            if (current.tree_uri != treeUri || queries.deleteDestinationGrant().value != 1L) {
                throw StoreConflict("Destination grant changed while removing")
            }
        }
    }

    /** Stores a tuple-bound assessment; its payload identifies formal or runtime capability evidence. */
    fun recordProviderQualification(command: ProviderQualificationCommand) {
        recordProviderAssessment(command)
    }

    /** Returns the committed assessment without a separate fallible post-commit read. */
    fun recordProviderAssessment(command: ProviderQualificationCommand): ProviderQualificationSnapshot =
        database.transactionWithResult {
            if (queries.providerQualificationById(command.qualificationId).executeAsOneOrNull() != null) {
                throw StoreConflict("ProviderQualification ID already exists")
            }
            queries.insertProviderQualification(
                qualification_id = command.qualificationId,
                adapter_id = command.adapterId,
                provider_version = command.providerVersion,
                os_version = command.osVersion,
                destination_access_class = command.destinationAccessClass,
                validation_suite_version = command.validationSuiteVersion,
                qualified_capabilities_payload = command.qualifiedCapabilitiesPayload,
            )
            ProviderQualificationSnapshot(
                command.qualificationId, command.adapterId, command.providerVersion, command.osVersion,
                command.destinationAccessClass, command.validationSuiteVersion, command.qualifiedCapabilitiesPayload,
            )
        }

    fun providerQualificationFor(
        adapterId: String,
        providerVersion: String,
        osVersion: String,
        destinationAccessClass: String,
    ): ProviderQualificationSnapshot? = queries.providerQualificationByTuple(
        adapterId, providerVersion, osVersion, destinationAccessClass,
    ).executeAsOneOrNull()?.let {
        ProviderQualificationSnapshot(
            it.qualification_id, it.adapter_id, it.provider_version, it.os_version,
            it.destination_access_class, it.validation_suite_version, it.qualified_capabilities_payload,
        )
    }

    fun providerQualificationsFor(
        adapterId: String,
        providerVersion: String,
        osVersion: String,
        destinationAccessClass: String,
    ): List<ProviderQualificationSnapshot> = queries.providerQualificationsByTuple(
        adapterId, providerVersion, osVersion, destinationAccessClass,
    ).executeAsList().map {
        ProviderQualificationSnapshot(
            it.qualification_id, it.adapter_id, it.provider_version, it.os_version,
            it.destination_access_class, it.validation_suite_version, it.qualified_capabilities_payload,
        )
    }

    /** Creates the initial workspace and session exactly once for a fresh product database. */
    fun createInitialWorkspace(workspaceId: WorkspaceId, sessionId: WorkspaceSessionId) {
        val time = clock.read()
        database.transaction {
            if (queries.workspaceById(workspaceId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("Workspace already exists: ${workspaceId.value}")
            }
            queries.insertWorkspace(
                workspace_id = workspaceId.value,
                order_revision = 0L,
                active_page_id = null,
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                retention_time_uncertain = 0L,
            )
            queries.insertWorkspaceSession(
                workspace_session_id = sessionId.value,
                workspace_id = workspaceId.value,
                state = "OPEN",
            )
        }
    }

    /** Records an artifact only after ArtifactRepository has made its bytes durable. */
    fun recordStagedDurableArtifact(artifact: DurableImageArtifact) {
        database.transaction {
            queries.insertStagedArtifact(
                artifact_id = artifact.artifactId.value,
                relative_storage_path = artifact.relativePath,
                artifact_kind = "DOCUMENT_PAGE",
                size_bytes = artifact.sizeBytes,
                checksum = artifact.sha256,
                artifact_format_version = artifact.formatVersion,
                creation_revision = 1L,
                producer_epoch = artifact.producerEpoch,
            )
        }
    }

    /** Crash-safe repeat registration for the deterministic per-page PDF render object. */
    fun recordStagedPdfImageArtifact(artifact: DurableImageArtifact): DurableImageArtifact =
        database.transactionWithResult {
            val existing = queries.artifactById(artifact.artifactId.value).executeAsOneOrNull()
            if (existing == null) {
                queries.insertStagedArtifact(
                    artifact_id = artifact.artifactId.value,
                    relative_storage_path = artifact.relativePath,
                    artifact_kind = "DOCUMENT_PAGE",
                    size_bytes = artifact.sizeBytes,
                    checksum = artifact.sha256,
                    artifact_format_version = artifact.formatVersion,
                    creation_revision = 1L,
                    producer_epoch = artifact.producerEpoch,
                )
                return@transactionWithResult artifact
            }
            if (existing.relative_storage_path != artifact.relativePath
                || existing.artifact_kind != "DOCUMENT_PAGE"
                || existing.size_bytes != artifact.sizeBytes
                || existing.checksum != artifact.sha256
                || existing.artifact_format_version != artifact.formatVersion
                || existing.state !in setOf("STAGED_DURABLE", "COMMITTED")
            ) throw StoreConflict("Repeated PDF page render differs from its durable artifact")
            artifact.copy(producerEpoch = existing.producer_epoch
                ?: throw StoreInvariantViolation("Staged PDF page has no producer epoch"))
        }

    /** Records output bytes only after private immutable publication has completed and synced. */
    fun recordStagedExportFile(saveManifestId: SaveManifestId, file: DurableExportFile) {
        database.transaction {
            if (queries.saveManifestById(saveManifestId.value).executeAsOneOrNull() == null) {
                throw StoreInvariantViolation("Cannot stage export output without its SaveManifest")
            }
            queries.insertStagedArtifact(
                artifact_id = file.artifactId.value,
                relative_storage_path = file.relativePath,
                artifact_kind = "EXPORT_OUTPUT",
                size_bytes = file.sizeBytes,
                checksum = file.sha256,
                artifact_format_version = file.formatVersion,
                creation_revision = 1L,
                producer_epoch = file.producerEpoch,
            )
            addArtifactReferenceChecked(
                artifact_id = file.artifactId.value,
                ref_kind = "SAVE_MANIFEST",
                ref_owner_id = saveManifestId.value,
                protection_class = "SAVE",
            )
        }
    }

    /** Journals a deterministic local output before its first filesystem write/rename. */
    fun beginSaveOutputPublication(saveManifestId: SaveManifestId, outputOrdinal: Int, file: DurableExportFile) {
        database.transaction {
            val manifest = queries.saveManifestById(saveManifestId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Cannot publish export output without its SaveManifest")
            if (queries.savedPdfEditCompositionByManifest(saveManifestId.value).executeAsOneOrNull() != null
                || queries.exportArtifactsBySaveManifest(saveManifestId.value).executeAsList().isNotEmpty()
            ) throw StoreConflict("Only an uncommitted normal SaveManifest can publish this output")
            val dependencies = queries.saveOutputCompositionDependencies(saveManifestId.value).executeAsList()
            if (dependencies.isEmpty() || outputOrdinal !in 0..dependencies.size
                || dependencies.any {
                    it.dependency_kind != "LOCAL_OUTPUT_COMPOSITION"
                        || it.dependency_spec != "frozen-image-version:${it.image_version_id}"
                }
            ) {
                throw StoreInvariantViolation("Output publication ordinal is outside the frozen SaveManifest")
            }
            val extension = if (outputOrdinal == 0) "pdf" else "jpg"
            val expectedId = ArtifactId.forSaveOutput(saveManifestId, outputOrdinal)
            val expectedPath = expectedExportPath(expectedId, extension)
            if (file.artifactId != expectedId || file.relativePath != expectedPath
                || queries.artifactById(expectedId.value).executeAsOneOrNull() != null
            ) throw StoreInvariantViolation("Export publication descriptor differs from deterministic Save output identity")
            queries.insertSaveOutputPublicationIntent(
                artifact_id = file.artifactId.value,
                save_manifest_id = manifest.save_manifest_id,
                output_ordinal = outputOrdinal.toLong(),
                extension = extension,
                relative_storage_path = file.relativePath,
                size_bytes = file.sizeBytes,
                checksum = file.sha256,
                artifact_format_version = file.formatVersion,
                producer_epoch = file.producerEpoch,
            )
        }
    }

    /** Moves a verified published output from its intent row into the staged artifact ledger. */
    fun completeSaveOutputPublication(saveManifestId: SaveManifestId, file: DurableExportFile) {
        database.transaction {
            val intent = queries.saveOutputPublicationIntentByArtifactId(file.artifactId.value).executeAsOneOrNull()
                ?: throw StoreConflict("Export output publication intent is missing")
            if (intent.save_manifest_id != saveManifestId.value
                || intent.relative_storage_path != file.relativePath
                || intent.size_bytes != file.sizeBytes
                || intent.checksum != file.sha256
                || intent.artifact_format_version != file.formatVersion
                || intent.producer_epoch != file.producerEpoch
                || ArtifactId.forSaveOutput(saveManifestId, intent.output_ordinal.toInt()) != file.artifactId
                || (if (intent.output_ordinal == 0L) "pdf" else "jpg") != intent.extension
            ) throw StoreInvariantViolation("Published output differs from its durable publication intent")
            if (queries.artifactById(file.artifactId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("Publication intent ArtifactId is already registered")
            }
            queries.insertStagedArtifact(
                artifact_id = file.artifactId.value,
                relative_storage_path = file.relativePath,
                artifact_kind = "EXPORT_OUTPUT",
                size_bytes = file.sizeBytes,
                checksum = file.sha256,
                artifact_format_version = file.formatVersion,
                creation_revision = 1L,
                producer_epoch = file.producerEpoch,
            )
            addArtifactReferenceChecked(
                artifact_id = file.artifactId.value,
                ref_kind = "SAVE_MANIFEST",
                ref_owner_id = saveManifestId.value,
                protection_class = "SAVE",
            )
            if (queries.deleteSaveOutputPublicationIntent(file.artifactId.value, saveManifestId.value).value != 1L) {
                throw StoreConflict("Export output publication intent changed while completing")
            }
        }
    }

    /**
     * Commits one capture after durable publication. All authoritative metadata and the
     * first protective reference are written in one SQLite transaction.
     */
    fun commitCapture(command: CaptureCommand): CapturedPage {
        val time = clock.read()
        return database.transactionWithResult {
            val workspace = queries.workspaceById(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            queries.openSessionForWorkspace(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace has no open session")
            val openSession = queries.openSessionForWorkspace(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace has no open session")
            val artifact = queries.artifactById(command.artifact.artifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Durable artifact record is missing")

            if (artifact.state != "STAGED_DURABLE"
                || artifact.relative_storage_path != command.artifact.relativePath
                || artifact.size_bytes != command.artifact.sizeBytes
                || artifact.checksum != command.artifact.sha256
                || artifact.artifact_format_version != command.artifact.formatVersion
                || artifact.producer_epoch != command.artifact.producerEpoch
                || artifact.artifact_kind != "DOCUMENT_PAGE"
            ) {
                throw StoreInvariantViolation("Artifact does not match the staged durable capture")
            }
            if (queries.pageById(command.pageId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("Page ID already exists: ${command.pageId.value}")
            }

            val orderIndex = queries.nextVisibleOrder(command.workspaceId.value).executeAsOne()
            queries.insertImageVersion(
                image_version_id = command.imageVersionId.value,
                page_id = command.pageId.value,
                artifact_id = command.artifact.artifactId.value,
                parent_version_id = null,
                coordinate_space = command.coordinateSpace,
                transform_provenance = "capture",
                artifact_format_version = command.artifact.formatVersion,
            )
            queries.insertPage(
                page_id = command.pageId.value,
                workspace_id = command.workspaceId.value,
                workspace_session_id = openSession.workspace_session_id,
                order_index = orderIndex,
                selected = if (command.selected) 1L else 0L,
                select_when_ready = if (command.autoSelectWhenReady && !command.selected) 1L else 0L,
                content_revision = 1L,
                current_image_version_id = command.imageVersionId.value,
                processing_desired_state = command.processingDesiredState,
                source_provenance = command.sourceProvenance,
            )
            val committedRows = queries.commitArtifact(command.artifact.artifactId.value).value
            if (committedRows != 1L) {
                throw StoreInvariantViolation("Artifact was not in STAGED_DURABLE state at commit")
            }
            addArtifactReferenceChecked(
                artifact_id = command.artifact.artifactId.value,
                ref_kind = "IMAGE_VERSION",
                ref_owner_id = command.imageVersionId.value,
                protection_class = "AUTHORITATIVE",
            )
            queries.advanceOrderRevision(command.workspaceId.value)
            if (command.makeActive) {
                queries.setActivePage(command.pageId.value, command.workspaceId.value)
            }
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspace.workspace_id,
            )
            CapturedPage(
                pageId = command.pageId,
                imageVersionId = command.imageVersionId,
                artifactId = ArtifactId.parse(artifact.artifact_id),
                orderIndex = orderIndex,
            )
        }
    }

    fun visiblePageCount(workspaceId: WorkspaceId): Long =
        queries.visiblePages(workspaceId.value).executeAsList().size.toLong()

    fun visiblePages(workspaceId: WorkspaceId): List<StoredPage> =
        workspaceProjection(workspaceId).pages

    fun deletePage(
        workspaceId: WorkspaceId,
        pageId: app.docscan.core.model.PageId,
        expectedContentRevision: Long,
    ): DeleteReceipt {
        require(expectedContentRevision > 0L)
        val time = clock.read()
        val undoUntilWall = Math.addExact(time.wallTimeMillis, DELETE_UNDO_WINDOW_MILLIS)
        val undoUntilMonotonic = Math.addExact(time.monotonicTimeNanos, DELETE_UNDO_WINDOW_NANOS)
        return database.transactionWithResult {
            val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val page = queries.pageById(pageId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Page is missing")
            if (page.workspace_id != workspaceId.value || page.tombstone != 0L) {
                throw StoreInvariantViolation("Page is not visible in this workspace")
            }
            if (page.content_revision != expectedContentRevision) {
                throw StoreConflict("Page changed before Delete")
            }
            val openSession = queries.openSessionForWorkspace(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace has no open session")

            // Only the most recent deletion has a visible Undo action. Earlier tombstones remain protected.
            clearPreviousDeleteUndoRecords(workspaceId.value)
            page.undo_image_version_id?.let { undoVersion ->
                if (queries.deleteArtifactReference(undoVersion).value != 1L) {
                    throw StoreInvariantViolation("Deleted Page Undo artifact reference is missing")
                }
            }
            val wasActive = workspace.active_page_id == page.page_id
            if (queries.markPageDeleted(
                    tombstone_at_wall = time.wallTimeMillis,
                    page_id = pageId.value,
                    workspace_id = workspaceId.value,
                    content_revision = expectedContentRevision,
                ).value != 1L
            ) throw StoreConflict("Page changed while Delete was committing")
            queries.insertDeleteUndo(
                page_id = page.page_id,
                workspace_id = workspaceId.value,
                previous_order_index = page.order_index,
                was_selected = page.selected,
                was_active = if (wasActive) 1L else 0L,
                deleted_wall = time.wallTimeMillis,
                undo_until_wall = undoUntilWall,
                undo_until_monotonic = undoUntilMonotonic,
                monotonic_epoch_token = time.monotonicEpochToken,
                undo_workspace_session_id = openSession.workspace_session_id,
                undo_batch_id = null,
            )
            queries.visiblePages(workspaceId.value).executeAsList().forEachIndexed { index, remaining ->
                if (queries.setPageOrderAnyVisible(index.toLong(), remaining.page_id, workspaceId.value).value != 1L) {
                    throw StoreInvariantViolation("Visible page order changed during Delete")
                }
            }
            if (wasActive) {
                val remaining = queries.visiblePages(workspaceId.value).executeAsList()
                val replacement = remaining.firstOrNull { it.order_index >= page.order_index } ?: remaining.lastOrNull()
                if (replacement == null) {
                    queries.clearActivePage(workspaceId.value, page.page_id)
                } else {
                    queries.setActivePage(replacement.page_id, workspaceId.value)
                }
            }
            queries.advanceOrderRevision(workspaceId.value)
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
            DeleteReceipt(pageId, undoUntilWall, wasActive)
        }
    }

    /** Atomically tombstones visible Pages with one all-or-nothing Undo receipt. */
    fun deletePages(
        workspaceId: WorkspaceId,
        expectedPages: List<DeletePageExpectation>,
    ): DeleteBatchReceipt {
        require(expectedPages.isNotEmpty()) { "Delete batch must contain at least one Page" }
        require(expectedPages.map { it.pageId }.distinct().size == expectedPages.size) {
            "Delete batch contains duplicate Page IDs"
        }
        val time = clock.read()
        return database.transactionWithResult {
            tombstoneVisiblePagesWithinTransaction(workspaceId, expectedPages, time)
        }
    }

    /** Caller owns the SQLite transaction; shared by explicit Delete and successful Save. */
    private fun tombstoneVisiblePagesWithinTransaction(
        workspaceId: WorkspaceId,
        expectedPages: List<DeletePageExpectation>,
        time: TimeReading,
    ): DeleteBatchReceipt {
        require(expectedPages.isNotEmpty()) { "Delete batch must contain at least one Page" }
        require(expectedPages.map { it.pageId }.distinct().size == expectedPages.size) {
            "Delete batch contains duplicate Page IDs"
        }
        val undoUntilWall = Math.addExact(time.wallTimeMillis, DELETE_UNDO_WINDOW_MILLIS)
        val undoUntilMonotonic = Math.addExact(time.monotonicTimeNanos, DELETE_UNDO_WINDOW_NANOS)
        val batchId = DeleteBatchId.new()
        val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Workspace is missing")
        val openSession = queries.openSessionForWorkspace(workspaceId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Workspace has no open session")
        val originalVisible = queries.visiblePages(workspaceId.value).executeAsList()
        val visibleById = originalVisible.associateBy { it.page_id }
        val targets = expectedPages.map { expected ->
            val page = visibleById[expected.pageId.value]
                ?: throw StoreInvariantViolation("Page is not visible in this workspace")
            if (page.content_revision != expected.contentRevision) {
                throw StoreConflict("Page changed before batch Delete")
            }
            page
        }.sortedBy { it.order_index }

        clearPreviousDeleteUndoRecords(workspaceId.value)
        targets.forEach { page ->
            page.undo_image_version_id?.let { undoVersion ->
                if (queries.deleteArtifactReference(undoVersion).value != 1L) {
                    throw StoreInvariantViolation("Deleted Page Undo artifact reference is missing")
                }
            }
        }
        targets.forEach { page ->
            val wasActive = workspace.active_page_id == page.page_id
            if (queries.markPageDeleted(
                    tombstone_at_wall = time.wallTimeMillis,
                    page_id = page.page_id,
                    workspace_id = workspaceId.value,
                    content_revision = page.content_revision,
                ).value != 1L
            ) throw StoreConflict("Page changed while batch Delete was committing")
            queries.insertDeleteUndo(
                page_id = page.page_id,
                workspace_id = workspaceId.value,
                previous_order_index = page.order_index,
                was_selected = page.selected,
                was_active = if (wasActive) 1L else 0L,
                deleted_wall = time.wallTimeMillis,
                undo_until_wall = undoUntilWall,
                undo_until_monotonic = undoUntilMonotonic,
                monotonic_epoch_token = time.monotonicEpochToken,
                undo_workspace_session_id = openSession.workspace_session_id,
                undo_batch_id = batchId.value,
            )
        }
        val remaining = queries.visiblePages(workspaceId.value).executeAsList()
        remaining.forEachIndexed { index, page ->
            if (queries.setPageOrderAnyVisible(index.toLong(), page.page_id, workspaceId.value).value != 1L) {
                throw StoreInvariantViolation("Visible order changed during batch Delete")
            }
        }
        val activePageId = workspace.active_page_id
        if (activePageId != null && targets.any { it.page_id == activePageId }) {
            val priorActiveOrder = visibleById[activePageId]?.order_index ?: 0L
            val replacement = remaining.firstOrNull { it.order_index >= priorActiveOrder } ?: remaining.lastOrNull()
            if (replacement == null) queries.clearActivePage(workspaceId.value, activePageId)
            else queries.setActivePage(replacement.page_id, workspaceId.value)
        }
        queries.advanceOrderRevision(workspaceId.value)
        queries.updateWorkspaceActivity(
            last_user_activity_wall = time.wallTimeMillis,
            last_user_activity_monotonic = time.monotonicTimeNanos,
            monotonic_epoch_token = time.monotonicEpochToken,
            workspace_id = workspaceId.value,
        )
        return DeleteBatchReceipt(batchId, targets.map { PageId.parse(it.page_id) }, undoUntilWall)
    }

    /** Restores every Page in a batch atomically; partial batch Undo is not supported. */
    fun undoDeleteBatch(workspaceId: WorkspaceId, batchId: DeleteBatchId) {
        val time = clock.read()
        database.transaction {
            val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val undos = queries.deleteUndosByBatch(batchId.value, workspaceId.value).executeAsList()
            if (undos.isEmpty()) throw StoreConflict("Delete batch Undo is no longer available")
            val openSession = queries.openSessionForWorkspace(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreConflict("Workspace session is no longer open")
            if (undos.any { it.undo_workspace_session_id != openSession.workspace_session_id }) {
                throw StoreConflict("Delete batch Undo belongs to an earlier workspace session")
            }
            undos.forEach { undo ->
                val page = queries.pageById(undo.page_id).executeAsOneOrNull()
                    ?: throw StoreConflict("Deleted Page is missing")
                if (page.workspace_id != workspaceId.value || page.tombstone != 1L) {
                    throw StoreConflict("A Page in the delete batch is no longer deleted")
                }
                val withinUndoWindow = if (time.monotonicEpochToken == undo.monotonic_epoch_token) {
                    time.monotonicTimeNanos >= undo.undo_until_monotonic - DELETE_UNDO_WINDOW_NANOS
                        && time.monotonicTimeNanos <= undo.undo_until_monotonic
                } else {
                    time.wallTimeMillis >= undo.deleted_wall && time.wallTimeMillis <= undo.undo_until_wall
                }
                if (!withinUndoWindow || time.wallTimeMillis < undo.deleted_wall) {
                    throw StoreConflict("Delete batch Undo window has expired or time is uncertain")
                }
            }
            undos.forEach { undo ->
                val visible = queries.visiblePages(workspaceId.value).executeAsList()
                val restoreIndex = undo.previous_order_index.coerceAtMost(visible.size.toLong())
                visible.filter { it.order_index >= restoreIndex }.asReversed().forEach { laterPage ->
                    if (queries.setPageOrderAnyVisible(
                            laterPage.order_index + 1L,
                            laterPage.page_id,
                            workspaceId.value,
                        ).value != 1L
                    ) throw StoreInvariantViolation("Could not make room for restored Page")
                }
                if (queries.restoreDeletedPage(
                        order_index = restoreIndex,
                        selected = undo.was_selected,
                        page_id = undo.page_id,
                        workspace_id = workspaceId.value,
                    ).value != 1L
                ) throw StoreConflict("Deleted Page could not be restored")
                queries.deleteUndoRecord(undo.page_id, workspaceId.value)
                if (undo.was_active != 0L) queries.setActivePage(undo.page_id, workspaceId.value)
            }
            if (workspace.active_page_id == null && undos.none { it.was_active != 0L }) {
                queries.visiblePages(workspaceId.value).executeAsList().firstOrNull()?.let {
                    queries.setActivePage(it.page_id, workspaceId.value)
                }
            }
            queries.advanceOrderRevision(workspaceId.value)
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
        }
    }

    private fun clearPreviousDeleteUndoRecords(workspaceId: String) {
        queries.deleteUndosForWorkspace(workspaceId).executeAsList().forEach { undo ->
            val page = queries.pageById(undo.page_id).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Delete Undo points to a missing Page")
            page.undo_image_version_id?.let { undoVersion ->
                if (queries.deleteArtifactReference(undoVersion).value != 1L) {
                    throw StoreInvariantViolation("Superseded Delete Undo artifact reference is missing")
                }
            }
        }
        queries.clearPreviousDeleteUndo(workspaceId)
    }

    /** Every reference write is serialized with cleanup claims by the SQLite write transaction. */
    private fun addArtifactReferenceChecked(
        artifact_id: String,
        ref_kind: String,
        ref_owner_id: String,
        protection_class: String,
    ) {
        val artifact = queries.artifactById(artifact_id).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Cannot protect a missing artifact")
        if (artifact.state !in setOf("STAGED_DURABLE", "COMMITTED") || artifact.storage_state != "PRESENT") {
            throw StoreConflict("Artifact is being deleted or is no longer present")
        }
        queries.addArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class)
    }

    fun undoDelete(workspaceId: WorkspaceId, pageId: app.docscan.core.model.PageId) {
        val time = clock.read()
        database.transaction {
            val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val page = queries.pageById(pageId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Deleted Page is missing")
            val undo = queries.deleteUndoByPage(pageId.value, workspaceId.value).executeAsOneOrNull()
                ?: throw StoreConflict("Delete Undo is no longer available")
            if (undo.undo_batch_id != null) {
                throw StoreConflict("This Page belongs to a multi-Page Delete; restore the complete batch")
            }
            if (page.workspace_id != workspaceId.value || page.tombstone != 1L) {
                throw StoreConflict("Page is no longer deleted")
            }
            val openSession = queries.openSessionForWorkspace(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreConflict("Workspace session is no longer open")
            if (openSession.workspace_session_id != undo.undo_workspace_session_id) {
                throw StoreConflict("Delete Undo belongs to an earlier workspace session")
            }
            val withinUndoWindow = if (time.monotonicEpochToken == undo.monotonic_epoch_token) {
                time.monotonicTimeNanos >= undo.undo_until_monotonic - DELETE_UNDO_WINDOW_NANOS
                    && time.monotonicTimeNanos <= undo.undo_until_monotonic
            } else {
                time.wallTimeMillis >= undo.deleted_wall && time.wallTimeMillis <= undo.undo_until_wall
            }
            if (!withinUndoWindow || time.wallTimeMillis < undo.deleted_wall) {
                throw StoreConflict("Delete Undo window has expired or time is uncertain")
            }
            val visible = queries.visiblePages(workspaceId.value).executeAsList()
            val restoreIndex = undo.previous_order_index.coerceAtMost(visible.size.toLong())
            visible.filter { it.order_index >= restoreIndex }.asReversed().forEach { laterPage ->
                if (queries.setPageOrderAnyVisible(
                        laterPage.order_index + 1L,
                        laterPage.page_id,
                        workspaceId.value,
                    ).value != 1L
                ) throw StoreInvariantViolation("Could not make room for restored Page")
            }
            if (queries.restoreDeletedPage(
                    order_index = restoreIndex,
                    selected = undo.was_selected,
                    page_id = pageId.value,
                    workspace_id = workspaceId.value,
                ).value != 1L
            ) throw StoreConflict("Deleted Page could not be restored")
            if (undo.was_active != 0L || workspace.active_page_id == null) {
                queries.setActivePage(pageId.value, workspaceId.value)
            }
            queries.deleteUndoRecord(pageId.value, workspaceId.value)
            queries.advanceOrderRevision(workspaceId.value)
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
        }
    }

    fun workspaceProjection(workspaceId: WorkspaceId): WorkspaceProjection = database.transactionWithResult {
        val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Workspace is missing")
        val openSessionId = queries.openSessionForWorkspace(workspaceId.value).executeAsOneOrNull()
            ?.workspace_session_id
        val pages = queries.visiblePages(workspaceId.value).executeAsList().map { page ->
            val versionId = ImageVersionId.parse(page.current_image_version_id)
            val version = queries.imageVersionById(versionId.value).executeAsOne()
            val artifact = queries.artifactById(version.artifact_id).executeAsOne()
            StoredPage(
                pageId = app.docscan.core.model.PageId.parse(page.page_id),
                imageVersionId = versionId,
                artifactRelativePath = artifact.relative_storage_path,
                orderIndex = page.order_index,
                selected = page.selected != 0L,
                contentRevision = page.content_revision,
                canUndoImage = page.undo_image_version_id != null
                    && page.undo_workspace_session_id == openSessionId,
                recognizedText = page.current_recognition_id?.let { recognitionId ->
                    queries.recognitionById(recognitionId).executeAsOneOrNull()?.payload?.let { raw ->
                        try {
                            RecognitionPayloadCodec.decode(raw).text
                        } catch (_: Exception) {
                            raw
                        }
                    }
                },
                artifactState = artifact.state,
            )
        }
        WorkspaceProjection(
            orderRevision = workspace.order_revision,
            activePageId = workspace.active_page_id?.let(app.docscan.core.model.PageId::parse),
            pages = pages,
        )
    }

    /** Bounded read-only audit of known ArtifactReference owner/artifact/protection links. */
    fun artifactReferenceOwnerIntegrityFindings(limit: Long): List<ArtifactReferenceIntegrityFinding> {
        require(limit in 1L..MAX_ARTIFACT_REFERENCE_FINDINGS)
        val findings = queries.artifactReferenceOwnerIntegrityFindings(MAX_ARTIFACT_REFERENCE_FINDINGS)
            .executeAsList().map { row ->
                ArtifactReferenceIntegrityFinding(
                    code = row.finding_code,
                    artifactId = ArtifactId.parse(row.artifact_id),
                    refKind = row.ref_kind,
                    refOwnerId = row.ref_owner_id,
                )
            } + queries.publicRecentDocumentIntegrityFindings(MAX_ARTIFACT_REFERENCE_FINDINGS)
            .executeAsList().map { row ->
                ArtifactReferenceIntegrityFinding(
                    code = row.finding_code,
                    artifactId = ArtifactId.parse(row.artifact_id),
                    refKind = row.ref_kind,
                    refOwnerId = row.ref_owner_id,
                )
            }
        return findings.sortedWith(
            compareBy<ArtifactReferenceIntegrityFinding>({ it.code }, { it.artifactId.value }, { it.refKind }, { it.refOwnerId }),
        ).take(limit.toInt())
    }

    /** Read-only bounded cross-record check; it never repairs or rewrites workspace state. */
    fun workspaceImageReferenceIntegrityFindings(
        workspaceId: WorkspaceId,
        limit: Long = MAX_WORKSPACE_INTEGRITY_FINDINGS,
    ): List<WorkspaceIntegrityFinding> {
        require(limit in 1L..MAX_WORKSPACE_INTEGRITY_FINDINGS)
        return queries.workspaceImageReferenceIntegrityFindings(
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            workspaceId.value,
            limit,
        ).executeAsList().map { row ->
            WorkspaceIntegrityFinding(row.finding_code, row.page_id)
        }.let { findings ->
            if (findings.size.toLong() >= limit) findings
            else findings + queries.workspaceActivePageIntegrityFinding(workspaceId.value)
                .executeAsOneOrNull()
                ?.let { listOf(WorkspaceIntegrityFinding(it.finding_code, it.page_id)) }
                .orEmpty()
        }
    }

    /**
     * Freezes the currently selected, visible Pages and protects their exact source artifacts.
     * The transaction performs metadata reads/writes only; callers do all image/provider I/O later.
     */
    fun freezeSelectedPagesForSave(
        workspaceId: WorkspaceId,
        saveManifestId: SaveManifestId = SaveManifestId.new(),
    ): SaveManifestSnapshot = database.transactionWithResult {
        if (queries.saveManifestById(saveManifestId.value).executeAsOneOrNull() != null) {
            throw StoreConflict("SaveManifest ID already exists: ${saveManifestId.value}")
        }
        queries.workspaceById(workspaceId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Workspace is missing")
        val selected = queries.selectedVisiblePagesForSave(workspaceId.value).executeAsList()
        if (selected.isEmpty()) throw StoreInvariantViolation("Save requires at least one selected visible Page")

        val frozenPages = selected.map { page ->
            val version = queries.imageVersionById(page.current_image_version_id).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Selected Page has no current ImageVersion")
            if (version.page_id != page.page_id) throw StoreInvariantViolation("ImageVersion belongs to another Page")
            val artifact = queries.artifactById(version.artifact_id).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Selected Page source Artifact is missing")
            if (artifact.state != "COMMITTED" || artifact.size_bytes <= 0L
                || artifact.checksum?.matches(Regex("[0-9a-f]{64}")) != true
            ) {
                throw StoreInvariantViolation("Selected Page source Artifact is not validated and committed")
            }
            val recognition = page.current_recognition_id?.let { recognitionId ->
                val recognition = queries.recognitionById(recognitionId).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Current Recognition is missing")
                if (recognition.page_id != page.page_id || recognition.image_version_id != version.image_version_id) {
                    throw StoreInvariantViolation("Current Recognition does not match the frozen Page version")
                }
                RecognitionPayloadCodec.decode(recognition.payload)
            }
            FrozenSavePage(
                pageId = PageId.parse(page.page_id),
                imageVersionId = ImageVersionId.parse(version.image_version_id),
                artifactId = ArtifactId.parse(artifact.artifact_id),
                artifactRelativePath = artifact.relative_storage_path,
                artifactSizeBytes = artifact.size_bytes,
                artifactSha256 = artifact.checksum,
                artifactFormatVersion = artifact.artifact_format_version,
                contentRevision = page.content_revision,
                orderIndex = page.order_index,
                recognizedText = recognition?.text,
                recognizedGeometry = recognition?.geometry,
                recognizedLayout = recognition?.layout,
            )
        }
        val payload = frozenPages.toCanonicalSavePayload(workspaceId)
        queries.insertSaveManifest(
            save_manifest_id = saveManifestId.value,
            workspace_id = workspaceId.value,
            manifest_format_version = 1L,
            frozen_payload = payload,
        )
        frozenPages.forEach { page ->
            addArtifactReferenceChecked(
                artifact_id = page.artifactId.value,
                ref_kind = "SAVE_MANIFEST",
                ref_owner_id = saveManifestId.value,
                protection_class = "SAVE",
            )
        }
        SaveManifestSnapshot(saveManifestId, workspaceId, payload, frozenPages)
    }

    /** Records immutable source and page order for an isolated saved-PDF edit export. */
    fun beginSavedPdfEditManifest(command: SavedPdfEditManifestCommand) {
        database.transaction {
            if (queries.saveManifestById(command.saveManifestId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("SaveManifest ID already exists: ${command.saveManifestId.value}")
            }
            queries.workspaceById(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val recent = queries.recentDocumentByExport(command.sourceExportArtifactId.value).executeAsOneOrNull()
                ?: throw StoreConflict("Saved PDF is no longer in the recent-document index")
            if (recent.page_count != command.sourcePageCount) {
                throw StoreConflict("Saved PDF page count changed before the edit was committed")
            }
            val sourceExport = queries.exportArtifactById(command.sourceExportArtifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Source ExportArtifact is missing")
            val sourceArtifact = queries.artifactById(command.sourcePdfArtifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Source PDF Artifact is missing")
            if (queries.artifactReferenceForExportOutput(
                    command.sourcePdfArtifactId.value, command.sourceExportArtifactId.value,
                ).executeAsOne() != 1L
                || sourceArtifact.state != "COMMITTED"
                || sourceArtifact.storage_state != "PRESENT"
                || sourceArtifact.relative_storage_path.endsWith(".pdf", ignoreCase = true).not()
                || sourceArtifact.checksum != command.sourcePdfSha256
            ) throw StoreConflict("Source PDF is not a committed member of the selected saved document")

            val indexes = command.orderedSourcePageIndexes.joinToString(separator = ",", prefix = "[", postfix = "]")
            val payload = "{\"kind\":\"SAVED_PDF_EDIT\",\"sourceExportArtifactId\":\"${command.sourceExportArtifactId.value}\",\"sourcePdfArtifactId\":\"${command.sourcePdfArtifactId.value}\",\"sourcePdfSha256\":\"${command.sourcePdfSha256}\",\"sourcePageCount\":${command.sourcePageCount},\"outputPageOrder\":$indexes}"
            queries.insertSaveManifest(
                command.saveManifestId.value,
                command.workspaceId.value,
                1L,
                payload,
            )
            queries.insertSavedPdfEditComposition(
                command.saveManifestId.value,
                command.sourceExportArtifactId.value,
                command.sourcePdfArtifactId.value,
                command.sourcePdfSha256,
                command.sourcePageCount,
                indexes,
            )
            addArtifactReferenceChecked(
                artifact_id = command.sourcePdfArtifactId.value,
                ref_kind = "SAVE_MANIFEST",
                ref_owner_id = command.saveManifestId.value,
                protection_class = "SAVE",
            )
        }
    }

    /** Removes edit manifests interrupted before their atomic ExportArtifact commit. */
    fun abandonIncompleteSavedPdfEditManifests(limit: Long = 4L): Int {
        require(limit > 0L)
        return database.transactionWithResult {
            val manifests = queries.incompleteSavedPdfEditManifests(limit).executeAsList()
            manifests.forEach { edit ->
                val manifest = queries.saveManifestById(edit.save_manifest_id).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Interrupted saved-PDF edit has no SaveManifest")
                if (queries.exportArtifactsBySaveManifest(edit.save_manifest_id).executeAsList().isNotEmpty()) {
                    throw StoreConflict("Saved-PDF edit became committed during recovery")
                }
                val stagedArtifacts = queries.saveManifestExportArtifacts(edit.save_manifest_id).executeAsList()
                queries.deleteSaveManifestArtifactReferences(edit.save_manifest_id)
                stagedArtifacts.filter { it.state == "STAGED_DURABLE" }.forEach { artifact ->
                    if (queries.referenceCountForArtifact(artifact.artifact_id).executeAsOne() == 0L) {
                        queries.quarantineArtifact(artifact.artifact_id)
                    }
                }
                queries.deleteSavedPdfEditComposition(edit.save_manifest_id)
                if (queries.deleteSaveManifest(edit.save_manifest_id, manifest.workspace_id).value != 1L) {
                    throw StoreConflict("Interrupted saved-PDF edit manifest changed during recovery")
                }
            }
            manifests.size
        }
    }

    /** Drops a frozen manifest only before any external output artifact/attempt exists. */
    fun cancelUnstartedSaveManifest(workspaceId: WorkspaceId, saveManifestId: SaveManifestId) {
        database.transaction {
            val manifest = queries.saveManifestById(saveManifestId.value).executeAsOneOrNull()
                ?: throw StoreConflict("SaveManifest is missing: ${saveManifestId.value}")
            if (manifest.workspace_id != workspaceId.value) {
                throw StoreInvariantViolation("SaveManifest belongs to another Workspace")
            }
            if (queries.exportArtifactsBySaveManifest(saveManifestId.value).executeAsList().isNotEmpty()) {
                throw StoreConflict("SaveManifest has an external output and cannot be cancelled as unstarted")
            }
            if (queries.stagedExportOutputReferencesForSave(saveManifestId.value).executeAsOne() > 0L) {
                throw StoreConflict("SaveManifest has published output bytes and cannot be cancelled")
            }
            if (queries.saveOutputCompositionStarted(saveManifestId.value).executeAsOne() > 0L) {
                throw StoreConflict("SaveManifest output composition has started and cannot be cancelled")
            }
            queries.deleteSavedPdfEditComposition(saveManifestId.value)
            queries.deleteSaveManifestArtifactReferences(saveManifestId.value)
            if (queries.deleteSaveManifest(saveManifestId.value, workspaceId.value).value != 1L) {
                throw StoreConflict("SaveManifest changed while cancelling")
            }
        }
    }

    /** Durable cancellation barrier before the first decode, encode or PDF/JPG write begins. */
    fun beginLocalOutputComposition(snapshot: SaveManifestSnapshot) {
        database.transaction {
            val manifest = queries.saveManifestById(snapshot.saveManifestId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("SaveManifest is missing")
            if (manifest.workspace_id != snapshot.workspaceId.value || manifest.frozen_payload != snapshot.frozenPayload) {
                throw StoreInvariantViolation("Composition snapshot differs from its persisted SaveManifest")
            }
            if (queries.saveOutputCompositionStarted(snapshot.saveManifestId.value).executeAsOne() > 0L) {
                throw StoreConflict("Local output composition already started for this SaveManifest")
            }
            snapshot.pages.forEach { page ->
                queries.insertSaveDependencyResult(
                    save_dependency_result_id = java.util.UUID.randomUUID().toString(),
                    save_manifest_id = snapshot.saveManifestId.value,
                    image_version_id = page.imageVersionId.value,
                    dependency_kind = "LOCAL_OUTPUT_COMPOSITION",
                    dependency_spec = "frozen-image-version:${page.imageVersionId.value}",
                )
            }
        }
    }

    /**
     * Rolls back only a failed in-process composition barrier. The frozen SaveManifest and its
     * source protection stay durable so the same snapshot can be retried or explicitly cancelled.
     */
    fun abortLocalOutputComposition(snapshot: SaveManifestSnapshot) {
        database.transaction {
            val manifest = queries.saveManifestById(snapshot.saveManifestId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("SaveManifest is missing")
            if (manifest.workspace_id != snapshot.workspaceId.value || manifest.frozen_payload != snapshot.frozenPayload) {
                throw StoreInvariantViolation("Composition snapshot differs from its persisted SaveManifest")
            }
            if (queries.exportArtifactsBySaveManifest(snapshot.saveManifestId.value).executeAsList().isNotEmpty()) {
                throw StoreConflict("Local output composition has a committed ExportArtifact")
            }
            if (queries.stagedExportOutputReferencesForSave(snapshot.saveManifestId.value).executeAsOne() > 0L) {
                throw StoreConflict("Local output composition has staged output bytes")
            }
            if (queries.saveOutputPublicationIntentCount(snapshot.saveManifestId.value).executeAsOne() > 0L) {
                throw StoreConflict("Local output composition has an active output publication intent")
            }
            val dependencies = queries.saveOutputCompositionDependencies(snapshot.saveManifestId.value).executeAsList()
            if (dependencies.isEmpty()) return@transaction
            if (dependencies.size != snapshot.pages.size
                || dependencies.map { it.image_version_id } != snapshot.pages.map { it.imageVersionId.value }
                || dependencies.any { it.dependency_spec != "frozen-image-version:${it.image_version_id}" }
            ) throw StoreInvariantViolation("Local output composition barrier differs from its frozen page set")
            val removed = queries.deleteLocalOutputCompositionBarrier(snapshot.saveManifestId.value).value
            if (removed != dependencies.size.toLong()) {
                throw StoreConflict("Local output composition barrier changed while aborting")
            }
        }
    }

    /** Bounded discovery for startup cleanup of interrupted, pre-output local compositions. */
    fun incompleteLocalOutputCompositionManifests(limit: Long = 4L): List<SaveManifestId> {
        require(limit > 0L)
        return queries.incompleteLocalOutputCompositionManifests(limit).executeAsList().map(SaveManifestId::parse)
    }

    /** Output identities for one startup-recovery candidate; no filesystem work is performed here. */
    fun stagedExportOutputsForInterruptedSave(saveManifestId: SaveManifestId): List<RecoveryArtifact> {
        val manifest = queries.saveManifestById(saveManifestId.value).executeAsOneOrNull()
            ?: throw StoreConflict("Interrupted SaveManifest is missing")
        if (queries.savedPdfEditCompositionByManifest(saveManifestId.value).executeAsOneOrNull() != null
            || queries.exportArtifactsBySaveManifest(saveManifestId.value).executeAsList().isNotEmpty()
        ) throw StoreConflict("SaveManifest is not an interrupted normal local composition")
        val dependencies = queries.saveOutputCompositionDependencies(saveManifestId.value).executeAsList()
        if (dependencies.isEmpty() || dependencies.any {
                it.dependency_kind != "LOCAL_OUTPUT_COMPOSITION"
                    || it.dependency_spec != "frozen-image-version:${it.image_version_id}"
            }
        ) throw StoreInvariantViolation("Interrupted SaveManifest composition barrier is malformed")
        val frozenArtifactIds = dependencies.map { dependency ->
            queries.imageVersionById(dependency.image_version_id).executeAsOneOrNull()?.artifact_id
                ?: throw StoreInvariantViolation("Interrupted SaveManifest references a missing ImageVersion")
        }.distinct().sorted()
        val outputs = queries.stagedExportOutputsForSave(saveManifestId.value).executeAsList()
        if (outputs.any { output ->
                output.state != "STAGED_DURABLE" || output.storage_state != "PRESENT"
                    || queries.artifactReferenceCount(output.artifact_id).executeAsOne() != 1L
            }
        ) throw StoreConflict("Interrupted SaveManifest has a non-private or multiply referenced staged output")
        val references = queries.saveManifestArtifactReferences(saveManifestId.value).executeAsList()
        val outputIds = outputs.map { it.artifact_id }.sorted()
        if (references.any { it.protection_class != "SAVE" }
            || references.filter { it.artifact_id !in outputIds }.map { it.artifact_id }.sorted() != frozenArtifactIds
            || outputs.any { output -> references.none {
                it.artifact_id == output.artifact_id && it.protection_class == "SAVE"
            } }
        ) throw StoreInvariantViolation("Interrupted SaveManifest references differ from its composition barrier")
        return outputs.map {
            RecoveryArtifact(
                recoverySequence = it.recovery_sequence,
                artifactId = ArtifactId.parse(it.artifact_id),
                relativePath = it.relative_storage_path,
                sizeBytes = it.size_bytes,
                sha256 = it.checksum,
                state = it.state,
            )
        }
    }

    fun outputPublicationIntentsForInterruptedSave(
        saveManifestId: SaveManifestId,
    ): List<ExportOutputPublicationIntentSnapshot> {
        val manifest = queries.saveManifestById(saveManifestId.value).executeAsOneOrNull()
            ?: throw StoreConflict("Interrupted SaveManifest is missing")
        if (queries.savedPdfEditCompositionByManifest(saveManifestId.value).executeAsOneOrNull() != null
            || queries.exportArtifactsBySaveManifest(saveManifestId.value).executeAsList().isNotEmpty()
        ) throw StoreConflict("SaveManifest is not an interrupted normal local composition")
        val dependencies = queries.saveOutputCompositionDependencies(saveManifestId.value).executeAsList()
        if (dependencies.isEmpty() || dependencies.any {
                it.dependency_kind != "LOCAL_OUTPUT_COMPOSITION"
                    || it.dependency_spec != "frozen-image-version:${it.image_version_id}"
            }
        ) throw StoreInvariantViolation("Interrupted SaveManifest composition barrier is malformed")
        val intents = queries.saveOutputPublicationIntentsForRecovery(saveManifestId.value).executeAsList()
        if (intents.map { it.output_ordinal }.distinct().size != intents.size) {
            throw StoreInvariantViolation("Interrupted SaveManifest has duplicate output publication positions")
        }
        intents.forEach { intent ->
            val ordinal = intent.output_ordinal.toInt()
            val id = ArtifactId.forSaveOutput(SaveManifestId.parse(manifest.save_manifest_id), ordinal)
            val extension = if (ordinal == 0) "pdf" else "jpg"
            if (ordinal !in 0..dependencies.size || intent.artifact_id != id.value
                || intent.extension != extension || intent.relative_storage_path != expectedExportPath(id, extension)
                || intent.size_bytes <= 0L || !intent.checksum.matches(Regex("[0-9a-f]{64}"))
                || intent.artifact_format_version <= 0L || intent.producer_epoch.isBlank()
                || queries.artifactById(intent.artifact_id).executeAsOneOrNull() != null
            ) throw StoreInvariantViolation("Interrupted Save publication intent is malformed or already registered")
        }
        return intents.map {
            ExportOutputPublicationIntentSnapshot(
                artifactId = ArtifactId.parse(it.artifact_id),
                saveManifestId = SaveManifestId.parse(it.save_manifest_id),
                outputOrdinal = it.output_ordinal.toInt(),
                extension = it.extension,
                relativePath = it.relative_storage_path,
                sizeBytes = it.size_bytes,
                sha256 = it.checksum,
                formatVersion = it.artifact_format_version,
                producerEpoch = it.producer_epoch,
            )
        }
    }

    fun hasCommittedNormalLocalSave(saveManifestId: SaveManifestId): Boolean =
        queries.hasCommittedNormalLocalSave(saveManifestId.value).executeAsOne() > 0L

    /**
     * Abandons one interrupted normal Save after its private candidate files have been removed and
     * synced. Source references are released only when they exactly match the barrier's frozen
     * ImageVersion artifacts. Filesystem work is deliberately performed by the caller, outside SQL.
     */
    fun abandonInterruptedLocalOutputComposition(
        saveManifestId: SaveManifestId,
        deletedOutputArtifactIds: List<ArtifactId> = emptyList(),
        cleanedPublicationIntentArtifactIds: List<ArtifactId> = emptyList(),
    ) {
        database.transaction {
            val manifest = queries.saveManifestById(saveManifestId.value).executeAsOneOrNull()
                ?: throw StoreConflict("Interrupted SaveManifest is missing: ${saveManifestId.value}")
            if (queries.savedPdfEditCompositionByManifest(saveManifestId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("Saved-PDF edit manifests use their dedicated recovery path")
            }
            if (queries.exportArtifactsBySaveManifest(saveManifestId.value).executeAsList().isNotEmpty()) {
                throw StoreConflict("SaveManifest already has a committed ExportArtifact")
            }
            val dependencies = queries.saveOutputCompositionDependencies(saveManifestId.value).executeAsList()
            if (dependencies.isEmpty()
                || dependencies.map { it.image_version_id }.distinct().size != dependencies.size
                || dependencies.any {
                    it.dependency_kind != "LOCAL_OUTPUT_COMPOSITION"
                        || it.dependency_spec != "frozen-image-version:${it.image_version_id}"
                }
            ) throw StoreInvariantViolation("Interrupted SaveManifest composition barrier is malformed")
            val frozenArtifactIds = dependencies.map { dependency ->
                queries.imageVersionById(dependency.image_version_id).executeAsOneOrNull()?.artifact_id
                    ?: throw StoreInvariantViolation("Interrupted SaveManifest references a missing ImageVersion")
            }.distinct().sorted()
            val references = queries.saveManifestArtifactReferences(saveManifestId.value).executeAsList()
            val stagedOutputs = queries.stagedExportOutputsForSave(saveManifestId.value).executeAsList()
            val intents = queries.saveOutputPublicationIntentsForRecovery(saveManifestId.value).executeAsList()
            if (stagedOutputs.any { output ->
                    output.state != "STAGED_DURABLE" || output.storage_state != "PRESENT"
                        || queries.artifactReferenceCount(output.artifact_id).executeAsOne() != 1L
                }
            ) throw StoreConflict("Interrupted SaveManifest has a non-private or multiply referenced staged output")
            val outputIds = stagedOutputs.map { it.artifact_id }.sorted()
            if (deletedOutputArtifactIds.map { it.value }.sorted() != outputIds) {
                throw StoreConflict("Verified deleted output identities differ from staged SaveManifest outputs")
            }
            intents.forEach { intent ->
                val ordinal = intent.output_ordinal.toInt()
                val id = ArtifactId.forSaveOutput(saveManifestId, ordinal)
                val extension = if (ordinal == 0) "pdf" else "jpg"
                if (ordinal !in 0..dependencies.size || intent.artifact_id != id.value
                    || intent.extension != extension || intent.relative_storage_path != expectedExportPath(id, extension)
                    || queries.artifactById(intent.artifact_id).executeAsOneOrNull() != null
                ) throw StoreInvariantViolation("Interrupted publication intent changed or is malformed")
            }
            if (cleanedPublicationIntentArtifactIds.map { it.value }.sorted()
                != intents.map { it.artifact_id }.sorted()
            ) throw StoreConflict("Cleaned publication intent identities differ from interrupted SaveManifest")
            val sourceReferences = references.filter { it.artifact_id !in outputIds }
            if (references.any { it.protection_class != "SAVE" }
                || sourceReferences.map { it.artifact_id }.sorted() != frozenArtifactIds
                || stagedOutputs.any { output -> references.none {
                    it.artifact_id == output.artifact_id && it.protection_class == "SAVE"
                } }
            ) throw StoreInvariantViolation("Interrupted SaveManifest references differ from its composition barrier")
            val removed = queries.deleteLocalOutputCompositionBarrier(saveManifestId.value).value
            if (removed != dependencies.size.toLong()) {
                throw StoreConflict("Interrupted SaveManifest barrier changed during recovery")
            }
            queries.deleteSaveManifestArtifactReferences(saveManifestId.value)
            stagedOutputs.forEach { output ->
                if (queries.markDeletedStagedExportArtifact(output.artifact_id).value != 1L) {
                    throw StoreConflict("Verified staged output changed while abandoning interrupted Save")
                }
            }
            if (queries.deleteSaveOutputPublicationIntents(saveManifestId.value).value != intents.size.toLong()) {
                throw StoreConflict("Publication intents changed while abandoning interrupted Save")
            }
            if (queries.deleteSaveManifest(saveManifestId.value, manifest.workspace_id).value != 1L) {
                throw StoreConflict("Interrupted SaveManifest changed during recovery")
            }
        }
    }

    /**
     * Atomically registers an already-published complete local output set. Filesystem I/O and
     * checksums are performed by the caller before this metadata-only transaction.
     */
    fun commitLocalExportArtifact(command: CommitLocalExportCommand) {
        database.transaction {
            val manifest = queries.saveManifestById(command.saveManifestId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("SaveManifest is missing")
            val savedPdfEdit = queries.savedPdfEditCompositionByManifest(command.saveManifestId.value).executeAsOneOrNull()
            if (queries.exportArtifactsBySaveManifest(command.saveManifestId.value).executeAsList().isNotEmpty()) {
                throw StoreConflict("SaveManifest already has a committed ExportArtifact")
            }
            if (queries.saveOutputPublicationIntentCount(command.saveManifestId.value).executeAsOne() > 0L) {
                throw StoreConflict("ExportArtifact cannot commit while output publication intents remain")
            }
            val frozenImageVersions = if (savedPdfEdit == null) {
                queries.saveOutputCompositionDependencies(command.saveManifestId.value)
                    .executeAsList().map { it.image_version_id }
            } else emptyList()
            if (savedPdfEdit == null) {
                if (command.outputKind != "PDF_WITH_JPG_PAGE_SET" || frozenImageVersions.isEmpty()
                    || frozenImageVersions.size.toLong() != queries.saveOutputCompositionStarted(command.saveManifestId.value).executeAsOne()
                ) throw StoreInvariantViolation("ExportArtifact commit requires a durable composition-start barrier")
            } else {
                val encodedIndexes = savedPdfEdit.output_page_order.removePrefix("[").removeSuffix("]")
                val outputIndexes = encodedIndexes.split(',').map { token ->
                    token.trim().toIntOrNull() ?: throw StoreInvariantViolation("Edited PDF page order is malformed")
                }
                val sourceRecent = queries.recentDocumentByExport(savedPdfEdit.source_export_artifact_id).executeAsOneOrNull()
                val sourcePdf = queries.artifactById(savedPdfEdit.source_pdf_artifact_id).executeAsOneOrNull()
                val sourcePdfReferenceCount = queries.artifactReferenceForExportOutput(
                    savedPdfEdit.source_pdf_artifact_id,
                    savedPdfEdit.source_export_artifact_id,
                ).executeAsOne()
                if (command.outputKind != "EDITED_SAVED_PDF"
                    || command.externalPackage == null
                    || command.expectedPageCount != outputIndexes.size.toLong()
                    || outputIndexes.isEmpty()
                    || outputIndexes.distinct().size != outputIndexes.size
                    || outputIndexes.any { it < 0 || it.toLong() >= savedPdfEdit.source_page_count }
                    || sourceRecent?.page_count != savedPdfEdit.source_page_count
                    || sourcePdfReferenceCount != 1L
                    || sourcePdf?.state != "COMMITTED"
                    || sourcePdf.storage_state != "PRESENT"
                    || sourcePdf.checksum != savedPdfEdit.source_pdf_sha256
                ) throw StoreInvariantViolation("Edited PDF export differs from its immutable edit manifest")
            }
            if (queries.artifactById(command.exportArtifactId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("ExportArtifact ID already exists")
            }
            if (savedPdfEdit == null) {
                val outputPages = command.files.filter { it.fileKind == "JPG_PAGE" }
                if (frozenImageVersions.isEmpty() || outputPages.map { it.sourceImageVersionId?.value } != frozenImageVersions) {
                    throw StoreInvariantViolation("JPG companions do not match frozen ImageVersion order")
                }
                if (command.files.size != frozenImageVersions.size + 1) {
                    throw StoreInvariantViolation("ExportArtifact must contain one PDF and one JPG per frozen Page")
                }
            } else if (command.files.size != 1 || command.files.single().fileKind != "PDF") {
                throw StoreInvariantViolation("Edited saved-PDF output must contain exactly one PDF")
            }

            command.files.forEach { output ->
                val artifact = output.file
                val staged = queries.artifactById(artifact.artifactId.value).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Published export output is not recorded as staged")
                if (staged.state != "STAGED_DURABLE" || staged.artifact_kind != "EXPORT_OUTPUT"
                    || staged.relative_storage_path != artifact.relativePath
                    || staged.size_bytes != artifact.sizeBytes || staged.checksum != artifact.sha256
                    || staged.artifact_format_version != artifact.formatVersion
                    || staged.producer_epoch != artifact.producerEpoch
                ) {
                    throw StoreInvariantViolation("Staged export Artifact metadata does not match published bytes")
                }
                if (queries.exportOutputReferenceForSave(artifact.artifactId.value, command.saveManifestId.value)
                        .executeAsOne() != 1L
                ) {
                    throw StoreInvariantViolation("Staged output Artifact is protected by another SaveManifest")
                }
            }
            queries.insertExportArtifact(
                export_artifact_id = command.exportArtifactId.value,
                save_manifest_id = command.saveManifestId.value,
                output_kind = command.outputKind,
                artifact_set_payload = command.toCanonicalArtifactSetPayload(),
                artifact_format_version = command.artifactFormatVersion,
                structural_validation_version = command.structuralValidationVersion,
            )
            command.files.forEach { output ->
                val artifactId = output.file.artifactId.value
                if (queries.commitArtifact(artifactId).value != 1L) {
                    throw StoreInvariantViolation("Export output Artifact was not staged durable")
                }
                addArtifactReferenceChecked(
                    artifact_id = artifactId,
                    ref_kind = "EXPORT_ARTIFACT",
                    ref_owner_id = command.exportArtifactId.value,
                    protection_class = "EXPORT",
                )
            }
            command.externalPackage?.let { external ->
                val activeGrant = queries.activeDestinationGrant().executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("External export package has no persisted destination grant")
                if (activeGrant.tree_uri != external.destinationTreeUri) {
                    throw StoreConflict("External export package destination differs from the persisted grant")
                }
                val qualification = queries.providerQualificationById(external.providerQualificationId).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("External export package has no provider qualification")
                if (qualification.adapter_id != external.adapterId
                    || qualification.provider_version != external.providerVersion
                    || qualification.os_version != external.osVersion
                    || qualification.destination_access_class != external.destinationAccessClass
                ) throw StoreInvariantViolation("External export package provider tuple is not qualified")
                val externalOutputIds = external.outputs.map { it.outputArtifactId.value }
                val committedOutputIds = command.files.map { it.file.artifactId.value }
                if (externalOutputIds != listOf(committedOutputIds.first())
                    && externalOutputIds != committedOutputIds
                ) {
                    throw StoreInvariantViolation("External package outputs differ from the committed output set")
                }
                queries.insertExternalExportPackage(
                    export_artifact_id = command.exportArtifactId.value,
                    destination_tree_uri = external.destinationTreeUri,
                    common_basename = external.commonBasename,
                    provider_qualification_id = external.providerQualificationId,
                    adapter_id = external.adapterId,
                    provider_version = external.providerVersion,
                    os_version = external.osVersion,
                    destination_access_class = external.destinationAccessClass,
                    ocr_status = external.ocrStatus,
                )
                if (external.outputs.size == 1 && command.files.size > 1) {
                    queries.markExternalPackagePdfOnly(command.exportArtifactId.value)
                }
                external.outputs.forEach { output ->
                    queries.insertExternalExportPackageOutput(
                        export_artifact_id = command.exportArtifactId.value,
                        output_artifact_id = output.outputArtifactId.value,
                        display_name = output.displayName,
                        ordinal = output.ordinal,
                    )
                }
            }
            queries.deleteSaveManifestArtifactReferences(command.saveManifestId.value)
        }
    }

    /**
     * Records a verified public MediaStore publication after its local ExportArtifact is committed.
     * The durable publication confirmation, RecentDocument index, and unchanged-page consumption
     * are one transaction so startup integrity can distinguish this path from SAF exports.
     */
    fun recordVerifiedPublicExport(
        exportArtifactId: ExportArtifactId,
        documentUri: String,
        displayName: String,
        pageCount: Long,
        ocrStatus: String,
    ) {
        require(documentUri.startsWith("content://")) { "Public export URI must be a content URI" }
        require(displayName.isNotBlank()) { "Public export display name must not be blank" }
        require(pageCount > 0L) { "Public export page count must be positive" }
        require(ocrStatus in setOf("FULL", "NONE", "PARTIAL")) { "Public export OCR status is invalid" }

        database.transaction {
            val export = queries.exportArtifactById(exportArtifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Public export requires a committed ExportArtifact")
            if (export.output_kind != "PDF_WITH_JPG_PAGE_SET"
                || queries.savedPdfEditCompositionByManifest(export.save_manifest_id).executeAsOneOrNull() != null
                || queries.externalExportPackageById(exportArtifactId.value).executeAsOneOrNull() != null
            ) throw StoreInvariantViolation("Public export requires a normal local PDF save without an ExternalExportPackage")

            val dependencies = queries.saveOutputCompositionDependencies(export.save_manifest_id).executeAsList()
            if (dependencies.isEmpty()
                || dependencies.map { it.image_version_id }.distinct().size != dependencies.size
                || dependencies.any {
                    it.dependency_kind != "LOCAL_OUTPUT_COMPOSITION"
                        || it.dependency_spec != "frozen-image-version:${it.image_version_id}"
                        || queries.imageVersionById(it.image_version_id).executeAsOneOrNull() == null
                }
                || pageCount != dependencies.size.toLong()
            ) throw StoreInvariantViolation("Public export page count differs from the frozen SaveManifest dependencies")

            val outputIds = queries.exportArtifactOutputReferences(exportArtifactId.value).executeAsList()
            val pdfOutputs = outputIds.mapNotNull { artifactId ->
                queries.artifactById(artifactId).executeAsOneOrNull()?.takeIf {
                    it.relative_storage_path.endsWith(".pdf", ignoreCase = true)
                }
            }
            if (pdfOutputs.size != 1) throw StoreInvariantViolation("Public export requires exactly one referenced PDF output")
            val pdf = pdfOutputs.single()
            if (pdf.artifact_kind != "EXPORT_OUTPUT" || pdf.state != "COMMITTED"
                || pdf.storage_state != "PRESENT" || pdf.size_bytes <= 0L
                || pdf.checksum?.matches(Regex("[0-9a-f]{64}")) != true
                || queries.artifactReferenceForExportOutput(pdf.artifact_id, exportArtifactId.value).executeAsOne() != 1L
                || !export.artifact_set_payload.contains("\"artifactId\":\"${pdf.artifact_id}\"")
            ) throw StoreInvariantViolation("Public export PDF is not a committed, protected member of its ExportArtifact")

            val publication = queries.publicExportPublicationById(exportArtifactId.value).executeAsOneOrNull()
            if (publication == null) {
                queries.insertPublicExportPublication(
                    exportArtifactId.value, documentUri, displayName, pageCount, ocrStatus,
                )
            } else if (publication.document_uri != documentUri || publication.display_name != displayName
                || publication.page_count != pageCount || publication.ocr_status != ocrStatus
            ) throw StoreInvariantViolation("Public export publication was already recorded with different metadata")

            val recent = queries.recentDocumentByExport(exportArtifactId.value).executeAsOneOrNull()
            if (recent == null) {
                queries.insertRecentDocument(
                    recent_id = exportArtifactId.value,
                    export_artifact_id = exportArtifactId.value,
                    document_uri = documentUri,
                    display_name = displayName,
                    page_count = pageCount,
                    saved_wall = clock.read().wallTimeMillis,
                    ocr_status = ocrStatus,
                )
            } else if (recent.document_uri != documentUri || recent.display_name != displayName
                || recent.page_count != pageCount || recent.ocr_status != ocrStatus
            ) throw StoreInvariantViolation("RecentDocument differs from its verified public export")

            consumeFrozenPagesForSuccessfulSave(exportArtifactId.value)
        }
    }

    private fun CommitLocalExportCommand.toCanonicalArtifactSetPayload(): String = buildString {
        append("{\"formatVersion\":1,\"files\":[")
        files.forEachIndexed { index, output ->
            if (index > 0) append(',')
            val artifact = output.file
            append("{\"kind\":\"").append(output.fileKind).append("\",\"artifactId\":\"")
                .append(artifact.artifactId.value).append("\",\"relativePath\":\"")
                .append(artifact.relativePath.jsonEscape()).append("\",\"sizeBytes\":")
                .append(artifact.sizeBytes).append(",\"sha256\":\"").append(artifact.sha256)
                .append("\",\"sourceImageVersionId\":")
                .append(output.sourceImageVersionId?.let { "\"${it.value}\"" } ?: "null")
                .append('}')
        }
        append("]}")
    }

    /** Records WritingExternal and recovery references before any provider I/O. */
    fun beginExportAttempt(command: BeginExportAttemptCommand): ExportAttemptSnapshot =
        database.transactionWithResult {
            val export = queries.exportArtifactById(command.exportArtifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Committed ExportArtifact is missing")
            val output = queries.artifactById(command.outputArtifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Export output Artifact is missing")
            if (output.state != "COMMITTED" || output.artifact_kind != "EXPORT_OUTPUT"
                || queries.artifactReferenceForExportOutput(output.artifact_id, export.export_artifact_id).executeAsOne() != 1L
            ) {
                throw StoreInvariantViolation("Attempt source is not a committed member of this ExportArtifact")
            }
            if ((output.relative_storage_path.endsWith(".pdf") && command.mimeType != "application/pdf")
                || (output.relative_storage_path.endsWith(".jpg") && command.mimeType != "image/jpeg")
                || (!output.relative_storage_path.endsWith(".pdf") && !output.relative_storage_path.endsWith(".jpg"))
            ) {
                throw StoreInvariantViolation("Provider MIME type does not match committed output format")
            }
            val savedDestination = queries.activeDestinationGrant().executeAsOneOrNull()
                ?: throw StoreInvariantViolation("No persisted SAF destination is selected")
            if (savedDestination.tree_uri != command.destinationTreeUri) {
                throw StoreConflict("Export destination differs from the persisted SAF grant")
            }
            val qualification = queries.providerQualificationById(command.providerQualificationId).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Provider tuple has no qualification record")
            if (qualification.adapter_id != command.adapterId
                || qualification.provider_version != command.providerVersion
                || qualification.os_version != command.osVersion
                || qualification.destination_access_class != command.destinationAccessClass
            ) {
                throw StoreInvariantViolation("Provider qualification does not match the attempted tuple")
            }
            val packageRecord = queries.externalExportPackageById(export.export_artifact_id).executeAsOneOrNull()
            if (packageRecord != null) {
                val packageOutput = queries.externalExportPackageOutputByArtifact(
                    export.export_artifact_id, output.artifact_id,
                ).executeAsOneOrNull() ?: throw StoreInvariantViolation("Output is not part of its external package")
                if (packageRecord.destination_tree_uri != command.destinationTreeUri
                    || packageRecord.provider_qualification_id != command.providerQualificationId
                    || packageRecord.adapter_id != command.adapterId
                    || packageRecord.provider_version != command.providerVersion
                    || packageRecord.os_version != command.osVersion
                    || packageRecord.destination_access_class != command.destinationAccessClass
                    || packageOutput.display_name != command.plannedDisplayName
                ) throw StoreInvariantViolation("ExportAttempt differs from its frozen external package intent")
                val priorForOutput = queries.exportAttemptsForOutput(
                    export.export_artifact_id, output.artifact_id,
                ).executeAsList()
                if (priorForOutput.lastOrNull()?.state == "SUCCEEDED") {
                    throw StoreConflict("This package output is already confirmed and cannot be written again")
                }
                if (packageRecord.state == "WRITING_EXTERNAL" || packageRecord.state == "UNKNOWN_OUTCOME") {
                    throw StoreConflict("Resolve the active package output before starting another provider write")
                }
                if (packageRecord.state == "SUCCEEDED" || packageRecord.state == "FAILED_KNOWN") {
                    throw StoreConflict("External export package is already terminal")
                }
            }
            val destination = command.toCanonicalDestinationDescriptor()
            if (queries.nonterminalAttemptForDestination(export.export_artifact_id, destination).executeAsOne() > 0L) {
                throw StoreConflict("A non-terminal attempt already exists for this output and destination intent")
            }
            if (queries.exportAttemptById(command.exportAttemptId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("ExportAttempt ID already exists")
            }
            val parameters = command.toCanonicalProviderParameters(output, qualification.qualification_id)
            queries.insertExportAttempt(
                export_attempt_id = command.exportAttemptId.value,
                export_artifact_id = export.export_artifact_id,
                output_artifact_id = output.artifact_id,
                durable_workflow_payload_version = command.durableWorkflowPayloadVersion,
                destination_descriptor = destination,
                frozen_provider_parameters = parameters,
            )
            if (packageRecord != null) {
                queries.updateExternalExportPackageState("WRITING_EXTERNAL", null, export.export_artifact_id)
            }
            addArtifactReferenceChecked(
                artifact_id = output.artifact_id,
                ref_kind = "EXPORT_ATTEMPT",
                ref_owner_id = command.exportAttemptId.value,
                protection_class = "RECOVERY",
            )
            val created = queries.exportAttemptById(command.exportAttemptId.value).executeAsOne()
            created.toExportAttemptSnapshot()
        }

    /** Startup-only barrier. Live attempts are never rewritten by foreground reconciliation. */
    fun markWritingExportAttemptsUnknownAtStartup(): List<ExportAttemptId> = database.transactionWithResult {
        val writing = queries.exportAttemptsInWriting().executeAsList()
        writing.forEach { attempt ->
            if (queries.finishWritingExportAttempt(
                    state = "UNKNOWN_OUTCOME",
                    external_identity = null,
                    operation_observation_id = null,
                    known_error = "Process ended before provider outcome was committed",
                    export_attempt_id = attempt.export_attempt_id,
                ).value != 1L
            ) {
                throw StoreConflict("ExportAttempt changed during startup ambiguity recovery")
            }
        }
        writing.map { it.export_artifact_id }.distinct().forEach(::refreshExternalExportPackageState)
        writing.map { ExportAttemptId.parse(it.export_attempt_id) }
    }

    /** Completes a live WritingExternal attempt with a definite provider result. */
    fun finishWritingExportAttempt(
        exportAttemptId: ExportAttemptId,
        outcome: ExportAttemptOutcome,
        externalIdentity: String?,
        knownError: String?,
        provesNoSideEffect: Boolean,
        observation: ProviderOperationObservationCommand,
    ) {
        require(outcome != ExportAttemptOutcome.UNKNOWN_OUTCOME) {
            "A live attempt may become UnknownOutcome only at the startup barrier"
        }
        require(if (outcome == ExportAttemptOutcome.SUCCEEDED) !externalIdentity.isNullOrBlank()
            else !knownError.isNullOrBlank() && provesNoSideEffect
        ) { "Outcome evidence is insufficient for the requested terminal state" }
        database.transaction {
            val attempt = queries.exportAttemptById(exportAttemptId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("ExportAttempt is missing")
            if (attempt.state != "WRITING_EXTERNAL") throw StoreConflict("ExportAttempt is no longer WritingExternal")
            insertOperationObservation(exportAttemptId, observation)
            val newState = outcome.databaseState()
            if (queries.finishWritingExportAttempt(
                    state = newState,
                    external_identity = externalIdentity,
                    operation_observation_id = observation.observationId,
                    known_error = knownError,
                    export_attempt_id = exportAttemptId.value,
                ).value != 1L
            ) throw StoreConflict("ExportAttempt changed before its terminal outcome was committed")
            queries.deleteExportAttemptArtifactReferences(exportAttemptId.value)
            refreshExternalExportPackageState(attempt.export_artifact_id)
        }
    }

    /** Records an ambiguous live provider result while retaining the output recovery reference. */
    fun markWritingExportAttemptUnknown(
        exportAttemptId: ExportAttemptId,
        reason: String,
        observation: ProviderOperationObservationCommand,
    ) {
        require(reason.isNotBlank())
        database.transaction {
            val attempt = queries.exportAttemptById(exportAttemptId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("ExportAttempt is missing")
            if (attempt.state != "WRITING_EXTERNAL") throw StoreConflict("Only WritingExternal can become UnknownOutcome")
            insertOperationObservation(exportAttemptId, observation)
            if (queries.finishWritingExportAttempt(
                    state = "UNKNOWN_OUTCOME",
                    external_identity = null,
                    operation_observation_id = observation.observationId,
                    known_error = reason,
                    export_attempt_id = exportAttemptId.value,
                ).value != 1L
            ) throw StoreConflict("ExportAttempt changed before its ambiguous outcome was recorded")
            // Recovery protection intentionally remains until later observation resolves the attempt.
            refreshExternalExportPackageState(attempt.export_artifact_id)
        }
    }

    /** Applies an adapter's read-only revalidation evidence to an UnknownOutcome attempt. */
    fun revalidateUnknownExportAttempt(
        exportAttemptId: ExportAttemptId,
        outcome: ExportAttemptOutcome,
        externalIdentity: String?,
        knownError: String?,
        provesNoSideEffect: Boolean,
        observation: ProviderOperationObservationCommand,
    ) {
        require(if (outcome == ExportAttemptOutcome.SUCCEEDED) !externalIdentity.isNullOrBlank()
            else if (outcome == ExportAttemptOutcome.FAILED_KNOWN) !knownError.isNullOrBlank() && provesNoSideEffect
            else true
        ) { "Revalidation evidence does not prove the requested outcome" }
        database.transaction {
            val attempt = queries.exportAttemptById(exportAttemptId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("ExportAttempt is missing")
            if (attempt.state != "UNKNOWN_OUTCOME") throw StoreConflict("Only UnknownOutcome can be revalidated")
            insertOperationObservation(exportAttemptId, observation)
            if (queries.finishUnknownExportAttempt(
                    state = outcome.databaseState(),
                    external_identity = externalIdentity,
                    operation_observation_id = observation.observationId,
                    known_error = knownError,
                    export_attempt_id = exportAttemptId.value,
                ).value != 1L
            ) throw StoreConflict("UnknownOutcome changed during revalidation")
            if (outcome != ExportAttemptOutcome.UNKNOWN_OUTCOME) {
                queries.deleteExportAttemptArtifactReferences(exportAttemptId.value)
            }
            refreshExternalExportPackageState(attempt.export_artifact_id)
        }
    }

    fun exportAttempt(exportAttemptId: ExportAttemptId): ExportAttemptSnapshot? =
        queries.exportAttemptById(exportAttemptId.value).executeAsOneOrNull()?.toExportAttemptSnapshot()

    fun unknownExportAttempts(): List<ExportAttemptSnapshot> =
        queries.unknownExportAttempts().executeAsList().map { it.toExportAttemptSnapshot() }

    fun exportAttempts(exportArtifactId: ExportArtifactId): List<ExportAttemptSnapshot> =
        queries.exportAttemptsForArtifact(exportArtifactId.value).executeAsList().map { it.toExportAttemptSnapshot() }

    fun externalExportPackage(exportArtifactId: ExportArtifactId): ExternalExportPackageSnapshot? {
        val packageRecord = queries.externalExportPackageById(exportArtifactId.value).executeAsOneOrNull() ?: return null
        val outputs = queries.externalExportPackageOutputs(exportArtifactId.value).executeAsList().map {
            ExternalExportPackageOutputCommand(ArtifactId.parse(it.output_artifact_id), it.display_name, it.ordinal)
        }
        return ExternalExportPackageSnapshot(
            ExportArtifactId.parse(packageRecord.export_artifact_id),
            packageRecord.destination_tree_uri,
            packageRecord.common_basename,
            packageRecord.provider_qualification_id,
            packageRecord.adapter_id,
            packageRecord.provider_version,
            packageRecord.os_version,
            packageRecord.destination_access_class,
            packageRecord.state,
            packageRecord.last_error,
            packageRecord.ocr_status,
            outputs,
        )
    }

    fun incompleteExternalExportPackages(): List<ExternalExportPackageSnapshot> =
        queries.incompleteExternalExportPackages().executeAsList().mapNotNull {
            externalExportPackage(ExportArtifactId.parse(it.export_artifact_id))
        }

    private fun refreshExternalExportPackageState(exportArtifactId: String) {
        val packageRecord = queries.externalExportPackageById(exportArtifactId).executeAsOneOrNull() ?: return
        val outputs = queries.externalExportPackageOutputs(exportArtifactId).executeAsList()
        if (outputs.isEmpty()) throw StoreInvariantViolation("External export package has no output mapping")
        val attempts = queries.exportAttemptsForArtifact(exportArtifactId).executeAsList()
        val latestStateByOutput = outputs.associate { output ->
            val latest = attempts.lastOrNull { it.output_artifact_id == output.output_artifact_id }
            output.output_artifact_id to latest
        }
        val latest = latestStateByOutput.values.filterNotNull()
        val state = when {
            latest.any { it.state == "WRITING_EXTERNAL" } -> "WRITING_EXTERNAL"
            latest.any { it.state == "UNKNOWN_OUTCOME" } -> "UNKNOWN_OUTCOME"
            latest.any { it.state == "FAILED_KNOWN" } -> "FAILED_KNOWN"
            latestStateByOutput.values.all { it?.state == "SUCCEEDED" } -> "SUCCEEDED"
            latest.isEmpty() -> "READY"
            else -> "PARTIAL"
        }
        val error = latest.lastOrNull { it.state == "UNKNOWN_OUTCOME" || it.state == "FAILED_KNOWN" }?.known_error
        queries.updateExternalExportPackageState(state, error, packageRecord.export_artifact_id)
        if (state == "SUCCEEDED") {
            val packageOutputs = queries.externalExportPackageOutputs(exportArtifactId).executeAsList()
            val pdf = packageOutputs.firstOrNull { it.ordinal == 0L }
                ?: throw StoreInvariantViolation("Successful export package has no PDF output")
            val pdfAttempt = queries.exportAttemptsForOutput(exportArtifactId, pdf.output_artifact_id)
                .executeAsList().lastOrNull()
                ?: throw StoreInvariantViolation("Successful PDF output has no export attempt")
            val uri = pdfAttempt.external_identity
                ?: throw StoreInvariantViolation("Successful PDF output has no external document URI")
            if (!uri.startsWith("content://")) {
                throw StoreInvariantViolation("Successful PDF output identity is not a content URI")
            }
            val export = queries.exportArtifactById(exportArtifactId).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Successful package has no ExportArtifact")
            val editedComposition = queries.savedPdfEditCompositionByManifest(export.save_manifest_id)
                .executeAsOneOrNull()
            if (editedComposition == null) consumeFrozenPagesForSuccessfulSave(exportArtifactId)
            val pageCount = editedComposition?.let { editedPdfOutputPageCount(it.output_page_order) } ?: run {
                val dependencies = queries.saveOutputCompositionDependencies(export.save_manifest_id).executeAsList()
                if (dependencies.isEmpty()
                    || dependencies.any {
                        it.dependency_kind != "LOCAL_OUTPUT_COMPOSITION"
                            || it.dependency_spec != "frozen-image-version:${it.image_version_id}"
                    }
                ) throw StoreInvariantViolation("Successful package differs from its frozen SaveManifest dependencies")
                dependencies.size.toLong()
            }
            if (pageCount <= 0L) throw StoreInvariantViolation("Recent saved PDF must contain a page")
            val existing = queries.recentDocumentByExport(exportArtifactId).executeAsOneOrNull()
            if (existing == null) {
                queries.insertRecentDocument(
                    recent_id = exportArtifactId,
                    export_artifact_id = exportArtifactId,
                    document_uri = uri,
                    display_name = pdf.display_name,
                    page_count = pageCount,
                    saved_wall = clock.read().wallTimeMillis,
                    ocr_status = packageRecord.ocr_status,
                )
            } else if (existing.document_uri != uri || existing.display_name != pdf.display_name
                || existing.page_count != pageCount || existing.ocr_status != packageRecord.ocr_status
            ) throw StoreInvariantViolation("RecentDocument differs from its confirmed export package")
        }
    }

    /**
     * A complete external package consumes only unchanged Page identities frozen into its
     * SaveManifest. This runs inside the same transaction that records package SUCCEEDED.
     */
    private fun consumeFrozenPagesForSuccessfulSave(exportArtifactId: String) {
        val export = queries.exportArtifactById(exportArtifactId).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Successful package has no ExportArtifact")
        val manifest = queries.saveManifestById(export.save_manifest_id).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Successful package has no SaveManifest")
        if (manifest.manifest_format_version != 1L
            || !manifest.frozen_payload.startsWith("{\"manifestFormatVersion\":1,\"workspaceId\":\"")
        ) throw StoreInvariantViolation("Successful SaveManifest format is unsupported")

        val pagePattern = Regex("\\{\"pageId\":\"([^\"]+)\",\"orderIndex\":([0-9]+),\"contentRevision\":([0-9]+)")
        val frozenPages = pagePattern.findAll(manifest.frozen_payload).map { match ->
            DeletePageExpectation(PageId.parse(match.groupValues[1]), match.groupValues[3].toLong())
        }.toList()
        val pageIdFields = Regex("\\\"pageId\\\":").findAll(manifest.frozen_payload).count()
        if (frozenPages.isEmpty() || frozenPages.size != pageIdFields
            || frozenPages.map { it.pageId }.distinct().size != frozenPages.size
        ) throw StoreInvariantViolation("Successful SaveManifest Page identities are malformed")

        val workspaceId = WorkspaceId.parse(manifest.workspace_id)
        val stillSameVisibleSources = frozenPages.filter { frozen ->
            val page = queries.pageById(frozen.pageId.value).executeAsOneOrNull()
            page != null && page.workspace_id == workspaceId.value && page.tombstone == 0L
                && page.content_revision == frozen.contentRevision
        }
        if (stillSameVisibleSources.isNotEmpty()) {
            tombstoneVisiblePagesWithinTransaction(workspaceId, stillSameVisibleSources, clock.read())
        }
    }

    private fun editedPdfOutputPageCount(encodedIndexes: String): Long {
        val body = encodedIndexes.removePrefix("[").removeSuffix("]")
        if (body.isBlank()) throw StoreInvariantViolation("Edited PDF manifest has no output pages")
        val indexes = body.split(',').map { token ->
            token.trim().toLongOrNull() ?: throw StoreInvariantViolation("Edited PDF manifest page order is malformed")
        }
        if (indexes.any { it < 0L } || indexes.distinct().size != indexes.size) {
            throw StoreInvariantViolation("Edited PDF manifest page order is invalid")
        }
        return indexes.size.toLong()
    }

    fun recentDocuments(nowWallMillis: Long = clock.read().wallTimeMillis): List<RecentDocumentSnapshot> {
        require(nowWallMillis >= 0L)
        val earliest = (nowWallMillis - RECENT_DOCUMENT_WINDOW_MILLIS).coerceAtLeast(0L)
        return database.transactionWithResult {
            queries.expireRecentDocuments(earliest)
            queries.recentDocumentsInWindow(earliest, nowWallMillis).executeAsList().map {
                RecentDocumentSnapshot(
                    it.recent_id,
                    ExportArtifactId.parse(it.export_artifact_id),
                    it.document_uri,
                    it.display_name,
                    it.page_count,
                    it.saved_wall,
                    it.ocr_status,
                )
            }
        }
    }

    fun committedExportOutputs(exportArtifactId: ExportArtifactId): List<CommittedExportOutput> {
        if (queries.exportArtifactById(exportArtifactId.value).executeAsOneOrNull() == null) {
            throw StoreInvariantViolation("Committed ExportArtifact is missing")
        }
        val ids = queries.exportArtifactOutputReferences(exportArtifactId.value).executeAsList()
        if (ids.isEmpty()) throw StoreInvariantViolation("ExportArtifact has no protected output files")
        return ids.map { artifactId ->
            val output = queries.artifactById(artifactId).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("ExportArtifact output Artifact is missing")
            if (output.state != "COMMITTED" || output.artifact_kind != "EXPORT_OUTPUT"
                || output.size_bytes <= 0L || output.checksum?.matches(Regex("[0-9a-f]{64}")) != true
            ) throw StoreInvariantViolation("ExportArtifact output Artifact is not committed and verified")
            CommittedExportOutput(
                ArtifactId.parse(output.artifact_id), output.relative_storage_path, output.size_bytes,
                output.checksum, output.artifact_format_version,
            )
        }
    }

    private fun insertOperationObservation(
        exportAttemptId: ExportAttemptId,
        observation: ProviderOperationObservationCommand,
    ) {
        queries.insertProviderOperationObservation(
            observation_id = observation.observationId,
            export_attempt_id = exportAttemptId.value,
            identity_evidence = observation.identityEvidence,
            existence_evidence = observation.existenceEvidence,
            integrity_evidence = observation.integrityEvidence,
            readability_evidence = observation.readabilityEvidence,
            name_evidence = observation.nameEvidence,
            access_evidence = observation.accessEvidence,
        )
    }

    private fun ExportAttemptOutcome.databaseState(): String = when (this) {
        ExportAttemptOutcome.SUCCEEDED -> "SUCCEEDED"
        ExportAttemptOutcome.FAILED_KNOWN -> "FAILED_KNOWN"
        ExportAttemptOutcome.UNKNOWN_OUTCOME -> "UNKNOWN_OUTCOME"
    }

    private fun app.docscan.core.db.ExportAttempt.toExportAttemptSnapshot() = ExportAttemptSnapshot(
        ExportAttemptId.parse(export_attempt_id), ExportArtifactId.parse(export_artifact_id),
        output_artifact_id?.let(ArtifactId::parse),
        destination_descriptor, frozen_provider_parameters, state, external_identity,
        operation_observation_id, known_error,
    )

    private fun BeginExportAttemptCommand.toCanonicalDestinationDescriptor(): String = buildString {
        append("{\"treeUri\":\"").append(destinationTreeUri.jsonEscape())
            .append("\",\"outputArtifactId\":\"").append(outputArtifactId.value)
            .append("\",\"plannedDisplayName\":\"").append(plannedDisplayName.jsonEscape()).append("\"}")
    }

    private fun BeginExportAttemptCommand.toCanonicalProviderParameters(output: app.docscan.core.db.ArtifactRecord,
        qualificationId: String): String = buildString {
        append("{\"outputArtifactId\":\"").append(output.artifact_id)
            .append("\",\"relativePath\":\"").append(output.relative_storage_path.jsonEscape())
            .append("\",\"sizeBytes\":").append(output.size_bytes)
            .append(",\"sha256\":\"").append(output.checksum)
            .append("\",\"plannedDisplayName\":\"").append(plannedDisplayName.jsonEscape())
            .append("\",\"mimeType\":\"").append(mimeType)
            .append("\",\"qualificationId\":\"").append(qualificationId).append("\"}")
    }

    private fun List<FrozenSavePage>.toCanonicalSavePayload(workspaceId: WorkspaceId): String = buildString {
        append("{\"manifestFormatVersion\":1,\"workspaceId\":\"")
        append(workspaceId.value)
        append("\",\"pages\":[")
        this@toCanonicalSavePayload.forEachIndexed { index, page ->
            if (index > 0) append(',')
            append("{\"pageId\":\"").append(page.pageId.value)
            append("\",\"orderIndex\":").append(page.orderIndex)
            append(",\"contentRevision\":").append(page.contentRevision)
            append(",\"imageVersionId\":\"").append(page.imageVersionId.value)
            append("\",\"artifactId\":\"").append(page.artifactId.value)
            append("\",\"artifactRelativePath\":\"").append(page.artifactRelativePath.jsonEscape())
            append("\",\"artifactSizeBytes\":").append(page.artifactSizeBytes)
            append(",\"artifactSha256\":\"").append(page.artifactSha256)
            append("\",\"artifactFormatVersion\":").append(page.artifactFormatVersion)
            append(",\"recognizedText\":")
            append(page.recognizedText?.let { "\"${it.jsonEscape()}\"" } ?: "null")
            append(",\"recognizedGeometry\":")
            append(page.recognizedGeometry?.toCanonicalJson() ?: "null")
            append(",\"recognizedLayout\":")
            append(page.recognizedLayout?.toCanonicalJson() ?: "null")
            append('}')
        }
        append("]}")
    }

    private fun RecognitionLayout.toCanonicalJson(): String = buildString {
        append("{\"formatVersion\":").append(formatVersion)
        append(",\"sourceWidthPx\":").append(sourceWidthPx)
        append(",\"sourceHeightPx\":").append(sourceHeightPx)
        quality?.let { q ->
            append(",\"quality\":{\"meanConfidence\":").append(q.meanConfidence)
            append(",\"lowConfidenceWordCount\":").append(q.lowConfidenceWordCount)
            append(",\"structureAvailable\":").append(q.structureAvailable)
            q.language?.let { append(",\"language\":\"").append(it.jsonEscape()).append('"') }
            append(",\"preprocessingProfile\":\"").append(q.preprocessingProfile.jsonEscape()).append('"')
            append(",\"engineVersion\":\"").append(q.engineVersion.jsonEscape()).append('"')
            append('}')
        }
        append(",\"blocks\":[")
        blocks.forEachIndexed { bIndex, block ->
            if (bIndex > 0) append(',')
            append("{\"id\":\"").append(block.id.jsonEscape()).append('"')
            append(",\"type\":\"").append(block.type.name.jsonEscape()).append('"')
            append(",\"readingOrder\":").append(block.readingOrder)
            append(",\"box\":{\"left\":").append(block.box.left)
            append(",\"top\":").append(block.box.top)
            append(",\"right\":").append(block.box.right)
            append(",\"bottom\":").append(block.box.bottom).append('}')
            append(",\"paragraphs\":[")
            block.paragraphs.forEachIndexed { pIndex, par ->
                if (pIndex > 0) append(',')
                append("{\"box\":{\"left\":").append(par.box.left)
                append(",\"top\":").append(par.box.top)
                append(",\"right\":").append(par.box.right)
                append(",\"bottom\":").append(par.box.bottom).append('}')
                append(",\"lines\":[")
                par.lines.forEachIndexed { lIndex, line ->
                    if (lIndex > 0) append(',')
                    append("{\"box\":{\"left\":").append(line.box.left)
                    append(",\"top\":").append(line.box.top)
                    append(",\"right\":").append(line.box.right)
                    append(",\"bottom\":").append(line.box.bottom).append('}')
                    line.baseline?.let { b ->
                        append(",\"baseline\":{\"slope\":").append(b.slope).append(",\"intercept\":").append(b.intercept).append('}')
                    }
                    line.xHeight?.let { append(",\"xHeight\":").append(it) }
                    append(",\"words\":[")
                    line.words.forEachIndexed { wIndex, word ->
                        if (wIndex > 0) append(',')
                        append("{\"text\":\"").append(word.text.jsonEscape()).append('"')
                        append(",\"box\":{\"left\":").append(word.box.left)
                        append(",\"top\":").append(word.box.top)
                        append(",\"right\":").append(word.box.right)
                        append(",\"bottom\":").append(word.box.bottom).append('}')
                        append(",\"confidence\":").append(word.confidence)
                        append('}')
                    }
                    append("]}")
                }
                append("]}")
            }
            append("]}")
        }
        append("]}")
    }

    private fun RecognitionGeometry.toCanonicalJson(): String = buildString {
        append("{\"formatVersion\":").append(formatVersion)
        append(",\"coordinateSpace\":\"").append(coordinateSpace.jsonEscape()).append('"')
        append(",\"sourceWidthPx\":").append(sourceWidthPx)
        append(",\"sourceHeightPx\":").append(sourceHeightPx)
        append(",\"words\":[")
        words.forEachIndexed { index, word ->
            if (index > 0) append(',')
            append("{\"text\":\"").append(word.text.jsonEscape()).append('"')
            append(",\"leftPx\":").append(word.leftPx)
            append(",\"topPx\":").append(word.topPx)
            append(",\"rightPx\":").append(word.rightPx)
            append(",\"bottomPx\":").append(word.bottomPx)
            append(",\"confidence\":").append(word.confidence)
            append('}')
        }
        append("]}")
    }

    fun setSelected(workspaceId: WorkspaceId, pageId: app.docscan.core.model.PageId, selected: Boolean) {
        val time = clock.read()
        database.transaction {
            val changed = queries.setPageSelected(
                selected = if (selected) 1L else 0L,
                page_id = pageId.value,
                workspace_id = workspaceId.value,
            ).value
            if (changed != 1L) throw StoreInvariantViolation("Visible Page is missing")
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
        }
    }

    fun setAllVisiblePagesSelected(workspaceId: WorkspaceId, selected: Boolean) {
        val time = clock.read()
        database.transaction {
            queries.setAllVisiblePagesSelected(
                selected = if (selected) 1L else 0L,
                workspace_id = workspaceId.value,
            )
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
        }
    }

    fun setActivePage(workspaceId: WorkspaceId, pageId: app.docscan.core.model.PageId) {
        val time = clock.read()
        database.transaction {
            val page = queries.pageById(pageId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Page is missing")
            if (page.workspace_id != workspaceId.value || page.tombstone != 0L) {
                throw StoreInvariantViolation("Page is not visible in this workspace")
            }
            queries.setActivePageOnly(pageId.value, workspaceId.value)
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
        }
    }

    fun replacePageImage(command: ReplacePageImageCommand): Long {
        val time = clock.read()
        return database.transactionWithResult {
            val page = queries.pageById(command.pageId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Page is missing")
            if (page.workspace_id != command.workspaceId.value || page.tombstone != 0L) {
                throw StoreInvariantViolation("Page is not visible in this workspace")
            }
            val openSession = queries.openSessionForWorkspace(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace has no open session")
            if (page.current_image_version_id != command.expectedImageVersionId.value
                || page.content_revision != command.expectedContentRevision
            ) throw StoreConflict("Page has changed since Retake/Edit started")
            if (queries.imageVersionById(command.newImageVersionId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("ImageVersion ID already exists")
            }
            val staged = queries.artifactById(command.artifact.artifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Durable artifact record is missing")
            if (staged.state != "STAGED_DURABLE"
                || staged.relative_storage_path != command.artifact.relativePath
                || staged.size_bytes != command.artifact.sizeBytes
                || staged.checksum != command.artifact.sha256
                || staged.artifact_format_version != command.artifact.formatVersion
                || staged.producer_epoch != command.artifact.producerEpoch
                || staged.artifact_kind != "DOCUMENT_PAGE"
            ) throw StoreInvariantViolation("Artifact does not match the staged durable image")

            page.undo_image_version_id?.let { oldUndo ->
                if (queries.deleteArtifactReference(oldUndo).value != 1L) {
                    throw StoreInvariantViolation("Previous Undo image reference is missing")
                }
            }
            queries.insertImageVersion(
                image_version_id = command.newImageVersionId.value,
                page_id = page.page_id,
                artifact_id = staged.artifact_id,
                parent_version_id = page.current_image_version_id,
                coordinate_space = command.coordinateSpace,
                transform_provenance = command.transformProvenance,
                artifact_format_version = staged.artifact_format_version,
            )
            if (queries.commitArtifact(staged.artifact_id).value != 1L) {
                throw StoreInvariantViolation("Artifact was not staged at Retake/Edit commit")
            }
            if (queries.setArtifactReferenceProtection("UNDO", page.current_image_version_id).value != 1L) {
                throw StoreInvariantViolation("Current image protection reference is missing")
            }
            addArtifactReferenceChecked(
                artifact_id = staged.artifact_id,
                ref_kind = "IMAGE_VERSION",
                ref_owner_id = command.newImageVersionId.value,
                protection_class = "AUTHORITATIVE",
            )
            val changed = queries.advancePageImage(
                current_image_version_id = command.newImageVersionId.value,
                undo_image_version_id = page.current_image_version_id,
                undo_workspace_session_id = openSession.workspace_session_id,
                processing_desired_state = command.processingDesiredState,
                page_id = page.page_id,
                workspace_id = command.workspaceId.value,
                current_image_version_id_ = command.expectedImageVersionId.value,
                content_revision = command.expectedContentRevision,
            ).value
            if (changed != 1L) throw StoreConflict("Page changed while committing Retake/Edit")
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = command.workspaceId.value,
            )
            page.content_revision + 1L
        }
    }

    fun undoPageImage(
        workspaceId: WorkspaceId,
        pageId: app.docscan.core.model.PageId,
        expectedImageVersionId: ImageVersionId,
        expectedContentRevision: Long,
        processingDesiredState: String = "OCR_LATIN_V1",
    ): Long {
        val time = clock.read()
        return database.transactionWithResult {
            val page = queries.pageById(pageId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Page is missing")
            if (page.workspace_id != workspaceId.value || page.tombstone != 0L) {
                throw StoreInvariantViolation("Page is not visible in this workspace")
            }
            val session = queries.openSessionForWorkspace(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace has no open session")
            val undoVersion = page.undo_image_version_id
                ?: throw StoreInvariantViolation("Page has no Undo target")
            if (page.undo_workspace_session_id != session.workspace_session_id) {
                throw StoreInvariantViolation("Undo target belongs to a closed or different session")
            }
            if (page.current_image_version_id != expectedImageVersionId.value
                || page.content_revision != expectedContentRevision
            ) throw StoreConflict("Page has changed since Undo started")
            val restoredRecognition = queries.recognitionForImageVersion(pageId.value, undoVersion)
                .executeAsOneOrNull()?.recognition_id
            val changed = queries.undoPageImage(
                current_image_version_id = undoVersion,
                current_recognition_id = restoredRecognition,
                processing_desired_state = processingDesiredState,
                page_id = pageId.value,
                workspace_id = workspaceId.value,
                current_image_version_id_ = expectedImageVersionId.value,
                content_revision = expectedContentRevision,
                undo_image_version_id = undoVersion,
                undo_workspace_session_id = session.workspace_session_id,
            ).value
            if (changed != 1L) throw StoreConflict("Page changed while committing Undo")
            if (queries.deleteArtifactReference(page.current_image_version_id).value != 1L) {
                throw StoreInvariantViolation("Current image protection reference is missing")
            }
            if (queries.setArtifactReferenceProtection("AUTHORITATIVE", undoVersion).value != 1L) {
                throw StoreInvariantViolation("Undo image protection reference is missing")
            }
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
            page.content_revision + 1L
        }
    }

    fun commitRecognition(candidate: RecognitionCandidate) {
        database.transaction {
            val page = queries.pageById(candidate.pageId.value).executeAsOneOrNull()
                ?: throw StoreConflict("OCR candidate Page no longer exists")
            if (page.workspace_id != candidate.workspaceId.value || page.tombstone != 0L
                || page.current_image_version_id != candidate.imageVersionId.value
                || page.content_revision != candidate.contentRevision
                || page.processing_desired_state != candidate.desiredOperationKey
            ) throw StoreConflict("OCR candidate became stale")
            if (queries.recognitionById(candidate.recognitionId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("Recognition ID already exists")
            }
            candidate.layout?.let { layout ->
                if (layout.sourceWidthPx <= 0 || layout.sourceHeightPx <= 0 || layout.formatVersion != 2) {
                    throw StoreInvariantViolation("OCR layout header is invalid")
                }
                layout.blocks.forEach { block ->
                    if (block.readingOrder < 0) {
                        throw StoreInvariantViolation("OCR block reading order is negative")
                    }
                    block.paragraphs.forEach { par ->
                        par.lines.forEach { line ->
                            line.words.forEach { word ->
                                if (word.text.isBlank() || !word.confidence.isFinite() || word.confidence !in 0f..100f) {
                                    throw StoreInvariantViolation("OCR word geometry is outside the source image or malformed")
                                }
                            }
                        }
                    }
                }
            }
            candidate.geometry?.let { geometry ->
                if (geometry.sourceWidthPx <= 0L || geometry.sourceHeightPx <= 0L
                    || geometry.formatVersion != 1L || geometry.coordinateSpace != OCR_WORD_COORDINATE_SPACE
                ) throw StoreInvariantViolation("OCR word geometry header is invalid")
                geometry.words.forEach { word ->
                    if (word.text.isBlank() || word.leftPx < 0L || word.topPx < 0L
                        || word.rightPx <= word.leftPx || word.bottomPx <= word.topPx
                        || word.rightPx > geometry.sourceWidthPx || word.bottomPx > geometry.sourceHeightPx
                        || !word.confidence.isFinite() || word.confidence !in 0.0..100.0
                    ) throw StoreInvariantViolation("OCR word geometry is outside the source image or malformed")
                }
            }
            val payload = candidate.layout?.let { RecognitionPayloadCodec.encode(candidate.recognizedText, it) }
                ?: candidate.geometry?.let { RecognitionPayloadCodec.encode(candidate.recognizedText, it) }
                ?: candidate.recognizedText
            queries.insertRecognition(
                recognition_id = candidate.recognitionId.value,
                page_id = candidate.pageId.value,
                image_version_id = candidate.imageVersionId.value,
                payload = payload,
            )
            if (queries.commitCurrentRecognition(
                    current_recognition_id = candidate.recognitionId.value,
                    page_id = candidate.pageId.value,
                    workspace_id = candidate.workspaceId.value,
                    current_image_version_id = candidate.imageVersionId.value,
                    content_revision = candidate.contentRevision,
                    processing_desired_state = candidate.desiredOperationKey,
                ).value != 1L
            ) throw StoreConflict("OCR candidate became stale during commit")
        }
    }

    /** Begins a snapshot-backed import after the source bytes have been published privately. */
    fun beginImageImport(command: ImportImageCommand) {
        val time = clock.read()
        database.transaction {
            val workspace = queries.workspaceById(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            if (queries.importSessionById(command.importSessionId.value).executeAsOneOrNull() != null) {
                throw StoreConflict("ImportSession ID already exists")
            }
            if (queries.pageById(command.pageId.value).executeAsOneOrNull() != null
                || queries.imageVersionById(command.imageVersionId.value).executeAsOneOrNull() != null
            ) throw StoreConflict("Imported Page or ImageVersion ID already exists")
            val artifact = queries.artifactById(command.sourceArtifact.artifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Import snapshot artifact record is missing")
            if (artifact.state != "STAGED_DURABLE"
                || artifact.relative_storage_path != command.sourceArtifact.relativePath
                || artifact.size_bytes != command.sourceArtifact.sizeBytes
                || artifact.checksum != command.sourceArtifact.sha256
                || artifact.artifact_format_version != command.sourceArtifact.formatVersion
                || artifact.producer_epoch != command.sourceArtifact.producerEpoch
            ) throw StoreInvariantViolation("Import snapshot does not match its staged artifact")
            if (queries.commitArtifact(artifact.artifact_id).value != 1L) {
                throw StoreInvariantViolation("Import snapshot was not staged at begin")
            }
            queries.insertImportSession(
                import_session_id = command.importSessionId.value,
                workspace_id = workspace.workspace_id,
                frozen_operation_parameters = "{\"displayName\":\"${command.displayName.jsonEscape()}\",\"mimeType\":\"${command.mimeType.jsonEscape()}\"}",
                source_snapshot_artifact_id = artifact.artifact_id,
                source_page_count = 1L,
                source_display_name = command.displayName,
            )
            addArtifactReferenceChecked(
                artifact_id = artifact.artifact_id,
                ref_kind = "IMPORT_SESSION",
                ref_owner_id = command.importSessionId.value,
                protection_class = "RECOVERY",
            )
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspace.workspace_id,
            )
        }
    }

    /** Persists all image-import identities before the provider stream is opened or copied. */
    fun prepareImageImportPublication(
        importSessionId: ImportSessionId,
        workspaceId: WorkspaceId,
        mimeType: String,
        displayName: String,
    ): PreparedImageImportPublication {
        require(mimeType.startsWith("image/"))
        require(displayName.isNotBlank())
        return database.transactionWithResult {
            val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val pending = queries.imageImportPublicationIntentById(importSessionId.value).executeAsOneOrNull()
            if (pending != null) {
                if (pending.workspace_id != workspace.workspace_id
                    || pending.source_display_name != displayName
                    || pending.mime_type != mimeType
                ) throw StoreConflict("Repeated image import request conflicts with its publication intent")
                return@transactionWithResult PreparedImageImportPublication(
                    pending.toImageImportPublicationIntent(),
                    cleanupOnFailure = true,
                )
            }

            val existing = queries.importSessionById(importSessionId.value).executeAsOneOrNull()
            if (existing != null) {
                val expectedParameters = imageImportParameters(displayName, mimeType)
                if (existing.workspace_id != workspace.workspace_id
                    || existing.state != "COMPLETED"
                    || existing.source_display_name != displayName
                    || existing.source_page_count != 1L
                    || existing.frozen_operation_parameters != expectedParameters
                ) throw StoreConflict("Repeated image import request conflicts with its stored session")
                val sourceId = existing.source_snapshot_artifact_id
                    ?: throw StoreInvariantViolation("Completed image import has no source snapshot")
                val source = queries.artifactById(sourceId).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Completed image import source record is missing")
                if (source.artifact_kind != "DOCUMENT_PAGE" || source.state != "COMMITTED") {
                    throw StoreConflict("Repeated image import request conflicts with a non-image source")
                }
                val page = queries.pageByImportTuple(importSessionId.value, 0L).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Completed image import page is missing")
                val version = queries.imageVersionById(page.current_image_version_id).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Completed image import version is missing")
                if (version.page_id != page.page_id || version.artifact_id != source.artifact_id) {
                    throw StoreInvariantViolation("Completed image import source tuple is inconsistent")
                }
                return@transactionWithResult PreparedImageImportPublication(
                    ImageImportPublicationIntent(
                        importSessionId,
                        workspaceId,
                        displayName,
                        mimeType,
                        ArtifactId.parse(source.artifact_id),
                        PageId.parse(page.page_id),
                        ImageVersionId.parse(version.image_version_id),
                    ),
                    cleanupOnFailure = false,
                )
            }

            val intent = ImageImportPublicationIntent(
                importSessionId = importSessionId,
                workspaceId = workspaceId,
                displayName = displayName,
                mimeType = mimeType,
                artifactId = ArtifactId.new(),
                pageId = PageId.new(),
                imageVersionId = ImageVersionId.new(),
            )
            queries.insertImageImportPublicationIntent(
                intent.importSessionId.value,
                intent.workspaceId.value,
                intent.displayName,
                intent.mimeType,
                intent.artifactId.value,
                intent.pageId.value,
                intent.imageVersionId.value,
            )
            PreparedImageImportPublication(intent, cleanupOnFailure = true)
        }
    }

    fun pendingImageImportPublicationIntents(limit: Long): List<ImageImportPublicationIntent> {
        require(limit > 0L)
        return queries.pendingImageImportPublicationIntents(limit).executeAsList().map { it.toImageImportPublicationIntent() }
    }

    /** Candidate legacy sessions are filtered by ProductStorage using their frozen MIME parameters. */
    fun openSinglePageImportSessions(limit: Long): List<app.docscan.core.db.ImportSession> {
        require(limit > 0L)
        return queries.openSinglePageImportSessions(limit).executeAsList()
    }

    fun artifactRecord(artifactId: ArtifactId): app.docscan.core.db.ArtifactRecord? =
        queries.artifactById(artifactId.value).executeAsOneOrNull()

    fun importSessionRecord(importSessionId: ImportSessionId): app.docscan.core.db.ImportSession? =
        queries.importSessionById(importSessionId.value).executeAsOneOrNull()

    /** Records recovery identities for a valid pre-intent `.100` single-image import session. */
    fun prepareLegacyImageImportPublication(
        importSessionId: ImportSessionId,
        workspaceId: WorkspaceId,
        displayName: String,
        mimeType: String,
        artifactId: ArtifactId,
        pageId: PageId,
        imageVersionId: ImageVersionId,
    ): ImageImportPublicationIntent = database.transactionWithResult {
        val session = queries.importSessionById(importSessionId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Legacy image ImportSession is missing")
        val expectedParameters = imageImportParameters(displayName, mimeType)
        if (session.workspace_id != workspaceId.value || session.state != "OPEN"
            || session.source_display_name != displayName || session.source_page_count != 1L
            || session.frozen_operation_parameters != expectedParameters
            || session.source_snapshot_artifact_id != artifactId.value
        ) throw StoreConflict("Legacy image ImportSession context is inconsistent")
        val source = queries.artifactById(artifactId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Legacy image source artifact record is missing")
        if (source.artifact_kind != "DOCUMENT_PAGE" || source.state != "COMMITTED") {
            throw StoreConflict("Legacy image source artifact is not committed")
        }
        val references = queries.artifactReferences(artifactId.value).executeAsList()
        if (references.size != 1
            || references.singleOrNull()?.let {
                it.ref_kind == "IMPORT_SESSION" && it.ref_owner_id == importSessionId.value
                    && it.protection_class == "RECOVERY"
            } != true
            || queries.pageByImportTuple(importSessionId.value, 0L).executeAsOneOrNull() != null
            || queries.pageById(pageId.value).executeAsOneOrNull() != null
            || queries.imageVersionById(imageVersionId.value).executeAsOneOrNull() != null
        ) throw StoreConflict("Legacy image ImportSession already has conflicting page metadata")
        val existing = queries.imageImportPublicationIntentById(importSessionId.value).executeAsOneOrNull()
        if (existing != null) {
            val intent = existing.toImageImportPublicationIntent()
            if (intent.workspaceId != workspaceId || intent.displayName != displayName || intent.mimeType != mimeType
                || intent.artifactId != artifactId || intent.pageId != pageId || intent.imageVersionId != imageVersionId
            ) throw StoreConflict("Legacy image recovery intent conflicts with its session")
            return@transactionWithResult intent
        }
        val intent = ImageImportPublicationIntent(importSessionId, workspaceId, displayName, mimeType, artifactId, pageId, imageVersionId)
        queries.insertImageImportPublicationIntent(
            importSessionId.value, workspaceId.value, displayName, mimeType,
            artifactId.value, pageId.value, imageVersionId.value,
        )
        intent
    }

    /** Drops only an image intent that has not already committed any import metadata. */
    fun abandonImageImportPublication(intent: ImageImportPublicationIntent) {
        database.transaction {
            val stored = queries.imageImportPublicationIntentById(intent.importSessionId.value).executeAsOneOrNull()
                ?: return@transaction
            if (!stored.matches(intent)) throw StoreConflict("Image publication intent changed during recovery")
            if (queries.importSessionById(intent.importSessionId.value).executeAsOneOrNull() != null
                || queries.artifactById(intent.artifactId.value).executeAsOneOrNull() != null
                || queries.pageById(intent.pageId.value).executeAsOneOrNull() != null
                || queries.imageVersionById(intent.imageVersionId.value).executeAsOneOrNull() != null
            ) throw StoreInvariantViolation("Cannot abandon an image publication intent after metadata registration")
            if (queries.deleteImageImportPublicationIntent(intent.importSessionId.value, intent.workspaceId.value).value != 1L) {
                throw StoreConflict("Image publication intent changed while abandoning")
            }
        }
    }

    /** Confirms that cleanup is still safe before deleting files owned by an unfinished image intent. */
    fun verifyImageImportPublicationUnregistered(intent: ImageImportPublicationIntent) {
        val stored = queries.imageImportPublicationIntentById(intent.importSessionId.value).executeAsOneOrNull()
            ?: throw StoreConflict("Image publication intent is no longer pending")
        if (!stored.matches(intent)) throw StoreConflict("Image publication intent changed before cleanup")
        if (queries.importSessionById(intent.importSessionId.value).executeAsOneOrNull() != null
            || queries.artifactById(intent.artifactId.value).executeAsOneOrNull() != null
            || queries.pageById(intent.pageId.value).executeAsOneOrNull() != null
            || queries.imageVersionById(intent.imageVersionId.value).executeAsOneOrNull() != null
        ) throw StoreInvariantViolation("Image publication already has registered metadata")
    }

    /** Atomically commits the private image, ImportSession, Page, ImageVersion and intent removal. */
    fun commitImageImportPublication(
        intent: ImageImportPublicationIntent,
        command: ImportImageCommand,
        beforeTransactionCommitForTest: ((ImportImageCommand) -> Unit)? = null,
    ): CapturedPage {
        require(command.importSessionId == intent.importSessionId)
        require(command.workspaceId == intent.workspaceId)
        require(command.pageId == intent.pageId)
        require(command.imageVersionId == intent.imageVersionId)
        require(command.sourceArtifact.artifactId == intent.artifactId)
        require(command.mimeType == intent.mimeType && command.displayName == intent.displayName)
        val time = clock.read()
        return database.transactionWithResult {
            val storedIntent = queries.imageImportPublicationIntentById(intent.importSessionId.value)
                .executeAsOneOrNull()
            val existingSession = queries.importSessionById(intent.importSessionId.value).executeAsOneOrNull()
            if (storedIntent == null) {
                if (existingSession?.state == "COMPLETED") {
                    validateCompletedImageImport(existingSession, intent, command.sourceArtifact)
                    return@transactionWithResult existingImportedImage(intent)
                }
                throw StoreConflict("Image publication intent is missing before import commit")
            }
            if (!storedIntent.matches(intent)) throw StoreConflict("Image publication intent differs from source snapshot")
            val workspace = queries.workspaceById(intent.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val source = command.sourceArtifact
            if (source.relativePath != expectedImagePath(intent.artifactId, source.relativePath.substringAfterLast('.'))
                || source.relativePath.substringAfterLast('.') !in setOf("jpg", "jpeg", "png", "webp", "heic")
            ) throw StoreInvariantViolation("Image source path does not match its deterministic identity")

            val existingArtifact = queries.artifactById(intent.artifactId.value).executeAsOneOrNull()
            if (queries.pageById(intent.pageId.value).executeAsOneOrNull() != null
                || queries.imageVersionById(intent.imageVersionId.value).executeAsOneOrNull() != null
            ) throw StoreConflict("Image publication intent identities are already in use")
            if (existingSession == null) {
                if (existingArtifact != null) throw StoreInvariantViolation("New image intent already has an artifact row")
                queries.insertStagedArtifact(
                    artifact_id = source.artifactId.value,
                    relative_storage_path = source.relativePath,
                    artifact_kind = "DOCUMENT_PAGE",
                    size_bytes = source.sizeBytes,
                    checksum = source.sha256,
                    artifact_format_version = source.formatVersion,
                    creation_revision = 1L,
                    producer_epoch = source.producerEpoch,
                )
                if (queries.commitArtifact(source.artifactId.value).value != 1L) {
                    throw StoreInvariantViolation("Private image import source could not be committed")
                }
                queries.insertImportSession(
                    import_session_id = intent.importSessionId.value,
                    workspace_id = workspace.workspace_id,
                    frozen_operation_parameters = imageImportParameters(intent.displayName, intent.mimeType),
                    source_snapshot_artifact_id = source.artifactId.value,
                    source_page_count = 1L,
                    source_display_name = intent.displayName,
                )
                addArtifactReferenceChecked(
                    artifact_id = source.artifactId.value,
                    ref_kind = "IMPORT_SESSION",
                    ref_owner_id = intent.importSessionId.value,
                    protection_class = "RECOVERY",
                )
            } else {
                val storedSource = existingArtifact
                    ?: throw StoreInvariantViolation("Legacy image source artifact record is missing")
                if (existingSession.state != "OPEN"
                    || existingSession.workspace_id != intent.workspaceId.value
                    || existingSession.source_display_name != intent.displayName
                    || existingSession.source_page_count != 1L
                    || existingSession.source_snapshot_artifact_id != source.artifactId.value
                    || existingSession.frozen_operation_parameters != imageImportParameters(intent.displayName, intent.mimeType)
                    || storedSource.artifact_kind != "DOCUMENT_PAGE" || storedSource.state != "COMMITTED"
                    || storedSource.relative_storage_path != source.relativePath
                    || storedSource.size_bytes != source.sizeBytes || storedSource.checksum != source.sha256
                    || storedSource.artifact_format_version != source.formatVersion
                ) throw StoreConflict("Legacy image source changed before recovery commit")
                val references = queries.artifactReferences(source.artifactId.value).executeAsList()
                if (references.size != 1
                    || references.singleOrNull()?.let {
                        it.ref_kind == "IMPORT_SESSION" && it.ref_owner_id == intent.importSessionId.value
                            && it.protection_class == "RECOVERY"
                    } != true
                ) throw StoreConflict("Legacy image source references changed before recovery commit")
            }
            val openWorkspaceSession = queries.openSessionForWorkspace(intent.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace has no open session")
            val orderIndex = queries.nextVisibleOrder(intent.workspaceId.value).executeAsOne()
            queries.insertImageVersion(
                image_version_id = intent.imageVersionId.value,
                page_id = intent.pageId.value,
                artifact_id = source.artifactId.value,
                parent_version_id = null,
                coordinate_space = "SOURCE_ENCODED_IMAGE_COORDINATES_V1",
                transform_provenance = "import-original-v1",
                artifact_format_version = source.formatVersion,
            )
            queries.insertImportedPage(
                page_id = intent.pageId.value,
                workspace_id = intent.workspaceId.value,
                workspace_session_id = openWorkspaceSession.workspace_session_id,
                order_index = orderIndex,
                current_image_version_id = intent.imageVersionId.value,
                processing_desired_state = command.desiredOperationKey,
                import_session_id = intent.importSessionId.value,
                source_page_index = 0L,
                source_provenance = "${intent.mimeType}:${intent.displayName}",
            )
            addArtifactReferenceChecked(
                artifact_id = source.artifactId.value,
                ref_kind = "IMAGE_VERSION",
                ref_owner_id = intent.imageVersionId.value,
                protection_class = "AUTHORITATIVE",
            )
            if (queries.completeImportSession(intent.importSessionId.value).value != 1L
                || queries.removeImportSnapshotReference(source.artifactId.value, intent.importSessionId.value).value != 1L
            ) throw StoreConflict("Image ImportSession changed while committing its page")
            if (queries.deleteImageImportPublicationIntent(intent.importSessionId.value, intent.workspaceId.value).value != 1L) {
                throw StoreConflict("Image publication intent changed while committing")
            }
            queries.advanceOrderRevision(intent.workspaceId.value)
            queries.setActivePage(intent.pageId.value, intent.workspaceId.value)
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = intent.workspaceId.value,
            )
            beforeTransactionCommitForTest?.invoke(command)
            CapturedPage(intent.pageId, intent.imageVersionId, source.artifactId, orderIndex)
        }
    }

    private fun imageImportParameters(displayName: String, mimeType: String) =
        "{\"displayName\":\"${displayName.jsonEscape()}\",\"mimeType\":\"${mimeType.jsonEscape()}\"}"

    private fun validateCompletedImageImport(
        session: app.docscan.core.db.ImportSession,
        intent: ImageImportPublicationIntent,
        artifact: DurableImageArtifact,
    ) {
        if (session.workspace_id != intent.workspaceId.value
            || session.source_display_name != intent.displayName
            || session.source_page_count != 1L
            || session.source_snapshot_artifact_id != artifact.artifactId.value
            || session.frozen_operation_parameters != imageImportParameters(intent.displayName, intent.mimeType)
        ) throw StoreConflict("Repeated image import request conflicts with its completed session")
        val stored = queries.artifactById(artifact.artifactId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Completed image import source record is missing")
        if (stored.artifact_kind != "DOCUMENT_PAGE" || stored.state != "COMMITTED"
            || stored.relative_storage_path != artifact.relativePath || stored.size_bytes != artifact.sizeBytes
            || stored.checksum != artifact.sha256 || stored.artifact_format_version != artifact.formatVersion
        ) throw StoreConflict("Repeated image import request supplied different source bytes")
    }

    private fun existingImportedImage(intent: ImageImportPublicationIntent): CapturedPage {
        val page = queries.pageByImportTuple(intent.importSessionId.value, 0L).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Completed image import page is missing")
        val version = queries.imageVersionById(page.current_image_version_id).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Completed image import version is missing")
        if (page.page_id != intent.pageId.value || version.image_version_id != intent.imageVersionId.value
            || version.page_id != page.page_id || version.artifact_id != intent.artifactId.value
        ) throw StoreInvariantViolation("Completed image import identities differ from publication intent")
        return CapturedPage(intent.pageId, intent.imageVersionId, intent.artifactId, page.order_index)
    }

    private fun app.docscan.core.db.ImageImportPublicationIntent.toImageImportPublicationIntent() = ImageImportPublicationIntent(
        ImportSessionId.parse(import_session_id),
        WorkspaceId.parse(workspace_id),
        source_display_name,
        mime_type,
        ArtifactId.parse(artifact_id),
        PageId.parse(page_id),
        ImageVersionId.parse(image_version_id),
    )

    private fun app.docscan.core.db.ImageImportPublicationIntent.matches(intent: ImageImportPublicationIntent) =
        import_session_id == intent.importSessionId.value && workspace_id == intent.workspaceId.value
            && source_display_name == intent.displayName && mime_type == intent.mimeType
            && artifact_id == intent.artifactId.value && page_id == intent.pageId.value
            && image_version_id == intent.imageVersionId.value

    private fun expectedImagePath(artifactId: ArtifactId, extension: String) =
        "objects/${artifactId.value.take(2)}/${artifactId.value}.$extension"

    /** Registers a fully copied, privately published PDF as a resumable multi-page source. */
    fun preparePdfImportPublication(
        importSessionId: ImportSessionId,
        workspaceId: WorkspaceId,
        displayName: String,
    ): Boolean {
        require(displayName.isNotBlank())
        return database.transactionWithResult {
            val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val existingSession = queries.importSessionById(importSessionId.value).executeAsOneOrNull()
            if (existingSession != null) {
                if (existingSession.workspace_id != workspace.workspace_id
                    || (existingSession.source_page_count ?: 0L) <= 0L
                    || existingSession.source_display_name != displayName
                    || existingSession.state !in setOf("OPEN", "COMPLETED")
                ) throw StoreConflict("Repeated PDF import request conflicts with its stored session")
                val sourceId = existingSession.source_snapshot_artifact_id
                    ?: throw StoreInvariantViolation("Existing PDF import has no source snapshot")
                val source = queries.artifactById(sourceId).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Existing PDF import source record is missing")
                if (source.artifact_kind != "IMPORT_PDF_SOURCE") {
                    throw StoreConflict("Repeated PDF import request conflicts with a non-PDF import session")
                }
                return@transactionWithResult false
            }
            val existingIntent = queries.pdfImportPublicationIntentById(importSessionId.value).executeAsOneOrNull()
            if (existingIntent != null) {
                if (existingIntent.workspace_id != workspace.workspace_id
                    || existingIntent.source_display_name != displayName
                ) throw StoreConflict("Repeated PDF import request conflicts with its publication intent")
                return@transactionWithResult true
            }
            queries.insertPdfImportPublicationIntent(importSessionId.value, workspace.workspace_id, displayName)
            true
        }
    }

    fun pendingPdfImportPublicationIntents(limit: Long): List<PdfImportPublicationIntent> {
        require(limit > 0L)
        return queries.pendingPdfImportPublicationIntents(limit).executeAsList().map {
            PdfImportPublicationIntent(
                importSessionId = ImportSessionId.parse(it.import_session_id),
                workspaceId = WorkspaceId.parse(it.workspace_id),
                displayName = it.source_display_name,
            )
        }
    }

    /** Drops a publication intent only after its exact private files have been reconciled. */
    fun abandonPdfImportPublication(intent: PdfImportPublicationIntent) {
        database.transaction {
            val stored = queries.pdfImportPublicationIntentById(intent.importSessionId.value).executeAsOneOrNull()
                ?: return@transaction
            if (stored.workspace_id != intent.workspaceId.value || stored.source_display_name != intent.displayName) {
                throw StoreConflict("PDF publication intent changed during recovery")
            }
            if (queries.importSessionById(intent.importSessionId.value).executeAsOneOrNull() != null
                || queries.artifactById(intent.importSessionId.value).executeAsOneOrNull() != null
            ) throw StoreInvariantViolation("Cannot abandon a PDF publication intent after metadata registration")
            if (queries.deletePdfImportPublicationIntent(intent.importSessionId.value, intent.workspaceId.value).value != 1L) {
                throw StoreConflict("PDF publication intent changed while abandoning")
            }
        }
    }

    fun beginPdfImport(command: BeginPdfImportCommand): ImportSessionId {
        val time = clock.read()
        return database.transactionWithResult {
            val workspace = queries.workspaceById(command.workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            val existing = queries.importSessionById(command.importSessionId.value).executeAsOneOrNull()
            if (existing != null) {
                if (existing.workspace_id != command.workspaceId.value
                    || existing.state !in setOf("OPEN", "COMPLETED")
                    || existing.source_page_count != command.sourcePageCount
                    || existing.source_display_name != command.displayName
                ) throw StoreConflict("Repeated PDF import request no longer matches its stored session")
                val sourceId = existing.source_snapshot_artifact_id
                    ?: throw StoreInvariantViolation("Existing PDF import has no source snapshot")
                val source = queries.artifactById(sourceId).executeAsOneOrNull()
                    ?: throw StoreInvariantViolation("Existing PDF import source record is missing")
                if (source.artifact_id != command.sourceArtifact.artifactId.value
                    || source.artifact_kind != "IMPORT_PDF_SOURCE"
                    || source.state != "COMMITTED"
                    || source.checksum != command.sourceArtifact.sha256
                    || source.size_bytes != command.sourceArtifact.sizeBytes
                    || source.relative_storage_path != command.sourceArtifact.relativePath
                    || source.artifact_format_version != command.sourceArtifact.formatVersion
                ) throw StoreConflict("Repeated request supplied different PDF bytes")
                val pending = queries.pdfImportPublicationIntentById(command.importSessionId.value).executeAsOneOrNull()
                if (pending != null) {
                    if (pending.workspace_id != command.workspaceId.value
                        || pending.source_display_name != command.displayName
                    ) throw StoreConflict("PDF publication intent conflicts with its existing session")
                    queries.deletePdfImportPublicationIntent(command.importSessionId.value, command.workspaceId.value)
                }
                return@transactionWithResult command.importSessionId
            }
            val pending = queries.pdfImportPublicationIntentById(command.importSessionId.value).executeAsOneOrNull()
            if (pending != null && (pending.workspace_id != command.workspaceId.value
                    || pending.source_display_name != command.displayName)
            ) throw StoreConflict("PDF publication intent differs from the completed source snapshot")
            val file = command.sourceArtifact
            queries.insertStagedArtifact(
                artifact_id = file.artifactId.value,
                relative_storage_path = file.relativePath,
                artifact_kind = "IMPORT_PDF_SOURCE",
                size_bytes = file.sizeBytes,
                checksum = file.sha256,
                artifact_format_version = file.formatVersion,
                creation_revision = 1L,
                producer_epoch = file.producerEpoch,
            )
            if (queries.commitArtifact(file.artifactId.value).value != 1L) {
                throw StoreInvariantViolation("Private PDF import source could not be committed")
            }
            queries.insertImportSession(
                import_session_id = command.importSessionId.value,
                workspace_id = workspace.workspace_id,
                frozen_operation_parameters = "{\"mimeType\":\"application/pdf\",\"pdfTitle\":\"${(command.documentTitle ?: "").jsonEscape()}\",\"renderDpi\":200,\"maxLongSidePx\":3000,\"textReusePolicy\":\"ENG-PDF-IMPORT-01\"}",
                source_snapshot_artifact_id = file.artifactId.value,
                source_page_count = command.sourcePageCount,
                source_display_name = command.displayName,
            )
            addArtifactReferenceChecked(
                artifact_id = file.artifactId.value,
                ref_kind = "IMPORT_SESSION",
                ref_owner_id = command.importSessionId.value,
                protection_class = "RECOVERY",
            )
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspace.workspace_id,
            )
            if (pending != null && queries.deletePdfImportPublicationIntent(
                    command.importSessionId.value,
                    command.workspaceId.value,
                ).value != 1L
            ) throw StoreConflict("PDF publication intent changed while registering the import session")
            command.importSessionId
        }
    }

    fun pdfImportSnapshot(importSessionId: ImportSessionId): PdfImportSourceSnapshot? {
        val session = queries.importSessionById(importSessionId.value).executeAsOneOrNull() ?: return null
        if (session.state != "OPEN" || session.source_page_count == null) return null
        val sourceId = session.source_snapshot_artifact_id ?: return null
        val source = queries.artifactById(sourceId).executeAsOneOrNull() ?: return null
        if (source.artifact_kind != "IMPORT_PDF_SOURCE" || source.state != "COMMITTED") return null
        return PdfImportSourceSnapshot(
            ImportSessionId.parse(session.import_session_id),
            WorkspaceId.parse(session.workspace_id),
            ArtifactId.parse(source.artifact_id),
            source.relative_storage_path,
            source.size_bytes,
            source.checksum ?: return null,
            source.artifact_format_version,
            source.producer_epoch ?: return null,
            session.source_page_count,
            session.source_display_name,
            session.last_error,
            queries.importedPdfPageIndices(importSessionId.value).executeAsList().toSet(),
        )
    }

    fun incompletePdfImportSessions(): List<PdfImportSourceSnapshot> =
        queries.openPdfImportSessionIds().executeAsList().mapNotNull {
            pdfImportSnapshot(ImportSessionId.parse(it))
        }

    fun importedPdfPageIds(importSessionId: ImportSessionId): List<PageId> =
        queries.importedPdfPageIds(importSessionId.value).executeAsList().map(PageId::parse)

    fun pdfImportSessionState(importSessionId: ImportSessionId): String? =
        queries.importSessionById(importSessionId.value).executeAsOneOrNull()?.state

    fun markPdfImportSourceUnavailable(importSessionId: ImportSessionId) {
        database.transaction {
            if (queries.markImportSourceUnavailable(importSessionId.value).value != 1L) {
                throw StoreConflict("PDF ImportSession is no longer open")
            }
        }
    }

    fun recordPdfImportFailure(importSessionId: ImportSessionId, message: String) {
        val diagnostic = message.trim().take(1_000).ifBlank { "PDF import failed" }
        database.transaction {
            if (queries.recordPdfImportFailure(diagnostic, importSessionId.value).value != 1L) {
                throw StoreConflict("PDF ImportSession is no longer open")
            }
        }
    }

    /** Materializes one source image and completes its ImportSession in one transaction. */
    fun commitImportedImage(command: ImportImageCommand): CapturedPage = database.transactionWithResult {
        val session = queries.importSessionById(command.importSessionId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("ImportSession is missing")
        if (session.workspace_id != command.workspaceId.value || session.state != "OPEN") {
            throw StoreConflict("ImportSession is not open in this workspace")
        }
        if (queries.pageByImportTuple(command.importSessionId.value, 0L).executeAsOneOrNull() != null) {
            throw StoreConflict("Source image was already materialized")
        }
        val artifact = queries.artifactById(command.sourceArtifact.artifactId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Import snapshot artifact record is missing")
        if (artifact.artifact_id != session.source_snapshot_artifact_id || artifact.state != "COMMITTED") {
            throw StoreInvariantViolation("ImportSession snapshot is not committed")
        }
        val openWorkspaceSession = queries.openSessionForWorkspace(command.workspaceId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Workspace has no open session")
        val orderIndex = queries.nextVisibleOrder(command.workspaceId.value).executeAsOne()
        queries.insertImageVersion(
            image_version_id = command.imageVersionId.value,
            page_id = command.pageId.value,
            artifact_id = artifact.artifact_id,
            parent_version_id = null,
            coordinate_space = "SOURCE_ENCODED_IMAGE_COORDINATES_V1",
            transform_provenance = "import-original-v1",
            artifact_format_version = artifact.artifact_format_version,
        )
        queries.insertImportedPage(
            page_id = command.pageId.value,
            workspace_id = command.workspaceId.value,
            workspace_session_id = openWorkspaceSession.workspace_session_id,
            order_index = orderIndex,
            current_image_version_id = command.imageVersionId.value,
            processing_desired_state = command.desiredOperationKey,
            import_session_id = command.importSessionId.value,
            source_page_index = 0L,
            source_provenance = "${command.mimeType}:${command.displayName}",
        )
        addArtifactReferenceChecked(
            artifact_id = artifact.artifact_id,
            ref_kind = "IMAGE_VERSION",
            ref_owner_id = command.imageVersionId.value,
            protection_class = "AUTHORITATIVE",
        )
        if (queries.completeImportSession(command.importSessionId.value).value != 1L) {
            throw StoreConflict("ImportSession changed while materializing source image")
        }
        if (queries.removeImportSnapshotReference(artifact.artifact_id, command.importSessionId.value).value != 1L) {
            throw StoreInvariantViolation("Import snapshot protection reference is missing")
        }
        queries.advanceOrderRevision(command.workspaceId.value)
        queries.setActivePage(command.pageId.value, command.workspaceId.value)
        CapturedPage(
            pageId = command.pageId,
            imageVersionId = command.imageVersionId,
            artifactId = ArtifactId.parse(artifact.artifact_id),
            orderIndex = orderIndex,
        )
    }

    /** Commits one rendered PDF page idempotently while retaining the private source until all pages exist. */
    fun commitPdfImportPage(
        command: CommitPdfImportPageCommand,
        beforeTransactionCommitForTest: ((CommitPdfImportPageCommand) -> Unit)? = null,
    ): CapturedPage = database.transactionWithResult {
        val session = queries.importSessionById(command.importSessionId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("PDF ImportSession is missing")
        if (session.workspace_id != command.workspaceId.value || session.state != "OPEN") {
            throw StoreConflict("PDF ImportSession is not open in this workspace")
        }
        if (command.sourcePageIndex >= (session.source_page_count ?: 0L)) {
            throw StoreInvariantViolation("PDF source page index is outside the inspected document")
        }
        val existingPage = queries.pageByImportTuple(command.importSessionId.value, command.sourcePageIndex).executeAsOneOrNull()
        if (existingPage != null) {
            val version = queries.imageVersionById(existingPage.current_image_version_id).executeAsOne()
            return@transactionWithResult CapturedPage(
                PageId.parse(existingPage.page_id),
                ImageVersionId.parse(existingPage.current_image_version_id),
                ArtifactId.parse(version.artifact_id),
                existingPage.order_index,
            )
        }
        val sourceArtifactId = session.source_snapshot_artifact_id
            ?: throw StoreInvariantViolation("PDF ImportSession has no source snapshot")
        val sourceArtifact = queries.artifactById(sourceArtifactId).executeAsOne()
        if (sourceArtifact.artifact_kind != "IMPORT_PDF_SOURCE" || sourceArtifact.state != "COMMITTED") {
            throw StoreInvariantViolation("PDF source snapshot is not committed")
        }
        if (queries.pageById(command.pageId.value).executeAsOneOrNull() != null
            || queries.imageVersionById(command.imageVersionId.value).executeAsOneOrNull() != null
        ) throw StoreConflict("Imported Page or ImageVersion ID already exists")
        val file = command.imageArtifact
        val staged = queries.artifactById(file.artifactId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Rendered PDF page artifact was not staged")
        if (staged.state != "STAGED_DURABLE"
            || staged.artifact_kind != "DOCUMENT_PAGE"
            || staged.relative_storage_path != file.relativePath
            || staged.size_bytes != file.sizeBytes
            || staged.checksum != file.sha256
            || staged.artifact_format_version != file.formatVersion
            || staged.producer_epoch != file.producerEpoch
        ) throw StoreInvariantViolation("Rendered PDF page differs from its staged durable artifact")
        if (queries.commitArtifact(file.artifactId.value).value != 1L) {
            throw StoreInvariantViolation("Rendered PDF page image could not be committed")
        }
        val openWorkspaceSession = queries.openSessionForWorkspace(command.workspaceId.value).executeAsOneOrNull()
            ?: throw StoreInvariantViolation("Workspace has no open session")
        val orderIndex = queries.nextVisibleOrder(command.workspaceId.value).executeAsOne()
        queries.insertImageVersion(
            image_version_id = command.imageVersionId.value,
            page_id = command.pageId.value,
            artifact_id = file.artifactId.value,
            parent_version_id = null,
            coordinate_space = "PDF_RENDERED_PAGE_PIXELS_TOP_LEFT",
            transform_provenance = "pdf-import-render-v1",
            artifact_format_version = file.formatVersion,
        )
        queries.insertImportedPage(
            page_id = command.pageId.value,
            workspace_id = command.workspaceId.value,
            workspace_session_id = openWorkspaceSession.workspace_session_id,
            order_index = orderIndex,
            current_image_version_id = command.imageVersionId.value,
            processing_desired_state = command.desiredOperationKey,
            import_session_id = command.importSessionId.value,
            source_page_index = command.sourcePageIndex,
            source_provenance = "application/pdf:${command.sourceDisplayName}",
        )
        addArtifactReferenceChecked(
            artifact_id = file.artifactId.value,
            ref_kind = "IMAGE_VERSION",
            ref_owner_id = command.imageVersionId.value,
            protection_class = "AUTHORITATIVE",
        )
        if (command.recognizedText != null) {
            val recognitionId = command.recognitionId ?: throw StoreInvariantViolation("PDF text recognition has no ID")
            queries.insertRecognition(
                recognition_id = recognitionId.value,
                page_id = command.pageId.value,
                image_version_id = command.imageVersionId.value,
                payload = command.recognizedText,
            )
            if (queries.commitCurrentRecognition(
                    current_recognition_id = recognitionId.value,
                    page_id = command.pageId.value,
                    workspace_id = command.workspaceId.value,
                    current_image_version_id = command.imageVersionId.value,
                    content_revision = 1L,
                    processing_desired_state = command.desiredOperationKey,
                ).value != 1L
            ) throw StoreInvariantViolation("PDF text layer could not be attached to its imported page")
        }
        queries.advanceOrderRevision(command.workspaceId.value)
        queries.setActivePage(command.pageId.value, command.workspaceId.value)
        queries.clearPdfImportFailure(command.importSessionId.value)
        if (queries.completePdfImportSession(command.importSessionId.value, command.importSessionId.value).value == 1L) {
            if (queries.removeImportSnapshotReference(sourceArtifactId, command.importSessionId.value).value != 1L) {
                throw StoreInvariantViolation("Completed PDF import lost its source recovery reference")
            }
        }
        // A no-op-by-default instrumentation seam: this callback runs after the page SQL mutations,
        // while the enclosing SQLite transaction is still open and before SQLDelight commits it.
        beforeTransactionCommitForTest?.invoke(command)
        CapturedPage(command.pageId, command.imageVersionId, file.artifactId, orderIndex)
    }

    fun recoveryArtifactBatch(limit: Long): List<RecoveryArtifact> {
        require(limit in 1L..100L)
        val cursor = queries.recoveryCursorByDomain(RECOVERY_CURSOR_DOMAIN).executeAsOneOrNull()
            ?.cursor_payload?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return queries.recoveryArtifactBatch(cursor, limit).executeAsList().map {
            RecoveryArtifact(
                recoverySequence = it.recovery_sequence,
                artifactId = ArtifactId.parse(it.artifact_id),
                relativePath = it.relative_storage_path,
                sizeBytes = it.size_bytes,
                sha256 = it.checksum,
                state = it.state,
            )
        }
    }

    /** Atomically claims only committed, currently unreferenced private artifacts. */
    fun claimUnreferencedArtifacts(limit: Long): List<ArtifactDeletionClaim> {
        require(limit in 1L..100L)
        return database.transactionWithResult {
            queries.unreferencedArtifacts(limit).executeAsList().mapNotNull { artifact ->
                val token = java.util.UUID.randomUUID().toString()
                if (queries.claimArtifactForDeletion(token, artifact.artifact_id).value != 1L) return@mapNotNull null
                ArtifactDeletionClaim(
                    artifactId = ArtifactId.parse(artifact.artifact_id),
                    claimToken = token,
                    relativePath = artifact.relative_storage_path,
                    sizeBytes = artifact.size_bytes,
                    sha256 = artifact.checksum,
                )
            }
        }
    }

    /** Lists interrupted physical deletions so CleanupArbiter can converge after process death. */
    fun claimedArtifactDeletions(limit: Long): List<ArtifactDeletionClaim> {
        require(limit in 1L..100L)
        return queries.gcClaimedArtifacts(limit).executeAsList().mapNotNull { artifact ->
            ArtifactDeletionClaim(
                artifactId = ArtifactId.parse(artifact.artifact_id),
                claimToken = artifact.gc_claim_token,
                relativePath = artifact.relative_storage_path,
                sizeBytes = artifact.size_bytes,
                sha256 = artifact.checksum,
            )
        }
    }

    fun finalizeArtifactDeletion(claim: ArtifactDeletionClaim) {
        database.transaction {
            if (queries.finalizeArtifactDeletion(claim.artifactId.value, claim.claimToken).value != 1L) {
                throw StoreConflict("Artifact deletion claim is stale or gained a protection reference")
            }
        }
    }

    fun releaseArtifactDeletionClaim(claim: ArtifactDeletionClaim) {
        database.transaction {
            if (queries.releaseArtifactDeletionClaim(claim.artifactId.value, claim.claimToken).value != 1L) {
                throw StoreConflict("Artifact deletion claim is stale")
            }
        }
    }

    fun quarantineClaimedArtifact(claim: ArtifactDeletionClaim) {
        database.transaction {
            if (queries.quarantineClaimedArtifact(claim.artifactId.value, claim.claimToken).value != 1L) {
                throw StoreConflict("Artifact deletion claim is stale")
            }
        }
    }

    fun acknowledgeRecoveredArtifact(artifact: RecoveryArtifact) {
        database.transaction {
            val current = queries.artifactById(artifact.artifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Recovery artifact disappeared")
            if (current.state !in setOf("STAGED_DURABLE", "COMMITTED")) {
                throw StoreConflict("Recovery artifact state changed")
            }
            queries.writeRecoveryCursor(RECOVERY_CURSOR_DOMAIN, artifact.recoverySequence.toString())
        }
    }

    fun quarantineRecoveredArtifact(artifact: RecoveryArtifact) {
        database.transaction {
            val current = queries.artifactById(artifact.artifactId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Recovery artifact disappeared")
            if (current.state != "QUARANTINED" && queries.quarantineArtifact(artifact.artifactId.value).value != 1L) {
                throw StoreConflict("Recovery artifact state changed while quarantining")
            }
            queries.writeRecoveryCursor(RECOVERY_CURSOR_DOMAIN, artifact.recoverySequence.toString())
        }
    }

    fun resetRecoveryCursor() {
        database.transaction { queries.writeRecoveryCursor(RECOVERY_CURSOR_DOMAIN, "") }
    }

    fun reorderPages(workspaceId: WorkspaceId, expectedOrderRevision: Long, orderedPageIds: List<app.docscan.core.model.PageId>) {
        require(expectedOrderRevision >= 0L)
        val time = clock.read()
        database.transaction {
            val workspace = queries.workspaceById(workspaceId.value).executeAsOneOrNull()
                ?: throw StoreInvariantViolation("Workspace is missing")
            if (workspace.order_revision != expectedOrderRevision) {
                throw StoreConflict("Workspace order changed since reorder started")
            }
            val visible = queries.visiblePages(workspaceId.value).executeAsList()
            val currentIds = visible.map { it.page_id }.toSet()
            val requestedIds = orderedPageIds.map { it.value }
            if (requestedIds.size != currentIds.size || requestedIds.toSet() != currentIds) {
                throw StoreInvariantViolation("Reorder must contain each visible Page exactly once")
            }
            val offset = (visible.maxOfOrNull { it.order_index } ?: -1L) + visible.size + 1L
            visible.forEachIndexed { index, page ->
                queries.setPageOrder(offset + index, page.page_id, workspaceId.value)
            }
            orderedPageIds.forEachIndexed { index, pageId ->
                queries.setPageOrder(index.toLong(), pageId.value, workspaceId.value)
            }
            if (queries.advanceOrderRevisionIfExpected(workspaceId.value, expectedOrderRevision).value != 1L) {
                throw StoreConflict("Workspace order changed while reordering")
            }
            queries.updateWorkspaceActivity(
                last_user_activity_wall = time.wallTimeMillis,
                last_user_activity_monotonic = time.monotonicTimeNanos,
                monotonic_epoch_token = time.monotonicEpochToken,
                workspace_id = workspaceId.value,
            )
        }
    }

    fun openSessionId(workspaceId: WorkspaceId): WorkspaceSessionId? =
        queries.openSessionForWorkspace(workspaceId.value)
            .executeAsOneOrNull()
            ?.workspace_session_id
            ?.let(WorkspaceSessionId::parse)

    private companion object {
        const val MAX_WORKSPACE_INTEGRITY_FINDINGS = 500L
        const val MAX_ARTIFACT_REFERENCE_FINDINGS = 500L
        const val RECOVERY_CURSOR_DOMAIN = "artifact-integrity-v1"
        const val DELETE_UNDO_WINDOW_MILLIS = 8_000L
        const val DELETE_UNDO_WINDOW_NANOS = 8_000_000_000L
        const val RECENT_DOCUMENT_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
