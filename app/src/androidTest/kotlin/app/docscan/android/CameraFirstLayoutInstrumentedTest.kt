package app.docscan.android

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.RecognitionCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraFirstLayoutInstrumentedTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun fixedBottomRailInsetViewerAndBothNavigationMethodsPreserveSelection() = fixture { scenario, storage, device ->
        val pages = storage.workspaceProjection().pages
        waitUntil { var ready = false; scenario.onActivity { ready = it.window.decorView.findViewWithTag<View>("docscan-home-page:" + pages[0].pageId.value)?.isLaidOut == true }; ready }
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val rail = bounds(decor.findViewWithTag("docscan-home-thumbnail-scroll"))
            val actions = bounds(decor.findViewWithTag("docscan-home-actions"))
            val camera = bounds(decor.findViewWithTag("docscan-camera-area"))
            val shutter = bounds(decor.findViewWithTag("docscan-capture-button"))
            val save = bounds(decor.findViewWithTag("docscan-save-button"))
            val density = activity.resources.displayMetrics.density
            assertTrue("Compact lower block <=175dp", rail.height() + actions.height() <= 175 * density)
            assertTrue(shutter.bottom <= rail.top)
            assertTrue("Shutter stays centered when side actions are hidden", kotlin.math.abs(shutter.centerX() - camera.centerX()) <= density)
            assertTrue(save.centerX() > camera.centerX())
            assertTrue(save.top < shutter.top)
            assertTrue(rail.bottom <= actions.top)
            decor.findViewWithTag<View>("docscan-home-page:" + pages[0].pageId.value).performClick()
        }
        waitUntil { topHost(scenario)?.isLaidOut == true }
        var host: View? = null
        scenario.onActivity { activity -> host = host(activity); assertHost(host!!, activity.resources.displayMetrics.density, true) }
        screenshot(device, "viewer")
        scenario.onActivity {
            host!!.findViewWithTag<View>(ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + pages[2].pageId.value).performClick()
        }
        waitUntil { storage.workspaceProjection().activePageId == pages[2].pageId }
        fun renderedBounds(): Rect {
            var result = Rect()
            waitUntil {
                var ready = false
                scenario.onActivity {
                    val image = host!!.findViewWithTag<android.widget.ImageView>(ProductActivity.VIEWER_MAIN_IMAGE_TAG)
                    ready = image.drawable != null && image.isLaidOut
                    if (ready) result = bounds(image)
                }
                ready
            }
            return result
        }
        fun swipeTo(delta: Int, expected: PageId) {
            val r = renderedBounds()
            android.util.Log.i("DocScanNativeGestureTest", "before=${storage.workspaceProjection().activePageId} delta=$delta bounds=$r expected=$expected")
            val direction = if (delta > 0) 1 else -1
            assertTrue(device.swipe(r.centerX() + direction*r.width()/3, r.centerY(), r.centerX()-direction*r.width()/3, r.centerY(), 18))
            waitUntil { storage.workspaceProjection().activePageId == expected }
        }
        swipeTo(-1, pages[1].pageId)
        swipeTo(1, pages[2].pageId)
        swipeTo(1, pages[2].pageId)
        assertEquals("Last page must not wrap", pages[2].pageId, storage.workspaceProjection().activePageId)
        val imageBounds = renderedBounds()
        device.swipe(imageBounds.centerX(), imageBounds.centerY() - imageBounds.height()/4, imageBounds.centerX(), imageBounds.centerY()+imageBounds.height()/4, 18)
        SystemClock.sleep(200)
        assertEquals("Vertical gesture never changes Page", pages[2].pageId, storage.workspaceProjection().activePageId)
        assertEquals("Navigation never changes selection", pages.map { it.selected }, storage.workspaceProjection().pages.map { it.selected })
        device.click(5, imageBounds.centerY())
        waitUntil { hostCount(scenario) == 0 }
    }

    @Test fun compactCaptionsFitInBothLocalesAndSelectionStates() = fixture { scenario, storage, device ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        var localeFailure: Throwable? = null
        try {
            for (language in listOf("en", "ru")) {
                switchLocaleAndAwaitActivity(scenario, androidx.core.os.LocaleListCompat.forLanguageTags(language))
                fun awaitRenderedSelection(allSelected: Boolean) {
                    waitUntil {
                        var ready = false
                        scenario.onActivity { activity ->
                            val button = activity.window.decorView.findViewWithTag<android.widget.Button>("docscan-selection-button")
                            val expected = activity.getString(if (allSelected) R.string.deselect_all_pages else R.string.select_all_pages)
                            val transformed = button.transformationMethod?.getTransformation(button.text, button) ?: button.text
                            ready = button.isEnabled && button.contentDescription == expected &&
                                !button.isLayoutRequested && button.layout?.text?.toString() == transformed.toString()
                        }
                        ready
                    }
                }
                fun assertCaptions() {
                    scenario.onActivity { activity ->
                        val actions = activity.window.decorView.findViewWithTag<ViewGroup>("docscan-home-actions")
                        val density = activity.resources.displayMetrics.density
                        assertTrue("Caption fix preserves compact bar", actions.height <= 48*density)
                        for (index in 0 until actions.childCount) {
                            val button = actions.getChildAt(index) as android.widget.Button
                            assertTextFits(button, "$language Home")
                            assertTrue("Full accessible name retained", !button.contentDescription.isNullOrBlank())
                            assertTrue("48dp touch target", button.width >= 48*density && button.height >= 48*density)
                        }
                        val selection = activity.window.decorView.findViewWithTag<android.widget.Button>("docscan-selection-button")
                        val all = storage.workspaceProjection().pages.all { it.selected }
                        assertEquals(activity.getString(if (all) R.string.deselect_all_pages else R.string.select_all_pages), selection.contentDescription)
                    }
                }
                awaitRenderedSelection(storage.workspaceProjection().pages.all { it.selected })
                assertCaptions()
                for (selected in listOf(true, false)) {
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("docscan-selection-button").performClick() }
                    waitUntil { storage.workspaceProjection().pages.all { it.selected == selected } }
                    awaitRenderedSelection(selected)
                    assertCaptions()
                }
                screenshot(device, "compact-captions-$language")
                val first = storage.workspaceProjection().pages.first().pageId
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("docscan-home-page:" + first.value).performClick() }
                waitUntil { topHost(scenario)?.findViewWithTag<View>(InsetDialogHost.RAIL_TAG)?.isLaidOut == true }
                scenario.onActivity { activity ->
                    assertFooterCaptions(host(activity)!!, activity.resources.displayMetrics.density, "$language workspace")
                    val checkbox = host(activity)!!.findViewWithTag<CheckBox>(ProductActivity.VIEWER_PAGE_SELECTION_TAG_PREFIX + first.value)
                    assertEquals(activity.getString(R.string.include_page_in_save, 1), checkbox.contentDescription)
                    assertEquals("Selection has no clipped visual sentence", "", checkbox.text.toString())
                }
                for (selected in listOf(true, false)) {
                    scenario.onActivity { host(it)!!.findViewWithTag<CheckBox>(ProductActivity.VIEWER_PAGE_SELECTION_TAG_PREFIX + first.value).performClick() }
                    waitUntil { storage.workspaceProjection().pages.first().selected == selected }
                    scenario.onActivity { assertEquals(selected, host(it)!!.findViewWithTag<CheckBox>(ProductActivity.VIEWER_PAGE_SELECTION_TAG_PREFIX + first.value).isChecked) }
                }
                screenshot(device, "compact-viewer-$language")
                device.pressBack()
                waitUntil { hostCount(scenario) == 0 }
            }
        } catch (failure: Throwable) {
            localeFailure = failure
            throw failure
        } finally {
            restoreLocaleAndRetainFailure(scenario, original, localeFailure)
        }
    }

    @Test fun menuAndNestedEditorsUseSameInsetsAndRestoreTheirParent() = fixture { scenario, storage, device ->
        waitUntil { var ready = false; scenario.onActivity { ready = it.window.decorView.findViewWithTag<View>("docscan-menu-button")?.isLaidOut == true }; ready }
        scenario.onActivity { it.window.decorView.findViewWithTag<View>("docscan-menu-button").performClick() }
        waitUntil { topHost(scenario)?.isLaidOut == true }
        scrollWorkspaceListToPositionForTest(scenario, 0)
        scenario.onActivity { activity ->
            assertHost(host(activity)!!, activity.resources.displayMetrics.density, false)
            listOf("docscan-version-label", "docscan-destination-button", "docscan-recover-exports-button", "docscan-resume-exports-button", "docscan-clear-workspace-button").forEach { tag ->
                val control = host(activity)!!.findViewWithTag<View>(tag)
                assertNotNull("Moved command remains available: $tag", control)
                assertTrue(control.isShown)
                assertTrue(control.height >= 48 * activity.resources.displayMetrics.density)
            }
            val button = host(activity)!!.findViewWithTag<View>(ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + storage.workspaceProjection().pages[0].pageId.value)
            assertNotNull("Original image commands remain available", button)
            button.performClick()
        }
        waitUntil { hostCount(scenario) == 2 && topHost(scenario)?.isLaidOut == true }
        scenario.onActivity { activity -> assertHost(host(activity)!!, activity.resources.displayMetrics.density, false) }
        screenshot(device, "image-editor")
        device.click(5, 600)
        SystemClock.sleep(150)
        assertEquals("Dirty-safe editor ignores margin touches", 2, hostCount(scenario))
        device.pressBack()
        waitUntil { hostCount(scenario) == 1 }
        scenario.onActivity { activity -> assertHost(host(activity)!!, activity.resources.displayMetrics.density, false) }
        device.pressBack()
        waitUntil { hostCount(scenario) == 0 }
        scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("docscan-home-thumbnail-strip").isShown) }
        screenshot(device, "home")
    }

    @Test fun savedPdfSupportsBottomThumbnailsAndSwipeWithoutChangingSource() = fixture { scenario, storage, device ->
        storage.visiblePages().forEach { storage.setSelected(it.pageId, true) }
        val frozen = storage.freezeSelectedPagesForSave()
        val output = storage.composeFrozenSaveOutputSet(frozen, PdfOutputSettings(595f, 842f, .85f, 150), companionJpegQuality = 85)
        val exportId = storage.commitLocalExportOutputSet(frozen, output)
        val pdf = storage.committedExportOutputs(exportId).single { it.mimeType == "application/pdf" }
        val original = storage.exportOutputFile(pdf).readBytes()
        val recent = app.docscan.core.store.RecentDocumentSnapshot(recentId = exportId.value, exportArtifactId = exportId, documentUri = "content://test-provider/native-ui", displayName = "native-three-pages.pdf", pageCount = 3L, savedWallMillis = System.currentTimeMillis(), ocrStatus = "FULL")
        val workspace = storage.workspaceProjection()
        scenario.onActivity { it.showSavedPdfPreview(recent) }
        waitUntil { topHost(scenario)?.findViewWithTag<View>("docscan-pdf-thumbnail:2")?.isLaidOut == true }
        scenario.onActivity { activity ->
            val host = host(activity)!!; assertHost(host, activity.resources.displayMetrics.density, true)
            host.findViewWithTag<View>("docscan-pdf-thumbnail:2").performClick()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        waitUntil {
            var ready = false
            scenario.onActivity { activity -> ready = host(activity)?.findViewWithTag<android.widget.ImageView>("docscan-pdf-thumbnail:0")?.drawable is android.graphics.drawable.BitmapDrawable }
            ready
        }
        scenario.onActivity { activity ->
            val bitmap = (host(activity)!!.findViewWithTag<android.widget.ImageView>("docscan-pdf-thumbnail:0").drawable as android.graphics.drawable.BitmapDrawable).bitmap
            assertTrue("PDF thumbnail preserves aspect ratio", kotlin.math.abs(bitmap.width.toFloat()/bitmap.height - 595f/842f) < .05f)
        }
        val third = context.getString(R.string.saved_pdf_page, 3, 3)
        waitUntil { device.hasObject(androidx.test.uiautomator.By.text(third)) }
        var imageBounds = Rect()
        scenario.onActivity { activity ->
            fun image(view: View): android.widget.ImageView? {
                if (view is android.widget.ImageView && view.contentDescription == context.getString(R.string.saved_pdf_preview_description)) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) image(view.getChildAt(index))?.let { return it }
                return null
            }
            imageBounds = bounds(image(host(activity)!!)!!)
        }
        device.swipe(imageBounds.centerX() - imageBounds.width()/3, imageBounds.centerY(), imageBounds.centerX()+imageBounds.width()/3, imageBounds.centerY(), 18)
        waitUntil { device.hasObject(androidx.test.uiautomator.By.text(context.getString(R.string.saved_pdf_page, 2, 3))) }
        screenshot(device, "saved-pdf")
        device.pressBack()
        waitUntil { hostCount(scenario) == 0 }
        val originalLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        var localeFailure: Throwable? = null
        try {
            for (language in listOf("en", "ru")) {
                switchLocaleAndAwaitActivity(scenario, androidx.core.os.LocaleListCompat.forLanguageTags(language))
                scenario.onActivity { it.showSavedPdfPreview(recent) }
                waitUntil { topHost(scenario)?.findViewWithTag<View>("docscan-pdf-thumbnail:2")?.isLaidOut == true }
                scenario.onActivity { assertFooterCaptions(host(it)!!, it.resources.displayMetrics.density, "$language PDF") }
                screenshot(device, "compact-pdf-$language")
                device.pressBack()
                waitUntil { hostCount(scenario) == 0 }
            }
        } catch (failure: Throwable) {
            localeFailure = failure
            throw failure
        } finally {
            restoreLocaleAndRetainFailure(scenario, originalLocales, localeFailure)
        }
        assertEquals(workspace, storage.workspaceProjection())
        assertArrayEquals(original, storage.exportOutputFile(pdf).readBytes())
    }

    @Test fun retakeFromMenuReturnsToCameraAndPreservesPageIdentityOrderAndSelection() = fixture { scenario, storage, device ->
        val before = storage.workspaceProjection().pages
        openWorkspaceMenuForTest(scenario)
        scrollWorkspaceListToPositionForTest(scenario, 0)
        var actionBounds = Rect()
        scenario.onActivity { activity ->
            val row = host(activity)!!.findViewWithTag<ViewGroup>(ProductActivity.PAGE_ROW_TAG_PREFIX + before.first().pageId.value)
            fun retake(view: View): android.widget.Button? {
                if (view is android.widget.Button && view.text.toString() == activity.getString(R.string.retake_page)) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) retake(view.getChildAt(index))?.let { return it }
                return null
            }
            val button = requireNotNull(retake(row))
            var parent = button.parent
            while (parent is View && parent !is android.widget.HorizontalScrollView) parent = parent.parent
            (parent as android.widget.HorizontalScrollView).scrollTo(button.left, 0)
            button.performClick()
        }
        waitUntil { hostCount(scenario) == 0 }
        waitUntil {
            var ready = false
            scenario.onActivity { activity ->
                val shutter = activity.window.decorView.findViewWithTag<View>("docscan-capture-button")
                ready = shutter.isEnabled && shutter.isShown
                if (ready) actionBounds = bounds(shutter)
            }
            ready
        }
        assertTrue(device.click(actionBounds.centerX(), actionBounds.centerY()))
        waitUntil { storage.workspaceProjection().pages.first().imageVersionId != before.first().imageVersionId }
        val end = SystemClock.uptimeMillis() + 60000
        while (storage.workspaceProjection().pages.first().recognizedText == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        val after = storage.workspaceProjection().pages
        assertNotNull("Retake OCR completes", after.first().recognizedText)
        assertEquals(before.map { it.pageId }, after.map { it.pageId })
        assertEquals(before.map { it.orderIndex }, after.map { it.orderIndex })
        assertEquals(before.map { it.selected }, after.map { it.selected })
        assertEquals(before.drop(1), after.drop(1))
        screenshot(device, "retake-complete")
    }

    private fun restoreLocaleAndRetainFailure(scenario: ActivityScenario<ProductActivity>, locales: androidx.core.os.LocaleListCompat, primary: Throwable?) {
        try { switchLocaleAndAwaitActivity(scenario, locales) }
        catch (restore: Throwable) {
            if (primary == null) throw restore
            primary.addSuppressed(restore)
        }
    }

    private fun switchLocaleAndAwaitActivity(scenario: ActivityScenario<ProductActivity>, locales: androidx.core.os.LocaleListCompat) {
        var previous: ProductActivity? = null
        var changed = false
        var language = ""
        val requested = locales.toLanguageTags()
        scenario.onActivity { activity ->
            previous = activity
            val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            changed = current != requested
            // Process Resources can still contain ru while an empty app locale list restores en-US.
            // LocaleManager supplies device locales independently of per-app configuration timing.
            val system = if (android.os.Build.VERSION.SDK_INT >= 33) {
                activity.getSystemService(android.app.LocaleManager::class.java).systemLocales[0].language
            } else android.content.res.Resources.getSystem().configuration.locales[0].language
            language = locales.get(0)?.language ?: system
            android.util.Log.i("DocScanNativeLocale", "before=$current requested=$requested target=$language device=$system processResources=${android.content.res.Resources.getSystem().configuration.locales.toLanguageTags()} instance=${System.identityHashCode(activity)} replace=$changed")
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
        }
        var observation = "not observed"
        try {
            waitUntil {
                var ready = false
                scenario.onActivity { activity ->
                    val actual = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
                    val effective = activity.resources.configuration.locales[0].language
                    val laidOut = activity.window.decorView.findViewWithTag<View>("docscan-home-actions")?.isLaidOut == true
                    val replaced = activity !== previous
                    observation = "requested=$requested actual=$actual target=$language effective=$effective replaced=$replaced layout=$laidOut"
                    ready = actual == requested && (!changed || replaced) && effective == language && laidOut
                }
                ready
            }
        } catch (failure: Throwable) {
            throw AssertionError("Locale transition: $observation", failure)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.util.Log.i("DocScanNativeLocale", "ready $observation")
    }

    private fun assertTextFits(view: android.widget.TextView, description: String) {
        val text = view.transformationMethod?.getTransformation(view.text, view) ?: view.text
        if (view is android.widget.Button && view !is CheckBox) assertTrue("Visible button caption: $description", text.isNotBlank())
        if (text.isEmpty()) return
        val layout = requireNotNull(view.layout)
        assertTrue("No hidden final line: $description $text", layout.lineCount > 0 && layout.getLineEnd(layout.lineCount-1) == text.length)
        assertTrue("No vertical clipping: $description $text", layout.height <= view.height-view.compoundPaddingTop-view.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals("No ellipsis: $description $text", 0, layout.getEllipsisCount(line))
            assertTrue("No horizontal clipping: $description $text", layout.getLineWidth(line) <= view.width-view.compoundPaddingLeft-view.compoundPaddingRight)
        }
    }
    private fun assertFooterCaptions(host: View, density: Float, description: String) {
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is android.widget.TextView) assertTextFits(view, description)
            if (view is android.widget.Button) {
                assertTrue("Footer target >=48dp: $description", view.width >= 48*density && view.height >= 48*density)
                assertTrue("Footer accessible name retained: $description", !(view.contentDescription ?: view.text).isNullOrBlank())
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(host.findViewWithTag(InsetDialogHost.RAIL_TAG))
    }

    private fun assertHost(host: View, density: Float, hasRail: Boolean) {
        val outer = bounds(host)
        val panel = bounds(host.findViewWithTag(InsetDialogHost.PANEL_TAG))
        assertTrue("Left margin >=20dp: $outer / $panel", panel.left - outer.left >= 19 * density)
        assertTrue("Right margin >=20dp: $outer / $panel", outer.right - panel.right >= 19 * density)
        assertTrue("Top margin >=24dp: $outer / $panel", panel.top - outer.top >= 23 * density)
        assertTrue("Bottom margin >=24dp", outer.bottom - panel.bottom >= 23 * density)
        if (hasRail) {
            val rail = bounds(host.findViewWithTag(InsetDialogHost.RAIL_TAG))
            assertTrue("Rail remains below page window", rail.top >= panel.bottom + 19 * density)
            assertTrue("Rail is in the bottom 175dp", rail.top >= outer.bottom - 175 * density)
        }
        android.util.Log.i("DocScanNativeLayout", "density=$density host=$outer panel=$panel bottomRail=$hasRail")
    }

    private fun bounds(view: View): Rect = Rect().also { rect ->
        view.getLocalVisibleRect(rect)
        val origin = IntArray(2); view.getLocationOnScreen(origin); rect.offset(origin[0], origin[1])
    }
    @Suppress("UNCHECKED_CAST") private fun hosts(activity: ProductActivity): List<View> {
        val controller = ProductActivity::class.java.getDeclaredField("dialogHost").apply { isAccessible = true }.get(activity)
        return InsetDialogHost::class.java.getDeclaredField("hosts").apply { isAccessible = true }.get(controller) as List<View>
    }
    private fun host(activity: ProductActivity) = hosts(activity).lastOrNull()
    private fun topHost(scenario: ActivityScenario<ProductActivity>): View? { var host: View? = null; scenario.onActivity { host = host(it) }; return host }
    private fun hostCount(scenario: ActivityScenario<ProductActivity>): Int { var count = 0; scenario.onActivity { count = hosts(it).size }; return count }
    private fun waitUntil(condition: () -> Boolean) { val end = SystemClock.uptimeMillis() + 15000; while (!condition()) { if (SystemClock.uptimeMillis() > end) throw AssertionError("UI condition timed out"); SystemClock.sleep(50) } }
    private fun screenshot(device: UiDevice, name: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = (output?.let { java.io.File(it, "native-ui-evidence") }
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("native-ui-evidence")!!).apply { mkdirs() }
        assertTrue(device.takeScreenshot(dir.resolve("$name.png")))
    }
    private fun fixture(test: (ActivityScenario<ProductActivity>, ProductStorage, UiDevice) -> Unit) {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val app = context.applicationContext as DocScanApplication
        val old = app.productStorageOverrideForTest
        val suffix = UUID.randomUUID().toString(); val db = "native-layout-$suffix.db"; val artifacts = "native-layout-$suffix"
        val storage = ProductStorage(context, db, artifacts)
        repeat(3) { index ->
            val bitmap = Bitmap.createBitmap(320, 440, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index == 0) 0xfff5f0df.toInt() else 0xffeeeeff.toInt()) }
            val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray(); bitmap.recycle()
            val pageId = PageId.new()
            storage.commitCapture(CaptureCommand(workspaceId = storage.workspaceId, pageId = pageId, imageVersionId = ImageVersionId.new(), artifact = storage.stageCapturedJpeg(ByteArrayInputStream(bytes)), selected = index == 0, makeActive = false, coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT", processingDesiredState = ProductActivity.OCR_OPERATION_KEY, sourceProvenance = "INSTRUMENTATION_TEST"))
            val page = storage.workspaceProjection().pages.last()
            storage.commitRecognition(RecognitionCandidate(RecognitionId.new(), storage.workspaceId, page.pageId, page.imageVersionId, page.contentRevision, ProductActivity.OCR_OPERATION_KEY, "Native UI fixture ${index + 1}"))
        }
        app.productStorageOverrideForTest = storage
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        try { test(scenario, storage, UiDevice.getInstance(inst)) } catch (failure: Throwable) {
            runCatching { screenshot(UiDevice.getInstance(inst), "failure") }
            throw failure
        } finally {
            scenario.close(); app.productStorageOverrideForTest = old; storage.close(); context.deleteDatabase(db); context.noBackupFilesDir.resolve(artifacts).deleteRecursively()
        }
    }
}
