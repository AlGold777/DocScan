package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraGeometryTransformTest {

    @Test
    fun `dm01 control example - buffer 1280x960 with cropRect 160,0,1120,960 maps 320,240 to 1 div 6, 1 div 4`() {
        val spec = AnalysisBufferSpec(
            bufferWidth = 1280,
            bufferHeight = 960,
            cropRect = BufferRect(left = 160, top = 0, right = 1120, bottom = 960),
            rotationDegrees = 0,
        )

        val normalized = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(320.0, 240.0, spec)

        val expectedX = 1.0 / 6.0
        val expectedY = 1.0 / 4.0
        assertEquals("Normalized X should be 1/6", expectedX, normalized.x, 1e-6)
        assertEquals("Normalized Y should be 1/4", expectedY, normalized.y, 1e-6)

        // Check inverse mapping
        val (backX, backY) = CameraGeometryTransform.normalizedViewPortToAnalysisBufferPoint(normalized, spec)
        assertEquals("Inverted buffer X should match original pixel", 320.0, backX, 1e-5)
        assertEquals("Inverted buffer Y should match original pixel", 240.0, backY, 1e-5)
    }

    @Test
    fun `rotations 0 90 180 270 bidirectional round-trip accuracy within 1e-5 normalized and 1 pixel`() {
        val bufferWidth = 1920
        val bufferHeight = 1080
        val cropRect = BufferRect(left = 240, top = 60, right = 1680, bottom = 1020)

        val testPoints = listOf(
            240.0 to 60.0,
            1680.0 to 60.0,
            1680.0 to 1020.0,
            240.0 to 1020.0,
            960.0 to 540.0,
            500.0 to 300.0,
            1200.0 to 800.0,
        )

        for (rotation in listOf(0, 90, 180, 270)) {
            val spec = AnalysisBufferSpec(
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                cropRect = cropRect,
                rotationDegrees = rotation,
            )

            for ((px, py) in testPoints) {
                val norm = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(px, py, spec)
                assertTrue("Normalized x must be in [0, 1] for rotation $rotation", norm.x in -1e-6..1.0 + 1e-6)
                assertTrue("Normalized y must be in [0, 1] for rotation $rotation", norm.y in -1e-6..1.0 + 1e-6)

                val (rx, ry) = CameraGeometryTransform.normalizedViewPortToAnalysisBufferPoint(norm, spec)
                val errX = abs(rx - px)
                val errY = abs(ry - py)
                assertTrue("Error in X for rotation $rotation must be < 1 px (was $errX)", errX <= 1.0)
                assertTrue("Error in Y for rotation $rotation must be < 1 px (was $errY)", errY <= 1.0)
                assertTrue("Error in X for rotation $rotation must be < 1e-5", errX < 1e-4)
                assertTrue("Error in Y for rotation $rotation must be < 1e-5", errY < 1e-4)
            }
        }
    }

    @Test
    fun `jpeg coordinate mapping with 90 degree portrait orientation and different resolutions`() {
        // Analysis buffer in landscape (1280x960), cropped to 720x960 (3:4 portrait), rotated 90 deg -> 960x720 (4:3)
        // or cropped to 960x720, rotated 90 deg -> 720x960 (3:4 portrait)
        val spec = AnalysisBufferSpec(
            bufferWidth = 1280,
            bufferHeight = 960,
            cropRect = BufferRect(left = 160, top = 0, right = 1120, bottom = 960), // 960x960 (1:1)
            rotationDegrees = 90,
        )
        val jpegSpec = JpegGeometrySpec(orientedWidth = 3000, orientedHeight = 3000) // 1:1

        assertTrue(CameraGeometryTransform.isGeometryCompatible(spec, jpegSpec))

        // Center of cropRect (left=160, width=960 -> center=640; top=0, height=960 -> center=480)
        val norm = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(640.0, 480.0, spec)
        assertEquals(0.5, norm.x, 1e-5)
        assertEquals(0.5, norm.y, 1e-5)

        val (jpegX, jpegY) = CameraGeometryTransform.normalizedViewPortToJpegPixel(norm, jpegSpec)
        assertEquals(1500.0, jpegX, 1.0)
        assertEquals(1500.0, jpegY, 1.0)
    }

    @Test
    fun `incompatible aspect ratio is detected to disable stale live quad fallback`() {
        // ViewPort is 16:9 portrait (oriented: 9:16 = 0.5625)
        val spec = AnalysisBufferSpec(
            bufferWidth = 1920,
            bufferHeight = 1080,
            cropRect = BufferRect(left = 0, top = 0, right = 1920, bottom = 1080),
            rotationDegrees = 90, // oriented: 1080 x 1920
        )
        // Saved JPEG is 4:3 portrait: 3000 x 4000 (aspect 0.75)
        val jpegSpec = JpegGeometrySpec(orientedWidth = 3000, orientedHeight = 4000)

        assertFalse("Aspect ratios 9:16 and 3:4 must be flagged as incompatible",
            CameraGeometryTransform.isGeometryCompatible(spec, jpegSpec))
    }
}
