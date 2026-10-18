package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLiveDetectionCoordinatorTest {

    private val spec = AnalysisBufferSpec(
        bufferWidth = 1280,
        bufferHeight = 960,
        cropRect = BufferRect(left = 160, top = 0, right = 1120, bottom = 960),
        rotationDegrees = 0,
    )

    private val candidatePoints1 = floatArrayOf(
        200f, 100f,  // TL
        1000f, 100f, // TR
        1000f, 900f, // BR
        200f, 900f,  // BL
    )

    private val candidatePointsClose = floatArrayOf(
        202f, 101f,
        998f, 102f,
        1001f, 898f,
        199f, 901f,
    )

    private val candidatePointsJump = floatArrayOf(
        500f, 400f,
        800f, 400f,
        800f, 700f,
        500f, 700f,
    )

    @Test
    fun `t04 - initial single frame or false jump is provisional and not confirmed`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        // First frame
        val state1 = coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        assertEquals("Single frame must be PROVISIONAL", LiveDetectionStatus.PROVISIONAL, state1.status)
        assertFalse("Single frame must not be usable for agreement", state1.isUsableForAgreement)

        // Second frame: false jump
        currentTime += 100L
        val stateJump = coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.80, spec)
        assertEquals("Jumped frame must remain PROVISIONAL", LiveDetectionStatus.PROVISIONAL, stateJump.status)
        assertFalse("Jumped frame must not be usable for agreement", stateJump.isUsableForAgreement)
    }

    @Test
    fun `t05 - two consecutive consistent frames confirm the detection`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)

        currentTime += 100L
        val state2 = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals("Two consistent frames must be CONFIRMED", LiveDetectionStatus.CONFIRMED, state2.status)
        assertTrue("Confirmed detection must be usable for agreement", state2.isUsableForAgreement)
    }

    @Test
    fun `t06 - misses within ttl become held, and after ttl become expired`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        // Establish confirmed state
        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        // Miss at +100ms (within 300ms TTL)
        currentTime += 100L
        val heldState = coordinator.onFrameMissing(gen, currentTime)
        assertEquals("Miss within TTL must be HELD", LiveDetectionStatus.HELD, heldState.status)
        assertFalse("Held contour must not be usable for agreement", heldState.isUsableForAgreement)

        // Miss at +350ms (exceeding TTL)
        currentTime += 250L
        val expiredState = coordinator.onFrameMissing(gen, currentTime)
        assertEquals("Miss beyond TTL must be EXPIRED", LiveDetectionStatus.EXPIRED, expiredState.status)
        assertFalse(expiredState.isUsableForAgreement)
    }

    @Test
    fun `t07 - mode invalidation and old generation callbacks are ignored`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val oldGen = coordinator.currentGeneration()

        coordinator.onFrameDetected(oldGen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, coordinator.getPublishedState().status)

        // Mode toggled off or activity paused -> invalidate
        coordinator.invalidate()
        val newGen = coordinator.currentGeneration()
        assertNotEquals("Generation must increment on invalidate", oldGen, newGen)
        assertEquals(LiveDetectionStatus.EXPIRED, coordinator.getPublishedState().status)

        // Late callback from old generation arrives on background thread
        currentTime += 50L
        coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.90, spec)
        assertEquals("Late callback from old generation must be ignored", LiveDetectionStatus.EXPIRED, coordinator.getPublishedState().status)
    }

    @Test
    fun `captureSnapshot captures frozen immutable copy and rejects expired state`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 80L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.85, spec)

        val snapshot = coordinator.captureSnapshot()
        assertEquals(LiveDetectionStatus.CONFIRMED, snapshot.status)
        assertTrue(snapshot.isUsableForAgreement)

        // Later frames in background thread do not affect captured snapshot
        currentTime += 80L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.70, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, snapshot.status)

        // Expired snapshot
        currentTime += 500L
        val expiredSnapshot = coordinator.captureSnapshot()
        assertEquals(LiveDetectionStatus.EXPIRED, expiredSnapshot.status)
        assertFalse(expiredSnapshot.isUsableForAgreement)
    }
}
