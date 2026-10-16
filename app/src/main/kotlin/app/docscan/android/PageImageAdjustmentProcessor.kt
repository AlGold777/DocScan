package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Matrix
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.PageImageEditState
import kotlin.math.roundToInt

/** Applies temporary right-angle, brightness, contrast, and sharpening edits to one bounded bitmap. */
object PageImageAdjustmentProcessor {
    const val MAX_SOURCE_PIXELS = 4L * 1024L * 1024L

    fun render(source: Bitmap, state: PageImageEditState): Bitmap {
        require(!source.isRecycled) { "Source image is unavailable" }
        val inputPixels = source.width.toLong() * source.height
        require(inputPixels in 1..MAX_SOURCE_PIXELS) { "Image is outside the edit pixel bound" }

        val rotated = if (state.clockwiseTurns == 0) source else Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(state.clockwiseTurns * 90f) },
            true,
        )
        try {
            val count = rotated.width * rotated.height
            val original = IntArray(count)
            val adjusted = IntArray(count)
            rotated.getPixels(original, 0, rotated.width, 0, 0, rotated.width, rotated.height)
            val contrast = state.contrastPercent / 100f
            for (index in original.indices) {
                val pixel = original[index]
                val alpha = pixel ushr 24 and 0xff
                val red = adjust(pixel ushr 16 and 0xff, contrast, state.brightness)
                val green = adjust(pixel ushr 8 and 0xff, contrast, state.brightness)
                val blue = adjust(pixel and 0xff, contrast, state.brightness)
                adjusted[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }

            val outputPixels = if (state.sharpnessPercent == 0) adjusted else {
                sharpen(
                    adjusted = adjusted,
                    output = original,
                    width = rotated.width,
                    height = rotated.height,
                    amount = state.sharpnessPercent / 100f,
                )
            }
            val adjustedBitmap = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(outputPixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)
            }
            val crop = state.crop ?: return adjustedBitmap
            return try {
                DocumentCropProcessor.rectifyManual(adjustedBitmap, crop)
            } finally {
                adjustedBitmap.recycle()
            }
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    private fun adjust(channel: Int, contrast: Float, brightness: Int): Int =
        (((channel - 127.5f) * contrast) + 127.5f + brightness).roundToInt().coerceIn(0, 255)

    private fun sharpen(
        adjusted: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        amount: Float,
    ): IntArray {
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val center = adjusted[index]
            var red = 0
            var green = 0
            var blue = 0
            var samples = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val neighbor = adjusted[ny * width + nx]
                red += neighbor ushr 16 and 0xff
                green += neighbor ushr 8 and 0xff
                blue += neighbor and 0xff
                samples++
            }
            val centerRed = center ushr 16 and 0xff
            val centerGreen = center ushr 8 and 0xff
            val centerBlue = center and 0xff
            val outRed = (centerRed + (centerRed - red / samples) * amount).roundToInt().coerceIn(0, 255)
            val outGreen = (centerGreen + (centerGreen - green / samples) * amount).roundToInt().coerceIn(0, 255)
            val outBlue = (centerBlue + (centerBlue - blue / samples) * amount).roundToInt().coerceIn(0, 255)
            output[index] = (center ushr 24 shl 24) or (outRed shl 16) or (outGreen shl 8) or outBlue
        }
        return output
    }
}
