package app.docscan.core.edit

/** Ordered normalized image-space corners: top-left, top-right, bottom-right, bottom-left. */
data class NormalizedCropPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite())
        require(x in 0.0..1.0 && y in 0.0..1.0)
    }
}

data class CropQuadrilateral(
    val topLeft: NormalizedCropPoint,
    val topRight: NormalizedCropPoint,
    val bottomRight: NormalizedCropPoint,
    val bottomLeft: NormalizedCropPoint,
) {
    init {
        val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
        val turns = points.indices.map { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        require(turns.all { it > MIN_CROSS } || turns.all { it < -MIN_CROSS }) {
            "Crop corners must form a non-degenerate convex quadrilateral"
        }
        val areaTwice = points.indices.sumOf { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            current.x * next.y - next.x * current.y
        }
        require(kotlin.math.abs(areaTwice) > MIN_AREA_TWICE) {
            "Crop area must be greater than zero"
        }
    }

    fun asList(): List<NormalizedCropPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Moves one of the 4 edges by the given normalized delta (dx, dy), clamping to bounds and preserving convexity. */
    fun withMovedEdge(edgeIndex: Int, dx: Double, dy: Double): CropQuadrilateral {
        require(edgeIndex in 0..3) { "edgeIndex must be in 0..3, was $edgeIndex" }
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0.0 && dy == 0.0)) return this

        val points = asList()
        val idx1 = edgeIndex
        val idx2 = (edgeIndex + 1) % 4
        val p1 = points[idx1]
        val p2 = points[idx2]

        val minDx = maxOf(-p1.x, -p2.x)
        val maxDx = minOf(1.0 - p1.x, 1.0 - p2.x)
        val clampedDx = dx.coerceIn(minDx, maxDx)

        val minDy = maxOf(-p1.y, -p2.y)
        val maxDy = minOf(1.0 - p1.y, 1.0 - p2.y)
        val clampedDy = dy.coerceIn(minDy, maxDy)

        if (clampedDx == 0.0 && clampedDy == 0.0) return this

        val initialTurnsPositive = run {
            val a = points[0]
            val b = points[1]
            val c = points[2]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0
        }

        fun tryConstruct(stepDx: Double, stepDy: Double): CropQuadrilateral? {
            return runCatching {
                val newP1 = NormalizedCropPoint(
                    (p1.x + stepDx).coerceIn(0.0, 1.0),
                    (p1.y + stepDy).coerceIn(0.0, 1.0),
                )
                val newP2 = NormalizedCropPoint(
                    (p2.x + stepDx).coerceIn(0.0, 1.0),
                    (p2.y + stepDy).coerceIn(0.0, 1.0),
                )
                val updated = points.toMutableList()
                updated[idx1] = newP1
                updated[idx2] = newP2
                val candTurnsPositive = run {
                    val a = updated[0]
                    val b = updated[1]
                    val c = updated[2]
                    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0
                }
                require(candTurnsPositive == initialTurnsPositive)
                CropQuadrilateral(updated[0], updated[1], updated[2], updated[3])
            }.getOrNull()
        }

        val full = tryConstruct(clampedDx, clampedDy)
        if (full != null) return full

        var low = 0.0
        var high = 1.0
        var best: CropQuadrilateral = this
        repeat(8) {
            val mid = (low + high) / 2.0
            val candidate = tryConstruct(clampedDx * mid, clampedDy * mid)
            if (candidate != null) {
                best = candidate
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    /** Keeps the selected source region aligned when the page is rotated clockwise. */
    fun rotatedClockwise(turns: Int): CropQuadrilateral {
        require(turns in 0..3)
        var points = asList()
        repeat(turns) {
            fun map(point: NormalizedCropPoint) = NormalizedCropPoint(1.0 - point.y, point.x)
            points = listOf(map(points[3]), map(points[0]), map(points[1]), map(points[2]))
        }
        return CropQuadrilateral(points[0], points[1], points[2], points[3])
    }

    private companion object {
        const val MIN_CROSS = 1e-6
        const val MIN_AREA_TWICE = 1e-6
    }
}
