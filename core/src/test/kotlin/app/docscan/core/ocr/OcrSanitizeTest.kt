package app.docscan.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrSanitizeTest {

    @Test
    fun testSanitizeOcrText() {
        assertEquals("ab", sanitizeOcrText("a\u200Bb"))
        assertEquals("", sanitizeOcrText("\u200B"))
        assertEquals("слово", sanitizeOcrText(" слово "))
    }

    @Test
    fun testClampBox() {
        // рамка с right = w + 1 даёт right = w
        val b1 = clampBox(10, 10, 101, 50, 100, 100)
        assertEquals(Bx(10, 10, 100, 50), b1)

        // рамка нулевой ширины l=5, r=5 даёт ширину 1
        val b2 = clampBox(5, 10, 5, 20, 100, 100)
        assertEquals(Bx(5, 10, 6, 20), b2)
        assertEquals(1, b2!!.r - b2.l)

        // w <= 1 or h <= 1 returns null
        assertNull(clampBox(0, 0, 10, 10, 1, 100))
        assertNull(clampBox(0, 0, 10, 10, 100, 1))
    }
}
