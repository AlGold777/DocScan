package app.docscan.android

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
import app.docscan.core.store.RecentDocumentSnapshot
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.store.RecognitionGeometry
import app.docscan.core.store.RecognizedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TryAgainImportActivityInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun retryRejectsChangedSavedPdfBeforeCreatingImportState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "try-again-integrity-$suffix.db"
        val artifactDirectory = "try-again-integrity-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val previousStorageOverride = application.productStorageOverrideForTest
        val previousStorageProvider = application.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null

        try {
            application.productStorageProviderForTest = null
            application.productStorageOverrideForTest = storage

            createRecognizedPage(storage, "INTEGRITY FIRST", Color.rgb(192, 32, 32))
            createRecognizedPage(storage, "INTEGRITY SECOND", Color.rgb(32, 32, 192))
            storage.visiblePages().forEach { storage.setSelected(it.pageId, true) }
            val beforeWorkspace = storage.workspaceProjection()
            val frozen = storage.freezeSelectedPagesForSave()
            val output = storage.composeFrozenSaveOutputSet(
                frozen,
                PdfOutputSettings(595f, 842f, 0.85f, 150),
                companionJpegQuality = 85,
            )
            val exportId = storage.commitLocalExportOutputSet(frozen, output)
            val pdf = storage.committedExportOutputs(exportId).single { it.mimeType == "application/pdf" }
            val sourceFile = storage.exportOutputFile(pdf)
            val originalBytes = sourceFile.readBytes()
            val originalSha256 = sha256(originalBytes)
            assertTrue("Committed source must initially match its checksum", storage.verifyExportOutput(pdf))
            val inspection = AndroidPdfImportReader(context).inspect(sourceFile)
            assertEquals("Committed source contains both pages", 2, inspection.pageCount)
            val beforeRecent = storage.recentDocuments()
            val beforeCounts = integrityTableCounts(context.getDatabasePath(databaseName))
            Log.i("TryAgainIntegrityEvidence", "scenario=baseline size=${originalBytes.size} sha256=$originalSha256 pageCount=${inspection.pageCount} tableCounts=$beforeCounts workspacePages=${beforeWorkspace.pages.map { it.pageId }} recentCount=${beforeRecent.size}")
            val recent = RecentDocumentSnapshot(
                recentId = exportId.value,
                exportArtifactId = exportId,
                documentUri = "content://test-provider/recent/$suffix",
                displayName = "retry-integrity-source.pdf",
                pageCount = 2L,
                savedWallMillis = System.currentTimeMillis(),
                ocrStatus = "FULL",
            )

            // Change one PDF header byte without changing file size; PDFBox must still parse it.
            val tamperedBytes = originalBytes.copyOf()
            val headerMinorVersionIndex = "%PDF-1.".toByteArray(Charsets.US_ASCII).size
            assertTrue("PDF header has a minor-version digit", tamperedBytes[headerMinorVersionIndex].toInt().toChar().isDigit())
            tamperedBytes[headerMinorVersionIndex] = if (tamperedBytes[headerMinorVersionIndex].toInt().toChar() == '7') '6'.code.toByte() else '7'.code.toByte()
            sourceFile.writeBytes(tamperedBytes)
            assertEquals("Tampering preserves committed file length", originalBytes.size, sourceFile.length().toInt())
            assertTrue("Tampered source differs from persisted checksum", !storage.verifyExportOutput(pdf))
            assertEquals("Changed header remains parseable as a two-page PDF", 2, AndroidPdfImportReader(context).inspect(sourceFile).pageCount)
            val tamperedSha256 = sha256(tamperedBytes)
            Log.i("TryAgainIntegrityEvidence", "scenario=same-size-sha-mismatch originalSize=${originalBytes.size} originalSha256=$originalSha256 mutatedSize=${tamperedBytes.size} mutatedSha256=$tamperedSha256 parsedPages=${inspection.pageCount}")

            val launchedScenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launchedScenario
            val activity = awaitCaptureReady(launchedScenario)
            launchedScenario.onActivity { it.tryAgainWithSavedPdf(recent) }
            val expectedStatus = activity.getString(
                R.string.try_again_failed,
                "Recent saved PDF failed its local checksum check",
            )
            awaitCondition("Try again must visibly reject the mismatched local PDF checksum") {
                var visible = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    visible = (findViewByTag(activity.window.decorView, "docscan-status") as? TextView)
                        ?.text?.toString() == expectedStatus
                }
                visible
            }

            val afterSameSizeWorkspace = storage.workspaceProjection()
            val afterSameSizeRecent = storage.recentDocuments()
            val afterSameSizeCounts = integrityTableCounts(context.getDatabasePath(databaseName))
            Log.i("TryAgainIntegrityEvidence", "scenario=same-size-rejection status=$expectedStatus countsBefore=$beforeCounts countsAfter=$afterSameSizeCounts workspaceEqual=${beforeWorkspace == afterSameSizeWorkspace} recentEqual=${beforeRecent == afterSameSizeRecent} sourceSize=${sourceFile.length()} sourceSha256=${sha256(sourceFile.readBytes())}")
            assertEquals("Try again does not alter workspace Page order, Active or Selected state", beforeWorkspace, afterSameSizeWorkspace)
            assertEquals("Try again does not alter Recent metadata", beforeRecent, afterSameSizeRecent)
            assertEquals("Try again creates no import or publication records", beforeCounts, afterSameSizeCounts)
            assertEquals("Try again leaves the tampered source byte-for-byte unchanged", tamperedBytes.toList(), sourceFile.readBytes().toList())
            assertEquals("Tampered source SHA remains stable after rejection", tamperedSha256, sha256(sourceFile.readBytes()))

            // Appending one legal PDF whitespace byte exercises persisted-size mismatch separately from same-size SHA mismatch.
            val sizeMismatchBytes = originalBytes + byteArrayOf('\n'.code.toByte())
            sourceFile.writeBytes(sizeMismatchBytes)
            assertEquals("The size-mismatch mutation adds exactly one byte", originalBytes.size + 1L, sourceFile.length())
            assertTrue("Size-mismatched source fails persisted output verification", !storage.verifyExportOutput(pdf))
            val sizeMismatchInspection = AndroidPdfImportReader(context).inspect(sourceFile)
            assertEquals("Appended legal PDF whitespace keeps both pages parseable", 2, sizeMismatchInspection.pageCount)
            val sizeMismatchSha256 = sha256(sizeMismatchBytes)
            Log.i("TryAgainIntegrityEvidence", "scenario=persisted-size-mismatch originalSize=${originalBytes.size} originalSha256=$originalSha256 mutatedSize=${sizeMismatchBytes.size} mutatedSha256=$sizeMismatchSha256 parsedPages=${sizeMismatchInspection.pageCount} tableCounts=$beforeCounts workspacePages=${beforeWorkspace.pages.map { it.pageId }} recentCount=${beforeRecent.size}")

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                (findViewByTag(activity.window.decorView, "docscan-status") as TextView).text = "RETRY SIZE CHECK START"
            }
            launchedScenario.onActivity { it.tryAgainWithSavedPdf(recent) }
            awaitCondition("Try again must visibly reject a readable source with changed committed size") {
                var visible = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    visible = (findViewByTag(activity.window.decorView, "docscan-status") as? TextView)
                        ?.text?.toString() == expectedStatus
                }
                visible
            }
            val afterSizeWorkspace = storage.workspaceProjection()
            val afterSizeRecent = storage.recentDocuments()
            val afterSizeCounts = integrityTableCounts(context.getDatabasePath(databaseName))
            val afterSizeBytes = sourceFile.readBytes()
            Log.i("TryAgainIntegrityEvidence", "scenario=size-mismatch-rejection status=$expectedStatus countsBefore=$beforeCounts countsAfter=$afterSizeCounts workspaceEqual=${beforeWorkspace == afterSizeWorkspace} recentEqual=${beforeRecent == afterSizeRecent} sourceSize=${afterSizeBytes.size} sourceSha256=${sha256(afterSizeBytes)}")
            assertEquals("Size rejection leaves workspace Page order, Active or Selected state unchanged", beforeWorkspace, afterSizeWorkspace)
            assertEquals("Size rejection leaves Recent metadata unchanged", beforeRecent, afterSizeRecent)
            assertEquals("Size rejection creates no import or publication records", beforeCounts, afterSizeCounts)
            assertEquals("Size rejection leaves source bytes unchanged", sizeMismatchBytes.toList(), afterSizeBytes.toList())
            assertEquals("Size rejection leaves mutated source SHA unchanged", sizeMismatchSha256, sha256(afterSizeBytes))
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = previousStorageProvider
            application.productStorageOverrideForTest = previousStorageOverride
            storage.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun retryImportsAllPagesSuppressesConcurrentDuplicateAndPreservesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "try-again-$suffix.db"
        val artifactDirectory = "try-again-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val previousStorageOverride = application.productStorageOverrideForTest
        val previousStorageProvider = application.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null

        try {
            application.productStorageProviderForTest = null
            application.productStorageOverrideForTest = storage

            createRecognizedPage(storage, "FIRST PAGE", Color.rgb(192, 32, 32))
            createRecognizedPage(storage, "SECOND PAGE", Color.rgb(32, 32, 192))
            storage.visiblePages().forEach { storage.setSelected(it.pageId, true) }
            val initial = storage.workspaceProjection()
            val initialPageIds = initial.pages.map { it.pageId }.toSet()
            val initialImageVersionIds = initial.pages.map { it.imageVersionId }.toSet()
            val frozen = storage.freezeSelectedPagesForSave()
            val output = storage.composeFrozenSaveOutputSet(
                frozen,
                PdfOutputSettings(595f, 842f, 0.85f, 150),
                companionJpegQuality = 85,
            )
            val exportId = storage.commitLocalExportOutputSet(frozen, output)
            val pdf = storage.committedExportOutputs(exportId).single { it.mimeType == "application/pdf" }
            val sourceFile = storage.exportOutputFile(pdf)
            val sourceBytes = sourceFile.readBytes()
            val sourceSha256 = sha256(sourceBytes)
            assertTrue(storage.verifyExportOutput(pdf))
            val sourceInspection = AndroidPdfImportReader(context).inspect(sourceFile)
            assertEquals("The saved retry fixture contains both source pages", 2, sourceInspection.pageCount)
            assertTrue(
                "A fully recognized saved PDF must expose a reusable text layer on every source page",
                sourceInspection.pages.all { it.hasReusableTextLayer },
            )

            val recent = RecentDocumentSnapshot(
                recentId = exportId.value,
                exportArtifactId = exportId,
                documentUri = "content://test-provider/recent/$suffix",
                displayName = "retry-source.pdf",
                pageCount = 2L,
                savedWallMillis = System.currentTimeMillis(),
                ocrStatus = "FULL",
            )
            val launchedScenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launchedScenario
            val activity = awaitCaptureReady(launchedScenario)

            launchedScenario.onActivity {
                it.tryAgainWithSavedPdf(recent)
                it.tryAgainWithSavedPdf(recent)
            }
            awaitPageCount(storage, initial.pages.size + 2, activity)
            awaitRetryHandlerIdle(activity)

            val firstRetry = storage.workspaceProjection().pages.filter { it.pageId !in initialPageIds }
            assertEquals("One retry imports every source PDF page", 2, firstRetry.size)
            assertTrue("Retry Pages are selected", firstRetry.all { it.selected })
            assertTrue("Retry Pages have new Page identities", firstRetry.none { it.pageId in initialPageIds })
            assertTrue("Retry Pages have new ImageVersion identities", firstRetry.none { it.imageVersionId in initialImageVersionIds })
            assertEquals(2, firstRetry.map { it.pageId }.distinct().size)
            assertEquals(2, firstRetry.map { it.imageVersionId }.distinct().size)
            assertEquals("The rapid duplicate action must not create a second page block", 4, storage.workspaceProjection().pages.size)
            assertTrue("Each retried page keeps the saved searchable text", firstRetry.all { page ->
                page.recognizedText.orEmpty().lowercase().filter { it.isLetterOrDigit() }
                    .contains("documentimportverifiedtext")
            })

            launchedScenario.onActivity { it.tryAgainWithSavedPdf(recent) }
            awaitPageCount(storage, initial.pages.size + 4, activity)
            awaitRetryHandlerIdle(activity)

            val afterSecondRetry = storage.workspaceProjection().pages
            val secondRetry = afterSecondRetry.filter { it.pageId !in initialPageIds && it.pageId !in firstRetry.map { page -> page.pageId }.toSet() }
            assertEquals("A later deliberate retry creates another full block", 2, secondRetry.size)
            assertTrue(secondRetry.all { it.selected })
            assertTrue(secondRetry.none { it.imageVersionId in initialImageVersionIds || it.imageVersionId in firstRetry.map { page -> page.imageVersionId }.toSet() })
            assertTrue("The later retry also reuses the saved searchable text", secondRetry.all { page ->
                page.recognizedText.orEmpty().lowercase().filter { it.isLetterOrDigit() }
                    .contains("documentimportverifiedtext")
            })
            assertEquals("Committed local source bytes remain unchanged", sourceSha256, sha256(sourceFile.readBytes()))
            assertEquals(sourceBytes.toList(), sourceFile.readBytes().toList())
            assertTrue(storage.verifyExportOutput(pdf))
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = previousStorageProvider
            application.productStorageOverrideForTest = previousStorageOverride
            storage.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    private fun createRecognizedPage(storage: ProductStorage, label: String, color: Int) {
        val searchableText = "$label DOCUMENT IMPORT VERIFIED TEXT"
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawText(label, 16f, 100f, Paint().apply {
                this.color = color
                textSize = 23f
            })
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val imported = storage.importImage(bytes.inputStream(), "image/jpeg", "$label.jpg")
        val page = storage.visiblePages().single { it.pageId == imported.pageId }
        storage.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = storage.workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = page.contentRevision,
                desiredOperationKey = ProductActivity.OCR_OPERATION_KEY,
                recognizedText = searchableText,
                geometry = RecognitionGeometry(
                    sourceWidthPx = 240L,
                    sourceHeightPx = 320L,
                    coordinateSpace = OCR_WORD_COORDINATE_SPACE,
                    words = listOf(RecognizedWord(searchableText, 16, 70, 220, 115, 99.0)),
                ),
            ),
        )
    }

    private fun integrityTableCounts(databaseFile: File): Map<String, Long> {
        val database = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            listOf("ImportSession", "Page", "ImageVersion", "ArtifactRecord", "Recognition", "ArtifactReference", "RecentDocument")
                .associateWith { table ->
                    database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                        check(cursor.moveToFirst()) { "Could not count $table rows" }
                        cursor.getLong(0)
                    }
                }
        } finally {
            database.close()
        }
    }

    private fun awaitCaptureReady(scenario: ActivityScenario<ProductActivity>): ProductActivity {
        var activity: ProductActivity? = null
        awaitCondition("Activity must finish storage initialization") {
            scenario.onActivity { current -> activity = current }
            activity?.let { current ->
                findViewByTag(current.window.decorView, "docscan-capture-button")?.isEnabled == true
            } == true
        }
        return requireNotNull(activity)
    }

    private fun awaitPageCount(storage: ProductStorage, expected: Int, activity: ProductActivity) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (storage.workspaceProjection().pages.size == expected) return
            SystemClock.sleep(50L)
        }
        var statusText = "<unavailable>"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            statusText = (findViewByTag(activity.window.decorView, "docscan-status") as? TextView)
                ?.text?.toString() ?: "<missing>"
        }
        throw AssertionError(
            "Expected workspace to contain $expected pages; found ${storage.workspaceProjection().pages.size}; status='$statusText'",
        )
    }

    private fun awaitRetryHandlerIdle(activity: ProductActivity) {
        val completedTwoPages = activity.getString(R.string.try_again_complete, 2)
        awaitCondition("Try again handler must return to its completed state") {
            var done = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val status = findViewByTag(activity.window.decorView, "docscan-status") as? TextView
                done = status?.text?.toString() == completedTwoPages
            }
            done
        }
    }

    private fun findViewByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findViewByTag(root.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError(description)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

}
