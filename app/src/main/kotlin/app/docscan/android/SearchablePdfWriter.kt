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
        val ocrLayout = layout?.toOcrLayout()
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
                } else if (recognition != null) {
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
                    val lines = textFallback.lines()
                    val fontSize = 12f
                    val leading = fontSize * 1.3f
                    var y = pageHeight - 50f
                    val startX = 50f
                    for (lineText in lines) {
                        if (y < 40f) break
                        val safe = font.safeText(lineText, cache)
                        if (!safe.isNullOrBlank()) {
                            cs.beginText()
                            cs.setFont(font, fontSize)
                            cs.setRenderingMode(renderingMode)
                            cs.setTextMatrix(Matrix.getTranslateInstance(startX, y))
                            cs.showText(safe)
                            cs.endText()
                        }
                        y -= leading
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
