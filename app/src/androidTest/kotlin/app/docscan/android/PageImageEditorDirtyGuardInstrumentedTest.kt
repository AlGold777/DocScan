package app.docscan.android

import android.content.Context
import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.RecognitionCandidate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PageImageEditorDirtyGuardInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun visiblePageImageEditorControlsHaveAccessibleNamesAndAtLeast48DpBounds() {
        val fixture = createFixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = fixture.launch()
        try {
            openWorkspaceMenuForTest(scenario)
            scrollWorkspaceListToPositionForTest(scenario, 0)
            awaitCondition("Workspace Page is rendered") {
                var present = false
                scenario.onActivity { activity ->
                    present = nativeWorkspaceRoot(activity).findViewWithTag<View>(
                        ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                    ) != null
                }
                present
            }
            scenario.onActivity { activity ->
                nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                )?.performClick() ?: throw AssertionError("Page image edit action is missing")
            }
            assertTrue("Image editor opens", awaitText(device, fixture.context.getString(R.string.edit_image_title)))
            val rotate = device.findObject(By.text(buttonText(fixture.context, R.string.edit_rotate_clockwise)))
            awaitCondition("Image editor source becomes ready") {
                runCatching { rotate.isEnabled }.getOrDefault(false)
            }

            val targets = linkedMapOf<String, EditorActionTarget>()
            for (viewport in 0..2) {
                val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    ?: throw AssertionError("Editor accessibility window is unavailable at viewport $viewport")
                collectEditorActionTargets(root, targets)
                android.util.Log.i(
                    "DocScanA11y",
                    "Page image editor inventory: viewport=$viewport density=${fixture.context.resources.displayMetrics.density} targets=${targets.values}",
                )
                if (viewport == 2) break
                val scrollBounds = findScrollableBounds(root) ?: break
                val startY = (scrollBounds.bottom - fixture.context.resources.displayMetrics.density * 24).toInt()
                val endY = (scrollBounds.top + fixture.context.resources.displayMetrics.density * 24).toInt()
                device.swipe(scrollBounds.centerX(), startY, scrollBounds.centerX(), endY, 18)
                android.os.SystemClock.sleep(250)
            }

            val minimumSizePx = kotlin.math.ceil(48f * fixture.context.resources.displayMetrics.density).toInt()
            val inventory = targets.values.joinToString("\n") { target ->
                "${target.className} label=${target.label ?: "<missing>"} size=${target.widthPx}x${target.heightPx} enabled=${target.enabled}"
            }
            assertEquals("The editor inventory must contain its eight required controls:\n$inventory", 8, targets.size)
            val unnamed = targets.values.filter { it.label.isNullOrBlank() }
            assertTrue("Every interactive editor control needs a spoken name:\n$inventory", unnamed.isEmpty())
            val labelsByNormalizedName = targets.values.mapNotNull { it.label?.trim()?.lowercase() }
            assertEquals("Every editor control needs a distinct spoken name:\n$inventory", targets.size, labelsByNormalizedName.toSet().size)
            val undersized = targets.values.filter { it.widthPx < minimumSizePx || it.heightPx < minimumSizePx }
            assertTrue(
                "Every editor touch target must meet 48dp (${minimumSizePx}px at this density):\n$inventory",
                undersized.isEmpty(),
            )
            val labels = targets.values.mapNotNull { it.label }.map { it.lowercase() }
            listOf(
                R.string.edit_rotate_clockwise,
                R.string.edit_crop_four_points,
                R.string.edit_undo,
                R.string.edit_brightness,
                R.string.edit_contrast,
                R.string.edit_sharpness,
                R.string.edit_save,
                R.string.edit_cancel,
            ).map { fixture.context.getString(it).lowercase() }.forEach { expected ->
                assertTrue("Editor inventory is missing '$expected':\n$inventory", labels.any { expected in it })
            }
        } finally {
            scenario.close()
            fixture.restore()
        }
    }

    @Test
    fun visibleWorkspaceActionTargetsHaveAccessibleNamesAndAtLeast48DpBounds() {
        val fixture = createFixture()
        val scenario = fixture.launch()
        try {
            openWorkspaceMenuForTest(scenario)
            scrollWorkspaceListToPositionForTest(scenario, 0)
            awaitCondition("Workspace Page is rendered") {
                var present = false
                scenario.onActivity { activity ->
                    present = nativeWorkspaceRoot(activity).findViewWithTag<View>(
                        ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                    ) != null
                }
                present
            }
            val targets = mutableListOf<WorkspaceActionTarget>()
            scenario.onActivity { activity ->
                val pageRow = nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_ROW_TAG_PREFIX + fixture.page.pageId.value,
                ) ?: throw AssertionError("Workspace Page row is missing")
                assertTrue("Workspace Page row is attached after list scrolling", pageRow.isLaidOut)
                val editButton = nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                ) ?: throw AssertionError("Workspace Page edit action is missing")
                val actionRail = findAncestor<HorizontalScrollView>(editButton)
                    ?: throw AssertionError("Workspace Page actions have no horizontal scroll rail")
                actionRail.scrollTo(editButton.left, 0)
                collectWorkspaceActionTargets(nativeWorkspaceRoot(activity), targets)
            }
            val minimumSizePx = kotlin.math.ceil(48f * fixture.context.resources.displayMetrics.density).toInt()
            val inventory = targets.joinToString("\n") { target ->
                "${target.tag ?: "<no-tag>"} ${target.className} label=${target.label ?: "<missing>"} size=${target.widthPx}x${target.heightPx}"
            }
            android.util.Log.i(
                "DocScanA11y",
                "Workspace action target inventory: locale=${fixture.context.resources.configuration.locales[0]}, density=${fixture.context.resources.displayMetrics.density}, count=${targets.size}\n$inventory",
            )
            assertTrue("Workspace audit must see visible actionable controls", targets.isNotEmpty())
            assertTrue(
                "Workspace audit must include this Page's selection control",
                targets.any { it.tag == ProductActivity.WORKSPACE_PAGE_SELECTION_TAG_PREFIX + fixture.page.pageId.value },
            )
            assertTrue(
                "Workspace audit must include the active Page thumbnail",
                targets.any { it.tag == ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + fixture.page.pageId.value },
            )
            val unnamed = targets.filter { it.label.isNullOrBlank() }
            assertTrue("Every visible actionable Workspace control needs a spoken or visible name:\n$inventory", unnamed.isEmpty())
            val undersized = targets.filter { it.widthPx < minimumSizePx || it.heightPx < minimumSizePx }
            assertTrue(
                "Every visible actionable Workspace control needs at least 48dp bounds (${minimumSizePx}px at this density):\n$inventory",
                undersized.isEmpty(),
            )
        } finally {
            scenario.close()
            fixture.restore()
        }
    }

    @Test
    fun manualCropCornersAreSeparatelyAccessibleAndTheirActionCanBeSaved() {
        val fixture = createFixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = fixture.launch()
        try {
            awaitCondition("Workspace page is rendered") {
                var present = false
                scenario.onActivity { activity ->
                    present = activity.window.decorView.findViewWithTag<android.view.View>(
                        ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                    ) != null
                }
                present
            }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<android.view.View>(
                    ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                )?.performClick() ?: throw AssertionError("Page image edit action is missing")
            }
            assertTrue("Image editor opens", awaitText(device, buttonText(fixture.context, R.string.edit_crop_four_points)))
            device.findObject(By.text(buttonText(fixture.context, R.string.edit_crop_four_points))).click()
            assertTrue("Manual crop preview becomes available", awaitText(device, fixture.context.getString(R.string.manual_crop_instruction)))

            val cornerDescriptions = listOf(
                R.string.manual_crop_corner_top_left,
                R.string.manual_crop_corner_top_right,
                R.string.manual_crop_corner_bottom_right,
                R.string.manual_crop_corner_bottom_left,
            ).map(fixture.context::getString)
            cornerDescriptions.forEach { description ->
                assertNotNull("Activity exposes accessible crop corner: $description", device.wait(Until.findObject(By.desc(description)), 2_000))
            }

            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: throw AssertionError("Manual crop accessibility window is unavailable")
            val directionalActions = listOf(
                CropSelectionView.ACTION_MOVE_LEFT,
                CropSelectionView.ACTION_MOVE_RIGHT,
                CropSelectionView.ACTION_MOVE_UP,
                CropSelectionView.ACTION_MOVE_DOWN,
            )
            val directionalChanges = listOf(
                CropSelectionView.ACTION_MOVE_LEFT to (-1 to 0),
                CropSelectionView.ACTION_MOVE_RIGHT to (1 to 0),
                CropSelectionView.ACTION_MOVE_UP to (0 to -1),
                CropSelectionView.ACTION_MOVE_DOWN to (0 to 1),
            )
            val minimumTargetSizePx = kotlin.math.ceil(48f * fixture.context.resources.displayMetrics.density).toInt()
            cornerDescriptions.forEach { description ->
                val node = findNodeByDescription(root, description)
                    ?: throw AssertionError("Crop corner is missing from the accessibility tree: $description")
                assertTrue(
                    "$description exposes all directional actions",
                    directionalActions.all { action -> node.actionList.any { it.id == action } },
                )
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                assertTrue(
                    "$description meets the 48dp width target (${bounds.width()}px < ${minimumTargetSizePx}px; density=${fixture.context.resources.displayMetrics.density})",
                    bounds.width() >= minimumTargetSizePx,
                )
                assertTrue(
                    "$description meets the 48dp height target (${bounds.height()}px < ${minimumTargetSizePx}px; density=${fixture.context.resources.displayMetrics.density})",
                    bounds.height() >= minimumTargetSizePx,
                )
            }

            val centerApproach = listOf(
                cornerDescriptions[0] to listOf(CropSelectionView.ACTION_MOVE_RIGHT, CropSelectionView.ACTION_MOVE_DOWN),
                cornerDescriptions[1] to listOf(CropSelectionView.ACTION_MOVE_LEFT, CropSelectionView.ACTION_MOVE_DOWN),
                cornerDescriptions[2] to listOf(CropSelectionView.ACTION_MOVE_LEFT, CropSelectionView.ACTION_MOVE_UP),
                cornerDescriptions[3] to listOf(CropSelectionView.ACTION_MOVE_RIGHT, CropSelectionView.ACTION_MOVE_UP),
            )
            repeat(20) {
                centerApproach.forEach { (description, inwardActions) ->
                    inwardActions.forEach { action ->
                        assertTrue(
                            "$description accepts an inward action before direction coverage",
                            performCornerAction(description, action),
                        )
                    }
                }
            }

            cornerDescriptions.forEach { targetDescription ->
                directionalChanges.forEach { (action, direction) ->
                    val before = cornerDescriptions.associateWith(::accessibleCornerBounds)
                    val targetBefore = before.getValue(targetDescription)
                    val currentRoot = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                        ?: throw AssertionError("Manual crop accessibility window is unavailable")
                    val target = findNodeByDescription(currentRoot, targetDescription)
                        ?: throw AssertionError("Crop corner disappeared before action: $targetDescription")
                    assertTrue(
                        "$targetDescription responds to action $action",
                        target.performAction(action),
                    )
                    awaitCondition("$targetDescription action $action updates its accessible bounds") {
                        val current = accessibleCornerBounds(targetDescription)
                        when {
                            direction.first < 0 -> current.centerX() < targetBefore.centerX()
                            direction.first > 0 -> current.centerX() > targetBefore.centerX()
                            direction.second < 0 -> current.centerY() < targetBefore.centerY()
                            else -> current.centerY() > targetBefore.centerY()
                        }
                    }
                    val after = cornerDescriptions.associateWith(::accessibleCornerBounds)
                    val targetAfter = after.getValue(targetDescription)
                    when {
                        direction.first < 0 -> assertTrue(
                            "$targetDescription moves left: $targetBefore -> $targetAfter",
                            targetAfter.centerX() < targetBefore.centerX(),
                        )
                        direction.first > 0 -> assertTrue(
                            "$targetDescription moves right: $targetBefore -> $targetAfter",
                            targetAfter.centerX() > targetBefore.centerX(),
                        )
                        direction.second < 0 -> assertTrue(
                            "$targetDescription moves up: $targetBefore -> $targetAfter",
                            targetAfter.centerY() < targetBefore.centerY(),
                        )
                        direction.second > 0 -> assertTrue(
                            "$targetDescription moves down: $targetBefore -> $targetAfter",
                            targetAfter.centerY() > targetBefore.centerY(),
                        )
                    }
                    cornerDescriptions.filterNot { it == targetDescription }.forEach { otherDescription ->
                        assertEquals(
                            "$targetDescription action $action leaves $otherDescription fixed",
                            before.getValue(otherDescription),
                            after.getValue(otherDescription),
                        )
                    }
                }
            }

            val beforeBounds = accessibleCornerBounds(cornerDescriptions.first())
            var validMoves = 1
            while (performCornerAction(cornerDescriptions.first(), CropSelectionView.ACTION_MOVE_RIGHT)) validMoves++
            assertTrue("Valid corner nudges work before the adjacent edge", validMoves > 1)
            val afterBounds = accessibleCornerBounds(cornerDescriptions.first())
            assertTrue("The action updates the control bounds to the moved corner", afterBounds.centerX() > beforeBounds.centerX())
            val topRightBounds = accessibleCornerBounds(cornerDescriptions[1])
            assertTrue("Rejected crossing leaves corners ordered", afterBounds.centerX() < topRightBounds.centerX())
            assertEquals(
                "Rejected crossing does not change the last valid top-left bounds",
                afterBounds,
                accessibleCornerBounds(cornerDescriptions.first()),
            )

            device.findObject(By.text(fixture.context.getString(R.string.manual_crop_apply))).click()
            assertTrue("Applying the crop returns to the editor", awaitText(device, fixture.context.getString(R.string.edit_save)))
            device.findObject(By.text(fixture.context.getString(R.string.edit_save))).click()
            awaitCondition("Saving the accessible crop commits a new image version") {
                fixture.storage.workspaceProjection().pages.singleOrNull { it.pageId == fixture.page.pageId }
                    ?.imageVersionId != fixture.page.imageVersionId
            }
            val saved = fixture.storage.workspaceProjection().pages.single { it.pageId == fixture.page.pageId }
            assertEquals(fixture.page.pageId, saved.pageId)
            assertNotEquals(fixture.page.imageVersionId, saved.imageVersionId)
            assertTrue("Saved crop leaves Undo available", saved.canUndoImage)
            assertTrue("Crop Save preserves the old immutable bytes", fixture.sourceBytes.contentEquals(
                fixture.storage.artifactPath(fixture.page.artifactRelativePath).toFile().readBytes(),
            ))
        } finally {
            scenario.close()
            fixture.restore()
        }
    }

    @Test
    fun keepEditingThenDiscardPreservesCommittedPageAndArtifact() {
        val fixture = createFixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        collapseNotificationShadeIfOpen(device)
        val scenario = fixture.launch()
        try {
            openDirtyPrompt(fixture, scenario, device)
            device.click(30, device.displayHeight / 2)
            assertTrue("Dirty prompt must remain open after an outside tap", awaitText(device, fixture.context.getString(R.string.edit_discard)))
            device.findObject(By.text(fixture.context.getString(R.string.edit_keep_editing))).click()
            assertTrue("Keep editing returns to the image editor", awaitText(device, fixture.context.getString(R.string.edit_image_title)))
            device.pressBack()
            assertTrue("Back from the dirty editor opens the discard prompt", awaitText(device, fixture.context.getString(R.string.edit_discard)))
            device.findObject(By.text(fixture.context.getString(R.string.edit_discard))).click()
            assertTrue("Discard closes the editor", device.wait(Until.gone(By.text(fixture.context.getString(R.string.edit_image_title))), 5_000))

            val after = fixture.storage.workspaceProjection()
            val page = after.pages.single { it.pageId == fixture.page.pageId }
            assertEquals(fixture.page, page)
            assertEquals(fixture.projection.activePageId, after.activePageId)
            assertEquals(fixture.referenceCount, referenceCount(fixture.context, fixture.databaseName, fixture.page.imageVersionId.value))
            assertEquals(fixture.sourceHash, sha256(fixture.storage.artifactPath(fixture.page.artifactRelativePath).toFile().readBytes()))
            assertTrue(fixture.sourceBytes.contentEquals(fixture.storage.artifactPath(fixture.page.artifactRelativePath).toFile().readBytes()))
        } finally {
            scenario.close()
            fixture.restore()
        }
    }

    @Test
    fun saveFromDirtyPromptCreatesUndoableVersionAndPreservesPriorArtifact() {
        val fixture = createFixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        collapseNotificationShadeIfOpen(device)
        val scenario = fixture.launch()
        try {
            openDirtyPrompt(fixture, scenario, device)
            device.findObject(By.text(fixture.context.getString(R.string.edit_save))).click()
            assertTrue("Save closes the editor after the immutable edit commits", device.wait(Until.gone(By.text(fixture.context.getString(R.string.edit_image_title))), 15_000))
            awaitCondition("Save from the prompt commits a new image version") {
                fixture.storage.workspaceProjection().pages.single { it.pageId == fixture.page.pageId }.imageVersionId != fixture.page.imageVersionId
            }

            val after = fixture.storage.workspaceProjection()
            val saved = after.pages.single { it.pageId == fixture.page.pageId }
            assertEquals(fixture.page.pageId, saved.pageId)
            assertNotEquals(fixture.page.imageVersionId, saved.imageVersionId)
            assertTrue("The previous image version is available to Undo", saved.canUndoImage)
            assertEquals(fixture.page.selected, saved.selected)
            assertEquals(fixture.projection.activePageId, after.activePageId)
            assertEquals(fixture.referenceCount, referenceCount(fixture.context, fixture.databaseName, fixture.page.imageVersionId.value))
            assertEquals(fixture.sourceHash, sha256(fixture.storage.artifactPath(fixture.page.artifactRelativePath).toFile().readBytes()))
            assertTrue(fixture.sourceBytes.contentEquals(fixture.storage.artifactPath(fixture.page.artifactRelativePath).toFile().readBytes()))
        } finally {
            scenario.close()
            fixture.restore()
        }
    }

    private fun openDirtyPrompt(fixture: Fixture, scenario: ActivityScenario<ProductActivity>, device: UiDevice) {
        awaitCondition("Workspace page is rendered") {
            var rendered = false
            scenario.onActivity { activity ->
                rendered = activity.window.decorView.findViewWithTag<android.view.View>(
                    ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
                ) != null
            }
            rendered
        }
        scenario.onActivity { activity ->
            activity.window.decorView.findViewWithTag<android.view.View>(
                ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + fixture.page.pageId.value,
            )?.performClick() ?: throw AssertionError("Page image edit action is missing")
        }
        assertTrue("Image editor opens", awaitText(device, buttonText(fixture.context, R.string.edit_rotate_clockwise)))
        device.findObject(By.text(buttonText(fixture.context, R.string.edit_rotate_clockwise))).click()
        assertTrue("Rotate makes the draft dirty", awaitText(device, fixture.context.getString(R.string.edit_adjustment_summary, 90, 0, 100, 0)))
        device.pressBack()
        assertTrue("Back opens the dirty-exit prompt", awaitText(device, fixture.context.getString(R.string.edit_discard)))
        assertNotNull("Prompt explains that the saved page is unchanged", device.findObject(By.text(fixture.context.getString(R.string.edit_discard_confirmation))))
    }

    private fun createFixture(): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "dirty-guard-$suffix.db"
        val storage = ProductStorage(context, databaseName, "dirty-guard-artifacts-$suffix")
        val previousOverride = application.productStorageOverrideForTest
        val previousProvider = application.productStorageProviderForTest
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage

        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        bitmap.recycle()
        val artifact = storage.stageCapturedJpeg(ByteArrayInputStream(bytes))
        val pageId = PageId.new()
        storage.commitCapture(
            CaptureCommand(
                workspaceId = storage.workspaceId,
                pageId = pageId,
                imageVersionId = ImageVersionId.new(),
                artifact = artifact,
                selected = true,
                makeActive = true,
                coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                processingDesiredState = ProductActivity.OCR_OPERATION_KEY,
                sourceProvenance = "TEST",
            ),
        )
        var page = storage.workspaceProjection().pages.single()
        storage.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = storage.workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = page.contentRevision,
                desiredOperationKey = ProductActivity.OCR_OPERATION_KEY,
                recognizedText = "dirty guard fixture",
            ),
        )
        page = storage.workspaceProjection().pages.single()
        val projection = storage.workspaceProjection()
        return Fixture(
            context = context,
            application = application,
            storage = storage,
            databaseName = databaseName,
            page = page,
            projection = projection,
            sourceBytes = bytes,
            sourceHash = sha256(bytes),
            referenceCount = referenceCount(context, databaseName, page.imageVersionId.value),
            previousOverride = previousOverride,
            previousProvider = previousProvider,
        )
    }

    private data class Fixture(
        val context: Context,
        val application: DocScanApplication,
        val storage: ProductStorage,
        val databaseName: String,
        val page: app.docscan.core.store.StoredPage,
        val projection: app.docscan.core.store.WorkspaceProjection,
        val sourceBytes: ByteArray,
        val sourceHash: String,
        val referenceCount: Long,
        val previousOverride: ProductStorage?,
        val previousProvider: (() -> ProductStorage)?,
    ) {
        fun launch(): ActivityScenario<ProductActivity> {
            val scenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario.onActivity { it.renderPages(storage.workspaceProjection()) }
            return scenario
        }

        fun restore() {
            application.productStorageProviderForTest = previousProvider
            application.productStorageOverrideForTest = previousOverride
        }
    }

    private fun referenceCount(context: Context, databaseName: String, imageVersionId: String): Long {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return database.use {
            it.rawQuery(
                "SELECT COUNT(*) FROM ArtifactReference WHERE artifact_id = (SELECT artifact_id FROM ImageVersion WHERE image_version_id = ?)",
                arrayOf(imageVersionId),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }

    private fun awaitText(device: UiDevice, text: String): Boolean {
        val found = device.wait(Until.hasObject(By.text(text)), 5_000)
        if (!found) {
            val hierarchy = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dirty-guard-window.xml")
            device.dumpWindowHierarchy(hierarchy)
            android.util.Log.e("DirtyGuardUI", "Expected text: $text\n${hierarchy.readText()}")
        }
        return found
    }

    private fun buttonText(context: Context, resourceId: Int): String = context.getString(resourceId).uppercase()

    private fun collapseNotificationShadeIfOpen(device: UiDevice) {
        repeat(2) {
            if (device.hasObject(By.res("com.android.systemui:id/notification_stack_scroller"))) {
                device.pressBack()
                android.os.SystemClock.sleep(300)
            }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            android.os.SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo,
        description: String,
    ): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNodeByDescription(child, description)?.let { return it }
        }
        return null
    }

    private data class EditorActionTarget(
        val className: String,
        val label: String?,
        val widthPx: Int,
        val heightPx: Int,
        val enabled: Boolean,
    )

    private fun collectEditorActionTargets(node: AccessibilityNodeInfo, targets: MutableMap<String, EditorActionTarget>) {
        val className = node.className?.toString().orEmpty()
        val isSeekBar = className.endsWith("SeekBar")
        val isButton = className.endsWith("Button")
        val rect = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser && rect.width() > 0 && rect.height() > 0 &&
            (node.isClickable || node.isLongClickable || isSeekBar || isButton)
        ) {
            val label = node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            val target = EditorActionTarget(className, label, rect.width(), rect.height(), node.isEnabled)
            val key = "$className|$label"
            val previous = targets[key]
            if (previous == null || (target.widthPx >= previous.widthPx && target.heightPx >= previous.heightPx)) {
                targets[key] = target
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectEditorActionTargets(child, targets)
        }
    }

    private fun findScrollableBounds(node: AccessibilityNodeInfo): Rect? {
        if (node.isVisibleToUser && node.isScrollable) return Rect().also(node::getBoundsInScreen)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findScrollableBounds(child)?.let { return it }
        }
        return null
    }

    private fun accessibleCornerBounds(description: String): android.graphics.Rect {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: throw AssertionError("Accessibility window is unavailable while reading $description")
        val node = findNodeByDescription(root, description)
            ?: throw AssertionError("Accessible crop corner is missing: $description")
        return android.graphics.Rect().also(node::getBoundsInScreen)
    }

    private fun performCornerAction(description: String, action: Int): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: throw AssertionError("Accessibility window is unavailable before action $action")
        val node = findNodeByDescription(root, description)
            ?: throw AssertionError("Accessible crop corner is missing before action $action: $description")
        return node.performAction(action)
    }

    private data class WorkspaceActionTarget(
        val tag: String?,
        val className: String,
        val label: String?,
        val widthPx: Int,
        val heightPx: Int,
    )

    private fun collectWorkspaceActionTargets(view: View, targets: MutableList<WorkspaceActionTarget>) {
        if (view.visibility != View.VISIBLE) return
        val visibleBounds = Rect()
        if ((view.isClickable || view.isLongClickable) && view.getGlobalVisibleRect(visibleBounds)) {
            val label = view.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: (view as? TextView)?.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            targets += WorkspaceActionTarget(
                tag = view.tag?.toString(),
                className = view.javaClass.simpleName,
                label = label,
                widthPx = view.width,
                heightPx = view.height,
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectWorkspaceActionTargets(view.getChildAt(index), targets)
        }
    }

    private inline fun <reified T : View> findAncestor(view: View): T? {
        var parent = view.parent
        while (parent is View) {
            if (parent is T) return parent
            parent = parent.parent
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
