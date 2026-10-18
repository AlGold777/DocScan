package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import app.docscan.core.ocr.toOcrLayout
import app.docscan.core.store.RecognitionGeometry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

data class PdfOutputSettings(
    val a4ShortSidePoints: Float,
    val a4LongSidePoints: Float,
    val jpegQuality: Float,
    val jpegResolutionDpi: Int,
    val includeImage: Boolean = true,
) {
    init {
        require(a4ShortSidePoints > 0f && a4LongSidePoints >= a4ShortSidePoints)
        require(jpegQuality in 0f..1f)
        require(jpegResolutionDpi > 0)
    }
}

data class PdfInputPage(
    val openBitmap: () -> Bitmap,
    val recognition: RecognitionGeometry? = null,
    val layout: app.docscan.core.store.RecognitionLayout? = null,
    val text: String? = null,
)

data class PdfWriteResult(val pageCount: Int, val fileSizeBytes: Long, val warnings: List<String> = emptyList())

/** PDF writer candidate. Output stays local; SAF export is owned by the later workflow. */
class SearchablePdfWriter(context: Context) {
    private val appContext = context.applicationContext

    fun write(
        destination: File,
        pages: List<PdfInputPage>,
        settings: PdfOutputSettings,
    ): PdfWriteResult {
        require(pages.isNotEmpty()) { "PDF requires at least one frozen Page" }
        require(!destination.exists()) { "PDF destination already exists" }
        val parent = destination.absoluteFile.parentFile
            ?: throw IllegalArgumentException("PDF destination has no parent directory")
        require(parent.isDirectory || parent.mkdirs()) { "PDF output directory cannot be created" }
        val temporary = File(parent, ".${destination.name}.${System.nanoTime()}.building")
        PDFBoxResourceLoader.init(appContext)
        var publicationWarnings = emptyList<String>()
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val font = resolveFont(document)
                val glyphCache = HashMap<Int, Boolean>()
                val warnings = mutableListOf<String>()
                pages.forEach { source ->
                    val bitmap = source.openBitmap()
                    try {
                        addPage(document, bitmap, source.recognition, source.layout, source.text, settings, font, glyphCache, warnings)
                    } finally {
                        bitmap.recycle()
                    }
                }
                document.save(temporary)
                publicationWarnings = warnings.toList()
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE)
            return PdfWriteResult(pages.size, destination.length(), publicationWarnings)
        } finally {
            if (temporary.exists()) check(temporary.delete()) { "Incomplete PDF temporary file cannot be removed" }
        }
    }

    private fun addPage(
        document: PDDocument,
        bitmap: Bitmap,
        recognition: RecognitionGeometry?,
        layout: app.docscan.core.store.RecognitionLayout?,
        textFallback: String?,
        settings: PdfOutputSettings,
        font: PDType0Font?,
        cache: HashMap<Int, Boolean>,
        warnings: MutableList<String>,
    ) {
        if (recognition != null) {
            require(recognition.sourceWidthPx == bitmap.width.toLong()
                && recognition.sourceHeightPx == bitmap.height.toLong()
                && recognition.coordinateSpace == app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
                && recognition.formatVersion == 1L
            ) { "OCR geometry does not match the oriented PDF source bitmap" }
        }
        if (layout != null) require(layout.sourceWidthPx == bitmap.width && layout.sourceHeightPx == bitmap.height) {
            "OCR layout does not match the oriented PDF source bitmap"
        }
        val plainText = layout?.plainText?.takeIf { it.isNotBlank() } ?: textFallback
        val layoutCandidate = layout?.takeUnless { it.quality?.structureAvailable == false }?.toOcrLayout()
            ?.takeIf { it.words.any { word -> word.text.isNotBlank() } }
        fun layoutText(value: app.docscan.core.ocr.OcrLayout): String = value.blocks.joinToString("\n\n") { block ->
            block.paras.joinToString("\n\n") { paragraph ->
                paragraph.lines.joinToString("\n") { line -> line.words.joinToString(" ") { it.text } }
            }
        }
        // A partial positional extraction must not silently discard the remaining plain text.
        val ocrLayout = layoutCandidate?.takeIf {
            plainText.isNullOrBlank() || normalizedTextContent(layoutText(it)) == normalizedTextContent(plainText)
        }
        val legacyGeometry = recognition?.takeIf { layout == null }
        val structuredText = ocrLayout?.let(::layoutText)
            ?: legacyGeometry?.words?.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.text }
        val rawText = structuredText ?: plainText.orEmpty()
        val hasText = rawText.isNotBlank()
        if (settings.includeImage && hasText && ocrLayout == null && legacyGeometry?.words.isNullOrEmpty()) {
            warnings += "Page ${document.numberOfPages + 1}: text is available without reliable source positions"
        }
        require(settings.includeImage || hasText) { "Text-only PDF requires recognized text on every page" }
        check(!hasText || font != null) { "PDF text font is unavailable" }
        val supportedText = font?.safeText(rawText, cache).orEmpty()
        val expectedContent = normalizedTextContent(supportedText)
        if (hasText && normalizedTextContent(rawText) != expectedContent) {
            warnings += "Page ${document.numberOfPages + 1}: bundled font cannot represent some recognized glyphs"
        }
        check(settings.includeImage || expectedContent.isNotEmpty()) { "Recognized text has no supported PDF glyphs" }
        val pageWidth = if (bitmap.width >= bitmap.height) settings.a4LongSidePoints else settings.a4ShortSidePoints
        val pageHeight = if (bitmap.width >= bitmap.height) settings.a4ShortSidePoints else settings.a4LongSidePoints
        val scale = minOf(pageWidth / bitmap.width, pageHeight / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val offsetX = (pageWidth - imageWidth) / 2f
        val offsetY = (pageHeight - imageHeight) / 2f
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        PDPageContentStream(document, page).use { cs ->
            if (settings.includeImage) {
                val image = JPEGFactory.createFromImage(document, bitmap, settings.jpegQuality, settings.jpegResolutionDpi)
                cs.drawImage(image, offsetX, offsetY, imageWidth, imageHeight)
            }
            if (font != null && expectedContent.isNotEmpty()) {
                val renderingMode = if (settings.includeImage) RenderingMode.NEITHER else RenderingMode.FILL
                if (!settings.includeImage) cs.setNonStrokingColor(0f, 0f, 0f)
                val ascent = font.fontDescriptor.ascent.takeIf { it.isFinite() && it > 0f } ?: 800f
                val descent = font.fontDescriptor.descent.takeIf { it.isFinite() && it <= 0f } ?: -200f
                val metricHeight = ascent - descent
                fun writeWord(raw: String, left: Float, top: Float, right: Float, bottom: Float,
                              baselineY: Float? = null, slope: Float = 0f) {
                    val text = font.safeText(raw, cache) ?: return
                    val fontSize = (bottom - top) * scale * 1000f / metricHeight
                    val targetWidth = (right - left) * scale
                    if (fontSize <= 0f || targetWidth <= 0f || !fontSize.isFinite()) return
                    val natural = font.getStringWidth(text) * fontSize / 1000f
                    if (natural <= 0f) return
                    val yImage = baselineY ?: (bottom + descent * fontSize / (1000f * scale))
                    val angle = kotlin.math.atan(-slope.toDouble())
                    // Horizontal scaling fits the word alone. A trailing space has no bbox width.
                    val baselineWidth = targetWidth / kotlin.math.cos(angle).toFloat()
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setRenderingMode(renderingMode)
                    cs.setHorizontalScaling(baselineWidth * 100f / natural)
                    cs.setTextMatrix(Matrix.getRotateInstance(
                        angle, offsetX + left * scale, offsetY + (bitmap.height - yImage) * scale,
                    ))
                    cs.showText(text)
                    cs.endText()
                }
                if (ocrLayout != null) {
                    for (block in ocrLayout.blocks) for (paragraph in block.paras) for (line in paragraph.lines) {
                        for (word in line.words) {
                            val baseline = line.baseline?.takeIf { it.slope.isFinite() && it.intercept.isFinite() }
                            val yImage = baseline?.let {
                                line.box.b + it.intercept + it.slope * (word.box.l - line.box.l)
                            }
                            writeWord(word.text, word.box.l.toFloat(), line.box.t.toFloat(), word.box.r.toFloat(),
                                line.box.b.toFloat(), yImage, baseline?.slope ?: 0f)
                        }
                    }
                } else if (legacyGeometry?.words?.isNotEmpty() == true) {
                    for (word in legacyGeometry.words) writeWord(word.text, word.leftPx.toFloat(), word.topPx.toFloat(),
                        word.rightPx.toFloat(), word.bottomPx.toFloat())
                } else {
                    val (fontSize, lines) = fitPlainText(font, plainText.orEmpty(), pageWidth - 80f, pageHeight - 80f, cache)
                    var y = pageHeight - 40f - fontSize
                    for (lineText in lines) {
                        if (lineText.isNotEmpty()) {
                            cs.beginText()
                            cs.setFont(font, fontSize)
                            cs.setRenderingMode(renderingMode)
                            cs.setHorizontalScaling(100f)
                            cs.setTextMatrix(Matrix.getTranslateInstance(40f, y))
                            cs.showText(lineText)
                            cs.endText()
                        }
                        y -= fontSize * 1.3f
                    }
                }
            }
        }
        if (hasText) {
            val extracted = PDFTextStripper().apply {
                startPage = document.numberOfPages
                endPage = document.numberOfPages
            }.getText(document)
            val matches = normalizedTextContent(extracted) == expectedContent
            check(settings.includeImage || matches) { "Text-only PDF did not preserve recognized text content" }
            if (!matches) warnings += "Page ${document.numberOfPages}: extracted PDF text differs from supported recognized content"
        }
        warnings.filter { it.startsWith("Page ${document.numberOfPages}:") }.forEach {
            android.util.Log.w("SearchablePdfWriter", it)
        }
    }

    /** Plain imported text has no geometry: wrap and fit the entire input on its source page. */
    private fun fitPlainText(
        font: PDType0Font, raw: String, width: Float, height: Float, cache: HashMap<Int, Boolean>,
    ): Pair<Float, List<String>> {
        val paragraphs = raw.lines().map { font.safeText(it, cache).orEmpty() }
        check(paragraphs.any { it.isNotBlank() }) { "Recognized text has no supported PDF glyphs" }
        fun wrap(size: Float): List<String> = buildList {
            for (paragraph in paragraphs) {
                if (paragraph.isEmpty()) { add(""); continue }
                val line = StringBuilder()
                var lineWidth = 0f
                val widths = HashMap<Int, Float>()
                // Wrap at whitespace when possible, split long unbroken strings at code points.
                for (token in paragraph.split(Regex("\\s+"))) {
                    val tokenWidth = font.getStringWidth(token) * size / 1000f
                    val spaceWidth = font.getStringWidth(" ") * size / 1000f
                    if (line.isNotEmpty() && lineWidth + spaceWidth + tokenWidth > width) {
                        add(line.toString()); line.setLength(0); lineWidth = 0f
                    }
                    if (line.isNotEmpty()) { line.append(' '); lineWidth += spaceWidth }
                    token.codePoints().forEach { cp ->
                        val charWidth = widths.getOrPut(cp) { font.getStringWidth(String(Character.toChars(cp))) * size / 1000f }
                        if (line.isNotEmpty() && lineWidth + charWidth > width) {
                            add(line.toString()); line.setLength(0); lineWidth = 0f
                        }
                        line.appendCodePoint(cp); lineWidth += charWidth
                    }
                }
                add(line.toString())
            }
        }
        var low = 0.1f
        var high = 12f
        check(wrap(low).size * low * 1.3f <= height) { "Recognized text is too large for one PDF page" }
        repeat(20) {
            val mid = (low + high) / 2f
            if (wrap(mid).size * mid * 1.3f <= height) low = mid else high = mid
        }
        return low to wrap(low)
    }

    private fun resolveFont(document: PDDocument): PDType0Font? {
        return try {
            appContext.assets.open("fonts/NotoSans-Regular.ttf").use { input ->
                PDType0Font.load(document, input, true)
            }
        } catch (e: Exception) {
            android.util.Log.w("SearchablePdfWriter", "Failed to load bundled font fonts/NotoSans-Regular.ttf", e)
            null
        }
    }

    companion object {
        /** Compare content rather than PDFBox's inferred word and line separators. */
        internal fun normalizedTextContent(raw: String): String = buildString {
            java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC).codePoints().forEach { cp ->
                if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) appendCodePoint(cp)
            }
        }

        fun PDType0Font.safeText(raw: String, cache: HashMap<Int, Boolean>): String? {
            val sb = StringBuilder()
            raw.codePoints().forEach { original ->
                val cp = if (Character.isWhitespace(original) || Character.isSpaceChar(original)) 32 else original
                val ok = cache.getOrPut(cp) {
                    try { encode(String(Character.toChars(cp))).isNotEmpty() } catch (_: Exception) { false }
                }
                if (ok) sb.appendCodePoint(cp)
            }
            return sb.toString().trim().ifEmpty { null }
        }
    }
}
