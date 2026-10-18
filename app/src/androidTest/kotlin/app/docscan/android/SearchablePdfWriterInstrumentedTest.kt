package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.ocr.toRecognitionLayout
import app.docscan.core.ocr.toPlainText
import app.docscan.core.store.Baseline
import app.docscan.core.store.NormalizedBox
import app.docscan.core.store.OcrBlock
import app.docscan.core.store.OcrLine
import app.docscan.core.store.OcrParagraph
import app.docscan.core.store.OcrWord
import app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
import app.docscan.core.store.RecognitionGeometry
import app.docscan.core.store.RecognitionLayout
import app.docscan.core.store.RecognitionPayloadCodec
import app.docscan.core.store.RecognizedWord
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchablePdfWriterInstrumentedTest {
    @Test
    fun SpanishPlainTextKeepsDiaeresisAndPunctuationAcrossCodecAndPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val truth = "¿Qué año? pingüino ¡Número 4821!"
        val encoded = RecognitionPayloadCodec.encodeLayout(
            app.docscan.core.ocr.OcrLayout(800, 1000, emptyList(), plainText = truth),
        )
        val decoded = RecognitionPayloadCodec.decodeLayout(encoded)
        assertEquals(truth, decoded.toPlainText())
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = File(context.cacheDir, "searchable-spanish-codec-${System.nanoTime()}.pdf")
        try {
            SearchablePdfWriter(context).write(
                output,
                listOf(PdfInputPage(
                    { bitmap.copy(Bitmap.Config.ARGB_8888, false) },
                    layout = decoded.toRecognitionLayout(),
                    text = decoded.toPlainText(),
                )),
                PdfOutputSettings(595f, 842f, 0.85f, 300, includeImage = false),
            )
            PDDocument.load(output).use { document ->
                val extracted = PDFTextStripper().getText(document)
                assertEquals(
                    SearchablePdfWriter.normalizedTextContent(truth),
                    SearchablePdfWriter.normalizedTextContent(extracted),
                )
            }
        } finally { bitmap.recycle(); output.delete() }
    }

    @Test
    fun positionedTextUsesSlopedHocrBaselineInPdfCoordinates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val lineBox = NormalizedBox(0.125f, 0.1f, 0.5f, 0.15f)
        val word = OcrWord("BASE", NormalizedBox(0.125f, 0.1f, 0.25f, 0.15f), 99f)
        val line = OcrLine(lineBox, Baseline(0.1f, -10f), null, listOf(word))
        val paragraph = OcrParagraph(lineBox, listOf(line))
        val block = OcrBlock("b0", app.docscan.core.store.BlockType.TEXT, lineBox, 0, listOf(paragraph))
        val layout = RecognitionLayout(800, 1000, listOf(block))
        val output = File(context.cacheDir, "searchable-baseline-${System.nanoTime()}.pdf")
        try {
            SearchablePdfWriter(context).write(
                output,
                listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, layout = layout)),
                PdfOutputSettings(595f, 842f, 0.85f, 300),
            )
            PDDocument.load(output).use { document ->
                var found: TextPosition? = null
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: List<TextPosition>) {
                        super.writeString(text, textPositions)
                        if (found == null && text.contains("BASE")) found = textPositions.firstOrNull()
                    }
                }
                assertTrue(stripper.getText(document).contains("BASE"))
                val position = requireNotNull(found) { "PDF text position for BASE was not exposed" }
                val scale = minOf(595f / 800f, 842f / 1000f)
                val offsetY = (842f - 1000f * scale) / 2f
                val expectedBaselineY = offsetY + (1000f - 140f) * scale
                assertEquals(expectedBaselineY, position.textMatrix.translateY, 1f)
                val slope = position.textMatrix.shearY / position.textMatrix.scaleX
                assertEquals(-0.1f, slope, 0.01f)
            }
        } finally { bitmap.recycle(); output.delete() }
    }

    @Test
    fun pdfBoxWritesInvisibleSearchableMixedScriptTextAndKeepsImageVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawColor(Color.WHITE)
        for (y in 20 until 60) for (x in 20 until 60) bitmap.setPixel(x, y, Color.BLACK)
        val geometry = RecognitionGeometry(
            600L, 800L, OCR_WORD_COORDINATE_SPACE,
            listOf(
                RecognizedWord("Invoice", 100L, 300L, 350L, 350L, 97.0),
                RecognizedWord("Счёт", 390L, 370L, 560L, 420L, 91.0),
            ),
        )
        val output = File(context.cacheDir, "searchable-${System.nanoTime()}.pdf")
        val noGeometryOutput = File(context.cacheDir, "searchable-no-geometry-${System.nanoTime()}.pdf")
        val mismatchedGeometryOutput = File(context.cacheDir, "searchable-mismatched-geometry-${System.nanoTime()}.pdf")
        try {
            val result = SearchablePdfWriter(context).write(
                output,
                listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, geometry)),
                PdfOutputSettings(595f, 842f, 0.85f, 300),
            )
            assertEquals(1, result.pageCount)
            assertTrue(result.fileSizeBytes > 0L)

            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                assertEquals(595f, document.getPage(0).mediaBox.width, 0.1f)
                val extractedWords = mutableListOf<Pair<String, List<TextPosition>>>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: List<TextPosition>) {
                        super.writeString(text, textPositions)
                        if (text.isNotBlank()) extractedWords += text.trim() to textPositions.toList()
                    }
                }
                val extracted = stripper.getText(document).lowercase(Locale.ROOT)
                assertTrue(extracted, extracted.contains("invoice"))
                assertTrue(extracted, extracted.contains("счёт"))

                val fitScale = minOf(595f / bitmap.width, 842f / bitmap.height)
                val expectedOffsetX = (595f - bitmap.width * fitScale) / 2f
                val expectedOffsetY = (842f - bitmap.height * fitScale) / 2f
                assertPdfWordOverlapsSourceBox(
                    extractedWords.single { it.first.contains("Invoice", ignoreCase = true) }.second,
                    expectedOffsetX + 100f * fitScale,
                    expectedOffsetY + 300f * fitScale,
                    expectedOffsetX + 350f * fitScale,
                    expectedOffsetY + 350f * fitScale,
                    "Invoice",
                )
                assertPdfWordOverlapsSourceBox(
                    extractedWords.single { it.first.contains("Счёт", ignoreCase = true) }.second,
                    expectedOffsetX + 390f * fitScale,
                    expectedOffsetY + 370f * fitScale,
                    expectedOffsetX + 560f * fitScale,
                    expectedOffsetY + 420f * fitScale,
                    "Счёт",
                )
                assertTrue("Parsed OCR words must remain in separate source regions", extractedWords.size >= 2)

                val rendered = PDFRenderer(document).renderImage(0)
                try {
                    assertEquals(Color.BLACK, rendered.getPixel(30, 60))
                    var darkPixelsInInvisibleWordArea = 0
                    for (y in 315 until 410) for (x in 90 until 360) {
                        if (Color.red(rendered.getPixel(x, y)) < 128) darkPixelsInInvisibleWordArea++
                    }
                    assertEquals("Invisible OCR text must not alter the page appearance", 0, darkPixelsInInvisibleWordArea)
                } finally {
                    rendered.recycle()
                }
            }

            // A second write to an existing output must fail closed without replacing it.
            val existingBytes = output.readBytes()
            try {
                SearchablePdfWriter(context).write(
                    output, listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, geometry)),
                    PdfOutputSettings(595f, 842f, 0.85f, 300),
                )
                throw AssertionError("Existing output should not be overwritten")
            } catch (expected: IllegalArgumentException) {
                assertFalse(existingBytes.isEmpty())
                assertTrue(existingBytes.contentEquals(output.readBytes()))
            }

            SearchablePdfWriter(context).write(
                noGeometryOutput,
                listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, null)),
                PdfOutputSettings(595f, 842f, 0.85f, 300),
            )
            PDDocument.load(noGeometryOutput).use { document ->
                assertTrue("A Page without OCR geometry must not gain searchable text", PDFTextStripper().getText(document).isBlank())
            }

            try {
                SearchablePdfWriter(context).write(
                    mismatchedGeometryOutput,
                    listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, geometry.copy(sourceWidthPx = 601L))),
                    PdfOutputSettings(595f, 842f, 0.85f, 300),
                )
                throw AssertionError("OCR geometry from another bitmap dimension must fail closed")
            } catch (expected: IllegalArgumentException) {
                assertFalse("Mismatched OCR geometry must not publish a final PDF", mismatchedGeometryOutput.exists())
                assertFalse(
                    "Mismatched OCR geometry must not leave a temporary PDF",
                    context.cacheDir.listFiles()?.any {
                        it.name.startsWith(".${mismatchedGeometryOutput.name}.") && it.name.endsWith(".building")
                    } == true,
                )
            }
        } finally {
            bitmap.recycle()
            output.delete()
            noGeometryOutput.delete()
            mismatchedGeometryOutput.delete()
        }
    }

    private fun assertPdfWordOverlapsSourceBox(
        positions: List<TextPosition>,
        expectedLeft: Float,
        expectedTop: Float,
        expectedRight: Float,
        expectedBottom: Float,
        word: String,
    ) {
        assertTrue("$word must have parsed glyph positions", positions.isNotEmpty())
        val actualLeft = positions.minOf { it.xDirAdj }
        val actualRight = positions.maxOf { it.xDirAdj + it.widthDirAdj }
        val actualTop = positions.minOf { it.yDirAdj - it.heightDir }
        val actualBottom = positions.maxOf { it.yDirAdj }
        assertTrue("$word starts inside/at its transformed OCR region: $actualLeft !< $expectedRight", actualLeft < expectedRight)
        assertTrue("$word ends inside/after its transformed OCR region: $actualRight !> $expectedLeft", actualRight > expectedLeft)
        assertTrue("$word glyph region should overlap transformed OCR top: $actualBottom !> $expectedTop", actualBottom > expectedTop)
        assertTrue("$word glyph region should overlap transformed OCR bottom: $actualTop !< $expectedBottom", actualTop < expectedBottom)
    }

    @Test
    fun exportWithMissingGlyphAndZeroWidthWordDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val wordWithoutGlyph = app.docscan.core.ocr.OcrWord("∑", app.docscan.core.ocr.Bx(10, 10, 50, 40), 90f)
        val zeroWidthWord = app.docscan.core.ocr.OcrWord("Zero", app.docscan.core.ocr.Bx(50, 10, 50, 40), 90f)
        val normalWord = app.docscan.core.ocr.OcrWord("Норма", app.docscan.core.ocr.Bx(60, 10, 120, 40), 95f)
        val line = app.docscan.core.ocr.OcrLine(app.docscan.core.ocr.Bx(10, 10, 120, 40), listOf(wordWithoutGlyph, zeroWidthWord, normalWord))
        val para = app.docscan.core.ocr.OcrPara(app.docscan.core.ocr.Bx(10, 10, 120, 40), listOf(line))
        val block = app.docscan.core.ocr.OcrBlock(app.docscan.core.ocr.Bx(10, 10, 120, 40), listOf(para))
        val ocrLayout = app.docscan.core.ocr.OcrLayout(400, 400, listOf(block))
        val layout = ocrLayout.toRecognitionLayout()

        val output = File(context.cacheDir, "test-missing-glyph-${System.nanoTime()}.pdf")
        try {
            val result = SearchablePdfWriter(context).write(
                output,
                listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, null, layout)),
                PdfOutputSettings(595f, 842f, 0.85f, 300),
            )
            assertEquals(1, result.pageCount)
            assertTrue(result.fileSizeBytes > 0L)
            assertTrue(output.exists())
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }

    @Test
    fun safeTextPreservesAllSpanishCharactersExactly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spanishText = "¿Qué año, señor? Ñandú ÁÉÍÓÚ ü ¡Hola!"
        PDDocument().use { doc ->
            val font = com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(
                doc,
                context.assets.open("fonts/NotoSans-Regular.ttf"),
                true,
            )
            val cache = HashMap<Int, Boolean>()
            val safe = with(SearchablePdfWriter) { font.safeText(spanishText, cache) }
            assertEquals(spanishText, safe)
        }
    }

    @Test
    fun pdfBoxWritesVisibleTextWithoutImageWhenIncludeImageIsFalse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val geometry = RecognitionGeometry(
            600L, 800L, OCR_WORD_COORDINATE_SPACE,
            listOf(
                RecognizedWord("VisibleOCR", 100L, 300L, 350L, 350L, 99.0),
            ),
        )
        val output = File(context.cacheDir, "visible-ocr-${System.nanoTime()}.pdf")
        try {
            val result = SearchablePdfWriter(context).write(
                output,
                listOf(PdfInputPage({ bitmap.copy(Bitmap.Config.ARGB_8888, false) }, geometry)),
                PdfOutputSettings(595f, 842f, 0.85f, 300, includeImage = false),
            )
            assertEquals(1, result.pageCount)
            assertTrue(result.fileSizeBytes > 0L)

            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                val page = document.getPage(0)
                val images = page.resources.xObjectNames.mapNotNull { name ->
                    page.resources.getXObject(name) as? com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                }
                assertTrue("Page must not contain image XObjects when includeImage is false", images.isEmpty())

                val extracted = PDFTextStripper().getText(document)
                assertTrue("Extracted text must contain VisibleOCR", extracted.contains("VisibleOCR"))

                val rendered = PDFRenderer(document).renderImage(0)
                try {
                    var darkPixels = 0
                    for (y in 0 until rendered.height) {
                        for (x in 0 until rendered.width) {
                            if (Color.red(rendered.getPixel(x, y)) < 128) darkPixels++
                        }
                    }
                    assertTrue("Visible text mode must render visible dark glyph pixels on the page", darkPixels > 0)
                } finally {
                    rendered.recycle()
                }
            }
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }
}
