package app.docscan.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.schedule.HeavyWorkScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SavedPdfEditComposerInstrumentedTest {
    @Test
    fun reorderProducesNewReadablePdfAndLeavesSourceUnchanged() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "edit-source-${System.nanoTime()}.pdf")
        try {
            PDDocument().use { document ->
                listOf(300f, 400f, 500f).forEachIndexed { index, width ->
                    val page = PDPage(PDRectangle(width, 600f))
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(50f, 500f)
                        content.showText("PAGE_$index")
                        content.endText()
                    }
                }
                document.save(source)
            }
            val sourceHash = sha256(source)

            val result = SavedPdfEditComposer(context).compose(
                sourceFile = source,
                expectedPageCount = 3,
                orderedSourcePageIndexes = listOf(2, 0),
                scheduler = HeavyWorkScheduler(),
            )

            assertEquals(sourceHash, sha256(source))
            assertEquals(sourceHash, result.sourceSha256)
            assertNotEquals(sourceHash, result.sha256)
            assertTrue(result.file.isFile && result.sizeBytes > 0L)
            PDDocument.load(result.file).use { edited ->
                assertEquals(2, edited.numberOfPages)
                assertEquals(500f, edited.getPage(0).mediaBox.width, 0f)
                assertEquals(300f, edited.getPage(1).mediaBox.width, 0f)
                val text = PDFTextStripper().getText(edited)
                assertTrue(text.indexOf("PAGE_2") in 0 until text.indexOf("PAGE_0"))
            }
            result.file.delete()
        } finally {
            source.delete()
        }
    }

    @Test
    fun rejectsEmptyDuplicateAndOutOfRangeOrders() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "edit-invalid-${System.nanoTime()}.pdf")
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(source)
            }
            val composer = SavedPdfEditComposer(context)
            listOf(emptyList(), listOf(0, 0), listOf(1)).forEach { order ->
                try {
                    composer.compose(source, 1, order, HeavyWorkScheduler())
                    throw AssertionError("Invalid page order was accepted: $order")
                } catch (_: IllegalArgumentException) {
                    // Expected validation before source mutation or output publication.
                }
            }
        } finally {
            source.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
