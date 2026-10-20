package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Coordinate space boundaries:
 * 1. AnalysisBufferSpace (width, height, cropRect, rotationDegrees)
 * 2. NormalizedViewPortSpace (normalized [0, 1] x [0, 1] relative to the oriented ViewPort)
 * 3. OrientedJpegSpace (width, height of JPEG after EXIF orientation)
 */
data class BufferRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left) { "BufferRect width must be positive: left=$left right=$right" }
        require(bottom > top) { "BufferRect height must be positive: top=$top bottom=$bottom" }
    }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class AnalysisBufferSpec(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropRect: BufferRect,
    val rotationDegrees: Int,
) {
    init {
        require(bufferWidth > 0 && bufferHeight > 0) { "Buffer dimensions must be positive" }
        require(cropRect.left >= 0 && cropRect.top >= 0) { "Crop rect origin must be non-negative" }
        require(cropRect.right <= bufferWidth && cropRect.bottom <= bufferHeight) {
            "Crop rect ($cropRect) exceeds buffer ($bufferWidth x $bufferHeight)"
        }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Unsupported rotationDegrees: $rotationDegrees" }
    }

    val orientedViewPortWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) cropRect.height else cropRect.width

    val orientedViewPortHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) cropRect.width else cropRect.height

    val orientedAspectRatio: Double
        get() = orientedViewPortWidth.toDouble() / orientedViewPortHeight.toDouble()
}

data class JpegGeometrySpec(
    val orientedWidth: Int,
    val orientedHeight: Int,
) {
    init {
        require(orientedWidth > 0 && orientedHeight > 0) { "JPEG dimensions must be positive" }
    }
    val aspectRatio: Double
        get() = orientedWidth.toDouble() / orientedHeight.toDouble()
}

object CameraGeometryTransform {

    fun analysisBufferPointToNormalizedViewPort(
        px: Double,
        py: Double,
        spec: AnalysisBufferSpec,
    ): NormalizedCropPoint {
        val u = (px - spec.cropRect.left) / spec.cropRect.width.toDouble()
        val v = (py - spec.cropRect.top) / spec.cropRect.height.toDouble()

        val (xNorm, yNorm) = when (spec.rotationDegrees) {
            0 -> u to v
            90 -> (1.0 - v) to u
            180 -> (1.0 - u) to (1.0 - v)
            270 -> v to (1.0 - u)
            else -> error("Invalid rotation: ${spec.rotationDegrees}")
        }
        return NormalizedCropPoint(xNorm, yNorm)
    }

    fun normalizedViewPortToAnalysisBufferPoint(
        p: NormalizedCropPoint,
        spec: AnalysisBufferSpec,
    ): Pair<Double, Double> {
        val (u, v) = when (spec.rotationDegrees) {
            0 -> p.x to p.y
            90 -> p.y to (1.0 - p.x)
            180 -> (1.0 - p.x) to (1.0 - p.y)
            270 -> (1.0 - p.y) to p.x
            else -> error("Invalid rotation: ${spec.rotationDegrees}")
        }
        val px = spec.cropRect.left + u * spec.cropRect.width.toDouble()
        val py = spec.cropRect.top + v * spec.cropRect.height.toDouble()
        return px to py
    }

    fun analysisBufferQuadToNormalizedViewPort(
        bufferPoints: FloatArray,
        spec: AnalysisBufferSpec,
    ): CropQuadrilateral {
        require(bufferPoints.size == 8) { "Buffer points must have exactly 8 elements" }
        // Sorting corners must not turn a crossed or degenerate input into an accepted polygon.
        BoundaryQuad.from(bufferPoints)
        val rawList = (0 until 4).map { i ->
            analysisBufferPointToNormalizedViewPort(
                bufferPoints[i * 2].toDouble(),
                bufferPoints[i * 2 + 1].toDouble(),
                spec,
            )
        }
        val canonical = QuadGeometryUtils.canonicalizeCorners(rawList)
        return CropQuadrilateral(
            topLeft = canonical[0],
            topRight = canonical[1],
            bottomRight = canonical[2],
            bottomLeft = canonical[3],
        )
    }

    fun isGeometryCompatible(
        analysisSpec: AnalysisBufferSpec,
        jpegSpec: JpegGeometrySpec,
        aspectTolerance: Double = 0.03,
    ): Boolean {
        val aRatio = analysisSpec.orientedAspectRatio
        val jRatio = jpegSpec.aspectRatio
        val relDiff = abs(aRatio - jRatio) / max(aRatio, jRatio)
        return relDiff <= aspectTolerance
    }

    fun normalizedViewPortToJpegPixel(
        p: NormalizedCropPoint,
        jpegSpec: JpegGeometrySpec,
    ): Pair<Double, Double> {
        return (p.x * jpegSpec.orientedWidth) to (p.y * jpegSpec.orientedHeight)
    }

    fun jpegPixelToNormalizedViewPort(
        x: Double,
        y: Double,
        jpegSpec: JpegGeometrySpec,
    ): NormalizedCropPoint {
        return NormalizedCropPoint(x / jpegSpec.orientedWidth, y / jpegSpec.orientedHeight)
    }
}
