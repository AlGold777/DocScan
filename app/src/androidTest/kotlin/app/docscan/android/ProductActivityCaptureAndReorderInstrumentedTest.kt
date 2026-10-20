package app.docscan.android

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.store.StoredPage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProductActivityCaptureAndReorderInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun emptyWorkspaceHomeActionsHaveDistinctNamesAndFortyEightDpTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        val previousOverride = application.productStorageOverrideForTest
        val suffix = UUID.randomUUID().toString()
        val databaseName = "a11y-home-$suffix.db"
        val storage = ProductStorage(context, databaseName, "a11y-home-artifacts-$suffix")
        application.productStorageOverrideForTest = storage
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        try {
            awaitCaptureReady(scenario)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: throw AssertionError("Empty-workspace Home accessibility window is unavailable")
            val targets = mutableListOf<ViewerAccessibilityTarget>()
            collectViewerAccessibilityTargets(root, targets)
            val inventory = targets.joinToString("\n") { target ->
                "${target.className} label=${target.label ?: "<missing>"} bounds=${target.widthPx}x${target.heightPx}"
            }
            val density = context.resources.displayMetrics.density
            android.util.Log.i("DocScanA11y", "Empty-workspace Home inventory: density=$density\n$inventory")
            assertTrue("Home exposes visible actionable controls:\n$inventory", targets.isNotEmpty())
            assertTrue(
                "Every visible Home action needs a nonblank accessible name:\n$inventory",
                targets.none { it.label.isNullOrBlank() },
            )
            val labels = targets.map { it.label!!.trim().lowercase() }
            assertEquals("Visible Home action names are distinct:\n$inventory", labels.size, labels.toSet().size)
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val expected = listOf(
                context.getString(R.string.capture_page),
                context.getString(R.string.import_images_or_pdf),
                context.getString(R.string.export_selected_pages),
                context.getString(R.string.select_all_pages),
                context.getString(R.string.delete_selected_pages),
            ).map { it.trim().lowercase() }
            expected.forEach { label ->
                assertTrue("Home action '$label' is missing from the accessibility tree:\n$inventory", label in labels)
            }
            val minimumSizePx = kotlin.math.ceil(48f * density).toInt()
            val undersized = targets.filter { it.widthPx < minimumSizePx || it.heightPx < minimumSizePx }
            assertTrue(
                "Every visible Home target must meet 48dp (${minimumSizePx}px):\n$inventory",
                undersized.isEmpty(),
            )
        } finally {
            scenario.close()
            application.productStorageOverrideForTest = previousOverride
            storage.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun pageSelectionCheckboxesExposeDistinctAccessibleLabelsAndUpdateOnlyTheirPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        val fixtures = mutableListOf<PageId>()
        try {
            awaitCaptureReady(scenario)
            val first = createFixturePage(context, storage, selected = true)
            val second = createFixturePage(context, storage, selected = false)
            fixtures += first.pageId
            fixtures += second.pageId
            scenario.onActivity { it.renderPages(storage.workspaceProjection()) }

            scrollHomeRailToPositionForTest(scenario, 0)

            val firstLabel = context.getString(R.string.include_page_in_save, 1)
            val secondLabel = context.getString(R.string.include_page_in_save, 2)
            assertTrue("Page labels identify different ordinals", firstLabel != secondLabel)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val firstAppeared = device.wait(Until.hasObject(By.desc(firstLabel)), 5_000)
            assertTrue("Page 1 selection checkbox appears after the workspace row scrolls into view", firstAppeared)
            val firstCheckbox = requireNotNull(device.findObject(By.desc(firstLabel))) {
                "Page 1 selection checkbox is not exposed in the accessibility tree"
            }
            assertTrue("Page 1 accessibility node retains the checkbox role", firstCheckbox.isCheckable)
            assertTrue("Page 1 starts selected", firstCheckbox.isChecked)

            scrollHomeRailToPositionForTest(scenario, 1)
            assertTrue(
                "Page 2 selection checkbox appears after its workspace row scrolls into view",
                device.wait(Until.hasObject(By.desc(secondLabel)), 5_000),
            )
            val secondCheckbox = requireNotNull(device.findObject(androidx.test.uiautomator.By.desc(secondLabel))) {
                "Page 2 selection checkbox is not exposed in the accessibility tree after scrolling to it"
            }
            assertTrue("Page 2 accessibility node retains the checkbox role", secondCheckbox.isCheckable)
            assertTrue("Page 2 starts unselected", !secondCheckbox.isChecked)

            secondCheckbox.click()
            awaitCondition("Accessible Page 2 activation must persist only Page 2 selection") {
                val pages = storage.workspaceProjection().pages
                pages.singleOrNull { it.pageId == first.pageId }?.selected == true &&
                    pages.singleOrNull { it.pageId == second.pageId }?.selected == true
            }
            awaitCondition("Page 2 remains checked in the current accessibility tree") { device.findObject(By.desc(secondLabel))?.isChecked == true }
            assertEquals(true, storage.workspaceProjection().pages.single { it.pageId == first.pageId }.selected)
        } finally {
            scenario.close()
            fixtures.forEach { id ->
                storage.workspaceProjection().pages.singleOrNull { it.pageId == id }?.let(storage::deletePage)
            }
        }
    }

    @Test
    fun workspaceViewerSynchronizesActiveAndSelectedAcrossThumbnailsButtonsAndSwipes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val priorActive = storage.workspaceProjection().activePageId
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        var reopened: ActivityScenario<ProductActivity>? = null
        var scenarioClosed = false
        val fixtures = mutableListOf<PageId>()
        try {
            awaitCaptureReady(scenario)
            val first = createFixturePage(context, storage, selected = true)
            val second = createFixturePage(context, storage, selected = false)
            val third = createFixturePage(context, storage, selected = true)
            fixtures += listOf(first.pageId, second.pageId, third.pageId)
            storage.setActivePage(first.pageId)
            scenario.onActivity { it.renderPages(storage.workspaceProjection()) }

            scrollHomeRailToPositionForTest(scenario, 0)
            scenario.onActivity { activity ->
                val thumb = findViewByTag(activity.window.decorView,
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + first.pageId.value) as? View
                    ?: throw AssertionError("Active Page 1 thumbnail is missing")
                val frame = thumb.background as? GradientDrawable
                    ?: throw AssertionError("Workspace thumbnail has no active frame")
                assertEquals(context.getString(R.string.active_page, 1), thumb.contentDescription)
                thumb.performClick()
            }
            awaitCondition("Opening a workspace thumbnail must persist Active before image rendering") {
                storage.workspaceProjection().activePageId == first.pageId
            }
            awaitCondition("Viewer strip must be rendered") {
                var present = false
                scenario.onActivity { activity ->
                    present = findViewerView(activity, ProductActivity.VIEWER_THUMBNAIL_STRIP_TAG) != null
                }
                present
            }
            assertWorkspaceViewerAccessibility(context)

            scenario.onActivity { activity ->
                val secondThumb = findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + second.pageId.value)
                    ?: throw AssertionError("Viewer Page 2 thumbnail is missing")
                secondThumb.performClick()
            }
            awaitCondition("Tapping the Viewer strip makes only its Page Active") {
                storage.workspaceProjection().activePageId == second.pageId &&
                    storage.workspaceProjection().pages.single { it.pageId == second.pageId }.selected.not()
            }
            assertTrue(storage.workspaceProjection().pages.single { it.pageId == first.pageId }.selected)
            assertTrue(storage.workspaceProjection().pages.single { it.pageId == third.pageId }.selected)
            assertEquals(false, storage.workspaceProjection().pages.single { it.pageId == second.pageId }.selected)

            scenario.onActivity { activity ->
                val pageTwoSelection = findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_SELECTION_TAG_PREFIX + second.pageId.value) as? CheckBox
                    ?: throw AssertionError("Viewer Page 2 Selected checkbox is missing")
                val minimumHitTargetPx = kotlin.math.ceil(
                    48f * activity.resources.displayMetrics.density,
                ).toInt()
                assertTrue(
                    "Viewer selection checkbox meets the 48dp minimum hit target (actual=${pageTwoSelection.height}px, minimum=${minimumHitTargetPx}px, minimumHeight=${pageTwoSelection.minimumHeight}px)",
                    pageTwoSelection.height >= minimumHitTargetPx,
                )
                assertFalse("Viewer checkbox mirrors persisted selection", pageTwoSelection.isChecked)
                pageTwoSelection.performClick()
            }
            awaitCondition("Viewer selection persists without changing Active") {
                storage.workspaceProjection().pages.single { it.pageId == second.pageId }.selected &&
                    storage.workspaceProjection().activePageId == second.pageId
            }

            scenario.onActivity { activity ->
                (findViewerView(activity, ProductActivity.VIEWER_NEXT_TAG) as? View)
                    ?.performClick() ?: throw AssertionError("Viewer Next control is missing")
            }
            awaitCondition("Next updates the displayed and durable Active Page") {
                storage.workspaceProjection().activePageId == third.pageId
            }

            scenario.onActivity { activity ->
                val title = findViewerView(activity, ProductActivity.VIEWER_TITLE_TAG) as? android.widget.TextView
                val ordinal = storage.workspaceProjection().pages.indexOfFirst { it.pageId == third.pageId } + 1
                assertEquals(context.getString(R.string.page_number, ordinal), title?.text)
                val selectedPageTwo = findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_SELECTION_TAG_PREFIX + second.pageId.value) as? CheckBox
                assertTrue("Viewer strip retains Selected state while Active moves",
                    selectedPageTwo?.isChecked == true)
                val activeThumbnail = findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + third.pageId.value) as? View
                    ?: throw AssertionError("Viewer Active thumbnail is missing")
                val activeFrame = activeThumbnail.background as? GradientDrawable
                    ?: throw AssertionError("Viewer Active thumbnail has no frame")
                val activeOrdinal = storage.workspaceProjection().pages.indexOfFirst { it.pageId == third.pageId } + 1
                assertEquals(context.getString(R.string.active_page, activeOrdinal), activeThumbnail.contentDescription)
                assertFalse("Next is disabled at the last Page", findViewerView(activity,
                    ProductActivity.VIEWER_NEXT_TAG)?.isEnabled ?: true)
                (findViewerView(activity, ProductActivity.VIEWER_NEXT_TAG) as View).performClick()
            }
            SystemClock.sleep(150L)
            assertEquals("Next at the end does not wrap", third.pageId, storage.workspaceProjection().activePageId)

            scenario.onActivity { activity ->
                (findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + second.pageId.value) as? View)
                    ?.performClick() ?: throw AssertionError("Viewer Page 2 thumbnail is missing")
            }
            awaitCondition("Selecting Page 2 prepares backward and forward swipe checks") {
                storage.workspaceProjection().activePageId == second.pageId
            }
            swipeViewer(scenario, directionLeft = false)
            awaitCondition("Right swipe navigates to the previous Page") {
                storage.workspaceProjection().activePageId == first.pageId
            }
            swipeViewer(scenario, directionLeft = true)
            awaitCondition("Left swipe navigates to the next Page") {
                storage.workspaceProjection().activePageId == second.pageId
            }
            scenario.onActivity { activity ->
                (findViewerView(activity, ProductActivity.VIEWER_PREVIOUS_TAG) as? View)
                    ?.performClick() ?: throw AssertionError("Viewer Previous control is missing")
            }
            awaitCondition("Previous control shares the swipe navigation state") {
                storage.workspaceProjection().activePageId == first.pageId
            }
            val actualFirst = storage.workspaceProjection().pages.first()
            scenario.onActivity { activity ->
                (findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + actualFirst.pageId.value) as? View)
                    ?.performClick() ?: throw AssertionError("Workspace first Page thumbnail is missing")
            }
            awaitCondition("Viewer can navigate to the true first workspace Page") {
                storage.workspaceProjection().activePageId == actualFirst.pageId
            }
            scenario.onActivity { activity ->
                assertFalse("Previous is disabled at the first Page", findViewerView(activity,
                    ProductActivity.VIEWER_PREVIOUS_TAG)?.isEnabled ?: true)
            }
            swipeViewer(scenario, directionLeft = false)
            SystemClock.sleep(150L)
            assertEquals("Right swipe at the first Page does not wrap", actualFirst.pageId, storage.workspaceProjection().activePageId)
            scenario.onActivity { activity ->
                (findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + first.pageId.value) as? View)
                    ?.performClick() ?: throw AssertionError("Fixture Page 1 thumbnail is missing")
            }
            awaitCondition("Fixture Active state is restored before closing Viewer") {
                storage.workspaceProjection().activePageId == first.pageId
            }

            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            awaitCondition("Viewer closes before checking persisted reopen state") {
                var closed = false
                scenario.onActivity { activity ->
                    closed = findViewerView(activity, ProductActivity.VIEWER_THUMBNAIL_STRIP_TAG) == null
                }
                closed
            }
            scrollHomeRailToPositionForTest(scenario, 0)
            scenario.onActivity { activity ->
                val active = findViewByTag(activity.window.decorView,
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + first.pageId.value) as? View
                    ?: throw AssertionError("Workspace thumbnail missing after Viewer close")
                assertTrue("Workspace active frame is synchronized after Viewer close",
                    active.background is GradientDrawable && active.contentDescription == context.getString(
                        R.string.active_page,
                        storage.workspaceProjection().pages.indexOfFirst { it.pageId == first.pageId } + 1,
                    ))
            }
            scenario.close()
            scenarioClosed = true
            reopened = ActivityScenario.launch(ProductActivity::class.java)
            scrollHomeRailToPositionForTest(reopened!!, 0)
            awaitCondition("Reopened workspace restores durable Active Page") {
                storage.workspaceProjection().activePageId == first.pageId
            }
            reopened!!.onActivity { activity ->
                val active = findViewByTag(activity.window.decorView,
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + first.pageId.value) as? View
                    ?: throw AssertionError("Workspace Active thumbnail missing after Activity recreation")
                assertTrue("Reopened workspace shows the durable Active frame",
                    active.background is GradientDrawable && active.contentDescription == context.getString(
                        R.string.active_page,
                        storage.workspaceProjection().pages.indexOfFirst { it.pageId == first.pageId } + 1,
                    ))
            }
        } finally {
            if (!scenarioClosed) scenario.close()
            reopened?.close()
            fixtures.asReversed().forEach { id ->
                storage.workspaceProjection().pages.singleOrNull { it.pageId == id }?.let(storage::deletePage)
            }
            priorActive?.let { id ->
                if (storage.workspaceProjection().pages.any { it.pageId == id }) storage.setActivePage(id)
            }
        }
    }

    @Test
    fun rapidViewerThumbnailNavigationDisplaysTheLastRequestedImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val priorActive = storage.workspaceProjection().activePageId
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        val fixtures = mutableListOf<PageId>()
        try {
            awaitCaptureReady(scenario)
            val first = createFixturePage(context, storage, selected = true)
            val second = createFixturePage(context, storage, selected = false)
            val third = createFixturePage(context, storage, selected = true)
            fixtures += listOf(first.pageId, second.pageId, third.pageId)
            storage.setActivePage(first.pageId)
            scrollHomeRailToPositionForTest(scenario, 0)
            scenario.onActivity { activity ->
                activity.renderPages(storage.workspaceProjection())
                (findViewByTag(activity.window.decorView,
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + first.pageId.value) as? View)
                    ?.performClick() ?: throw AssertionError("Workspace Page 1 thumbnail is missing")
            }
            awaitCondition("Viewer strip opens for the fixture workspace") {
                var present = false
                scenario.onActivity { activity ->
                    present = findViewerView(activity, ProductActivity.VIEWER_THUMBNAIL_STRIP_TAG) != null
                }
                present
            }

            scenario.onActivity { activity ->
                val secondThumbnail = findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + second.pageId.value)
                    ?: throw AssertionError("Viewer Page 2 thumbnail is missing")
                val thirdThumbnail = findViewerView(activity,
                    ProductActivity.VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + third.pageId.value)
                    ?: throw AssertionError("Viewer Page 3 thumbnail is missing")
                // Queue both requests in the same UI turn so the first decode cannot settle first.
                secondThumbnail.performClick()
                thirdThumbnail.performClick()
            }
            awaitCondition("The final thumbnail request becomes durable Active and displays its red image") {
                var finalImageShown = false
                scenario.onActivity { activity ->
                    val viewerImage = findViewerView(activity,
                        ProductActivity.VIEWER_MAIN_IMAGE_TAG) as? android.widget.ImageView
                    val bitmap = (viewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        finalImageShown = android.graphics.Color.red(pixel) > android.graphics.Color.blue(pixel)
                    }
                }
                finalImageShown && storage.workspaceProjection().activePageId == third.pageId
            }
            assertFalse("Rapid navigation does not change Page 2 Selected state",
                storage.workspaceProjection().pages.single { it.pageId == second.pageId }.selected)
        } finally {
            scenario.close()
            fixtures.asReversed().forEach { id ->
                storage.workspaceProjection().pages.singleOrNull { it.pageId == id }?.let(storage::deletePage)
            }
            priorActive?.let { id ->
                if (storage.workspaceProjection().pages.any { it.pageId == id }) storage.setActivePage(id)
            }
        }
    }

    @Test
    fun viewerSelectionPersistsActiveEvenWhenPageImageCannotBeDecoded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val priorActive = storage.workspaceProjection().activePageId
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        var fixtureId: PageId? = null
        var imagePath: java.nio.file.Path? = null
        var originalImage: ByteArray? = null
        try {
            awaitCaptureReady(scenario)
            val fixture = createFixturePage(context, storage, selected = false)
            fixtureId = fixture.pageId
            imagePath = storage.artifactPath(fixture.artifactRelativePath)
            originalImage = java.nio.file.Files.readAllBytes(imagePath)
            java.nio.file.Files.write(imagePath, byteArrayOf(0, 1, 2, 3, 4))
            scrollHomeRailToPositionForTest(scenario, 0)
            scenario.onActivity { activity ->
                activity.renderPages(storage.workspaceProjection())
                (findViewByTag(activity.window.decorView,
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + fixture.pageId.value) as? View)
                    ?.performClick() ?: throw AssertionError("Corrupt-image fixture thumbnail is missing")
            }
            awaitCondition("Active is persisted even when selected Page image decoding fails") {
                storage.workspaceProjection().activePageId == fixture.pageId
            }
            awaitCondition("Viewer reports the image decode error") {
                var failed = false
                scenario.onActivity { activity ->
                    failed = (findViewerView(activity, ProductActivity.VIEWER_TITLE_TAG)
                        as? android.widget.TextView)?.text != context.getString(
                        R.string.page_number,
                        storage.workspaceProjection().pages.indexOfFirst { it.pageId == fixture.pageId } + 1,
                    )
                }
                failed
            }
            assertFalse("Decode failure does not change Selected", storage.workspaceProjection().pages
                .single { it.pageId == fixture.pageId }.selected)
        } finally {
            scenario.close()
            if (imagePath != null && originalImage != null) java.nio.file.Files.write(imagePath, originalImage)
            fixtureId?.let { id ->
                storage.workspaceProjection().pages.singleOrNull { it.pageId == id }?.let(storage::deletePage)
            }
            priorActive?.let { id ->
                if (storage.workspaceProjection().pages.any { it.pageId == id }) storage.setActivePage(id)
            }
        }
    }

    private fun swipeViewer(scenario: ActivityScenario<ProductActivity>, directionLeft: Boolean) {
        val rect = android.graphics.Rect()
        awaitCondition("Viewer main image becomes visible before swiping") {
            var visible = false
            scenario.onActivity { activity ->
                val image = findViewerView(activity, ProductActivity.VIEWER_MAIN_IMAGE_TAG) as? android.widget.ImageView
                visible = image != null && image.drawable != null && image.getGlobalVisibleRect(rect) &&
                    rect.width() > 0 && rect.height() > 0
            }
            visible
        }
        val centerY = rect.centerY()
        val startX = if (directionLeft) rect.left + rect.width() * 3 / 4 else rect.left + rect.width() / 4
        val endX = if (directionLeft) rect.left + rect.width() / 4 else rect.left + rect.width() * 3 / 4
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .swipe(startX, centerY, endX, centerY, 12)
    }

    @Test
    fun validShutterProvidesOneLightFeedbackAndCommitsPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val beforeIds = storage.workspaceProjection().pages.map { it.pageId }.toSet()
        val feedbackCount = AtomicInteger()
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        try {
            val activity = awaitCaptureReady(scenario)
            scenario.onActivity { it.uiHapticFeedback = { feedbackCount.incrementAndGet() } }
            scenario.onActivity { activity ->
                findViewByTag(activity.window.decorView, "docscan-capture-button")
                    ?.performClick() ?: throw AssertionError("Capture button is missing")
            }
            assertEquals("A valid shutter invokes the system haptic hook once", 1, feedbackCount.get())
            awaitCondition("Camera capture must commit a new Page") {
                storage.workspaceProjection().pages.any { it.pageId !in beforeIds }
            }
            val captured = storage.workspaceProjection().pages.filter { it.pageId !in beforeIds }
            assertEquals(1, captured.size)
            storage.deletePage(captured.single())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun workspaceThumbnailSwipeDeletesOnlyTargetAndUndoRestoresPageState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "swipe-delete-$suffix.db"
        val artifactDirectory = "swipe-delete-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            val target = createFixturePage(context, storage, selected = true)
            val untouched = createFixturePage(context, storage, selected = false)
            val extra = listOf(createFixturePage(context, storage, selected = false), createFixturePage(context, storage, selected = false))
            val originalPages = storage.workspaceProjection().pages
            assertEquals(listOf(target.pageId, untouched.pageId) + extra.map { it.pageId }, originalPages.map { it.pageId })

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            awaitCaptureReady(activeScenario)
            activeScenario.onActivity { it.renderPages(storage.workspaceProjection()) }
            openWorkspaceMenuForTest(activeScenario)
            scrollWorkspaceListToPositionForTest(activeScenario, 0)
            val device = UiDevice.getInstance(instrumentation)
            val menuScrollRef = AtomicReference<ScrollView>()
            activeScenario.onActivity { activity ->
                assertNotNull("Detailed Page list is available in the open Menu", activity.workspacePagesListForTest)
                menuScrollRef.set(requireNotNull(nativeWorkspaceRoot(activity).findViewWithTag("docscan-workspace-menu-scroll")))
            }
            awaitCondition("Target thumbnail is above the system navigation gesture area") {
                val bounds = android.graphics.Rect()
                var safelyPositioned = false
                activeScenario.onActivity { activity ->
                    val thumbnail = findViewByTag(
                        nativeWorkspaceRoot(activity),
                        ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + target.pageId.value,
                    )
                    safelyPositioned = thumbnail != null && visibleBoundsOnScreen(thumbnail, bounds) &&
                        bounds.centerY() < device.displayHeight - 180
                }
                safelyPositioned
            }
            fun swipeTarget(distanceFraction: Float) {
                val bounds = android.graphics.Rect()
                activeScenario.onActivity { activity ->
                    val thumbnail = findViewByTag(
                        nativeWorkspaceRoot(activity),
                        ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + target.pageId.value,
                    ) ?: throw AssertionError("Target Page thumbnail is missing")
                    visibleBoundsOnScreen(thumbnail, bounds)
                }
                assertFalse("Target Page thumbnail is visible", bounds.isEmpty)
                val startX = bounds.left + (bounds.width() * 0.35f).toInt()
                val endX = startX + (bounds.width() * distanceFraction).toInt()
                assertTrue("Swipe remains within the target thumbnail", endX <= bounds.right)
                assertTrue(device.swipe(startX, bounds.centerY(), endX, bounds.centerY(), 12))
            }

            swipeTarget(distanceFraction = 0.16f)
            assertEquals("A short horizontal movement must not delete either Page", originalPages,
                storage.workspaceProjection().pages)
            val viewerOpened = AtomicBoolean(false)
            activeScenario.onActivity {
                viewerOpened.set(findViewerView(it, ProductActivity.VIEWER_THUMBNAIL_STRIP_TAG) != null)
            }
            assertFalse("A short movement must not open the Viewer", viewerOpened.get())

            val scrollBeforeVerticalGesture = AtomicReference<List<Int>>()
            activeScenario.onActivity { activity ->
                scrollBeforeVerticalGesture.set(listOf(
                    requireNotNull(activity.workspacePagesListForTest).computeVerticalScrollOffset(),
                    menuScrollRef.get().scrollY,
                ))
            }
            val targetBounds = android.graphics.Rect()
            activeScenario.onActivity { activity ->
                val thumbnail = findViewByTag(
                    nativeWorkspaceRoot(activity),
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + target.pageId.value,
                ) ?: throw AssertionError("Target Page thumbnail is missing")
                visibleBoundsOnScreen(thumbnail, targetBounds)
            }
            assertTrue(device.swipe(
                targetBounds.centerX(), targetBounds.centerY() - 60,
                targetBounds.centerX(), targetBounds.centerY() + 60, 12,
            ))
            awaitCondition("A vertical gesture on the thumbnail continues to scroll the workspace") {
                var scrolled = false
                activeScenario.onActivity { activity ->
                    val list = requireNotNull(activity.workspacePagesListForTest)
                    scrolled = list.computeVerticalScrollOffset() != scrollBeforeVerticalGesture.get()[0] ||
                        menuScrollRef.get().scrollY != scrollBeforeVerticalGesture.get()[1]
                }
                scrolled
            }
            assertEquals("Vertical movement preserves both Pages", originalPages,
                storage.workspaceProjection().pages)
            activeScenario.onActivity { activity ->
                requireNotNull(activity.workspacePagesListForTest).scrollToPosition(0)
                menuScrollRef.get().scrollTo(0, 0)
            }
            awaitCondition("Target thumbnail returns above the system navigation gesture area") {
                val bounds = android.graphics.Rect()
                var safelyPositioned = false
                activeScenario.onActivity { activity ->
                    val thumbnail = findViewByTag(
                        nativeWorkspaceRoot(activity),
                        ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + target.pageId.value,
                    )
                    safelyPositioned = thumbnail != null && visibleBoundsOnScreen(thumbnail, bounds) &&
                        bounds.centerY() < device.displayHeight - 180
                }
                safelyPositioned
            }

            swipeTarget(distanceFraction = 0.60f)
            awaitCondition("A deliberate thumbnail swipe deletes its target Page") {
                storage.workspaceProjection().pages.map { it.pageId } == originalPages.drop(1).map { it.pageId }
            }
            val remaining = storage.workspaceProjection().pages.first { it.pageId == untouched.pageId }
            assertEquals("The other Page remains", untouched.pageId, remaining.pageId)
            assertEquals("The other Page selection remains", untouched.selected, remaining.selected)
            assertEquals("The other Page OCR state remains", untouched.recognizedText, remaining.recognizedText)
            assertEquals("The other Page image version remains", untouched.imageVersionId, remaining.imageVersionId)
            assertEquals("The other Page artifact remains", untouched.artifactRelativePath, remaining.artifactRelativePath)

            val undoLabel = context.getString(R.string.undo_delete)
            val undo = device.wait(Until.findObject(By.text(undoLabel)), 5_000L)
            assertNotNull("Direct Delete offers Undo", undo)
            undo!!.click()
            awaitCondition("Undo restores the original ordered Page records") {
                storage.workspaceProjection().pages.map { it.pageId } == originalPages.map { it.pageId }
            }
            val restoredPages = storage.workspaceProjection().pages
            assertEquals(originalPages,
                restoredPages.mapIndexed { index, page ->
                    page.copy(contentRevision = originalPages[index].contentRevision)
                })
            assertEquals("Delete and Undo each advance the restored Page revision",
                originalPages.first().contentRevision + 2L, restoredPages.first().contentRevision)
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = oldOverride
            app.productStorageProviderForTest = oldProvider
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun selectedDeleteAndConfirmedClearUndoRestoreWorkspaceAsWholeBatches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "batch-delete-$suffix.db"
        val artifactDirectory = "batch-delete-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            val selectedFirst = createFixturePage(context, storage, selected = true)
            val activeUnselected = createFixturePage(context, storage, selected = false)
            val selectedLast = createFixturePage(context, storage, selected = true)
            storage.setActivePage(activeUnselected.pageId)
            val originalPages = storage.workspaceProjection().pages
            assertEquals(listOf(selectedFirst.pageId, activeUnselected.pageId, selectedLast.pageId),
                originalPages.map { it.pageId })

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            awaitCaptureReady(activeScenario)
            activeScenario.onActivity { it.renderPages(storage.workspaceProjection()) }
            var clearDuringBatch: View? = null
            activeScenario.onActivity { activity ->
                clearDuringBatch = ProductActivity::class.java.getDeclaredField("clearWorkspaceButton")
                    .apply { isAccessible = true }.get(activity) as? View
                assertNotNull("The Activity owns the Clear control used by its menu", clearDuringBatch)
            }
            activeScenario.onActivity { activity ->
                val deleteSelected = findViewByTag(activity.window.decorView, "docscan-delete-selected-button")
                    ?: throw AssertionError("Delete Selected control is missing")
                assertTrue("Delete Selected is enabled for explicit selection", deleteSelected.isEnabled)
                assertTrue("Delete Selected control accepts a click", deleteSelected.performClick())
                val save = findViewByTag(activity.window.decorView, "docscan-save-button")
                    ?: throw AssertionError("Save control is missing")
                assertFalse("Save is disabled as soon as the batch mutation acquires the operation slot", save.isEnabled)
                assertFalse("Clear remains disabled while Delete owns the operation slot",
                    requireNotNull(clearDuringBatch).isEnabled)
                assertFalse("Duplicate Delete is disabled while the first batch is in flight", deleteSelected.isEnabled)
            }
            awaitCondition("Batch Delete removes only explicitly selected Pages") {
                storage.workspaceProjection().pages.map { it.pageId } == listOf(activeUnselected.pageId)
            }
            assertEquals("Active unselected Page remains Active", activeUnselected.pageId,
                storage.workspaceProjection().activePageId)

            val device = UiDevice.getInstance(instrumentation)
            val undoLabel = context.getString(R.string.undo_delete)
            val batchUndo = device.wait(Until.findObject(By.text(undoLabel)), 5_000L)
            assertNotNull("Batch Delete offers one Undo action; active tree=${activeWindowTextInventory(instrumentation)}",
                batchUndo)
            assertTrue("Batch Undo action is enabled while visible", batchUndo!!.isEnabled)
            batchUndo.click()
            awaitCondition("Batch Undo restores all original Page identities") {
                storage.workspaceProjection().pages.map { it.pageId } == originalPages.map { it.pageId }
            }
            val afterBatchUndo = storage.workspaceProjection()
            assertEquals("Batch Undo restores order, selection, OCR, and image metadata", originalPages,
                afterBatchUndo.pages.mapIndexed { index, page ->
                    page.copy(contentRevision = originalPages[index].contentRevision)
                })
            assertEquals(activeUnselected.pageId, afterBatchUndo.activePageId)
            assertEquals(originalPages[0].contentRevision + 2L, afterBatchUndo.pages[0].contentRevision)
            assertEquals(originalPages[1].contentRevision, afterBatchUndo.pages[1].contentRevision)
            assertEquals(originalPages[2].contentRevision + 2L, afterBatchUndo.pages[2].contentRevision)

            storage.setAllVisiblePagesSelected(false)
            activeScenario.onActivity { it.renderPages(storage.workspaceProjection()) }
            openWorkspaceMenuForTest(activeScenario)
            var deleteDisabled = false
            var clearEnabled = false
            activeScenario.onActivity { activity ->
                deleteDisabled = findViewByTag(activity.window.decorView, "docscan-delete-selected-button")
                    ?.isEnabled == false
                clearEnabled = findViewByTag(nativeWorkspaceRoot(activity), "docscan-clear-workspace-button")
                    ?.isEnabled == true
            }
            assertTrue("Delete Selected is disabled with no explicit selection", deleteDisabled)
            assertTrue("Clear remains available with no explicit selection", clearEnabled)

            val pagesBeforeClear = storage.workspaceProjection().pages
            activeScenario.onActivity { activity ->
                val clearButton = findViewByTag(nativeWorkspaceRoot(activity), "docscan-clear-workspace-button")
                    ?: throw AssertionError("Clear Workspace control is missing")
                assertTrue("Clear is attached to the visible workspace menu", clearButton.isAttachedToWindow && clearButton.isShown)
                assertTrue("Clear Workspace control accepts a click", clearButton.performClick())
            }
            val cancel = device.wait(Until.findObject(By.res("android", "button2")), 5_000L)
            assertNotNull("Clear presents a confirmation with Cancel", cancel)
            cancel!!.click()
            assertEquals("Cancel leaves the complete workspace unchanged", pagesBeforeClear,
                storage.workspaceProjection().pages)
            assertEquals(activeUnselected.pageId, storage.workspaceProjection().activePageId)

            activeScenario.onActivity { activity ->
                val clearButton = findViewByTag(nativeWorkspaceRoot(activity), "docscan-clear-workspace-button")
                    ?: throw AssertionError("Clear Workspace control is missing")
                assertTrue("Clear is attached to the visible workspace menu", clearButton.isAttachedToWindow && clearButton.isShown)
                assertTrue("Clear Workspace can be confirmed in a second operation", clearButton.performClick())
            }
            val confirm = device.wait(Until.findObject(By.res("android", "button1")), 5_000L)
            assertNotNull("Clear confirmation exposes the positive action", confirm)
            confirm!!.click()
            awaitCondition("Confirmed Clear removes every unsaved workspace Page") {
                storage.workspaceProjection().pages.isEmpty()
            }
            val clearUndo = device.wait(Until.findObject(By.text(undoLabel)), 5_000L)
            assertNotNull("Clear offers one Undo action for the complete batch", clearUndo)
            assertTrue("Clear Undo action is enabled while visible", clearUndo!!.isEnabled)
            clearUndo.click()
            awaitCondition("Clear Undo restores the full Page order") {
                storage.workspaceProjection().pages.map { it.pageId } == pagesBeforeClear.map { it.pageId }
            }
            val afterClearUndo = storage.workspaceProjection()
            assertEquals("Clear Undo restores all Page metadata", pagesBeforeClear,
                afterClearUndo.pages.mapIndexed { index, page ->
                    page.copy(contentRevision = pagesBeforeClear[index].contentRevision)
                })
            assertEquals("Clear Undo restores Active state", activeUnselected.pageId, afterClearUndo.activePageId)
            afterClearUndo.pages.forEachIndexed { index, page ->
                assertEquals("Clear Delete and Undo advance each revision twice",
                    pagesBeforeClear[index].contentRevision + 2L, page.contentRevision)
            }
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = oldOverride
            app.productStorageProviderForTest = oldProvider
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun deletingAndClearingCancelNativeOcrAndUndoCanRestartIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "ocr-cancel-$suffix.db"
        val artifactDirectory = "ocr-cancel-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val firstOcrProgress = CountDownLatch(1)
        val firstOcrContinue = CountDownLatch(1)
        val clearOcrProgress = CountDownLatch(1)
        val clearOcrContinue = CountDownLatch(1)
        val nextProgressGate = AtomicReference(firstOcrProgress to firstOcrContinue)
        val adapter = TesseractOcrAdapter(context) {
            nextProgressGate.getAndSet(null)?.let { (started, proceed) ->
                started.countDown()
                check(proceed.await(60, TimeUnit.SECONDS)) { "Test did not release OCR progress" }
            }
        }
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        val oldOcrAdapter = app.ocrAdapterOverrideForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        val fixtures = mutableListOf<StoredPage>()
        try {
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            app.ocrAdapterOverrideForTest = adapter
            val deletingPage = createOcrFixturePage(context, storage, selected = true, text = "Invoice 8472")
            val survivingPage = createOcrFixturePage(context, storage, selected = false, text = "Document 9351")
            val queuedPage = createOcrFixturePage(context, storage, selected = false, text = "Routing 6123")
            fixtures += listOf(deletingPage, survivingPage, queuedPage)

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            awaitCaptureReady(activeScenario)
            assertTrue("The first Page reaches native OCR progress", firstOcrProgress.await(90, TimeUnit.SECONDS))
            val device = UiDevice.getInstance(instrumentation)
            storage.setActivePage(deletingPage.pageId)
            activeScenario.onActivity { it.renderPages(storage.workspaceProjection()) }
            val pageThumbnailTag = WorkspaceHomeAdapter.HOME_THUMBNAIL_TAG_PREFIX + deletingPage.pageId.value
            activeScenario.onActivity { activity ->
                val thumbnail = findViewByTag(activity.window.decorView, pageThumbnailTag)
                    ?: throw AssertionError("The OCR Page must be attached to the visible Home rail")
                assertTrue("Home thumbnail is attached and shown", thumbnail.isAttachedToWindow && thumbnail.isShown)
                assertTrue("Active Home thumbnail opens its viewer", thumbnail.performClick())
            }
            assertTrue("The viewer exposes its attached per-Page Delete action",
                device.wait(Until.hasObject(By.desc(context.getString(R.string.delete_page))), 5_000L))
            device.findObject(By.desc(context.getString(R.string.delete_page))).click()
            awaitPageDeleteWithDiagnostics(activeScenario, storage, listOf(survivingPage.pageId, queuedPage.pageId))
            assertTrue("Undo remains available while source bytes remain retained",
                storage.artifactPath(deletingPage.artifactRelativePath).toFile().isFile)
            awaitCondition("Direct Delete offers Undo") {
                device.wait(Until.hasObject(By.text(context.getString(R.string.undo_delete))), 100L)
            }
            nextProgressGate.set(clearOcrProgress to clearOcrContinue)
            val directUndo = device.findObject(By.text(context.getString(R.string.undo_delete)))
            assertTrue("Direct Delete Undo action is enabled while visible", directUndo.isEnabled)
            directUndo.click()
            awaitCondition("Undo restores the deleted Page") {
                storage.workspaceProjection().pages.any { it.pageId == deletingPage.pageId }
            }
            firstOcrContinue.countDown()
            assertTrue("Queued OCR for a surviving Page begins after the deleted Page is canceled",
                clearOcrProgress.await(90, TimeUnit.SECONDS))

            openWorkspaceMenuForTest(activeScenario)
            activeScenario.onActivity { activity ->
                val clear = findViewByTag(nativeWorkspaceRoot(activity), "docscan-clear-workspace-button")
                    ?: throw AssertionError("Clear control is missing")
                assertTrue("Clear is attached to the visible workspace menu", clear.isAttachedToWindow && clear.isShown)
                assertTrue(clear.isEnabled)
                assertTrue(clear.performClick())
            }
            assertTrue("Clear confirmation appears", device.wait(Until.hasObject(
                By.text(context.getString(R.string.clear_workspace_confirmation)),
            ), 5_000L))
            device.findObject(By.text(context.getString(R.string.clear_workspace))).click()
            awaitCondition("Clear removes all workspace Pages while OCR is active") {
                storage.workspaceProjection().pages.isEmpty()
            }
            clearOcrContinue.countDown()
            val ocrIdle = CountDownLatch(1)
            app.enqueueOcr(PageId.new()) { _, _ -> ocrIdle.countDown() }
            assertTrue("The OCR executor becomes available after native cancellation", ocrIdle.await(30, TimeUnit.SECONDS))
            assertTrue("Canceled OCR never republishes a deleted Page", storage.workspaceProjection().pages.isEmpty())
            assertTrue("Clear retains the committed image artifact for undo", fixtures.all { page ->
                storage.artifactPath(page.artifactRelativePath).toFile().isFile
            })
            awaitCondition("Clear offers one Undo action") {
                device.wait(Until.hasObject(By.text(context.getString(R.string.undo_delete))), 100L)
            }
            val clearUndo = device.findObject(By.text(context.getString(R.string.undo_delete)))
            assertTrue("Clear Undo action is enabled while visible", clearUndo.isEnabled)
            clearUndo.click()
            awaitCondition("Clear Undo restores the workspace and restarts OCR for its Pages") {
                val restored = storage.workspaceProjection().pages.associateBy { it.pageId }
                restored.keys == fixtures.map { it.pageId }.toSet()
                    && restored[deletingPage.pageId]?.recognizedText?.contains("8472") == true
                    && restored[survivingPage.pageId]?.recognizedText?.contains("9351") == true
                    && restored[queuedPage.pageId]?.recognizedText?.contains("6123") == true
            }
        } finally {
            firstOcrContinue.countDown()
            clearOcrContinue.countDown()
            app.cancelOcrForPages(fixtures.map { it.pageId })
            scenario?.close()
            app.ocrAdapterOverrideForTest = oldOcrAdapter
            adapter.close()
            app.productStorageOverrideForTest = oldOverride
            app.productStorageProviderForTest = oldProvider
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun pageDragShowsInsertionMarkerCommitsOrderAndPreservesPageState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        val fixtures = mutableListOf<PageId>()
        try {
            val activity = awaitCaptureReady(scenario)
            val originalPages = storage.workspaceProjection().pages
            val first = createFixturePage(context, storage, selected = true)
            val second = createFixturePage(context, storage, selected = false)
            fixtures += first.pageId
            fixtures += second.pageId
            val feedbackCount = AtomicInteger()
            scenario.onActivity {
                it.uiHapticFeedback = { feedbackCount.incrementAndGet() }
                it.renderPages(storage.workspaceProjection())
            }
            openWorkspaceMenuForTest(scenario)
            scrollWorkspaceListToPositionForTest(scenario, 0)
            awaitCondition("Rendered workspace rows must be laid out") {
                var laidOut = false
                scenario.onActivity { current ->
                    val row = findViewByTag(
                        nativeWorkspaceRoot(current),
                        ProductActivity.PAGE_ROW_TAG_PREFIX + first.pageId.value,
                    )
                    laidOut = row?.isLaidOut == true && row.height > 0
                }
                laidOut
            }
            SystemClock.sleep(250L)
            val before = storage.workspaceProjection()
            val firstState = before.pages.single { it.pageId == first.pageId }
            val secondState = before.pages.single { it.pageId == second.pageId }
            val dragHandle = findViewByTag(
                scenarioView(activity),
                ProductActivity.PAGE_DRAG_HANDLE_TAG_PREFIX + first.pageId.value,
            )
            val targetRow = findViewByTag(
                scenarioView(activity),
                ProductActivity.PAGE_ROW_TAG_PREFIX + second.pageId.value,
            )
            val sourceRow = findViewByTag(
                scenarioView(activity),
                ProductActivity.PAGE_ROW_TAG_PREFIX + first.pageId.value,
            )
            assertNotNull("Each Page exposes a long-press drag handle", dragHandle)
            assertNotNull("Each Page row accepts a drop", targetRow)
            assertNotNull("Dragged Page has a visual moving state", sourceRow)

            val marker = findViewByTag(
                scenarioView(activity),
                ProductActivity.PAGE_DROP_AFTER_TAG_PREFIX + second.pageId.value,
            )
            assertNotNull("Target row has a visible insertion affordance", marker)
            dragAndDrop(
                source = dragHandle!!,
                sourceRow = sourceRow!!,
                target = targetRow!!,
                marker = marker!!,
            )
            awaitCondition("Drop must place the source Page after the target Page") {
                val orderedIds = storage.workspaceProjection().pages.map { it.pageId }
                orderedIds.indexOf(first.pageId) == orderedIds.indexOf(second.pageId) + 1
            }
            awaitCondition("The committed reorder must deliver its UI-thread completion feedback") {
                feedbackCount.get() == 1
            }
            val after = storage.workspaceProjection()
            val firstAfter = after.pages.single { it.pageId == first.pageId }
            val secondAfter = after.pages.single { it.pageId == second.pageId }
            assertEquals(firstState.selected, firstAfter.selected)
            assertEquals(secondState.selected, secondAfter.selected)
            assertEquals(firstState.imageVersionId, firstAfter.imageVersionId)
            assertEquals(secondState.imageVersionId, secondAfter.imageVersionId)
            assertEquals(firstState.artifactRelativePath, firstAfter.artifactRelativePath)
            assertEquals(secondState.artifactRelativePath, secondAfter.artifactRelativePath)
            assertEquals(firstState.artifactState, firstAfter.artifactState)
            assertEquals(secondState.artifactState, secondAfter.artifactState)
            assertEquals(firstState.contentRevision, firstAfter.contentRevision)
            assertEquals(secondState.contentRevision, secondAfter.contentRevision)
            assertEquals(firstState.recognizedText, firstAfter.recognizedText)
            assertEquals(secondState.recognizedText, secondAfter.recognizedText)
            assertEquals(before.activePageId, after.activePageId)
            assertEquals("Only the committed workspace reorder emits completion feedback", 1, feedbackCount.get())
            assertEquals(originalPages.size + 2, after.pages.size)
        } finally {
            scenario.close()
            fixtures.forEach { id ->
                storage.workspaceProjection().pages.singleOrNull { it.pageId == id }?.let(storage::deletePage)
            }
        }
    }

    @Test
    fun noOpDropLeavesOrderAndDoesNotEmitCompletionFeedback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = (context.applicationContext as DocScanApplication).productStorage
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        val fixtures = mutableListOf<PageId>()
        try {
            val activity = awaitCaptureReady(scenario)
            val page = createFixturePage(context, storage, selected = true)
            fixtures += page.pageId
            val originalOrder = storage.workspaceProjection().pages.map { it.pageId }
            val feedbackCount = AtomicInteger()
            scenario.onActivity {
                it.uiHapticFeedback = { feedbackCount.incrementAndGet() }
                it.movePageByDrop(page.pageId.value, page.pageId.value, afterTarget = true)
            }
            SystemClock.sleep(200)
            assertEquals("A drop onto the same Page is a no-op", 0, feedbackCount.get())
            assertEquals(originalOrder, storage.workspaceProjection().pages.map { it.pageId })
        } finally {
            scenario.close()
            fixtures.forEach { id ->
                storage.workspaceProjection().pages.singleOrNull { it.pageId == id }?.let(storage::deletePage)
            }
        }
    }

    private fun awaitCaptureReady(scenario: ActivityScenario<ProductActivity>): ProductActivity {
        var activity: ProductActivity? = null
        awaitCondition("Camera capture button must become ready") {
            scenario.onActivity { current -> activity = current }
            activity?.let { current ->
                findViewByTag(current.window.decorView, "docscan-capture-button")?.isEnabled == true
            } == true
        }
        return activity!!
    }

    private fun createFixturePage(
        context: Context,
        storage: ProductStorage,
        selected: Boolean,
        recognizedText: String? = "reorder fixture",
    ): StoredPage {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (selected) 0xffff0000.toInt() else 0xff0000ff.toInt())
        val encoded = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val artifact = storage.stageCapturedJpeg(ByteArrayInputStream(encoded))
        val pageId = PageId.new()
        storage.commitCapture(
            CaptureCommand(
                workspaceId = storage.workspaceId,
                pageId = pageId,
                imageVersionId = ImageVersionId.new(),
                artifact = artifact,
                selected = selected,
                makeActive = false,
                coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                processingDesiredState = ProductActivity.OCR_OPERATION_KEY,
                sourceProvenance = "INSTRUMENTATION_TEST",
            ),
        )
        val stored = storage.workspaceProjection().pages.single { it.pageId == pageId }
        recognizedText?.let { text ->
            storage.commitRecognition(
                RecognitionCandidate(
                    recognitionId = RecognitionId.new(),
                    workspaceId = storage.workspaceId,
                    pageId = stored.pageId,
                    imageVersionId = stored.imageVersionId,
                    contentRevision = stored.contentRevision,
                    desiredOperationKey = ProductActivity.OCR_OPERATION_KEY,
                    recognizedText = text,
                ),
            )
        }
        return storage.workspaceProjection().pages.single { it.pageId == pageId }
    }

    private fun createOcrFixturePage(context: Context, storage: ProductStorage, selected: Boolean, text: String): StoredPage {
        val bitmap = Bitmap.createBitmap(1800, 520, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textSize = 104f
        }
        canvas.drawText(text, 70f, 260f, paint)
        val encoded = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val artifact = storage.stageCapturedJpeg(ByteArrayInputStream(encoded))
        val pageId = PageId.new()
        storage.commitCapture(
            CaptureCommand(
                workspaceId = storage.workspaceId,
                pageId = pageId,
                imageVersionId = ImageVersionId.new(),
                artifact = artifact,
                selected = selected,
                makeActive = false,
                coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                processingDesiredState = ProductActivity.OCR_OPERATION_KEY,
                sourceProvenance = "INSTRUMENTATION_TEST",
            ),
        )
        return storage.workspaceProjection().pages.single { it.pageId == pageId }
    }

    private fun dragAndDrop(source: View, sourceRow: View, target: View, marker: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sourceDescription = instrumentation.runOnMainSyncValue {
            source.contentDescription.toString()
        }
        val targetBounds = instrumentation.runOnMainSyncValue {
            android.graphics.Rect().also(target::getGlobalVisibleRect)
        }
        assertTrue("The long-press drag handle is visible and described", source.isShown && sourceDescription.isNotBlank())
        val device = UiDevice.getInstance(instrumentation)
        val sourceBounds = instrumentation.runOnMainSyncValue {
            android.graphics.Rect().also(source::getGlobalVisibleRect)
        }
        assertTrue("The drag handle intersects the visible workspace viewport: $sourceBounds", !sourceBounds.isEmpty)
        val sourceCenter = android.graphics.Point(sourceBounds.centerX(), sourceBounds.centerY())
        val markerSeenDuringDrag = AtomicBoolean(false)
        val dragStarted = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val drag = FutureTask(Callable {
                device.drag(
                    sourceCenter.x,
                    sourceCenter.y,
                    targetBounds.centerX(),
                    targetBounds.top + (targetBounds.height() * 0.8f).toInt(),
                    80,
                )
            })
            executor.execute(drag)
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (!drag.isDone && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    if (sourceRow.alpha < 0.9f) dragStarted.set(true)
                    if (marker.visibility == View.VISIBLE) markerSeenDuringDrag.set(true)
                }
                SystemClock.sleep(10L)
            }
            drag.get()
            instrumentation.runOnMainSync {
                if (sourceRow.alpha < 0.9f) dragStarted.set(true)
                if (marker.visibility == View.VISIBLE) markerSeenDuringDrag.set(true)
            }
            assertTrue("The platform must start the long-press drag", dragStarted.get())
            assertTrue("The row displays the insertion marker during drag", markerSeenDuringDrag.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun <T> Instrumentation.runOnMainSyncValue(block: () -> T): T {
        var value: T? = null
        runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun visibleBoundsOnScreen(view: View, out: android.graphics.Rect): Boolean {
        val visible = view.getLocalVisibleRect(out)
        val position = IntArray(2); view.getLocationOnScreen(position); out.offset(position[0], position[1])
        return visible
    }

    private fun scenarioView(activity: ProductActivity): View = nativeWorkspaceRoot(activity)

    private data class ViewerAccessibilityTarget(
        val className: String,
        val label: String?,
        val widthPx: Int,
        val heightPx: Int,
    )

    private fun assertWorkspaceViewerAccessibility(context: Context) {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: throw AssertionError("Workspace Viewer accessibility window is unavailable")
        val targets = mutableListOf<ViewerAccessibilityTarget>()
        collectViewerAccessibilityTargets(root, targets)
        val inventory = targets.joinToString("\n") { target ->
            "${target.className} label=${target.label ?: "<missing>"} bounds=${target.widthPx}x${target.heightPx}"
        }
        val normalizedLabels = targets.mapNotNull { it.label?.trim()?.lowercase() }
        android.util.Log.i(
            "DocScanA11y",
            "Workspace Viewer inventory: density=${context.resources.displayMetrics.density}\n$inventory",
        )
        val unnamed = targets.filter { it.label.isNullOrBlank() }
        assertTrue("Every actionable Workspace Viewer target needs an accessible name:\n$inventory", unnamed.isEmpty())
        assertEquals(
            "Viewer action names must be distinct:\n$inventory",
            targets.size,
            normalizedLabels.toSet().size,
        )
        val expectedLabels = buildList {
            add(context.getString(R.string.viewer_previous))
            add(context.getString(R.string.viewer_next))
            add(context.getString(android.R.string.cancel))
            for (ordinal in 1..3) add(context.getString(R.string.include_page_in_save, ordinal))
            add(context.getString(R.string.active_page, 1))
            add(context.getString(R.string.page_image_description, 2))
            add(context.getString(R.string.page_image_description, 3))
        }.map { it.lowercase() }
        expectedLabels.forEach { expected ->
            assertTrue("Viewer is missing accessible target '$expected':\n$inventory", expected in normalizedLabels)
        }
        assertEquals("The three Page selection controls remain exposed:\n$inventory", 3,
            targets.count { it.className.endsWith("CheckBox") })
        assertEquals("Previous, Next and Close remain exposed:\n$inventory", 3,
            targets.count { it.className.endsWith("Button") })
        assertTrue("Each Page thumbnail remains actionable:\n$inventory",
            targets.count { it.className.endsWith("ImageView") } >= 3)
        val minimumSizePx = kotlin.math.ceil(48f * context.resources.displayMetrics.density).toInt()
        val undersized = targets.filter { it.widthPx < minimumSizePx || it.heightPx < minimumSizePx }
        assertTrue("Every actionable Viewer target must meet 48dp (${minimumSizePx}px):\n$inventory", undersized.isEmpty())
        assertNotNull(
            "Viewer page counter is accessible",
            findAccessibilityNode(root, context.getString(R.string.page_number, 1), useDescription = false),
        )
        assertNotNull(
            "Main Viewer image has an accessible description",
            findAccessibilityNode(root, context.getString(R.string.page_image_description, 1), useDescription = true),
        )
    }

    private fun collectViewerAccessibilityTargets(
        node: AccessibilityNodeInfo,
        targets: MutableList<ViewerAccessibilityTarget>,
    ) {
        val className = node.className?.toString().orEmpty()
        val isButton = className.endsWith("Button")
        val isCheckBox = className.endsWith("CheckBox")
        val isImage = className.endsWith("ImageView")
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 &&
            (node.isClickable || node.isLongClickable || isButton || isCheckBox)
        ) {
            val label = node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            targets += ViewerAccessibilityTarget(className, label, bounds.width(), bounds.height())
        }
        if (node.isVisibleToUser && isImage && node.isClickable && bounds.width() > 0 && bounds.height() > 0 &&
            targets.none { it.className == className && it.widthPx == bounds.width() && it.heightPx == bounds.height() &&
                it.label == node.contentDescription?.toString() }
        ) {
            targets += ViewerAccessibilityTarget(
                className,
                node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty),
                bounds.width(),
                bounds.height(),
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectViewerAccessibilityTargets(child, targets)
        }
    }

    private fun findAccessibilityNode(
        node: AccessibilityNodeInfo,
        expected: String,
        useDescription: Boolean,
    ): AccessibilityNodeInfo? {
        val actual = if (useDescription) node.contentDescription?.toString() else node.text?.toString()
        if (actual == expected) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findAccessibilityNode(child, expected, useDescription)?.let { return it }
        }
        return null
    }

    private fun activeWindowTextInventory(instrumentation: Instrumentation): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "<no active accessibility window>"
        val entries = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || description.isNotEmpty()) {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                entries += "${node.className} text=$text desc=$description visible=${node.isVisibleToUser} bounds=$bounds"
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        root.recycle()
        return entries.joinToString(" | ")
    }

    private fun findViewerView(activity: ProductActivity, tag: String): View? {
        val dialogField = ProductActivity::class.java.getDeclaredField("pageViewer").apply { isAccessible = true }
        val dialog = dialogField.get(activity) as? androidx.appcompat.app.AlertDialog ?: return null
        val decor = dialog.window?.decorView ?: return null
        return findViewByTag(decor, tag)
    }

    private fun findViewByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findViewByTag(root.getChildAt(index), tag)?.let { return it }
        }
        return null
    }

    private inline fun <reified T : View> findAncestor(view: View): T? {
        var current = view.parent
        while (current is View) {
            if (current is T) return current
            current = current.parent
        }
        return null
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError(description)
    }

    private fun awaitPageDeleteWithDiagnostics(
        scenario: ActivityScenario<ProductActivity>,
        storage: ProductStorage,
        expectedPageIds: List<app.docscan.core.model.PageId>,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (storage.workspaceProjection().pages.map { it.pageId } == expectedPageIds) return
            SystemClock.sleep(50L)
        }
        var activityState = "<activity unavailable>"
        scenario.onActivity { activity ->
            val busy = ProductActivity::class.java.getDeclaredField("externalWorkflowRunning")
                .apply { isAccessible = true }.get(activity) as AtomicBoolean
            val blocked = ProductActivity::class.java.getDeclaredField("workspaceActionBlocked")
                .apply { isAccessible = true }.getBoolean(activity)
            val status = ProductActivity::class.java.getDeclaredField("status")
                .apply { isAccessible = true }.get(activity) as TextView
            activityState = "busy=${busy.get()} blocked=$blocked status=${status.text}"
        }
        val threadDump = Thread.getAllStackTraces().entries
            .sortedBy { it.key.name }
            .joinToString("\n") { (thread, trace) ->
                "${thread.name} [${thread.state}]\n" + trace.take(10).joinToString("\n") { "  at $it" }
            }
        throw AssertionError(
            "Delete did not remove target under native OCR hold; " +
                "pageIds=${storage.workspaceProjection().pages.map { it.pageId }}; " +
                "$activityState\nthreads:\n$threadDump",
        )
    }

}
