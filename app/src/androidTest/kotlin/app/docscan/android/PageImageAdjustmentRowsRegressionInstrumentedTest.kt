package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.edit.PageImageEditState
import java.util.Random
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageImageAdjustmentRowsRegressionInstrumentedTest {
    @Test
    fun rowRendererMatchesPreviousFullArrayAlgorithmForSmallAndSeededImages() {
        val random = Random(0xD0C5CAFL)
        val dimensions = listOf(1 to 1, 1 to 9, 11 to 1, 2 to 2, 17 to 13, 29 to 7)
        val states = listOf(
            PageImageEditState(),
            PageImageEditState(brightness = -100, contrastPercent = 0),
            PageImageEditState(brightness = 100, contrastPercent = 200),
            PageImageEditState(brightness = -37, contrastPercent = 175, sharpnessPercent = 100),
            PageImageEditState(brightness = 42, contrastPercent = 0, sharpnessPercent = 200),
            PageImageEditState(brightness = 0, contrastPercent = 200, sharpnessPercent = 1),
        )

        dimensions.forEach { (width, height) ->
            val pixels = IntArray(width * height) {
                (random.nextInt(256) shl 24) or
                    (random.nextInt(256) shl 16) or
                    (random.nextInt(256) shl 8) or
                    random.nextInt(256)
            }
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
            try {
                for (turns in 0..3) for (parameters in states) {
                    val state = parameters.copy(clockwiseTurns = turns)
                    val expected = renderWithPreviousFullArrayAlgorithm(source, state)
                    val actual = PageImageAdjustmentProcessor.render(source, state)
                    try {
                        assertEquals("width for ${width}x$height, $state", expected.width, actual.width)
                        assertEquals("height for ${width}x$height, $state", expected.height, actual.height)
                        assertArrayEquals(
                            "pixels for seeded ${width}x$height, $state",
                            pixelsOf(expected),
                            pixelsOf(actual),
                        )
                    } finally {
                        expected.recycle()
                        actual.recycle()
                    }
                }
            } finally {
                source.recycle()
            }
        }
    }

    @Test
    fun noOpRenderReturnsIndependentBitmap() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3185c7.toInt()) }
        try {
            val sourcePixel = source.getPixel(0, 0)
            val output = PageImageAdjustmentProcessor.render(source, PageImageEditState())
            try {
                assertNotSame("The caller must own a separate output bitmap", source, output)
                assertTrue("Rendering must keep the source available", !source.isRecycled)
                output.setPixel(0, 0, 0xffabcdef.toInt())
                assertEquals("Changing output must not mutate the source", sourcePixel, source.getPixel(0, 0))
            } finally {
                output.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun rowRendererHandlesImageAboveFourMegapixelsWithinTwelveMegapixelBound() {
        val width = 2048
        val height = 2304
        val pixelCount = width.toLong() * height
        assertTrue(pixelCount > 4L * 1024L * 1024L)
        assertTrue(pixelCount <= PageImageAdjustmentProcessor.MAX_SOURCE_PIXELS)
        val sourceColor = 0xff808080.toInt()
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(sourceColor) }
        try {
            val output = PageImageAdjustmentProcessor.render(
                source,
                PageImageEditState(brightness = 0, contrastPercent = 100, sharpnessPercent = 200),
            )
            try {
                assertEquals(width, output.width)
                assertEquals(height, output.height)
                assertEquals(sourceColor, output.getPixel(0, 0))
                assertEquals(sourceColor, output.getPixel(width / 2, height / 2))
                assertEquals(sourceColor, output.getPixel(width - 1, height - 1))
                assertTrue("Large-image output must be independent", source !== output)
            } finally {
                output.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun renderWithPreviousFullArrayAlgorithm(source: Bitmap, state: PageImageEditState): Bitmap {
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
            val width = rotated.width
            val height = rotated.height
            val original = IntArray(width * height)
            val adjusted = IntArray(width * height)
            rotated.getPixels(original, 0, width, 0, 0, width, height)
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
                val sharpened = original.copyOf()
                val amount = state.sharpnessPercent / 100f
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
                    sharpened[index] = (center ushr 24 shl 24) or (outRed shl 16) or (outGreen shl 8) or outBlue
                }
                sharpened
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(outputPixels, 0, width, 0, 0, width, height)
            }
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    private fun adjust(channel: Int, contrast: Float, brightness: Int): Int =
        (((channel - 127.5f) * contrast) + 127.5f + brightness).roundToInt().coerceIn(0, 255)

    private fun pixelsOf(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
