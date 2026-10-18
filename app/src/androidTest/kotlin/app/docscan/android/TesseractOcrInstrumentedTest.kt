package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.exifinterface.media.ExifInterface
import app.docscan.core.ocr.OcrLangMode
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
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
                    val automatic = adapter.recognizeDetailed(image, mode = OcrLangMode.ES_EN)
                    Log.i("DocScanOcrLangProbe", "${image.extension} PSM_AUTO: ${automatic.text.replace('\n', '|')}")
                    val automaticTokens = Regex("[\\p{L}\\p{M}\\p{N}]+")
                        .findAll(automatic.text.lowercase(Locale.ROOT)).map { it.value }.toSet()
                    val result = adapter.recognizeDetailed(
                        image,
                        mode = OcrLangMode.ES_EN,
                        settings = OcrRunSettings(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK),
                    )
                    val tokens = Regex("[\\p{L}\\p{M}\\p{N}]+")
                        .findAll(result.text.lowercase(Locale.ROOT))
                        .map { it.value }
                        .toSet()
                    Log.i("DocScanOcrLangProbe", "${image.extension} PSM_SINGLE_BLOCK: ${result.text.replace('\n', '|')}")
                    val supportedAccentTokens = listOf("mañana", "acción", "niña", "café", "número", "4821")
                    supportedAccentTokens
                        .forEach { expected ->
                            assertTrue(
                                "PSM_AUTO dropped Spanish token '$expected' from ${image.extension}: ${automatic.text}",
                                expected in automaticTokens,
                            )
                            assertTrue(
                                "Spanish token '$expected' missing from ${image.extension}: ${result.text}",
                                expected in tokens,
                            )
                        }
                    assertEquals(1800L, result.geometry.sourceWidthPx)
                    assertEquals(620L, result.geometry.sourceHeightPx)
                    assertFalse(result.geometry.words.isEmpty())

                    val truth = "Mañana acción\nNiña café pingüino\nNúmero 4821"
                    logSpanishModelResult(image.extension, "fast-ES_EN-PSM_AUTO", truth, automatic.text)
                    logSpanishModelResult(image.extension, "fast-ES_EN-PSM_SINGLE_BLOCK", truth, result.text)
                }
            }

            val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
            val bestAssets = testAssets.list("tessdata-best").orEmpty().toSet()
            if (listOf("eng.traineddata", "rus.traineddata", "spa.traineddata").all(bestAssets::contains)) {
                val bestRoot = OcrQualityTestSupport.installBestModels(context)
                val hybridRoot = installFastEnglishBestSpanishModels(context)
                listOf(png, jpeg).forEach { image ->
                    for ((label, root) in listOf("best-ES_EN" to bestRoot, "fast-eng+best-spa-ES_EN" to hybridRoot)) {
                        TesseractOcrAdapter(context, modelDataRootOverride = root).use { adapter ->
                            val bestResult = adapter.recognizeDetailed(image, mode = OcrLangMode.ES_EN)
                            logSpanishModelResult(image.extension, label, "Mañana acción\nNiña café pingüino\nNúmero 4821", bestResult.text)
                        }
                    }
                }
            } else {
                Log.i("DocScanOcrLangProbe", "Spanish best/hybrid comparison skipped; run tools/prepare_ocr_benchmark_assets.py")
            }
        } finally {
            fixture.recycle()
            png.delete()
            jpeg.delete()
        }
    }

    private fun logSpanishModelResult(extension: String, model: String, truth: String, recognized: String) {
        val diaeresis = Regex("(?iu)pingüino").containsMatchIn(recognized)
        Log.i("DocScanOcrLangProbe", "$extension model=$model cer=${OcrQualityTestSupport.cer(truth, recognized)} " +
            "pingueino_with_diaeresis=$diaeresis text=${recognized.replace('\n', '|')}")
    }

    private fun installFastEnglishBestSpanishModels(context: android.content.Context): File {
        val root = File(context.cacheDir, "tessdata-fast-eng-best-spa-validation")
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        context.assets.open("tesseract/tessdata/eng.traineddata").use { input ->
            File(tessdata, "eng.traineddata").outputStream().use { output -> input.copyTo(output) }
        }
        InstrumentationRegistry.getInstrumentation().context.assets.open("tessdata-best/spa.traineddata").use { input ->
            File(tessdata, "spa.traineddata").outputStream().use { output -> input.copyTo(output) }
        }
        return root
    }
}
