package app.docscan.android

import android.content.Context
import android.os.CancellationSignal
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.artifact.AtomicArtifactPublisher
import app.docscan.core.artifact.ArtifactStagingSync
import app.docscan.core.artifact.ArtifactStagingWriteCheckpoint
import app.docscan.core.artifact.ArtifactStagingOutputFlush
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.cleanup.CleanupArbiter
import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.SaveManifestId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.CapturedPage
import app.docscan.core.store.DeleteReceipt
import app.docscan.core.store.ReplacePageImageCommand
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.store.ImportImageCommand
import app.docscan.core.store.DurableImageArtifact
import app.docscan.core.store.DurableExportFile
import app.docscan.core.store.CommitLocalExportCommand
import app.docscan.core.store.LocalExportFileCommand
import app.docscan.core.store.DestinationGrantSnapshot
import app.docscan.core.store.CommittedExportOutput
import app.docscan.core.store.BeginExportAttemptCommand
import app.docscan.core.store.ExportAttemptSnapshot
import app.docscan.core.store.ExportAttemptOutcome
import app.docscan.core.store.ProviderOperationObservationCommand
import app.docscan.core.store.ProviderQualificationCommand
import app.docscan.core.store.ProviderQualificationSnapshot
import app.docscan.core.store.ExternalExportPackageCommand
import app.docscan.core.store.ExternalExportPackagePlan
import app.docscan.core.store.ExternalExportPackageOutputCommand
import app.docscan.core.store.StateStore
import app.docscan.core.store.StoredPage
import app.docscan.core.store.WorkspaceProjection
import app.docscan.core.store.WorkspaceBootstrapState
import java.io.InputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

private const val STARTUP_REFERENCE_INTEGRITY_FINDING_LIMIT = 500L
private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

