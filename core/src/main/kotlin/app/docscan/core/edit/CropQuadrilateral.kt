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
