package app.docscan.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClockTest {
    @Test
    fun fakeClockPreservesIndependentWallMonotonicAndEpochValues() {
        val initial = TimeReading(100L, 200L, "epoch-a")
        val clock = FakeClock(initial)

        assertEquals(initial, clock.read())
        val afterReboot = TimeReading(50L, 10L, "epoch-b")
        clock.set(afterReboot)
        assertEquals(afterReboot, clock.read())
    }

    @Test
    fun timeReadingsRejectMissingOrNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeReading(-1L, 0L, "epoch")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeReading(0L, 0L, " ")
        }
    }
}
