package app.docscan.core.ocr

import app.docscan.core.store.BlockType
import app.docscan.core.store.Baseline
import app.docscan.core.store.LayoutQuality
import app.docscan.core.store.NormalizedBox
import app.docscan.core.store.OcrBlock as RecognitionBlock
import app.docscan.core.store.OcrLine as RecognitionLine
import app.docscan.core.store.OcrParagraph
import app.docscan.core.store.OcrWord as RecognitionWord
import app.docscan.core.store.RecognitionLayout
import app.docscan.core.store.RecognitionPayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutConversionTest {
    @Test
    fun conversionsComputeAndPreserveConfidenceLanguageAndPlainText() {
        val words = listOf(
            OcrWord("Invoice", Bx(10, 10, 80, 30), 59f),
            OcrWord("42", Bx(85, 10, 105, 30), 90f),
        )
        val line = OcrLine(Bx(10, 10, 105, 30), words)
        val paragraph = OcrPara(line.box, listOf(line))
        val block = OcrBlock(line.box, listOf(paragraph))
        val extracted = OcrLayout(200, 100, listOf(block), lang = "eng", languageHints = setOf("eng", "spa"))

        val recognition = extracted.toRecognitionLayout()
        assertEquals(74.5f, recognition.quality?.meanConfidence ?: 0f, 0.001f)
        assertEquals(1, recognition.quality?.lowConfidenceWordCount)
        assertTrue(recognition.quality?.structureAvailable == true)
        assertEquals("eng", recognition.quality?.language)
        assertEquals(setOf("eng", "spa"), recognition.languageHints)
        assertEquals("Invoice 42", recognition.toPlainText())

        val restored = recognition.toOcrLayout()
        assertEquals(recognition.quality, restored.quality)
        assertEquals(recognition.languageHints, restored.languageHints)
        assertEquals("eng", restored.lang)
        assertEquals("Invoice 42", restored.toPlainText())
    }

    @Test
    fun providedQualityIsNotReplacedAndTextOnlyLayoutsKeepTheirText() {
        val suppliedQuality = LayoutQuality(
            meanConfidence = 31f,
            lowConfidenceWordCount = 7,
            structureAvailable = false,
            language = "spa",
            preprocessingProfile = "ADAPTIVE",
            engineVersion = "TESSERACT_5.5.1",
        )
        val recognition = RecognitionLayout(
            sourceWidthPx = 100,
            sourceHeightPx = 100,
            blocks = emptyList(),
            languageHints = setOf("spa", "eng"),
            quality = suppliedQuality,
            plainText = "Texto recognizado 2026",
        )

        val ocr = recognition.toOcrLayout()
        assertEquals("Texto recognizado 2026", ocr.toPlainText())
        assertEquals(suppliedQuality, ocr.quality)
        assertEquals(setOf("spa", "eng"), ocr.languageHints)
        assertEquals("Texto recognizado 2026", ocr.toRecognitionLayout().toPlainText())
        assertFalse(ocr.toRecognitionLayout().quality?.structureAvailable ?: true)

        val encoded = RecognitionPayloadCodec.encode("Fallback transcript", RecognitionLayout(100, 100, emptyList()))
        val decoded = RecognitionPayloadCodec.decode(encoded)
        assertEquals("Fallback transcript", decoded.text)
        assertEquals("Fallback transcript", decoded.layout?.plainText)
        assertEquals("Fallback transcript", decoded.ocrLayout?.toPlainText())
    }

    @Test
    fun scaledLayoutPreservesPlainTextQualityAndTransformsBaseline() {
        val quality = LayoutQuality(88f, 2, true, language = "eng", engineVersion = "UNKNOWN")
        val baseline = Baseline(slope = 0.5f, intercept = 4f)
        val line = OcrLine(
            Bx(10, 20, 50, 40),
            listOf(OcrWord("Text", Bx(10, 20, 40, 40), 88f)),
            baseline,
        )
        val original = OcrLayout(
            sourceW = 100,
            sourceH = 100,
            blocks = listOf(OcrBlock(Bx(10, 20, 50, 40), listOf(OcrPara(Bx(10, 20, 50, 40), listOf(line))))),
            lang = "eng",
            plainText = "Text fallback",
            quality = quality,
            languageHints = setOf("eng"),
        )

        val scaled = original.scaledTo(101, 100)

        assertEquals("Text fallback", scaled?.plainText)
        assertEquals(quality, scaled?.quality)
        assertEquals(setOf("eng"), scaled?.languageHints)
        assertEquals("eng", scaled?.lang)
        val scaledBaseline = scaled!!.blocks.single().paras.single().lines.single().baseline!!
        assertEquals(0.5f / 1.01f, scaledBaseline.slope, 0.0001f)
        assertEquals(4f, scaledBaseline.intercept, 0.0001f)
    }

    @Test
    fun textOnlyRecognitionLayoutPlainTextFallsBackWhenThereAreNoWords() {
        val emptyBlock = RecognitionBlock(
            id = "empty",
            type = BlockType.PARAGRAPH,
            box = NormalizedBox(0f, 0f, 1f, 1f),
            readingOrder = 0,
            paragraphs = listOf(
                OcrParagraph(
                    box = NormalizedBox(0f, 0f, 1f, 1f),
                    lines = listOf(RecognitionLine(
                        box = NormalizedBox(0f, 0f, 1f, 1f),
                        baseline = null,
                        xHeight = null,
                        words = emptyList(),
                    )),
                ),
            ),
        )
        val layout = RecognitionLayout(10, 10, listOf(emptyBlock), plainText = "No words, retained transcript")

        assertEquals("No words, retained transcript", layout.toPlainText())
        assertNull(layout.quality)
    }

    @Test
    fun completePdfTranscriptRemainsAuthoritativeWhenSomeGlyphBoxesAreAvailable() {
        val box = NormalizedBox(0.1f, 0.1f, 0.5f, 0.2f)
        val partialBlock = RecognitionBlock(
            id = "pdf-line-1",
            type = BlockType.TEXT,
            box = box,
            readingOrder = 0,
            paragraphs = listOf(OcrParagraph(box, listOf(RecognitionLine(
                box = box,
                baseline = null,
                xHeight = null,
                words = listOf(RecognitionWord("Coordinates", box, 100f)),
            )))),
        )
        val fullTranscript = "Coordinates for part of line\nAnd remaining PDF text without boxes"
        val recognition = RecognitionLayout(1000, 1400, listOf(partialBlock), plainText = fullTranscript)

        assertEquals(fullTranscript, recognition.toPlainText())
        assertEquals(fullTranscript, recognition.toOcrLayout().toPlainText())
        assertEquals(fullTranscript, recognition.toOcrLayout().toRecognitionLayout().toPlainText())
    }
}
