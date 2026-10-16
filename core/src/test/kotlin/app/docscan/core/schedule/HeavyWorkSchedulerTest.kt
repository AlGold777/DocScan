package app.docscan.core.schedule

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyWorkSchedulerTest {
    @Test
    fun concurrentCallersNeverExceedSingleJobOrDecodedBudget() {
        val scheduler = HeavyWorkScheduler(maxConcurrentJobs = 1, decodedByteBudget = 100L)
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val active = AtomicInteger()
        val peak = AtomicInteger()

        repeat(8) {
            pool.execute {
                ready.countDown()
                start.await()
                scheduler.withAdmission(80L) {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { current -> maxOf(current, now) }
                    Thread.sleep(10L)
                    active.decrementAndGet()
                }
                finished.countDown()
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, peak.get())
    }

    @Test
    fun oversizedEstimateIsRejectedBeforeWorkStarts() {
        val scheduler = HeavyWorkScheduler(decodedByteBudget = 96L)
        var ran = false

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.withAdmission(97L) { ran = true }
        }

        assertEquals(false, ran)
    }

    @Test
    fun thrownWorkReleasesAdmissionForNextJob() {
        val scheduler = HeavyWorkScheduler(decodedByteBudget = 96L)

        assertThrows(IllegalStateException::class.java) {
            scheduler.withAdmission(96L) { error("decode failed") }
        }
        assertEquals("ok", scheduler.withAdmission(96L) { "ok" })
    }
}
