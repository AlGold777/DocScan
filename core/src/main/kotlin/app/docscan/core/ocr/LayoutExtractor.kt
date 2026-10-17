package app.docscan.core.ocr

import app.docscan.core.store.Baseline
import app.docscan.core.store.BlockType
import app.docscan.core.store.LayoutQuality
import app.docscan.core.store.NormalizedBox
import app.docscan.core.store.OcrBlock
import app.docscan.core.store.OcrLine
import app.docscan.core.store.OcrParagraph
import app.docscan.core.store.OcrWord
import app.docscan.core.store.RecognitionLayout
import app.docscan.core.store.normalizeBox
import app.docscan.core.store.sanitizeOcrText
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

interface LayoutExtractor {
    fun extract(
        hocr: String,
        fallbackWidthPx: Int = 0,
        fallbackHeightPx: Int = 0,
        languageHints: Set<String> = emptySet(),
    ): RecognitionLayout
}

class HocrLayoutExtractor : LayoutExtractor {
    override fun extract(
        hocr: String,
        fallbackWidthPx: Int,
        fallbackHeightPx: Int,
        languageHints: Set<String>,
    ): RecognitionLayout {
        if (hocr.isBlank()) {
            return RecognitionLayout(
                sourceWidthPx = maxOf(0, fallbackWidthPx),
                sourceHeightPx = maxOf(0, fallbackHeightPx),
                blocks = emptyList(),
                languageHints = languageHints,
                quality = LayoutQuality(
                    meanConfidence = 0f,
                    lowConfidenceWordCount = 0,
                    structureAvailable = false,
                    language = languageHints.firstOrNull(),
                ),
            )
        }

        val doc = Jsoup.parse(hocr)
        val pageElem = doc.selectFirst(".ocr_page")

        var pageWidth = fallbackWidthPx
        var pageHeight = fallbackHeightPx
        if (pageElem != null) {
            val pageBbox = parsePixelBbox(pageElem.attr("title"))
            if (pageBbox != null && pageBbox.right > pageBbox.left && pageBbox.bottom > pageBbox.top) {
                pageWidth = pageBbox.right.toInt()
                pageHeight = pageBbox.bottom.toInt()
            }
        }
        pageWidth = maxOf(1, pageWidth)
        pageHeight = maxOf(1, pageHeight)

        val root = pageElem ?: doc.body()
        val containers = root.select(".ocr_carea, .ocr_header, .ocr_footer, .ocr_caption")

        val blocks = mutableListOf<OcrBlock>()
        var readingOrder = 0

        if (containers.isNotEmpty()) {
            containers.forEachIndexed { cIndex, container ->
                val pars = container.select(".ocr_par")
                val paragraphs = mutableListOf<OcrParagraph>()
                if (pars.isNotEmpty()) {
                    pars.forEach { par ->
                        val lines = parseLines(par, pageWidth, pageHeight)
                        if (lines.isNotEmpty()) {
                            val parBox = resolveNormalizedBox(
                                parsePixelBbox(par.attr("title")),
                                lines.map { it.box },
                                pageWidth,
                                pageHeight,
                            )
                            if (parBox != null) {
                                paragraphs += OcrParagraph(parBox, lines)
                            }
                        }
                    }
                } else {
                    val lines = parseLines(container, pageWidth, pageHeight)
                    if (lines.isNotEmpty()) {
                        val parBox = resolveNormalizedBox(
                            parsePixelBbox(container.attr("title")),
                            lines.map { it.box },
                            pageWidth,
                            pageHeight,
                        )
                        if (parBox != null) {
                            paragraphs += OcrParagraph(parBox, lines)
                        }
                    }
                }

                if (paragraphs.isNotEmpty()) {
                    val blockBox = resolveNormalizedBox(
                        parsePixelBbox(container.attr("title")),
                        paragraphs.map { it.box },
                        pageWidth,
                        pageHeight,
                    )
                    if (blockBox != null) {
                        val blockId = container.id().ifBlank { "block_${cIndex + 1}" }
                        val type = determineBlockType(container, null)
                        blocks += OcrBlock(
                            id = blockId,
                            type = type,
                            box = blockBox,
                            readingOrder = readingOrder++,
                            paragraphs = paragraphs,
                        )
                    }
                }
            }
        } else {
            val pars = root.select(".ocr_par")
            if (pars.isNotEmpty()) {
                val paragraphs = mutableListOf<OcrParagraph>()
                pars.forEach { par ->
                    val lines = parseLines(par, pageWidth, pageHeight)
                    if (lines.isNotEmpty()) {
                        val parBox = resolveNormalizedBox(
                            parsePixelBbox(par.attr("title")),
                            lines.map { it.box },
                            pageWidth,
                            pageHeight,
                        )
                        if (parBox != null) {
                            paragraphs += OcrParagraph(parBox, lines)
                        }
                    }
                }
                if (paragraphs.isNotEmpty()) {
                    val blockBox = resolveNormalizedBox(null, paragraphs.map { it.box }, pageWidth, pageHeight)
                    if (blockBox != null) {
                        blocks += OcrBlock(
                            id = "block_1",
                            type = BlockType.TEXT,
                            box = blockBox,
                            readingOrder = readingOrder++,
                            paragraphs = paragraphs,
                        )
                    }
                }
            } else {
                val lines = parseLines(root, pageWidth, pageHeight)
                if (lines.isNotEmpty()) {
                    val box = resolveNormalizedBox(null, lines.map { it.box }, pageWidth, pageHeight)
                    if (box != null) {
                        val paragraph = OcrParagraph(box, lines)
                        blocks += OcrBlock(
                            id = "block_1",
                            type = BlockType.TEXT,
                            box = box,
                            readingOrder = readingOrder++,
                            paragraphs = listOf(paragraph),
                        )
                    }
                }
            }
        }

        val allWords = blocks.flatMap { it.paragraphs.flatMap { it.lines.flatMap { it.words } } }
        val meanConf = if (allWords.isEmpty()) 0f else (allWords.map { it.confidence.toDouble() }.average()).toFloat()
        val lowConfCount = allWords.count { it.confidence < 60f }
        val quality = LayoutQuality(
            meanConfidence = meanConf,
            lowConfidenceWordCount = lowConfCount,
            structureAvailable = allWords.isNotEmpty(),
            language = languageHints.firstOrNull(),
        )

        return RecognitionLayout(
            sourceWidthPx = pageWidth,
            sourceHeightPx = pageHeight,
            blocks = blocks,
            languageHints = languageHints,
            quality = quality,
            formatVersion = 2,
        )
    }

