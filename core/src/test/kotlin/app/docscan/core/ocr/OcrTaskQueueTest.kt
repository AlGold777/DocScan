package app.docscan.core.ocr

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrTaskQueueTest {
    @Test
    fun `same request is deduplicated and newer request replaces running work`() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseOld = CountDownLatch(1)
        val newTaskRan = CountDownLatch(1)
        val stopped = AtomicInteger()
        try {
            val queue = OcrTaskQueue<String>(executor, stopActiveTask = {
                stopped.incrementAndGet()
                releaseOld.countDown()
            })
            assertTrue(queue.enqueue("page", requestKey = "1:1:eng") { cancelled ->
                while (!cancelled()) releaseOld.await(10, TimeUnit.MILLISECONDS)
            })
            assertFalse(queue.enqueue("page", requestKey = "1:1:eng") { error("duplicate ran") })
            assertTrue(queue.enqueue("page", requestKey = "1:1:rus") { newTaskRan.countDown() })

            assertTrue(newTaskRan.await(2, TimeUnit.SECONDS))
            queue.await(listOf("page"), 2_000)
            assertEquals(1, stopped.get())
        } finally {
            releaseOld.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacement is not submitted until active native cancellation finishes`() {
        val executor = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val oldStarted = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        try {
            val queue = OcrTaskQueue<String>(executor, stopActiveTask = {
                stopEntered.countDown()
                releaseStop.await(2, TimeUnit.SECONDS)
            })
            queue.enqueue("page", requestKey = "old") { cancelled ->
                oldStarted.countDown()
                while (!cancelled()) Thread.yield()
            }
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS))

            val replacement = caller.submit<Boolean> {
                queue.enqueue("page", replaceExisting = true, requestKey = "new") {
                    replacementStarted.countDown()
                }
            }
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            assertFalse("Replacement must remain queued until native stop returns", replacementStarted.await(100, TimeUnit.MILLISECONDS))
            releaseStop.countDown()

            assertTrue(replacement.get(2, TimeUnit.SECONDS))
            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            queue.await(listOf("page"), 2_000)
        } finally {
            releaseStop.countDown()
            caller.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacement of pending work cancels only the old future`() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseBlocker = CountDownLatch(1)
        val latestRan = CountDownLatch(1)
        val oldPendingRan = AtomicInteger()
        try {
            val queue = OcrTaskQueue<String>(executor, stopActiveTask = {})
            queue.enqueue("blocker") { releaseBlocker.await(2, TimeUnit.SECONDS) }
            queue.enqueue("page", requestKey = "old") { oldPendingRan.incrementAndGet() }
            queue.enqueue("page", requestKey = "new") { latestRan.countDown() }
            releaseBlocker.countDown()

            assertTrue(latestRan.await(2, TimeUnit.SECONDS))
            queue.await(listOf("blocker", "page"), 2_000)
            assertEquals(0, oldPendingRan.get())
        } finally {
            releaseBlocker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `await follows replacement and reports task failures`() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseOld = CountDownLatch(1)
        val latestStarted = CountDownLatch(1)
        val releaseLatest = CountDownLatch(1)
        try {
            val queue = OcrTaskQueue<String>(executor, stopActiveTask = { releaseOld.countDown() })
            queue.enqueue("page", requestKey = "old") { cancelled ->
                while (!cancelled()) releaseOld.await(10, TimeUnit.MILLISECONDS)
            }

            val waiter = Executors.newSingleThreadExecutor()
            try {
                val awaiting = waiter.submit { queue.await(listOf("page"), 2_000) }
                queue.enqueue("page", requestKey = "latest") {
                    latestStarted.countDown()
                    releaseLatest.await(2, TimeUnit.SECONDS)
                }
                assertTrue(latestStarted.await(2, TimeUnit.SECONDS))
                assertFalse(awaiting.isDone)
                releaseLatest.countDown()
                awaiting.get(2, TimeUnit.SECONDS)
            } finally {
                releaseLatest.countDown()
                waiter.shutdownNow()
            }

            queue.enqueue("failed", replaceExisting = true) { throw IllegalStateException("recognition failed") }
            assertFailsWith<IllegalStateException> { queue.await(listOf("failed"), 2_000) }
        } finally {
            releaseOld.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `await reports timeout and explicit cancellation`() {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            val queue = OcrTaskQueue<String>(executor, stopActiveTask = { release.countDown() })
            queue.enqueue("page") { release.await(2, TimeUnit.SECONDS) }
            assertFailsWith<TimeoutException> { queue.await(listOf("page"), 1) }

            queue.cancel(listOf("page"))
            assertFailsWith<CancellationException> { queue.await(listOf("page"), 2_000) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `completed failed request with same key can be retried`() {
        val executor = Executors.newSingleThreadExecutor()
        val retryRan = CountDownLatch(1)
        try {
            val queue = OcrTaskQueue<String>(executor, stopActiveTask = {})
            queue.enqueue("page", requestKey = "1:2:eng") { throw IllegalStateException("first attempt failed") }
            assertFailsWith<IllegalStateException> { queue.await(listOf("page"), 2_000) }

            assertTrue(queue.enqueue("page", requestKey = "1:2:eng") { retryRan.countDown() })
            assertTrue(retryRan.await(2, TimeUnit.SECONDS))
            queue.await(listOf("page"), 2_000)
        } finally {
            executor.shutdownNow()
        }
    }
}
