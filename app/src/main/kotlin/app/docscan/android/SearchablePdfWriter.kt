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

data class PdfWriteResult(val pageCount: Int, val fileSizeBytes: Long)

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
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val font = resolveFont(document)
                val glyphCache = HashMap<Int, Boolean>()
                pages.forEach { source ->
                    val bitmap = source.openBitmap()
                    try {
                        addPage(document, bitmap, source.recognition, source.layout, source.text, settings, font, glyphCache)
                    } finally {
                        bitmap.recycle()
                    }
                }
                document.save(temporary)
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE)
            return PdfWriteResult(pages.size, destination.length())
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
    ) {
        if (recognition != null) {
            require(recognition.sourceWidthPx == bitmap.width.toLong()
                && recognition.sourceHeightPx == bitmap.height.toLong()
                && recognition.coordinateSpace == app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
                && recognition.formatVersion == 1L
            ) { "OCR geometry does not match the oriented PDF source bitmap" }
        }
        val ocrLayout = layout?.toOcrLayout()?.takeIf { it.words.isNotEmpty() }
        val hasText = ocrLayout?.words?.any { it.text.isNotBlank() } == true
            || recognition?.words?.any { it.text.isNotBlank() } == true || !textFallback.isNullOrBlank()
        require(settings.includeImage || hasText) { "Text-only PDF requires recognized text on every page" }
        check(!hasText || font != null) { "PDF text font is unavailable" }
        val pageWidth = if (bitmap.width >= bitmap.height) settings.a4LongSidePoints else settings.a4ShortSidePoints
        val pageHeight = if (bitmap.width >= bitmap.height) settings.a4ShortSidePoints else settings.a4LongSidePoints
        val scale = minOf(pageWidth / bitmap.width, pageHeight / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val offsetX = (pageWidth - imageWidth) / 2f
        val offsetY = (pageHeight - imageHeight) / 2f
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        val bitmapH = bitmap.height
        PDPageContentStream(document, page).use { cs ->
            if (settings.includeImage) {
                val image = JPEGFactory.createFromImage(document, bitmap, settings.jpegQuality, settings.jpegResolutionDpi)
                cs.drawImage(image, offsetX, offsetY, imageWidth, imageHeight)
            }
            if (font != null) {
                val renderingMode = if (settings.includeImage) RenderingMode.NEITHER else RenderingMode.FILL
                if (!settings.includeImage) {
                    cs.setNonStrokingColor(0f, 0f, 0f)
                }
                if (ocrLayout != null) {
                    for (block in ocrLayout.blocks) for (p in block.paras) for (line in p.lines) {
                        val fontSize = ((line.box.b - line.box.t) * scale * 0.8f).coerceIn(4f, 72f)
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setRenderingMode(renderingMode)
                        line.words.forEachIndexed { i, w ->
                            val text = font.safeText(w.text, cache) ?: return@forEachIndexed
                            val natural = font.getStringWidth(text) * fontSize / 1000f
                            val target = (w.box.r - w.box.l) * scale
                            if (natural <= 0f || target <= 0f) return@forEachIndexed
                            val x = offsetX + w.box.l * scale
                            val y = offsetY + (bitmapH - line.box.b) * scale + fontSize * 0.2f
                            val hScale = if (settings.includeImage) {
                                (target * 100f / natural).coerceIn(20f, 500f)
                            } else {
                                (target * 100f / natural).coerceIn(70f, 150f)
                            }
                            cs.setHorizontalScaling(hScale)
                            cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
                            cs.showText(if (i < line.words.lastIndex) "$text " else text)
                        }
                        cs.endText()
                    }
                } else if (recognition?.words?.isNotEmpty() == true) {
                    recognition.words.forEach { w ->
                        val text = font.safeText(w.text, cache) ?: return@forEach
                        val fontSize = ((w.bottomPx - w.topPx) * scale * 0.8f).coerceIn(4f, 72f)
                        val natural = font.getStringWidth(text) * fontSize / 1000f
                        val target = (w.rightPx - w.leftPx) * scale
                        if (natural <= 0f || target <= 0f) return@forEach
                        val x = offsetX + w.leftPx * scale
                        val y = offsetY + (bitmapH - w.bottomPx) * scale + fontSize * 0.2f
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setRenderingMode(renderingMode)
                        val hScale = if (settings.includeImage) {
                            (target * 100f / natural).coerceIn(20f, 500f)
                        } else {
                            (target * 100f / natural).coerceIn(70f, 150f)
                        }
                        cs.setHorizontalScaling(hScale)
                        cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
                        cs.showText(text)
                        cs.endText()
                    }
                } else if (!textFallback.isNullOrBlank()) {
                    val (fontSize, lines) = fitPlainText(font, textFallback, pageWidth - 80f, pageHeight - 80f, cache)
                    var y = pageHeight - 40f - fontSize
                    for (lineText in lines) {
                        if (lineText.isNotEmpty()) {
                            cs.beginText()
                            cs.setFont(font, fontSize)
                            cs.setRenderingMode(renderingMode)
                            cs.setTextMatrix(Matrix.getTranslateInstance(40f, y))
                            cs.showText(lineText)
                            cs.endText()
                        }
                        y -= fontSize * 1.3f
                    }
                }
            }
        }

        // T15. Verification of text layer after writing
        val wordCount = ocrLayout?.words?.size ?: recognition?.words?.size ?: 0
        if (wordCount > 0) {
            val extracted = try {
                val stripper = PDFTextStripper()
                stripper.startPage = document.numberOfPages
                stripper.endPage = document.numberOfPages
                stripper.getText(document).trim()
            } catch (e: Exception) {
                ""
            }
            if (extracted.isEmpty()) {
                android.util.Log.w(
                    "SearchablePdfWriter",
                    "Page ${document.numberOfPages} text layer is empty although $wordCount OCR words were written",
                )
            }
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
        fun PDType0Font.safeText(raw: String, cache: HashMap<Int, Boolean>): String? {
            val sb = StringBuilder()
            raw.codePoints().forEach { cp ->
                val ok = cache.getOrPut(cp) {
                    try { getStringWidth(String(Character.toChars(cp))) > 0f } catch (_: Exception) { false }
                }
                if (ok) sb.appendCodePoint(cp)
            }
            return sb.toString().trim().ifEmpty { null }
        }
    }
}
