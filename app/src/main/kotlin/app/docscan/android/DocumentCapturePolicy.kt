package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral

enum class DocumentCaptureDecision { RECTIFY_STILL, NO_STILL_BOUNDARY, BOUNDARIES_DISAGREE }

/** Same aspect ratio and shutter request time do not prove JPEG exposure/FOV alignment. */
data class LiveStillCompatibility(
    val sameFieldOfViewVerified: Boolean,
    val exposureTimestampMonotonicMs: Long?,
) {
    fun permitsComparison(live: LiveDetectionState): Boolean = sameFieldOfViewVerified &&
        exposureTimestampMonotonicMs?.let { live.isFresh(it) } == true
}

/** JPEG supplies the crop; only proven spatial AND temporal correspondence permits a live veto. */
object DocumentCapturePolicy {
    fun decide(
        stillQuad: CropQuadrilateral?,
        live: LiveDetectionState?,
        jpeg: JpegGeometrySpec,
        generationAtShutter: Long,
        shutterTimestampMs: Long,
        compatibility: LiveStillCompatibility? = null,
    ): DocumentCaptureDecision {
        if (stillQuad == null) return DocumentCaptureDecision.NO_STILL_BOUNDARY
        val canCompare = live != null && live.generationId == generationAtShutter &&
            compatibility?.permitsComparison(live) == true &&
            live.isUsableForAgreement && live.isFresh(shutterTimestampMs) &&
            live.analysisSpec?.let { CameraGeometryTransform.isGeometryCompatible(it, jpeg) } == true
        if (!canCompare) return DocumentCaptureDecision.RECTIFY_STILL
        return if (QuadGeometryUtils.checkAgreement(live!!.quadInNormalizedViewPort!!, stillQuad,
                maxRelativeShiftTolerance = 0.05, minIouTolerance = 0.85,
                imageAspectRatio = jpeg.aspectRatio).agrees)
            DocumentCaptureDecision.RECTIFY_STILL else DocumentCaptureDecision.BOUNDARIES_DISAGREE
    }
}
