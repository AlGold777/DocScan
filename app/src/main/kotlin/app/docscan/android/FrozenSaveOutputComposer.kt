package app.docscan.android

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.store.FrozenSavePage
import app.docscan.core.store.RecognitionGeometry
import app.docscan.core.store.SaveManifestSnapshot
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

@ConsistentCopyVisibility
data class FrozenPageOutput internal constructor(
    val imageVersionId: String,
    val orderIndex: Long,
    val jpegFile: File,
    val jpegSha256: String,
    val widthPx: Int,
    val heightPx: Int,
)

@ConsistentCopyVisibility
data class FrozenSaveOutputSet internal constructor(
    val saveManifestId: String,
    val pdfFile: File,
    val pdfSha256: String,
    val pages: List<FrozenPageOutput>,
)

/** Creates a locally validated candidate set from one immutable SaveManifest. */
class FrozenSaveOutputComposer(
    private val sourceRepository: FileArtifactRepository,
    private val sourcePath: (String) -> File,
    private val pdfWriter: SearchablePdfWriter,
    private val resourceLoaderContext: android.content.Context,
    private val directorySync: DirectorySync,
) {
    fun compose(
        snapshot: SaveManifestSnapshot,
        outputRoot: File,
        pdfSettings: PdfOutputSettings,
        companionJpegQuality: Int,
        includeOcr: Boolean = true,
    ): FrozenSaveOutputSet {
        require(snapshot.pages.isNotEmpty()) { "SaveManifest has no frozen pages" }
        require(companionJpegQuality in 0..100)
        require(snapshot.pages.zipWithNext().all { (a, b) -> a.orderIndex < b.orderIndex }) {
            "Frozen pages are not in strictly increasing workspace order"
        }
        require(!outputRoot.exists() || outputRoot.isDirectory) { "Save output root is not a directory" }
        if (!outputRoot.exists()) check(outputRoot.mkdirs()) { "Save output root cannot be created" }

        val staging = File(outputRoot, ".${snapshot.saveManifestId.value}.${UUID.randomUUID()}.building")
        val finalDir = File(outputRoot, snapshot.saveManifestId.value)
        check(!finalDir.exists()) { "Output already exists for this SaveManifest" }
        check(staging.mkdir()) { "Save staging directory cannot be created" }
        try {
            val pageCandidates = mutableListOf<PageCandidate>()
            val pageOutputs = snapshot.pages.mapIndexed { index, page ->
                val source = sourcePath(page.artifactRelativePath)
                check(sourceRepository.verifyPublished(page.artifactRelativePath, page.artifactSizeBytes, page.artifactSha256)) {
                    "Frozen source artifact failed size/SHA-256 verification: ${page.artifactId.value}"
                }
                require(source.isFile) { "Frozen source artifact is missing" }
                val decoded = ProductImageDecoder.decode(source, MAX_DECODE_PIXELS)
                    ?: throw IllegalArgumentException("Frozen source image cannot be decoded")
                val sourceWidth = decoded.width
                val sourceHeight = decoded.height
                page.recognizedLayout?.let { layout ->
                    require(layout.sourceWidthPx == sourceWidth
                        && layout.sourceHeightPx == sourceHeight) {
                        "Frozen OCR layout does not match its source ImageVersion"
                    }
                }
                page.recognizedGeometry?.let { geometry ->
                    require(geometry.sourceWidthPx == sourceWidth.toLong()
                        && geometry.sourceHeightPx == sourceHeight.toLong()) {
                        "Frozen OCR geometry does not match its source ImageVersion"
                    }
                }
                val scale = minOf(1f, MAX_OUTPUT_SIDE_PX.toFloat() / maxOf(sourceWidth, sourceHeight))
                val outputWidth = maxOf(1, (sourceWidth * scale).toInt())
                val outputHeight = maxOf(1, (sourceHeight * scale).toInt())
                val bitmap = if (outputWidth == sourceWidth && outputHeight == sourceHeight) decoded
                    else Bitmap.createScaledBitmap(decoded, outputWidth, outputHeight, true).also { decoded.recycle() }
                try {
                    val jpeg = File(staging, "page-${index + 1}.jpg")
                    FileOutputStream(jpeg).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, companionJpegQuality, stream)) {
                            "Companion JPG encoding failed"
                        }
                        stream.fd.sync()
                    }
                    val checkBitmap = BitmapFactory.decodeFile(jpeg.absolutePath)
                        ?: throw IllegalStateException("Companion JPG cannot be read back")
                    try {
                        check(checkBitmap.width == bitmap.width && checkBitmap.height == bitmap.height
                            && maxOf(checkBitmap.width, checkBitmap.height) <= MAX_OUTPUT_SIDE_PX
                        ) {
                            "Companion JPG dimensions differ from its bounded output image"
                        }
                    } finally {
                        checkBitmap.recycle()
                    }
                    val effectiveGeometry = if (includeOcr) page.recognizedGeometry else null
                    val effectiveLayout = if (includeOcr) page.recognizedLayout else null
                    pageCandidates += PageCandidate(
                        jpeg = jpeg,
                        recognition = scaleGeometry(effectiveGeometry, sourceWidth, sourceHeight, bitmap.width, bitmap.height),
                        layout = scaleLayout(effectiveLayout, sourceWidth, sourceHeight, bitmap.width, bitmap.height),
                    )
                    FrozenPageOutput(
                        imageVersionId = page.imageVersionId.value,
                        orderIndex = page.orderIndex,
                        jpegFile = File(finalDir, jpeg.name),
                        jpegSha256 = sha256(jpeg),
                        widthPx = bitmap.width,
                        heightPx = bitmap.height,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
            val inputs = pageCandidates.map { candidate ->
                PdfInputPage(
                    openBitmap = {
                        ProductImageDecoder.decode(candidate.jpeg, MAX_DECODE_PIXELS)
                            ?: throw IllegalArgumentException("Bounded companion JPG cannot be decoded")
                    },
                    recognition = candidate.recognition,
                    layout = candidate.layout,
                )
            }
            val pdfTemp = File(staging, "document.pdf")
            pdfWriter.write(pdfTemp, inputs, pdfSettings)
            validatePdf(pdfTemp, snapshot.pages, pageOutputs, includeOcr)
            snapshot.pages.forEach { page ->
                check(sourceRepository.verifyPublished(page.artifactRelativePath, page.artifactSizeBytes, page.artifactSha256)) {
                    "Frozen source artifact changed while composing the output set: ${page.artifactId.value}"
                }
            }
            val pdfHash = sha256(pdfTemp)
            directorySync.sync(staging.toPath())
            Files.move(staging.toPath(), finalDir.toPath(), ATOMIC_MOVE)
            directorySync.sync(outputRoot.toPath())
            return FrozenSaveOutputSet(
                saveManifestId = snapshot.saveManifestId.value,
                pdfFile = File(finalDir, pdfTemp.name),
                pdfSha256 = pdfHash,
                pages = pageOutputs,
            )
        } catch (failure: Throwable) {
            val incomplete = if (finalDir.exists()) finalDir else staging
            try {
                deleteTree(incomplete)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            try {
                directorySync.sync(outputRoot.toPath())
            } catch (syncFailure: Throwable) {
                failure.addSuppressed(syncFailure)
            }
            throw failure
        }
    }

    private fun validatePdf(
        pdfFile: File,
        pages: List<FrozenSavePage>,
        pageOutputs: List<FrozenPageOutput>,
        includeOcr: Boolean,
    ) {
        PDFBoxResourceLoader.init(resourceLoaderContext.applicationContext)
        PDDocument.load(pdfFile, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            check(document.numberOfPages == pages.size) { "PDF page count differs from frozen SaveManifest" }
            if (includeOcr) {
                val expectedWords = pages.flatMap { page ->
                    val layout = page.recognizedLayout
                    val geometry = page.recognizedGeometry
                    when {
                        layout != null -> layout.blocks.flatMap { it.lines.flatMap { line -> line.words.map { w -> w.text } } }
                        geometry != null -> geometry.words.map { it.text }
                        else -> emptyList()
                    }
                }.filter { it.isNotBlank() }
                if (expectedWords.isNotEmpty()) {
                    val extracted = PDFTextStripper().getText(document).normalizeWhitespace()
                    expectedWords.forEach { word ->
                        check(extracted.contains(word.normalizeWhitespace())) {
                            "PDF read-back is missing frozen searchable OCR text"
                        }
                    }
                }
            }
            val renderer = PDFRenderer(document)
            pages.indices.forEach { index ->
                val rendered = renderer.renderImage(index, 0.25f)
                try {
                    check(rendered.width > 0 && rendered.height > 0) {
                        "PDF page $index did not render"
                    }
                    val page = document.getPage(index)
                    val sourceImages = page.resources.xObjectNames.mapNotNull { name ->
                        page.resources.getXObject(name) as? PDImageXObject
                    }
                    check(sourceImages.size == 1
                        && sourceImages.single().width == pageOutputs[index].widthPx
                        && sourceImages.single().height == pageOutputs[index].heightPx) {
                        "PDF page $index does not contain exactly one full-resolution frozen source image"
                    }
                } finally {
                    rendered.recycle()
                }
            }
        }
        check(pdfFile.isFile && pdfFile.length() > 0L) { "PDF is empty after read-back" }
    }

    private fun String.normalizeWhitespace(): String = trim().replace(Regex("\\s+"), " ")

    private fun scaleGeometry(
        geometry: RecognitionGeometry?,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): RecognitionGeometry? {
        if (geometry == null) return null
        val scaleX = outputWidth.toDouble() / sourceWidth
        val scaleY = outputHeight.toDouble() / sourceHeight
        val words = geometry.words.map { word ->
            val left = (word.leftPx * scaleX).toLong().coerceIn(0L, outputWidth.toLong() - 1L)
            val top = (word.topPx * scaleY).toLong().coerceIn(0L, outputHeight.toLong() - 1L)
            val right = kotlin.math.ceil(word.rightPx * scaleX).toLong().coerceIn(left + 1L, outputWidth.toLong())
            val bottom = kotlin.math.ceil(word.bottomPx * scaleY).toLong().coerceIn(top + 1L, outputHeight.toLong())
            word.copy(leftPx = left, topPx = top, rightPx = right, bottomPx = bottom)
        }
        return geometry.copy(sourceWidthPx = outputWidth.toLong(), sourceHeightPx = outputHeight.toLong(), words = words)
    }

    private fun scaleLayout(
        layout: app.docscan.core.store.RecognitionLayout?,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): app.docscan.core.store.RecognitionLayout? {
        if (layout == null) return null
        return layout.copy(
            sourceWidthPx = outputWidth,
            sourceHeightPx = outputHeight,
        )
    }

    private data class PageCandidate(
        val jpeg: File,
        val recognition: RecognitionGeometry?,
        val layout: app.docscan.core.store.RecognitionLayout? = null,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteTree)
        check(file.delete()) { "Cannot remove incomplete save output: ${file.name}" }
    }

    private companion object {
        const val MAX_DECODE_PIXELS = 12L * 1024L * 1024L
        const val MAX_OUTPUT_SIDE_PX = 3000
    }
}
