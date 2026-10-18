package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class DocumentCropLongReceiptTest {

    @Test
    fun canonicalizeCorners_supportsExtremeReceiptAspectRatios() {
        // Receipt with 1:12 aspect ratio: narrow width (0.07), long height (0.84)
        val pTL = NormalizedCropPoint(0.45, 0.05)
        val pTR = NormalizedCropPoint(0.52, 0.05)
        val pBR = NormalizedCropPoint(0.52, 0.89)
        val pBL = NormalizedCropPoint(0.45, 0.89)

        val shuffled = listOf(pTR, pBL, pBR, pTL)
        val canonical = QuadGeometryUtils.canonicalizeCorners(shuffled)

        assertEquals("TL", pTL, canonical[0])
        assertEquals("TR", pTR, canonical[1])
        assertEquals("BR", pBR, canonical[2])
        assertEquals("BL", pBL, canonical[3])
    }

    @Test
    fun checkAgreement_acceptsStableLongReceipts() {
        // 1:8 receipt
        val receiptLive = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.40, 0.10),
            topRight = NormalizedCropPoint(0.50, 0.10),
            bottomRight = NormalizedCropPoint(0.50, 0.90),
            bottomLeft = NormalizedCropPoint(0.40, 0.90),
        )

        // Still capture with slight 1% sensor jitter
        val receiptStill = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.402, 0.101),
            topRight = NormalizedCropPoint(0.501, 0.099),
            bottomRight = NormalizedCropPoint(0.499, 0.899),
            bottomLeft = NormalizedCropPoint(0.399, 0.902),
        )

        val check = QuadGeometryUtils.checkAgreement(
            receiptLive,
            receiptStill,
            maxRelativeShiftTolerance = 0.05,
            minIouTolerance = 0.85,
        )

        assertTrue("Agreement must hold for stable long receipt", check.agrees)
        assertTrue("IoU should be high (> 0.90)", check.iou > 0.90)
    }

    @Test
    fun checkAgreement_rejectsShiftedLongReceipt() {
        // 1:10 receipt
        val receiptLive = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.40, 0.05),
            topRight = NormalizedCropPoint(0.48, 0.05),
            bottomRight = NormalizedCropPoint(0.48, 0.85),
            bottomLeft = NormalizedCropPoint(0.40, 0.85),
        )

        // Document shifted laterally by 10%
        val receiptMoved = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.50, 0.05),
            topRight = NormalizedCropPoint(0.58, 0.05),
            bottomRight = NormalizedCropPoint(0.58, 0.85),
            bottomLeft = NormalizedCropPoint(0.50, 0.85),
        )

        val check = QuadGeometryUtils.checkAgreement(
            receiptLive,
            receiptMoved,
            maxRelativeShiftTolerance = 0.05,
            minIouTolerance = 0.85,
        )

        assertFalse("Agreement must be rejected when receipt shifted laterally", check.agrees)
    }

    @Test
    fun cameraGeometryTransform_mapsLongReceiptAccurately() {
        // 1280x960 buffer, cropRect [160, 0, 1120, 960], rotation 90
        val spec = AnalysisBufferSpec(
            bufferWidth = 1280,
            bufferHeight = 960,
            cropRect = BufferRect(160, 0, 1120, 960),
            rotationDegrees = 90,
        )

        // Narrow receipt in viewport coordinates: x in [0.45, 0.55], y in [0.1, 0.9] (1:8 ratio)
        val qVp = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.45, 0.10),
            topRight = NormalizedCropPoint(0.55, 0.10),
            bottomRight = NormalizedCropPoint(0.55, 0.90),
            bottomLeft = NormalizedCropPoint(0.45, 0.90),
        )

        // Convert corners from viewport to buffer and back
        for (corner in qVp.asList()) {
            val (bx, by) = CameraGeometryTransform.normalizedViewPortToAnalysisBufferPoint(corner, spec)
            val mappedBack = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(bx, by, spec)
            assertEquals(corner.x, mappedBack.x, 1e-6)
            assertEquals(corner.y, mappedBack.y, 1e-6)
        }
    }
}
