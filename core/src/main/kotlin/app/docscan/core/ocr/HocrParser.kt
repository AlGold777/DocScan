package app.docscan.core.ocr

import app.docscan.core.store.Baseline
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

private val BBOX = Regex("""bbox (-?\d+) (-?\d+) (-?\d+) (-?\d+)""")
private val WCONF = Regex("""x_wconf (\d+)""")
private val BASELINE = Regex("""baseline\s+(-?[\d.]+)\s+(-?[\d.]+)""")
private const val CONTAINER_SEL = ".ocr_carea, .ocr_header, .ocr_footer, .ocr_caption, .ocr_textfloat"

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

    fun isContainer(element: org.jsoup.nodes.Element): Boolean =
        element.hasClass("ocr_carea") || element.hasClass("ocr_header") ||
            element.hasClass("ocr_footer") || element.hasClass("ocr_caption") ||
            element.hasClass("ocr_textfloat")

    fun nearestContainer(element: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        var ancestor = element.parent()
        while (ancestor != null) {
            if (isContainer(ancestor)) return ancestor
            ancestor = ancestor.parent()
        }
        return null
    }

    fun nearestLine(element: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        var ancestor = element.parent()
        while (ancestor != null) {
            if (ancestor.hasClass("ocr_line")) return ancestor
            ancestor = ancestor.parent()
        }
        return null
    }

    fun isNonText(element: org.jsoup.nodes.Element): Boolean {
        var ancestor: org.jsoup.nodes.Element? = element
        while (ancestor != null) {
            if (ancestor.hasClass("ocr_photo") || ancestor.hasClass("ocr_separator")) return true
            ancestor = ancestor.parent()
        }
        return false
    }

    fun parseLine(l: org.jsoup.nodes.Element): OcrLine? {
        val lb = l.box(pageW, pageH) ?: return null
        val lineContainer = if (isContainer(l)) l else nearestContainer(l)
        val words = l.select(".ocrx_word").filter { word ->
            !isNonText(word) && nearestContainer(word) === lineContainer &&
                nearestLine(word) == (if (l.hasClass("ocr_line")) l else null)
        }.mapNotNull { e ->
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

    val containers = doc.select(CONTAINER_SEL)

    fun ownedBy(element: org.jsoup.nodes.Element, container: org.jsoup.nodes.Element): Boolean =
        nearestContainer(element) === container

    fun nearestParagraph(element: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        var ancestor = element.parent()
        while (ancestor != null) {
            if (ancestor.hasClass("ocr_par")) return ancestor
            ancestor = ancestor.parent()
        }
        return null
    }

    fun lineElements(area: org.jsoup.nodes.Element): List<org.jsoup.nodes.Element> {
        val lines = area.select(".ocr_line")
            // Element.selectFirst includes the element itself when it matches.
            // Only discard a line when it contains a distinct nested line.
            .filter { line -> line.select(".ocr_line").none { it !== line } && !isNonText(line) }
            .toMutableList()

        // Some hOCR producers put words directly in semantic containers instead of
        // wrapping them in an .ocr_line. Treat those containers as one line only
        // when they have no actual line descendants.
        if (area.select(".ocr_line").none { it !== area } && area.children().any { it.hasClass("ocrx_word") }) {
            lines += area
        }
        return lines
    }

    data class LineEntry(
        val container: org.jsoup.nodes.Element,
        val paragraph: org.jsoup.nodes.Element?,
        val source: org.jsoup.nodes.Element,
        val line: OcrLine,
    )

    val domOrder = doc.select("*").withIndex().associate { (index, element) -> element to index }
    val lineEntries = containers.flatMap { area ->
        val paragraphs = area.select(".ocr_par").filter { paragraph -> ownedBy(paragraph, area) }
        val candidates = if (paragraphs.isNotEmpty()) {
            paragraphs.flatMap { paragraph ->
                lineElements(paragraph)
                    .filter { line -> ownedBy(line, area) && (line === paragraph || nearestParagraph(line) === paragraph) }
                    .mapNotNull { line -> parseLine(line)?.let { LineEntry(area, paragraph, line, it) } }
            }
        } else {
            lineElements(area)
                .filter { line -> line === area || ownedBy(line, area) }
                .mapNotNull { line -> parseLine(line)?.let { LineEntry(area, null, line, it) } }
        }
        candidates
    }.sortedBy { entry -> domOrder[entry.source] ?: Int.MAX_VALUE }

    val chunks = mutableListOf<MutableList<LineEntry>>()
    lineEntries.forEach { entry ->
        val current = chunks.lastOrNull()
        if (current == null || current.last().container !== entry.container) {
            chunks += mutableListOf(entry)
        } else {
            current += entry
        }
    }

    val blocks = chunks.mapNotNull { chunk ->
        val area = chunk.first().container
        val ab = area.box(pageW, pageH) ?: return@mapNotNull null
        val paraChunks = mutableListOf<MutableList<LineEntry>>()
        chunk.forEach { entry ->
            val current = paraChunks.lastOrNull()
            if (current == null || current.last().paragraph !== entry.paragraph) {
                paraChunks += mutableListOf(entry)
            } else {
                current += entry
            }
        }
        val paras = paraChunks.mapNotNull { paraChunk ->
            val paragraph = paraChunk.first().paragraph
            val pb = paragraph?.box(pageW, pageH) ?: ab
            OcrPara(pb, paraChunk.map { it.line })
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
        OcrBlock(ab, paras, type, id)
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
