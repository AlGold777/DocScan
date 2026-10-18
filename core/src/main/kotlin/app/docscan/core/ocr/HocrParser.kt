package app.docscan.core.ocr

import app.docscan.core.store.Baseline
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

private val BBOX = Regex("""bbox (-?\d+) (-?\d+) (-?\d+) (-?\d+)""")
private val WCONF = Regex("""x_wconf (\d+)""")
private val BASELINE = Regex("""baseline\s+(-?[\d.]+)\s+(-?[\d.]+)""")
private const val LINE_SEL = ".ocr_line, .ocr_header, .ocr_caption, .ocr_textfloat"

private fun org.jsoup.nodes.Element.box(w: Int, h: Int): Bx? {
    val g = BBOX.find(attr("title"))?.groupValues ?: return null
    return clampBox(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), w, h)
}

fun parseHocr(hocr: String, w: Int, h: Int): OcrLayout {
    if (hocr.isBlank()) return OcrLayout(maxOf(1, w), maxOf(1, h), emptyList())
    val doc = Jsoup.parse(hocr, "", Parser.xmlParser())
    val pageElem = doc.selectFirst(".ocr_page")
    val pageBbox = pageElem?.let { BBOX.find(it.attr("title"))?.groupValues }
    val pageW = if (w > 1) w else (pageBbox?.get(3)?.toIntOrNull() ?: maxOf(1, w))
    val pageH = if (h > 1) h else (pageBbox?.get(4)?.toIntOrNull() ?: maxOf(1, h))

    fun parseLine(l: org.jsoup.nodes.Element): OcrLine? {
        val lb = l.box(pageW, pageH) ?: return null
        val words = l.select(".ocrx_word").mapNotNull { e ->
            val text = sanitizeOcrText(e.text())
            val wb = e.box(pageW, pageH)
            if (text.isEmpty() || wb == null) null
            else OcrWord(text, wb, WCONF.find(e.attr("title"))?.groupValues?.get(1)?.toFloatOrNull() ?: 0f)
        }
        val baseMatch = BASELINE.find(l.attr("title"))
        val baseline = if (baseMatch != null) {
            val slope = baseMatch.groupValues[1].toFloatOrNull() ?: 0f
            val intercept = baseMatch.groupValues[2].toFloatOrNull() ?: 0f
            Baseline(slope, intercept)
        } else null
        return if (words.isEmpty()) null else OcrLine(lb, words, baseline)
    }

    val containers = doc.select(".ocr_carea, .ocr_header, .ocr_footer, .ocr_caption")
    val blocks = containers.mapNotNull { area ->
        val ab = area.box(pageW, pageH) ?: return@mapNotNull null
        val parEls = area.select(".ocr_par")
        val paras = if (parEls.isNotEmpty()) {
            parEls.mapNotNull { p ->
                val pb = p.box(pageW, pageH) ?: return@mapNotNull null
                val lines = p.select(LINE_SEL).mapNotNull(::parseLine)
                if (lines.isEmpty()) null else OcrPara(pb, lines)
            }
        } else {
            val lines = area.select(LINE_SEL).mapNotNull(::parseLine)
            if (lines.isEmpty()) emptyList() else listOf(OcrPara(ab, lines))
        }
        val type = when {
            area.hasClass("ocr_header") -> "HEADER"
            area.hasClass("ocr_footer") -> "FOOTER"
            area.hasClass("ocr_caption") -> "CAPTION"
            area.hasClass("ocr_title") -> "TITLE"
            area.selectFirst("h1, h2, h3, .ocr_title") != null -> "TITLE"
            area.hasClass("ocr_carea") && area.selectFirst(".ocr_par") != null -> "PARAGRAPH"
            else -> "TEXT"
        }
        val id = area.id().ifBlank { null }
        if (paras.isEmpty()) null else OcrBlock(ab, paras, type, id)
    }
    return OcrLayout(pageW, pageH, blocks)
}

object HocrParser {
    fun parse(
        hocr: String,
        fallbackWidthPx: Int = 0,
        fallbackHeightPx: Int = 0,
        languageHints: Set<String> = emptySet(),
    ): app.docscan.core.store.RecognitionLayout {
        return parseHocr(hocr, fallbackWidthPx, fallbackHeightPx).toRecognitionLayout()
    }

    fun parse(
        hocr: String,
        fallbackWidthPx: Long,
        fallbackHeightPx: Long,
    ): app.docscan.core.store.RecognitionLayout {
        return parse(hocr, fallbackWidthPx.toInt(), fallbackHeightPx.toInt())
    }
}
