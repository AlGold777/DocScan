package app.docscan.android

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
import com.googlecode.tesseract.android.TessBaseAPI

/**
 * Native LayoutExtractor that reads Tesseract's internal hierarchical layout structure
 * (Block -> Paragraph -> Line -> Word) directly via ResultIterator.
 * Avoids any intermediate XML/HTML serialization, tag parsing, or HTML entity decoding.
 */
class ResultIteratorLayoutExtractor {

    fun extract(
        tess: TessBaseAPI,
        pageWidth: Int,
        pageHeight: Int,
        languageHints: Set<String> = emptySet(),
    ): RecognitionLayout? {
        val iterator = tess.resultIterator ?: return null
        try {
            iterator.begin()

            class WordDraft(val text: String, val box: NormalizedBox, val confidence: Float)
            class LineDraft(val box: NormalizedBox?, val words: MutableList<WordDraft> = mutableListOf())
            class ParaDraft(val box: NormalizedBox?, val lines: MutableList<LineDraft> = mutableListOf())
            class BlockDraft(val id: String, val box: NormalizedBox?, val paragraphs: MutableList<ParaDraft> = mutableListOf())

            val blocks = mutableListOf<BlockDraft>()
            var currentBlock: BlockDraft? = null
            var currentPara: ParaDraft? = null
            var currentLine: LineDraft? = null
            var blockIndex = 0

            do {
                val isNewBlock = currentBlock == null || iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)
                val isNewPara = isNewBlock || currentPara == null || iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)
                val isNewLine = isNewPara || currentLine == null || iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)

                if (isNewBlock) {
                    blockIndex++
                    val bBoxRaw = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)
                    val bBox = bBoxRaw?.let {
                        normalizeBox(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3].toLong(), pageWidth, pageHeight)
                    }
                    val block = BlockDraft(id = "block_$blockIndex", box = bBox)
                    blocks.add(block)
                    currentBlock = block
                    currentPara = null
                    currentLine = null
                }

                if (isNewPara) {
                    val pBoxRaw = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_PARA)
                    val pBox = pBoxRaw?.let {
                        normalizeBox(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3].toLong(), pageWidth, pageHeight)
                    }
                    val para = ParaDraft(box = pBox)
                    currentBlock!!.paragraphs.add(para)
                    currentPara = para
                    currentLine = null
                }

                if (isNewLine) {
                    val lBoxRaw = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    val lBox = lBoxRaw?.let {
                        normalizeBox(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3].toLong(), pageWidth, pageHeight)
                    }
                    val line = LineDraft(box = lBox)
                    currentPara!!.lines.add(line)
                    currentLine = line
                }

                val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val sanitized = sanitizeOcrText(wordText)
                if (sanitized.isNotBlank()) {
                    val wBoxRaw = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val wBox = wBoxRaw?.let {
                        normalizeBox(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3].toLong(), pageWidth, pageHeight)
                    }
                    if (wBox != null) {
                        val conf = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD).coerceIn(0f, 100f)
                        currentLine!!.words.add(WordDraft(sanitized, wBox, conf))
                    }
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

            val resultBlocks = mutableListOf<OcrBlock>()
            var readingOrder = 0
            for (bDraft in blocks) {
                val paragraphs = mutableListOf<OcrParagraph>()
                for (pDraft in bDraft.paragraphs) {
                    val lines = mutableListOf<OcrLine>()
                    for (lDraft in pDraft.lines) {
                        if (lDraft.words.isNotEmpty()) {
                            val lineWords = lDraft.words.map { OcrWord(it.text, it.box, it.confidence) }
                            val lineBox = lDraft.box ?: resolveBoundingBox(lineWords.map { it.box })
                            if (lineBox != null) {
                                lines.add(OcrLine(box = lineBox, baseline = null, xHeight = null, words = lineWords))
                            }
                        }
                    }
                    if (lines.isNotEmpty()) {
                        val paraBox = pDraft.box ?: resolveBoundingBox(lines.map { it.box })
                        if (paraBox != null) {
                            paragraphs.add(OcrParagraph(box = paraBox, lines = lines))
                        }
                    }
                }
                if (paragraphs.isNotEmpty()) {
                    val blockBox = bDraft.box ?: resolveBoundingBox(paragraphs.map { it.box })
                    if (blockBox != null) {
                        resultBlocks.add(
                            OcrBlock(
                                id = bDraft.id,
                                type = BlockType.TEXT,
                                box = blockBox,
                                readingOrder = readingOrder++,
                                paragraphs = paragraphs,
                            ),
                        )
                    }
                }
            }

            if (resultBlocks.isEmpty()) return null

            val allWords = resultBlocks.flatMap { it.paragraphs.flatMap { it.lines.flatMap { it.words } } }
            val meanConf = if (allWords.isEmpty()) 0f else allWords.map { it.confidence }.average().toFloat()
            val lowConfCount = allWords.count { it.confidence < 60f }
            val quality = LayoutQuality(
                meanConfidence = meanConf,
                lowConfidenceWordCount = lowConfCount,
                structureAvailable = allWords.isNotEmpty(),
                language = languageHints.firstOrNull(),
            )
            return RecognitionLayout(
                sourceWidthPx = maxOf(1, pageWidth),
                sourceHeightPx = maxOf(1, pageHeight),
                blocks = resultBlocks,
                languageHints = languageHints,
                quality = quality,
                formatVersion = 2,
            )
        } finally {
            iterator.delete()
        }
    }

    private fun resolveBoundingBox(boxes: List<NormalizedBox>): NormalizedBox? {
        if (boxes.isEmpty()) return null
        val minL = boxes.minOf { it.left }.coerceIn(0f, 1f)
        val minT = boxes.minOf { it.top }.coerceIn(0f, 1f)
        val maxR = boxes.maxOf { it.right }.coerceIn(minL + 0.0001f, 1f)
        val maxB = boxes.maxOf { it.bottom }.coerceIn(minT + 0.0001f, 1f)
        return NormalizedBox(minL, minT, maxR, maxB)
    }
}
