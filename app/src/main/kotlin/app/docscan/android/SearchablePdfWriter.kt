package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
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
)

data class PdfWriteResult(val pageCount: Int, val fileSizeBytes: Long)

/** PDF writer candidate. Output stays local; SAF export is owned by the later T6.6 workflow. */
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
                pages.forEach { source ->
                    val bitmap = source.openBitmap()
                    try {
                        addPage(document, bitmap, source.recognition, source.layout, settings, font)
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
        settings: PdfOutputSettings,
        font: PDType0Font?,
    ) {
        if (layout != null) {
            require(layout.formatVersion == 2) { "OCR layout does not match formatVersion 2" }
        } else if (recognition != null) {
            require(recognition.formatVersion == 1L
                && recognition.coordinateSpace == app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
            ) { "OCR geometry does not match formatVersion 1" }
        }
        val pageWidth = if (bitmap.width >= bitmap.height) settings.a4LongSidePoints else settings.a4ShortSidePoints
        val pageHeight = if (bitmap.width >= bitmap.height) settings.a4ShortSidePoints else settings.a4LongSidePoints
        val scale = minOf(pageWidth / bitmap.width, pageHeight / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val offsetX = (pageWidth - imageWidth) / 2f
        val offsetY = (pageHeight - imageHeight) / 2f
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        val image = JPEGFactory.createFromImage(document, bitmap, settings.jpegQuality, settings.jpegResolutionDpi)
        PDPageContentStream(document, page).use { content ->
            content.drawImage(image, offsetX, offsetY, imageWidth, imageHeight)
            if (font != null) {
                if (layout != null) {
                    val blocks = layout.blocks.sortedBy { it.readingOrder }
                    blocks.forEach { block ->
                        block.paragraphs.forEach { paragraph ->
                            paragraph.lines.forEach { line ->
                                content.beginText()
                                content.setRenderingMode(RenderingMode.NEITHER)
                                line.words.forEach { word ->
                                    val boxWidth = word.box.width * imageWidth
                                    val boxHeight = word.box.height * imageHeight
                                    val x = offsetX + word.box.left * imageWidth
                                    val bottom = offsetY + (1f - word.box.bottom) * imageHeight
                                    val fontDescriptor = font.fontDescriptor
                                    val glyphHeight = fontDescriptor.ascent - fontDescriptor.descent
                                    if (boxWidth <= 0f || boxHeight <= 0f || glyphHeight <= 0f) return@forEach
                                    val cleanText = app.docscan.core.store.sanitizeOcrText(word.text)
                                    val safeWord = sanitizeForFont(font, cleanText)
                                    if (safeWord.isBlank()) return@forEach
                                    val fontSize = boxHeight * 1000f / glyphHeight
                                    val lineBaseline = line.baseline
                                    val baseline = if (lineBaseline != null && layout.sourceHeightPx > 0) {
                                        val lineBottomPdf = offsetY + (1f - line.box.bottom) * imageHeight
                                        val baselinePdf = lineBottomPdf - (lineBaseline.intercept / layout.sourceHeightPx) * imageHeight
                                        baselinePdf.coerceIn(bottom, bottom + boxHeight)
                                    } else {
                                        bottom - fontDescriptor.descent * fontSize / 1000f
                                    }
                                    val naturalWidth = try {
                                        font.getStringWidth(safeWord) * fontSize / 1000f
                                    } catch (_: Exception) {
                                        0f
                                    }
                                    if (naturalWidth <= 0f) return@forEach
                                    val horizontalScale = boxWidth * 100f / naturalWidth
                                    if (!horizontalScale.isFinite() || horizontalScale <= 0f) return@forEach
                                    content.setFont(font, fontSize)
                                    content.setHorizontalScaling(horizontalScale)
                                    content.setTextMatrix(Matrix.getTranslateInstance(x, baseline))
                                    try {
                                        content.showText(safeWord)
                                    } catch (_: Exception) {
                                        // Fail-safe: do not let a rogue glyph fail the entire PDF export
                                    }
                                }
                                content.endText()
                            }
                        }
                    }
                } else if (recognition != null) {
                    recognition.words.forEach { word ->
                        val boxWidth = (word.rightPx - word.leftPx) * scale
                        val boxHeight = (word.bottomPx - word.topPx) * scale
                        val x = offsetX + word.leftPx * scale
                        val bottom = offsetY + (bitmap.height - word.bottomPx) * scale
                        val fontDescriptor = font.fontDescriptor
                        val glyphHeight = fontDescriptor.ascent - fontDescriptor.descent
                        if (boxWidth <= 0f || boxHeight <= 0f || glyphHeight <= 0f) return@forEach
                        val cleanText = app.docscan.core.store.sanitizeOcrText(word.text)
                        val safeWord = sanitizeForFont(font, cleanText)
                        if (safeWord.isBlank()) return@forEach
                        val fontSize = boxHeight * 1000f / glyphHeight
                        val baseline = bottom - fontDescriptor.descent * fontSize / 1000f
                        val naturalWidth = try {
                            font.getStringWidth(safeWord) * fontSize / 1000f
                        } catch (_: Exception) {
                            0f
                        }
                        if (naturalWidth <= 0f) return@forEach
                        val horizontalScale = boxWidth * 100f / naturalWidth
                        if (!horizontalScale.isFinite() || horizontalScale <= 0f) return@forEach
                        content.beginText()
                        content.setFont(font, fontSize)
                        content.setRenderingMode(RenderingMode.NEITHER)
                        content.setHorizontalScaling(horizontalScale)
                        content.setTextMatrix(Matrix.getTranslateInstance(x, baseline))
                        try {
                            content.showText(safeWord)
                        } catch (_: Exception) {
                            // Fail-safe: do not let a rogue glyph fail the entire PDF export
                        }
                        content.endText()
                    }
                }
            }
        }
    }

    private fun resolveFont(document: PDDocument): PDType0Font? {
        val candidates = mutableListOf<File>()
        FONT_CANDIDATES.forEach { path ->
            val f = File(path)
            if (f.isFile && f.canRead()) candidates.add(f)
        }
        val systemFontsDir = File("/system/fonts")
        if (systemFontsDir.isDirectory) {
            systemFontsDir.listFiles()?.filter {
                it.isFile && it.canRead() && it.name.endsWith(".ttf", ignoreCase = true)
            }?.forEach { f ->
                if (!candidates.contains(f)) candidates.add(f)
            }
        }
        for (file in candidates) {
            try {
                return PDType0Font.load(document, file)
            } catch (_: Exception) {
                // try next candidate
            }
        }
        return null
    }

    private fun sanitizeForFont(font: PDType0Font, text: String): String {
        return try {
            font.encode(text)
            text
        } catch (_: Exception) {
            val sb = StringBuilder(text.length)
            for (cp in text.codePoints()) {
                val chars = Character.toChars(cp)
                val s = String(chars)
                try {
                    font.encode(s)
                    sb.append(s)
                } catch (_: Exception) {
                    // Skip characters not present in the font
                }
            }
            sb.toString()
        }
    }

    private companion object {
        val FONT_CANDIDATES = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/Roboto.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/NotoSans.ttf",
            "/system/fonts/DroidSans.ttf",
            "/system/fonts/NotoSansCyrillic-Regular.ttf",
            "/system/fonts/RobotoFlex-Regular.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
        )
    }
}
