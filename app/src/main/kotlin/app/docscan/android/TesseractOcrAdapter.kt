package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import app.docscan.core.ocr.LangDetect
import app.docscan.core.ocr.OcrLangMode
import app.docscan.core.ocr.OcrLangProfile
import app.docscan.core.ocr.toPlainText
import app.docscan.core.ocr.toRecognitionLayout
import app.docscan.core.store.OCR_WORD_COORDINATE_SPACE
import app.docscan.core.store.RecognitionGeometry
import app.docscan.core.store.RecognizedWord
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Offline Russian + English + Spanish recognizer. Use from the single admitted OCR executor only. */
class TesseractOcrAdapter(
    context: Context,
    private val progressObserverForTest: (() -> Unit)? = null,
) : DocumentRecognizer {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("docscan_app_preferences", Context.MODE_PRIVATE) }
    private val dataRoot = File(appContext.filesDir, "docscan-tesseract/fast-8741641")
    private var tess = createTessInstance()
    private val cancellationRequested = AtomicBoolean(false)
    private val nativeRecognitionActive = AtomicBoolean(false)
    private val resultIteratorExtractor = ResultIteratorLayoutExtractor()
    @Volatile private var initialized = false
    private var unusable = false
    private var currentLang: String? = null

    private fun createTessInstance(): TessBaseAPI = TessBaseAPI(
        progressObserverForTest?.let { observer -> TessBaseAPI.ProgressNotifier { observer() } }
            ?: TessBaseAPI.ProgressNotifier { /* no-op progress notifier to ensure stop() interrupts recognition */ },
    )

    data class OcrResult(
        val text: String,
        val geometry: RecognitionGeometry,
        val layout: app.docscan.core.store.RecognitionLayout,
    )

    fun estimateDecodedWorkingSetBytes(file: File): Long =
        ProductImageDecoder.estimateWorkingSetBytes(file, MAX_BITMAP_PIXELS)

    fun recognize(file: File): String {
        return recognizeDetailed(file).text
    }

    override fun recognize(file: File, options: RecognitionOptions): app.docscan.core.store.RecognitionLayout {
        return recognizeDetailed(file, options.isCancelled).layout
    }

    fun runOcr(
        bitmap: Bitmap,
        profile: OcrLangProfile,
        isCancelled: () -> Boolean = { false },
    ): app.docscan.core.ocr.OcrLayout {
        throwIfCancelled(isCancelled)
        return synchronized(initLock) {
            ensureInitialized()
            ensureLang(tess, dataRoot.absolutePath, profile.tess)
            tess.setImage(bitmap)
            try {
                throwIfCancelled(isCancelled)
                nativeRecognitionActive.set(true)
                throwIfCancelled(isCancelled)

                val hocr = try {
                    tess.getHOCRText(0).orEmpty()
                } catch (e: Throwable) {
                    android.util.Log.w("OCR", "getHOCRText failed for profile ${profile.tess}", e)
                    ""
                }

                var ocrLayout = app.docscan.core.ocr.parseHocr(hocr, bitmap.width, bitmap.height)
                if (ocrLayout.blocks.isEmpty() && hocr.isNotBlank()) {
                    android.util.Log.w("OCR", "hOCR was not empty but parseHocr produced no blocks")
                }

                if (ocrLayout.blocks.isEmpty()) {
                    val utf8 = try {
                        tess.getUTF8Text()?.trim().orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    if (utf8.isNotBlank()) {
                        ocrLayout = createFallbackOcrLayout(utf8, bitmap.width, bitmap.height)
                    }
                }

                ocrLayout.copy(lang = profile.tess)
            } finally {
                nativeRecognitionActive.set(false)
                tess.clear()
            }
        }
    }

    fun recognize(
        bitmap: Bitmap,
        mode: OcrLangMode = readLangMode(prefs),
        isCancelled: () -> Boolean = { false },
    ): app.docscan.core.ocr.OcrLayout {
        val first = mode.profile ?: OcrLangProfile.RU_EN
        val r1 = runOcr(bitmap, first, isCancelled)
        if (mode != OcrLangMode.AUTO) return r1
        val target = LangDetect.decide(r1.toPlainText()) ?: return r1
        if (target == first) return r1
        throwIfCancelled(isCancelled)
        return try {
            val r2 = runOcr(bitmap, target, isCancelled)
            if (r2.words.size >= r1.words.size * 0.5) r2 else r1
        } catch (e: Exception) {
            android.util.Log.w("OCR", "second pass failed", e)
            r1
        }
    }

    fun recognizeDetailed(
        file: File,
        isCancelled: () -> Boolean = { false },
        mode: OcrLangMode = readLangMode(prefs),
    ): OcrResult {
        cancellationRequested.set(false)
        throwIfCancelled(isCancelled)
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
            val ocrLayout = recognize(decoded, mode, isCancelled)
            val finalLayout = ocrLayout.toRecognitionLayout()

            OcrResult(
                text = finalLayout.toPlainText(),
                geometry = finalLayout.toLegacyGeometry(),
                layout = finalLayout,
            )
        }
    }

    private fun createFallbackOcrLayout(
        text: String,
        widthPx: Int,
        heightPx: Int,
    ): app.docscan.core.ocr.OcrLayout {
        val w = maxOf(1, widthPx)
        val h = maxOf(1, heightPx)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ocrLines = lines.map { lineStr ->
            val words = lineStr.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { wordText ->
                val sanitized = app.docscan.core.ocr.sanitizeOcrText(wordText)
                app.docscan.core.ocr.OcrWord(
                    text = sanitized.ifEmpty { wordText },
                    box = app.docscan.core.ocr.Bx(1, 1, w - 1, h - 1),
                    conf = 80f,
                )
            }
            app.docscan.core.ocr.OcrLine(
                box = app.docscan.core.ocr.Bx(1, 1, w - 1, h - 1),
                words = words,
            )
        }
        val paragraph = app.docscan.core.ocr.OcrPara(
            box = app.docscan.core.ocr.Bx(1, 1, w - 1, h - 1),
            lines = ocrLines,
        )
        val block = app.docscan.core.ocr.OcrBlock(
            box = app.docscan.core.ocr.Bx(1, 1, w - 1, h - 1),
            paras = listOf(paragraph),
            type = "text",
            id = "fallback_block",
        )
        return app.docscan.core.ocr.OcrLayout(
            sourceW = w,
            sourceH = h,
            blocks = listOf(block),
        )
    }

    override fun cancelCurrentRecognition() {
        cancellationRequested.set(true)
        if (nativeRecognitionActive.get() && initialized) {
            tess.stop()
        }
    }

    override fun close() {
        synchronized(initLock) {
            if (initialized) {
                tess.recycle()
                initialized = false
                currentLang = null
            }
        }
    }

    private fun applyConfig(tess: TessBaseAPI) {
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        tess.setVariable("user_defined_dpi", "300")
        tess.setVariable("preserve_interword_spaces", "1")
    }

    private fun ensureLang(tessApi: TessBaseAPI, dataPath: String, lang: String) {
        if (currentLang == lang) return
        currentLang = null
        val codes = lang.split("+")
        for (code in codes) {
            val file = File(dataPath, "tessdata/$code.traineddata")
            check(file.isFile && file.length() > 0) { "Missing model file: ${file.absolutePath}" }
        }
        val success = try {
            tessApi.init(dataPath, lang)
        } catch (e: Throwable) {
            android.util.Log.w("OCR", "tess.init failed for $lang, recreating instance", e)
            false
        }
        if (!success) {
            try { tess.recycle() } catch (_: Throwable) {}
            tess = createTessInstance()
            check(tess.init(dataPath, lang)) { "Tesseract init failed for $lang" }
        }
        applyConfig(tess)
        currentLang = lang
    }

    private fun ensureInitialized() {
        synchronized(initLock) {
            if (initialized) return
            check(!unusable) { "Offline Russian/English/Spanish OCR is unavailable after model initialization failed" }
            installModel("eng.traineddata", ENG_SHA256)
            installModel("rus.traineddata", RUS_SHA256)
            installModel("spa.traineddata", SPA_SHA256)
            initialized = true
        }
    }

    private fun installModel(name: String, expectedSha256: String) {
        val tessdata = File(dataRoot, "tessdata")
        check(tessdata.isDirectory || tessdata.mkdirs()) { "OCR model directory cannot be created" }
        val target = File(tessdata, name)
        if (target.isFile && sha256(target) == expectedSha256) return

        val temporary = File(tessdata, "$name.installing.${Thread.currentThread().id}")
        if (temporary.exists()) temporary.delete()
        try {
            appContext.assets.open("tesseract/tessdata/$name").use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(sha256(temporary) == expectedSha256) { "Bundled OCR model checksum mismatch: $name" }
            check(!target.exists() || target.delete()) { "Outdated OCR model cannot be replaced: $name" }
            check(temporary.renameTo(target)) { "OCR model could not be installed: $name" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
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

    private inline fun <T> android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }

    private companion object {
        val initLock = Any()
        const val MAX_BITMAP_PIXELS = 12L * 1024L * 1024L
        const val ENG_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        const val RUS_SHA256 = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"
        const val SPA_SHA256 = "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"
    }
}
