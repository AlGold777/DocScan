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
    val publicationId: Long = 0L,
    val visualDeadlineMonotonicMs: Long = frameTimestampMonotonicMs + DEFAULT_MAX_TTL_MS,
) {
    val isUsableForAgreement: Boolean
        get() = status == LiveDetectionStatus.CONFIRMED && quadInNormalizedViewPort != null

    fun isFresh(nowMonotonicMs: Long, maxTtlMs: Long = DEFAULT_MAX_TTL_MS): Boolean {
        return nowMonotonicMs >= frameTimestampMonotonicMs &&
            nowMonotonicMs - frameTimestampMonotonicMs < maxTtlMs &&
            nowMonotonicMs < visualDeadlineMonotonicMs
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
        if (publicationId != other.publicationId) return false
        if (visualDeadlineMonotonicMs != other.visualDeadlineMonotonicMs) return false
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
        result = 31 * result + publicationId.hashCode()
        result = 31 * result + visualDeadlineMonotonicMs.hashCode()
        return result
    }

    companion object {
        const val DEFAULT_MAX_TTL_MS = 200L

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

/** Serializes tracking events; published arrays are defensive copies. */
class DocumentLiveDetectionCoordinator(
    private val maxTtlMs: Long = LiveDetectionState.DEFAULT_MAX_TTL_MS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    init { require(maxTtlMs > 0) }

    private val generationSequence = AtomicLong(1L)
    @Volatile private var currentGenerationId = generationSequence.get()
    private val lock = Any()
    private var publishedState = LiveDetectionState.empty(currentGenerationId)
    private var activeSpec: AnalysisBufferSpec? = null
    private var provisionalPoints: FloatArray? = null
    private var provisionalQuad: CropQuadrilateral? = null
    private var provisionalTimestampMs: Long? = null
    private var confirmedPoints: FloatArray? = null
    private var confirmedQuad: CropQuadrilateral? = null
    private var confirmedTimestampMs: Long? = null
    private var confirmedScore = 0.0
    private var lastFrameTimestampMs: Long? = null
    private var publicationSequence = 0L

    fun currentGeneration(): Long = currentGenerationId

    fun newGeneration(): Long = synchronized(lock) { advanceGeneration() }

    fun invalidate() = synchronized(lock) {
        advanceGeneration()
        publishedState = publishedState.copy(status = LiveDetectionStatus.EXPIRED)
    }

    /** Binds a frame to its geometry without allowing an in-flight old session back in. */
    fun generationForFrame(spec: AnalysisBufferSpec, expectedGeneration: Long): Long? = synchronized(lock) {
        if (expectedGeneration != currentGenerationId) return@synchronized null
        if (activeSpec != null && activeSpec != spec) advanceGeneration()
        activeSpec = spec
        currentGenerationId
    }

    fun getPublishedState(): LiveDetectionState = synchronized(lock) { publishedState.detached() }

    /** Used both at UI submission and onDraw; old callbacks cannot clear or revive a newer lease. */
    fun isCurrent(state: LiveDetectionState): Boolean = synchronized(lock) {
        state.generationId == currentGenerationId && state.publicationId == publishedState.publicationId &&
            state.analysisSpec == publishedState.analysisSpec
    }

    fun canDisplay(state: LiveDetectionState, nowMonotonicMs: Long = clock()): Boolean = synchronized(lock) {
        isCurrent(state) && state.status in setOf(LiveDetectionStatus.CONFIRMED, LiveDetectionStatus.HELD) &&
            state.quadInBufferPixels != null && state.isFresh(nowMonotonicMs, maxTtlMs)
    }

    fun captureSnapshot(): LiveDetectionState = synchronized(lock) {
        val state = publishedState.detached()
        if (state.isUsableForAgreement && state.isFresh(clock(), maxTtlMs)) state
        else state.copy(status = LiveDetectionStatus.EXPIRED)
    }

    fun onFrameDetected(
        generationId: Long,
        frameTimestampMonotonicMs: Long,
        rawBufferPoints: FloatArray,
        rawScore: Double,
        spec: AnalysisBufferSpec,
        processedTimestampMonotonicMs: Long = clock(),
    ): LiveDetectionState = synchronized(lock) {
        if (generationId != currentGenerationId) return@synchronized publishedState.detached()
        // Callers may submit geometry directly (e.g. tests). Never smooth across spaces.
        if (activeSpec != null && activeSpec != spec) {
            advanceGeneration()
            activeSpec = spec
            return@synchronized publishedState.detached()
        }
        activeSpec = spec
        // Reject late/duplicate events before validating their payload. A bad old result must
        // never erase a newer detection or move the frame watermark to its completion time.
        if (lastFrameTimestampMs?.let { frameTimestampMonotonicMs <= it } == true)
            return@synchronized publishedState.detached()
        if (frameTimestampMonotonicMs < 0 || !rawScore.isFinite() ||
            processedTimestampMonotonicMs - frameTimestampMonotonicMs !in 0 until maxTtlMs) {
            return@synchronized onFrameMissing(currentGenerationId, frameTimestampMonotonicMs)
        }
        if (lastFrameTimestampMs?.let { frameTimestampMonotonicMs - it > maxTtlMs } == true)
            resetTracking()
        lastFrameTimestampMs = frameTimestampMonotonicMs

        val candidate = try {
            CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(rawBufferPoints, spec)
        } catch (_: IllegalArgumentException) {
            return@synchronized onFrameMissing(currentGenerationId, frameTimestampMonotonicMs)
        }
        if (!isRecent(confirmedTimestampMs, frameTimestampMonotonicMs)) {
            confirmedQuad = null
            confirmedPoints = null
        }
        if (!isRecent(provisionalTimestampMs, frameTimestampMonotonicMs)) clearProvisional()

        val confirmedAgreement = confirmedQuad?.let { QuadGeometryUtils.checkAgreement(it, candidate) }
        val provisionalAgreement = provisionalQuad?.let { QuadGeometryUtils.checkAgreement(it, candidate) }
        val reference = when {
            confirmedAgreement?.agrees == true -> confirmedPoints
            provisionalAgreement?.agrees == true -> provisionalPoints
            else -> null
        }
        if (reference != null) {
            val agreement = if (confirmedAgreement?.agrees == true) confirmedAgreement else provisionalAgreement!!
            val proposed = applyEma(reference, rawBufferPoints, agreement.relativeCornerShift.toFloat())
            // Reject a filter-induced non-convex shape rather than failing the camera session.
            val smoothed = try {
                CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(proposed, spec)
                proposed
            } catch (_: IllegalArgumentException) { rawBufferPoints.clone() }
            confirmedPoints = smoothed
            confirmedQuad = CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(smoothed, spec)
            confirmedTimestampMs = frameTimestampMonotonicMs
            confirmedScore = rawScore
            clearProvisional()
            publishedState = state(LiveDetectionStatus.CONFIRMED, spec, smoothed, confirmedQuad,
                rawScore, frameTimestampMonotonicMs, processedTimestampMonotonicMs)
        } else {
            provisionalPoints = rawBufferPoints.clone()
            provisionalQuad = candidate
            provisionalTimestampMs = frameTimestampMonotonicMs
            publishedState = if (confirmedQuad != null) {
                state(LiveDetectionStatus.HELD, spec, confirmedPoints, confirmedQuad, confirmedScore,
                    confirmedTimestampMs!!, processedTimestampMonotonicMs)
            } else {
                state(LiveDetectionStatus.PROVISIONAL, spec, provisionalPoints, candidate, rawScore,
                    frameTimestampMonotonicMs, processedTimestampMonotonicMs)
            }
        }
        publishedState.detached()
    }

    fun onFrameMissing(generationId: Long, nowMonotonicMs: Long = clock()): LiveDetectionState = synchronized(lock) {
        if (generationId != currentGenerationId) return@synchronized publishedState.detached()
        if (lastFrameTimestampMs?.let { nowMonotonicMs < it } == true)
            return@synchronized publishedState.detached()
        clearProvisional() // A missing observation breaks consecutive confirmation.
        lastFrameTimestampMs = nowMonotonicMs
        publishedState = if (confirmedQuad != null && isRecent(confirmedTimestampMs, nowMonotonicMs)) {
            state(LiveDetectionStatus.HELD, activeSpec, confirmedPoints, confirmedQuad, confirmedScore,
                confirmedTimestampMs!!, nowMonotonicMs)
        } else {
            resetTracking()
            LiveDetectionState.empty(currentGenerationId).copy(status = LiveDetectionStatus.EXPIRED,
                processedTimestampMonotonicMs = nowMonotonicMs)
        }
        publishedState.detached()
    }

    private fun isRecent(timestamp: Long?, now: Long): Boolean =
        timestamp != null && now - timestamp in 0 until maxTtlMs

    private fun advanceGeneration(): Long {
        currentGenerationId = generationSequence.incrementAndGet()
        resetTracking()
        activeSpec = null
        publishedState = LiveDetectionState.empty(currentGenerationId)
        return currentGenerationId
    }

    private fun clearProvisional() {
        provisionalPoints = null
        provisionalQuad = null
        provisionalTimestampMs = null
    }

    private fun resetTracking() {
        clearProvisional()
        confirmedPoints = null
        confirmedQuad = null
        confirmedTimestampMs = null
        confirmedScore = 0.0
        lastFrameTimestampMs = null
    }

    private fun state(status: LiveDetectionStatus, spec: AnalysisBufferSpec?, points: FloatArray?,
                      quad: CropQuadrilateral?, score: Double, timestamp: Long, processed: Long) =
        LiveDetectionState(currentGenerationId, status, spec, points?.clone(), quad, score, timestamp, processed,
            publicationId = ++publicationSequence,
            visualDeadlineMonotonicMs = if (timestamp > Long.MAX_VALUE - maxTtlMs) Long.MAX_VALUE else timestamp + maxTtlMs)

    private fun LiveDetectionState.detached() = copy(quadInBufferPixels = quadInBufferPixels?.clone())

    private fun applyEma(previous: FloatArray, incoming: FloatArray, displacement: Float): FloatArray {
        // Match cyclic corner numbering before interpolating near a 45-degree rotation.
        val shift = (0 until 4).minByOrNull { offset ->
            (0 until 4).sumOf { index ->
                val other = (index + offset) % 4
                hypot((previous[index * 2] - incoming[other * 2]).toDouble(),
                    (previous[index * 2 + 1] - incoming[other * 2 + 1]).toDouble())
            }
        } ?: 0
        val alpha = when {
            displacement < 0.015f -> 0.18f
            displacement < 0.04f -> 0.30f
            else -> 0.50f
        }
        return FloatArray(8) { i ->
            val source = ((i / 2 + shift) % 4) * 2 + i % 2
            previous[i] + alpha * (incoming[source] - previous[i])
        }
    }
}
