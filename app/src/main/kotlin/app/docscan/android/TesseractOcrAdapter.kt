package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
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
) : DocumentRecognizer {
    private val appContext = context.applicationContext
    private val dataRoot = File(appContext.filesDir, "docscan-tesseract/fast-8741641")
    private val tess = TessBaseAPI(
        progressObserverForTest?.let { observer -> TessBaseAPI.ProgressNotifier { observer() } },
    )
    private val cancellationRequested = AtomicBoolean(false)
    private val nativeRecognitionActive = AtomicBoolean(false)
    private val resultIteratorExtractor = ResultIteratorLayoutExtractor()
    @Volatile private var initialized = false
    private var unusable = false

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

                // 1. Try ResultIteratorLayoutExtractor directly from Tesseract native layout structures
                var layout: app.docscan.core.store.RecognitionLayout? = try {
                    resultIteratorExtractor.extract(tess, decoded.width, decoded.height)
                } catch (_: Throwable) {
                    null
                }

                // 2. If ResultIterator yielded no blocks, fall back to HocrLayoutExtractor
                if (layout == null || layout.blocks.isEmpty()) {
                    val hocr = try {
                        tess.getHOCRText(0).orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    if (hocr.isNotBlank()) {
                        layout = app.docscan.core.ocr.HocrParser.parse(
                            hocr = hocr,
                            fallbackWidthPx = decoded.width,
                            fallbackHeightPx = decoded.height,
                        )
                    }
                }

                // 3. Fail-safe text preservation: if layout still has no text, check getUTF8Text()
                val plainText = layout?.toPlainText().orEmpty()
                val finalLayout = if (plainText.isBlank()) {
                    val utf8 = try {
                        tess.getUTF8Text()?.trim().orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    if (utf8.isNotBlank()) {
                        createFallbackLayout(utf8, decoded.width, decoded.height)
                    } else {
                        layout ?: app.docscan.core.ocr.HocrParser.parse("", decoded.width, decoded.height)
                    }
                } else {
                    layout!!
                }

                OcrResult(
                    text = finalLayout.toPlainText(),
                    geometry = finalLayout.toLegacyGeometry(),
                    layout = finalLayout,
                )
            } finally {
                nativeRecognitionActive.set(false)
                tess.clear()
            }
        }
    }

    private fun createFallbackLayout(
        text: String,
        widthPx: Int,
        heightPx: Int,
    ): app.docscan.core.store.RecognitionLayout {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ocrLines = lines.map { lineStr ->
            val words = lineStr.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w ->
                val sanitized = app.docscan.core.store.sanitizeOcrText(w)
                app.docscan.core.store.OcrWord(
                    text = sanitized.ifEmpty { w },
                    box = app.docscan.core.store.NormalizedBox(0.01f, 0.01f, 0.99f, 0.99f),
                    confidence = 80f,
                )
            }
            app.docscan.core.store.OcrLine(
                box = app.docscan.core.store.NormalizedBox(0.01f, 0.01f, 0.99f, 0.99f),
                baseline = null,
                xHeight = null,
                words = words,
            )
        }
        val paragraph = app.docscan.core.store.OcrParagraph(
            box = app.docscan.core.store.NormalizedBox(0.01f, 0.01f, 0.99f, 0.99f),
            lines = ocrLines,
        )
        val block = app.docscan.core.store.OcrBlock(
            id = "fallback_block",
            type = app.docscan.core.store.BlockType.TEXT,
            box = app.docscan.core.store.NormalizedBox(0.01f, 0.01f, 0.99f, 0.99f),
            readingOrder = 0,
            paragraphs = listOf(paragraph),
        )
        return app.docscan.core.store.RecognitionLayout(
            sourceWidthPx = maxOf(1, widthPx),
            sourceHeightPx = maxOf(1, heightPx),
            blocks = listOf(block),
            quality = app.docscan.core.store.LayoutQuality(80f, 0, structureAvailable = false),
            formatVersion = 2,
        )
    }

    override fun cancelCurrentRecognition() {
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

    private inline fun <T> android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }

    private companion object {
        const val MAX_BITMAP_PIXELS = 12L * 1024L * 1024L
        const val ENG_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        const val RUS_SHA256 = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"
    }
}
