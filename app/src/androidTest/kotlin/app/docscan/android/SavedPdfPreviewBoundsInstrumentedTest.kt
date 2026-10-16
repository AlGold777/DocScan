package app.docscan.android

import android.Manifest
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.store.RecentDocumentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SavedPdfPreviewBoundsInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun unsupportedPageCountsShowUnavailableWithoutOpeningPreviewOrChangingState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saved-preview-bounds-$suffix.db"
        val artifactDirectory = "saved-preview-bounds-artifacts-$suffix"
        val candidateDirectory = "saved-preview-bounds-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val device = UiDevice.getInstance(instrumentation)
            var statusViewPresent = false
            scenario.onActivity { activity ->
                statusViewPresent = activity.window.decorView.findViewWithTag<TextView>("docscan-status") != null
            }
            assertTrue("ProductActivity initializes its status view", statusViewPresent)
            val recentBefore = storage.recentDocuments()
            val workspaceBefore = storage.workspaceProjection()

            listOf(0L, 501L).forEach { pageCount ->
                val displayName = "unsupported-$pageCount-$suffix.pdf"
                val snapshot = RecentDocumentSnapshot(
                    recentId = "unsupported-$pageCount-$suffix",
                    exportArtifactId = ExportArtifactId.new(),
                    documentUri = "content://test-provider/$suffix/$pageCount",
                    displayName = displayName,
                    pageCount = pageCount,
                    savedWallMillis = System.currentTimeMillis(),
                    ocrStatus = "UNKNOWN",
                )
                scenario.onActivity { activity -> activity.showSavedPdfPreview(snapshot) }
                var status = ""
                scenario.onActivity { activity ->
                    status = activity.window.decorView.findViewWithTag<TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                assertEquals(
                    "Unsupported page count $pageCount reports the existing unavailable state",
                    context.getString(R.string.recent_document_unavailable, "Unsupported saved PDF page count"),
                    status,
                )
                assertFalse("Unsupported page count $pageCount does not open a preview dialog", device.hasObject(By.text(displayName)))
                assertEquals("Recent metadata remains unchanged", recentBefore, storage.recentDocuments())
                assertEquals("Workspace state remains unchanged", workspaceBefore, storage.workspaceProjection())
            }
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            runCatching { storage.close() }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
        }
    }
}
