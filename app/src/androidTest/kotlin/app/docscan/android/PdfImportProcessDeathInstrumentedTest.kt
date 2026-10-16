package app.docscan.android

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.docscan.core.model.ImportSessionId
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly driven in two phases by execution/tools/run_pdf_import_process_death.sh. */
@RunWith(AndroidJUnit4::class)
class PdfImportProcessDeathInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun processDeathResumeProbe() {
        val arguments = InstrumentationRegistry.getArguments()
        val fixtureId = arguments.getString("fixtureId") ?: return
        val phase = arguments.getString("phase") ?: return
        val pageCount = arguments.getString("pageCount")?.toIntOrNull() ?: 2
        val pauseAfterPage = arguments.getString("pauseAfterPage")?.toIntOrNull() ?: 1
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> pauseAfterCommittedPrefix(context, application, fixtureId, pageCount, pauseAfterPage)
            "resume" -> resumeAfterProcessDeath(context, application, fixtureId, pageCount, pauseAfterPage)
            else -> throw AssertionError("Unknown process-death probe phase: $phase")
        }
    }

    private fun pauseAfterCommittedPrefix(
        context: android.content.Context,
        application: DocScanApplication,
        fixtureId: String,
        pageCount: Int,
        pauseAfterPage: Int,
    ) {
        val names = fixtureNames(context, fixtureId)
        check(!context.getDatabasePath(names.database).exists()) { "Fixture database already exists" }
        check(!names.artifacts.exists()) { "Fixture artifact root already exists" }
        check(!names.fixture.exists()) { "Fixture directory already exists" }
        names.fixture.mkdirs()
        val sourcePdf = names.fixture.resolve("source.pdf")
        createSourcePdf(sourcePdf, pageCount)
        val sourceHash = sha256(sourcePdf)
        val previousOverride = application.productStorageOverrideForTest
        val previousProvider = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        try {
            val importId = ImportSessionId.parse(fixtureId)
            storage.importPdf(
                source = sourcePdf.inputStream(),
                displayName = "process-death-fixture.pdf",
                importSessionId = importId,
                scheduler = application.heavyWorkScheduler,
            ) { completed, total ->
                if (completed == pauseAfterPage) {
                    check(total == pageCount)
                    val committedPages = storage.workspaceProjection().pages
                    check(committedPages.size == pauseAfterPage)
                    val checkpoint = buildList {
                        add(pageCount.toString())
                        add(pauseAfterPage.toString())
                        add(sourceHash)
                        committedPages.forEach { page ->
                            add("${page.pageId.value}|${page.imageVersionId.value}")
                        }
                    }.joinToString("\n") + "\n"
                    val checkpointFile = names.fixture.resolve("checkpoint.txt")
                    FileOutputStream(checkpointFile).use { stream ->
                        stream.write(checkpoint.toByteArray())
                        stream.fd.sync()
                    }
                    Log.i(TAG, "CHECKPOINT fixture=$fixtureId committed_pages=$pauseAfterPage/$pageCount source_sha256=$sourceHash")
                    check(CountDownLatch(1).await(10, TimeUnit.MINUTES)) {
                        "Host did not terminate the app at the first-page checkpoint"
                    }
                }
            }
            throw AssertionError("Process-death checkpoint returned without a host kill")
        } finally {
            application.productStorageProviderForTest = previousProvider
            application.productStorageOverrideForTest = previousOverride
            // On the intended path Android kills this process at the checkpoint. This runs only
            // if the harness failed to kill it, allowing the harness to report a normal failure.
            runCatching { storage.close() }
            context.deleteDatabase(names.database)
            names.artifacts.deleteRecursively()
            names.fixture.deleteRecursively()
        }
    }

    private fun resumeAfterProcessDeath(
        context: android.content.Context,
        application: DocScanApplication,
        fixtureId: String,
        pageCount: Int,
        pauseAfterPage: Int,
    ) {
        val names = fixtureNames(context, fixtureId)
        val fixtureLines = names.fixture.resolve("checkpoint.txt").readLines()
        require(fixtureLines.size == 3 + pauseAfterPage)
        assertEquals(pageCount.toString(), fixtureLines[0])
        assertEquals(pauseAfterPage.toString(), fixtureLines[1])
        val sourceHash = fixtureLines[2]
        val expectedCommitted = fixtureLines.drop(3).map { line ->
            line.split('|').also { assertEquals(2, it.size) }
        }
        val previousOverride = application.productStorageOverrideForTest
        val previousProvider = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        var scenario: ActivityScenario<ProductActivity>? = null
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        try {
            assertFalse("Session fixture must exist after process death", storage.startupIntegrityBlocked)
            val before = storage.workspaceProjection().pages
            assertEquals("Only the recorded source prefix was committed before the kill", pauseAfterPage, before.size)
            before.forEachIndexed { index, page ->
                assertEquals(expectedCommitted[index][0], page.pageId.value)
                assertEquals(expectedCommitted[index][1], page.imageVersionId.value)
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitPageCount(scenario, storage, pageCount)

            val after = storage.workspaceProjection().pages
            val importedIds = storage.importedPdfPageIds(ImportSessionId.parse(fixtureId))
            assertEquals(pageCount, after.size)
            assertEquals(pageCount, after.map { it.pageId }.distinct().size)
            assertEquals(pageCount, after.map { it.imageVersionId }.distinct().size)
            assertEquals(importedIds, after.map { it.pageId })
            expectedCommitted.forEachIndexed { index, identity ->
                assertEquals(identity[0], after[index].pageId.value)
                assertEquals(identity[1], after[index].imageVersionId.value)
            }

            val sourceArtifacts = names.artifacts.walkTopDown().filter { it.isFile && it.extension == "pdf" }.toList()
            assertEquals("One private source PDF remains in the isolated artifact root", 1, sourceArtifacts.size)
            assertEquals(sourceHash, sha256(sourceArtifacts.single()))
            assertTrue(names.fixture.resolve("source.pdf").isFile)
            assertEquals(sourceHash, sha256(names.fixture.resolve("source.pdf")))

            assertTrue(
                "Completed import is not returned as new work on a repeated resume",
                storage.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty(),
            )
            assertEquals("Repeated resume must not duplicate Pages", pageCount, storage.workspaceProjection().pages.size)
            Log.i(TAG, "RESUME_PASS fixture=$fixtureId page_ids=${importedIds.joinToString(",")} source_sha256=$sourceHash")
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = previousProvider
            application.productStorageOverrideForTest = previousOverride
            storage.close()
            context.deleteDatabase(names.database)
            names.artifacts.deleteRecursively()
            names.fixture.deleteRecursively()
        }
    }

    private fun awaitPageCount(
        scenario: ActivityScenario<ProductActivity>,
        storage: ProductStorage,
        expected: Int,
    ) {
        val deadline = SystemClock.uptimeMillis() + 60_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (storage.workspaceProjection().pages.size == expected) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("ProductActivity startup did not resume PDF import to $expected pages")
    }

    private fun fixtureNames(
        context: android.content.Context,
        fixtureId: String,
    ): FixtureNames {
        val database = "pdf-death-$fixtureId.db"
        val artifactsName = "pdf-death-artifacts-$fixtureId"
        val fixtureName = "pdf-death-fixture-$fixtureId"
        val names = FixtureNames(
            database = database,
            artifacts = File(context.noBackupFilesDir, artifactsName),
            fixture = File(context.filesDir, fixtureName),
        )
        return names
    }

    private fun createSourcePdf(file: File, pageCount: Int) {
        PDDocument().use { document ->
            repeat(pageCount) {
                document.addPage(PDPage(PDRectangle.A4))
            }
            document.save(file)
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class FixtureNames(val database: String, val artifacts: File, val fixture: File)

    private companion object {
        const val TAG = "PdfImportProcessDeath"
    }
}
