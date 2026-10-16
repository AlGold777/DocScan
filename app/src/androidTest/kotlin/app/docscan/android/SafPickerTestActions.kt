package app.docscan.android

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.regex.Pattern

/** Shared real-DocumentsUI navigation for SAF integration tests. */
internal object SafPickerTestActions {
    private const val DOCUMENTS_UI = "com.google.android.documentsui"
    private const val APP_PACKAGE = "app.docscan.scanner"
    private const val BREADCRUMB = "breadcrumb_text"
    private const val TIMEOUT_MILLIS = 15_000L

    fun chooseTreeDirectory(
        device: UiDevice,
        scenario: ActivityScenario<ProductActivity>,
        pathSegments: List<String>,
    ) {
        require(pathSegments.isNotEmpty())
        assertTrue(
            "DocumentsUI must launch",
            device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS),
        )
        val initialBreadcrumb = device.wait(
            Until.findObject(By.res(DOCUMENTS_UI, BREADCRUMB)),
            TIMEOUT_MILLIS,
        )
        assertNotNull("DocumentsUI must identify its current folder", initialBreadcrumb)

        openPrimaryDirectory(device, pathSegments)

        val useFolder = By.text(Pattern.compile("(?i)^use this folder$"))
        val allow = By.text(Pattern.compile("(?i)^allow$"))
        clickFresh(
            device,
            useFolder,
            "Use this folder action",
            completed = { device.hasObject(allow) || device.hasObject(By.pkg(APP_PACKAGE)) },
        )
        if (device.wait(Until.hasObject(allow), 3_000L)) {
            clickFresh(
                device,
                allow,
                "Allow folder access",
                timeoutMs = 3_000L,
                completed = { device.hasObject(By.pkg(APP_PACKAGE)) },
            )
        }

        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var observedState = "unknown"
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity -> observedState = activity.lifecycle.currentState.name }
            if (observedState == Lifecycle.State.RESUMED.name) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "Picker must return the same ProductActivity to RESUMED; " +
                "observedLifecycle=$observedState, currentPackage=${device.currentPackageName}",
        )
    }

    /** Tree picker may start inside the default or a saved folder; descend from its primary root. */
    fun openPrimaryDirectory(device: UiDevice, pathSegments: List<String>) {
        require(pathSegments.isNotEmpty())
        assertTrue("DocumentsUI must launch", device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
        val initial = currentBreadcrumbs(device)
        assertTrue("Primary-storage breadcrumb must be present", initial.isNotEmpty())
        if (initial.size > 1) {
            clickFresh(device, By.res(DOCUMENTS_UI, BREADCRUMB).text(initial.first()), "Primary storage root",
                completed = { currentBreadcrumbs(device).size == 1 })
            assertTrue("Primary root must be reached before selecting a sibling directory", device.wait(
                Until.hasObject(By.res(DOCUMENTS_UI, "header_title").textStartsWith("Files on ")), TIMEOUT_MILLIS,
            ))
        }
        pathSegments.forEach { segment ->
            val breadcrumbs = currentBreadcrumbs(device)
            if (segment !in breadcrumbs) {
                // AOSP DocumentsUI exposes title text as a non-clickable child of item_root.
                clickFresh(
                    device,
                    By.text(segment),
                    "Folder '$segment'",
                    completed = { segment in currentBreadcrumbs(device) },
                ) // The tap is dispatched to its clickable row.
                assertTrue(
                    "The current-folder breadcrumb must become '$segment'",
                    device.wait(
                        Until.hasObject(By.res(DOCUMENTS_UI, BREADCRUMB).text(segment)),
                        TIMEOUT_MILLIS,
                    ),
                )
            }
        }

    }

    /** Commands are deliberately separate: UiAutomation does not interpret shell operators. */
    fun createDefaultFolderFixture(): Boolean {
        val existing = shellOutput("ls -ld /sdcard/Docscan").trim()
        if (existing.isNotEmpty()) {
            check(existing.startsWith("d")) { "Existing /Docscan is not a directory: $existing" }
            return false
        }
        shellOutput("mkdir /sdcard/Docscan")
        check(shellOutput("ls -ld /sdcard/Docscan").trim().startsWith("d")) { "Could not create /Docscan test fixture" }
        return true
    }

    fun removeDefaultFolderFixture(created: Boolean) {
        if (!created) return
        shellOutput("rmdir /sdcard/Docscan")
        check(shellOutput("ls -d /sdcard/Docscan").isBlank()) { "Test-created /Docscan was not removed; preserve unexpected contents" }
    }

    private fun shellOutput(command: String): String =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }

    fun clickFresh(
        device: UiDevice,
        selector: BySelector,
        label: String,
        timeoutMs: Long = TIMEOUT_MILLIS,
        completed: () -> Boolean = { false },
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStale: StaleObjectException? = null
        var foundCount = 0
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (completed()) return
            } catch (stale: StaleObjectException) {
                lastStale = stale
                SystemClock.sleep(50)
                continue
            }
            val target = try {
                device.findObject(selector)
            } catch (stale: StaleObjectException) {
                lastStale = stale
                null
            }
            if (target != null) {
                foundCount += 1
                try {
                    target.click()
                    return
                } catch (stale: StaleObjectException) {
                    lastStale = stale
                }
            }
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "$label did not complete; found=$foundCount stale=${lastStale != null}",
            lastStale,
        )
    }

    private fun currentBreadcrumbs(device: UiDevice): List<String> {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var lastStale: StaleObjectException? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                return device.findObjects(By.res(DOCUMENTS_UI, BREADCRUMB))
                    .mapNotNull { it.text?.toString() }
            } catch (stale: StaleObjectException) {
                lastStale = stale
                SystemClock.sleep(50)
            }
        }
        throw AssertionError("DocumentsUI breadcrumb snapshot remained stale", lastStale)
    }
}
