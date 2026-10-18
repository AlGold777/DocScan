package app.docscan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.ocr.OcrLangMode
import app.docscan.core.ocr.toPlainText
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, reproducible benchmark; results are diagnostics, not claims about real camera scans. */
@RunWith(AndroidJUnit4::class)
class OcrQualityBenchmarkInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun benchmarkSyntheticPagesAcrossProfilesSegmentationModesAndModelSets() {
        val bestAssets = InstrumentationRegistry.getInstrumentation().context.assets
            .list("tessdata-best").orEmpty().toSet()
        assumeTrue(
            "Prepare the pinned validation models with tools/prepare_ocr_benchmark_assets.py",
            setOf("eng.traineddata", "rus.traineddata", "spa.traineddata").all(bestAssets::contains),
        )
        val report = File(context.cacheDir, "ocr-quality-benchmark.csv")
        val bestRoot = OcrQualityTestSupport.installBestModels(context)
        val lines = mutableListOf(
            "fixture,model,profile,psm,cer,order_ok,elapsed_ms,peak_pss_kb,truth,recognized",
        )
        val fixtures = OcrQualityTestSupport.fixtures

        TesseractOcrAdapter(context).use { fast ->
            fixtures.forEach { fixture ->
                record(lines, fixture, "fast", OcrRunSettings(), fast)
            }
            listOf("shadow", "small", "en").forEach { id ->
                val fixture = fixtures.first { it.id == id }
                OcrImageProfile.entries.forEach { profile ->
                    record(lines, fixture, "fast", OcrRunSettings(TessBaseAPI.PageSegMode.PSM_AUTO, profile), fast)
                }
            }
            listOf("columns", "receipt").forEach { id ->
                val fixture = fixtures.first { it.id == id }
                listOf(
                    TessBaseAPI.PageSegMode.PSM_AUTO,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                    TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                ).forEach { psm ->
                    record(lines, fixture, "fast", OcrRunSettings(psm, OcrImageProfile.ORIGINAL), fast)
                }
            }
        }

        listOf("ru", "en", "es").forEach { id ->
            val fixture = fixtures.first { it.id == id }
            TesseractOcrAdapter(context, modelDataRootOverride = bestRoot).use { best ->
                record(lines, fixture, "best", OcrRunSettings(), best)
            }
        }

        TesseractOcrAdapter(context).use { fast -> runLargePageCropProbe(lines, fast) }

