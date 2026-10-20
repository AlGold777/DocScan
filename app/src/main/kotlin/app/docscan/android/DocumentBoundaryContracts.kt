package app.docscan.android

import org.opencv.core.Mat
import kotlin.math.abs

/** Pixel-centre coordinates [0,width-1] x [0,height-1], clockwise cyclic order. */
data class BoundaryPoint(val x: Double, val y: Double)

data class BoundaryQuad(val a: BoundaryPoint, val b: BoundaryPoint, val c: BoundaryPoint, val d: BoundaryPoint) {
    val points: List<BoundaryPoint> get() = listOf(a, b, c, d)
    init {
        val p = points
        require(p.all { it.x.isFinite() && it.y.isFinite() })
        val turns = p.indices.map { i ->
            val u = p[i]; val v = p[(i + 1) % 4]; val w = p[(i + 2) % 4]
            (v.x-u.x)*(w.y-v.y)-(v.y-u.y)*(w.x-v.x)
        }
        require(turns.all { it > 1e-6 } || turns.all { it < -1e-6 })
        require(abs(p.indices.sumOf { i -> p[i].x*p[(i+1)%4].y-p[(i+1)%4].x*p[i].y }) > 1e-4)
    }
    fun toFloatArray() = points.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
    fun inside(rect: BufferRect): Boolean = points.all {
        it.x >= rect.left && it.x <= rect.right - 1 && it.y >= rect.top && it.y <= rect.bottom - 1
    }
    companion object {
        fun from(points: FloatArray): BoundaryQuad {
            require(points.size == 8)
            val p = (0..3).map { BoundaryPoint(points[it*2].toDouble(), points[it*2+1].toDouble()) }
            return BoundaryQuad(p[0],p[1],p[2],p[3])
        }
    }
}

enum class BoundaryOutcome { FOUND, NO_DOCUMENT, AMBIGUOUS, PARTIAL, UNSUPPORTED, ERROR }
enum class BoundaryVisibility { FULL, OUT_OF_FRAME, UNKNOWN }
enum class BoundaryRefinement { ORIGINAL, REFINED, REJECTED }
data class BoundaryEdgeEvidence(val coverage: Double?, val occupiedBins: Int?, val residualPx: Double?)

data class DocumentHypothesis(
    val candidateId: Int,
    val quad: BoundaryQuad,
    val rawScore: Double,
    val documentPresence: Double? = null,
    val edges: List<BoundaryEdgeEvidence> = List(4) { BoundaryEdgeEvidence(null,null,null) },
    val visibility: BoundaryVisibility = BoundaryVisibility.UNKNOWN,
    val source: String,
    val refinement: BoundaryRefinement = BoundaryRefinement.ORIGINAL,
    val rankMargin: Double? = null,
    val rawQuad: BoundaryQuad = quad,
    val cornerUncertaintyPx: List<Double?> = List(4) { null },
) {
    init { require(rawScore.isFinite()); require(edges.size == 4); require(cornerUncertaintyPx.size == 4) }
}

data class BoundaryFrameId(val frameId: Long, val generationId: Long, val geometryId: Long)
/** Input Mat is borrowed synchronously; no reference may survive detect(). */
data class DocumentBoundaryInput(val identity: BoundaryFrameId, val spec: AnalysisBufferSpec,
                                 val timing: DocumentFrameTiming, val rgba: Mat)
data class DocumentBoundaryResult(val identity: BoundaryFrameId, val detectorId: String,
    val configVersion: String, val outcome: BoundaryOutcome,
    val candidates: List<DocumentHypothesis>, val detectorStartedNs: Long,
    val detectorFinishedNs: Long, val reason: String? = null)

interface DocumentBoundaryDetector : AutoCloseable {
    val detectorId: String
    val configVersion: String
    fun detect(input: DocumentBoundaryInput): DocumentBoundaryResult
}

/** Validates candidates before choosing a winner. Outside-ViewPort candidates cannot hide an inside one. */
object DocumentBoundarySelection {
    fun visible(result: DocumentBoundaryResult, rect: BufferRect): List<DocumentHypothesis> =
        result.candidates.filter { it.visibility != BoundaryVisibility.OUT_OF_FRAME && it.quad.inside(rect) }
}
