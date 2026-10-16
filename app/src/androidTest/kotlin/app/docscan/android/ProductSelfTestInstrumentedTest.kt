package app.docscan.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductSelfTestInstrumentedTest {
    @Test
    fun injectedFailureIsPersistedAndShareableWithoutChangingRealWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realStorage = (context.applicationContext as DocScanApplication).productStorage
        val before = realStorage.workspaceProjection()
        val result = ProductSelfTestRunner(context, realStorage) { step ->
            if (step == "SANDBOX_CAPTURE_AND_OCR") throw IllegalStateException("fixture failure <redacted>")
        }.run()

        assertFalse(result.passed)
        assertTrue(result.reportFile.isFile)
        assertTrue(result.reportFile.canonicalPath.startsWith(
            context.filesDir.resolve("docscan-self-test/reports").canonicalPath + "/",
        ))
        val report = JSONObject(result.reportJson)
        assertEquals("SUPPLEMENTAL_SELF_TEST", report.getString("reportType"))
        assertEquals("FAIL", report.getString("reportStatus"))
        assertEquals("SANDBOX_CAPTURE_AND_OCR", report.getJSONArray("checks").getJSONObject(0).getString("id"))
        assertTrue(report.getJSONArray("checks").getJSONObject(0).getString("diagnostic").contains("fixture failure"))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.selftest.files", result.reportFile)
        val shared = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(result.reportJson, shared)
        assertEquals(before, realStorage.workspaceProjection())
    }

    @Test
    fun reportRejectsModeConfusionAndInvalidCheckStatus() {
        val invalid = ProductSelfTestReport(
            runId = "run",
            packageName = "app.docscan.scanner",
            appVersion = "test",
            versionCode = 1L,
            startedAtUtc = "2026-01-01T00:00:00Z",
            completedAtUtc = "2026-01-01T00:00:01Z",
            reportStatus = "PHYSICAL_GATE_PASS",
            androidRelease = "test",
            sdkInt = 35,
            deviceModel = "test",
            buildFingerprint = "test",
            checks = emptyList(),
        )
        assertTrue(runCatching { invalid.toJson() }.isFailure)
        val invalidCheck = invalid.copy(
            reportStatus = "PASS",
            checks = listOf(SelfTestCheck("OCR", "SKIPPED", 1L)),
        )
        assertTrue(runCatching { invalidCheck.toJson() }.isFailure)
    }
}
