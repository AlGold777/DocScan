package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.edit.PageImageEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageImageAdjustmentProcessorInstrumentedTest {
    @Test
    fun brightnessAndContrastCreateEditedPixelsWithoutMutatingSource() {
        val source = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            val original = source.getPixel(1, 1)
            val edited = PageImageAdjustmentProcessor.render(
                source,
                PageImageEditState(brightness = 25, contrastPercent = 150),
            )
            try {
                assertNotEquals(original, edited.getPixel(1, 1))
                assertEquals(original, source.getPixel(1, 1))
            } finally {
                edited.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun rotationChangesDimensionsAndSharpnessStaysWithinPixelRange() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        source.setPixel(1, 1, Color.WHITE)
        try {
            val edited = PageImageAdjustmentProcessor.render(
                source,
                PageImageEditState(clockwiseTurns = 1, sharpnessPercent = 150),
            )
            try {
                assertEquals(2, edited.width)
                assertEquals(3, edited.height)
                val pixel = edited.getPixel(0, 0)
                assertTrue(Color.alpha(pixel) in 0..255)
                assertTrue(Color.red(pixel) in 0..255)
            } finally {
                edited.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
