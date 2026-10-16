package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentCropProcessorInstrumentedTest {
    @Test
    fun rectifiesStrongPageBoundaryWithoutChangingSource() {
        val source = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(30, 30, 30))
        val page = Path().apply {
            moveTo(130f, 100f)
            lineTo(850f, 155f)
            lineTo(790f, 1280f)
            lineTo(190f, 1320f)
            close()
        }
        canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val originalCorner = source.getPixel(0, 0)

        val result = DocumentCropProcessor.rectify(source)

        assertNotNull(result)
        assertEquals(originalCorner, source.getPixel(0, 0))
        assertTrue(requireNotNull(result).width >= 500)
        assertTrue(result.height >= 900)
        result.recycle()
        source.recycle()
    }

    @Test
    fun returnsNoCandidateForUniformImage() {
        val source = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val result = DocumentCropProcessor.rectify(source)

        assertNull(result)
        source.recycle()
    }
}
