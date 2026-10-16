package app.docscan.android

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
import app.docscan.core.store.ExternalExportPackagePlan
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.store.RecognitionGeometry
import app.docscan.core.store.RecognizedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FrozenSaveOutputComposerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun frozenManifestProducesReadBackValidatedPdfAndCompanionJpeg() {
        val fixture = fixture("positive")
        try {
            val output = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.8f, 150),
                companionJpegQuality = 82,
            )
            assertEquals(1, output.pages.size)
            assertEquals(fixture.snapshot.pages.single().imageVersionId.value, output.pages.single().imageVersionId)
            assertTrue(output.pdfFile.isFile && output.pdfFile.length() > 0L)
            assertTrue(output.pages.single().jpegFile.isFile && output.pages.single().jpegSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(maxOf(output.pages.single().widthPx, output.pages.single().heightPx) <= 3000)
            assertTrue(File(output.pdfFile.parentFile, "page-1.jpg").isFile)
        } finally {
            close(fixture)
        }
    }

    @Test
    fun oversizedImageIsScaledForPdfAndJpegWithoutChangingFrozenSource() {
        val fixture = fixture("bounded", width = 3600, height = 1800)
        try {
            val frozenHash = fixture.snapshot.pages.single().artifactSha256
            val output = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.85f, 300),
                companionJpegQuality = 85,
            )
            assertEquals(3000, output.pages.single().widthPx)
            assertEquals(1500, output.pages.single().heightPx)
            assertEquals(frozenHash, sha256(fixture.source))
        } finally {
            close(fixture)
        }
    }

    @Test
    fun corruptedFrozenSourceFailsWithoutPublishingFinalOutputDirectory() {
        val fixture = fixture("negative")
        try {
            fixture.source.writeBytes(byteArrayOf(0, 1, 2, 3))
            val failure = runCatching {
                fixture.storage.composeFrozenSaveOutputSet(
                    fixture.snapshot,
                    PdfOutputSettings(595f, 842f, 0.8f, 150),
                    companionJpegQuality = 82,
                )
            }.exceptionOrNull()
            assertTrue("Corrupt frozen source must fail", failure != null)
            assertFalse(File(fixture.outputRoot, fixture.snapshot.saveManifestId.value).exists())
            assertTrue(fixture.outputRoot.listFiles().orEmpty().none { it.name.endsWith(".building") })
        } finally {
            close(fixture)
        }
    }

    @Test
    fun parentDirectorySyncFailureAfterRenameRemovesUnregisteredFinalCandidate() {
        val fixture = fixture("sync-failure", directorySyncFactory = parentSyncFailureOnceFactory())
        try {
            val sourceHash = sha256(fixture.source)
            val failure = runCatching {
                fixture.storage.composeFrozenSaveOutputSet(
                    fixture.snapshot,
                    PdfOutputSettings(595f, 842f, 0.8f, 150),
                    companionJpegQuality = 82,
                )
            }.exceptionOrNull()

            assertTrue("Output-root sync failure must propagate", failure is IOException)
            assertFalse(File(fixture.outputRoot, fixture.snapshot.saveManifestId.value).exists())
            assertTrue(fixture.outputRoot.listFiles().orEmpty().none { it.name.endsWith(".building") })
            assertEquals(sourceHash, sha256(fixture.source))

            val retry = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.8f, 150),
                companionJpegQuality = 82,
            )
            assertTrue(retry.pdfFile.isFile && retry.pdfFile.length() > 0L)
            assertEquals(sourceHash, sha256(fixture.source))
        } finally {
            close(fixture)
        }
    }

    @Test
    fun failedCompositionCanBeCancelledWithoutReleasingSourceArtifact() {
        val fixture = fixture("sync-failure-cancel", directorySyncFactory = parentSyncFailureOnceFactory())
        try {
            val sourceHash = sha256(fixture.source)
            val failure = runCatching {
                fixture.storage.composeFrozenSaveOutputSet(
                    fixture.snapshot,
                    PdfOutputSettings(595f, 842f, 0.8f, 150),
                    companionJpegQuality = 82,
                )
            }.exceptionOrNull()

            assertTrue("Output-root sync failure must propagate", failure is IOException)
            fixture.storage.cancelUnstartedSaveManifest(fixture.snapshot.saveManifestId)
            assertFalse(File(fixture.outputRoot, fixture.snapshot.saveManifestId.value).exists())
            assertTrue(fixture.outputRoot.listFiles().orEmpty().none { it.name.endsWith(".building") })
            assertEquals(sourceHash, sha256(fixture.source))
        } finally {
            close(fixture)
        }
    }

    @Test
    fun startupRecoveryRemovesOnlyCandidatesForInterruptedLocalSave() {
        val fixture = fixture("startup-recovery")
        try {
            val saveManifestId = fixture.snapshot.saveManifestId.value
            val sourceHash = sha256(fixture.source)
            fixture.storage.close()

            val candidateRoot = File(context.noBackupFilesDir, "docscan-save-candidates")
            assertTrue(candidateRoot.mkdirs() || candidateRoot.isDirectory)
            val finalCandidate = File(candidateRoot, saveManifestId).apply {
                assertTrue(mkdirs())
                File(this, "orphan.marker").writeText("incomplete final candidate")
            }
            val stagingCandidate = File(candidateRoot, ".$saveManifestId.${UUID.randomUUID()}.building").apply {
                assertTrue(mkdirs())
                File(this, "orphan.marker").writeText("incomplete staging candidate")
            }
            val unrelatedCandidate = File(candidateRoot, ".${UUID.randomUUID()}.${UUID.randomUUID()}.building").apply {
                assertTrue(mkdirs())
            }

            val databaseFile = context.getDatabasePath(fixture.databaseName)
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                val dependency = ContentValues().apply {
                    put("save_dependency_result_id", UUID.randomUUID().toString())
                    put("save_manifest_id", saveManifestId)
                    put("image_version_id", fixture.snapshot.pages.single().imageVersionId.value)
                    put("dependency_kind", "LOCAL_OUTPUT_COMPOSITION")
                    put("dependency_spec", "frozen-image-version:${fixture.snapshot.pages.single().imageVersionId.value}")
                    putNull("payload")
                    putNull("artifact_id")
                }
                db.insertOrThrow("SaveDependencyResult", null, dependency)
            }

            fixture.storage = ProductStorage(context, fixture.databaseName, fixture.artifactDirectory)

            assertEquals(1, fixture.storage.abandonedLocalOutputCompositionsAtStartup)
            assertFalse(finalCandidate.exists())
            assertFalse(stagingCandidate.exists())
            assertTrue(unrelatedCandidate.isDirectory)
            assertEquals(sourceHash, sha256(fixture.source))
            assertEquals(1L, fixture.storage.workspaceProjection().pages.size.toLong())
            val replacement = fixture.storage.freezeSelectedPagesForSave()
            fixture.storage.cancelUnstartedSaveManifest(replacement.saveManifestId)
        } finally {
            close(fixture)
        }
    }

    @Test
    fun committedSaveCandidateIsRemovedAtStartupWhileDurableOutputsRemain() {
        val fixture = fixture("committed-candidate-recovery")
        try {
            val output = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.8f, 150),
                companionJpegQuality = 82,
            )
            val candidateDirectory = requireNotNull(output.pdfFile.parentFile)
            val sourceHash = sha256(fixture.source)
            val exportId = fixture.storage.commitLocalExportOutputSet(fixture.snapshot, output)
            assertTrue(candidateDirectory.isDirectory)
            assertTrue(fixture.storage.cleanupCommittedLocalOutputCandidate(fixture.snapshot.saveManifestId).isSuccess)
            assertFalse(candidateDirectory.exists())
            assertTrue(candidateDirectory.mkdirs())
            File(candidateDirectory, "cleanup-retry.marker").writeText("retry after cleanup failure")

            fixture.storage.close()
            fixture.storage = ProductStorage(context, fixture.databaseName, fixture.artifactDirectory)

            assertEquals(0, fixture.storage.failedCommittedSaveCandidateCleanupsAtStartup)
            assertFalse(candidateDirectory.exists())
            val committed = fixture.storage.committedExportOutputs(exportId)
            assertEquals(2, committed.size)
            assertTrue(committed.all(fixture.storage::verifyExportOutput))
            assertEquals(sourceHash, sha256(fixture.source))
        } finally {
            close(fixture)
        }
    }

    @Test
    fun failedLocalExportCommitKeepsItsCandidateDirectory() {
        val fixture = fixture("commit-failure-keeps-candidate")
        try {
            val output = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.8f, 150),
                companionJpegQuality = 82,
            )
            val invalidPackage = ExternalExportPackagePlan(
                destinationTreeUri = "content://example/tree/primary%3ADocuments",
                commonBasename = "fixture",
                providerQualificationId = "qualification",
                adapterId = "adapter",
                providerVersion = "version",
                osVersion = "os",
                destinationAccessClass = "primary-internal",
                outputDisplayNames = listOf("fixture.pdf"),
            )
            val sourceHash = sha256(fixture.source)

            val failure = runCatching {
                fixture.storage.commitLocalExportOutputSet(fixture.snapshot, output, invalidPackage)
            }.exceptionOrNull()

            assertTrue("Invalid package mapping must fail local commit", failure != null)
            assertTrue(requireNotNull(output.pdfFile.parentFile).isDirectory)
            assertTrue(output.pdfFile.isFile)
            assertEquals(sourceHash, sha256(fixture.source))
        } finally {
            close(fixture)
        }
    }

    @Test
    fun startupAbandonsInterruptedSaveAfterObjectRenameBeforeArtifactRow() {
        var failObjectSync = false
        val syncFactory: () -> DirectorySync = {
            object : DirectorySync {
                override fun sync(directory: java.nio.file.Path) {
                    val isObjectShard = directory.parent?.fileName?.toString() == "objects"
                        && directory.fileName.toString().matches(Regex("[0-9a-f]{2}"))
                    if (failObjectSync && isObjectShard) {
                        failObjectSync = false
                        throw IOException("injected sync failure after export object rename")
                    }
                    AndroidDirectorySync().sync(directory)
                }
            }
        }
        val fixture = fixture("staged-output-recovery", directorySyncFactory = syncFactory)
        try {
            val output = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.8f, 150),
                companionJpegQuality = 82,
            )
            val candidateDirectory = requireNotNull(output.pdfFile.parentFile)
            val outputObjectCountBefore = File(context.noBackupFilesDir, fixture.artifactDirectory)
                .resolve("objects").walkTopDown().count { it.isFile }
            val sourceHash = sha256(fixture.source)
            val pdfId = app.docscan.core.model.ArtifactId.forSaveOutput(fixture.snapshot.saveManifestId, 0)
            val unregisteredPdf = File(
                context.noBackupFilesDir,
                "${fixture.artifactDirectory}/objects/${pdfId.value.take(2)}/${pdfId.value}.pdf",
            )

            // The publication intent is durable; fail after the PDF rename and before its DB row.
            failObjectSync = true
            assertTrue(runCatching {
                fixture.storage.commitLocalExportOutputSet(fixture.snapshot, output)
            }.isFailure)
            assertTrue(unregisteredPdf.isFile)
            assertTrue(candidateDirectory.isDirectory)

            fixture.storage.close()
            fixture.storage = ProductStorage(context, fixture.databaseName, fixture.artifactDirectory)

            assertEquals(1, fixture.storage.abandonedLocalOutputCompositionsAtStartup)
            assertFalse(candidateDirectory.exists())
            assertFalse(unregisteredPdf.exists())
            assertEquals(outputObjectCountBefore, File(context.noBackupFilesDir, fixture.artifactDirectory)
                .resolve("objects").walkTopDown().count { it.isFile })
            assertEquals(1, fixture.storage.visiblePages().size)
            assertEquals(sourceHash, sha256(fixture.source))
        } finally {
            close(fixture)
        }
    }

    @Test
    fun candidateCleanupFailureKeepsCommittedExportAndRetriesAtStartup() {
        val fixture = fixture("candidate-cleanup-retry", directorySyncFactory = failCandidateCleanupSyncFactory())
        try {
            val output = fixture.storage.composeFrozenSaveOutputSet(
                fixture.snapshot,
                PdfOutputSettings(595f, 842f, 0.8f, 150),
                companionJpegQuality = 82,
            )
            val candidateDirectory = requireNotNull(output.pdfFile.parentFile)
            val exportId = fixture.storage.commitLocalExportOutputSet(fixture.snapshot, output)
            val cleanup = fixture.storage.cleanupCommittedLocalOutputCandidate(fixture.snapshot.saveManifestId)

            assertTrue(cleanup.isFailure)
            assertFalse(candidateDirectory.exists())
            val committedOutputs = fixture.storage.committedExportOutputs(exportId)
            assertEquals(2, committedOutputs.size)
            committedOutputs.forEach { output ->
                val file = fixture.storage.artifactPath(output.relativePath).toFile()
                assertTrue(file.isFile)
                assertEquals(output.sizeBytes, file.length())
                assertEquals(output.sha256, sha256(file))
            }

            fixture.storage.close()
            fixture.storage = ProductStorage(context, fixture.databaseName, fixture.artifactDirectory)

            assertEquals(0, fixture.storage.failedCommittedSaveCandidateCleanupsAtStartup)
            assertFalse(candidateDirectory.exists())
            assertEquals(committedOutputs, fixture.storage.committedExportOutputs(exportId))
        } finally {
            close(fixture)
        }
    }

    private fun failCandidateCleanupSyncFactory(): () -> DirectorySync {
        var factoryCalls = 0
        return {
            factoryCalls++
            if (factoryCalls == 3) {
                object : DirectorySync {
                    override fun sync(directory: java.nio.file.Path) {
                        throw IOException("injected committed candidate cleanup sync failure")
                    }
                }
            } else AndroidDirectorySync()
        }
    }

    private fun parentSyncFailureOnceFactory(): () -> DirectorySync {
        var factoryCalls = 0
        var composerSyncCalls = 0
        return {
            factoryCalls++
            if (factoryCalls == 1) {
                // ProductStorage constructs its artifact repository before the composer.
                AndroidDirectorySync()
            } else {
                object : DirectorySync {
                    override fun sync(directory: java.nio.file.Path) {
                        composerSyncCalls++
                        if (composerSyncCalls == 2) {
                            throw IOException("injected output-root sync failure")
                        }
                        AndroidDirectorySync().sync(directory)
                    }
                }
            }
        }
    }

    private fun fixture(
        label: String,
        width: Int = 240,
        height: Int = 320,
        addOcr: Boolean = true,
        directorySyncFactory: () -> DirectorySync = { AndroidDirectorySync() },
    ): Fixture {
        val id = UUID.randomUUID().toString()
        val databaseName = "test-save-$id.db"
        val artifactDirectory = "test-save-artifacts-$id"
        val storage = ProductStorage(
            context,
            databaseName,
            artifactDirectory,
            directorySyncFactory = directorySyncFactory,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawText("DOCSCAN", 20f, 80f, Paint().apply {
                color = Color.BLACK
                textSize = 24f
            })
        }
        val encoded = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            stream.toByteArray()
        }
        bitmap.recycle()
        val imported = storage.importImage(encoded.inputStream(), "image/jpeg", "fixture.jpg")
        storage.setSelected(imported.pageId, true)
        val page = storage.visiblePages().single()
        if (addOcr) storage.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = storage.workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = page.contentRevision,
                desiredOperationKey = ProductActivity.OCR_OPERATION_KEY,
                recognizedText = "DOCSCAN Привет",
                geometry = RecognitionGeometry(
                    sourceWidthPx = width.toLong(),
                    sourceHeightPx = height.toLong(),
                    coordinateSpace = OCR_WORD_COORDINATE_SPACE,
                    words = listOf(
                        RecognizedWord("DOCSCAN", 20, 50, 150, 82, 99.0),
                        RecognizedWord("Привет", 25, 100, 160, 135, 96.0),
                    ),
                ),
            ),
        )
        val snapshot = storage.freezeSelectedPagesForSave()
        val source = storage.artifactPath(snapshot.pages.single().artifactRelativePath).toFile()
        val outputRoot = File(context.noBackupFilesDir, "docscan-save-candidates")
        return Fixture(storage, databaseName, artifactDirectory, snapshot, source, outputRoot)
    }

    private fun close(fixture: Fixture) {
        fixture.storage.close()
        context.deleteDatabase(fixture.databaseName)
        File(context.noBackupFilesDir, fixture.artifactDirectory).deleteRecursively()
        File(context.noBackupFilesDir, "docscan-save-candidates").deleteRecursively()
    }

    private fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(
        var storage: ProductStorage,
        val databaseName: String,
        val artifactDirectory: String,
        val snapshot: app.docscan.core.store.SaveManifestSnapshot,
        val source: File,
        val outputRoot: File,
    )
}
