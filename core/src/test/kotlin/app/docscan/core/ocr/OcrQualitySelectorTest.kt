package app.docscan.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrQualitySelectorTest {
    @Test
    fun favorsAdditionalLettersAndDigitsWhenFirstPassTextIsRetained() {
        val first = layout("INVOICE 123", confidence = 72f)
        val second = layout("INVOICE 1234", confidence = 68f)

        assertEquals(second, OcrQualitySelector.choose(first, second))
    }

    @Test
    fun preservesBroadCharacterCoverageInsteadOfChoosingShorterHighConfidenceText() {
        val first = layout("INVOICE 12345", confidence = 61f)
        val second = layout("INVC 123", confidence = 99f)

        assertEquals(first, OcrQualitySelector.choose(first, second))
    }

    @Test
    fun usesConfidenceAndLanguageCompatibilityWhenCharacterCoverageIsEqual() {
        val expected = layout("Texto 42", confidence = 70f, language = "eng", hints = setOf("eng", "spa"))
        val mismatching = layout("Texto 42", confidence = 70f, language = "deu", hints = setOf("eng", "spa"))

        assertEquals(expected, OcrQualitySelector.choose(expected, mismatching))
        val higherConfidence = layout("Texto 42", confidence = 92f, language = "eng", hints = setOf("eng", "spa"))
        assertEquals(higherConfidence, OcrQualitySelector.choose(expected, higherConfidence))
    }

    @Test
    fun emptyPassesKeepFirstAndSelectedPassRetainsAllOfItsWords() {
        val emptyFirst = OcrLayout(100, 100, emptyList())
        val emptySecond = OcrLayout(100, 100, emptyList(), plainText = "")
        assertEquals(emptyFirst, OcrQualitySelector.choose(emptyFirst, emptySecond))

        val first = layout("weak", confidence = 42f)
        val selected = layout("weak strong", confidence = 88f)
        val chosen = OcrQualitySelector.choose(first, selected)
        assertEquals(selected, chosen)
        assertEquals(listOf("weak", "strong"), chosen.words.map { it.text })
    }

    @Test
    fun shortSpanishDiacriticsAreDetectedButNumbersAreNotLanguageEvidence() {
        assertEquals(OcrLangProfile.ES_EN, LangDetect.decide("Niña café"))
        assertNull(LangDetect.decide("INV-2026 12345 90.25"))
    }

    @Test
    fun credibleNumberIsPreservedWhenTheOtherPassChangesIt() {
        val first = layout("Total 42", confidence = 86f)
        val second = layout("Total 43", confidence = 93f)
        assertEquals(first, OcrQualitySelector.choose(first, second))
    }

    @Test
    fun lowConfidenceNumberCanBeCorrectedWhenSecondPassRecoversMoreContent() {
        val first = layout("Total 45", confidence = 45f)
        val second = layout("Total 90 Amount 4.50", confidence = 78f)
        assertEquals(second, OcrQualitySelector.choose(first, second))
    }

    @Test
    fun correctedDiacriticsKeepCharacterCoverageAndSpanishLanguageEvidence() {
        val first = layout("Nina cafe", confidence = 74f, language = "eng", hints = setOf("eng", "spa"))
        val corrected = layout("Niña café", confidence = 74f, language = "spa", hints = setOf("spa", "eng"))
        assertEquals(corrected, OcrQualitySelector.choose(first, corrected))
    }

    private fun layout(text: String, confidence: Float, language: String = "eng", hints: Set<String> = setOf("eng")): OcrLayout {
        val words = text.split(' ').filter(String::isNotBlank).mapIndexed { index, token ->
            OcrWord(token, Bx(index * 20, 10, index * 20 + 18, 30), confidence)
        }
        val line = OcrLine(Bx(0, 10, maxOf(1, words.size * 20), 30), words)
        val paragraph = OcrPara(line.box, listOf(line))
        val block = OcrBlock(line.box, listOf(paragraph))
        return OcrLayout(200, 100, listOf(block), lang = language, languageHints = hints)
    }
}
