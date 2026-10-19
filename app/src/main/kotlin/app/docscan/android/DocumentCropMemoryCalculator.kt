package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A decoder request and the maximum simultaneously live source/output buffers for its warp. */
data class DocumentCropWorkPlan(
    val sourcePixelLimit: Long,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val workingSetBytes: Long,
)

object DocumentCropMemoryCalculator {
    const val DEFAULT_MAX_BUDGET_BYTES = 96L * 1024L * 1024L
    const val DETECTION_THUMBNAIL_MAX_SIDE = 720
    const val ESTIMATED_DETECTION_OVERHEAD_BYTES = 16L * 1024L * 1024L
    const val WARP_BUFFER_MULTIPLIER = 16L
    const val WARP_RESERVE_BYTES = 4L * 1024L * 1024L

    /** Bitmap+Mat for each of the source and result; fail before an estimate can wrap. */
    fun estimateWarpWorkingSetBytes(targetPixels: Long, outputPixels: Long = targetPixels): Long {
        require(targetPixels > 0 && outputPixels > 0) { "Pixel counts must be positive" }
        return Math.addExact(WARP_RESERVE_BYTES,
            Math.multiplyExact(Math.addExact(targetPixels, outputPixels), 8L))
    }

    fun calculateBoundedPixels(
        sourceWidth: Int,
        sourceHeight: Int,
        maxBudgetBytes: Long = DEFAULT_MAX_BUDGET_BYTES,
        maxSourcePixelsLimit: Long = DocumentCropProcessor.MAX_CAPTURE_SOURCE_PIXELS,
    ): Long {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(maxSourcePixelsLimit > 0) { "Source pixel limit must be positive" }
        require(maxBudgetBytes >= WARP_RESERVE_BYTES + WARP_BUFFER_MULTIPLIER) {
            "Not enough memory for a crop working set"
        }
        val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
        val cap = (maxBudgetBytes - WARP_RESERVE_BYTES) / WARP_BUFFER_MULTIPLIER
        return min(sourcePixels, min(maxSourcePixelsLimit, cap))
    }

    /**
     * Use actual quadrilateral edge lengths, just as rectifyManual does. A trapezoid's
     * output rectangle can be larger than its source; 16*sourcePixels alone is unsafe.
     */
    fun planWarp(
        sourceWidth: Int,
        sourceHeight: Int,
        quad: CropQuadrilateral,
        maxBudgetBytes: Long = DEFAULT_MAX_BUDGET_BYTES,
        maxSourcePixelsLimit: Long = DocumentCropProcessor.MAX_CAPTURE_SOURCE_PIXELS,
    ): DocumentCropWorkPlan {
        require(sourceWidth >= 2 && sourceHeight >= 2) { "Crop source is too small" }
        val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
        var low = 1L
        var high = calculateBoundedPixels(sourceWidth, sourceHeight, maxBudgetBytes,
            min(maxSourcePixelsLimit, DocumentCropProcessor.MAX_MANUAL_SOURCE_PIXELS))
        var best: DocumentCropWorkPlan? = null
        while (low <= high) {
            val limit = low + (high - low) / 2
            val ratio = min(1.0, sqrt(limit.toDouble() / sourcePixels))
            val width = max(1, (sourceWidth * ratio).toInt())
            val height = max(1, (sourceHeight * ratio).toInt())
            if (width < 2 || height < 2) { low = limit + 1; continue }
            val points = quad.asList()
            fun edge(a: Int, b: Int) = hypot((points[a].x - points[b].x) * (width - 1),
                (points[a].y - points[b].y) * (height - 1))
            val outputWidth = max(edge(0, 1), edge(3, 2)).roundToInt().coerceAtLeast(2)
            val outputHeight = max(edge(0, 3), edge(1, 2)).roundToInt().coerceAtLeast(2)
            val outputPixels = Math.multiplyExact(outputWidth.toLong(), outputHeight.toLong())
            val bytes = estimateWarpWorkingSetBytes(width.toLong() * height, outputPixels)
            if (bytes <= maxBudgetBytes && outputPixels <= DocumentCropProcessor.MAX_MANUAL_SOURCE_PIXELS) {
                best = DocumentCropWorkPlan(limit, width, height, outputWidth, outputHeight, bytes)
                low = limit + 1
            } else high = limit - 1
        }
        return requireNotNull(best) { "Not enough memory for a usable crop" }
    }

    fun readImageBounds(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() == 0L) return null
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    fun readOrientedImageBounds(file: File): Pair<Int, Int>? {
        val raw = readImageBounds(file) ?: return null
        val rotation = androidx.exifinterface.media.ExifInterface(file).rotationDegrees
        return if (rotation == 90 || rotation == 270) raw.second to raw.first else raw
    }
}
