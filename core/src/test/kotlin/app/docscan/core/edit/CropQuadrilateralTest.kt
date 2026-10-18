package app.docscan.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CropQuadrilateralTest {
    private val standardQuad = CropQuadrilateral(
        topLeft = NormalizedCropPoint(0.1, 0.1),
        topRight = NormalizedCropPoint(0.9, 0.1),
        bottomRight = NormalizedCropPoint(0.9, 0.9),
        bottomLeft = NormalizedCropPoint(0.1, 0.9),
    )

    @Test
    fun movingTopEdgeShiftsBothTopLeftAndTopRight() {
        val moved = standardQuad.withMovedEdge(0, dx = 0.0, dy = 0.05)
        assertEquals(0.1, moved.topLeft.x, 1e-6)
        assertEquals(0.15, moved.topLeft.y, 1e-6)
        assertEquals(0.9, moved.topRight.x, 1e-6)
        assertEquals(0.15, moved.topRight.y, 1e-6)
        // Bottom points remain unchanged
        assertEquals(standardQuad.bottomRight, moved.bottomRight)
        assertEquals(standardQuad.bottomLeft, moved.bottomLeft)
    }

    @Test
    fun movingRightEdgeShiftsBothTopRightAndBottomRight() {
        val moved = standardQuad.withMovedEdge(1, dx = -0.1, dy = 0.0)
        assertEquals(standardQuad.topLeft, moved.topLeft)
        assertEquals(standardQuad.bottomLeft, moved.bottomLeft)
        assertEquals(0.8, moved.topRight.x, 1e-6)
        assertEquals(0.1, moved.topRight.y, 1e-6)
        assertEquals(0.8, moved.bottomRight.x, 1e-6)
        assertEquals(0.9, moved.bottomRight.y, 1e-6)
    }

    @Test
    fun movingBottomEdgeShiftsBothBottomRightAndBottomLeft() {
        val moved = standardQuad.withMovedEdge(2, dx = 0.0, dy = -0.2)
        assertEquals(standardQuad.topLeft, moved.topLeft)
        assertEquals(standardQuad.topRight, moved.topRight)
        assertEquals(0.9, moved.bottomRight.x, 1e-6)
        assertEquals(0.7, moved.bottomRight.y, 1e-6)
        assertEquals(0.1, moved.bottomLeft.x, 1e-6)
        assertEquals(0.7, moved.bottomLeft.y, 1e-6)
    }

    @Test
    fun movingLeftEdgeShiftsBothBottomLeftAndTopLeft() {
        val moved = standardQuad.withMovedEdge(3, dx = 0.05, dy = 0.0)
        assertEquals(0.15, moved.topLeft.x, 1e-6)
        assertEquals(0.1, moved.topLeft.y, 1e-6)
        assertEquals(standardQuad.topRight, moved.topRight)
        assertEquals(standardQuad.bottomRight, moved.bottomRight)
        assertEquals(0.15, moved.bottomLeft.x, 1e-6)
        assertEquals(0.9, moved.bottomLeft.y, 1e-6)
    }

    @Test
    fun edgeTranslationPreservesEdgeLength() {
        val initialEdgeLength = hypot(
            standardQuad.topRight.x - standardQuad.topLeft.x,
            standardQuad.topRight.y - standardQuad.topLeft.y,
        )
        val moved = standardQuad.withMovedEdge(0, dx = 0.04, dy = -0.05)
        val movedEdgeLength = hypot(
            moved.topRight.x - moved.topLeft.x,
            moved.topRight.y - moved.topLeft.y,
        )
        assertEquals(initialEdgeLength, movedEdgeLength, 1e-6)
    }

    @Test
    fun movingEdgeClampsToImageBoundaries() {
        // Drag top edge far beyond top boundary (y < 0)
        val clampedTop = standardQuad.withMovedEdge(0, dx = 0.0, dy = -0.5)
        assertEquals(0.0, clampedTop.topLeft.y, 1e-6)
        assertEquals(0.0, clampedTop.topRight.y, 1e-6)

        // Drag right edge far beyond right boundary (x > 1)
        val clampedRight = standardQuad.withMovedEdge(1, dx = 0.5, dy = 0.0)
        assertEquals(1.0, clampedRight.topRight.x, 1e-6)
        assertEquals(1.0, clampedRight.bottomRight.x, 1e-6)
    }

    @Test
    fun draggingEdgePastOppositeEdgeHaltsSmoothlyAndPreservesConvexity() {
        val moved = standardQuad.withMovedEdge(0, dx = 0.0, dy = 1.5)
        assertTrue("topLeft.y (${moved.topLeft.y}) must be < bottomLeft.y (${moved.bottomLeft.y})", moved.topLeft.y < moved.bottomLeft.y)
        assertTrue("topRight.y (${moved.topRight.y}) must be < bottomRight.y (${moved.bottomRight.y})", moved.topRight.y < moved.bottomRight.y)
        assertTrue("topLeft.y was ${moved.topLeft.y}", moved.topLeft.y > 0.5)
    }

    @Test
    fun zeroOrNonFiniteDeltaReturnsSameQuad() {
        assertEquals(standardQuad, standardQuad.withMovedEdge(0, 0.0, 0.0))
        assertEquals(standardQuad, standardQuad.withMovedEdge(1, Double.NaN, 0.0))
        assertEquals(standardQuad, standardQuad.withMovedEdge(2, 0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun invalidEdgeIndexThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            standardQuad.withMovedEdge(-1, 0.1, 0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            standardQuad.withMovedEdge(4, 0.1, 0.1)
        }
    }
}
