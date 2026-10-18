package app.docscan.core.store

import app.docscan.core.ocr.Bx
import app.docscan.core.ocr.OcrBlock
import app.docscan.core.ocr.OcrLine
import app.docscan.core.ocr.OcrPara
import app.docscan.core.ocr.OcrWord
import app.docscan.core.ocr.OcrLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionPayloadCodecTest {

    @Test
    fun testLayoutRoundTrip() {
        val word1 = OcrWord("Привет", Bx(10, 20, 100, 50), 95f)
        val word2 = OcrWord("мир", Bx(110, 20, 200, 50), 90f)
        val line1 = OcrLine(Bx(10, 20, 200, 50), listOf(word1, word2), Baseline(0.25f, -3.5f))
        val para1 = OcrPara(Bx(10, 20, 200, 50), listOf(line1))
        val block1 = OcrBlock(Bx(10, 20, 200, 50), listOf(para1))
        val quality = LayoutQuality(92.5f, 1, true, "spa", "ADAPTIVE", "TESSERACT_5.5.1")
        val layout = OcrLayout(
            sourceW = 800,
            sourceH = 600,
            blocks = listOf(block1),
            lang = "spa+eng",
            plainText = "Preserved transcript",
            quality = quality,
            languageHints = setOf("spa", "eng"),
        )

        val encoded = RecognitionPayloadCodec.encodeLayout(layout)
        val decoded = RecognitionPayloadCodec.decodeLayout(encoded)

        assertEquals(layout, decoded)

        // With lang
        val layoutWithLang = layout.copy(lang = "spa+eng")
        val encodedWithLang = RecognitionPayloadCodec.encodeLayout(layoutWithLang)
        val decodedWithLang = RecognitionPayloadCodec.decodeLayout(encodedWithLang)
        assertEquals(layoutWithLang, decodedWithLang)

        // Legacy /2 without lang field
        val legacyV2WithoutLang = "DOCSCAN-OCR-LAYOUT/2\n{\"w\":800,\"h\":600,\"blocks\":[]}"
        val decodedLegacyV2 = RecognitionPayloadCodec.decodeLayout(legacyV2WithoutLang)
        assertEquals(800, decodedLegacyV2.sourceW)
        assertEquals(600, decodedLegacyV2.sourceH)
        assertNull(decodedLegacyV2.lang)
        assertNull(decodedLegacyV2.plainText)
        assertNull(decodedLegacyV2.quality)
        assertEquals(emptySet<String>(), decodedLegacyV2.languageHints)
    }

    @Test
    fun jsonV2RoundTripPreservesBaselineQualityPlainTextAndLanguageHints() {
        val original = OcrLayout(
            sourceW = 400,
            sourceH = 300,
            blocks = listOf(
                OcrBlock(
                    Bx(1, 2, 100, 40),
                    listOf(OcrPara(
                        Bx(1, 2, 100, 40),
                        listOf(OcrLine(
                            Bx(1, 2, 100, 40),
                            listOf(OcrWord("Line", Bx(1, 2, 50, 40), 76f)),
                            Baseline(-0.5f, 8f),
                        )),
                    )),
                    type = "PARAGRAPH",
                    id = "block-A",
                ),
            ),
            lang = "eng",
            plainText = "Line fallback",
            quality = LayoutQuality(76f, 1, true, "eng", "DEFAULT", "UNKNOWN"),
            languageHints = setOf("eng", "spa"),
        )

        val decoded = RecognitionPayloadCodec.decodeLayout(RecognitionPayloadCodec.encodeLayout(original))

        assertEquals(original, decoded)
    }

    @Test
    fun testDecodeLegacyFormats() {
        // Plain text
        val plainText = "Simple plain text"
        val decodedPlain = RecognitionPayloadCodec.decode(plainText)
        assertEquals("Simple plain text", decodedPlain.text)
        assertNull(decodedPlain.geometry)
        assertNull(decodedPlain.layout)

        // Geometry /1 format
        val word = RecognizedWord("Тест", 10, 10, 50, 30, 99.0)
        val geometry = RecognitionGeometry(
            sourceWidthPx = 100,
            sourceHeightPx = 100,
            coordinateSpace = OCR_WORD_COORDINATE_SPACE,
            words = listOf(word),
            formatVersion = 1L,
        )
        val encodedV1 = RecognitionPayloadCodec.encode("Тест", geometry)
        val decodedV1 = RecognitionPayloadCodec.decode(encodedV1)
        assertEquals("Тест", decodedV1.text)
        assertNotNull(decodedV1.geometry)
        assertEquals(1, decodedV1.geometry!!.words.size)
        assertEquals("Тест", decodedV1.geometry!!.words[0].text)
        assertNull(decodedV1.layout) // /1 does not create blocks/lines
    }
}
