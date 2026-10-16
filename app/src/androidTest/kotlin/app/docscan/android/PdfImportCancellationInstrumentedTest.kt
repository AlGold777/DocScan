package app.docscan.android

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.schedule.HeavyWorkScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportCancellationInstrumentedTest {
    @Test
    fun cancellationAfterCommittedPrefixLeavesImportResumableAndDoesNotDuplicatePages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-cancel-$suffix.db"
        val artifactsDirectory = "pdf-cancel-$suffix"
        val source = File(context.cacheDir, "pdf-cancel-$suffix.pdf")
        val sourceBytes = threePagePdf()
        source.writeBytes(sourceBytes)
        val expectedSourceSha = sha256(source)
        val sourceScratchBefore = renderScratchSnapshot(context.cacheDir)
        val sessionId = ImportSessionId.new()
        val pageIds = (0..2).map { index ->
            val identity = "${sessionId.value}:$index"
            PageId.parse(stableId("$identity:page"))
        }
        val signal = CancellationSignal()
        val scheduler = HeavyWorkScheduler()
        val progressBeforeCancel = mutableListOf<Pair<Int, Int>>()
        val storage = ProductStorage(context, databaseName, artifactsDirectory)
        storage.pdfImportPageCommittedObserverForTest = { observedSession, committed, total ->
            assertEquals(sessionId.value, observedSession)
            assertEquals(3, total)
            if (committed == 1) signal.cancel()
        }
        try {
            val failure = org.junit.Assert.assertThrows(OperationCanceledException::class.java) {
                FileInputStream(source).use { input ->
                    storage.importPdf(
                        source = input,
                        displayName = "cancel-resume.pdf",
                        importSessionId = sessionId,
                        scheduler = scheduler,
                        cancellationSignal = signal,
                        onProgress = { completed, total -> progressBeforeCancel += completed to total },
                    )
                }
            }
            val failedState = importState(context, databaseName, sessionId.value)
            val prefix = storage.importedPdfPageIds(sessionId)
            val indices = sourcePageIndices(context, databaseName, sessionId.value)
            val scratchAfterCancel = renderScratchSnapshot(context.cacheDir)
            val schedulerReleased = scheduler.withAdmission(80L * 1024L * 1024L) { "available" }
            val unchangedSourceSha = sha256(source)
            assertTrue(failure.message.orEmpty().contains("cancel", ignoreCase = true))
            assertEquals(listOf(1 to 3), progressBeforeCancel)
            assertEquals(pageIds.take(1), prefix)
            assertEquals(listOf(0L), indices)
            assertEquals("OPEN", failedState.first)
            assertTrue(failedState.second.orEmpty().contains("cancel", ignoreCase = true))
            assertEquals(sourceScratchBefore, scratchAfterCancel)
            assertEquals("available", schedulerReleased)
            assertEquals(expectedSourceSha, unchangedSourceSha)
            Log.i(TAG, "CANCELLED_STATE session=${sessionId.value} state=${failedState.first} " +
                "error=${failedState.second?.replace(' ', '_')} progress=${progressBeforeCancel.joinToString()} " +
                "pageIds=${prefix.joinToString { it.value }} sourcePageIndices=${indices.joinToString()} " +
                "scratch=${scratchAfterCancel.joinToString { it.first }} scheduler=$schedulerReleased " +
                "sourceSha256=$unchangedSourceSha")

            storage.pdfImportPageCommittedObserverForTest = null
            val resumedProgress = mutableListOf<Pair<Int, Int>>()
            val resumed = storage.resumeIncompletePdfImports(scheduler) { _, completed, total ->
                resumedProgress += completed to total
            }
            val recoveredState = importState(context, databaseName, sessionId.value)
            val recoveredPageIds = storage.importedPdfPageIds(sessionId)
            val recoveredIndices = sourcePageIndices(context, databaseName, sessionId.value)
            val scratchAfterResume = renderScratchSnapshot(context.cacheDir)
            val schedulerReusable = scheduler.withAdmission(80L * 1024L * 1024L) { "available" }
            val finalSourceSha = sha256(source)
            assertEquals(pageIds, resumed)
            assertEquals(pageIds, recoveredPageIds)
            assertEquals(listOf(0L, 1L, 2L), recoveredIndices)
            assertEquals(listOf(2 to 3, 3 to 3), resumedProgress)
            assertEquals("COMPLETED", recoveredState.first)
            assertNull(recoveredState.second)
            assertEquals(sourceScratchBefore, scratchAfterResume)
            assertEquals("available", schedulerReusable)
            assertEquals(expectedSourceSha, finalSourceSha)
            Log.i(TAG, "RECOVERED_STATE session=${sessionId.value} state=${recoveredState.first} " +
                "error=null progress=${resumedProgress.joinToString()} " +
                "pageIds=${recoveredPageIds.joinToString { it.value }} sourcePageIndices=${recoveredIndices.joinToString()} " +
                "scratch=${scratchAfterResume.joinToString { it.first }} scheduler=$schedulerReusable " +
                "sourceSha256=$finalSourceSha")
        } finally {
            storage.pdfImportPageCommittedObserverForTest = null
            storage.close()
            source.delete()
            File(context.noBackupFilesDir, artifactsDirectory).deleteRecursively()
            context.deleteDatabase(databaseName)
        }
    }

    private fun threePagePdf(): ByteArray = ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage(PDRectangle.A4)) }
            document.save(output)
        }
        output.toByteArray()
    }

    private fun sourcePageIndices(context: android.content.Context, databaseName: String, sessionId: String): List<Long> =
        readDatabase(context, databaseName) { database ->
            database.rawQuery(
                "SELECT source_page_index FROM Page WHERE import_session_id = ? ORDER BY source_page_index ASC",
                arrayOf(sessionId),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        }

    private fun importState(context: android.content.Context, databaseName: String, sessionId: String): Pair<String, String?> =
        readDatabase(context, databaseName) { database ->
            database.rawQuery(
                "SELECT state, last_error FROM ImportSession WHERE import_session_id = ?",
                arrayOf(sessionId),
            ).use { cursor ->
                assertTrue("ImportSession row must exist", cursor.moveToFirst())
                cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }

    private fun <T> readDatabase(
        context: android.content.Context,
        databaseName: String,
        block: (SQLiteDatabase) -> T,
    ): T {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try { block(database) } finally { database.close() }
    }

    private fun renderScratchSnapshot(cache: File): List<Pair<String, String>> =
        cache.listFiles().orEmpty()
            .filter { it.name.startsWith("pdf-page-") }
            .map { it.name to sha256(it) }
            .sortedBy { it.first }

    private fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PdfImportCancellation"
    }
}
