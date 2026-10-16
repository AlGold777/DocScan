package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the bundled offline model through the same production OCR adapter. */
@RunWith(AndroidJUnit4::class)
class TesseractOcrInstrumentedTest {
    @Test
    fun bundledRusEngModelRecognizesCyrillicAndLatinOnOnePage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Bitmap.createBitmap(1800, 520, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fixture)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 104f
        }
        canvas.drawText("Документ № 8472", 70f, 190f, paint)
        canvas.drawText("Invoice 8472", 70f, 390f, paint)

        val png = File(context.cacheDir, "ocr-rus-eng-${System.nanoTime()}.png")
        val jpeg = File(context.cacheDir, "ocr-rus-eng-${System.nanoTime()}.jpg")
        try {
            FileOutputStream(png).use { output ->
                check(fixture.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            FileOutputStream(jpeg).use { output ->
                check(fixture.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.fd.sync()
            }
            TesseractOcrAdapter(context).use { adapter ->
                listOf(png, jpeg).forEach { image ->
                    val decoded = ProductImageDecoder.decode(
                        image,
                        12L * 1024L * 1024L,
                        preferredConfig = Bitmap.Config.ARGB_8888,
                    )
                    assertEquals(Bitmap.Config.ARGB_8888, decoded?.config)
                    decoded?.recycle()

                    val result = adapter.recognizeDetailed(image)
                    val text = result.text.lowercase(Locale.ROOT).replace('ё', 'е')
                    assertTrue("Russian Cyrillic token missing from ${image.extension}: $text", text.contains("документ"))
                    assertTrue("English Latin token missing from ${image.extension}: $text", text.contains("invoice"))
                    assertTrue("Shared numeric token missing from ${image.extension}: $text", text.contains("8472"))
                    assertTrue("Recognized text preserves the two source lines: $text", result.text.contains('\n'))
                    assertEquals(1800L, result.geometry.sourceWidthPx)
                    assertEquals(520L, result.geometry.sourceHeightPx)
                    assertFalse(result.geometry.words.isEmpty())
                    assertTrue(result.geometry.words.all { word ->
                        word.leftPx >= 0 && word.topPx >= 0 && word.rightPx <= 1800 && word.bottomPx <= 520
                    })
                }
            }
        } finally {
            fixture.recycle()
            png.delete()
            jpeg.delete()
        }
    }

    @Test
    fun bundledRusEngModelProcessesSpanishWordsAndDiacritics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Bitmap.createBitmap(1800, 620, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fixture)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 112f
        }
        canvas.drawText("Mañana acción", 70f, 190f, paint)
        canvas.drawText("Niña café pingüino", 70f, 380f, paint)
        canvas.drawText("Número 4821", 70f, 570f, paint)

        val png = File(context.cacheDir, "ocr-spanish-${System.nanoTime()}.png")
        val jpeg = File(context.cacheDir, "ocr-spanish-${System.nanoTime()}.jpg")
        try {
            FileOutputStream(png).use { output ->
                check(fixture.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            FileOutputStream(jpeg).use { output ->
                check(fixture.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.fd.sync()
            }
            TesseractOcrAdapter(context).use { adapter ->
                listOf(png, jpeg).forEach { image ->
                    val result = adapter.recognizeDetailed(image)
                    val normalized = Normalizer.normalize(
                        result.text.lowercase(Locale.ROOT),
                        Normalizer.Form.NFD,
                    ).replace(Regex("\\p{M}+"), "")
                    val tokens = Regex("[a-z0-9]+")
                        .findAll(normalized)
                        .map { it.value }
                        .toSet()
                    Log.i("DocScanOcrLangProbe", "${image.extension}: ${result.text.replace('\n', '|')}")
                    listOf("manana", "accion", "nina", "cafe", "pinguino", "numero", "4821")
                        .forEach { expected ->
                            assertTrue(
                                "Spanish token '$expected' missing from ${image.extension}: ${result.text}",
                                expected in tokens,
                            )
                        }
                    assertEquals(1800L, result.geometry.sourceWidthPx)
                    assertEquals(620L, result.geometry.sourceHeightPx)
                    assertFalse(result.geometry.words.isEmpty())
                }
            }
        } finally {
            fixture.recycle()
            png.delete()
            jpeg.delete()
        }
    }
}
