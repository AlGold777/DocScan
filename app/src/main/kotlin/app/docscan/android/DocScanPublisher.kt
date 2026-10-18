package app.docscan.android

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.docscan.core.export.OutputNameResolution
import app.docscan.core.export.OutputNameResolver
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class PublicationResult(
    val pdfUri: Uri,
    val pdfDisplayName: String,
    val displayPath: String,
    /** The PDF followed by its page JPGs in page order. */
    val outputUris: List<Uri>,
) {
    val jpegUris: List<Uri> get() = outputUris.drop(1)
}

/** Publishes a verified PDF/JPG set to public Downloads without replacing existing files. */
object DocScanPublisher {
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/DocScan/"

    fun getDocScanDisplayPath(): String = relativePath.removeSuffix("/")

    /**
     * Returns only after every member has passed read-back verification and left IS_PENDING.
     * On failure, removes all rows created by this call; cleanup failures are suppressed on the
     * original exception. MediaStore cannot atomically make several rows visible, and process
     * death can leave pending rows for Android to expire.
     *
     * jpegFileNames is retained for source compatibility; OutputNameResolver names the entire set.
     */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun publish(
        context: Context,
        pdfSource: File,
        pdfFileName: String,
        jpegSources: List<File>,
        jpegFileNames: List<String>,
        saveJpg: Boolean,
    ): PublicationResult {
        val resolver = context.contentResolver
        val sources = listOf(pdfSource) + if (saveJpg) jpegSources else emptyList()
        require(!saveJpg || jpegSources.isNotEmpty()) { "At least one JPG page is required" }
        val expected = sources.map { source ->
            require(source.isFile) { "Publication source is unavailable: ${source.name}" }
            source.inputStream().use(::fingerprint).also {
                require(it.size > 0) { "Publication source is empty: ${source.name}" }
            }
        }
        val existingNames = queryExistingNames(resolver)
        val resolution = if (saveJpg) {
            OutputNameResolver.resolve(pdfFileName, jpegSources.size, existingNames)
        } else {
            OutputNameResolver.resolveSinglePdfWithSuffix(pdfFileName, existingNames)
        }
        val names = when (resolution) {
            is OutputNameResolution.Resolved -> resolution.names
            is OutputNameResolution.Invalid -> throw IllegalArgumentException(resolution.reason)
            is OutputNameResolution.Collision -> error("Output names conflict: ${resolution.conflictingNames}")
        }
        val displayNames = listOf(names.pdfName) + if (saveJpg) names.pageJpegNames else emptyList()
        val created = mutableListOf<Uri>()
        try {
            // Reserve every name before writing. Unexpected provider suffixes are conflicts,
            // including files belonging to other apps that scoped-storage queries cannot see.
            displayNames.forEachIndexed { index, name ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (index == 0) "application/pdf" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore did not create $name")
                created.add(uri)
                verifyLocation(resolver, uri, name)
            }
            created.forEachIndexed { index, uri ->
                val output = resolver.openOutputStream(uri, "w")
                    ?: error("MediaStore returned no output stream for ${displayNames[index]}")
                output.use { destination ->
                    sources[index].inputStream().use { it.copyTo(destination) }
                }
                val actual = resolver.openInputStream(uri)?.use(::fingerprint)
                    ?: error("MediaStore returned no read-back stream for ${displayNames[index]}")
                check(actual == expected[index]) { "Publication verification failed for ${displayNames[index]}" }
                verifyLocation(resolver, uri, displayNames[index])
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            created.forEachIndexed { index, uri ->
                check(resolver.update(uri, ready, null, null) == 1) {
                    "MediaStore did not finalize ${displayNames[index]}"
                }
                verifyLocation(resolver, uri, displayNames[index])
            }
            return PublicationResult(created.first(), names.pdfName, getDocScanDisplayPath(), created.toList())
        } catch (failure: Throwable) {
            created.asReversed().forEach { uri ->
                try {
                    check(resolver.delete(uri, null, null) == 1) { "MediaStore did not remove failed publication $uri" }
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    private fun queryExistingNames(resolver: ContentResolver): List<String> {
        val column = MediaStore.MediaColumns.DISPLAY_NAME
        val cursor = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(column),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        ) ?: error("MediaStore returned no Downloads listing")
        return cursor.use {
            val nameIndex = it.getColumnIndexOrThrow(column)
            buildList {
                while (it.moveToNext()) add(checkNotNull(it.getString(nameIndex)) { "MediaStore returned an unnamed file" })
            }
        }
    }

    private fun verifyLocation(resolver: ContentResolver, uri: Uri, expectedName: String) {
        val cursor = resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null,
        ) ?: error("MediaStore returned no metadata for $uri")
        cursor.use {
            check(it.moveToFirst()) { "MediaStore publication disappeared: $uri" }
            val actualName = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            val actualPath = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
            check(actualName == expectedName && actualPath?.trimEnd('/') == relativePath.trimEnd('/')) {
                "MediaStore changed the output name or folder: $actualPath$actualName"
            }
        }
    }

    private data class Fingerprint(val size: Long, val sha256: String)

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            size += count
        }
        return Fingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