        report.writeText(lines.joinToString("\n", postfix = "\n"))
        android.util.Log.i("DocScanOcrBenchmark", "CSV=${report.absolutePath}\n${lines.joinToString("\n")}")
        assertEquals("twelve synthetic fixture pages are present", 12, fixtures.size)
        assertEquals("all 12 fixture baselines were measured", 12, fixtures.count { fixture ->
            lines.any { it.startsWith("${fixture.id},fast,ORIGINAL,${TessBaseAPI.PageSegMode.PSM_AUTO},") }
        })
    }

    private fun record(
        output: MutableList<String>,
        fixture: OcrFixture,
        model: String,
        settings: OcrRunSettings,
        adapter: TesseractOcrAdapter,
    ) {
        val image = OcrQualityTestSupport.savePng(context, fixture)
        try {
            lateinit var result: TesseractOcrAdapter.OcrResult
            val measurement = OcrQualityTestSupport.measure {
                result = adapter.recognizeDetailed(
                    image,
                    mode = when (fixture.id) {
                        "es" -> OcrLangMode.ES_EN
                        "mixed" -> OcrLangMode.RU_ES_EN
                        "ru" -> OcrLangMode.RU_EN
                        else -> OcrLangMode.RU_EN
                    },
                    settings = settings,
                )
            }
            val expectedTokens = words(fixture.truth)
            val actualTokens = words(result.text)
            val orderOk = isSubsequence(expectedTokens, actualTokens)
            val cer = OcrQualityTestSupport.cer(fixture.truth, result.text)
            output += listOf(
                fixture.id, model, settings.imageProfile.name, settings.pageSegMode.toString(),
                "%.5f".format(Locale.ROOT, cer), orderOk.toString(), measurement.elapsedMs.toString(),
                measurement.peakPssKb.toString(), csv(fixture.truth), csv(result.text.replace('\n', ' ')),
            ).joinToString(",")
            android.util.Log.i(
                "DocScanOcrBenchmark",
                "${fixture.id},$model,${settings.imageProfile},psm=${settings.pageSegMode}," +
                    "CER=${"%.3f".format(Locale.ROOT, cer)},order=$orderOk," +
                    "elapsed=${measurement.elapsedMs}ms,peakPSS=${measurement.peakPssKb}KB",
            )
        } finally { image.delete() }
    }

    /** Compares the former 4MP power-of-two decode with the current precise 6MP OCR decode. */
    private fun runLargePageCropProbe(output: MutableList<String>, adapter: TesseractOcrAdapter) {
        val rows = (0 until 22).map { row -> "ID%02d TOTAL %06d REF %04d".format(Locale.ROOT, row + 1, 125000 + row, 8000 + row) }
        val truth = rows.joinToString("\n")
        val source = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val image = File(context.cacheDir, "ocr-quality-12mp-${System.nanoTime()}.jpg")
        try {
            val canvas = Canvas(source)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textSize = 32f
            }
            rows.forEachIndexed { index, row -> canvas.drawText(row, 150f, 140f + index * 125f, paint) }
            image.outputStream().use { outputStream ->
                check(source.compress(Bitmap.CompressFormat.JPEG, 96, outputStream))
                outputStream.fd.sync()
            }
        } finally { source.recycle() }

        try {
            val oldBitmap = ProductImageDecoder.decode(image, 4L * 1024L * 1024L, Bitmap.Config.ARGB_8888)
                ?: error("legacy 4MP decoder returned no bitmap")
            try {
                lateinit var oldText: String
                val measurement = OcrQualityTestSupport.measure {
                    oldText = adapter.recognize(oldBitmap, OcrLangMode.RU_EN).toPlainText()
                }
                appendCropRow(output, "crop-legacy-4mp", oldBitmap.width, oldBitmap.height, truth, oldText, measurement)
            } finally { oldBitmap.recycle() }

            val preciseBitmap = ProductImageDecoder.decodePrecisely(image, 6L * 1024L * 1024L)
            try {
                lateinit var preciseText: String
                val measurement = OcrQualityTestSupport.measure {
                    preciseText = adapter.recognize(preciseBitmap, OcrLangMode.RU_EN).toPlainText()
                }
                appendCropRow(output, "crop-current-6mp", preciseBitmap.width, preciseBitmap.height, truth, preciseText, measurement)
                assertTrue("precise decode retains more pixels than the former 4MP path",
                    preciseBitmap.width.toLong() * preciseBitmap.height > 2_000L * 1_500L)
            } finally { preciseBitmap.recycle() }
        } finally { image.delete() }
    }

    private fun appendCropRow(
        output: MutableList<String>,
        id: String,
        width: Int,
        height: Int,
        truth: String,
        recognized: String,
        measurement: OcrMeasurement,
    ) {
        val orderOk = isSubsequence(words(truth), words(recognized))
        val cer = OcrQualityTestSupport.cer(truth, recognized)
        output += listOf(id, "fast", "ORIGINAL", TessBaseAPI.PageSegMode.PSM_AUTO.toString(),
            "%.5f".format(Locale.ROOT, cer), orderOk.toString(), measurement.elapsedMs.toString(),
            measurement.peakPssKb.toString(), csv("${width}x$height; $truth"), csv(recognized.replace('\n', ' ')))
            .joinToString(",")
        android.util.Log.i("DocScanOcrBenchmark", "$id ${width}x$height CER=$cer order=$orderOk " +
            "elapsed=${measurement.elapsedMs}ms peakPSS=${measurement.peakPssKb}KB")
    }

    private fun words(text: String): List<String> = Regex("[\\p{L}\\p{M}\\p{N}]+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun isSubsequence(expected: List<String>, actual: List<String>): Boolean {
        if (expected.isEmpty()) return actual.isEmpty()
        var next = 0
        actual.forEach { if (it == expected[next]) next++ else Unit
            if (next == expected.size) return true
        }
        return false
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

}