    private fun parseLines(container: Element, pageWidth: Int, pageHeight: Int): List<OcrLine> {
        val lineElems = container.select(".ocr_line")
        if (lineElems.isEmpty()) {
            val words = parseWords(container, pageWidth, pageHeight)
            if (words.isEmpty()) return emptyList()
            val box = resolveNormalizedBox(null, words.map { it.box }, pageWidth, pageHeight) ?: return emptyList()
            return listOf(OcrLine(box, null, null, words))
        }

        val result = mutableListOf<OcrLine>()
        lineElems.forEach { lineElem ->
            val words = parseWords(lineElem, pageWidth, pageHeight)
            if (words.isNotEmpty()) {
                val rawTitle = lineElem.attr("title")
                val box = resolveNormalizedBox(parsePixelBbox(rawTitle), words.map { it.box }, pageWidth, pageHeight)
                if (box != null) {
                    val baseline = parseBaseline(rawTitle)
                    val xHeight = parseXHeight(rawTitle)
                    result += OcrLine(box, baseline, xHeight, words)
                }
            }
        }
        return result
    }

    private fun parseWords(container: Element, pageWidth: Int, pageHeight: Int): List<OcrWord> {
        val wordElems = container.select(".ocrx_word, .ocr_word")
        val result = mutableListOf<OcrWord>()
        wordElems.forEach { wordElem ->
            val sanitized = sanitizeOcrText(wordElem.text())
            if (sanitized.isEmpty()) return@forEach
            val title = wordElem.attr("title")
            val rawBbox = parsePixelBbox(title) ?: return@forEach
            val normalizedBox = normalizeBox(
                leftPx = rawBbox.left,
                topPx = rawBbox.top,
                rightPx = rawBbox.right,
                bottomPx = rawBbox.bottom,
                widthPx = pageWidth,
                heightPx = pageHeight,
            ) ?: return@forEach

            val confidence = CONFIDENCE_REGEX.find(title)?.groupValues?.get(1)
                ?.toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f

            result += OcrWord(sanitized, normalizedBox, confidence)
        }
        return result
    }

