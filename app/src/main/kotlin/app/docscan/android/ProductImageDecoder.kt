package app.docscan.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import kotlin.math.sqrt
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Decodes bounded pixels in the requested format and applies EXIF orientation in memory. Source artifacts stay immutable. */
object ProductImageDecoder {
    /** Decode directly to the requested envelope; EXIF is applied by ImageDecoder.
     * Unlike power-of-two BitmapFactory sampling, a 12MP photo stays 12MP when allowed.
     */
    fun decodePrecisely(file: File, maxPixels: Long, maxSide: Int = Int.MAX_VALUE): Bitmap {
        require(maxPixels > 0 && maxSide > 0)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val ratio = minOf(1.0, sqrt(maxPixels.toDouble() / (width.toLong() * height)),
                maxSide.toDouble() / maxOf(width, height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.isMutableRequired = true
            decoder.setTargetSize(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
        }
    }

    fun estimateWorkingSetBytes(file: File, maxPixels: Long): Long {
        val bounds = bounds(file)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        val pixels = (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample)
        // Reserve for the decoded source, oriented copy, and a downstream RGBA working image.
        return (pixels * 8L).coerceAtLeast(1L)
    }

    fun decode(
        file: File,
        maxPixels: Long,
        preferredConfig: Bitmap.Config = Bitmap.Config.RGB_565,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE,
    ): Bitmap? {
        require(maxPixels > 0L && maxWidth > 0 && maxHeight > 0)
        val imageBounds = bounds(file)
        var sample = sampleSize(imageBounds.outWidth, imageBounds.outHeight, maxPixels)
        while (imageBounds.outWidth / sample > maxWidth || imageBounds.outHeight / sample > maxHeight) {
            sample *= 2
        }
        val source = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = preferredConfig
        }) ?: return null

        return try {
            val exif = ExifInterface(file)
            val rotation = exif.rotationDegrees
            val flipped = exif.isFlipped
            if (rotation == 0 && !flipped) {
                source
            } else {
                val transform = Matrix().apply {
                    if (flipped) setScale(-1f, 1f)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
                    .also { oriented -> if (oriented !== source) source.recycle() }
            }
        } catch (failure: Exception) {
            source.recycle()
            throw failure
        }
    }

    private fun bounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Image cannot be decoded" }
        return options
    }

    private fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while (width.toLong() / sample * (height.toLong() / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }
}
