package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentCapturePolicyTest {

    private val generation = 7L
    private val shutterTimeMs = 1_000L
    private val analysisSpec = AnalysisBufferSpec(
        bufferWidth = 800,
        bufferHeight = 800,
        cropRect = BufferRect(80, 80, 720, 720),
        rotationDegrees = 0,
    )
    private val compatibleJpeg = JpegGeometrySpec(orientedWidth = 3000, orientedHeight = 3000)
    private val document = quad(0.10, 0.10, 0.90, 0.90)

    @Test
    fun `good still boundary rectifies when live boundary is absent`() {
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, stillQuad = document, live = null)
    }

    @Test
    fun `good still boundary rectifies when live frame is held stale or from another generation`() {
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live(status = LiveDetectionStatus.HELD))
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live(timestampMs = shutterTimeMs - 301L))
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live(generationId = generation + 1L))
    }

    @Test
    fun `good still boundary rectifies when analysis and jpeg geometry are incompatible`() {
        val incompatibleAnalysis = AnalysisBufferSpec(
            bufferWidth = 800,
            bufferHeight = 600,
            cropRect = BufferRect(80, 60, 720, 540),
            rotationDegrees = 0,
        )
        val incompatibleLive = live(spec = incompatibleAnalysis)

        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, incompatibleLive)
    }

    @Test
    fun `missing still boundary never borrows a live boundary`() {
        assertDecision(DocumentCaptureDecision.NO_STILL_BOUNDARY, stillQuad = null, live = live())
    }

    @Test
    fun `matching live and still boundaries allow rectification`() {
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live())
    }

    @Test
    fun `one bad live corner vetoes automatic rectification`() {
        val oneBadCorner = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.48, 0.12),
            topRight = NormalizedCropPoint(0.90, 0.10),
            bottomRight = NormalizedCropPoint(0.90, 0.90),
            bottomLeft = NormalizedCropPoint(0.10, 0.90),
        )

        assertDecision(
            DocumentCaptureDecision.BOUNDARIES_DISAGREE,
            document,
            live(quad = oneBadCorner),
        )
    }

    private fun assertDecision(
        expected: DocumentCaptureDecision,
        stillQuad: CropQuadrilateral?,
        live: LiveDetectionState?,
    ) {
        assertEquals(
            expected,
            DocumentCapturePolicy.decide(
                stillQuad = stillQuad,
                live = live,
                jpeg = compatibleJpeg,
                generationAtShutter = generation,
                shutterTimestampMs = shutterTimeMs,
            ),
        )
    }

    private fun live(
        status: LiveDetectionStatus = LiveDetectionStatus.CONFIRMED,
        quad: CropQuadrilateral = document,
        generationId: Long = generation,
        timestampMs: Long = shutterTimeMs - 100L,
        spec: AnalysisBufferSpec = analysisSpec,
    ) = LiveDetectionState(
        generationId = generationId,
        status = status,
        analysisSpec = spec,
        quadInBufferPixels = floatArrayOf(160f, 160f, 640f, 160f, 640f, 640f, 160f, 640f),
        quadInNormalizedViewPort = quad,
        rawScore = 0.9,
        frameTimestampMonotonicMs = timestampMs,
        processedTimestampMonotonicMs = timestampMs,
    )

    private fun quad(left: Double, top: Double, right: Double, bottom: Double) = CropQuadrilateral(
        topLeft = NormalizedCropPoint(left, top),
        topRight = NormalizedCropPoint(right, top),
        bottomRight = NormalizedCropPoint(right, bottom),
        bottomLeft = NormalizedCropPoint(left, bottom),
    )
}
