package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

enum class LiveDetectionStatus {
    IDLE,
    PROVISIONAL,
    CONFIRMED,
    HELD,
    EXPIRED,
}

data class LiveDetectionState(
    val generationId: Long,
    val status: LiveDetectionStatus,
    val analysisSpec: AnalysisBufferSpec?,
    val quadInBufferPixels: FloatArray?,
    val quadInNormalizedViewPort: CropQuadrilateral?,
    val rawScore: Double,
    val frameTimestampMonotonicMs: Long,
    val processedTimestampMonotonicMs: Long,
) {
    val isUsableForAgreement: Boolean
        get() = status == LiveDetectionStatus.CONFIRMED && quadInNormalizedViewPort != null

    fun isFresh(nowMonotonicMs: Long, maxTtlMs: Long = DEFAULT_MAX_TTL_MS): Boolean {
        return (nowMonotonicMs - frameTimestampMonotonicMs) in 0..maxTtlMs
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LiveDetectionState
        if (generationId != other.generationId) return false
        if (status != other.status) return false
        if (analysisSpec != other.analysisSpec) return false
        if (quadInBufferPixels != null) {
            if (other.quadInBufferPixels == null) return false
            if (!quadInBufferPixels.contentEquals(other.quadInBufferPixels)) return false
        } else if (other.quadInBufferPixels != null) return false
        if (quadInNormalizedViewPort != other.quadInNormalizedViewPort) return false
        if (rawScore != other.rawScore) return false
        if (frameTimestampMonotonicMs != other.frameTimestampMonotonicMs) return false
        if (processedTimestampMonotonicMs != other.processedTimestampMonotonicMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = generationId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (analysisSpec?.hashCode() ?: 0)
        result = 31 * result + (quadInBufferPixels?.contentHashCode() ?: 0)
        result = 31 * result + (quadInNormalizedViewPort?.hashCode() ?: 0)
        result = 31 * result + rawScore.hashCode()
        result = 31 * result + frameTimestampMonotonicMs.hashCode()
        result = 31 * result + processedTimestampMonotonicMs.hashCode()
        return result
    }

    companion object {
        const val DEFAULT_MAX_TTL_MS = 300L

        fun empty(generationId: Long = 0L) = LiveDetectionState(
            generationId = generationId,
            status = LiveDetectionStatus.IDLE,
            analysisSpec = null,
            quadInBufferPixels = null,
            quadInNormalizedViewPort = null,
            rawScore = 0.0,
            frameTimestampMonotonicMs = 0L,
            processedTimestampMonotonicMs = 0L,
        )
    }
}

class DocumentLiveDetectionCoordinator(
    private val maxTtlMs: Long = LiveDetectionState.DEFAULT_MAX_TTL_MS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val generationSequence = AtomicLong(1L)
    @Volatile private var currentGenerationId = generationSequence.get()

    private val lock = Any()

    @Volatile private var publishedState: LiveDetectionState = LiveDetectionState.empty(currentGenerationId)

    // Internal state machine guarded by lock
    private var provisionalPoints: FloatArray? = null
    private var provisionalQuad: CropQuadrilateral? = null
    private var confirmedPoints: FloatArray? = null
    private var confirmedQuad: CropQuadrilateral? = null
    private var lastConfirmedTimestampMs: Long = 0L

    fun currentGeneration(): Long = currentGenerationId

    fun newGeneration(): Long {
        synchronized(lock) {
            val nextGen = generationSequence.incrementAndGet()
            currentGenerationId = nextGen
            resetInternalState()
            publishedState = LiveDetectionState.empty(nextGen)
            return nextGen
        }
    }

    fun invalidate() {
        synchronized(lock) {
            val nextGen = generationSequence.incrementAndGet()
            currentGenerationId = nextGen
            resetInternalState()
            publishedState = LiveDetectionState(
                generationId = nextGen,
                status = LiveDetectionStatus.EXPIRED,
                analysisSpec = null,
                quadInBufferPixels = null,
                quadInNormalizedViewPort = null,
                rawScore = 0.0,
                frameTimestampMonotonicMs = 0L,
                processedTimestampMonotonicMs = 0L,
            )
        }
    }

    fun getPublishedState(): LiveDetectionState = publishedState

    fun captureSnapshot(): LiveDetectionState {
        val state = publishedState
        val now = clock()
        return if (state.isUsableForAgreement && state.isFresh(now, maxTtlMs)) {
            state
        } else {
            state.copy(status = LiveDetectionStatus.EXPIRED)
        }
    }

    fun onFrameDetected(
        generationId: Long,
        frameTimestampMonotonicMs: Long,
        rawBufferPoints: FloatArray,
        rawScore: Double,
        spec: AnalysisBufferSpec,
        processedTimestampMonotonicMs: Long = clock(),
    ): LiveDetectionState {
        synchronized(lock) {
            if (generationId != currentGenerationId) {
                return publishedState
            }

            val candidateQuad = try {
                CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(rawBufferPoints, spec)
            } catch (e: Throwable) {
                return onFrameMissing(generationId, frameTimestampMonotonicMs)
            }

            val curConfirmed = confirmedQuad
            val curProvisional = provisionalQuad

            if (curConfirmed != null) {
                // Check agreement with already confirmed quad
                val agree = QuadGeometryUtils.checkAgreement(curConfirmed, candidateQuad)
                if (agree.agrees) {
                    val smoothedPoints = applyEma(confirmedPoints!!, rawBufferPoints, displacement = agree.relativeCornerShift.toFloat())
                    val smoothedQuad = CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(smoothedPoints, spec)
                    confirmedPoints = smoothedPoints
                    confirmedQuad = smoothedQuad
                    lastConfirmedTimestampMs = frameTimestampMonotonicMs
                    provisionalPoints = null
                    provisionalQuad = null

                    val nextState = LiveDetectionState(
                        generationId = generationId,
                        status = LiveDetectionStatus.CONFIRMED,
                        analysisSpec = spec,
                        quadInBufferPixels = smoothedPoints.clone(),
                        quadInNormalizedViewPort = smoothedQuad,
                        rawScore = rawScore,
                        frameTimestampMonotonicMs = frameTimestampMonotonicMs,
                        processedTimestampMonotonicMs = processedTimestampMonotonicMs,
                    )
                    publishedState = nextState
                    return nextState
                } else {
                    // Sudden jump: do not accept immediately as confirmed.
                    // Start provisional tracking. Keep previous confirmed as HELD temporarily if fresh.
                    provisionalPoints = rawBufferPoints.clone()
                    provisionalQuad = candidateQuad

                    val isStillFresh = (frameTimestampMonotonicMs - lastConfirmedTimestampMs) <= maxTtlMs
                    val nextStatus = if (isStillFresh) LiveDetectionStatus.HELD else LiveDetectionStatus.PROVISIONAL
                    val heldQuad = if (isStillFresh) confirmedQuad else candidateQuad
                    val heldPoints = if (isStillFresh) confirmedPoints else rawBufferPoints

                    val nextState = LiveDetectionState(
                        generationId = generationId,
                        status = nextStatus,
                        analysisSpec = spec,
                        quadInBufferPixels = heldPoints?.clone(),
                        quadInNormalizedViewPort = heldQuad,
                        rawScore = rawScore,
                        frameTimestampMonotonicMs = frameTimestampMonotonicMs,
                        processedTimestampMonotonicMs = processedTimestampMonotonicMs,
                    )
                    publishedState = nextState
                    return nextState
                }
            } else if (curProvisional != null) {
                // We have 1 provisional candidate; check if this second frame confirms it
                val agree = QuadGeometryUtils.checkAgreement(curProvisional, candidateQuad)
                if (agree.agrees) {
                    // Confirmed by two consecutive consistent observations!
                    val smoothedPoints = applyEma(provisionalPoints!!, rawBufferPoints, displacement = agree.relativeCornerShift.toFloat())
                    val smoothedQuad = CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(smoothedPoints, spec)
                    confirmedPoints = smoothedPoints
                    confirmedQuad = smoothedQuad
                    lastConfirmedTimestampMs = frameTimestampMonotonicMs
                    provisionalPoints = null
                    provisionalQuad = null

                    val nextState = LiveDetectionState(
                        generationId = generationId,
                        status = LiveDetectionStatus.CONFIRMED,
                        analysisSpec = spec,
                        quadInBufferPixels = smoothedPoints.clone(),
                        quadInNormalizedViewPort = smoothedQuad,
                        rawScore = rawScore,
                        frameTimestampMonotonicMs = frameTimestampMonotonicMs,
                        processedTimestampMonotonicMs = processedTimestampMonotonicMs,
                    )
                    publishedState = nextState
                    return nextState
                } else {
                    // Jumped again without confirmation; update provisional to the new candidate
                    provisionalPoints = rawBufferPoints.clone()
                    provisionalQuad = candidateQuad

                    val nextState = LiveDetectionState(
                        generationId = generationId,
                        status = LiveDetectionStatus.PROVISIONAL,
                        analysisSpec = spec,
                        quadInBufferPixels = rawBufferPoints.clone(),
                        quadInNormalizedViewPort = candidateQuad,
                        rawScore = rawScore,
                        frameTimestampMonotonicMs = frameTimestampMonotonicMs,
                        processedTimestampMonotonicMs = processedTimestampMonotonicMs,
                    )
                    publishedState = nextState
                    return nextState
                }
            } else {
                // Initial candidate: 1 frame seen -> PROVISIONAL (never CONFIRMED on first frame)
                provisionalPoints = rawBufferPoints.clone()
                provisionalQuad = candidateQuad

                val nextState = LiveDetectionState(
                    generationId = generationId,
                    status = LiveDetectionStatus.PROVISIONAL,
                    analysisSpec = spec,
                    quadInBufferPixels = rawBufferPoints.clone(),
                    quadInNormalizedViewPort = candidateQuad,
                    rawScore = rawScore,
                    frameTimestampMonotonicMs = frameTimestampMonotonicMs,
                    processedTimestampMonotonicMs = processedTimestampMonotonicMs,
                )
                publishedState = nextState
                return nextState
            }
        }
    }

    fun onFrameMissing(generationId: Long, nowMonotonicMs: Long = clock()): LiveDetectionState {
        synchronized(lock) {
            if (generationId != currentGenerationId) {
                return publishedState
            }

            val lastTime = lastConfirmedTimestampMs
            val confirmed = confirmedPoints
            val quad = confirmedQuad

            if (confirmed != null && quad != null && (nowMonotonicMs - lastTime) <= maxTtlMs) {
                // Held contour within TTL: dimmed/held, but NOT eligible for fresh auto-crop agreement
                val nextState = LiveDetectionState(
                    generationId = generationId,
                    status = LiveDetectionStatus.HELD,
                    analysisSpec = publishedState.analysisSpec,
                    quadInBufferPixels = confirmed.clone(),
                    quadInNormalizedViewPort = quad,
                    rawScore = publishedState.rawScore,
                    frameTimestampMonotonicMs = lastTime,
                    processedTimestampMonotonicMs = nowMonotonicMs,
                )
                publishedState = nextState
                return nextState
            } else {
                // TTL expired or no prior confirmed quad
                resetInternalState()
                val nextState = LiveDetectionState(
                    generationId = generationId,
                    status = LiveDetectionStatus.EXPIRED,
                    analysisSpec = null,
                    quadInBufferPixels = null,
                    quadInNormalizedViewPort = null,
                    rawScore = 0.0,
                    frameTimestampMonotonicMs = 0L,
                    processedTimestampMonotonicMs = nowMonotonicMs,
                )
                publishedState = nextState
                return nextState
            }
        }
    }

    private fun resetInternalState() {
        provisionalPoints = null
        provisionalQuad = null
        confirmedPoints = null
        confirmedQuad = null
        lastConfirmedTimestampMs = 0L
    }

    private fun applyEma(previous: FloatArray, incoming: FloatArray, displacement: Float): FloatArray {
        val alpha = when {
            displacement < 0.015f -> 0.18f
            displacement < 0.04f -> 0.30f
            else -> 0.50f
        }
        val result = FloatArray(8)
        for (i in 0 until 8) {
            result[i] = previous[i] + alpha * (incoming[i] - previous[i])
        }
        return result
    }
}
