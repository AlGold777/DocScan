package app.docscan.android

import app.docscan.core.store.Baseline
import app.docscan.core.store.BlockType
import app.docscan.core.store.LayoutQuality
import app.docscan.core.store.NormalizedBox
import app.docscan.core.store.OcrBlock
import app.docscan.core.store.OcrLine
import app.docscan.core.store.OcrParagraph
import app.docscan.core.store.OcrWord
import app.docscan.core.store.RecognitionLayout
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Captures actual PDF glyph positions in the coordinate system of PDFRenderer's cropped raster. */
internal class PdfTextLayoutExtractor private constructor(
    private val documentSource: PDDocument,
    private val pageIndex: Int,
    private val rasterWidth: Int,
    private val rasterHeight: Int,
) : PDFTextStripper() {
    private data class Point(val x: Float, val y: Float)
    private data class Glyph(val text: String, val box: NormalizedBox, val start: Point, val end: Point)
    private val page = documentSource.getPage(pageIndex)
    private val crop = page.cropBox
    private val rotation = ((page.rotation % 360) + 360) % 360
    private val displayedWidth = if (rotation == 90 || rotation == 270) crop.height else crop.width
    private val displayedHeight = if (rotation == 90 || rotation == 270) crop.width else crop.height
    private val lineWords = mutableListOf<OcrWord>()
    private val blocks = mutableListOf<OcrBlock>()
    private var firstGlyph: Glyph? = null
    private var horizontalStructure = true

    init {
        startPage = pageIndex + 1
        endPage = pageIndex + 1
        sortByPosition = true
        setShouldSeparateByBeads(false)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val wordGlyphs = mutableListOf<Glyph>()
        fun flushWord() {
            if (wordGlyphs.isEmpty()) return
            val first = wordGlyphs.first()
            if (firstGlyph == null) firstGlyph = first
            val wordText = wordGlyphs.joinToString("") { it.text }
            lineWords += OcrWord(wordText, union(wordGlyphs.map { it.box }), 100f)
            wordGlyphs.clear()
        }
        for (position in textPositions) {
            if (position.unicode.isNullOrBlank()) { flushWord(); continue }
            val glyph = glyph(position) ?: continue
            wordGlyphs += glyph
        }
        flushWord()
        super.writeString(text, textPositions)
    }

    override fun writeLineSeparator() {
        flushLine()
        super.writeLineSeparator()
    }

    override fun writeParagraphEnd() {
        flushLine()
        super.writeParagraphEnd()
    }

    private fun flushLine() {
        if (lineWords.isEmpty()) return
        val box = union(lineWords.map { it.box })
        val baseline = firstGlyph?.let { glyph ->
            val dx = glyph.end.x - glyph.start.x
            val dy = glyph.end.y - glyph.start.y
            // A y(x) baseline cannot represent vertical or backwards text. Keep its true boxes,
            // but mark the page for plain-text reflow when writing a new PDF.
            if (dx <= 0.001f || kotlin.math.abs(dy) > dx * 4f) {
                horizontalStructure = false
                null
            } else {
                val slope = dy / dx
                val left = floor(box.left * rasterWidth)
                val bottom = ceil(box.bottom * rasterHeight)
                Baseline(slope, glyph.start.y - bottom - slope * (glyph.start.x - left))
            }
        }
        val line = OcrLine(box, baseline, null, lineWords.toList())
        blocks += OcrBlock("pdf-line-${blocks.size + 1}", BlockType.TEXT, box, blocks.size,
            listOf(OcrParagraph(box, listOf(line))))
        lineWords.clear()
        firstGlyph = null
    }

    private fun glyph(position: TextPosition): Glyph? {
        val matrix = position.textMatrix
        // PDFBox LegacyPDFStreamEngine has already subtracted CropBox.lowerLeft from both
        // textMatrix and endX/endY. Subtracting it again shifts non-zero CropBox pages.
        val x = matrix.translateX
        val y = matrix.translateY
        val height = position.heightDir
        val upLength = hypot(matrix.shearX, matrix.scaleY)
        if (!listOf(x, y, position.endX, position.endY, height, upLength).all { it.isFinite() }
            || height <= 0f || upLength <= 0f) return null
        val upX = matrix.shearX * height / upLength
        val upY = matrix.scaleY * height / upLength
        val points = listOf(
            rasterPoint(x, y), rasterPoint(position.endX, position.endY),
            rasterPoint(x + upX, y + upY), rasterPoint(position.endX + upX, position.endY + upY),
        )
        val left = (points.minOf { it.x } / rasterWidth).coerceIn(0f, 1f)
        val top = (points.minOf { it.y } / rasterHeight).coerceIn(0f, 1f)
        val right = (points.maxOf { it.x } / rasterWidth).coerceIn(0f, 1f)
        val bottom = (points.maxOf { it.y } / rasterHeight).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return Glyph(position.unicode, NormalizedBox(left, top, right, bottom),
            rasterPoint(x, y), rasterPoint(position.endX, position.endY))
    }

    private fun rasterPoint(x: Float, y: Float): Point {
        val rotated = when (rotation) {
            90 -> Point(y, x)
            180 -> Point(crop.width - x, y)
            270 -> Point(crop.height - y, crop.width - x)
            else -> Point(x, crop.height - y)
        }
        return Point(rotated.x * rasterWidth / displayedWidth, rotated.y * rasterHeight / displayedHeight)
    }

    private fun union(boxes: List<NormalizedBox>): NormalizedBox = NormalizedBox(
        boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
    )

    companion object {
        fun extract(document: PDDocument, pageIndex: Int, width: Int, height: Int): RecognitionLayout {
            require(width > 0 && height > 0)
            val extractor = PdfTextLayoutExtractor(document, pageIndex, width, height)
            val text = extractor.getText(document)
            extractor.flushLine()
            return RecognitionLayout(
                sourceWidthPx = width,
                sourceHeightPx = height,
                blocks = extractor.blocks.toList(),
                plainText = text,
                quality = LayoutQuality(100f, 0,
                    structureAvailable = extractor.blocks.isNotEmpty() && extractor.horizontalStructure,
                    engineVersion = "PDF_TEXT"),
            )
        }
    }
}
