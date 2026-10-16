package app.docscan.android

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.model.ArtifactId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.schedule.HeavyWorkScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportPagePublicationSyncFailureInstrumentedTest {
    @Test
    fun postPublicationSyncFailurePreservesPrefixAndResumesDeterministicPageOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-page-sync-$suffix.db"
        val artifactDirectoryName = "pdf-page-sync-$suffix"
        val artifactsRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val sourceFile = File(context.cacheDir, "pdf-page-sync-source-$suffix.pdf")
        val sessionId = ImportSessionId.new()
        val sourceBytes = threePagePdf()
        sourceFile.writeBytes(sourceBytes)
        val sourceSha = sha256(sourceFile)
        val failingIndex = 1
        val pageIdentity = "${sessionId.value}:$failingIndex"
        val failedPageId = ArtifactId.parse(stableId("$pageIdentity:artifact"))
        val failedPageFile = File(
            artifactsRoot,
            "objects/${failedPageId.value.take(2)}/${failedPageId.value}.png",
        )
        val expectedShardFile = requireNotNull(failedPageFile.parentFile)
        val expectedShardRaw = expectedShardFile.toPath().toAbsolutePath().normalize()
        val expectedShardCanonical = expectedShardFile.canonicalFile.toPath()
        val expectedPageIds = (0..2).map { index ->
            val identity = "${sessionId.value}:$index"
            PageId.parse(stableId("$identity:page"))
        }
        val injected = AtomicBoolean(false)
        val injectionCount = AtomicInteger(0)
        val syncCallCount = AtomicInteger(0)
        val syncFactory: () -> DirectorySync = {
            object : DirectorySync {
                override fun sync(directory: Path) {
                    val observedDirectoryRaw = directory.toAbsolutePath().normalize()
                    val observedDirectoryCanonical = directory.toFile().canonicalFile.toPath()
                    val targetExists = failedPageFile.isFile
                    Log.i(
                        TAG,
                        "DIRECTORY_SYNC_TRACE call=${syncCallCount.incrementAndGet()} " +
                            "pathRaw=$observedDirectoryRaw expectedShardRaw=$expectedShardRaw " +
                            "pathCanonical=$observedDirectoryCanonical expectedShardCanonical=$expectedShardCanonical " +
                            "targetExists=$targetExists injected=${injected.get()}",
                    )
                    if (observedDirectoryCanonical == expectedShardCanonical
                        && targetExists
                        && injected.compareAndSet(false, true)
                    ) {
                        injectionCount.incrementAndGet()
                        Log.i(TAG, "DIRECTORY_SYNC_INJECTION pageIndex=$failingIndex target=$failedPageFile")
                        throw IOException(FAILURE_MESSAGE)
                    }
                    AndroidDirectorySync().sync(directory)
                }
            }
        }
        val storage = ProductStorage(
            context = context,
            databaseName = databaseName,
            artifactDirectoryName = artifactDirectoryName,
            directorySyncFactory = syncFactory,
        )
        val scheduler = HeavyWorkScheduler()
        val renderScratchBefore = renderScratchSnapshot(context.cacheDir)
        val firstProgress = mutableListOf<Pair<Int, Int>>()
        try {
            val failure = org.junit.Assert.assertThrows(IOException::class.java) {
                storage.importPdf(
                    source = ByteArrayInputStream(sourceBytes),
                    displayName = "sync-failure.pdf",
                    importSessionId = sessionId,
                    scheduler = scheduler,
                ) { completed, total -> firstProgress += completed to total }
            }

            assertEquals(FAILURE_MESSAGE, failure.message)
            assertTrue("Injected failure must occur once after the deterministic image exists", injected.get())
            assertEquals("The registered directory-sync fault must fire exactly once", 1, injectionCount.get())
            assertTrue("Directory sync trace must include observed callbacks", syncCallCount.get() > 0)
            val failedSessionState = importState(context, databaseName, sessionId.value)
            val failedPageIds = storage.importedPdfPageIds(sessionId)
            val failedPageIndices = pageIndices(context, databaseName, sessionId.value)
            assertTrue("The post-rename failure leaves the deterministic page object for retry", failedPageFile.isFile)
            assertValidPng(failedPageFile)
            val orphanSha = sha256(failedPageFile)
            val orphanSize = failedPageFile.length()
            val failedArtifactRecordCount = artifactRecordCount(context, databaseName, failedPageId.value)
            val failedStagingTemps = artifactStagingTempSnapshot(artifactsRoot)
            val failedRenderScratch = renderScratchSnapshot(context.cacheDir)
            val failedSourceSha = sha256(sourceFile)
            val failedSchedulerAdmission = scheduler.withAdmission(80L * 1024L * 1024L) {
                "scheduler admission released"
            }
            assertEquals(listOf(1 to 3), firstProgress)
            assertEquals(expectedPageIds.take(1), failedPageIds)
            assertEquals(listOf(0L), failedPageIndices)
            assertEquals("OPEN", failedSessionState.first)
            assertEquals(FAILURE_MESSAGE, failedSessionState.second)
            assertEquals(0L, failedArtifactRecordCount)
            assertTrue("Artifact staging temp files must be removed", failedStagingTemps.isEmpty())
            assertEquals(renderScratchBefore, failedRenderScratch)
            assertEquals(sourceSha, failedSourceSha)
            assertEquals("scheduler admission released", failedSchedulerAdmission)
            Log.i(
                TAG,
                "POST_FAILURE_STATE injectionCount=${injectionCount.get()} " +
                    "progress=${progressSnapshot(firstProgress)} sessionState=${failedSessionState.first} " +
                    "sessionError=${logValue(failedSessionState.second)} " +
                    "pageIds=${failedPageIds.joinToString(",") { it.value }} " +
                    "sourcePageIndices=${failedPageIndices.joinToString(",")} " +
                    "artifactRecordCount=$failedArtifactRecordCount orphanPath=${failedPageFile.absolutePath} " +
                    "orphanPngValid=true orphanBytes=$orphanSize orphanSha256=$orphanSha " +
                    "stagingTemps=${failedStagingTemps.joinToString(",")} " +
                    "renderScratch=${renderScratchSnapshotString(failedRenderScratch)} " +
                    "sourceSha256=$failedSourceSha scheduler=$failedSchedulerAdmission",
            )

            val resumedProgress = mutableListOf<Pair<Int, Int>>()
            val resumedPageIds = storage.resumeIncompletePdfImports(scheduler) { _, completed, total ->
                resumedProgress += completed to total
            }
            val recoveredSessionState = importState(context, databaseName, sessionId.value)
            val recoveredPageIds = storage.importedPdfPageIds(sessionId)
            val recoveredPageIndices = pageIndices(context, databaseName, sessionId.value)
            val committedRecord = requireNotNull(artifactRecord(context, databaseName, failedPageId.value)) {
                "Retry must register the previously published deterministic image"
            }
            val recoveredArtifactRecordCount = artifactRecordCount(context, databaseName, failedPageId.value)
            val recoveredStagingTemps = artifactStagingTempSnapshot(artifactsRoot)
            val recoveredRenderScratch = renderScratchSnapshot(context.cacheDir)
            val recoveredSourceSha = sha256(sourceFile)
            val recoveredSchedulerAdmission = scheduler.withAdmission(80L * 1024L * 1024L) {
                "scheduler remains reusable"
            }
            assertEquals(expectedPageIds, resumedPageIds)
            assertEquals(expectedPageIds, recoveredPageIds)
            assertEquals(listOf(0L, 1L, 2L), recoveredPageIndices)
            assertEquals(listOf(2 to 3, 3 to 3), resumedProgress)
            assertEquals("COMPLETED", recoveredSessionState.first)
            assertNull(recoveredSessionState.second)
            assertEquals("objects/${failedPageId.value.take(2)}/${failedPageId.value}.png", committedRecord.relativePath)
            assertEquals(orphanSize, committedRecord.sizeBytes)
            assertEquals(orphanSha, committedRecord.sha256)
            assertEquals("COMMITTED", committedRecord.state)
            assertEquals(1L, recoveredArtifactRecordCount)
            assertTrue("Artifact staging temp files must be removed", recoveredStagingTemps.isEmpty())
            assertEquals(renderScratchBefore, recoveredRenderScratch)
            assertEquals(sourceSha, recoveredSourceSha)
            assertEquals("scheduler remains reusable", recoveredSchedulerAdmission)
            Log.i(
                TAG,
                "POST_RESUME_STATE injectionCount=${injectionCount.get()} " +
                    "progress=${progressSnapshot(resumedProgress)} sessionState=${recoveredSessionState.first} " +
                    "sessionError=${logValue(recoveredSessionState.second)} " +
                    "pageIds=${recoveredPageIds.joinToString(",") { it.value }} " +
                    "sourcePageIndices=${recoveredPageIndices.joinToString(",")} " +
                    "artifactRecordCount=$recoveredArtifactRecordCount artifactPath=${committedRecord.relativePath} " +
                    "artifactState=${committedRecord.state} artifactBytes=${committedRecord.sizeBytes} " +
                    "artifactSha256=${committedRecord.sha256} reusedOrphanMatch=${committedRecord.sizeBytes == orphanSize && committedRecord.sha256 == orphanSha} " +
                    "stagingTemps=${recoveredStagingTemps.joinToString(",")} " +
                    "renderScratch=${renderScratchSnapshotString(recoveredRenderScratch)} " +
                    "sourceSha256=$recoveredSourceSha scheduler=$recoveredSchedulerAdmission",
            )
        } finally {
            storage.close()
            sourceFile.delete()
            artifactsRoot.deleteRecursively()
            context.deleteDatabase(databaseName)
            File(context.getDatabasePath(databaseName).path + "-wal").delete()
            File(context.getDatabasePath(databaseName).path + "-shm").delete()
        }
    }

    private data class ArtifactRecord(
        val relativePath: String,
        val sizeBytes: Long,
        val sha256: String,
        val state: String,
    )

    private fun threePagePdf(): ByteArray = ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage(PDRectangle.A4)) }
            document.save(output)
        }
        output.toByteArray()
    }

    private fun pageIndices(context: android.content.Context, databaseName: String, sessionId: String): List<Long> =
        readDatabase(context, databaseName) { database ->
            database.rawQuery(
                "SELECT source_page_index FROM Page WHERE import_session_id = ? ORDER BY source_page_index ASC",
                arrayOf(sessionId),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
        }

    private fun importState(
        context: android.content.Context,
        databaseName: String,
        sessionId: String,
    ): Pair<String, String?> = readDatabase(context, databaseName) { database ->
        database.rawQuery(
            "SELECT state, last_error FROM ImportSession WHERE import_session_id = ?",
            arrayOf(sessionId),
        ).use { cursor ->
            assertTrue("ImportSession row must exist", cursor.moveToFirst())
            cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getString(1)
        }
    }

    private fun artifactRecordCount(
        context: android.content.Context,
        databaseName: String,
        artifactId: String,
    ): Long = readDatabase(context, databaseName) { database ->
        database.rawQuery(
            "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?",
            arrayOf(artifactId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    private fun artifactRecord(
        context: android.content.Context,
        databaseName: String,
        artifactId: String,
    ): ArtifactRecord? = readDatabase(context, databaseName) { database ->
        database.rawQuery(
            "SELECT relative_storage_path, size_bytes, checksum, state FROM ArtifactRecord WHERE artifact_id = ?",
            arrayOf(artifactId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ArtifactRecord(
                relativePath = cursor.getString(0),
                sizeBytes = cursor.getLong(1),
                sha256 = cursor.getString(2),
                state = cursor.getString(3),
            )
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

    private fun assertValidPng(file: File) {
        val bytes = file.readBytes()
        assertTrue("PNG signature must be present", bytes.size > 8)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), bytes.copyOfRange(0, 8))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertTrue("Orphan PNG must decode to bounded dimensions", bounds.outWidth > 0 && bounds.outHeight > 0)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 3000)
    }

    private fun artifactStagingTempSnapshot(root: File): List<String> {
        val staging = File(root, "staging")
        return staging.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.map { it.name }.sorted()
    }

    private fun progressSnapshot(progress: List<Pair<Int, Int>>): String =
        progress.joinToString(",") { "${it.first}/${it.second}" }

    private fun renderScratchSnapshotString(snapshot: List<Pair<String, String>>): String =
        snapshot.joinToString(",") { "${it.first}:${it.second}" }.ifEmpty { "none" }

    private fun logValue(value: String?): String = value?.replace(" ", "_") ?: "null"

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
        const val TAG = "PdfImportPagePublicationSyncFailure"
        const val FAILURE_MESSAGE = "injected no-space directory sync after page image publish"
    }
}
