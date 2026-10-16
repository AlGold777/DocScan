package app.docscan.android

import android.Manifest
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.store.RecentDocumentSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Two-phase host-driven proof that a Try again ImportSession resumes after OS process death. */
@RunWith(AndroidJUnit4::class)
class TryAgainProcessDeathInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun processDeathResumeProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> checkpointAfterFirstTryAgainPage(context, application, fixtureId)
            "resume" -> resumeTryAgainAfterProcessDeath(context, application, fixtureId)
            "cleanup" -> cleanupFixture(context, fixtureId)
            else -> throw AssertionError("Unknown Try again process-death phase: $phase")
        }
    }

    private fun checkpointAfterFirstTryAgainPage(
        context: Context,
        application: DocScanApplication,
        fixtureId: String,
    ) {
        val names = fixtureNames(context, fixtureId)
        check(!context.getDatabasePath(names.databaseName).exists()) { "Fixture database already exists" }
        check(!names.artifactRoot.exists()) { "Fixture artifact root already exists" }
        check(!names.fixtureRoot.exists()) { "Fixture directory already exists" }
        names.fixtureRoot.mkdirs()

        val storage = ProductStorage(
            context,
            names.databaseName,
            names.artifactRoot.name,
            names.candidateRoot.name,
        )
        val priorOverride = application.productStorageOverrideForTest
        val priorProvider = application.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            application.productStorageProviderForTest = null
            application.productStorageOverrideForTest = storage
            addRecognizedPage(storage, "TRY AGAIN SOURCE ONE", Color.rgb(170, 36, 44))
            addRecognizedPage(storage, "TRY AGAIN SOURCE TWO", Color.rgb(34, 70, 180))
            storage.visiblePages().forEach { storage.setSelected(it.pageId, true) }

            val initialPages = storage.workspaceProjection().pages
            assertEquals(2, initialPages.size)
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
            val sourceSha = sha256(sourceBytes)
            assertTrue(storage.verifyExportOutput(pdf))

            val recent = RecentDocumentSnapshot(
                recentId = exportId.value,
                exportArtifactId = exportId,
                documentUri = "content://test-provider/try-again-death/$fixtureId",
                displayName = "try-again-process-death.pdf",
                pageCount = 2L,
                savedWallMillis = System.currentTimeMillis(),
                ocrStatus = "FULL",
            )
            val sessionsBefore = pageImportSessionIds(context, names.databaseName)
            val checkpointReached = CountDownLatch(1)
            val neverReleasedByTest = CountDownLatch(1)
            storage.pdfImportPageCommittedObserverForTest = { sessionId, completed, total ->
                if (completed == 1) {
                    check(total == 2) { "Expected a two-page saved PDF, got $total" }
                    val newSessions = pageImportSessionIds(context, names.databaseName) - sessionsBefore
                    check(newSessions == setOf(sessionId)) { "Unexpected retry sessions after page one: $newSessions" }
                    val visible = storage.workspaceProjection().pages
                    check(visible.size == initialPages.size + 1) {
                        "Expected original Pages plus one committed retry Page, found ${visible.size}"
                    }
                    val firstImported = visible.last()
                    val metadata = Properties().apply {
                        setProperty("exportArtifactId", exportId.value)
                        setProperty("importSessionId", sessionId)
                        setProperty("sourceSha256", sourceSha)
                        setProperty("initialPages", initialPages.joinToString(",") { "${it.pageId.value}|${it.imageVersionId.value}" })
                        setProperty("firstImportedPage", "${firstImported.pageId.value}|${firstImported.imageVersionId.value}")
                    }
                    FileOutputStream(names.checkpoint).use { stream ->
                        metadata.store(stream, "Try again committed-prefix checkpoint")
                        stream.fd.sync()
                    }
                    Log.i(TAG, "CHECKPOINT fixture=$fixtureId session=$sessionId committed_pages=1/2 source_sha256=$sourceSha")
                    checkpointReached.countDown()
                    check(neverReleasedByTest.await(10, TimeUnit.MINUTES)) {
                        "Host did not force-stop app at the committed-prefix checkpoint"
                    }
                }
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitCaptureReady(scenario)
            scenario.onActivity { it.tryAgainWithSavedPdf(recent) }
            assertTrue("Host-visible committed-prefix checkpoint was not reached", checkpointReached.await(90, TimeUnit.SECONDS))
            assertTrue("Test must remain blocked for the host process kill", neverReleasedByTest.await(10, TimeUnit.MINUTES))
            throw AssertionError("Process-death checkpoint returned without host kill")
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = priorOverride
            application.productStorageProviderForTest = priorProvider
            runCatching { storage.close() }
            // Intended process death prevents this block from running. The resume or cleanup phase
            // removes only the deterministic fixture names owned by this invocation.
            if (!names.checkpoint.exists()) cleanupFixture(context, fixtureId)
        }
    }

    private fun resumeTryAgainAfterProcessDeath(
        context: Context,
        application: DocScanApplication,
        fixtureId: String,
    ) {
        val names = fixtureNames(context, fixtureId)
        val metadata = Properties().apply { names.checkpoint.inputStream().use(::load) }
        val exportId = ExportArtifactId.parse(metadata.getProperty("exportArtifactId"))
        val importId = ImportSessionId.parse(metadata.getProperty("importSessionId"))
        val sourceSha = metadata.getProperty("sourceSha256")
        val initialPagePairs = metadata.getProperty("initialPages").split(',').map(::parseIdentity)
        val firstImportedPair = parseIdentity(metadata.getProperty("firstImportedPage"))
        val storage = ProductStorage(
            context,
            names.databaseName,
            names.artifactRoot.name,
            names.candidateRoot.name,
        )
        val priorOverride = application.productStorageOverrideForTest
        val priorProvider = application.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            application.productStorageProviderForTest = null
            application.productStorageOverrideForTest = storage
            assertFalse("The durable open retry session must survive process death", storage.startupIntegrityBlocked)
            val before = storage.workspaceProjection().pages
            assertEquals("Only the recorded committed prefix exists before startup resume", initialPagePairs.size + 1, before.size)
            initialPagePairs.forEachIndexed { index, pair ->
                assertEquals(pair.first, before[index].pageId.value)
                assertEquals(pair.second, before[index].imageVersionId.value)
            }
            assertEquals(firstImportedPair.first, before.last().pageId.value)
            assertEquals(firstImportedPair.second, before.last().imageVersionId.value)

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitPageCount(scenario, storage, initialPagePairs.size + 2)
            val after = storage.workspaceProjection().pages
            assertEquals("Startup recovery must finish the same retry without a duplicate prefix", initialPagePairs.size + 2, after.size)
            initialPagePairs.forEachIndexed { index, pair ->
                assertEquals(pair.first, after[index].pageId.value)
                assertEquals(pair.second, after[index].imageVersionId.value)
            }
            val importedIds = storage.importedPdfPageIds(importId)
            assertEquals(2, importedIds.size)
            val importedPages = importedIds.map { id -> after.single { it.pageId == id } }
            assertEquals(firstImportedPair.first, importedPages.first().pageId.value)
            assertEquals(firstImportedPair.second, importedPages.first().imageVersionId.value)
            assertEquals(2, importedPages.map { it.pageId }.distinct().size)
            assertEquals(2, importedPages.map { it.imageVersionId }.distinct().size)
            assertTrue("Recovered Pages remain selected", importedPages.all { it.selected })
            assertEquals(importedIds, after.takeLast(2).map { it.pageId })

            val pdf = storage.committedExportOutputs(exportId).single { it.mimeType == "application/pdf" }
            val sourceFile = storage.exportOutputFile(pdf)
            assertEquals("Source digest is unchanged after retry and resume", sourceSha, sha256(sourceFile.readBytes()))
            assertTrue("Original committed export remains verified", storage.verifyExportOutput(pdf))
            assertTrue("Completed session is not offered as incomplete work", storage.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
            assertEquals("Repeated recovery must not duplicate imported Pages", initialPagePairs.size + 2, storage.workspaceProjection().pages.size)
            Log.i(TAG, "RESUME_PASS fixture=$fixtureId import_session=$importId imported_pages=${importedIds.joinToString(",")} source_sha256=$sourceSha")
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = priorOverride
            application.productStorageProviderForTest = priorProvider
            storage.close()
            cleanupFixture(context, fixtureId)
        }
    }

    private fun addRecognizedPage(storage: ProductStorage, label: String, color: Int) {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(label, 14f, 90f, Paint().apply { this.color = color; textSize = 22f })
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
            output.toByteArray()
        }
        bitmap.recycle()
        storage.importImage(bytes.inputStream(), "image/jpeg", "$label.jpg")
    }

    private fun awaitCaptureReady(scenario: ActivityScenario<ProductActivity>) {
        await("ProductActivity finishes startup before Try again") {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.window.decorView.findViewWithTag<android.view.View>("docscan-capture-button")?.isEnabled == true
            }
            ready
        }
    }

    private fun awaitPageCount(scenario: ActivityScenario<ProductActivity>, storage: ProductStorage, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 90_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (storage.workspaceProjection().pages.size == expected) return
            SystemClock.sleep(100L)
        }
        var status = "<unavailable>"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            scenario.onActivity { activity ->
                status = activity.window.decorView.findViewWithTag<TextView>("docscan-status")?.text?.toString() ?: "<missing>"
            }
        }
        throw AssertionError("Startup did not resume Try again to $expected pages; found ${storage.workspaceProjection().pages.size}; status='$status'")
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun pageImportSessionIds(context: Context, databaseName: String): Set<String> =
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT DISTINCT import_session_id FROM Page WHERE import_session_id IS NOT NULL", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun parseIdentity(value: String): Pair<String, String> = value.split('|').also {
        require(it.size == 2)
    }.let { it[0] to it[1] }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fixtureNames(context: Context, fixtureId: String): FixtureNames {
        require(Regex("[0-9a-fA-F-]{36}").matches(fixtureId))
        val artifactsName = "try-again-death-artifacts-$fixtureId"
        val candidatesName = "try-again-death-candidates-$fixtureId"
        val fixtureName = "try-again-death-fixture-$fixtureId"
        return FixtureNames(
            databaseName = "try-again-death-$fixtureId.db",
            artifactRoot = File(context.noBackupFilesDir, artifactsName),
            candidateRoot = File(context.noBackupFilesDir, candidatesName),
            fixtureRoot = File(context.filesDir, fixtureName),
            checkpoint = File(context.filesDir, "$fixtureName/checkpoint.properties"),
        )
    }

    private fun cleanupFixture(context: Context, fixtureId: String) {
        val names = fixtureNames(context, fixtureId)
        context.deleteDatabase(names.databaseName)
        names.artifactRoot.deleteRecursively()
        names.candidateRoot.deleteRecursively()
        names.fixtureRoot.deleteRecursively()
    }

    private data class FixtureNames(
        val databaseName: String,
        val artifactRoot: File,
        val candidateRoot: File,
        val fixtureRoot: File,
        val checkpoint: File,
    )

    private companion object {
        const val TAG = "TryAgainProcessDeath"
    }
}
