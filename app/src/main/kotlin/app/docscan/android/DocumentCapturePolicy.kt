package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral

enum class DocumentCaptureDecision { RECTIFY_STILL, NO_STILL_BOUNDARY, BOUNDARIES_DISAGREE }

/** The saved photograph supplies the crop; live analysis can only veto a conflicting crop. */
object DocumentCapturePolicy {
    fun decide(
        stillQuad: CropQuadrilateral?,
        live: LiveDetectionState?,
        jpeg: JpegGeometrySpec,
        generationAtShutter: Long,
        shutterTimestampMs: Long,
    ): DocumentCaptureDecision {
        if (stillQuad == null) return DocumentCaptureDecision.NO_STILL_BOUNDARY
        val canCompare = live != null && live.generationId == generationAtShutter &&
            live.isUsableForAgreement && live.isFresh(shutterTimestampMs) &&
            live.analysisSpec?.let { CameraGeometryTransform.isGeometryCompatible(it, jpeg) } == true
        if (!canCompare) return DocumentCaptureDecision.RECTIFY_STILL
        return if (QuadGeometryUtils.checkAgreement(live!!.quadInNormalizedViewPort!!, stillQuad,
                maxRelativeShiftTolerance = 0.05, minIouTolerance = 0.85).agrees)
            DocumentCaptureDecision.RECTIFY_STILL else DocumentCaptureDecision.BOUNDARIES_DISAGREE
    }
}
