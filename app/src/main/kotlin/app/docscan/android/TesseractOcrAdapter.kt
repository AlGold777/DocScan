package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import app.docscan.core.ocr.LangDetect
import app.docscan.core.ocr.OcrLangMode
import app.docscan.core.ocr.OcrLangProfile
import app.docscan.core.ocr.OcrQualitySelector
import app.docscan.core.ocr.toOcrLayout
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
    private val modelDataRootOverride: File? = null,
) : DocumentRecognizer {
    // Preserve the existing trailing-lambda constructor used by capture/import clients.
    constructor(context: Context, progressObserverForTest: () -> Unit) :
        this(context, progressObserverForTest, null)

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("docscan_app_preferences", Context.MODE_PRIVATE) }
    private val dataRoot = modelDataRootOverride ?: File(appContext.filesDir, "docscan-tesseract/fast-8741641")
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

    private fun workingPixelLimit(): Long = minOf(MAX_BITMAP_PIXELS,
        (Runtime.getRuntime().maxMemory() / 48L).coerceAtLeast(1024L * 1024L))

    fun estimateDecodedWorkingSetBytes(file: File): Long {
        require(file.isFile) { "OCR source is missing" }
        // Bitmap, Leptonica/native work buffers, optional photometric copy and model reserve.
        return NATIVE_RESERVE_BYTES + workingPixelLimit() * BYTES_PER_WORKING_PIXEL
    }

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
        settings: OcrRunSettings = OcrRunSettings(),
    ): app.docscan.core.ocr.OcrLayout {
        throwIfCancelled(isCancelled)
        return synchronized(initLock) {
            ensureInitialized()
            ensureLang(tess, dataRoot.absolutePath, profile.tess)
            applyConfig(tess, settings)
            try {
                tess.setImage(bitmap)
                throwIfCancelled(isCancelled)
                nativeRecognitionActive.set(true)
                throwIfCancelled(isCancelled)
                // This is the wrapper's interruptible recognition entry point. Do not replace
                // it with a standalone getUTF8Text(), which does not honor stop().
                val nativeHocr = tess.getHOCRText(0)
                throwIfCancelled(isCancelled)
                val hocr = nativeHocr ?: throw IllegalStateException("OCR engine returned no result")
                var extraction = "HOCR"
                var layout = try {
                    app.docscan.core.ocr.parseHocr(hocr, bitmap.width, bitmap.height)
                } catch (failure: RuntimeException) {
                    android.util.Log.w("OCR", "hOCR structure unavailable; reading native results", failure)
                    app.docscan.core.ocr.OcrLayout(bitmap.width, bitmap.height, emptyList())
                }
                // Compare against the engine's cached complete transcript. A nonempty but
                // partial DOM extraction is just as unsafe as an empty extraction.
                val nativeText = tess.getUTF8Text().orEmpty().lines().joinToString("\n") {
                    app.docscan.core.ocr.sanitizeOcrText(it.replace('\t', ' '))
                }.trim()
                throwIfCancelled(isCancelled)
                val hocrLayout = layout
                if (layout.words.isEmpty() || (nativeText.isNotBlank() &&
                        normalizedOcrContent(layout.toPlainText()) != normalizedOcrContent(nativeText))) {
                    val nativeLayout = resultIteratorExtractor.extract(tess, bitmap.width, bitmap.height,
                        profile.tess.split('+').toSet())
                    throwIfCancelled(isCancelled)
                    if (nativeLayout != null) {
                        val sourceLines = hocrLayout.blocks.flatMap { it.paras }.flatMap { it.lines }
                        val extracted = nativeLayout.toOcrLayout()
                        layout = extracted.copy(blocks = extracted.blocks.map { block -> block.copy(
                            paras = block.paras.map { paragraph -> paragraph.copy(lines = paragraph.lines.map { line ->
                                val original = sourceLines.firstOrNull { other ->
                                    kotlin.math.abs(other.box.l - line.box.l) <= 2 && kotlin.math.abs(other.box.t - line.box.t) <= 2 &&
                                        kotlin.math.abs(other.box.r - line.box.r) <= 2 && kotlin.math.abs(other.box.b - line.box.b) <= 2
                                }
                                line.copy(baseline = original?.baseline)
                            }) },
                        ) })
                        extraction = "RESULT_ITERATOR"
                    }
                    if (nativeText.isNotBlank() &&
                        normalizedOcrContent(layout.toPlainText()) != normalizedOcrContent(nativeText)) {
                        layout = layout.copy(plainText = nativeText, quality = layout.toRecognitionLayout().quality?.copy(
                            structureAvailable = false,
                        ))
                        extraction = "PLAIN_TEXT"
                    } else if (layout.words.isEmpty()) {
                        layout = layout.copy(plainText = nativeText)
                        extraction = if (nativeText.isBlank()) "EMPTY" else "PLAIN_TEXT"
                    }
                }
                val normalized = layout.copy(lang = profile.tess,
                    languageHints = profile.tess.split('+').toSet()).toRecognitionLayout()
                normalized.toOcrLayout().copy(quality = normalized.quality?.copy(
                    language = profile.tess,
                    preprocessingProfile = "${settings.imageProfile.name}:PSM${settings.pageSegMode}:$extraction",
                    engineVersion = tess.version,
                ))
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
        settings: OcrRunSettings = OcrRunSettings(),
    ): app.docscan.core.ocr.OcrLayout {
        val first = mode.profile ?: OcrLangProfile.RU_EN
        val r1 = runOcr(bitmap, first, isCancelled, settings)
        if (mode != OcrLangMode.AUTO) return r1
        val target = LangDetect.decide(r1.toPlainText()) ?: return r1
        if (target == first) return r1
        throwIfCancelled(isCancelled)
        return try {
            val r2 = runOcr(bitmap, target, isCancelled, settings)
            throwIfCancelled(isCancelled)
            OcrQualitySelector.choose(r1, r2)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.w("OCR", "second pass failed", e)
            r1
        }
    }

    fun recognizeDetailed(
        file: File,
        isCancelled: () -> Boolean = { false },
        mode: OcrLangMode = readLangMode(prefs),
        settings: OcrRunSettings = OcrRunSettings(autoLighting = true),
    ): OcrResult {
        cancellationRequested.set(false)
        throwIfCancelled(isCancelled)
        try {
            val bitmap = ProductImageDecoder.decodePrecisely(file, workingPixelLimit())
            return bitmap.useBitmap { decoded ->
                throwIfCancelled(isCancelled)
                val resolvedSettings = if (settings.autoLighting && settings.imageProfile == OcrImageProfile.ORIGINAL)
                    settings.copy(imageProfile = OcrImagePreparation.suggestProfile(decoded), autoLighting = false)
                else settings
                val prepared = OcrImagePreparation.prepare(decoded, resolvedSettings.imageProfile) {
                    cancellationRequested.get() || isCancelled()
                }
                try {
                    val ocrLayout = recognize(prepared, mode, isCancelled, resolvedSettings)
                    throwIfCancelled(isCancelled)
                    val finalLayout = ocrLayout.toRecognitionLayout()
                    OcrResult(text = finalLayout.toPlainText(), geometry = finalLayout.toLegacyGeometry(), layout = finalLayout)
                } finally { if (prepared !== decoded) prepared.recycle() }
            }
        } catch (memory: OutOfMemoryError) {
            throw IllegalStateException(appContext.getString(R.string.ocr_memory_error), memory)
        }
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

    private fun normalizedOcrContent(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).filterNot(Char::isWhitespace)

    private fun applyConfig(tess: TessBaseAPI, settings: OcrRunSettings = OcrRunSettings()) {
        tess.pageSegMode = settings.pageSegMode
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
        } catch (e: Exception) {
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
            if (modelDataRootOverride == null) {
                installModel("eng.traineddata", ENG_SHA256)
                installModel("rus.traineddata", RUS_SHA256)
                installModel("spa.traineddata", SPA_SHA256)
            } else check(File(dataRoot, "tessdata").isDirectory) { "OCR model directory is missing" }
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
        const val MAX_BITMAP_PIXELS = 6L * 1024L * 1024L
        const val NATIVE_RESERVE_BYTES = 32L * 1024L * 1024L
        const val BYTES_PER_WORKING_PIXEL = 10L
        const val ENG_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        const val RUS_SHA256 = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"
        const val SPA_SHA256 = "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"
    }
}
