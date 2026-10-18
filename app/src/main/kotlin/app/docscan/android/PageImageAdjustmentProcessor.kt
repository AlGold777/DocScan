package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Matrix
import app.docscan.core.edit.PageImageEditState
import kotlin.math.roundToInt

/** Applies temporary right-angle, brightness, contrast, and sharpening edits to one bounded bitmap. */
object PageImageAdjustmentProcessor {
    const val MAX_SOURCE_PIXELS = 12L * 1024L * 1024L

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
            val crop = state.crop
            if (crop != null && state.brightness == 0 && state.contrastPercent == 100 && state.sharpnessPercent == 0) {
                return DocumentCropProcessor.rectifyManual(rotated, crop)
            }
            // Even an unchanged image gets an independent bitmap: callers own and recycle it.
            val adjustedBitmap = renderRows(rotated, state)
            if (crop == null) return adjustedBitmap
            return try {
                DocumentCropProcessor.rectifyManual(adjustedBitmap, crop)
            } finally {
                adjustedBitmap.recycle()
            }
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    private fun renderRows(source: Bitmap, state: PageImageEditState): Bitmap {
        val width = source.width
        val height = source.height
        val contrast = state.contrastPercent / 100f
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (state.sharpnessPercent == 0) {
                val row = IntArray(width)
                for (y in 0 until height) {
                    readAdjustedRow(source, y, row, contrast, state.brightness)
                    output.setPixels(row, 0, width, 0, y, width, 1)
                }
            } else {
                // Keep adjusted (already rounded/clamped) source rows, never sharpened rows.
                val rows = Array(3) { IntArray(width) }
                val result = IntArray(width)
                val amount = state.sharpnessPercent / 100f
                readAdjustedRow(source, 0, rows[0], contrast, state.brightness)
                for (y in 0 until height) {
                    if (y + 1 < height) {
                        readAdjustedRow(source, y + 1, rows[(y + 1) % 3], contrast, state.brightness)
                    }
                    sharpenRow(rows, result, y, width, height, amount)
                    output.setPixels(result, 0, width, 0, y, width, 1)
                }
            }
            return output
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    private fun readAdjustedRow(source: Bitmap, y: Int, row: IntArray, contrast: Float, brightness: Int) {
        source.getPixels(row, 0, source.width, 0, y, source.width, 1)
        for (x in row.indices) {
            val pixel = row[x]
            val alpha = pixel ushr 24 and 0xff
            val red = adjust(pixel ushr 16 and 0xff, contrast, brightness)
            val green = adjust(pixel ushr 8 and 0xff, contrast, brightness)
            val blue = adjust(pixel and 0xff, contrast, brightness)
            row[x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun adjust(channel: Int, contrast: Float, brightness: Int): Int =
        (((channel - 127.5f) * contrast) + 127.5f + brightness).roundToInt().coerceIn(0, 255)

    private fun sharpenRow(
        adjustedRows: Array<IntArray>,
        output: IntArray,
        y: Int,
        width: Int,
        height: Int,
        amount: Float,
    ) {
        val centerRow = adjustedRows[y % 3]
        for (x in 0 until width) {
            val center = centerRow[x]
            var red = 0
            var green = 0
            var blue = 0
            var samples = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val neighbor = adjustedRows[ny % 3][nx]
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
            output[x] = (center ushr 24 shl 24) or (outRed shl 16) or (outGreen shl 8) or outBlue
        }
    }
}
