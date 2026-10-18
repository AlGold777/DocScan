package app.docscan.android

import android.graphics.Bitmap
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId

data class DocumentCropPlan(
    val pageId: PageId,
    val initialImageVersionId: ImageVersionId,
    val initialContentRevision: Long,
    val artifactRelativePath: String,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val cropQuad: CropQuadrilateral,
    val algorithmVersion: String = ALGORITHM_VERSION,
) {
    init {
        require(orientedWidth > 0 && orientedHeight > 0) {
            "Dimensions must be positive: ${orientedWidth}x${orientedHeight}"
        }
        require(artifactRelativePath.isNotBlank()) {
            "artifactRelativePath must not be blank"
        }
    }

    companion object {
        const val ALGORITHM_VERSION = "auto-perspective-crop-v2"
    }
}

data class DocumentCropPreviewResult(
    val previewBitmap: Bitmap,
    val plan: DocumentCropPlan,
)
