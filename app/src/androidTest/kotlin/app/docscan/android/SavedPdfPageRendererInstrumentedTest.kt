package app.docscan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.schedule.HeavyWorkScheduler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedPdfPageRendererInstrumentedTest {
    @Test
    fun rendersOneBoundedPageAtATimeAndRejectsInvalidPageAndPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "preview-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.addPage(PDPage(PDRectangle(842f, 595f)))
            document.save(pdf)
        }
        try {
            val scheduler = HeavyWorkScheduler()
            val portrait = SavedPdfPageRenderer.renderPage(pdf, 2, 0, scheduler)
            assertEquals(2, portrait.pageCount)
            assertEquals(0, portrait.pageIndex)
            assertTrue(portrait.bitmap.width > 0 && portrait.bitmap.height > 0)
            assertTrue(maxOf(portrait.bitmap.width, portrait.bitmap.height) <= SavedPdfPageRenderer.MAX_LONG_SIDE_PX)
            portrait.bitmap.recycle()

            val landscape = SavedPdfPageRenderer.renderPage(pdf, 2, 1, scheduler)
            assertEquals(1, landscape.pageIndex)
            assertTrue(landscape.bitmap.width > landscape.bitmap.height)
            landscape.bitmap.recycle()
            assertThrows(IllegalArgumentException::class.java) {
                SavedPdfPageRenderer.renderPage(pdf, 2, 2, scheduler)
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun corruptPdfDoesNotReturnAPreviewBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "preview-corrupt-${System.nanoTime()}.pdf")
        pdf.writeBytes("not a PDF".toByteArray())
        try {
            assertThrows(Exception::class.java) {
                SavedPdfPageRenderer.renderPage(pdf, 1, 0, HeavyWorkScheduler())
            }
        } finally {
            pdf.delete()
        }
    }
}
