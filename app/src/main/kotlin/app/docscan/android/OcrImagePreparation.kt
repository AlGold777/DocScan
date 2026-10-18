package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import kotlin.math.roundToInt

/** Photometric changes only: dimensions and pixel coordinates never change. */
enum class OcrImageProfile { ORIGINAL, LIGHTING, OTSU }

data class OcrRunSettings(
    val pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_AUTO,
    val imageProfile: OcrImageProfile = OcrImageProfile.ORIGINAL,
    val autoLighting: Boolean = false,
)

object OcrImagePreparation {
    /** Sample the bright part of each region so ink does not masquerade as a shadow.
     * Thresholds are deliberately conservative; they change only the OCR copy.
     */
    fun suggestProfile(source: Bitmap): OcrImageProfile {
        val backgrounds = ArrayList<Int>(48)
        val tile = IntArray(25)
        for (row in 0 until 8) for (column in 0 until 6) {
            for (sy in 0 until 5) for (sx in 0 until 5) {
                val x = ((column + (sx + 0.5) / 5) * source.width / 6).toInt().coerceIn(0, source.width - 1)
                val y = ((row + (sy + 0.5) / 5) * source.height / 8).toInt().coerceIn(0, source.height - 1)
                val pixel = source.getPixel(x, y)
                val gray = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
                val alpha = Color.alpha(pixel)
                tile[sy * 5 + sx] = (gray * alpha + 255 * (255 - alpha)) / 255
            }
            tile.sort()
            if (tile[22] >= 96) backgrounds += tile[22]
        }
        if (backgrounds.size < 12) return OcrImageProfile.ORIGINAL
        backgrounds.sort()
        val low = backgrounds[backgrounds.size / 5]
        val high = backgrounds[(backgrounds.size * 9 / 10).coerceAtMost(backgrounds.lastIndex)]
        return if (high >= 185 && high - low >= 50 && low * 1.3f < high)
            OcrImageProfile.LIGHTING else OcrImageProfile.ORIGINAL
    }

    fun prepare(source: Bitmap, profile: OcrImageProfile, isCancelled: () -> Boolean = { false }): Bitmap {
        if (profile == OcrImageProfile.ORIGINAL) return source
        val width = source.width
        val height = source.height
        val row = IntArray(width)
        val histogram = LongArray(256)
        val tileSize = 64
        val columns = (width + tileSize - 1) / tileSize
        val rows = (height + tileSize - 1) / tileSize
        val background = FloatArray(columns * rows)
        val counts = IntArray(background.size)
        fun luminance(pixel: Int): Int {
            val gray = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
            val alpha = Color.alpha(pixel)
            return (gray * alpha + 255 * (255 - alpha)) / 255
        }
        for (y in 0 until height) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("OCR image preparation cancelled")
            source.getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                val gray = luminance(row[x])
                histogram[gray]++
                val tile = (y / tileSize) * columns + x / tileSize
                // Bright pixels estimate paper rather than dark text; keep a safe floor.
                if (gray >= 96) { background[tile] += gray; counts[tile]++ }
            }
        }
        background.indices.forEach { background[it] = if (counts[it] > 0) background[it] / counts[it] else 160f }
        var threshold = 127
        if (profile == OcrImageProfile.OTSU) {
            val total = width.toLong() * height
            val sum = histogram.indices.sumOf { it * histogram[it].toDouble() }
            var left = 0L
            var leftSum = 0.0
            var best = -1.0
            for (i in histogram.indices) {
                left += histogram[i]
                leftSum += i * histogram[i].toDouble()
                val right = total - left
                if (left == 0L || right == 0L) continue
                val delta = leftSum / left - (sum - leftSum) / right
                val variance = left.toDouble() * right * delta * delta
                if (variance > best) { best = variance; threshold = i }
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until height) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("OCR image preparation cancelled")
                source.getPixels(row, 0, width, 0, y, width, 1)
                val ty = (y.toFloat() / tileSize - 0.5f).coerceIn(0f, (rows - 1).toFloat())
                val y0 = ty.toInt(); val y1 = minOf(y0 + 1, rows - 1); val fy = ty - y0
                for (x in row.indices) {
                    val gray = luminance(row[x])
                    val value = if (profile == OcrImageProfile.OTSU) {
                        if (gray > threshold) 255 else 0
                    } else {
                        val tx = (x.toFloat() / tileSize - 0.5f).coerceIn(0f, (columns - 1).toFloat())
                        val x0 = tx.toInt(); val x1 = minOf(x0 + 1, columns - 1); val fx = tx - x0
                        val upper = background[y0 * columns + x0] * (1 - fx) + background[y0 * columns + x1] * fx
                        val lower = background[y1 * columns + x0] * (1 - fx) + background[y1 * columns + x1] * fx
                        (gray * 245f / (upper * (1 - fy) + lower * fy).coerceAtLeast(96f)).roundToInt().coerceIn(0, 255)
                    }
                    row[x] = Color.rgb(value, value, value)
                }
                result.setPixels(row, 0, width, 0, y, width, 1)
            }
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }
}
