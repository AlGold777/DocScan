package app.docscan.android

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.cleanup.CleanupArbiter
import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.ArtifactId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId
import app.docscan.core.store.BeginExportAttemptCommand
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.CommitLocalExportCommand
import app.docscan.core.store.DurableImageArtifact
import app.docscan.core.store.ExternalExportPackageCommand
import app.docscan.core.store.ExternalExportPackageOutputCommand
import app.docscan.core.store.ImportImageCommand
import app.docscan.core.store.LocalExportFileCommand
import app.docscan.core.store.ProviderQualificationCommand
import app.docscan.core.store.ReplacePageImageCommand
import app.docscan.core.store.StateStore
import app.docscan.core.store.DurableExportFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupProducerReferenceMatrixInstrumentedTest {
    @Test
    fun realProducerReferencesProtectAllSixClassesFromAndroidCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "cleanup-producers-$suffix.db"
        val artifactRoot = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "cleanup-producers-$suffix-")
        val driver = AndroidSqliteDriver(
            schema = DocScanDatabase.Schema,
            context = context,
            name = databaseName,
            callback = object : AndroidSqliteDriver.Callback(DocScanDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                    check(db.enableWriteAheadLogging()) { "SQLite WAL mode is required" }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                    db.query("PRAGMA synchronous").use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 2) {
                            "SQLite synchronous=FULL is required for cleanup producer fixture"
                        }
                    }
                }
            },
        )
        val database = DocScanDatabase(driver)
        val store = StateStore(database, AndroidClock())
        val repository = FileArtifactRepository(
            privateRoot = artifactRoot,
            directorySync = AndroidDirectorySync(),
            producerEpoch = "cleanup-producers-$suffix",
            atomicPublisher = AndroidAtomicArtifactPublisher(),
        )

        try {
            val workspaceId = WorkspaceId.new()
            store.createInitialWorkspace(workspaceId, WorkspaceSessionId.new())

            val original = publishImage(repository, store, byteArrayOf(1, 3, 5, 7))
            val page = store.commitCapture(
                CaptureCommand(
                    workspaceId = workspaceId,
                    pageId = PageId.new(),
                    imageVersionId = ImageVersionId.new(),
                    artifact = original,
                    selected = true,
                    makeActive = true,
                    coordinateSpace = "source-pixel-v1",
                    processingDesiredState = "OCR_LATIN_V1",
                    sourceProvenance = "{\"kind\":\"cleanup-producer-fixture\"}",
                ),
            )

            val replacement = publishImage(repository, store, byteArrayOf(2, 4, 6, 8))
            val replacementVersion = ImageVersionId.new()
            val nextContentRevision = store.replacePageImage(
                ReplacePageImageCommand(
                    workspaceId = workspaceId,
                    pageId = page.pageId,
                    expectedImageVersionId = page.imageVersionId,
                    expectedContentRevision = 1L,
                    newImageVersionId = replacementVersion,
                    artifact = replacement,
                    coordinateSpace = "edited-pixel-v1",
                    transformProvenance = "producer-reference-matrix",
                    processingDesiredState = "OCR_LATIN_V1",
                ),
            )
            assertEquals(2L, nextContentRevision)

            val importSnapshot = publishImage(repository, store, byteArrayOf(9, 11, 13, 15))
            val importSessionId = ImportSessionId.new()
            store.beginImageImport(
                ImportImageCommand(
                    importSessionId = importSessionId,
                    workspaceId = workspaceId,
                    pageId = PageId.new(),
                    imageVersionId = ImageVersionId.new(),
                    sourceArtifact = importSnapshot,
                    mimeType = "image/jpeg",
                    displayName = "unfinished-import.jpg",
                    desiredOperationKey = "OCR_LATIN_V1",
                ),
            )

            val saveManifest = store.freezeSelectedPagesForSave(workspaceId)
            store.beginLocalOutputComposition(saveManifest)
            val pdf = repository.publishExportFile(ByteArrayInputStream(byteArrayOf(21, 22, 23, 24)), "pdf")
            val jpeg = repository.publishExportFile(ByteArrayInputStream(byteArrayOf(31, 32, 33, 34)), "jpg")
            store.recordStagedExportFile(saveManifest.saveManifestId, pdf)
            store.recordStagedExportFile(saveManifest.saveManifestId, jpeg)

            val provider = ProviderQualificationCommand(
                qualificationId = "cleanup-qualification-$suffix",
                adapterId = "cleanup-test-saf-v1",
                providerVersion = "provider-fixture-v1",
                osVersion = "api36.1-avd",
                destinationAccessClass = "PERSISTED_TREE_READ_WRITE",
                validationSuiteVersion = "cleanup-producer-matrix-v1",
                qualifiedCapabilitiesPayload = "{\"create\":true,\"write\":true,\"observe\":true}",
            )
            store.recordProviderQualification(provider)
            val destination = "content://cleanup-test.provider/tree/$suffix"
            store.recordDestinationGrant(destination, 100L)
            val exportId = ExportArtifactId.new()
            store.commitLocalExportArtifact(
                CommitLocalExportCommand(
                    exportArtifactId = exportId,
                    saveManifestId = saveManifest.saveManifestId,
                    files = listOf(
                        LocalExportFileCommand(pdf, "PDF", null),
                        LocalExportFileCommand(jpeg, "JPG_PAGE", replacementVersion),
                    ),
                    externalPackage = ExternalExportPackageCommand(
                        destinationTreeUri = destination,
                        commonBasename = "Scan",
                        providerQualificationId = provider.qualificationId,
                        adapterId = provider.adapterId,
                        providerVersion = provider.providerVersion,
                        osVersion = provider.osVersion,
                        destinationAccessClass = provider.destinationAccessClass,
                        outputs = listOf(
                            ExternalExportPackageOutputCommand(pdf.artifactId, "Scan.pdf", 0L),
                            ExternalExportPackageOutputCommand(jpeg.artifactId, "Scan-001.jpg", 1L),
                        ),
                    ),
                ),
            )

            val saveProtection = store.freezeSelectedPagesForSave(workspaceId)
            val attemptId = ExportAttemptId.new()
            store.beginExportAttempt(
                BeginExportAttemptCommand(
                    exportAttemptId = attemptId,
                    exportArtifactId = exportId,
                    outputArtifactId = pdf.artifactId,
                    destinationTreeUri = destination,
                    plannedDisplayName = "Scan.pdf",
                    mimeType = "application/pdf",
                    providerQualificationId = provider.qualificationId,
                    adapterId = provider.adapterId,
                    providerVersion = provider.providerVersion,
                    osVersion = provider.osVersion,
                    destinationAccessClass = provider.destinationAccessClass,
                ),
            )

            val orphan = publishImage(repository, store, byteArrayOf(41, 42, 43, 44))
            assertEquals(1L, database.schemaQueries.commitArtifact(orphan.artifactId.value).value)

            data class ProtectedFile(
                val artifactId: ArtifactId,
                val relativePath: String,
                val sizeBytes: Long,
                val sha256: String,
                val expectedBytes: ByteArray,
                val references: List<Triple<String, String, String>>,
            )
            fun imageFile(artifact: DurableImageArtifact, bytes: ByteArray, vararg refs: Triple<String, String, String>) =
                ProtectedFile(artifact.artifactId, artifact.relativePath, artifact.sizeBytes, artifact.sha256, bytes, refs.toList())
            fun exportFile(file: DurableExportFile, bytes: ByteArray, vararg refs: Triple<String, String, String>) =
                ProtectedFile(file.artifactId, file.relativePath, file.sizeBytes, file.sha256, bytes, refs.toList())

            val protectedArtifacts = listOf(
                imageFile(original, byteArrayOf(1, 3, 5, 7), Triple("IMAGE_VERSION", "UNDO", page.imageVersionId.value)),
                imageFile(replacement, byteArrayOf(2, 4, 6, 8),
                    Triple("IMAGE_VERSION", "AUTHORITATIVE", replacementVersion.value),
                    Triple("SAVE_MANIFEST", "SAVE", saveProtection.saveManifestId.value)),
                imageFile(importSnapshot, byteArrayOf(9, 11, 13, 15),
                    Triple("IMPORT_SESSION", "RECOVERY", importSessionId.value)),
                exportFile(pdf, byteArrayOf(21, 22, 23, 24),
                    Triple("EXPORT_ARTIFACT", "EXPORT", exportId.value),
                    Triple("EXPORT_ATTEMPT", "RECOVERY", attemptId.value)),
                exportFile(jpeg, byteArrayOf(31, 32, 33, 34),
                    Triple("EXPORT_ARTIFACT", "EXPORT", exportId.value)),
            )
            val observedPairs = protectedArtifacts.flatMap { artifact ->
                val references = database.schemaQueries.artifactReferences(artifact.artifactId.value).executeAsList()
                artifact.references.forEach { (kind, protection, ownerId) ->
                    assertTrue("Producer did not persist $kind/$protection owner $ownerId for ${artifact.artifactId.value}",
                        references.any {
                            it.ref_kind == kind && it.protection_class == protection && it.ref_owner_id == ownerId
                        })
                }
                references.map { it.ref_kind to it.protection_class }
            }.toSet()
            assertEquals(
                setOf(
                    "IMAGE_VERSION" to "AUTHORITATIVE",
                    "IMAGE_VERSION" to "UNDO",
                    "IMPORT_SESSION" to "RECOVERY",
                    "SAVE_MANIFEST" to "SAVE",
                    "EXPORT_ARTIFACT" to "EXPORT",
                    "EXPORT_ATTEMPT" to "RECOVERY",
                ),
                observedPairs,
            )
            val result = CleanupArbiter(store, repository).runBatch(20L)

            assertEquals(1, result.claimedArtifacts)
            assertEquals(1, result.deletedArtifacts)
            assertEquals(0, result.quarantinedArtifacts)
            protectedArtifacts.forEach { artifact ->
                val row = database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne()
                assertEquals("COMMITTED", row.state)
                assertEquals("PRESENT", row.storage_state)
                assertTrue("Producer-protected bytes changed for ${artifact.artifactId.value}",
                    repository.verifyPublished(artifact.relativePath, artifact.sizeBytes, artifact.sha256))
                assertTrue("Producer-protected bytes differ for ${artifact.artifactId.value}",
                    Files.readAllBytes(artifactRoot.resolve(artifact.relativePath)).contentEquals(artifact.expectedBytes))
            }
            val deletedOrphan = database.schemaQueries.artifactById(orphan.artifactId.value).executeAsOne()
            assertEquals("COMMITTED", deletedOrphan.state)
            assertEquals("DELETED", deletedOrphan.storage_state)
            assertEquals(0L, database.schemaQueries.referenceCountForArtifact(orphan.artifactId.value).executeAsOne())
            assertFalse(Files.exists(artifactRoot.resolve(orphan.relativePath)))
        } finally {
            driver.close()
            context.deleteDatabase(databaseName)
            Files.walk(artifactRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun publishImage(
        repository: FileArtifactRepository,
        store: StateStore,
        bytes: ByteArray,
    ): DurableImageArtifact = repository.publishJpeg(ByteArrayInputStream(bytes)).also(store::recordStagedDurableArtifact)

}
