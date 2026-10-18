package app.docscan.android

import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

object DocumentCropMemoryCalculator {

    const val DEFAULT_MAX_BUDGET_BYTES: Long = 96L * 1024L * 1024L // 96 MiB standard budget
    const val DETECTION_THUMBNAIL_MAX_SIDE: Int = 720
    const val ESTIMATED_DETECTION_OVERHEAD_BYTES: Long = 16L * 1024L * 1024L // ~16 MiB for 720px + OpenCV masks
    const val WARP_BUFFER_MULTIPLIER: Long = 16L // Source Bitmap (4) + Source Mat (4) + Warped Mat (4) + Result Bitmap (4)
    const val MIN_WARP_PIXELS: Long = 1024L * 768L // ~0.78 MP min bound

    /**
     * Estimates peak memory needed to warp an image of [targetPixels].
     * Uses 64-bit Long arithmetic to prevent overflow.
     */
    fun estimateWarpWorkingSetBytes(targetPixels: Long): Long {
        require(targetPixels > 0L) { "targetPixels must be positive: $targetPixels" }
        val bufferBytes = targetPixels.coerceAtLeast(1L) * WARP_BUFFER_MULTIPLIER
        val safetyReserve = 4L * 1024L * 1024L // 4 MiB reserve
        return bufferBytes + safetyReserve
    }

    /**
     * Determines the maximum pixel count that will safely fit into [maxBudgetBytes].
     */
    fun calculateBoundedPixels(
        sourceWidth: Int,
        sourceHeight: Int,
        maxBudgetBytes: Long = DEFAULT_MAX_BUDGET_BYTES,
        maxSourcePixelsLimit: Long = DocumentCropProcessor.MAX_CAPTURE_SOURCE_PIXELS,
    ): Long {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(maxBudgetBytes > 0L) { "maxBudgetBytes must be positive" }

        val totalSourcePixels = sourceWidth.toLong() * sourceHeight.toLong()

        // Available bytes for buffers after 4 MiB reserve
        val usableBytes = (maxBudgetBytes - 4L * 1024L * 1024L).coerceAtLeast(MIN_WARP_PIXELS * WARP_BUFFER_MULTIPLIER)
        val budgetPixelCap = usableBytes / WARP_BUFFER_MULTIPLIER

        return min(totalSourcePixels, min(maxSourcePixelsLimit, budgetPixelCap))
    }

    /**
     * Reads image bounds from file without decoding full bitmap.
     */
    fun readImageBounds(file: File): Pair<Int, Int>? {
        if (!file.exists() || file.length() == 0L) return null
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }
}
