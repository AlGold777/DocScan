package app.docscan.android

import android.Manifest
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceMenuButtonInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun clickingWorkspaceMenuButtonOpensWorkspaceMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val previousStorage = app.productStorageOverrideForTest
        val previousProvider = app.productStorageProviderForTest
        val suffix = UUID.randomUUID().toString()
        val databaseName = "workspace-menu-button-$suffix.db"
        val artifactDirectoryName = "workspace-menu-button-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectoryName)
        app.productStorageOverrideForTest = storage
        app.productStorageProviderForTest = null
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        val device = UiDevice.getInstance(instrumentation)
        try {
            var captureReady = false
            awaitCondition("ProductActivity capture initialization") {
                scenario.onActivity { activity ->
                    val button = activity.window.decorView.findViewWithTag<View>("docscan-capture-button")
                    captureReady = button?.isEnabled == true
                }
                captureReady
            }

            var menuButton: View? = null
            scenario.onActivity { activity ->
                menuButton = activity.window.decorView.findViewWithTag("docscan-workspace-menu-button")
            }
            assertTrue("Workspace menu button is present and enabled", menuButton?.isEnabled == true)
            scenario.onActivity {
                checkNotNull(menuButton).performClick()
            }

            val title = context.getString(R.string.workspace_menu)
            assertTrue(
                "A real click on the workspace menu button must open its dialog",
                device.wait(Until.hasObject(By.text(title)), 5_000L),
            )
            device.pressBack()
        } finally {
            scenario.close()
            app.productStorageOverrideForTest = previousStorage
            app.productStorageProviderForTest = previousProvider
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectoryName).deleteRecursively()
        }
    }

    private fun awaitCondition(description: String, timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
