package app.docscan.android

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.StoredPage
import app.docscan.core.store.WorkspaceProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WorkspaceThumbnailLoaderInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun productionDecoderSamplesOversizedSourceAndKeepsCacheWithinConfiguredBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = createJpeg(context.cacheDir, 1_200, 1_800)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val loader = WorkspaceThumbnailLoader(worker = worker, maxCacheBytes = 256L * 1024L)
        val image = onMain(instrumentation) { ImageView(context) }
        val key = WorkspaceThumbnailLoader.Key(
            PageId.new(), ImageVersionId.new(), source.length(), targetWidthPx = 72, targetHeightPx = 64,
        )
        try {
            onMain(instrumentation) { loader.bind(image, key, source) }
            await("The production thumbnail decode must complete") {
                onMain(instrumentation) { loader.pendingCount == 0 && loader.decodeCount == 1 }
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val bitmap = (image.drawable as? BitmapDrawable)?.bitmap
                assertNotNull("A committed oversized JPEG produces a thumbnail", bitmap)
                assertTrue("Decoded width is bounded by its requested target", bitmap!!.width <= key.targetWidthPx)
                assertTrue("Decoded height is bounded by its requested target", bitmap.height <= key.targetHeightPx)
                assertTrue("The production cache stays within its configured byte cap", loader.cachedBytes <= loader.maxCacheBytes)
            }
        } finally {
            onMain(instrumentation) { loader.close() }
            worker.shutdownNow()
            source.delete()
        }
    }

    @Test
    fun workerDecodeIsOffMainAndRebindOrCloseRecyclesStaleResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val worker = ManualExecutor()
        val mainPoster = ManualMainPoster()
        val decodeThread = AtomicReference<Thread?>()
        val decodedBitmaps = ConcurrentLinkedQueue<Bitmap>()
        val decodedFiles = ConcurrentLinkedQueue<String>()
        val loader = WorkspaceThumbnailLoader(
            worker = worker,
            mainPoster = mainPoster,
            maxCacheBytes = 128L,
            decode = { file, width, height ->
                decodeThread.set(Thread.currentThread())
                decodedFiles.add(file.name)
                Bitmap.createBitmap(minOf(width, 8), minOf(height, 8), Bitmap.Config.RGB_565).apply {
                    eraseColor(if (file.name == "second.jpg") Color.BLUE else Color.RED)
                    decodedBitmaps.add(this)
                }
            },
        )
        val firstView = onMain(instrumentation) { ImageView(context) }
        val firstFile = File("first.jpg")
        val secondFile = File("second.jpg")
        val reprocessedPageId = PageId.new()
        val firstKey = key(firstFile, reprocessedPageId)
        val secondKey = key(secondFile, reprocessedPageId)
        try {
            onMain(instrumentation) { loader.bind(firstView, firstKey, firstFile) }
            worker.runNext()
            val firstBitmap = decodedBitmaps.last()
            assertTrue("Thumbnail decoding runs away from the UI thread", decodeThread.get() !== Looper.getMainLooper().thread)

            // Keep the first completion queued while the view is rebound, then deliver both.
            onMain(instrumentation) { loader.bind(firstView, secondKey, secondFile) }
            worker.runNext()
            val secondBitmap = decodedBitmaps.last()
            mainPoster.drainOnMain(instrumentation)
            onMain(instrumentation) {
                assertSame("Only the latest binding may publish to the recycled target", secondBitmap,
                    (firstView.drawable as? BitmapDrawable)?.bitmap)
                assertTrue("The stale first completion is recycled", firstBitmap.isRecycled)
                assertTrue("The cache never exceeds its byte budget", loader.cachedBytes <= loader.maxCacheBytes)
            }

            val queuedView = onMain(instrumentation) { ImageView(context) }
            val cancelledFile = File("cancelled-before-decode.jpg")
            val currentFile = File("current-after-rebind.jpg")
            val queuedPageId = PageId.new()
            onMain(instrumentation) { loader.bind(queuedView, key(cancelledFile, queuedPageId), cancelledFile) }
            onMain(instrumentation) { loader.bind(queuedView, key(currentFile, queuedPageId), currentFile) }
            val decodeCountBeforeCancelledTask = decodedFiles.size
            worker.runNext()
            assertEquals("A queued request for a rebound row is cancelled before invoking the decoder",
                decodeCountBeforeCancelledTask, decodedFiles.size)
            worker.runNext()
            assertTrue("The current binding is the only queued request decoded", decodedFiles.contains(currentFile.name))
            mainPoster.drainOnMain(instrumentation)
            onMain(instrumentation) {
                val currentBitmap = (queuedView.drawable as? BitmapDrawable)?.bitmap
                assertSame("The queued current request publishes to its still-current view", decodedBitmaps.last(), currentBitmap)
                assertFalse("The canceled request never reached the decoder", decodedFiles.contains(cancelledFile.name))
            }

            val lateView = onMain(instrumentation) { ImageView(context) }
            val lateFile = File("late.jpg")
            onMain(instrumentation) { loader.bind(lateView, key(lateFile, PageId.new()), lateFile) }
            worker.runNext()
            val lateBitmap = decodedBitmaps.last()
            onMain(instrumentation) { loader.close() }
            mainPoster.drainOnMain(instrumentation)
            onMain(instrumentation) {
                assertNull("Closing invalidates callbacks already queued for the main thread", lateView.drawable)
                assertTrue("A completion arriving after close is recycled", lateBitmap.isRecycled)
                assertTrue("Close releases cached image bytes", loader.cachedBytes == 0L)
            }
        } finally {
            onMain(instrumentation) { loader.close() }
        }
    }

    @Test
    fun detailedAdapterBindsOnlyAttachedRowsAndDetachCancelsUnusedDecode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val worker = ManualExecutor()
        val mainPoster = ManualMainPoster()
        val decodedBitmap = AtomicReference<Bitmap?>()
        var decodeCount = 0
        val loader = WorkspaceThumbnailLoader(
            worker = worker,
            mainPoster = mainPoster,
            decode = { _, width, height ->
                decodeCount += 1
                Bitmap.createBitmap(minOf(width, 8), minOf(height, 8), Bitmap.Config.RGB_565).also(decodedBitmap::set)
            },
        )
        val pageA = StoredPage(PageId.new(), ImageVersionId.new(), "a.jpg", 0, true, 0, false, null, "COMMITTED")
        val pageB = StoredPage(PageId.new(), ImageVersionId.new(), "b.jpg", 1, true, 0, false, null, "COMMITTED")
        val projection = WorkspaceProjection(0, pageA.pageId, listOf(pageA, pageB))
        val files = mapOf(
            pageA.pageId to File(context.cacheDir, "detached-a.jpg"),
            pageB.pageId to File(context.cacheDir, "detached-b.jpg"),
        )
        val rowImages = mutableMapOf<PageId, ImageView>()
        val adapter = WorkspacePageAdapter(
            loader = loader,
            createRow = { rowProjection, index ->
                val page = rowProjection.pages[index]
                ImageView(context).apply {
                    tag = ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + page.pageId.value
                    rowImages[page.pageId] = this
                }
            },
            bindRowThumbnail = { row, rowProjection, index ->
                val page = rowProjection.pages[index]
                val image = row.findViewWithTag<ImageView>(
                    ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + page.pageId.value,
                )
                loader.bind(
                    requireNotNull(image),
                    WorkspaceThumbnailLoader.Key(page.pageId, page.imageVersionId, 1, 32, 32),
                    requireNotNull(files[page.pageId]),
                )
            },
        )
        val parent = onMain(instrumentation) { RecyclerView(context) }
        try {
            onMain(instrumentation) { adapter.submitProjection(projection) }
            // ListAdapter's first nonempty diff is asynchronous; wait outside the main thread.
            await("The adapter receives its two Page items") { adapter.itemCount == 2 }

            val attachedHolder = onMain(instrumentation) {
                adapter.onCreateViewHolder(parent, 0).also { adapter.onBindViewHolder(it, 0) }
            }
            assertEquals("Binding a detached/prefetched holder does not queue a thumbnail decode", 0, worker.size)
            onMain(instrumentation) { adapter.onViewAttachedToWindow(attachedHolder) }
            assertEquals("Attaching the row requests its visible thumbnail", 1, worker.size)
            worker.runNext()
            val completedBitmap = decodedBitmap.get() ?: error("Expected an attached-row bitmap")
            assertEquals("An attached row reaches the injected decoder", 1, decodeCount)

            // Detach before delivering the completion: the result must not be retained or shown.
            onMain(instrumentation) { adapter.onViewDetachedFromWindow(attachedHolder) }
            mainPoster.drainOnMain(instrumentation)
            onMain(instrumentation) {
                val image = requireNotNull(rowImages[pageA.pageId])
                assertNull("Detaching clears the row's drawable", image.drawable)
                assertTrue("A completion for a detached row is recycled", completedBitmap.isRecycled)
            }

            val cancelledHolder = onMain(instrumentation) {
                adapter.onCreateViewHolder(parent, 0).also { adapter.onBindViewHolder(it, 1) }
            }
            assertEquals("A second detached bind still does not enqueue work", 0, worker.size)
            onMain(instrumentation) { adapter.onViewAttachedToWindow(cancelledHolder) }
            assertEquals("Attach enqueues the second visible row", 1, worker.size)
            onMain(instrumentation) { adapter.onViewDetachedFromWindow(cancelledHolder) }
            worker.runNext()
            mainPoster.drainOnMain(instrumentation)
            assertEquals("A detached queued request is cancelled before decoder invocation", 1, decodeCount)
            onMain(instrumentation) {
                assertEquals("Detached rows release all loader target bindings", 0, loader.boundTargetCount)
                assertEquals("Detached requests settle after their cancellation callback", 0, loader.pendingCount)
            }
        } finally {
            onMain(instrumentation) { loader.close() }
        }
    }

    @Test
    fun outstandingThumbnailRequestsStayWithinSixteenAndCancelledWaitersSkipDecoder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val worker = ManualExecutor()
        val mainPoster = ManualMainPoster()
        var decodeCount = 0
        val loader = WorkspaceThumbnailLoader(
            worker = worker,
            mainPoster = mainPoster,
            decode = { _, width, height ->
                decodeCount += 1
                Bitmap.createBitmap(minOf(width, 8), minOf(height, 8), Bitmap.Config.RGB_565)
            },
        )
        val targets = onMain(instrumentation) { List(20) { ImageView(context) } }
        try {
            onMain(instrumentation) {
                targets.forEachIndexed { index, target ->
                    val file = File("bounded-$index.jpg")
                    loader.bind(target, key(file, PageId.new()), file)
                }
            }
            assertEquals("Only the fixed sixteen worker slots accept queued work", 16, worker.size)
            onMain(instrumentation) {
                assertEquals("Pending thumbnail jobs never exceed their fixed cap", 16, loader.pendingCount)
                assertEquals("All live targets, including waiters, remain bound", 20, loader.boundTargetCount)
                targets.drop(16).forEach(loader::unbind)
                loader.close()
            }
            while (worker.size > 0) worker.runNext()
            mainPoster.drainOnMain(instrumentation)
            assertEquals("Unused queued requests are cancelled before decode", 0, decodeCount)
            onMain(instrumentation) {
                assertEquals("Cancelled jobs release their target bindings", 0, loader.boundTargetCount)
                assertEquals("Cancelled jobs settle from the in-flight map", 0, loader.pendingCount)
            }
        } finally {
            onMain(instrumentation) { loader.close() }
        }
    }

    @Test
    fun twentyLiveThumbnailTargetsArePromotedThroughTheSixteenWorkerSlots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val worker = ManualExecutor()
        val mainPoster = ManualMainPoster()
        var decodeCount = 0
        val loader = WorkspaceThumbnailLoader(
            worker = worker,
            mainPoster = mainPoster,
            maxCacheBytes = 4L * 1024L,
            decode = { _, width, height ->
                decodeCount += 1
                Bitmap.createBitmap(minOf(width, 8), minOf(height, 8), Bitmap.Config.RGB_565)
            },
        )
        val targets = onMain(instrumentation) { List(20) { ImageView(context) } }
        try {
            onMain(instrumentation) {
                targets.forEachIndexed { index, target ->
                    val file = File("promotion-$index.jpg")
                    loader.bind(target, key(file, PageId.new()), file)
                }
                assertEquals("The first submitted batch is capped at sixteen", 16, worker.size)
                assertEquals("All twenty visible bindings remain eligible for promotion", 20, loader.boundTargetCount)
            }

            var largestWorkerBatch = worker.size
            var rounds = 0
            while (true) {
                while (worker.size > 0) {
                    largestWorkerBatch = maxOf(largestWorkerBatch, worker.size)
                    assertTrue("Submitted worker tasks remain bounded", worker.size <= 16)
                    worker.runNext()
                }
                mainPoster.drainOnMain(instrumentation)
                rounds += 1
                if (worker.size == 0 && loader.pendingCount == 0) break
                assertTrue("Live waiters make bounded progress without rebinding", rounds <= 4)
            }

            assertEquals("Every still-bound target eventually decoded exactly once", 20, decodeCount)
            assertTrue("No promotion exceeds the sixteen-worker cap", largestWorkerBatch <= 16)
            assertTrue("Every original target receives a bitmap without rebinding", targets.all { target ->
                (target.drawable as? BitmapDrawable)?.bitmap?.isRecycled == false
            })
            onMain(instrumentation) {
                assertEquals("All live requests settle after promotion", 0, loader.pendingCount)
                assertEquals("The shared thumbnail cache remains byte bounded", 20 * 8 * 8 * 2L,
                    loader.cachedBytes)
                assertTrue(loader.cachedBytes <= loader.maxCacheBytes)
            }
        } finally {
            onMain(instrumentation) { loader.close() }
        }
    }

    @Test
    fun realHomeAndLazyMenuHandle101PagesWithoutEagerOffscreenDecodes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as DocScanApplication
        val previousOverride = application.productStorageOverrideForTest
        val suffix = UUID.randomUUID().toString()
        val databaseName = "u06-large-home-$suffix.db"
        val artifactDirectory = "u06-large-home-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val pageIds = mutableListOf<PageId>()
        val jpegBytes = createJpegBytes(48, 64)
        repeat(101) { index ->
            val artifact = storage.stageCapturedJpeg(ByteArrayInputStream(jpegBytes))
            val pageId = PageId.new()
            storage.commitCapture(
                CaptureCommand(
                    workspaceId = storage.workspaceId,
                    pageId = pageId,
                    imageVersionId = ImageVersionId.new(),
                    artifact = artifact,
                    selected = true,
                    makeActive = index == 0,
                    coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                    processingDesiredState = ProductActivity.OCR_OPERATION_KEY,
                    sourceProvenance = "INSTRUMENTATION_TEST_U06_101_PAGE",
                ),
            )
            pageIds += pageId
        }
        val scenario: ActivityScenario<ProductActivity>
        application.productStorageOverrideForTest = storage
        scenario = ActivityScenario.launch(ProductActivity::class.java)
        try {
            await("The real Home camera screen must become ready with 101 Pages") {
                var ready = false
                scenario.onActivity { activity ->
                    val home = activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll")
                    ready = activity.window.decorView.findViewWithTag<View>("docscan-capture-button")?.isEnabled == true &&
                        home?.adapter?.itemCount == 101
                }
                ready
            }
            await("Visible Home thumbnails finish decoding off the main thread") {
                var settled = false
                scenario.onActivity { activity ->
                    settled = activity.workspaceThumbnailLoader.pendingCount == 0 &&
                        activity.workspaceThumbnailLoader.decodeCount > 0
                }
                settled
            }
            instrumentation.waitForIdleSync()
            var homeImageCount = 0
            var homeBoundCount = 0
            var homeBoundTargets = 0
            var afterMenuImageCount = 0
            var afterMenuCacheBytes = 0L
            val savedPagesBeforeMenu = storage.workspaceProjection().pages
            assertEquals(101, savedPagesBeforeMenu.size)
            assertTrue("The fixture has exactly 101 selected Pages", savedPagesBeforeMenu.all { it.selected })
            assertEquals("The workspace projection preserves admitted Page order", pageIds, savedPagesBeforeMenu.map { it.pageId })
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val home = requireNotNull(decor.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll"))
                val save = requireNotNull(decor.findViewWithTag<View>("docscan-save-button"))
                assertEquals("Home adapter represents every stable Page identity", 101, home.adapter?.itemCount)
                assertTrue("Home uses stable identities for recycled rows", home.adapter?.hasStableIds() == true)
                val adapter = requireNotNull(home.adapter)
                assertEquals("Home exposes a distinct stable ID for every Page", 101,
                    (0 until adapter.itemCount).map(adapter::getItemId).toSet().size)
                assertEquals("The first adapter identity matches the first ordered Page",
                    UUID.fromString(pageIds.first().value).mostSignificantBits, adapter.getItemId(0))
                assertTrue("Home attaches only viewport rows, not all 101 thumbnail views", home.childCount < 101)
                assertFalse("Save remains disabled above its 100-Page limit", save.isEnabled)
                homeImageCount = activity.workspaceThumbnailLoader.decodeCount
                homeBoundCount = home.childCount
                homeBoundTargets = activity.workspaceThumbnailLoader.boundTargetCount
                assertTrue("Home decoded only a subset of the offscreen Page set", homeImageCount < 101)
                assertTrue("The Activity-owned thumbnail cache respects its byte cap",
                    activity.workspaceThumbnailLoader.cachedBytes <= activity.workspaceThumbnailLoader.maxCacheBytes)
                assertNull("The detailed Menu page list is not built while the Menu is hidden",
                    activity.workspacePagesListForTest)
                home.scrollToPosition(100)
            }

            await("The Home rail can bind the last Page without binding all previous thumbnails") {
                var lastBound = false
                scenario.onActivity { activity ->
                    val home = activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll")
                    lastBound = home?.findViewWithTag<View>("docscan-home-page:${pageIds.last().value}") != null
                }
                lastBound
            }
            await("The newly visible last Page thumbnail finishes decoding") {
                var decoded = false
                scenario.onActivity { activity ->
                    decoded = activity.workspaceThumbnailLoader.pendingCount == 0 &&
                        activity.workspaceThumbnailLoader.decodeCount > homeImageCount
                }
                decoded
            }
            scenario.onActivity { activity ->
                val home = requireNotNull(activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll"))
                assertTrue("Scrolling to the last Page still attaches only a subset of rows", home.childCount < 101)
                assertTrue("Scrolling does not eagerly decode the 101-Page set", activity.workspaceThumbnailLoader.decodeCount < 101)
                home.scrollToPosition(0)
            }
            await("Scrolling the Home rail back to the first Page restores its visible binding") {
                var firstBound = false
                scenario.onActivity { activity ->
                    firstBound = activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll")
                        ?.findViewWithTag<View>("docscan-home-page:${pageIds.first().value}") != null
                }
                firstBound
            }

            scenario.onActivity { activity ->
                activity.showWorkspaceMenu()
            }
            await("Opening Menu binds only visible Page rows") {
                var ready = false
                scenario.onActivity { activity ->
                    val list = activity.workspacePagesListForTest
                    ready = list?.adapter?.itemCount == 101 && list.childCount > 0
                }
                ready
            }
            await("Visible Menu rows finish their thumbnail work") {
                var settled = false
                scenario.onActivity { activity ->
                    settled = activity.workspaceThumbnailLoader.pendingCount == 0
                }
                settled
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val list = requireNotNull(activity.workspacePagesListForTest)
                assertEquals("Menu list retains all workspace Pages", 101, list.adapter?.itemCount)
                assertTrue("Menu attaches only visible rows", list.childCount < 101)
                assertTrue("Visible Menu rows bind their own thumbnail targets",
                    activity.workspaceThumbnailLoader.boundTargetCount > homeBoundTargets)
                assertTrue("Opening Menu does not eagerly decode all Page images",
                    activity.workspaceThumbnailLoader.decodeCount < 101)
                assertTrue("Thumbnail bytes remain within the configured cache budget",
                    activity.workspaceThumbnailLoader.cachedBytes <= activity.workspaceThumbnailLoader.maxCacheBytes)
            }
            val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            device.pressBack()
            await("Menu dismisses and Home remains usable") {
                var dismissed = false
                scenario.onActivity { activity ->
                    dismissed = activity.window.decorView.findViewWithTag<View>("docscan-title")?.isLaidOut == true &&
                        activity.window.decorView.findViewWithTag<RecyclerView>("docscan-home-thumbnail-scroll")?.adapter?.itemCount == 101 &&
                        activity.workspacePagesListForTest == null &&
                        activity.workspaceThumbnailLoader.pendingCount == 0 &&
                        activity.workspaceThumbnailLoader.boundTargetCount == homeBoundTargets
                }
                dismissed
            }
            val afterMenu = storage.workspaceProjection().pages
            assertEquals("Opening Menu preserves stable Page order", pageIds, afterMenu.map { it.pageId })
            assertTrue("Opening Menu preserves all selection flags", afterMenu.all { it.selected })
            assertEquals("Opening Menu does not change Active Page", pageIds.first(), storage.workspaceProjection().activePageId)
            scenario.onActivity { activity ->
                afterMenuImageCount = activity.workspaceThumbnailLoader.decodeCount
                afterMenuCacheBytes = activity.workspaceThumbnailLoader.cachedBytes
            }

            scenario.onActivity { activity ->
                activity.showWorkspaceMenu()
            }
            await("Reopening Menu creates a fresh lazy list for the same 101 Pages") {
                var reopened = false
                scenario.onActivity { activity ->
                    val list = activity.workspacePagesListForTest
                    reopened = list?.adapter?.itemCount == 101 && list.childCount > 0
                }
                reopened
            }
            scenario.onActivity { activity ->
                val list = requireNotNull(activity.workspacePagesListForTest)
                assertTrue("Reopened Menu still attaches only visible rows", list.childCount < 101)
                assertTrue("Reopened Menu binds visible thumbnails without materializing offscreen rows",
                    activity.workspaceThumbnailLoader.boundTargetCount > homeBoundTargets)
                assertTrue("Reopened Menu keeps the shared cache within its byte budget",
                    activity.workspaceThumbnailLoader.cachedBytes <= activity.workspaceThumbnailLoader.maxCacheBytes)
            }
            device.pressBack()
            await("Reopened Menu releases its Page row thumbnail bindings") {
                var released = false
                scenario.onActivity { activity ->
                    released = activity.workspacePagesListForTest == null &&
                        activity.workspaceThumbnailLoader.boundTargetCount == homeBoundTargets &&
                        activity.workspaceThumbnailLoader.pendingCount == 0
                }
                released
            }
            assertEquals("Reopening and dismissing Menu preserves Page order", pageIds,
                storage.workspaceProjection().pages.map { it.pageId })
            assertTrue("Reopening Menu preserves Page selection", storage.workspaceProjection().pages.all { it.selected })
            assertEquals("Reopening Menu preserves Active Page", pageIds.first(), storage.workspaceProjection().activePageId)
            Log.i("DocScanU06", "101-page Home/Menu: homeVisible=$homeBoundCount homeDecoded=$homeImageCount " +
                "afterMenuDecoded=$afterMenuImageCount cacheBytes=$afterMenuCacheBytes")
        } finally {
            scenario.close()
            application.productStorageOverrideForTest = previousOverride
            storage.workspaceProjection().pages.forEach { page -> runCatching { storage.deletePage(page) } }
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
        }
    }

    private fun key(file: File, pageId: PageId): WorkspaceThumbnailLoader.Key =
        WorkspaceThumbnailLoader.Key(pageId, ImageVersionId.new(), 1L, 32, 32)

    private fun createJpeg(directory: File, width: Int, height: Int): File {
        val file = File(directory, "u06-thumbnail-${UUID.randomUUID()}.jpg")
        file.writeBytes(createJpegBytes(width, height))
        return file
    }

    private fun createJpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply { eraseColor(Color.LTGRAY) }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun <T> onMain(instrumentation: android.app.Instrumentation, block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out: $description")
    }

    private class ManualExecutor : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.add(command) }

        fun runNext() {
            val task = tasks.poll() ?: throw AssertionError("Expected one thumbnail decode task")
            val failure = AtomicReference<Throwable?>()
            val thread = Thread({
                try { task.run() } catch (caught: Throwable) { failure.set(caught) }
            }, "u06-thumbnail-test-worker")
            thread.start()
            thread.join(5_000)
            assertFalse("Thumbnail worker task finishes", thread.isAlive)
            failure.get()?.let { throw AssertionError("Thumbnail worker failed", it) }
        }
    }

    private class ManualMainPoster : (Runnable) -> Unit {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun invoke(task: Runnable) { tasks.add(task) }

        fun drainOnMain(instrumentation: android.app.Instrumentation) = instrumentation.runOnMainSync {
            while (true) {
                val task = tasks.poll() ?: break
                task.run()
            }
        }
    }
}
