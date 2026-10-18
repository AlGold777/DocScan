package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

data class QuadAgreementResult(
    val agrees: Boolean,
    val iou: Double,
    val relativeCornerShift: Double,
    val maxCornerDistance: Double,
    val documentDiagonal: Double,
)

object QuadGeometryUtils {

    /**
     * Canonicalizes 4 corners into standard order:
     * [0] Top-Left (minimum x + y)
     * [1] Top-Right (clockwise)
     * [2] Bottom-Right (clockwise)
     * [3] Bottom-Left (clockwise)
     */
    fun canonicalizeCorners(points: List<NormalizedCropPoint>): List<NormalizedCropPoint> {
        require(points.size == 4) { "Must have exactly 4 points to canonicalize, got ${points.size}" }
        val cx = points.sumOf { it.x } / 4.0
        val cy = points.sumOf { it.y } / 4.0
        val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val tlIndex = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: 0
        return (0 until 4).map { clockwise[(tlIndex + it) % 4] }
    }

    fun canonicalize(quad: CropQuadrilateral): CropQuadrilateral {
        val c = canonicalizeCorners(quad.asList())
        return CropQuadrilateral(
            topLeft = c[0],
            topRight = c[1],
            bottomRight = c[2],
            bottomLeft = c[3],
        )
    }

    /**
     * Polygon area via the standard Shoelace formula.
     */
    fun polygonArea(points: List<NormalizedCropPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return abs(sum) / 2.0
    }

    /**
     * Exact convex polygon intersection via Sutherland-Hodgman clipping.
     */
    fun convexPolygonIntersection(
        subjectPolygon: List<NormalizedCropPoint>,
        clipPolygon: List<NormalizedCropPoint>,
    ): List<NormalizedCropPoint> {
        if (subjectPolygon.size < 3 || clipPolygon.size < 3) return emptyList()

        var outputList = subjectPolygon

        // Ensure clip polygon is oriented counter-clockwise
        val clip = ensureCounterClockwise(clipPolygon)

        for (i in clip.indices) {
            val edgeStart = clip[i]
            val edgeEnd = clip[(i + 1) % clip.size]
            val inputList = outputList
            outputList = mutableListOf()
            if (inputList.isEmpty()) break

            var prev = inputList.last()
            for (curr in inputList) {
                if (isInside(curr, edgeStart, edgeEnd)) {
                    if (isInside(prev, edgeStart, edgeEnd)) {
                        outputList.add(curr)
                    } else {
                        outputList.add(computeIntersection(prev, curr, edgeStart, edgeEnd))
                        outputList.add(curr)
                    }
                } else if (isInside(prev, edgeStart, edgeEnd)) {
                    outputList.add(computeIntersection(prev, curr, edgeStart, edgeEnd))
                }
                prev = curr
            }
        }
        return outputList
    }

    private fun isInside(p: NormalizedCropPoint, a: NormalizedCropPoint, b: NormalizedCropPoint): Boolean {
        // Cross product of (b - a) and (p - a) >= -1e-9 for counter-clockwise boundary
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        return cross >= -1e-9
    }

    private fun computeIntersection(
        p1: NormalizedCropPoint,
        p2: NormalizedCropPoint,
        cp1: NormalizedCropPoint,
        cp2: NormalizedCropPoint,
    ): NormalizedCropPoint {
        val a1 = p2.y - p1.y
        val b1 = p1.x - p2.x
        val c1 = a1 * p1.x + b1 * p1.y

        val a2 = cp2.y - cp1.y
        val b2 = cp1.x - cp2.x
        val c2 = a2 * cp1.x + b2 * cp1.y

        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-12) return p1 // Parallel fallback

        val x = (b2 * c1 - b1 * c2) / det
        val y = (a1 * c2 - a2 * c1) / det
        return NormalizedCropPoint(x, y)
    }

    private fun ensureCounterClockwise(points: List<NormalizedCropPoint>): List<NormalizedCropPoint> {
        var signedArea = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            signedArea += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return if (signedArea >= 0.0) points else points.reversed()
    }

    /**
     * Polygon Intersection-over-Union (IoU) for two quadrilaterals.
     */
    fun polygonIoU(q1: CropQuadrilateral, q2: CropQuadrilateral): Double {
        val pts1 = q1.asList()
        val pts2 = q2.asList()
        val area1 = polygonArea(pts1)
        val area2 = polygonArea(pts2)
        if (area1 <= 0.0 || area2 <= 0.0) return 0.0

        val intersectionPolygon = convexPolygonIntersection(pts1, pts2)
        val intersectionArea = polygonArea(intersectionPolygon)
        val unionArea = area1 + area2 - intersectionArea
        if (unionArea <= 1e-9) return 0.0
        return (intersectionArea / unionArea).coerceIn(0.0, 1.0)
    }

    /**
     * Compares live quad vs still (JPEG) quad:
     * - Accounts for cyclic shifts to avoid false mismatches
     * - Measures maximum individual corner displacement relative to document diagonal
     * - Measures Polygon IoU
     * - Thresholds: maxCornerShift <= 6% of diagonal, IoU >= 0.80
     */
    fun checkAgreement(
        liveQuad: CropQuadrilateral,
        stillQuad: CropQuadrilateral,
        maxRelativeShiftTolerance: Double = 0.06,
        minIouTolerance: Double = 0.80,
    ): QuadAgreementResult {
        val live = canonicalizeCorners(liveQuad.asList())
        val still = canonicalizeCorners(stillQuad.asList())

        var bestShift = 0
        var minTotalDist = Double.MAX_VALUE
        var bestMaxCornerDist = Double.MAX_VALUE

        for (shift in 0 until 4) {
            var totalDist = 0.0
            var maxDist = 0.0
            for (i in 0 until 4) {
                val d = hypot(live[i].x - still[(i + shift) % 4].x, live[i].y - still[(i + shift) % 4].y)
                totalDist += d
                if (d > maxDist) maxDist = d
            }
            if (totalDist < minTotalDist) {
                minTotalDist = totalDist
                bestShift = shift
                bestMaxCornerDist = maxDist
            }
        }

        val d1 = hypot(still[2].x - still[0].x, still[2].y - still[0].y)
        val d2 = hypot(still[3].x - still[1].x, still[3].y - still[1].y)
        val docDiagonal = max(1e-4, max(d1, d2))

        val relativeCornerShift = bestMaxCornerDist / docDiagonal
        val iou = polygonIoU(liveQuad, stillQuad)

        val agrees = (relativeCornerShift <= maxRelativeShiftTolerance) && (iou >= minIouTolerance)

        return QuadAgreementResult(
            agrees = agrees,
            iou = iou,
            relativeCornerShift = relativeCornerShift,
            maxCornerDistance = bestMaxCornerDist,
            documentDiagonal = docDiagonal,
        )
    }
}
