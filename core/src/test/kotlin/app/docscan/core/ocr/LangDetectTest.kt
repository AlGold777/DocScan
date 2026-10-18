package app.docscan.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangDetectTest {
    @Test
    fun spanishParagraphDetectsEsEn() {
        val text = "El contrato de arrendamiento se firmó en la ciudad y las partes acordaron que el pago se hará por mes, con el fin de cumplir lo que se dijo en el documento"
        val result = LangDetect.decide(text)
        assertEquals(OcrLangProfile.ES_EN, result)
    }

    @Test
    fun englishParagraphReturnsNull() {
        val text = "The agreement was signed in the city and the parties agreed that the payment will be made by month in order to fulfill what was stated in the document"
        val result = LangDetect.decide(text)
        assertNull(result)
    }

    @Test
    fun russianParagraphReturnsNull() {
        val text = "Договор аренды был подписан в городе и стороны согласились, что оплата будет производиться ежемесячно для выполнения условий документа"
        val result = LangDetect.decide(text)
        assertNull(result)
    }

    @Test
    fun mixedRussianAndSpanishReturnsRuEsEn() {
        val spanish = "El contrato de arrendamiento se firmó en la ciudad y las partes acordaron que el pago se hará por mes, con el fin de cumplir lo que se dijo en el documento. "
        val russian = "Договор аренды был подписан в городе и стороны согласились, что оплата будет производиться ежемесячно для выполнения условий документа."
        val text = spanish + russian
        val result = LangDetect.decide(text)
        assertEquals(OcrLangProfile.RU_ES_EN, result)
    }

    @Test
    fun shortStringReturnsNull() {
        val text = "Total: 12,50 €"
        val result = LangDetect.decide(text)
        assertNull(result)
    }

    @Test
    fun englishTextWithOneSenorReturnsNull() {
        val text = "Good morning señor, we are pleased to inform you that the agreement is ready for your signature."
        val result = LangDetect.decide(text)
        assertNull(result)
    }
}
