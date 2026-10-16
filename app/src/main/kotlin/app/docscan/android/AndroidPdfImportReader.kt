package app.docscan.android

import android.content.Context
import android.os.CancellationSignal
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import app.docscan.core.schedule.HeavyWorkScheduler
import app.docscan.core.store.PdfTextLayerPolicy
import java.io.File
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class PdfImportInspection(
    val pageCount: Int,
    val title: String?,
    val pages: List<PdfPageInspection>,
)

data class PdfPageInspection(
    val pageIndex: Int,
    val widthPoints: Float,
    val heightPoints: Float,
    val rotationDegrees: Int,
    val hasReusableTextLayer: Boolean,
)

class PdfImportInvalidSourceException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal fun interface PdfPageBitmapRenderer {
    fun render(renderer: PDFRenderer, pageIndex: Int, scale: Float): Bitmap
}

internal interface PdfPagePngOutput : Closeable {
    fun compress(bitmap: Bitmap): Boolean
    fun sync()
}

internal fun interface PdfPagePngOutputFactory {
    fun open(output: File): PdfPagePngOutput
}

internal object FilePdfPagePngOutputFactory : PdfPagePngOutputFactory {
    override fun open(output: File): PdfPagePngOutput {
        val stream = FileOutputStream(output)
        return object : PdfPagePngOutput {
            override fun compress(bitmap: Bitmap): Boolean =
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)

            override fun sync() {
                stream.fd.sync()
            }

            override fun close() {
                stream.close()
            }
        }
    }
}

