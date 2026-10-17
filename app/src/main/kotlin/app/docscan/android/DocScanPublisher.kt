package app.docscan.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Publishes exported PDF and JPG files directly to public /DocScan storage
 * via both direct filesystem and Android MediaStore Scoped Storage.
 */
object DocScanPublisher {

    fun getDocScanDisplayPath(): String = "/DocScan"

    fun publish(
        context: Context,
        pdfSource: File,
        pdfFileName: String,
        jpegSources: List<File>,
        jpegFileNames: List<String>,
        saveJpg: Boolean,
    ): Uri? {
        val cleanPdfName = if (pdfFileName.endsWith(".pdf", ignoreCase = true)) pdfFileName else "$pdfFileName.pdf"
        val baseName = cleanPdfName.removeSuffix(".pdf")
        var resultUri: Uri? = null

        // 1. Direct filesystem write to Documents/DocScan and /DocScan
        val targetDirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DocScan"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DocScan"),
            File(Environment.getExternalStorageDirectory(), "DocScan"),
        )

        for (dir in targetDirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                if (dir.isDirectory) {
                    val destPdf = File(dir, cleanPdfName)
                    pdfSource.copyTo(destPdf, overwrite = true)

                    val scannedPaths = mutableListOf(destPdf.absolutePath)
                    val scannedMimes = mutableListOf("application/pdf")

                    if (saveJpg) {
                        jpegSources.forEachIndexed { i, srcJpg ->
                            val name = jpegFileNames.getOrNull(i) ?: "${baseName}_%03d.jpg".format(i + 1)
                            val destJpg = File(dir, name)
                            srcJpg.copyTo(destJpg, overwrite = true)
                            scannedPaths.add(destJpg.absolutePath)
                            scannedMimes.add("image/jpeg")
                        }
                    }

                    MediaScannerConnection.scanFile(
                        context,
                        scannedPaths.toTypedArray(),
                        scannedMimes.toTypedArray(),
                        null,
                    )

                    try {
                        resultUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            destPdf,
                        )
                    } catch (_: Exception) {
                        resultUri = Uri.fromFile(destPdf)
                    }
                    break
                }
            } catch (_: Exception) {
                // Continue to next directory or MediaStore
            }
        }

        // 2. Guaranteed MediaStore publication for Android 10+ (API 29..36)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanPdfName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/DocScan")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val mediaUri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

                if (mediaUri != null) {
                    resolver.openOutputStream(mediaUri, "w")?.use { out ->
                        pdfSource.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(mediaUri, values, null, null)
                    resultUri = mediaUri
                }

                if (saveJpg) {
                    jpegSources.forEachIndexed { i, srcJpg ->
                        val name = jpegFileNames.getOrNull(i) ?: "${baseName}_%03d.jpg".format(i + 1)
                        val jpgValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/DocScan")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val jpgUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, jpgValues)
                        if (jpgUri != null) {
                            resolver.openOutputStream(jpgUri)?.use { out ->
                                srcJpg.inputStream().use { it.copyTo(out) }
                            }
                            jpgValues.clear()
                            jpgValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(jpgUri, jpgValues, null, null)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback already attempted in direct filesystem
            }
        }

        return resultUri
    }
}
