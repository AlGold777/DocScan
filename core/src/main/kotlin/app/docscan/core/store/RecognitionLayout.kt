package app.docscan.core.store

data class RecognitionLayout(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val blocks: List<OcrBlock>,
    val languageHints: Set<String> = emptySet(),
    val quality: LayoutQuality? = null,
    val formatVersion: Int = 2,
    val plainText: String? = null,
) {
    fun toLegacyGeometry(): RecognitionGeometry {
        val allWords = blocks
            .sortedBy { it.readingOrder }
            .flatMap { it.paragraphs.flatMap { par -> par.lines.flatMap { line -> line.words } } }
            .map { word ->
                val leftPx = (word.box.left * sourceWidthPx).toLong().coerceIn(0L, maxOf(0L, sourceWidthPx.toLong() - 1L))
                val topPx = (word.box.top * sourceHeightPx).toLong().coerceIn(0L, maxOf(0L, sourceHeightPx.toLong() - 1L))
                val rightPx = kotlin.math.ceil(word.box.right * sourceWidthPx).toLong().coerceIn(leftPx + 1L, maxOf(leftPx + 1L, sourceWidthPx.toLong()))
                val bottomPx = kotlin.math.ceil(word.box.bottom * sourceHeightPx).toLong().coerceIn(topPx + 1L, maxOf(topPx + 1L, sourceHeightPx.toLong()))
                RecognizedWord(
                    text = word.text,
                    leftPx = leftPx,
                    topPx = topPx,
                    rightPx = rightPx,
                    bottomPx = bottomPx,
                    confidence = word.confidence.toDouble(),
                )
            }
        return RecognitionGeometry(
            sourceWidthPx = sourceWidthPx.toLong(),
            sourceHeightPx = sourceHeightPx.toLong(),
            coordinateSpace = OCR_WORD_COORDINATE_SPACE,
            words = allWords,
            formatVersion = 1L,
        )
    }

    fun toPlainText(): String {
        if (!plainText.isNullOrBlank()) return plainText
        val structuralText = blocks
            .sortedBy { it.readingOrder }
            .mapNotNull { block ->
                val blockText = block.paragraphs
                    .mapNotNull { paragraph ->
                        val parText = paragraph.lines
                            .map { line -> line.words.joinToString(" ") { it.text } }
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        if (parText.isBlank()) null else parText
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                if (blockText.isBlank()) null else blockText
            }
            .joinToString("\n\n")
        val hasWords = blocks.any { block ->
            block.paragraphs.any { paragraph -> paragraph.lines.any { it.words.isNotEmpty() } }
        }
        return if (hasWords) structuralText else plainText.orEmpty()
    }
}

data class OcrBlock(
    val id: String,
    val type: BlockType,
    val box: NormalizedBox,
    val readingOrder: Int,
    val paragraphs: List<OcrParagraph>,
) {
    val lines: List<OcrLine> get() = paragraphs.flatMap { it.lines }
}

data class OcrParagraph(
    val box: NormalizedBox,
    val lines: List<OcrLine>,
)

data class OcrLine(
    val box: NormalizedBox,
    val baseline: Baseline?,
    val xHeight: Float?,
    val words: List<OcrWord>,
)

data class OcrWord(
    val text: String,
    val box: NormalizedBox,
    val confidence: Float,
)

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Normalized box coordinates must be in [0, 1]: $this"
        }
        require(right >= left && bottom >= top) {
            "Normalized box inverted: right ($right) < left ($left) or bottom ($bottom) < top ($top)"
        }
    }

    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class Baseline(
    val slope: Float = 0f,
    val intercept: Float = 0f,
)

data class LayoutQuality(
    val meanConfidence: Float,
    val lowConfidenceWordCount: Int,
    val structureAvailable: Boolean,
    val language: String? = null,
    val preprocessingProfile: String = "DEFAULT",
    val engineVersion: String = "UNKNOWN",
)

enum class BlockType {
    TEXT,
    TITLE,
    PARAGRAPH,
    LIST,
    TABLE,
    HEADER,
    FOOTER,
    CAPTION,
    FIGURE,
    UNKNOWN,
}

fun normalizeBox(
    leftPx: Long,
    topPx: Long,
    rightPx: Long,
    bottomPx: Long,
    widthPx: Int,
    heightPx: Int,
): NormalizedBox? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val l = leftPx.coerceIn(0L, widthPx.toLong())
    val t = topPx.coerceIn(0L, heightPx.toLong())
    val r = rightPx.coerceIn(0L, widthPx.toLong())
    val b = bottomPx.coerceIn(0L, heightPx.toLong())
    if (r <= l || b <= t) return null
    val left = (l.toFloat() / widthPx).coerceIn(0f, 1f)
    val top = (t.toFloat() / heightPx).coerceIn(0f, 1f)
    val right = (r.toFloat() / widthPx).coerceIn(left, 1f)
    val bottom = (b.toFloat() / heightPx).coerceIn(top, 1f)
    if (right <= left || bottom <= top) return null
    return NormalizedBox(left, top, right, bottom)
}

fun sanitizeOcrText(raw: String): String = app.docscan.core.ocr.sanitizeOcrText(raw)
