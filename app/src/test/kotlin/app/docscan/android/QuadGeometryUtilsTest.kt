package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadGeometryUtilsTest {

    @Test
    fun `canonicalizeCorners correctly orders cyclically shifted or shuffled corners`() {
        val pTL = NormalizedCropPoint(0.1, 0.1)
        val pTR = NormalizedCropPoint(0.9, 0.1)
        val pBR = NormalizedCropPoint(0.9, 0.9)
        val pBL = NormalizedCropPoint(0.1, 0.9)

        // Test shuffled permutations
        val permutations = listOf(
            listOf(pTL, pTR, pBR, pBL),
            listOf(pTR, pBR, pBL, pTL),
            listOf(pBR, pBL, pTL, pTR),
            listOf(pBL, pTL, pTR, pBR),
            listOf(pTL, pBL, pBR, pTR),
        )

        for (perm in permutations) {
            val ordered = QuadGeometryUtils.canonicalizeCorners(perm)
            assertEquals("TL should be pTL", pTL, ordered[0])
            assertEquals("TR should be pTR", pTR, ordered[1])
            assertEquals("BR should be pBR", pBR, ordered[2])
            assertEquals("BL should be pBL", pBL, ordered[3])
        }
    }

    @Test
    fun `polygonIoU matches expected values for identical, shifted, and disjoint quadrilaterals`() {
        val q1 = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.1, 0.1),
            topRight = NormalizedCropPoint(0.9, 0.1),
            bottomRight = NormalizedCropPoint(0.9, 0.9),
            bottomLeft = NormalizedCropPoint(0.1, 0.9),
        )

        // Identical quad -> IoU = 1.0
        val iouSame = QuadGeometryUtils.polygonIoU(q1, q1)
        assertEquals(1.0, iouSame, 1e-4)

        // Disjoint quad
        val qDisjoint = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.95, 0.95),
            topRight = NormalizedCropPoint(1.0, 0.95),
            bottomRight = NormalizedCropPoint(1.0, 1.0),
            bottomLeft = NormalizedCropPoint(0.95, 1.0),
        )
        val iouDisjoint = QuadGeometryUtils.polygonIoU(q1, qDisjoint)
        assertEquals(0.0, iouDisjoint, 1e-4)

        // Overlapping quads:
        // q1 is [0.1..0.7] x [0.1..0.7] -> width 0.6, height 0.6 -> Area1 = 0.36
        // qOverlap is [0.4..1.0] x [0.1..0.7] -> width 0.6, height 0.6 -> Area2 = 0.36
        // Overlap region: [0.4..0.7] x [0.1..0.7] -> width 0.3, height 0.6 -> InterArea = 0.18
        // UnionArea = 0.36 + 0.36 - 0.18 = 0.54
        // IoU = 0.18 / 0.54 = 1/3 ~ 0.3333
        val qBase = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.1, 0.1),
            topRight = NormalizedCropPoint(0.7, 0.1),
            bottomRight = NormalizedCropPoint(0.7, 0.7),
            bottomLeft = NormalizedCropPoint(0.1, 0.7),
        )
        val qOverlap = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.4, 0.1),
            topRight = NormalizedCropPoint(1.0, 0.1),
            bottomRight = NormalizedCropPoint(1.0, 0.7),
            bottomLeft = NormalizedCropPoint(0.4, 0.7),
        )
        val iouOverlap = QuadGeometryUtils.polygonIoU(qBase, qOverlap)
        assertEquals(1.0 / 3.0, iouOverlap, 1e-3)
    }

    @Test
    fun `checkAgreement passes for close quads and fails for substantial disagreement or single bad corner`() {
        val live = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.10, 0.10),
            topRight = NormalizedCropPoint(0.90, 0.10),
            bottomRight = NormalizedCropPoint(0.90, 0.90),
            bottomLeft = NormalizedCropPoint(0.10, 0.90),
        )

        // Very close quad (within 1% diagonal shift) -> should agree
        val close = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.105, 0.102),
            topRight = NormalizedCropPoint(0.898, 0.104),
            bottomRight = NormalizedCropPoint(0.903, 0.897),
            bottomLeft = NormalizedCropPoint(0.098, 0.902),
        )
        val resClose = QuadGeometryUtils.checkAgreement(live, close)
        assertTrue("Close quads must agree", resClose.agrees)
        assertTrue("IoU must be high (>0.90)", resClose.iou > 0.90)
        assertTrue("Relative corner shift must be <= 0.06", resClose.relativeCornerShift <= 0.06)

        // Substantial disagreement (one bad corner shifted by 15% of diagonal)
        // Diagonal is hypot(0.8, 0.8) = 1.131. 15% is ~0.17
        val oneBadCorner = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.28, 0.28), // Shifted by 0.25 (diagonal ratio ~0.22)
            topRight = NormalizedCropPoint(0.90, 0.10),
            bottomRight = NormalizedCropPoint(0.90, 0.90),
            bottomLeft = NormalizedCropPoint(0.10, 0.90),
        )
        val resBad = QuadGeometryUtils.checkAgreement(live, oneBadCorner)
        assertFalse("Single bad corner (>6% diagonal) must reject agreement", resBad.agrees)

        // Low IoU (< 0.80)
        val shifted = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.25, 0.25),
            topRight = NormalizedCropPoint(0.95, 0.25),
            bottomRight = NormalizedCropPoint(0.95, 0.95),
            bottomLeft = NormalizedCropPoint(0.25, 0.95),
        )
        val resShifted = QuadGeometryUtils.checkAgreement(live, shifted)
        assertFalse("Significantly shifted quad must reject agreement", resShifted.agrees)
    }

    @Test
    fun `checkAgreement measures equal pixel translations isotropically in portrait viewport`() {
        val live = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.20, 0.20),
            topRight = NormalizedCropPoint(0.80, 0.20),
            bottomRight = NormalizedCropPoint(0.80, 0.80),
            bottomLeft = NormalizedCropPoint(0.20, 0.80),
        )
        // For a 100x200 viewport, 5px means 0.05 normalized X or 0.025 normalized Y.
        val shiftedX = live.translate(dx = 5.0 / 100.0, dy = 0.0)
        val shiftedY = live.translate(dx = 0.0, dy = 5.0 / 200.0)

        val horizontal = QuadGeometryUtils.checkAgreement(
            live,
            shiftedX,
            maxRelativeShiftTolerance = 1.0,
            minIouTolerance = 0.0,
            imageAspectRatio = 100.0 / 200.0,
        )
        val vertical = QuadGeometryUtils.checkAgreement(
            live,
            shiftedY,
            maxRelativeShiftTolerance = 1.0,
            minIouTolerance = 0.0,
            imageAspectRatio = 100.0 / 200.0,
        )

        assertEquals(horizontal.maxCornerDistance, vertical.maxCornerDistance, 1e-12)
        assertEquals(horizontal.documentDiagonal, vertical.documentDiagonal, 1e-12)
        assertEquals(horizontal.relativeCornerShift, vertical.relativeCornerShift, 1e-12)
        assertTrue(horizontal.agrees)
        assertTrue(vertical.agrees)
    }

    @Test
    fun `checkAgreement rejects nonfinite or nonpositive image aspect ratio`() {
        val quad = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.1, 0.1),
            topRight = NormalizedCropPoint(0.9, 0.1),
            bottomRight = NormalizedCropPoint(0.9, 0.9),
            bottomLeft = NormalizedCropPoint(0.1, 0.9),
        )
        for (aspect in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                QuadGeometryUtils.checkAgreement(quad, quad, imageAspectRatio = aspect)
                throw AssertionError("Expected invalid aspect ratio $aspect to be rejected")
            } catch (_: IllegalArgumentException) {
                // Invalid geometry scale must fail closed.
            }
        }
    }

    private fun CropQuadrilateral.translate(dx: Double, dy: Double) = CropQuadrilateral(
        topLeft = NormalizedCropPoint(topLeft.x + dx, topLeft.y + dy),
        topRight = NormalizedCropPoint(topRight.x + dx, topRight.y + dy),
        bottomRight = NormalizedCropPoint(bottomRight.x + dx, bottomRight.y + dy),
        bottomLeft = NormalizedCropPoint(bottomLeft.x + dx, bottomLeft.y + dy),
    )
}