    private fun parsePixelBbox(title: String): PixelBbox? {
        val match = BBOX_REGEX.find(title) ?: return null
        val values = match.groupValues.drop(1).mapNotNull(String::toLongOrNull)
        if (values.size != 4) return null
        val left = values[0]
        val top = values[1]
        val right = values[2]
        val bottom = values[3]
        if (left < 0L || top < 0L || right <= left || bottom <= top) return null
        return PixelBbox(left, top, right, bottom)
    }

    private fun parseBaseline(title: String): Baseline? {
        val match = BASELINE_REGEX.find(title) ?: return null
        val slope = match.groupValues[1].toFloatOrNull() ?: return null
        val intercept = match.groupValues[2].toFloatOrNull() ?: return null
        return Baseline(slope, intercept)
    }

    private fun parseXHeight(title: String): Float? {
        val match = X_SIZE_REGEX.find(title) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    private fun resolveNormalizedBox(
        declared: PixelBbox?,
        childrenBoxes: List<NormalizedBox>,
        pageWidth: Int,
        pageHeight: Int,
    ): NormalizedBox? {
        if (declared != null) {
            val normalized = normalizeBox(declared.left, declared.top, declared.right, declared.bottom, pageWidth, pageHeight)
            if (normalized != null) return normalized
        }
        if (childrenBoxes.isEmpty()) return null
        val minLeft = childrenBoxes.minOf { it.left }.coerceIn(0f, 1f)
        val minTop = childrenBoxes.minOf { it.top }.coerceIn(0f, 1f)
        val maxRight = childrenBoxes.maxOf { it.right }.coerceIn(minLeft + 0.0001f, 1f)
        val maxBottom = childrenBoxes.maxOf { it.bottom }.coerceIn(minTop + 0.0001f, 1f)
        return NormalizedBox(minLeft, minTop, maxRight, maxBottom)
    }

    private fun determineBlockType(element: Element, parent: Element?): BlockType {
        val combined = (element.className() + " " + element.id() + " " + (parent?.className().orEmpty()) + " " + (parent?.id().orEmpty())).lowercase()
        return when {
            combined.contains("header") -> BlockType.HEADER
            combined.contains("footer") -> BlockType.FOOTER
            combined.contains("caption") -> BlockType.CAPTION
            combined.contains("title") || combined.contains("heading") -> BlockType.TITLE
            combined.contains("table") -> BlockType.TABLE
            combined.contains("list") -> BlockType.LIST
            combined.contains("figure") -> BlockType.FIGURE
            combined.contains("par") || combined.contains("carea") -> BlockType.PARAGRAPH
            else -> BlockType.TEXT
        }
    }

    private data class PixelBbox(val left: Long, val top: Long, val right: Long, val bottom: Long)

    private companion object {
        val BBOX_REGEX = Regex("""\bbbox\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)""")
        val BASELINE_REGEX = Regex("""\bbaseline\s+([-\d.]+)\s+([-\d.]+)""")
        val X_SIZE_REGEX = Regex("""\bx_size\s+([-\d.]+)""")
        val CONFIDENCE_REGEX = Regex("""\bx_wconf\s+([-\d.]+)""")
    }
}
