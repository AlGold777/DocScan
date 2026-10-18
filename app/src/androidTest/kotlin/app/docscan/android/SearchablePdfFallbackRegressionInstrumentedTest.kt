package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchablePdfFallbackRegressionInstrumentedTest {
    @Test
    fun fallbackKeepsAtLeastEightyLinesAndDoesNotTruncateLongLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val longLine = "LongLine-" + "0123456789".repeat(180)
        val fallback = (1..80).map { "FallbackLine-%03d".format(it) }.plus(longLine).joinToString("\n")
        val output = File(context.cacheDir, "fallback-regression-${System.nanoTime()}.pdf")
        try {
            SearchablePdfWriter(context).write(
                output,
                listOf(PdfInputPage(openBitmap = { bitmap.copy(Bitmap.Config.ARGB_8888, false) }, text = fallback)),
                PdfOutputSettings(595f, 842f, 0.85f, 300),
            )
            PDDocument.load(output).use { document ->
                val extracted = PDFTextStripper().getText(document)
                assertTrue("The 80th fallback line must survive PDF generation", extracted.contains("FallbackLine-080"))
                val extractedWithoutWhitespace = extracted.filterNot(Char::isWhitespace)
                val expectedWithoutWhitespace = longLine.filterNot(Char::isWhitespace)
                assertTrue(
                    "Long fallback text must retain every character across PDF line wrapping",
                    extractedWithoutWhitespace.contains(expectedWithoutWhitespace),
                )
            }
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }

    @Test
    fun blankTextOnlyPdfIsRejectedWithoutPublishingOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = File(context.cacheDir, "blank-text-only-${System.nanoTime()}.pdf")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SearchablePdfWriter(context).write(
                    output,
                    listOf(PdfInputPage(openBitmap = { bitmap.copy(Bitmap.Config.ARGB_8888, false) }, text = " \n\t ")),
                    PdfOutputSettings(595f, 842f, 0.85f, 300, includeImage = false),
                )
            }
            assertFalse("Rejected text-only output must not leave a final PDF", output.exists())
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }
}
