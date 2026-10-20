package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentBoundaryGeometryTest {
    @Test
    fun `EXIF orientations map all continuous image corners and round trip`() {
        val width = 640.0
        val height = 480.0
        val corners = listOf(
            BoundaryPoint(0.0, 0.0), BoundaryPoint(width, 0.0),
            BoundaryPoint(width, height), BoundaryPoint(0.0, height),
        )
        val expected = mapOf(
            1 to listOf(BoundaryPoint(0.0, 0.0), BoundaryPoint(width, 0.0), BoundaryPoint(width, height), BoundaryPoint(0.0, height)),
            2 to listOf(BoundaryPoint(width, 0.0), BoundaryPoint(0.0, 0.0), BoundaryPoint(0.0, height), BoundaryPoint(width, height)),
            3 to listOf(BoundaryPoint(width, height), BoundaryPoint(0.0, height), BoundaryPoint(0.0, 0.0), BoundaryPoint(width, 0.0)),
            4 to listOf(BoundaryPoint(0.0, height), BoundaryPoint(width, height), BoundaryPoint(width, 0.0), BoundaryPoint(0.0, 0.0)),
            5 to listOf(BoundaryPoint(0.0, 0.0), BoundaryPoint(0.0, width), BoundaryPoint(height, width), BoundaryPoint(height, 0.0)),
            6 to listOf(BoundaryPoint(height, 0.0), BoundaryPoint(height, width), BoundaryPoint(0.0, width), BoundaryPoint(0.0, 0.0)),
            7 to listOf(BoundaryPoint(height, width), BoundaryPoint(height, 0.0), BoundaryPoint(0.0, 0.0), BoundaryPoint(0.0, width)),
            8 to listOf(BoundaryPoint(0.0, width), BoundaryPoint(0.0, 0.0), BoundaryPoint(height, 0.0), BoundaryPoint(height, width)),
        )

        for (orientation in 1..8) {
            val transform = BoundaryImageTransform.exif(orientation, width.toInt(), height.toInt())
            corners.zip(expected.getValue(orientation)).forEach { (source, destination) ->
                assertEquals("EXIF $orientation maps $source x", destination.x, transform.map(source).x, 1e-9)
                assertEquals("EXIF $orientation maps $source y", destination.y, transform.map(source).y, 1e-9)
                val roundTrip = transform.inverse().map(destination)
                assertEquals("EXIF $orientation inverse x", source.x, roundTrip.x, 1e-9)
                assertEquals("EXIF $orientation inverse y", source.y, roundTrip.y, 1e-9)
            }
        }
    }

    @Test
    fun `letterbox removes asymmetric padding on inverse without clamping`() {
        val transform = BoundaryImageTransform.letterbox(200, 100, 100, 100)
        assertEquals(BoundaryPoint(0.0, 25.0), transform.map(BoundaryPoint(0.0, 0.0)))
        assertEquals(BoundaryPoint(100.0, 75.0), transform.map(BoundaryPoint(200.0, 100.0)))
        val original = BoundaryPoint(37.25, 68.5)
        val mapped = transform.map(original)
        assertEquals(original.x, transform.inverse().map(mapped).x, 1e-9)
        assertEquals(original.y, transform.inverse().map(mapped).y, 1e-9)
        assertTrue("Transform leaves source points outside target bounds unclamped", transform.map(BoundaryPoint(220.0, 50.0)).x > 100.0)
    }

    @Test
    fun `pixel-centre resize follows OpenCV half-pixel convention and round trips`() {
        val transform = BoundaryImageTransform.resizeCentres(640, 480, 320, 240)
        val centre = BoundaryPoint(319.5, 239.5)
        val mapped = transform.map(centre)
        assertEquals(159.5, mapped.x, 1e-9)
        assertEquals(119.5, mapped.y, 1e-9)
        val roundTrip = transform.inverse().map(mapped)
        assertEquals(centre.x, roundTrip.x, 1e-9)
        assertEquals(centre.y, roundTrip.y, 1e-9)
        assertEquals(-0.25, transform.map(BoundaryPoint(0.0, 0.0)).x, 1e-9)
    }

    @Test
    fun `quad validation accepts cyclic and reversed winding but rejects malformed and crossed points`() {
        val points = floatArrayOf(20f, 10f, 80f, 10f, 80f, 70f, 20f, 70f)
        val canonical = BoundaryQuad.from(points)
        val cyclic = BoundaryQuad.from(floatArrayOf(80f, 10f, 80f, 70f, 20f, 70f, 20f, 10f))
        val reversed = BoundaryQuad.from(floatArrayOf(20f, 10f, 20f, 70f, 80f, 70f, 80f, 10f))
        assertEquals(canonical.points.toSet(), cyclic.points.toSet())
        assertEquals(canonical.points.toSet(), reversed.points.toSet())
        assertTrue(runCatching { BoundaryQuad.from(floatArrayOf(1f, 2f, 3f)) }.isFailure)
        assertTrue(runCatching {
            BoundaryQuad.from(floatArrayOf(Float.NaN, 0f, 10f, 0f, 10f, 10f, 0f, 10f))
        }.isFailure)
        assertTrue(runCatching {
            BoundaryQuad.from(floatArrayOf(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f))
        }.isFailure)
    }

    @Test
    fun `camera mapping rejects crossed raw corner order and uses pixel-centre crop limits`() {
        val spec = AnalysisBufferSpec(100, 80, BufferRect(10, 5, 90, 75), 0)
        val crossed = floatArrayOf(20f, 10f, 80f, 60f, 80f, 10f, 20f, 60f)
        assertTrue("Raw self-crossing order must not be silently canonicalized", runCatching {
            CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(crossed, spec)
        }.isFailure)

        val rightEdge = quad(20.0, 10.0, 90.0, 60.0)
        val rect = BufferRect(10, 5, 90, 75)
        assertFalse("CropRect right/bottom are exclusive pixel-centre limits", rightEdge.inside(rect))
        assertTrue(quad(20.0, 10.0, 89.0, 74.0).inside(rect))
    }

    @Test
    fun `outside or explicitly hidden hypothesis cannot suppress an inside page candidate`() {
        val viewport = BufferRect(0, 0, 1000, 800)
        val inside = hypothesis(
            quad(180.0, 100.0, 820.0, 700.0), source = "inside-perspective", visibility = BoundaryVisibility.FULL,
        )
        val outside = hypothesis(
            quad(-100.0, -80.0, 1100.0, 880.0), source = "outside-simple-page", visibility = BoundaryVisibility.OUT_OF_FRAME,
        )
        val unknownButInside = hypothesis(
            quad(210.0, 130.0, 790.0, 670.0), source = "inside-color-page", visibility = BoundaryVisibility.UNKNOWN,
        )
        val result = boundaryResult(listOf(outside, inside, unknownButInside))

        assertEquals(listOf(inside, unknownButInside), DocumentBoundarySelection.visible(result, viewport))
        assertTrue(inside.quad.inside(viewport))
        assertTrue(unknownButInside.quad.inside(viewport))
    }

    private fun hypothesis(
        quad: BoundaryQuad,
        source: String,
        visibility: BoundaryVisibility,
    ) = DocumentHypothesis(0, quad, 0.8, visibility = visibility, source = source)

    private fun boundaryResult(candidates: List<DocumentHypothesis>) = DocumentBoundaryResult(
        identity = BoundaryFrameId(1L, 1L, 1L), detectorId = "test", configVersion = "test-v1",
        outcome = BoundaryOutcome.FOUND, candidates = candidates, detectorStartedNs = 0L, detectorFinishedNs = 1L,
    )

    private fun quad(left: Double, top: Double, right: Double, bottom: Double) = BoundaryQuad(
        BoundaryPoint(left, top), BoundaryPoint(right, top), BoundaryPoint(right, bottom), BoundaryPoint(left, bottom),
    )
}
