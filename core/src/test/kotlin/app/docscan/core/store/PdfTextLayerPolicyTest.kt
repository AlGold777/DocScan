package app.docscan.core.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextLayerPolicyTest {
    @Test
    fun acceptsTwentyOrMoreLatinOrCyrillicCharactersWithAtLeastSeventyPercentSupported() {
        assertTrue(PdfTextLayerPolicy.isReusable("Scan document page number 123"))
        assertTrue(PdfTextLayerPolicy.isReusable("Документ страницы сканирования 123"))
        assertTrue(PdfTextLayerPolicy.isReusable("12345678901234------"))
        assertFalse(PdfTextLayerPolicy.isReusable("1234567890123-------"))
    }

    @Test
    fun rejectsShortWhitespaceOnlyOrUnsupportedScriptText() {
        assertFalse(PdfTextLayerPolicy.isReusable("Short text 123"))
        assertFalse(PdfTextLayerPolicy.isReusable("                    "))
        assertFalse(PdfTextLayerPolicy.isReusable("Καλημέρα κόσμε και καλώς ορίσατε"))
    }
}
