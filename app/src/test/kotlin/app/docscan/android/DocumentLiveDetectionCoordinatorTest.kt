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
    fun `portrait viewport coordinator gives equal five pixel shifts equal filter response`() {
        val portrait = AnalysisBufferSpec(
            bufferWidth = 100,
            bufferHeight = 200,
            cropRect = BufferRect(0, 0, 100, 200),
            rotationDegrees = 0,
        )
        assertEquals(0.5, portrait.orientedAspectRatio, 0.0)
        val base = floatArrayOf(20f, 40f, 80f, 40f, 80f, 160f, 20f, 160f)

        fun smoothedAfterFivePixelShift(dx: Float, dy: Float): FloatArray {
            var now = 1_000L
            val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { now })
            val generation = coordinator.currentGeneration()
            assertEquals(LiveDetectionStatus.PROVISIONAL,
                coordinator.onFrameDetected(generation, now, base, 0.9, portrait).status)
            now += 10
            val confirmed = coordinator.onFrameDetected(generation, now, base, 0.9, portrait)
            assertEquals(LiveDetectionStatus.CONFIRMED, confirmed.status)
            val shifted = FloatArray(base.size) { index ->
                base[index] + if (index % 2 == 0) dx else dy
            }
            now += 10
            val updated = coordinator.onFrameDetected(generation, now, shifted, 0.9, portrait)
            assertEquals(LiveDetectionStatus.CONFIRMED, updated.status)
            return updated.quadInBufferPixels!!
        }

        val movedX = smoothedAfterFivePixelShift(dx = 5f, dy = 0f)
        val movedY = smoothedAfterFivePixelShift(dx = 0f, dy = 5f)
        val horizontalResponsePx = movedX[0] - base[0]
        val verticalResponsePx = movedY[1] - base[1]

        assertEquals("Same pixel displacement must select the same EMA response",
            horizontalResponsePx, verticalResponsePx, 0.02f)
        assertEquals(1.5f, horizontalResponsePx, 0.02f)
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
        val staleFirst = coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.90, spec)
        currentTime += 100L
        val staleSecond = coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.90, spec)
        assertEquals(LiveDetectionStatus.EXPIRED, staleFirst.status)
        assertEquals(LiveDetectionStatus.EXPIRED, staleSecond.status)
        assertEquals("Late callback from old generation must be ignored", LiveDetectionStatus.EXPIRED, coordinator.getPublishedState().status)

        // The caller must use the fresh generation for frames after toggling back on.
        val firstFresh = coordinator.onFrameDetected(newGen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        val secondFresh = coordinator.onFrameDetected(newGen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstFresh.status)
        assertEquals(LiveDetectionStatus.CONFIRMED, secondFresh.status)
        assertEquals(newGen, secondFresh.generationId)
    }

    @Test
    fun `t05 - jump to a new document is confirmed by two stable frames`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        assertEquals(
            LiveDetectionStatus.CONFIRMED,
            coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec).status,
        )

        currentTime += 100L
        val firstB = coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.80, spec)
        assertEquals("A single jump may hold A during its TTL", LiveDetectionStatus.HELD, firstB.status)

        currentTime += 100L
        val confirmedB = coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.80, spec)

        assertEquals("Two stable B observations must replace A", LiveDetectionStatus.CONFIRMED, confirmedB.status)
        assertTrue(confirmedB.isUsableForAgreement)
        assertEquals(
            CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(candidatePointsJump, spec),
            confirmedB.quadInNormalizedViewPort,
        )
    }

    @Test
    fun `provisional candidate separated by more than ttl is not confirmed`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 301L
        val lateSecond = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        assertNotEquals(LiveDetectionStatus.CONFIRMED, lateSecond.status)
    }

    @Test
    fun `confirmed state expires after a gap and next frame starts a new candidate`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        currentTime += 301L
        val expired = coordinator.onFrameMissing(gen, currentTime)
        assertEquals(LiveDetectionStatus.EXPIRED, expired.status)
        assertFalse(expired.isUsableForAgreement)

        currentTime += 1L
        val firstAfterGap = coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstAfterGap.status)
        currentTime += 100L
        val secondAfterGap = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, secondAfterGap.status)
    }

    @Test
    fun `missing frame breaks provisional confirmation streak`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        val firstCandidate = coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstCandidate.status)

        currentTime += 100L
        coordinator.onFrameMissing(gen, currentTime)
        currentTime += 100L
        val afterGap = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        assertEquals("A missing observation must break the two-frame streak", LiveDetectionStatus.PROVISIONAL, afterGap.status)
    }

    @Test
    fun `geometry change starts a fresh generation and rejects old geometry callbacks`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val oldGeneration = coordinator.currentGeneration()
        val initialFrameGeneration = requireNotNull(coordinator.generationForFrame(spec, oldGeneration))
        assertEquals(oldGeneration, initialFrameGeneration)
        coordinator.onFrameDetected(initialFrameGeneration, currentTime, candidatePoints1, 0.85, spec)

        val changedSpec = AnalysisBufferSpec(
            bufferWidth = 1920,
            bufferHeight = 1080,
            cropRect = BufferRect(left = 240, top = 0, right = 1680, bottom = 1080),
            rotationDegrees = 90,
        )
        val newGeneration = requireNotNull(coordinator.generationForFrame(changedSpec, oldGeneration))
        assertNotEquals(oldGeneration, newGeneration)
        currentTime += 100L
        val stale = coordinator.onFrameDetected(oldGeneration, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.IDLE, stale.status)
        assertEquals(newGeneration, stale.generationId)

        val pointsInChangedGeometry = floatArrayOf(
            400f, 100f,
            1500f, 100f,
            1500f, 900f,
            400f, 900f,
        )
        val firstNewGeometry = coordinator.onFrameDetected(newGeneration, currentTime, pointsInChangedGeometry, 0.85, changedSpec)
        currentTime += 100L
        val secondNewGeometry = coordinator.onFrameDetected(newGeneration, currentTime, pointsInChangedGeometry.clone(), 0.87, changedSpec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstNewGeometry.status)
        assertEquals(LiveDetectionStatus.CONFIRMED, secondNewGeometry.status)
        assertEquals(changedSpec, secondNewGeometry.analysisSpec)
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

        val publishedFirstX = coordinator.getPublishedState().quadInBufferPixels!![0]
        snapshot.quadInBufferPixels!![0] = -100f
        assertEquals(
            "Snapshot mutation must not mutate published state",
            publishedFirstX,
            coordinator.getPublishedState().quadInBufferPixels!![0],
            0f,
        )

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

    @Test
    fun `ut11 malformed duplicate or out of order frame cannot erase a newer publication`() {
        var now = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val generation = coordinator.currentGeneration()
        coordinator.onFrameDetected(generation, now, candidatePoints1, 0.85, spec)

        now = 1_100L
        val newest = coordinator.onFrameDetected(generation, now, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, newest.status)
        val beforeLateResult = coordinator.getPublishedState()

        // Timestamp 1,050 is older than the newest 1,100 frame, and the payload is malformed.
        val lateMalformed = coordinator.onFrameDetected(
            generationId = generation,
            frameTimestampMonotonicMs = 1_050L,
            rawBufferPoints = floatArrayOf(1f, 2f),
            rawScore = Double.NaN,
            spec = spec,
            processedTimestampMonotonicMs = 1_120L,
        )

        assertEquals(LiveDetectionStatus.CONFIRMED, coordinator.getPublishedState().status)
        assertEquals(beforeLateResult.publicationId, coordinator.getPublishedState().publicationId)
        assertEquals(beforeLateResult, coordinator.getPublishedState())
        assertEquals(beforeLateResult, lateMalformed)
        assertTrue(coordinator.isCurrent(newest))
    }

    @Test
    fun `ut13 default visual lease expires exactly at 200 milliseconds without a new callback`() {
        var now = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val generation = coordinator.currentGeneration()
        coordinator.onFrameDetected(generation, now, candidatePoints1, 0.85, spec)

        now = 1_050L
        val confirmed = coordinator.onFrameDetected(generation, now, candidatePointsClose, 0.87, spec)
        assertEquals(1_250L, confirmed.visualDeadlineMonotonicMs)
        assertEquals(LiveDetectionState.DEFAULT_MAX_TTL_MS, 200L)
        assertTrue(coordinator.canDisplay(confirmed, nowMonotonicMs = 1_249L))
        assertFalse(coordinator.canDisplay(confirmed, nowMonotonicMs = 1_250L))
    }

    @Test
    fun `ut14 delayed UI lease from an older publication cannot display after a newer one`() {
        var now = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val generation = coordinator.currentGeneration()
        coordinator.onFrameDetected(generation, now, candidatePoints1, 0.85, spec)

        now = 1_050L
        val olderPublication = coordinator.onFrameDetected(generation, now, candidatePointsClose, 0.87, spec)
        assertTrue(coordinator.canDisplay(olderPublication, nowMonotonicMs = now))

        now = 1_100L
        val newerPublication = coordinator.onFrameDetected(generation, now, candidatePointsClose, 0.88, spec)
        assertNotEquals(olderPublication.publicationId, newerPublication.publicationId)
        assertFalse(coordinator.isCurrent(olderPublication))
        assertFalse(coordinator.canDisplay(olderPublication, nowMonotonicMs = now))
        assertTrue(coordinator.canDisplay(newerPublication, nowMonotonicMs = now))
    }

    @Test
    fun `ut15 invalidation keeps a confirmed old publication from being displayed after mode returns`() {
        var now = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val oldGeneration = coordinator.currentGeneration()
        coordinator.onFrameDetected(oldGeneration, now, candidatePoints1, 0.85, spec)
        now = 1_050L
        val oldPublication = coordinator.onFrameDetected(oldGeneration, now, candidatePointsClose, 0.87, spec)
        assertTrue(coordinator.canDisplay(oldPublication, now))

        coordinator.invalidate()
        val newGeneration = coordinator.currentGeneration()
        assertFalse(coordinator.isCurrent(oldPublication))
        assertFalse(coordinator.canDisplay(oldPublication, now))

        now = 1_100L
        val newFirst = coordinator.onFrameDetected(newGeneration, now, candidatePoints1, 0.85, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, newFirst.status)
        assertFalse(coordinator.canDisplay(oldPublication, now))
    }

    @Test
    fun `ut16 geometry change rejects old space publication and old callbacks`() {
        var now = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val generation = coordinator.currentGeneration()
        val oldGeneration = requireNotNull(coordinator.generationForFrame(spec, generation))
        coordinator.onFrameDetected(oldGeneration, now, candidatePoints1, 0.85, spec)
        now = 1_050L
        val oldPublication = coordinator.onFrameDetected(oldGeneration, now, candidatePointsClose, 0.87, spec)
        assertTrue(coordinator.canDisplay(oldPublication, now))

        val changedSpec = AnalysisBufferSpec(
            bufferWidth = 1920,
            bufferHeight = 1080,
            cropRect = BufferRect(left = 240, top = 0, right = 1680, bottom = 1080),
            rotationDegrees = 90,
        )
        val changedGeneration = requireNotNull(coordinator.generationForFrame(changedSpec, oldGeneration))
        assertNotEquals(oldGeneration, changedGeneration)
        assertFalse(coordinator.canDisplay(oldPublication, now))
        val stale = coordinator.onFrameDetected(oldGeneration, now + 10L, candidatePoints1, 0.9, spec)
        assertEquals(LiveDetectionStatus.IDLE, stale.status)
        assertFalse(coordinator.isCurrent(oldPublication))
    }

    @Test
    fun `ut17 detector result processed at the ttl boundary is discarded instead of promoted`() {
        val now = 1_200L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val generation = coordinator.currentGeneration()
        coordinator.onFrameDetected(generation, 1_000L, candidatePoints1, 0.85, spec,
            processedTimestampMonotonicMs = 1_000L)

        val late = coordinator.onFrameDetected(generation, 1_001L, candidatePointsClose, 0.87, spec,
            processedTimestampMonotonicMs = 1_201L)

        assertEquals(LiveDetectionStatus.EXPIRED, late.status)
        assertFalse(late.isUsableForAgreement)
        assertFalse(coordinator.canDisplay(late, nowMonotonicMs = now))
        assertEquals(generation, late.generationId)
    }

    @Test
    fun `ut20 input and returned coordinate arrays cannot mutate published state`() {
        var now = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { now })
        val generation = coordinator.currentGeneration()
        val submitted = candidatePoints1.clone()
        val originalFirstX = submitted[0]

        val returned = coordinator.onFrameDetected(generation, now, submitted, 0.85, spec)
        submitted[0] = -500f
        returned.quadInBufferPixels!![0] = -600f

        val published = coordinator.getPublishedState()
        assertEquals(originalFirstX, published.quadInBufferPixels!![0], 0f)
        assertEquals(LiveDetectionStatus.PROVISIONAL, published.status)
    }
}
