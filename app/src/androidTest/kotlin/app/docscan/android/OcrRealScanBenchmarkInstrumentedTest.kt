package app.docscan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.ocr.OcrLangMode
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public FUNSD testing scans; metrics are word-set recall, not annotated reading order. */
@RunWith(AndroidJUnit4::class)
class OcrRealScanBenchmarkInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun benchmarkTenPublicScansWithAdaptiveOriginalAndBestModels() {
        val assets = instrumentation.context.assets
        val corpusFiles = assets.list("funsd").orEmpty().toSet()
        val bestFiles = assets.list("tessdata-best").orEmpty().toSet()
        assumeTrue(
            "Prepare FUNSD and pinned best models with tools/prepare_ocr_benchmark_assets.py",
            corpusFiles.contains("manifest.json") &&
                listOf("eng.traineddata", "rus.traineddata", "spa.traineddata").all(bestFiles::contains),
        )
        val manifest = JSONObject(assets.open("funsd/manifest.json").bufferedReader().use { it.readText() })
        val pages = manifest.getJSONArray("pages")
        assertEquals("the public corpus snapshot contains ten pages", 10, pages.length())
        val report = File(context.cacheDir, "ocr-funsd-benchmark.csv")
        val rows = mutableListOf("fixture,model,profile,annotated_words,word_recall,numeric_recall,elapsed_ms,peak_pss_kb")
        val preparedImages = mutableListOf<File>()
        val bestRoot = OcrQualityTestSupport.installBestModels(context)
        try {
            TesseractOcrAdapter(context).use { fast ->
                TesseractOcrAdapter(context, modelDataRootOverride = bestRoot).use { best ->
                    for (index in 0 until pages.length()) {
                        val id = pages.getJSONObject(index).getString("id")
                        val image = copyAsset("funsd/$id.png", "funsd-$id.png")
                        val annotation = JSONObject(assets.open("funsd/$id.json").bufferedReader().use { it.readText() })
                        preparedImages += image
                        val truth = annotationTokens(annotation)
                        record(rows, id, truth, "fast", "ADAPTIVE", fast) {
                            it.recognizeDetailed(image, mode = OcrLangMode.RU_EN)
                        }
                        record(rows, id, truth, "fast", "ORIGINAL", fast) {
                            it.recognizeDetailed(image, mode = OcrLangMode.RU_EN,
                                settings = OcrRunSettings(autoLighting = false))
                        }
                        if (index < 3) {
                            record(rows, id, truth, "best", "ORIGINAL", best) {
                                it.recognizeDetailed(image, mode = OcrLangMode.RU_EN,
                                    settings = OcrRunSettings(autoLighting = false))
                            }
                        }
                    }
                }
            }
        } finally { preparedImages.forEach(File::delete) }
        report.writeText(rows.joinToString("\n", postfix = "\n"))
        android.util.Log.i("DocScanRealOcrBenchmark", "CSV=${report.absolutePath}\n${rows.joinToString("\n")}")
        assertTrue("all 10 pages have adaptive and ORIGINAL rows", rows.size >= 1 + 10 * 2)
    }

    private fun record(
        output: MutableList<String>,
        id: String,
        truth: List<String>,
        model: String,
        profile: String,
        adapter: TesseractOcrAdapter,
        recognize: (TesseractOcrAdapter) -> TesseractOcrAdapter.OcrResult,
    ) {
        lateinit var result: TesseractOcrAdapter.OcrResult
        val measure = OcrQualityTestSupport.measure { result = recognize(adapter) }
        val prediction = tokens(result.text)
        val all = multisetRecall(truth, prediction)
        val numeric = multisetRecall(truth.filter(::isNumeric), prediction.filter(::isNumeric))
        val row = listOf(
            id, model, profile, truth.size.toString(), "%.5f".format(Locale.ROOT, all),
            "%.5f".format(Locale.ROOT, numeric), measure.elapsedMs.toString(), measure.peakPssKb.toString(),
        ).joinToString(",")
        output += row
        android.util.Log.i("DocScanRealOcrBenchmark", row)
    }

    private fun copyAsset(assetPath: String, name: String): File = File(context.cacheDir, name).also { target ->
        instrumentation.context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun annotationTokens(annotation: JSONObject): List<String> {
        val result = mutableListOf<String>()
        val form = annotation.getJSONArray("form")
        for (i in 0 until form.length()) {
            val words = form.getJSONObject(i).optJSONArray("words") ?: continue
            for (j in 0 until words.length()) result += tokens(words.getJSONObject(j).optString("text"))
        }
        return result
    }

    private fun tokens(text: String): List<String> = Regex("[\\p{L}\\p{M}\\p{N}]+")
        .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        .map { it.value }.toList()

    private fun isNumeric(token: String): Boolean = token.any(Char::isDigit)

    private fun multisetRecall(truth: List<String>, prediction: List<String>): Double {
        if (truth.isEmpty()) return 1.0
        val available = prediction.groupingBy { it }.eachCount().toMutableMap()
        val matched = truth.count { token ->
            val count = available[token] ?: 0
            if (count > 0) available[token] = count - 1
            count > 0
        }
        return matched.toDouble() / truth.size
    }
}
