package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class RenderedSavedPdfPage(
    val bitmap: Bitmap,
    val pageCount: Int,
    val pageIndex: Int,
)

/** Renders one page at a time from an immutable, checksum-verified local PDF output. */
object SavedPdfPageRenderer {
    const val MAX_LONG_SIDE_PX = 1600

    fun renderPage(
        file: File,
        expectedPageCount: Int,
        pageIndex: Int,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): RenderedSavedPdfPage {
        require(expectedPageCount > 0 && pageIndex in 0 until expectedPageCount)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount == expectedPageCount) {
                    "Saved PDF page count differs from its confirmed export record"
                }
                renderer.openPage(pageIndex).use { page ->
                    val sourceLongSide = max(page.width, page.height)
                    val scale = (MAX_LONG_SIDE_PX.toFloat() / sourceLongSide).coerceAtMost(1f)
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val reservation = Math.addExact(width.toLong() * height * 4L, RENDER_OVERHEAD_BYTES)
                    val bitmap = scheduler.withAdmission(reservation) {
                        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(
                                target,
                                null,
                                Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) },
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            target
                        } catch (failure: Throwable) {
                            target.recycle()
                            throw failure
                        }
                    }
                    return RenderedSavedPdfPage(bitmap, renderer.pageCount, pageIndex)
                }
            }
        }
    }

    private const val RENDER_OVERHEAD_BYTES = 8L * 1024L * 1024L
}
