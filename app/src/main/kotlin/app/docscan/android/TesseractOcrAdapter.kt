package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.text.Html
import app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
import app.docscan.core.store.RecognitionGeometry
import app.docscan.core.store.RecognizedWord
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Offline Russian + English recognizer. Use from the single admitted OCR executor only. */
class TesseractOcrAdapter(
    context: Context,
    private val progressObserverForTest: (() -> Unit)? = null,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val dataRoot = File(appContext.filesDir, "docscan-tesseract/fast-8741641")
    private val tess = TessBaseAPI(
        progressObserverForTest?.let { observer -> TessBaseAPI.ProgressNotifier { observer() } },
    )
    private val cancellationRequested = AtomicBoolean(false)
    private val nativeRecognitionActive = AtomicBoolean(false)
    @Volatile private var initialized = false
    private var unusable = false

    data class OcrResult(val text: String, val geometry: RecognitionGeometry)

    fun estimateDecodedWorkingSetBytes(file: File): Long =
        ProductImageDecoder.estimateWorkingSetBytes(file, MAX_BITMAP_PIXELS)

    fun recognize(file: File): String {
        return recognizeDetailed(file).text
    }

    fun recognizeDetailed(file: File, isCancelled: () -> Boolean = { false }): OcrResult {
        cancellationRequested.set(false)
        throwIfCancelled(isCancelled)
        ensureInitialized()
        val bitmap = ProductImageDecoder.decode(
            file,
            MAX_BITMAP_PIXELS,
            preferredConfig = Bitmap.Config.ARGB_8888,
        )
            ?: throw IllegalArgumentException("Image cannot be decoded")
        return bitmap.useBitmap { decoded ->
            throwIfCancelled(isCancelled)
            check(decoded.config == Bitmap.Config.ARGB_8888) {
                "OCR bitmap must use ARGB_8888"
            }
            tess.setImage(decoded)
            try {
                throwIfCancelled(isCancelled)
                nativeRecognitionActive.set(true)
                throwIfCancelled(isCancelled)
                val words = try {
                    val hocr = tess.getHOCRText(0).orEmpty()
                    val iterWords = extractWordsFromIterator(tess)
                    if (iterWords.isNotEmpty()) {
                        iterWords
                    } else {
                        parseHocrWords(hocr)
                    }
                } catch (_: Throwable) {
                    val hocr = tess.getHOCRText(0).orEmpty()
                    parseHocrWords(hocr)
                } finally {
                    nativeRecognitionActive.set(false)
                }
                throwIfCancelled(isCancelled)
                OcrResult(
                    text = wordsToText(words),
                    geometry = RecognitionGeometry(
                        sourceWidthPx = decoded.width.toLong(),
                        sourceHeightPx = decoded.height.toLong(),
                        coordinateSpace = OCR_WORD_COORDINATE_SPACE,
                        words = words,
                    ),
                )
            } finally {
                tess.clear()
            }
        }
    }

    fun cancelCurrentRecognition() {
        cancellationRequested.set(true)
        if (nativeRecognitionActive.get() && initialized) {
            tess.stop()
        }
    }

    override fun close() {
        if (initialized) {
            tess.recycle()
            initialized = false
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        check(!unusable) { "Offline Russian/English OCR is unavailable after model initialization failed" }
        installModel("eng.traineddata", ENG_SHA256)
        installModel("rus.traineddata", RUS_SHA256)
        if (!tess.init(dataRoot.absolutePath, "rus+eng")) {
            tess.recycle()
            unusable = true
            throw IllegalStateException("Offline Russian/English OCR model could not be initialized")
        }
        initialized = true
    }

    private fun installModel(name: String, expectedSha256: String) {
        val tessdata = File(dataRoot, "tessdata")
        check(tessdata.isDirectory || tessdata.mkdirs()) { "OCR model directory cannot be created" }
        val target = File(tessdata, name)
        if (target.isFile && sha256(target) == expectedSha256) return

        val temporary = File(tessdata, "$name.installing")
        appContext.assets.open("tesseract/tessdata/$name").use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(sha256(temporary) == expectedSha256) { "Bundled OCR model checksum mismatch: $name" }
        check(!target.exists() || target.delete()) { "Outdated OCR model cannot be replaced: $name" }
        check(temporary.renameTo(target)) { "OCR model could not be installed: $name" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun throwIfCancelled(isCancelled: () -> Boolean) {
        if (cancellationRequested.get() || isCancelled()) throw CancellationException("OCR was canceled")
    }

    private fun extractWordsFromIterator(tess: TessBaseAPI): List<RecognizedWord> {
        val words = mutableListOf<RecognizedWord>()
        val iterator = tess.resultIterator ?: return emptyList()
        iterator.begin()
        do {
            val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
            if (text.isNotEmpty()) {
                val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val conf = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD).toDouble().coerceIn(0.0, 100.0)
                if (box != null && box.size >= 4) {
                    val left = box[0].toLong()
                    val top = box[1].toLong()
                    val right = box[2].toLong()
                    val bottom = box[3].toLong()
                    if (left >= 0 && top >= 0 && right > left && bottom > top) {
                        words += RecognizedWord(text, left, top, right, bottom, conf)
                    }
                }
            }
        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        return words
    }

    private fun parseHocrWords(hocr: String): List<RecognizedWord> {
        val words = mutableListOf<RecognizedWord>()
        WORD_SPAN.findAll(hocr).forEach { match ->
            val openTag = match.groupValues[1]
            val title = TITLE_ATTRIBUTE.find(openTag)?.groupValues?.get(1).orEmpty()
            val bounds = BBOX.find(title)?.groupValues?.drop(1)?.mapNotNull(String::toLongOrNull)
                ?: return@forEach
            if (bounds.size != 4) return@forEach
            val left = bounds[0]
            val top = bounds[1]
            val right = bounds[2]
            val bottom = bounds[3]
            if (left < 0 || top < 0 || right <= left || bottom <= top) return@forEach
            val word = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (word.isEmpty()) return@forEach
            val confidence = CONFIDENCE.find(title)?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
            words += RecognizedWord(word, left, top, right, bottom, confidence)
        }
        return words
    }

    private fun wordsToText(words: List<RecognizedWord>): String {
        val lines = mutableListOf<MutableList<RecognizedWord>>()
        words.forEach { word ->
            val current = lines.lastOrNull()
            val previous = current?.lastOrNull()
            val overlap = if (previous == null) 0L else
                (minOf(previous.bottomPx, word.bottomPx) - maxOf(previous.topPx, word.topPx)).coerceAtLeast(0L)
            val sameLineThreshold = if (previous == null) 0L else
                minOf(previous.bottomPx - previous.topPx, word.bottomPx - word.topPx) / 2
            if (current == null || overlap < sameLineThreshold) lines += mutableListOf(word) else current += word
        }
        return lines.joinToString("\n") { line -> line.joinToString(" ") { it.text } }
    }

    private inline fun <T> android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }

    private companion object {
        const val MAX_BITMAP_PIXELS = 12L * 1024L * 1024L
        val WORD_SPAN = Regex("""(?is)(<span\b(?=[^>]*\bclass=['"][^'"]*\bocrx_word\b)[^>]*>)(.*?)</span>""")
        val TITLE_ATTRIBUTE = Regex("""(?is)\btitle=['"]([^'"]*)['"]""")
        val BBOX = Regex("""\bbbox\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)""")
        val CONFIDENCE = Regex("""\bx_wconf\s+([\d.]+)""")
        const val ENG_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        const val RUS_SHA256 = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"
    }
}
