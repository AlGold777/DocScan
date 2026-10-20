package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class LatestDocumentPublicationTest {
    private class QueueHarness {
        val queue = ArrayDeque<Runnable>()
        val removed = mutableListOf<Runnable>()
        fun post(task: Runnable) { queue.addLast(task) }
        fun remove(task: Runnable) { removed += task; queue.remove(task) }
        fun runNext() { queue.removeFirst().run() }
    }

    @Test
    fun `many offers while main is blocked retain one queued task and apply only newest value`() {
        val harness = QueueHarness()
        val applied = mutableListOf<Int>()
        val publication = LatestDocumentPublication<Int>(harness::post, harness::remove, applied::add)

        repeat(20_000) { publication.offer(it) }

        assertEquals("Pending work is bounded to a single main-loop task", 1, harness.queue.size)
        assertTrue(applied.isEmpty())
        harness.runNext()
        assertEquals(listOf(19_999), applied)
        assertTrue(harness.queue.isEmpty())
    }

    @Test
    fun `an offer during apply is drained in a second task without losing the newer value`() {
        val harness = QueueHarness()
        val applied = mutableListOf<Int>()
        lateinit var publication: LatestDocumentPublication<Int>
        publication = LatestDocumentPublication(harness::post, harness::remove) { value ->
            applied += value
            if (value == 1) publication.offer(2)
        }

        publication.offer(1)
        harness.runNext()
        assertEquals(listOf(1), applied)
        assertEquals(1, harness.queue.size)
        harness.runNext()
        assertEquals(listOf(1, 2), applied)
        assertTrue(harness.queue.isEmpty())
    }

    @Test
    fun `clear discards pending value but keeps publisher reusable`() {
        val harness = QueueHarness()
        val applied = mutableListOf<Int>()
        val publication = LatestDocumentPublication<Int>(harness::post, harness::remove, applied::add)

        publication.offer(1)
        publication.clear()
        harness.runNext()
        assertTrue(applied.isEmpty())

        publication.offer(2)
        assertEquals(1, harness.queue.size)
        harness.runNext()
        assertEquals(listOf(2), applied)
    }

    @Test
    fun `close removes queued runnable and later offers can never publish`() {
        val harness = QueueHarness()
        val applied = mutableListOf<Int>()
        val publication = LatestDocumentPublication<Int>(harness::post, harness::remove, applied::add)

        publication.offer(1)
        val alreadyPosted = harness.queue.single()
        publication.close()
        assertTrue(harness.queue.isEmpty())
        assertTrue(harness.removed.contains(alreadyPosted))

        // A looper may already have dequeued a Runnable when removeCallbacks is called.
        alreadyPosted.run()
        publication.offer(2)
        assertTrue(applied.isEmpty())
        assertTrue(harness.queue.isEmpty())
        assertFalse(harness.removed.isEmpty())
    }
}
