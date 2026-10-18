package app.docscan.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.exifinterface.media.ExifInterface
import app.docscan.core.ocr.OcrLangMode
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrQualityRegressionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun blankAndCorruptInputsStayDistinctAndNeverInventWordGeometry() {
        val blank = OcrQualityTestSupport.savePng(context, OcrQualityTestSupport.fixtures.first { it.id == "blank" })
        val corrupt = File(context.cacheDir, "ocr-corrupt-${System.nanoTime()}.png").apply { writeText("not an image") }
        try {
            TesseractOcrAdapter(context).use { adapter ->
                val result = adapter.recognizeDetailed(blank, mode = OcrLangMode.RU_ES_EN)
                assertTrue("blank page should return blank text", result.text.isBlank())
                assertTrue("blank page should not contain invented geometry", result.geometry.words.isEmpty())
                assertTrue(result.layout.blocks.isEmpty())

                try {
                    adapter.recognizeDetailed(corrupt, mode = OcrLangMode.RU_EN)
                    throw AssertionError("corrupt image should be reported as an error")
                } catch (failure: Exception) {
                    assertFalse("decode errors must not be reported as cancellation", failure is CancellationException)
                }
            }
        } finally { blank.delete(); corrupt.delete() }
    }

    @Test
    fun cancelledBeforeDecodePropagatesWithoutStartingNativeRecognition() {
        val file = OcrQualityTestSupport.savePng(context, OcrQualityTestSupport.fixtures.first { it.id == "en" })
        val progress = AtomicReference(false)
        try {
            TesseractOcrAdapter(context, { progress.set(true) }).use { adapter ->
                try {
                    adapter.recognizeDetailed(file, isCancelled = { true }, mode = OcrLangMode.RU_EN)
                    throw AssertionError("cancellation should propagate")
                } catch (_: CancellationException) { }
                assertFalse("pre-cancelled request must not enter native recognition", progress.get())
            }
        } finally { file.delete() }
    }

    @Test
    fun cancelCurrentRecognitionStopsAnActiveNativePass() {
        val file = OcrQualityTestSupport.savePng(context, OcrQualityTestSupport.fixtures.first { it.id == "dense" })
        val recoveryFile = OcrQualityTestSupport.savePng(context,
            OcrQualityTestSupport.fixtures.first { it.id == "en" })
        val nativeProgress = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val adapter = TesseractOcrAdapter(context, { nativeProgress.countDown() })
        val worker = Thread {
            try { adapter.recognizeDetailed(file, mode = OcrLangMode.RU_EN) }
            catch (failure: Throwable) { outcome.set(failure) }
            finally { finished.countDown() }
        }
        try {
            worker.start()
            assertTrue("Tesseract progress callback did not arrive", nativeProgress.await(30, TimeUnit.SECONDS))
            adapter.cancelCurrentRecognition()
            assertTrue("recognition did not stop after cancellation", finished.await(30, TimeUnit.SECONDS))
            assertTrue("active cancellation should propagate, got ${outcome.get()}", outcome.get() is CancellationException)
            val recovered = adapter.recognizeDetailed(recoveryFile, mode = OcrLangMode.RU_EN)
            assertTrue("adapter should process the next request after cancellation: ${recovered.text}",
                recovered.text.contains("Invoice") && recovered.text.contains("504821"))
        } finally {
            adapter.close(); worker.interrupt(); file.delete(); recoveryFile.delete()
        }
    }

    @Test
    fun twelveMegapixelSourceRemainsFullResolutionWhenAllowed() {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "ocr-12mp-${System.nanoTime()}.jpg")
        try {
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.fd.sync()
            }
            val decoded = ProductImageDecoder.decodePrecisely(file, 12L * 1024L * 1024L)
            try {
                assertEquals(4000, decoded.width)
                assertEquals(3000, decoded.height)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test
    fun preciseDecoderMatchesLegacyExifOrientationForRotateAndFlip() {
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(source)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
        canvas.drawRect(0f, 0f, 58f, 38f, paint)
        paint.color = android.graphics.Color.BLUE
        canvas.drawRect(62f, 42f, 120f, 80f, paint)
        val file = File(context.cacheDir, "ocr-exif-${System.nanoTime()}.jpg")
        try {
            FileOutputStream(file).use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.fd.sync()
            }
            listOf(
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ).forEach { orientation ->
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val precise = ProductImageDecoder.decodePrecisely(file, 1_000_000)
                val legacy = ProductImageDecoder.decode(file, 1_000_000, Bitmap.Config.ARGB_8888)
                    ?: error("legacy decoder returned no image")
                try {
                    assertEquals("EXIF orientation $orientation width", legacy.width, precise.width)
                    assertEquals("EXIF orientation $orientation height", legacy.height, precise.height)
                    listOf(0.15f to 0.15f, 0.85f to 0.85f, 0.15f to 0.85f, 0.85f to 0.15f).forEach { (x, y) ->
                        val px = (precise.width * x).toInt().coerceAtMost(precise.width - 1)
                        val py = (precise.height * y).toInt().coerceAtMost(precise.height - 1)
                        val a = precise.getPixel(px, py)
                        val b = legacy.getPixel(px, py)
                        val delta = listOf(
                            kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
                            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
                            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
                        ).maxOrNull() ?: 0
                        assertTrue("EXIF orientation $orientation changed corner pixels", delta < 40)
                    }
                } finally { precise.recycle(); legacy.recycle() }
            }
        } finally { source.recycle(); file.delete() }
    }

    @Test
    fun invoiceNumberAndAmountSurviveRecognition() {
        val file = OcrQualityTestSupport.savePng(context, OcrQualityTestSupport.fixtures.first { it.id == "en" })
        try {
            TesseractOcrAdapter(context).use { adapter ->
                val text = adapter.recognizeDetailed(file, mode = OcrLangMode.RU_EN).text
                assertTrue("invoice identifier missing: $text", text.contains("504821"))
                assertTrue("invoice amount missing: $text", text.contains("127.45"))
            }
        } finally { file.delete() }
    }

    @Test
    fun pageSegmentationAndPhotometricProfilesPreserveSourceCoordinates() {
        val file = OcrQualityTestSupport.savePng(context, OcrQualityTestSupport.fixtures.first { it.id == "shadow" })
        try {
            TesseractOcrAdapter(context).use { adapter ->
                val result = adapter.recognizeDetailed(
                    file,
                    mode = OcrLangMode.RU_ES_EN,
                    settings = OcrRunSettings(TessBaseAPI.PageSegMode.PSM_AUTO, OcrImageProfile.LIGHTING),
                )
                assertEquals(OcrQualityTestSupport.WIDTH.toLong(), result.geometry.sourceWidthPx)
                assertEquals(OcrQualityTestSupport.HEIGHT.toLong(), result.geometry.sourceHeightPx)
                assertFalse("nonblank page should have real recognized words", result.geometry.words.isEmpty())
                assertTrue(result.geometry.words.all { word ->
                    word.leftPx >= 0 && word.topPx >= 0 &&
                        word.rightPx <= OcrQualityTestSupport.WIDTH && word.bottomPx <= OcrQualityTestSupport.HEIGHT
                })
            }
        } finally { file.delete() }
    }

    @Test
    fun automaticLightingOnlyPreprocessesUnevenBackgroundAndKeepsItsText() {
        val clearFile = OcrQualityTestSupport.savePng(context,
            OcrQualityTestSupport.fixtures.first { it.id == "en" })
        val shadowFile = OcrQualityTestSupport.savePng(context,
            OcrQualityTestSupport.fixtures.first { it.id == "shadow" })
        try {
            val clear = ProductImageDecoder.decodePrecisely(clearFile, 6L * 1024L * 1024L)
            val shadow = ProductImageDecoder.decodePrecisely(shadowFile, 6L * 1024L * 1024L)
            try {
                assertEquals(OcrImageProfile.ORIGINAL, OcrImagePreparation.suggestProfile(clear))
                assertEquals(OcrImageProfile.LIGHTING, OcrImagePreparation.suggestProfile(shadow))
            } finally { clear.recycle(); shadow.recycle() }

            TesseractOcrAdapter(context).use { adapter ->
                val result = adapter.recognizeDetailed(shadowFile, mode = OcrLangMode.RU_EN)
                assertTrue("adaptive OCR should preserve the synthetic receipt truth: ${result.text}",
                    result.text.contains("RECEIPT 8831") && result.text.contains("Coffee 2.50") &&
                        result.text.contains("TOTAL 2.50 EUR"))
                assertTrue("quality metadata should identify applied lighting correction",
                    result.layout.quality?.preprocessingProfile?.startsWith("LIGHTING:") == true)
            }
        } finally { clearFile.delete(); shadowFile.delete() }
    }
}
