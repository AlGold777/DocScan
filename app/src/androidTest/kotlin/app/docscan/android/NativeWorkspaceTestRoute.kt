package app.docscan.android

import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario

/** Enter the owner-approved detailed-command menu instead of the superseded main scroller. */
internal fun nativeWorkspaceRoot(activity: ProductActivity): View {
    val dialog = ProductActivity::class.java.getDeclaredField("workspaceMenuDialog").apply { isAccessible = true }.get(activity) as? AlertDialog
    return dialog?.takeIf { it.isShowing }?.window?.decorView ?: activity.window.decorView
}

internal fun openWorkspaceMenuForTest(scenario: ActivityScenario<ProductActivity>) {
    scenario.onActivity { activity ->
        if (nativeWorkspaceRoot(activity) === activity.window.decorView) activity.window.decorView.findViewWithTag<View>("docscan-menu-button").performClick()
    }
    val end = SystemClock.uptimeMillis() + 10000
    while (true) {
        var ready = false
        scenario.onActivity { activity -> ready = nativeWorkspaceRoot(activity) !== activity.window.decorView && nativeWorkspaceRoot(activity).findViewWithTag<View>("docscan-workspace-menu")?.isLaidOut == true }
        if (ready) return
        check(SystemClock.uptimeMillis() < end) { "Detailed workspace menu did not become visible" }
        SystemClock.sleep(30)
    }
}

internal fun closeWorkspaceMenuForTest(scenario: ActivityScenario<ProductActivity>) {
    scenario.onActivity { activity ->
        val dialog = ProductActivity::class.java.getDeclaredField("workspaceMenuDialog").apply { isAccessible = true }.get(activity) as? AlertDialog
        dialog?.dismiss()
    }
}

internal fun scrollHomeRailToPositionForTest(scenario: ActivityScenario<ProductActivity>, position: Int) {
    scenario.onActivity { activity ->
        val rail = activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll")
            ?: throw AssertionError("Home thumbnail RecyclerView is missing")
        val count = rail.adapter?.itemCount ?: 0
        require(position in 0 until count) { "Home position $position is outside adapter count $count" }
        rail.scrollToPosition(position)
    }
    awaitNativeWorkspaceCondition("Home thumbnail at position $position is attached") {
        var attached = false
        scenario.onActivity { activity ->
            val rail = activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll")
            attached = rail?.findViewHolderForAdapterPosition(position)?.itemView?.isLaidOut == true
        }
        attached
    }
}

internal fun scrollWorkspaceListToPositionForTest(scenario: ActivityScenario<ProductActivity>, position: Int) {
    openWorkspaceMenuForTest(scenario)
    scenario.onActivity { activity ->
        val list = activity.workspacePagesListForTest
            ?: throw AssertionError("Detailed workspace Page RecyclerView is not initialized")
        val count = list.adapter?.itemCount ?: 0
        require(position in 0 until count) { "Workspace position $position is outside adapter count $count" }
        list.scrollToPosition(position)
    }
    awaitNativeWorkspaceCondition("Workspace row at position $position is attached") {
        var attached = false
        scenario.onActivity { activity ->
            val list = activity.workspacePagesListForTest
            attached = list?.findViewHolderForAdapterPosition(position)?.itemView?.isLaidOut == true
        }
        attached
    }
}

private fun awaitNativeWorkspaceCondition(description: String, predicate: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 10_000L
    while (SystemClock.uptimeMillis() < deadline) {
        if (predicate()) return
        SystemClock.sleep(30L)
    }
    throw AssertionError("Timed out waiting for $description")
}