/** Android persistence wiring. The database and image objects are both app-private. */
class ProductStorage(
    context: Context,
    private val databaseName: String = "docscan-product.db",
    private val artifactDirectoryName: String = "docscan-artifacts",
    private val saveCandidateDirectoryName: String = "docscan-save-candidates",
    cleanupSavedPdfEditCacheOnStartup: Boolean = true,
    private val directorySyncFactory: () -> DirectorySync = { AndroidDirectorySync() },
    private val artifactAtomicPublisher: AtomicArtifactPublisher = AndroidAtomicArtifactPublisher(),
    private val artifactStagingSync: ArtifactStagingSync = ArtifactStagingSync { channel -> channel.force(true) },
    private val artifactStagingWriteCheckpoint: ArtifactStagingWriteCheckpoint = ArtifactStagingWriteCheckpoint { _, _ -> },
    private val artifactStagingOutputFlush: ArtifactStagingOutputFlush = ArtifactStagingOutputFlush { output, _, _ -> output.flush() },
) {
    /** Instrumentation-only observer for proving durable PDF page publication boundaries. */
    internal var pdfImportPublicationBoundaryObserverForTest:
        ((boundary: String, sourcePageIndex: Int, artifact: DurableImageArtifact) -> Unit)? = null
    /** Instrumentation-only observer invoked inside the PDF page transaction immediately before commit. */
    internal var pdfImportPageTransactionBoundaryObserverForTest:
        ((command: app.docscan.core.store.CommitPdfImportPageCommand) -> Unit)? = null
    /** Instrumentation-only observer after one PDF import Page has durably committed. */
    internal var pdfImportPageCommittedObserverForTest:
        ((importSessionId: String, completedPages: Int, totalPages: Int) -> Unit)? = null
    /** Instrumentation-only observer after provider read-back succeeds but before export outcome commit. */
    internal var exportReadBackBoundaryObserverForTest:
        ((exportAttemptId: String, exportArtifactId: String, outputArtifactId: String, documentUri: String, sha256: String) -> Unit)? = null
    /** Instrumentation-only observer after createDocument returns but before its output stream opens. */
    internal var exportCreatedBeforeStreamObserverForTest:
        ((exportAttemptId: String, exportArtifactId: String, outputArtifactId: String, documentUri: String) -> Unit)? = null
    /** Instrumentation-only observer after the first provider bytes are flushed and before more bytes copy. */
    internal var exportFirstProviderWriteBoundaryObserverForTest:
        ((exportAttemptId: String, exportArtifactId: String, outputArtifactId: String, documentUri: String, firstChunkBytes: Int, expectedSizeBytes: Long, expectedSha256: String) -> Unit)? = null
    /** Instrumentation observer after provider stream close and before gateway read-back starts. */
    internal var exportStreamClosedBeforeReadBackObserverForTest:
        ((exportAttemptId: String, exportArtifactId: String, outputArtifactId: String, documentUri: String, expectedSizeBytes: Long, expectedSha256: String) -> Unit)? = null
    /** Instrumentation-only observer after durable source-PDF publication and before DB registration. */
    internal var pdfImportSourcePublicationBoundaryObserverForTest:
        ((intent: app.docscan.core.store.PdfImportPublicationIntent, artifact: DurableExportFile) -> Unit)? = null
    /** Instrumentation observer after the exact intent-owned image becomes durable. */
    internal var imageImportSourcePublicationBoundaryObserverForTest:
        ((intent: app.docscan.core.store.ImageImportPublicationIntent, artifact: DurableImageArtifact) -> Unit)? = null
    /** Instrumentation observer inside the image metadata transaction immediately before commit. */
    internal var imageImportTransactionBoundaryObserverForTest:
        ((command: ImportImageCommand) -> Unit)? = null

    private val appContext = context.applicationContext
    private val database: DocScanDatabase
    private val driver: AndroidSqliteDriver
    private val artifacts: FileArtifactRepository
    private val store: StateStore
    val workspaceId: WorkspaceId
    val workspaceIntegrityFindingsAtStartup: List<app.docscan.core.store.WorkspaceIntegrityFinding>
    val artifactReferenceIntegrityFindingsAtStartup: List<app.docscan.core.store.ArtifactReferenceIntegrityFinding>
    val startupIntegrityFindingCount: Int
    val startupIntegrityBlocked: Boolean
    val quarantinedArtifactsAtStartup: Int
    val abandonedProcessingScratchAtStartup: Int
    val abandonedSavedPdfEditsAtStartup: Int
    val abandonedLocalOutputCompositionsAtStartup: Int
    val failedCommittedSaveCandidateCleanupsAtStartup: Int

    init {
        abandonedProcessingScratchAtStartup = cleanupInterruptedProcessingScratch()
        if (cleanupSavedPdfEditCacheOnStartup) {
            appContext.cacheDir.listFiles { file -> file.name.startsWith("saved-pdf-edit-") && file.name.endsWith(".pdf") }
                ?.forEach { it.delete() }
        }
        driver = AndroidSqliteDriver(
            schema = DocScanDatabase.Schema,
            context = appContext,
            name = databaseName,
            callback = object : AndroidSqliteDriver.Callback(DocScanDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                    check(db.enableWriteAheadLogging()) { "SQLite WAL mode is required" }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                    val cursor = db.query("PRAGMA synchronous")
                    cursor.use {
                        check(it.moveToFirst() && it.getInt(0) == 2) {
                            "SQLite synchronous=FULL is required"
                        }
                    }
                    check(db.isWriteAheadLoggingEnabled) { "SQLite WAL mode is required" }
                }
            },
        )
        database = DocScanDatabase(driver)
        store = StateStore(database, AndroidClock())
        val bootstrapState = store.workspaceBootstrapState()
        val existingWorkspaceId = store.firstWorkspaceId()
        workspaceId = when {
            existingWorkspaceId != null -> existingWorkspaceId
            bootstrapState == WorkspaceBootstrapState.EMPTY -> WorkspaceId.new().also {
                store.createInitialWorkspace(it, WorkspaceSessionId.new())
            }
            else -> WorkspaceId.new()
        }
        workspaceIntegrityFindingsAtStartup = if (bootstrapState == WorkspaceBootstrapState.ORPHANED) {
            listOf(app.docscan.core.store.WorkspaceIntegrityFinding("WORKSPACE_MISSING_WITH_PERSISTED_STATE", ""))
        } else {
            store.workspaceImageReferenceIntegrityFindings(workspaceId)
        }
        // The owner audit is global because stale owners can refer to exports or provider
        // operations outside the active workspace. A saturated inventory is not complete.
        artifactReferenceIntegrityFindingsAtStartup =
            store.artifactReferenceOwnerIntegrityFindings(STARTUP_REFERENCE_INTEGRITY_FINDING_LIMIT)
        startupIntegrityFindingCount = workspaceIntegrityFindingsAtStartup.size +
            artifactReferenceIntegrityFindingsAtStartup.size
        // Hitting either diagnostic cap is also a block: the inventory may be incomplete.
        startupIntegrityBlocked = bootstrapState == WorkspaceBootstrapState.ORPHANED
            || workspaceIntegrityFindingsAtStartup.isNotEmpty()
            || workspaceIntegrityFindingsAtStartup.size.toLong() >= STARTUP_REFERENCE_INTEGRITY_FINDING_LIMIT
            || artifactReferenceIntegrityFindingsAtStartup.isNotEmpty()
            || artifactReferenceIntegrityFindingsAtStartup.size.toLong() >= STARTUP_REFERENCE_INTEGRITY_FINDING_LIMIT
        val root = appContext.noBackupFilesDir.toPath().resolve(artifactDirectoryName)
        artifacts = FileArtifactRepository(
            privateRoot = root,
            directorySync = directorySyncFactory(),
            producerEpoch = UUID.randomUUID().toString(),
            atomicPublisher = artifactAtomicPublisher,
            stagingSync = artifactStagingSync,
            stagingWriteCheckpoint = artifactStagingWriteCheckpoint,
            stagingOutputFlush = artifactStagingOutputFlush,
        )
        if (startupIntegrityBlocked) {
            // Preserve all rows and files for diagnosis until a quarantine policy is approved.
            abandonedSavedPdfEditsAtStartup = 0
            abandonedLocalOutputCompositionsAtStartup = 0
            failedCommittedSaveCandidateCleanupsAtStartup = 0
            quarantinedArtifactsAtStartup = 0
        } else {
            recoverLegacyOpenImageImportSessions()
            recoverPendingImageImportPublications()
            recoverPendingPdfImportPublications()
            store.markWritingExportAttemptsUnknownAtStartup()
            abandonedSavedPdfEditsAtStartup = store.abandonIncompleteSavedPdfEditManifests(STARTUP_RECOVERY_BATCH_SIZE)
            abandonedLocalOutputCompositionsAtStartup = recoverInterruptedLocalOutputCompositions()
            failedCommittedSaveCandidateCleanupsAtStartup = recoverCommittedLocalOutputCandidates()

            // Recover a bounded number of interrupted physical deletions before the UI reads state.
            // This does not claim new artifacts or apply the unresolved age-based retention policy.
            CleanupArbiter(store, artifacts).recoverInterruptedDeletions(STARTUP_RECOVERY_BATCH_SIZE)
            quarantinedArtifactsAtStartup = reconcileSmallArtifactBatch()
        }
    }

    /** Reclaims only known transient image files left by a terminated producer. */
    private fun cleanupInterruptedProcessingScratch(): Int {
        val cache = appContext.cacheDir
        check(cache.isDirectory) { "Private processing scratch directory is unavailable" }
        val scratchNames = listOf(
            Regex("capture-[0-9]+\\.jpg"),
            Regex("rotate-${UUID_PATTERN}\\.jpg"),
            Regex("page-edit-${UUID_PATTERN}\\.jpg"),
            Regex("crop-${UUID_PATTERN}\\.jpg"),
            Regex("pdf-page-[1-9][0-9]*-[A-Za-z0-9_-]+\\.png"),
        )
        val entries = cache.listFiles()
            ?: throw IllegalStateException("Private processing scratch directory cannot be listed")
        var removed = 0
        entries.forEach { file ->
            if (scratchNames.none { it.matches(file.name) }) return@forEach
            val path = file.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
            Files.delete(path)
            check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                "Interrupted processing scratch file remains after cleanup"
            }
            removed += 1
        }
        return removed
    }

    /** Removes only exact private candidate paths for DB-confirmed pre-output SaveManifests. */
    private fun recoverInterruptedLocalOutputCompositions(): Int {
        val candidateRoot = appContext.noBackupFilesDir.toPath().resolve(saveCandidateDirectoryName)
        val manifests = store.incompleteLocalOutputCompositionManifests(STARTUP_RECOVERY_BATCH_SIZE)
        var abandoned = 0
        manifests.forEach { saveManifestId ->
            val stagedOutputs = store.stagedExportOutputsForInterruptedSave(saveManifestId)
            val publicationIntents = store.outputPublicationIntentsForInterruptedSave(saveManifestId)
            publicationIntents.forEach { intent ->
                artifacts.cleanupInterruptedExportPublication(
                    intent.artifactId,
                    intent.extension,
                    intent.relativePath,
                    intent.sizeBytes,
                    intent.sha256,
                )
            }
            stagedOutputs.forEach { output ->
                val checksum = output.sha256
                    ?: throw IllegalStateException("Interrupted Save output has no checksum")
                when (artifacts.inspect(output.relativePath, output.sizeBytes, checksum)) {
                    app.docscan.core.artifact.ArtifactFileObservation.VERIFIED_PRESENT -> {
                        check(artifacts.deleteVerified(output.relativePath, output.sizeBytes, checksum)
                            != app.docscan.core.artifact.VerifiedDeleteResult.INVALID
                        ) { "Interrupted Save output failed verified cleanup" }
                    }
                    // A previous startup may have unlinked this exact staged object before a crash.
                    app.docscan.core.artifact.ArtifactFileObservation.MISSING -> Unit
                    app.docscan.core.artifact.ArtifactFileObservation.INVALID ->
                        throw IllegalStateException("Interrupted Save output path or bytes are invalid")
                }
            }
            if (Files.exists(candidateRoot, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isDirectory(candidateRoot, LinkOption.NOFOLLOW_LINKS)) {
                    "Save candidate root is not a private directory"
                }
                val escapedId = Regex.escape(saveManifestId.value)
                val stagingPattern = Regex(
                    "^\\.$escapedId\\.[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.building$",
                )
                val candidates = Files.newDirectoryStream(candidateRoot).use { entries ->
                    entries.filter { candidate ->
                        val name = candidate.fileName.toString()
                        name == saveManifestId.value || stagingPattern.matches(name)
                    }.toList()
                }
                candidates.forEach { candidate ->
                    deleteCandidateTreeWithoutFollowingLinks(candidate)
                }
                // Sync even when a previous startup already removed the candidate but failed
                // before its metadata transaction; this closes that idempotent retry boundary.
                AndroidDirectorySync().sync(candidateRoot)
            }
            store.abandonInterruptedLocalOutputComposition(
                saveManifestId,
                stagedOutputs.map { it.artifactId },
                publicationIntents.map { it.artifactId },
            )
            abandoned++
        }
        return abandoned
    }

    private fun deleteCandidateTreeWithoutFollowingLinks(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Retries bounded cleanup after a completed local Save whose earlier cleanup failed. */
    private fun recoverCommittedLocalOutputCandidates(): Int {
        val root = appContext.noBackupFilesDir.toPath().resolve(saveCandidateDirectoryName)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 1
        val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val candidates = Files.newDirectoryStream(root).use { entries ->
            entries.filter { uuidPattern.matches(it.fileName.toString()) }
        }
        val committedCandidates = candidates.mapNotNull { candidate ->
            val saveManifestId = runCatching { SaveManifestId.parse(candidate.fileName.toString()) }.getOrNull()
                ?: return@mapNotNull null
            if (store.hasCommittedNormalLocalSave(saveManifestId)) candidate to saveManifestId else null
        }.take(STARTUP_RECOVERY_BATCH_SIZE.toInt())
        var failures = 0
        committedCandidates.forEach { (_, saveManifestId) ->
            if (cleanupCommittedLocalOutputCandidate(saveManifestId).isFailure) failures++
        }
        return failures
    }

    /** Removes a candidate only after its normal local ExportArtifact transaction is committed. */
    fun cleanupCommittedLocalOutputCandidate(saveManifestId: SaveManifestId): Result<Unit> = runCatching {
        check(store.hasCommittedNormalLocalSave(saveManifestId)) {
            "Save candidate cannot be removed before its normal ExportArtifact is committed"
        }
        val root = appContext.noBackupFilesDir.toPath().resolve(saveCandidateDirectoryName)
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Save candidate root is not a private directory" }
            val candidate = root.resolve(saveManifestId.value)
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                deleteCandidateTreeWithoutFollowingLinks(candidate)
                directorySyncFactory().sync(root)
            }
        }
    }

    fun stageCapturedJpeg(encodedJpeg: InputStream): DurableImageArtifact {
        val durable = artifacts.publishJpeg(encodedJpeg)
        store.recordStagedDurableArtifact(durable)
        return durable
    }

    fun importImage(
        source: InputStream,
        mimeType: String,
        displayName: String,
        importSessionId: app.docscan.core.model.ImportSessionId = app.docscan.core.model.ImportSessionId.new(),
    ): CapturedPage = importImageFromProvider({ source }, mimeType, displayName, importSessionId)

    /** Opens a provider source only after its durable import intent has been recorded. */
    fun importImageFromProvider(
        openSource: () -> InputStream?,
        mimeType: String,
        displayName: String,
        importSessionId: app.docscan.core.model.ImportSessionId = app.docscan.core.model.ImportSessionId.new(),
    ): CapturedPage {
        val normalizedMimeType = mimeType.lowercase()
        val extension = imageExtensionForMime(normalizedMimeType)
        val normalizedDisplayName = displayName.ifBlank { "Imported image" }
        val prepared = store.prepareImageImportPublication(
            importSessionId,
            workspaceId,
            normalizedMimeType,
            normalizedDisplayName,
        )
        val intent = prepared.intent
        try {
            val source = openSource() ?: throw IllegalStateException("Selected image cannot be opened")
            val artifact = source.use { artifacts.publishOrVerifyImage(it, extension, intent.artifactId) }
            if (prepared.cleanupOnFailure) {
                imageImportSourcePublicationBoundaryObserverForTest?.invoke(intent, artifact)
            }
            validateImageArtifact(artifact)
            val command = ImportImageCommand(
                importSessionId = intent.importSessionId,
                workspaceId = intent.workspaceId,
                pageId = intent.pageId,
                imageVersionId = intent.imageVersionId,
                sourceArtifact = artifact,
                mimeType = intent.mimeType,
                displayName = intent.displayName,
                desiredOperationKey = IMPORT_OCR_OPERATION_KEY,
            )
            return store.commitImageImportPublication(intent, command) {
                imageImportTransactionBoundaryObserverForTest?.invoke(it)
            }
        } catch (failure: Exception) {
            if (prepared.cleanupOnFailure) {
                try {
                    store.verifyImageImportPublicationUnregistered(intent)
                    artifacts.cleanupInterruptedImagePublication(intent.artifactId, extension)
                    store.abandonImageImportPublication(intent)
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    fun importPdf(
        source: InputStream,
        displayName: String,
        importSessionId: app.docscan.core.model.ImportSessionId,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
        cancellationSignal: CancellationSignal? = null,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<app.docscan.core.model.PageId> {
        val normalizedDisplayName = displayName.ifBlank { "Imported PDF" }
        val ownsPublicationIntent = store.preparePdfImportPublication(
            importSessionId,
            workspaceId,
            normalizedDisplayName,
        )
        val sourceArtifact: DurableExportFile
        val sessionId: app.docscan.core.model.ImportSessionId
        try {
            sourceArtifact = artifacts.publishOrVerifyExportFile(
                source,
                "pdf",
                app.docscan.core.model.ArtifactId.parse(importSessionId.value),
            )
            if (ownsPublicationIntent) {
                pdfImportSourcePublicationBoundaryObserverForTest?.invoke(
                    app.docscan.core.store.PdfImportPublicationIntent(importSessionId, workspaceId, normalizedDisplayName),
                    sourceArtifact,
                )
            }
            val sourceFile = artifactPath(sourceArtifact.relativePath).toFile()
            val inspection = AndroidPdfImportReader(appContext).inspect(sourceFile)
            sessionId = store.beginPdfImport(
                app.docscan.core.store.BeginPdfImportCommand(
                    importSessionId,
                    workspaceId,
                    sourceArtifact,
                    inspection.pageCount.toLong(),
                    normalizedDisplayName,
                    inspection.title,
                ),
            )
        } catch (failure: Exception) {
            if (ownsPublicationIntent) {
                try {
                    artifacts.cleanupInterruptedPdfPublication(
                        app.docscan.core.model.ArtifactId.parse(importSessionId.value),
                    )
                    store.abandonPdfImportPublication(
                        app.docscan.core.store.PdfImportPublicationIntent(
                            importSessionId,
                            workspaceId,
                            normalizedDisplayName,
                        ),
                    )
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
        val snapshot = store.pdfImportSnapshot(sessionId)
            ?: run {
                check(store.pdfImportSessionState(sessionId) == "COMPLETED") {
                    "PDF ImportSession is not resumable: ${store.pdfImportSessionState(sessionId)}"
                }
                return store.importedPdfPageIds(sessionId)
            }
        return materializePdfImport(snapshot, scheduler, cancellationSignal, onProgress)
    }

    fun resumeIncompletePdfImports(
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
        onProgress: (name: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<app.docscan.core.model.PageId> {
        val materialized = mutableListOf<app.docscan.core.model.PageId>()
        var firstFailure: Exception? = null
        store.incompletePdfImportSessions().forEach { snapshot ->
            try {
                materialized += materializePdfImport(snapshot, scheduler) { completed, total ->
                    onProgress(snapshot.displayName, completed, total)
                }
            } catch (failure: Exception) {
                val prior = firstFailure
                if (prior == null) firstFailure = failure else prior.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
        return materialized
    }

    private fun materializePdfImport(
        snapshot: app.docscan.core.store.PdfImportSourceSnapshot,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
        cancellationSignal: CancellationSignal? = null,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): List<app.docscan.core.model.PageId> {
        val sourceFile = artifactPath(snapshot.relativePath).toFile()
        if (!artifacts.verifyPublished(snapshot.relativePath, snapshot.sizeBytes, snapshot.sha256)) {
            store.markPdfImportSourceUnavailable(snapshot.importSessionId)
            throw IllegalStateException("Private PDF source failed size/SHA-256 revalidation")
        }
        var completedPages = snapshot.importedPageIndices.size
        try {
            AndroidPdfImportReader(appContext).renderPages(
                source = sourceFile,
                expectedPageCount = snapshot.sourcePageCount.toInt(),
                skipPageIndices = snapshot.importedPageIndices,
                scratchDirectory = appContext.cacheDir,
                scheduler = scheduler,
                cancellationSignal = cancellationSignal,
            ) { index, renderedPng, recognizedText ->
                val pageIdentity = "${snapshot.importSessionId.value}:$index"
                val imageArtifactId = app.docscan.core.model.ArtifactId.parse(
                    UUID.nameUUIDFromBytes("$pageIdentity:artifact".toByteArray()).toString(),
                )
                val published = renderedPng.inputStream().buffered().use {
                    artifacts.publishOrVerifyImage(it, "png", imageArtifactId)
                }
                pdfImportPublicationBoundaryObserverForTest?.invoke("BYTES_PUBLISHED", index, published)
                val imageArtifact = store.recordStagedPdfImageArtifact(published)
                pdfImportPublicationBoundaryObserverForTest?.invoke("METADATA_STAGED", index, imageArtifact)
                store.commitPdfImportPage(
                    command = app.docscan.core.store.CommitPdfImportPageCommand(
                        importSessionId = snapshot.importSessionId,
                        workspaceId = snapshot.workspaceId,
                        sourcePageIndex = index.toLong(),
                        pageId = app.docscan.core.model.PageId.parse(
                            UUID.nameUUIDFromBytes("$pageIdentity:page".toByteArray()).toString(),
                        ),
                        imageVersionId = app.docscan.core.model.ImageVersionId.parse(
                            UUID.nameUUIDFromBytes("$pageIdentity:image-version".toByteArray()).toString(),
                        ),
                        imageArtifact = imageArtifact,
                        desiredOperationKey = IMPORT_OCR_OPERATION_KEY,
                        sourceDisplayName = snapshot.displayName,
                        recognizedText = recognizedText,
                        recognitionId = recognizedText?.let { app.docscan.core.model.RecognitionId.new() },
                    ),
                    beforeTransactionCommitForTest = pdfImportPageTransactionBoundaryObserverForTest,
                )
                completedPages += 1
                pdfImportPageCommittedObserverForTest?.invoke(
                    snapshot.importSessionId.value,
                    completedPages,
                    snapshot.sourcePageCount.toInt(),
                )
                onProgress(completedPages, snapshot.sourcePageCount.toInt())
            }
        } catch (failure: Exception) {
            if (store.pdfImportSessionState(snapshot.importSessionId) == "OPEN") {
                runCatching { store.recordPdfImportFailure(snapshot.importSessionId, failure.message ?: failure.javaClass.simpleName) }
            }
            throw failure
        }
        return store.importedPdfPageIds(snapshot.importSessionId)
    }

    fun commitCapture(command: CaptureCommand): CapturedPage {
        require(command.workspaceId == workspaceId) { "Capture belongs to another workspace" }
        return store.commitCapture(command)
    }

    fun workspaceProjection(): WorkspaceProjection = store.workspaceProjection(workspaceId)

    fun visiblePages(): List<StoredPage> = workspaceProjection().pages

    fun reorderPages(projection: WorkspaceProjection, orderedPages: List<StoredPage>) =
        store.reorderPages(workspaceId, projection.orderRevision, orderedPages.map { it.pageId })

    fun setSelected(pageId: app.docscan.core.model.PageId, selected: Boolean) =
        store.setSelected(workspaceId, pageId, selected)

    fun setAllVisiblePagesSelected(selected: Boolean) =
        store.setAllVisiblePagesSelected(workspaceId, selected)

    fun setActivePage(pageId: app.docscan.core.model.PageId) =
        store.setActivePage(workspaceId, pageId)

    fun replacePageImage(page: StoredPage, artifact: DurableImageArtifact) = replacePageImage(
        page,
        artifact,
        "camera-retake",
    )

    private fun replacePageImage(
        page: StoredPage,
        artifact: DurableImageArtifact,
        transformProvenance: String,
    ) = store.replacePageImage(
        ReplacePageImageCommand(
            workspaceId = workspaceId,
            pageId = page.pageId,
            expectedImageVersionId = page.imageVersionId,
            expectedContentRevision = page.contentRevision,
            newImageVersionId = app.docscan.core.model.ImageVersionId.new(),
            artifact = artifact,
            coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
            transformProvenance = transformProvenance,
            processingDesiredState = IMPORT_OCR_OPERATION_KEY,
        ),
    )

    fun rotatePageImage(
        page: StoredPage,
        clockwiseTurns: Int,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): WorkspaceProjection {
        require(clockwiseTurns in 1..3)
        val sourceFile = artifactPath(page.artifactRelativePath).toFile()
        val temporary = File(appContext.cacheDir, "rotate-${UUID.randomUUID()}.jpg")
        try {
            val estimate = ProductImageDecoder.estimateWorkingSetBytes(sourceFile, MAX_IMPORT_BITMAP_PIXELS)
            val artifact = scheduler.withAdmission(estimate) {
                val source = ProductImageDecoder.decode(
                    sourceFile,
                    MAX_IMPORT_BITMAP_PIXELS,
                    preferredConfig = Bitmap.Config.RGB_565,
                ) ?: throw IllegalArgumentException("Image cannot be decoded for editing")
                try {
                    val rotation = if (clockwiseTurns == 3) -90f else clockwiseTurns * 90f
                    val edited = Bitmap.createBitmap(
                        source,
                        0,
                        0,
                        source.width,
                        source.height,
                        android.graphics.Matrix().apply { postRotate(rotation) },
                        true,
                    )
                    try {
                        FileOutputStream(temporary).use { output ->
                            check(edited.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                                "Rotated image could not be encoded"
                            }
                            output.fd.sync()
                        }
                    } finally {
                        if (edited !== source) edited.recycle()
                    }
                } finally {
                    source.recycle()
                }
                FileInputStream(temporary).use(::stageCapturedJpeg)
            }
            validateImageArtifact(artifact)
            replacePageImage(page, artifact, "manual-rotate-${clockwiseTurns * 90}")
            return workspaceProjection()
        } finally {
            temporary.delete()
        }
    }

    fun applyPageImageEdit(
        page: StoredPage,
        editedBitmap: Bitmap,
    ): WorkspaceProjection {
        val temporary = File(appContext.cacheDir, "page-edit-${UUID.randomUUID()}.jpg")
        try {
            val artifact = FileOutputStream(temporary).use { output ->
                check(editedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "Edited image could not be encoded"
                }
                output.fd.sync()
                FileInputStream(temporary).use(::stageCapturedJpeg)
            }
            validateImageArtifact(artifact)
            replacePageImage(page, artifact, "manual-image-edit-v1")
            return workspaceProjection()
        } finally {
            temporary.delete()
        }
    }

    fun createDocumentCropPreview(
        page: StoredPage,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): Bitmap? {
        val sourceFile = artifactPath(page.artifactRelativePath).toFile()
        return scheduler.withAdmission(DOCUMENT_CROP_WORKING_SET_BYTES) {
            val source = ProductImageDecoder.decode(
                sourceFile,
                DocumentCropProcessor.MAX_SOURCE_PIXELS,
                preferredConfig = Bitmap.Config.ARGB_8888,
            ) ?: throw IllegalArgumentException("Image cannot be decoded for crop preview")
            try {
                DocumentCropProcessor.rectify(source)
            } finally {
                source.recycle()
            }
        }
    }

    fun applyDocumentCrop(
        page: StoredPage,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): WorkspaceProjection {
        val sourceFile = artifactPath(page.artifactRelativePath).toFile()
        val temporary = File(appContext.cacheDir, "crop-${UUID.randomUUID()}.jpg")
        try {
            val artifact = scheduler.withAdmission(DOCUMENT_CROP_WORKING_SET_BYTES) {
                val source = ProductImageDecoder.decode(
                    sourceFile,
                    DocumentCropProcessor.MAX_SOURCE_PIXELS,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                ) ?: throw IllegalArgumentException("Image cannot be decoded for cropping")
                try {
                    val candidate = DocumentCropProcessor.rectify(source)
                        ?: throw IllegalArgumentException("No reliable page boundary was found")
                    try {
                        FileOutputStream(temporary).use { output ->
                            check(candidate.compress(Bitmap.CompressFormat.JPEG, DOCUMENT_CROP_JPEG_QUALITY, output)) {
                                "Cropped image could not be encoded"
                            }
                            output.fd.sync()
                        }
                    } finally {
                        candidate.recycle()
                    }
                } finally {
                    source.recycle()
                }
                FileInputStream(temporary).use(::stageCapturedJpeg)
            }
            validateImageArtifact(artifact)
            replacePageImage(page, artifact, "auto-perspective-crop-v1")
            return workspaceProjection()
        } finally {
            temporary.delete()
        }
    }

    fun undoPageImage(page: StoredPage) = store.undoPageImage(
        workspaceId = workspaceId,
        pageId = page.pageId,
        expectedImageVersionId = page.imageVersionId,
        expectedContentRevision = page.contentRevision,
        processingDesiredState = IMPORT_OCR_OPERATION_KEY,
    )

    fun commitRecognition(candidate: RecognitionCandidate) = store.commitRecognition(candidate)

    fun recordDestinationGrant(treeUri: String) =
        store.recordDestinationGrant(treeUri, System.currentTimeMillis())

    fun recordProviderQualification(command: ProviderQualificationCommand) =
        store.recordProviderQualification(command)

    fun recordRuntimeProviderAssessment(command: ProviderQualificationCommand): ProviderQualificationSnapshot =
        store.recordProviderAssessment(command)

    fun providerQualificationFor(
        adapterId: String,
        providerVersion: String,
        osVersion: String,
        destinationAccessClass: String,
    ): ProviderQualificationSnapshot? = store.providerQualificationFor(
        adapterId, providerVersion, osVersion, destinationAccessClass,
    )

    fun providerQualificationsFor(
        adapterId: String,
        providerVersion: String,
        osVersion: String,
        destinationAccessClass: String,
    ): List<ProviderQualificationSnapshot> = store.providerQualificationsFor(
        adapterId, providerVersion, osVersion, destinationAccessClass,
    )

    fun destinationGrant(): DestinationGrantSnapshot? = store.destinationGrant()

    fun committedExportOutputs(exportArtifactId: ExportArtifactId): List<CommittedExportOutput> =
        store.committedExportOutputs(exportArtifactId)

    fun exportOutputFile(output: CommittedExportOutput): File = artifactRoot().resolve(output.relativePath).toFile()

    fun verifyExportOutput(output: CommittedExportOutput): Boolean =
        artifacts.verifyPublished(output.relativePath, output.sizeBytes, output.sha256)

    fun beginExportAttempt(command: BeginExportAttemptCommand): ExportAttemptSnapshot = store.beginExportAttempt(command)

    fun finishWritingExportAttempt(
        exportAttemptId: ExportAttemptId,
        outcome: ExportAttemptOutcome,
        externalIdentity: String?,
        knownError: String?,
        provesNoSideEffect: Boolean,
        observation: ProviderOperationObservationCommand,
    ) = store.finishWritingExportAttempt(
        exportAttemptId, outcome, externalIdentity, knownError, provesNoSideEffect, observation,
    )

    fun markWritingExportAttemptUnknown(
        exportAttemptId: ExportAttemptId,
        reason: String,
        observation: ProviderOperationObservationCommand,
    ) = store.markWritingExportAttemptUnknown(exportAttemptId, reason, observation)

    fun revalidateUnknownExportAttempt(
        exportAttemptId: ExportAttemptId,
        outcome: ExportAttemptOutcome,
        externalIdentity: String?,
        knownError: String?,
        provesNoSideEffect: Boolean,
        observation: ProviderOperationObservationCommand,
    ) = store.revalidateUnknownExportAttempt(
        exportAttemptId, outcome, externalIdentity, knownError, provesNoSideEffect, observation,
    )

    fun unknownExportAttempts() = store.unknownExportAttempts()

    fun exportAttempts(exportArtifactId: ExportArtifactId) = store.exportAttempts(exportArtifactId)

    fun recentDocuments() = store.recentDocuments()

    fun recordVerifiedPublicExport(
        exportArtifactId: ExportArtifactId, documentUri: String, displayName: String,
        pageCount: Long, ocrStatus: String,
    ) = store.recordVerifiedPublicExport(exportArtifactId, documentUri, displayName, pageCount, ocrStatus)

    fun useDefaultSaveFolder() {
        store.destinationGrant()?.let { store.clearDestinationGrant(it.treeUri) }
    }

    fun renderRecentSavedPdfPage(
        recent: app.docscan.core.store.RecentDocumentSnapshot,
        pageIndex: Int,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): RenderedSavedPdfPage {
        require(recent.pageCount in 1L..MAX_PDF_IMPORT_PAGES.toLong())
        val pdf = committedExportOutputs(recent.exportArtifactId)
            .firstOrNull { it.mimeType == "application/pdf" }
            ?: throw IllegalStateException("Recent saved PDF has no committed local source")
        check(verifyExportOutput(pdf)) { "Recent saved PDF failed its local checksum check" }
        return SavedPdfPageRenderer.renderPage(
            exportOutputFile(pdf),
            recent.pageCount.toInt(),
            pageIndex,
            scheduler,
        )
    }

    fun recentSavedPdfFile(recent: app.docscan.core.store.RecentDocumentSnapshot): File {
        require(recent.pageCount in 1L..MAX_PDF_IMPORT_PAGES.toLong())
        val pdf = committedExportOutputs(recent.exportArtifactId)
            .firstOrNull { it.mimeType == "application/pdf" }
            ?: throw IllegalStateException("Recent saved PDF has no committed local source")
        check(verifyExportOutput(pdf)) { "Recent saved PDF failed its local checksum check" }
        return exportOutputFile(pdf).also { file ->
            check(file.isFile && file.length() == pdf.sizeBytes) {
                "Recent saved PDF differs from its committed size"
            }
        }
    }

    fun commitSavedPdfEditOutput(
        recent: app.docscan.core.store.RecentDocumentSnapshot,
        orderedSourcePageIndexes: List<Int>,
        output: SavedPdfEditOutput,
        externalPackage: ExternalExportPackagePlan,
    ): ExportArtifactId {
        val source = recentSavedPdfFile(recent)
        val sourcePdf = committedExportOutputs(recent.exportArtifactId)
            .singleOrNull { it.mimeType == "application/pdf" }
            ?: throw IllegalStateException("Recent saved PDF must have exactly one committed PDF")
        check(sourcePdf.sha256 == output.sourceSha256 && source.length() == sourcePdf.sizeBytes) {
            "Saved PDF source changed after the edit preview"
        }
        require(output.pageCount == orderedSourcePageIndexes.size)
        require(externalPackage.outputDisplayNames.size == 1)

        val saveManifestId = SaveManifestId.new()
        store.beginSavedPdfEditManifest(
            app.docscan.core.store.SavedPdfEditManifestCommand(
                saveManifestId = saveManifestId,
                workspaceId = workspaceId,
                sourceExportArtifactId = recent.exportArtifactId,
                sourcePdfArtifactId = sourcePdf.artifactId,
                sourcePdfSha256 = sourcePdf.sha256,
                sourcePageCount = recent.pageCount,
                orderedSourcePageIndexes = orderedSourcePageIndexes,
            ),
        )
        try {
            val artifact = artifacts.publishExportFile(output.file.inputStream(), "pdf")
            check(artifact.sha256 == output.sha256 && artifact.sizeBytes == output.sizeBytes) {
                "Edited PDF differs from the validated composition"
            }
            check(artifacts.verifyPublished(artifact.relativePath, artifact.sizeBytes, artifact.sha256)) {
                "Edited PDF failed private-file read-back checksum verification"
            }
            store.recordStagedExportFile(saveManifestId, artifact)
            val durableExternalPackage = ExternalExportPackageCommand(
                destinationTreeUri = externalPackage.destinationTreeUri,
                commonBasename = externalPackage.commonBasename,
                providerQualificationId = externalPackage.providerQualificationId,
                adapterId = externalPackage.adapterId,
                providerVersion = externalPackage.providerVersion,
                osVersion = externalPackage.osVersion,
                destinationAccessClass = externalPackage.destinationAccessClass,
                outputs = listOf(
                    app.docscan.core.store.ExternalExportPackageOutputCommand(
                        artifact.artifactId,
                        externalPackage.outputDisplayNames.single(),
                        0L,
                    ),
                ),
                ocrStatus = externalPackage.ocrStatus,
            )
            val exportId = ExportArtifactId.new()
            store.commitLocalExportArtifact(
                app.docscan.core.store.CommitLocalExportCommand(
                    exportArtifactId = exportId,
                    saveManifestId = saveManifestId,
                    files = listOf(app.docscan.core.store.LocalExportFileCommand(artifact, "PDF", null)),
                    externalPackage = durableExternalPackage,
                    outputKind = "EDITED_SAVED_PDF",
                    expectedPageCount = output.pageCount.toLong(),
                ),
            )
            return exportId
        } catch (failure: Exception) {
            runCatching { store.abandonIncompleteSavedPdfEditManifests(STARTUP_RECOVERY_BATCH_SIZE) }
            throw failure
        }
    }

    fun importedPdfPageIds(importSessionId: app.docscan.core.model.ImportSessionId) =
        store.importedPdfPageIds(importSessionId)

    fun externalExportPackage(exportArtifactId: ExportArtifactId) = store.externalExportPackage(exportArtifactId)

    fun incompleteExternalExportPackages() = store.incompleteExternalExportPackages()

    fun exportAttempt(exportAttemptId: ExportAttemptId) = store.exportAttempt(exportAttemptId)

    fun markDestinationGrantVerified(treeUri: String, wallMillis: Long = System.currentTimeMillis()) =
        store.markDestinationGrantVerified(treeUri, wallMillis)

    fun freezeSelectedPagesForSave(): app.docscan.core.store.SaveManifestSnapshot =
        store.freezeSelectedPagesForSave(workspaceId)

    fun cancelUnstartedSaveManifest(saveManifestId: SaveManifestId) =
        store.cancelUnstartedSaveManifest(workspaceId, saveManifestId)

    fun composeFrozenSaveOutputSet(
        snapshot: app.docscan.core.store.SaveManifestSnapshot,
        pdfSettings: PdfOutputSettings,
        companionJpegQuality: Int,
        includeOcr: Boolean = true,
    ): FrozenSaveOutputSet {
        require(snapshot.workspaceId == workspaceId) { "SaveManifest belongs to another Workspace" }
        store.beginLocalOutputComposition(snapshot)
        val candidateRoot = appContext.noBackupFilesDir.toPath().resolve(saveCandidateDirectoryName).toFile()
        return try {
            FrozenSaveOutputComposer(
                sourceRepository = artifacts,
                sourcePath = { relativePath -> artifactRoot().resolve(relativePath).toFile() },
                pdfWriter = SearchablePdfWriter(appContext),
                resourceLoaderContext = appContext,
                directorySync = directorySyncFactory(),
            ).compose(snapshot, candidateRoot, pdfSettings, companionJpegQuality, includeOcr)
        } catch (failure: Throwable) {
            try {
                store.abortLocalOutputComposition(snapshot)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    /**
     * Publishes the validated frozen output set as immutable private files, then commits all
     * ArtifactRecords/ExportArtifact/references atomically in SQLite.
     */
    fun commitLocalExportOutputSet(
        snapshot: app.docscan.core.store.SaveManifestSnapshot,
        outputSet: FrozenSaveOutputSet,
        externalPackage: ExternalExportPackagePlan? = null,
    ): ExportArtifactId {
        require(snapshot.saveManifestId.value == outputSet.saveManifestId) {
            "Output set belongs to another SaveManifest"
        }
        require(outputSet.pages.map { it.imageVersionId } == snapshot.pages.map { it.imageVersionId.value }) {
            "Output JPG order differs from the frozen SaveManifest"
        }
        val exportId = ExportArtifactId.new()
        val files = mutableListOf<LocalExportFileCommand>()
        val pdf = publishFrozenSaveOutput(
            snapshot.saveManifestId,
            0,
            outputSet.pdfFile,
            "pdf",
            outputSet.pdfSha256,
        )
        files += LocalExportFileCommand(pdf, "PDF", null)

        outputSet.pages.forEachIndexed { index, page ->
            val output = publishFrozenSaveOutput(
                snapshot.saveManifestId,
                index + 1,
                page.jpegFile,
                "jpg",
                page.jpegSha256,
            )
            files += LocalExportFileCommand(output, "JPG_PAGE", snapshot.pages[index].imageVersionId)
        }
        val durableExternalPackage = externalPackage?.let { plan ->
            require(plan.outputDisplayNames.size == files.size || plan.outputDisplayNames.size == 1) {
                "External package names do not match the committed output count"
            }
            ExternalExportPackageCommand(
                plan.destinationTreeUri,
                plan.commonBasename,
                plan.providerQualificationId,
                plan.adapterId,
                plan.providerVersion,
                plan.osVersion,
                plan.destinationAccessClass,
                files.take(plan.outputDisplayNames.size).mapIndexed { index, file ->
                    ExternalExportPackageOutputCommand(file.file.artifactId, plan.outputDisplayNames[index], index.toLong())
                },
                plan.ocrStatus,
            )
        }
        store.commitLocalExportArtifact(
            CommitLocalExportCommand(
                exportArtifactId = exportId,
                saveManifestId = snapshot.saveManifestId,
                files = files,
                externalPackage = durableExternalPackage,
            ),
        )
        return exportId
    }

    private fun publishFrozenSaveOutput(
        saveManifestId: SaveManifestId,
        outputOrdinal: Int,
        candidate: File,
        extension: String,
        expectedSha256: String,
    ): DurableExportFile {
        val artifactId = app.docscan.core.model.ArtifactId.forSaveOutput(saveManifestId, outputOrdinal)
        val intent = artifacts.describeExportFile(artifactId, extension, candidate.length(), expectedSha256)
        store.beginSaveOutputPublication(saveManifestId, outputOrdinal, intent)
        val published = artifacts.publishExportFile(candidate.inputStream(), extension, artifactId)
        require(published == intent) { "Published output differs from its durable publication intent" }
        check(artifacts.verifyPublished(published.relativePath, published.sizeBytes, published.sha256)) {
            "Published export output failed private-file read-back checksum verification"
        }
        store.completeSaveOutputPublication(saveManifestId, published)
        return published
    }

    fun deletePage(page: StoredPage): DeleteReceipt =
        store.deletePage(workspaceId, page.pageId, page.contentRevision)

    fun deletePages(pages: List<StoredPage>) = store.deletePages(
        workspaceId,
        pages.map { app.docscan.core.store.DeletePageExpectation(it.pageId, it.contentRevision) },
    )

    fun undoDelete(pageId: app.docscan.core.model.PageId) = store.undoDelete(workspaceId, pageId)

    fun undoDeleteBatch(batchId: app.docscan.core.model.DeleteBatchId) = store.undoDeleteBatch(workspaceId, batchId)

    fun close() = driver.close()

    fun artifactPath(artifact: DurableImageArtifact): Path =
        artifactRoot().resolve(artifact.relativePath)

    fun artifactPath(relativePath: String): Path =
        artifactRoot().resolve(relativePath)

    private fun artifactRoot(): Path = appContext.noBackupFilesDir.toPath().resolve(artifactDirectoryName)

    private fun validateImageArtifact(artifact: DurableImageArtifact) {
        val file = artifactPath(artifact).toFile()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType.isNullOrBlank()) {
            throw ImageImportInvalidSourceException("Imported file is not a supported image")
        }
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_IMPORT_BITMAP_PIXELS) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: throw ImageImportInvalidSourceException("Imported image cannot be decoded")
        bitmap.recycle()
    }

    private companion object {
        const val MAX_IMPORT_BITMAP_PIXELS = 12L * 1024L * 1024L
        const val DOCUMENT_CROP_WORKING_SET_BYTES = 80L * 1024L * 1024L
        const val DOCUMENT_CROP_JPEG_QUALITY = 92
        const val MAX_PDF_IMPORT_PAGES = 500
        const val STARTUP_RECOVERY_BATCH_SIZE = 4L
        const val IMPORT_OCR_OPERATION_KEY = "OCR_RUS_ENG_TESSERACT_V1"
    }

    private class ImageImportInvalidSourceException(message: String) : IllegalArgumentException(message)

    private fun reconcileSmallArtifactBatch(): Int {
        val batch = store.recoveryArtifactBatch(STARTUP_RECOVERY_BATCH_SIZE)
        if (batch.isEmpty()) {
            store.resetRecoveryCursor()
            return 0
        }
        var quarantined = 0
        batch.forEach { artifact ->
            val valid = artifact.sha256?.let { checksum ->
                runCatching { artifacts.verifyPublished(artifact.relativePath, artifact.sizeBytes, checksum) }
                    .getOrDefault(false)
            } ?: false
            if (valid) {
                store.acknowledgeRecoveredArtifact(artifact)
            } else {
                store.quarantineRecoveredArtifact(artifact)
                quarantined += 1
            }
        }
        return quarantined
    }

    private fun recoverPendingPdfImportPublications() {
        store.pendingPdfImportPublicationIntents(STARTUP_RECOVERY_BATCH_SIZE).forEach { intent ->
            val artifactId = app.docscan.core.model.ArtifactId.parse(intent.importSessionId.value)
            val source = artifacts.publishedPdfForImport(artifactId)
            if (source == null) {
                artifacts.cleanupInterruptedPdfPublication(artifactId)
                store.abandonPdfImportPublication(intent)
                return@forEach
            }
            val inspection = try {
                AndroidPdfImportReader(appContext).inspect(artifactPath(source.relativePath).toFile())
            } catch (_: PdfImportInvalidSourceException) {
                artifacts.cleanupInterruptedPdfPublication(artifactId)
                store.abandonPdfImportPublication(intent)
                return@forEach
            }
            store.beginPdfImport(
                app.docscan.core.store.BeginPdfImportCommand(
                    intent.importSessionId,
                    intent.workspaceId,
                    source,
                    inspection.pageCount.toLong(),
                    intent.displayName,
                    inspection.title,
                ),
            )
        }
    }

    private fun recoverPendingImageImportPublications() {
        store.pendingImageImportPublicationIntents(STARTUP_RECOVERY_BATCH_SIZE).forEach { intent ->
            val extension = imageExtensionForMime(intent.mimeType)
            val legacySession = store.importSessionRecord(intent.importSessionId)
            val artifact = artifacts.publishedImageForImport(intent.artifactId, extension)
            if (artifact == null) {
                check(legacySession == null) { "Legacy image ImportSession private source is missing; preserving its records" }
                artifacts.cleanupInterruptedImagePublication(intent.artifactId, extension)
                store.abandonImageImportPublication(intent)
                return@forEach
            }
            try {
                validateImageArtifact(artifact)
            } catch (_: ImageImportInvalidSourceException) {
                check(legacySession == null) { "Legacy image ImportSession source is invalid; preserving its records" }
                artifacts.cleanupInterruptedImagePublication(intent.artifactId, extension)
                store.abandonImageImportPublication(intent)
                return@forEach
            }
            store.commitImageImportPublication(
                intent,
                ImportImageCommand(
                    importSessionId = intent.importSessionId,
                    workspaceId = intent.workspaceId,
                    pageId = intent.pageId,
                    imageVersionId = intent.imageVersionId,
                    sourceArtifact = artifact,
                    mimeType = intent.mimeType,
                    displayName = intent.displayName,
                    desiredOperationKey = IMPORT_OCR_OPERATION_KEY,
                ),
            )
        }
    }

    private fun recoverLegacyOpenImageImportSessions() {
        val alreadyPending = store.pendingImageImportPublicationIntents(STARTUP_RECOVERY_BATCH_SIZE)
            .map { it.importSessionId }.toSet()
        store.openSinglePageImportSessions(STARTUP_RECOVERY_BATCH_SIZE).forEach { session ->
            val importSessionId = app.docscan.core.model.ImportSessionId.parse(session.import_session_id)
            if (importSessionId in alreadyPending) return@forEach
            val sourceArtifactId = session.source_snapshot_artifact_id
                ?.let(app.docscan.core.model.ArtifactId::parse) ?: return@forEach
            val storedArtifact = store.artifactRecord(sourceArtifactId) ?: return@forEach
            if (storedArtifact.artifact_kind != "DOCUMENT_PAGE") return@forEach
            val parameters = session.frozen_operation_parameters
            val json = try {
                org.json.JSONObject(parameters)
            } catch (failure: Exception) {
                throw IllegalStateException("Legacy image ImportSession has malformed frozen parameters", failure)
            }
            val mimeType = json.optString("mimeType", "").lowercase()
            val displayName = json.optString("displayName", "")
            check(mimeType.startsWith("image/") && displayName.isNotBlank()
                && displayName == session.source_display_name
            ) { "Legacy image ImportSession context is inconsistent" }
            val extension = imageExtensionForMime(mimeType)
            val artifactId = sourceArtifactId
            check(storedArtifact.state == "COMMITTED") {
                "Legacy image ImportSession source is not a committed image artifact"
            }
            val source = artifacts.publishedImageForImport(artifactId, extension)
                ?: error("Legacy image ImportSession private source file is missing")
            check(storedArtifact.relative_storage_path == source.relativePath
                && storedArtifact.size_bytes == source.sizeBytes
                && storedArtifact.checksum == source.sha256
                && storedArtifact.artifact_format_version == source.formatVersion
            ) { "Legacy image ImportSession source checksum or path is inconsistent" }
            validateImageArtifact(source)
            val pageId = deterministicLegacyImagePageId(session.import_session_id)
            val imageVersionId = deterministicLegacyImageVersionId(session.import_session_id)
            val intent = store.prepareLegacyImageImportPublication(
                importSessionId,
                app.docscan.core.model.WorkspaceId.parse(session.workspace_id),
                displayName,
                mimeType,
                artifactId,
                pageId,
                imageVersionId,
            )
            store.commitImageImportPublication(
                intent,
                ImportImageCommand(
                    importSessionId = importSessionId,
                    workspaceId = intent.workspaceId,
                    pageId = pageId,
                    imageVersionId = imageVersionId,
                    sourceArtifact = source,
                    mimeType = mimeType,
                    displayName = displayName,
                    desiredOperationKey = IMPORT_OCR_OPERATION_KEY,
                ),
            )
        }
    }

    private fun deterministicLegacyImagePageId(sessionId: String): app.docscan.core.model.PageId =
        app.docscan.core.model.PageId.parse(UUID.nameUUIDFromBytes("$sessionId:0:page".toByteArray()).toString())

    private fun deterministicLegacyImageVersionId(sessionId: String): app.docscan.core.model.ImageVersionId =
        app.docscan.core.model.ImageVersionId.parse(UUID.nameUUIDFromBytes("$sessionId:0:image-version".toByteArray()).toString())

    private fun imageExtensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> throw IllegalArgumentException("Unsupported image type: $mimeType")
    }
}
