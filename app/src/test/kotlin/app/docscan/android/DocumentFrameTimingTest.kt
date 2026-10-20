package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentFrameTimingTest {

    @Test
    fun `ut12 unknown sensor clock does not produce a sensor capture age`() {
        val timing = DocumentFrameTiming(
            sensorTimestampNs = 1_000_000L,
            receivedTimestampNs = 1_100_000L,
            sensorClock = DocumentSensorClock.UNKNOWN,
        )

        assertNull(timing.sensorAgeNs(nowElapsedRealtimeNs = 1_200_000L))
        assertEquals(100_000L, timing.processingAgeNs(nowElapsedRealtimeNs = 1_200_000L))
    }

    @Test
    fun `realtime sensor clock permits age calculation and rejects future timestamps`() {
        val timing = DocumentFrameTiming(
            sensorTimestampNs = 1_000_000L,
            receivedTimestampNs = 1_100_000L,
            sensorClock = DocumentSensorClock.REALTIME,
        )

        assertEquals(200_000L, timing.sensorAgeNs(nowElapsedRealtimeNs = 1_200_000L))
        assertNull(timing.sensorAgeNs(nowElapsedRealtimeNs = 900_000L))
        assertNull(timing.processingAgeNs(nowElapsedRealtimeNs = 1_000_000L))
    }
}
