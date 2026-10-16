package app.docscan.android

import android.content.Context
import app.docscan.core.schedule.HeavyWorkScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class SavedPdfEditOutput(
    val file: File,
    val pageCount: Int,
    val sizeBytes: Long,
    val sha256: String,
    val sourceSha256: String,
)

/** Composes a new PDF from ordered pages of a checksum-verified immutable local source. */
class SavedPdfEditComposer(context: Context) {
    private val appContext = context.applicationContext

    fun compose(
        sourceFile: File,
        expectedPageCount: Int,
        orderedSourcePageIndexes: List<Int>,
        scheduler: HeavyWorkScheduler,
    ): SavedPdfEditOutput {
        require(expectedPageCount in 1..MAX_PAGES)
        require(orderedSourcePageIndexes.isNotEmpty()) { "Cannot save an empty edited PDF" }
        require(orderedSourcePageIndexes.distinct().size == orderedSourcePageIndexes.size) {
            "Edited PDF page order contains duplicates"
        }
        require(orderedSourcePageIndexes.all { it in 0 until expectedPageCount }) {
            "Edited PDF page order refers to a missing source page"
        }
        require(sourceFile.isFile && sourceFile.length() > 0L) { "Saved PDF source is unavailable" }

        val sourceHash = sha256(sourceFile)
        val output = File(appContext.cacheDir, "saved-pdf-edit-${UUID.randomUUID()}.pdf")
        check(!output.exists()) { "Temporary edited-PDF path already exists" }
        return try {
            scheduler.withAdmission(PDF_EDIT_RESERVATION_BYTES) {
                PDFBoxResourceLoader.init(appContext)
                PDDocument.load(sourceFile, MemoryUsageSetting.setupTempFileOnly()).use { source ->
                    check(source.numberOfPages == expectedPageCount) {
                        "Saved PDF page count differs from its recent-document record"
                    }
                    val originalPages = (0 until source.numberOfPages).map(source::getPage)
                    val expectedPageText = orderedSourcePageIndexes.map { sourceIndex -> pageText(source, sourceIndex) }
                    while (source.numberOfPages > 0) source.removePage(0)
                    orderedSourcePageIndexes.forEach { sourceIndex ->
                        source.addPage(originalPages[sourceIndex])
                    }
                    source.save(output)
                    FileOutputStream(output, true).use { it.fd.sync() }
                    validateOutput(output, expectedPageText)
                }
            }
            check(sha256(sourceFile) == sourceHash) { "Original saved PDF changed during editing" }
            SavedPdfEditOutput(output, orderedSourcePageIndexes.size, output.length(), sha256(output), sourceHash)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun validateOutput(output: File, expectedPageText: List<String>) {
        PDDocument.load(output, MemoryUsageSetting.setupTempFileOnly()).use { result ->
            check(result.numberOfPages == expectedPageText.size) {
                "Edited PDF read-back page count differs from the edit order"
            }
            val renderer = PDFRenderer(result)
            expectedPageText.indices.forEach { index ->
                check(normalizeText(pageText(result, index)) == normalizeText(expectedPageText[index])) {
                    "Edited PDF page ${index + 1} text differs from its selected source page"
                }
                val rendered = renderer.renderImage(index, 0.125f)
                try {
                    check(rendered.width > 0 && rendered.height > 0) {
                        "Edited PDF page ${index + 1} failed render validation"
                    }
                } finally {
                    rendered.recycle()
                }
            }
        }
        check(output.isFile && output.length() > 0L) { "Edited PDF is empty after read-back" }
    }

    private fun pageText(document: PDDocument, pageIndex: Int): String = PDFTextStripper().apply {
        startPage = pageIndex + 1
        endPage = pageIndex + 1
    }.getText(document)

    private fun normalizeText(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_PAGES = 500
        const val PDF_EDIT_RESERVATION_BYTES = 24L * 1024L * 1024L
    }
}