/** Bounded, one-page-at-a-time PDF import using the already pinned PDFBox Android runtime. */
class AndroidPdfImportReader private constructor(
    context: Context,
    private val pageBitmapRenderer: PdfPageBitmapRenderer,
    private val pagePngOutputFactory: PdfPagePngOutputFactory,
    private val recycleBitmap: (Bitmap) -> Unit,
) {
    constructor(context: Context) : this(
        context,
        PdfPageBitmapRenderer { renderer, pageIndex, scale -> renderer.renderImage(pageIndex, scale) },
        FilePdfPagePngOutputFactory,
        Bitmap::recycle,
    )

    private val appContext = context.applicationContext

    fun inspect(source: File): PdfImportInspection {
        check(source.isFile && source.length() > 0L) { "Private PDF source is missing or empty" }
        PDFBoxResourceLoader.init(appContext)
        return try {
            withDocument(source) { document ->
                val count = document.numberOfPages
                require(count in 1..MAX_PDF_PAGES) {
                    "PDF must contain between 1 and $MAX_PDF_PAGES pages (found $count)"
                }
                val stripper = PDFTextStripper()
                val pages = document.pages.mapIndexed { index, page ->
                    validatePage(page, index)
                    stripper.startPage = index + 1
                    stripper.endPage = index + 1
                    val hasReusableTextLayer = PdfTextLayerPolicy.isReusable(stripper.getText(document))
                    val box = page.cropBox
                    PdfPageInspection(
                        pageIndex = index,
                        widthPoints = box.width,
                        heightPoints = box.height,
                        rotationDegrees = page.rotation,
                        hasReusableTextLayer = hasReusableTextLayer,
                    )
                }
                PdfImportInspection(
                    pageCount = count,
                    title = document.documentInformation?.title?.takeIf(String::isNotBlank),
                    pages = pages,
                )
            }
        } catch (failure: IOException) {
            throw PdfImportInvalidSourceException("Private PDF source is malformed or unreadable", failure)
        } catch (failure: IllegalArgumentException) {
            if (failure is PdfImportInvalidSourceException) throw failure
            throw PdfImportInvalidSourceException(failure.message ?: "PDF source violates import constraints", failure)
        } catch (failure: IllegalStateException) {
            throw PdfImportInvalidSourceException(failure.message ?: "PDF source is unsupported", failure)
        }
    }

    fun renderPages(
        source: File,
        expectedPageCount: Int,
        skipPageIndices: Set<Long>,
        scratchDirectory: File,
        scheduler: HeavyWorkScheduler,
        cancellationSignal: CancellationSignal? = null,
        onRenderedPage: (index: Int, pngFile: File, reusableText: String?) -> Unit,
    ) {
        require(expectedPageCount in 1..MAX_PDF_PAGES)
        check(scratchDirectory.isDirectory || scratchDirectory.mkdirs()) { "PDF import scratch directory is unavailable" }
        withDocument(source) { document ->
            check(document.numberOfPages == expectedPageCount) { "PDF page count changed after source snapshot" }
            val renderer = PDFRenderer(document)
            val stripper = PDFTextStripper()
            for (index in 0 until expectedPageCount) {
                cancellationSignal?.throwIfCanceled()
                if (index.toLong() in skipPageIndices) continue
                val page = document.getPage(index)
                validatePage(page, index)
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                val extracted = stripper.getText(document)
                val reusableText = extracted.takeIf(PdfTextLayerPolicy::isReusable)
                val scale = renderScale(page)
                val output = File.createTempFile("pdf-page-${index + 1}-", ".png", scratchDirectory)
                try {
                    scheduler.withAdmission(PDF_RENDER_RESERVATION_BYTES) {
                        val bitmap = pageBitmapRenderer.render(renderer, index, scale)
                        try {
                            cancellationSignal?.throwIfCanceled()
                            check(bitmap.width > 0 && bitmap.height > 0
                                && maxOf(bitmap.width, bitmap.height) <= MAX_RENDER_SIDE_PX
                            ) { "Rendered PDF page exceeds the bounded raster envelope" }
                            pagePngOutputFactory.open(output).use { pageOutput ->
                                check(pageOutput.compress(bitmap)) {
                                    "PDF page image encoding failed"
                                }
                                pageOutput.sync()
                            }
                            cancellationSignal?.throwIfCanceled()
                        } finally {
                            recycleBitmap(bitmap)
                        }
                    }
                    cancellationSignal?.throwIfCanceled()
                    onRenderedPage(index, output, reusableText)
                } finally {
                    output.delete()
                }
            }
        }
    }

    private fun renderScale(page: PDPage): Float {
        val box = page.cropBox
        val points = maxOf(box.width, box.height)
        require(points.isFinite() && points > 0f) { "PDF page has invalid dimensions" }
        return minOf(IMPORT_DPI / PDF_POINTS_PER_INCH, MAX_RENDER_SIDE_PX / points)
    }

    private fun validatePage(page: PDPage, index: Int) {
        val box = page.cropBox
        require(box.width.isFinite() && box.height.isFinite() && box.width > 0f && box.height > 0f) {
            "PDF page ${index + 1} has invalid dimensions"
        }
        require(page.rotation % 90 == 0) { "PDF page ${index + 1} has unsupported rotation" }
    }

    private fun <T> withDocument(source: File, block: (PDDocument) -> T): T {
        check(source.isFile && source.length() > 0L) { "Private PDF source is missing or empty" }
        requireHasTerminalEofMarker(source)
        PDFBoxResourceLoader.init(appContext)
        val document = try {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly())
        } catch (failure: InvalidPasswordException) {
            throw PdfImportInvalidSourceException("Encrypted PDFs are not supported for import", failure)
        }
        return document.use { loaded ->
            if (loaded.isEncrypted) {
                throw PdfImportInvalidSourceException("Encrypted PDFs are not supported for import")
            }
            block(loaded)
        }
    }

    private fun requireHasTerminalEofMarker(source: File) {
        val marker = PDF_EOF_MARKER
        RandomAccessFile(source, "r").use { file ->
            val buffer = ByteArray(TRAILER_SCAN_CHUNK_BYTES)
            var remaining = file.length()
            var finalNonWhitespaceOffset = -1L
            while (remaining > 0L && finalNonWhitespaceOffset < 0L) {
                val chunkLength = minOf(buffer.size.toLong(), remaining).toInt()
                val chunkStart = remaining - chunkLength
                file.seek(chunkStart)
                file.readFully(buffer, 0, chunkLength)
                for (index in chunkLength - 1 downTo 0) {
                    if ((buffer[index].toInt() and 0xff) !in PDF_WHITESPACE_BYTES) {
                        finalNonWhitespaceOffset = chunkStart + index
                        break
                    }
                }
                remaining = chunkStart
            }

            val markerStart = finalNonWhitespaceOffset - marker.lastIndex
            require(finalNonWhitespaceOffset >= 0L && markerStart >= 0L) {
                "PDF is truncated: terminal %%EOF marker is missing"
            }
            val candidate = ByteArray(marker.size)
            file.seek(markerStart)
            file.readFully(candidate)
            require(marker.indices.all { candidate[it] == marker[it] }) {
                "PDF is truncated: terminal %%EOF marker is missing"
            }
        }
    }

    companion object {
        internal fun withRasterizerForTest(
            context: Context,
            rasterizer: PdfPageBitmapRenderer,
        ): AndroidPdfImportReader = AndroidPdfImportReader(
            context,
            rasterizer,
            FilePdfPagePngOutputFactory,
            Bitmap::recycle,
        )

        internal fun withPngOutputForTest(
            context: Context,
            rasterizer: PdfPageBitmapRenderer,
            outputFactory: PdfPagePngOutputFactory,
            recycleBitmap: (Bitmap) -> Unit,
        ): AndroidPdfImportReader = AndroidPdfImportReader(
            context,
            rasterizer,
            outputFactory,
            recycleBitmap,
        )

        private const val MAX_PDF_PAGES = 500
        private const val IMPORT_DPI = 200f
        private const val PDF_POINTS_PER_INCH = 72f
        private const val MAX_RENDER_SIDE_PX = 3000f
        private const val PDF_RENDER_RESERVATION_BYTES = 80L * 1024L * 1024L
        private const val TRAILER_SCAN_CHUNK_BYTES = 8 * 1024
        private val PDF_EOF_MARKER = "%%EOF".toByteArray(StandardCharsets.US_ASCII)
        private val PDF_WHITESPACE_BYTES = setOf(0, 9, 10, 12, 13, 32)
    }
}
