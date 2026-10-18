package app.docscan.core.ocr

import app.docscan.core.store.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HocrParserTest {

    @Test
    fun parsesTwoColumnDocumentInNaturalReadingOrder() {
        val hocr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
                "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
             <head><title></title></head>
             <body>
              <div class='ocr_page' id='page_1' title='image "doc.png"; bbox 0 0 2000 1000; ppageno 0'>
               <!-- Column 1: Left column -->
               <div class='ocr_carea' id='block_1_1' title='bbox 100 100 900 800'>
                <p class='ocr_par' id='par_1_1' title='bbox 100 100 900 400'>
                 <span class='ocr_line' id='line_1_1' title='bbox 100 100 900 200; baseline 0 -10'>
                  <span class='ocrx_word' id='word_1_1' title='bbox 100 100 400 200; x_wconf 95'>Колонка</span>
                  <span class='ocrx_word' id='word_1_2' title='bbox 450 100 600 200; x_wconf 92'>1</span>
                  <span class='ocrx_word' id='word_1_3' title='bbox 650 100 900 200; x_wconf 90'>строка</span>
                  <span class='ocrx_word' id='word_1_4' title='bbox 910 100 950 200; x_wconf 91'>1</span>
                 </span>
                 <span class='ocr_line' id='line_1_2' title='bbox 100 250 900 350; baseline 0 -8'>
                  <span class='ocrx_word' id='word_1_5' title='bbox 100 250 400 350; x_wconf 88'>Колонка</span>
                  <span class='ocrx_word' id='word_1_6' title='bbox 450 250 600 350; x_wconf 94'>1</span>
                  <span class='ocrx_word' id='word_1_7' title='bbox 650 250 900 350; x_wconf 89'>строка</span>
                  <span class='ocrx_word' id='word_1_8' title='bbox 910 250 950 350; x_wconf 90'>2</span>
                 </span>
                </p>
               </div>
               <!-- Column 2: Right column -->
               <div class='ocr_carea' id='block_1_2' title='bbox 1100 100 1900 800'>
                <p class='ocr_par' id='par_1_2' title='bbox 1100 100 1900 400'>
                 <span class='ocr_line' id='line_1_3' title='bbox 1100 100 1900 200; baseline 0 -12'>
                  <span class='ocrx_word' id='word_1_9' title='bbox 1100 100 1400 200; x_wconf 96'>Колонка</span>
                  <span class='ocrx_word' id='word_1_10' title='bbox 1450 100 1600 200; x_wconf 93'>2</span>
                  <span class='ocrx_word' id='word_1_11' title='bbox 1650 100 1900 200; x_wconf 91'>строка</span>
                  <span class='ocrx_word' id='word_1_12' title='bbox 1910 100 1950 200; x_wconf 92'>1</span>
                 </span>
                </p>
               </div>
              </div>
             </body>
            </html>
        """.trimIndent()

        val layout = HocrParser.parse(hocr)
        assertEquals(2000, layout.sourceWidthPx)
        assertEquals(1000, layout.sourceHeightPx)
        assertEquals(2, layout.blocks.size)

        val block1 = layout.blocks[0]
        val block2 = layout.blocks[1]
        assertEquals(0, block1.readingOrder)
        assertEquals(1, block2.readingOrder)
        assertEquals(2, block1.lines.size)
        assertEquals(1, block2.lines.size)

        // Baseline verification
        assertNotNull(block1.lines[0].baseline)
        assertEquals(0f, block1.lines[0].baseline?.slope)
        assertEquals(-10f, block1.lines[0].baseline?.intercept)

        // Verify plain text has Column 1 first, then Column 2 (not interleaved!)
        val plainText = layout.toPlainText()
        val expected = "Колонка 1 строка 1\nКолонка 1 строка 2\n\nКолонка 2 строка 1"
        assertEquals(expected, plainText)

        // Verify legacy geometry projection
        val geometry = layout.toLegacyGeometry()
        assertEquals(2000L, geometry.sourceWidthPx)
        assertEquals(1000L, geometry.sourceHeightPx)
        assertEquals(12, geometry.words.size)
        assertEquals("Колонка", geometry.words.first().text)
        assertEquals("1", geometry.words.last().text)
    }

    @Test
    fun parsesSemanticBlockTypesAndHtmlEntities() {
        val hocr = """
            <div class='ocr_page' title='bbox 0 0 1000 800'>
             <div class='ocr_header' id='hdr_1' title='bbox 50 20 950 80'>
              <span class='ocr_line' title='bbox 50 20 950 80'>
               <span class='ocrx_word' title='bbox 50 20 300 80; x_wconf 98'>HEADER &amp; TITLE</span>
              </span>
             </div>
             <div class='ocr_carea' id='carea_1' title='bbox 50 100 950 600'>
              <p class='ocr_par' title='bbox 50 100 950 600'>
               <span class='ocr_line' title='bbox 50 100 950 200'>
                <span class='ocrx_word' title='bbox 50 100 400 200; x_wconf 90'>Tom &quot;Cat&quot;</span>
               </span>
              </p>
             </div>
             <div class='ocr_footer' id='ftr_1' title='bbox 50 700 950 780'>
              <span class='ocr_line' title='bbox 50 700 950 780'>
               <span class='ocrx_word' title='bbox 50 700 300 780; x_wconf 85'>Footer 1</span>
              </span>
             </div>
            </div>
        """.trimIndent()

        val layout = HocrParser.parse(hocr)
        assertEquals(3, layout.blocks.size)
        assertEquals(BlockType.HEADER, layout.blocks[0].type)
        assertEquals(BlockType.PARAGRAPH, layout.blocks[1].type)
        assertEquals(BlockType.FOOTER, layout.blocks[2].type)

        assertEquals("HEADER & TITLE", layout.blocks[0].lines[0].words[0].text)
        assertEquals("Tom \"Cat\"", layout.blocks[1].lines[0].words[0].text)
    }

    @Test
    fun handlesEmptyAndMalformedHocrGracefully() {
        val emptyLayout = HocrParser.parse("", fallbackWidthPx = 800, fallbackHeightPx = 600)
        assertEquals(800, emptyLayout.sourceWidthPx)
        assertEquals(600, emptyLayout.sourceHeightPx)
        assertTrue(emptyLayout.blocks.isEmpty())
        assertEquals("", emptyLayout.toPlainText())

        val malformedHocr = "<html><body><div>Just some raw text without hocr markup</div></body></html>"
        val malformedLayout = HocrParser.parse(malformedHocr, 100, 200)
        assertEquals(100, malformedLayout.sourceWidthPx)
        assertEquals(200, malformedLayout.sourceHeightPx)
        assertTrue(malformedLayout.blocks.isEmpty())
    }

    @Test
    fun testParseHocrSnippetFromPlan() {
        val hocr = """
            <div class='ocr_page' title='bbox 0 0 1000 1400'>
             <div class='ocr_carea' title='bbox 100 100 900 200'><p class='ocr_par' title='bbox 100 100 900 200'>
              <span class='ocr_line' title='bbox 100 100 900 150'>
               <span class='ocrx_word' title='bbox 100 100 300 150; x_wconf 95'>Привет</span>
               <span class='ocrx_word' title='bbox 320 100 500 150; x_wconf 93'>мир</span></span>
              <span class='ocr_line' title='bbox 100 160 900 200'>
               <span class='ocrx_word' title='bbox 100 160 400 200; x_wconf 90'>Вторая</span></span>
             </p></div>
             <div class='ocr_carea' title='bbox 100 300 900 350'><p class='ocr_par' title='bbox 100 300 900 350'>
              <span class='ocr_line' title='bbox 100 300 900 350'>
               <span class='ocrx_word' title='bbox 100 300 400 350; x_wconf 90'>Другой</span></span>
             </p></div></div>
        """.trimIndent()

        val layout = parseHocr(hocr, 1000, 1400)
        assertEquals("Привет мир\nВторая\n\nДругой", layout.toPlainText())
    }

    @Test
    fun testWordWithRightExceedingWidthAndInvisibleChar() {
        val hocr = """
            <div class='ocr_page' title='bbox 0 0 100 100'>
             <div class='ocr_carea' title='bbox 0 0 100 100'><p class='ocr_par' title='bbox 0 0 100 100'>
              <span class='ocr_line' title='bbox 0 0 100 50'>
               <span class='ocrx_word' title='bbox 10 10 105 40; x_wconf 90'>Слово</span>
               <span class='ocrx_word' title='bbox 10 10 20 40; x_wconf 90'>&#x200B;</span>
              </span>
             </p></div></div>
        """.trimIndent()

        val layout = parseHocr(hocr, 100, 100)
        assertEquals(1, layout.words.size)
        assertEquals("Слово", layout.words[0].text)
        assertEquals(100, layout.words[0].box.r)
    }
}
