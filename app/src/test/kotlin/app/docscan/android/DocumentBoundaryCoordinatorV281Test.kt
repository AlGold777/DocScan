package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentBoundaryCoordinatorV281Test {
    private val spec = AnalysisBufferSpec(
        bufferWidth = 1280,
        bufferHeight = 960,
        cropRect = BufferRect(160, 0, 1120, 960),
        rotationDegrees = 0,
    )
    private val quad = floatArrayOf(240f, 120f, 1000f, 120f, 1000f, 840f, 240f, 840f)
    private val nearQuad = floatArrayOf(242f, 121f, 998f, 121f, 998f, 839f, 242f, 839f)

    @Test
    fun `optical flow can retain a confirmed candidate but cannot confirm the first detector observation`() {
        var processingClock = 10_000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 1_000L, clock = { processingClock })
        val generation = coordinator.currentGeneration()

        val firstDetection = coordinator.onFrameDetected(generation, 1_000L, quad, 0.9, spec, 1_012L)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstDetection.status)
        val unconfirmedFlow = coordinator.onFrameTracked(generation, 1_050L, nearQuad, spec)
        assertEquals(LiveDetectionStatus.EXPIRED, unconfirmedFlow.status)
        assertFalse(unconfirmedFlow.isUsableForAgreement)

        val freshFirst = coordinator.onFrameDetected(generation, 1_100L, quad, 0.9, spec, 1_115L)
        assertEquals("Flow between observations must break rather than complete detector confirmation", LiveDetectionStatus.PROVISIONAL, freshFirst.status)
        val confirmed = coordinator.onFrameDetected(generation, 1_150L, nearQuad, 0.9, spec, 1_164L)
        assertEquals(LiveDetectionStatus.CONFIRMED, confirmed.status)
        assertEquals(1_150L, confirmed.lastDetectionTimestampMonotonicMs)
    }

    @Test
    fun `tracked visual timestamps renew only display freshness and stop at original detection plus 500ms`() {
        var processingClock = 5_000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 1_000L, clock = { processingClock })
        val generation = coordinator.currentGeneration()
        coordinator.onFrameDetected(generation, 1_000L, quad, 0.9, spec, 1_010L)
        val detection = coordinator.onFrameDetected(generation, 1_100L, nearQuad, 0.9, spec, 1_117L)
        assertEquals(LiveDetectionStatus.CONFIRMED, detection.status)

        processingClock = 5_123L
        val tracked = coordinator.onFrameTracked(generation, 1_200L, nearQuad, spec)
        assertEquals(LiveDetectionStatus.HELD, tracked.status)
        assertTrue(tracked.isTrackedGeometry)
        assertEquals(1_200L, tracked.frameTimestampMonotonicMs)
        assertEquals("Flow must not rewrite detector evidence", 1_100L, tracked.lastDetectionTimestampMonotonicMs)
        assertEquals("Processing time remains a separate semantic timestamp", 5_123L, tracked.processedTimestampMonotonicMs)
        assertEquals("Visual deadline is capped by original detection + 500ms", 1_600L, tracked.visualDeadlineMonotonicMs)
        assertTrue(coordinator.canDisplay(tracked, 1_599L))
        assertFalse(coordinator.canDisplay(tracked, 1_600L))

        processingClock = 5_200L
        val lastAllowed = coordinator.onFrameTracked(generation, 1_599L, nearQuad, spec)
        assertEquals(1_100L, lastAllowed.lastDetectionTimestampMonotonicMs)
        assertEquals(1_600L, lastAllowed.visualDeadlineMonotonicMs)
        assertFalse(coordinator.canDisplay(lastAllowed, 1_600L))

        val atDetectionLimit = coordinator.onFrameTracked(generation, 1_600L, nearQuad, spec)
        assertEquals("Tracking at the exact 500ms evidence boundary expires", LiveDetectionStatus.EXPIRED, atDetectionLimit.status)
        assertNull(atDetectionLimit.quadInBufferPixels)
    }

    @Test
    fun `partial and error detector outcomes clear confirmed live geometry`() {
        var processingClock = 2_000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 1_000L, clock = { processingClock })
        val generation = coordinator.currentGeneration()
        coordinator.onFrameDetected(generation, 1_000L, quad, 0.9, spec, 1_010L)
        val confirmed = coordinator.onFrameDetected(generation, 1_100L, nearQuad, 0.9, spec, 1_115L)
        assertEquals(LiveDetectionStatus.CONFIRMED, confirmed.status)

        processingClock = 2_120L
        val partial = coordinator.onFrameUnavailable(generation, 1_200L, LiveDetectionStatus.PARTIAL)
        assertEquals(LiveDetectionStatus.PARTIAL, partial.status)
        assertNull(partial.quadInBufferPixels)
        assertNull(partial.quadInNormalizedViewPort)
        assertFalse(coordinator.isCurrent(confirmed))

        processingClock = 2_220L
        val error = coordinator.onFrameUnavailable(generation, 1_300L, LiveDetectionStatus.ERROR)
        assertEquals(LiveDetectionStatus.ERROR, error.status)
        assertNull(error.quadInBufferPixels)
        assertNull(error.quadInNormalizedViewPort)
    }

    @Test
    fun `callbacks from an earlier generation cannot restore a confirmed publication`() {
        var processingClock = 3_000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 1_000L, clock = { processingClock })
        val oldGeneration = coordinator.currentGeneration()
        coordinator.onFrameDetected(oldGeneration, 1_000L, quad, 0.9, spec, 1_010L)
        val oldPublication = coordinator.onFrameDetected(oldGeneration, 1_100L, nearQuad, 0.9, spec, 1_115L)
        assertEquals(LiveDetectionStatus.CONFIRMED, oldPublication.status)

        val newGeneration = coordinator.newGeneration()
        assertTrue(newGeneration > oldGeneration)
        val staleTracked = coordinator.onFrameTracked(oldGeneration, 1_200L, quad, spec)
        val staleUnavailable = coordinator.onFrameUnavailable(oldGeneration, 1_300L, LiveDetectionStatus.ERROR)
        assertEquals(newGeneration, staleTracked.generationId)
        assertEquals(newGeneration, staleUnavailable.generationId)
        assertEquals(LiveDetectionStatus.IDLE, coordinator.getPublishedState().status)
        assertNull(coordinator.getPublishedState().quadInBufferPixels)
        assertFalse(coordinator.isCurrent(oldPublication))
    }
}
