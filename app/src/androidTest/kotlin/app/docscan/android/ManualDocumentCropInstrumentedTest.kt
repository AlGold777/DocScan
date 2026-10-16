package app.docscan.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualDocumentCropInstrumentedTest {
    @Test
    fun perspectiveCropProducesSeparateBoundedBitmapAndLeavesSourceDimensionsUnchanged() {
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        try {
            val result = DocumentCropProcessor.rectifyManual(
                source,
                CropQuadrilateral(
                    NormalizedCropPoint(0.1, 0.1),
                    NormalizedCropPoint(0.9, 0.1),
                    NormalizedCropPoint(0.8, 0.9),
                    NormalizedCropPoint(0.2, 0.9),
                ),
            )
            try {
                assertTrue(result !== source)
                assertTrue(result.width < source.width)
                assertTrue(result.height < source.height)
                assertEquals(300, source.width)
                assertEquals(200, source.height)
            } finally {
                result.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
