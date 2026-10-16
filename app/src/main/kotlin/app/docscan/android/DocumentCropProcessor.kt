package app.docscan.android

import android.graphics.Bitmap
import app.docscan.core.edit.CropQuadrilateral
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Finds a strong four-edge page candidate and returns a rectified preview; it never edits input. */
object DocumentCropProcessor {
    const val MAX_SOURCE_PIXELS = 4L * 1024L * 1024L

    fun rectifyManual(source: Bitmap, corners: CropQuadrilateral): Bitmap {
        require(!source.isRecycled)
        require(source.width.toLong() * source.height in 1..MAX_SOURCE_PIXELS)
        ensureOpenCvLoaded()
        val rgba = Mat()
        var warped: Mat? = null
        try {
            Utils.bitmapToMat(source, rgba)
            val sourcePoints = corners.asList().map { point ->
                Point(point.x * (rgba.cols() - 1), point.y * (rgba.rows() - 1))
            }
            val width = max(
                distance(sourcePoints[0], sourcePoints[1]),
                distance(sourcePoints[3], sourcePoints[2]),
            ).roundToInt().coerceAtLeast(2)
            val height = max(
                distance(sourcePoints[0], sourcePoints[3]),
                distance(sourcePoints[1], sourcePoints[2]),
            ).roundToInt().coerceAtLeast(2)
            require(width.toLong() * height <= MAX_SOURCE_PIXELS) {
                "Manual crop output exceeds the bounded pixel area"
            }
            val from = MatOfPoint2f(*sourcePoints.toTypedArray())
            val to = MatOfPoint2f(
                Point(0.0, 0.0), Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0),
            )
            val transform = Imgproc.getPerspectiveTransform(from, to)
            val output = Mat()
            warped = output
            try {
                Imgproc.warpPerspective(
                    rgba,
                    output,
                    transform,
                    Size(width.toDouble(), height.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                )
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { result ->
                    try {
                        Utils.matToBitmap(output, result)
                    } catch (failure: Throwable) {
                        result.recycle()
                        throw failure
                    }
                }
            } finally {
                from.release()
                to.release()
                transform.release()
            }
        } finally {
            rgba.release()
            warped?.release()
        }
    }

    fun rectify(source: Bitmap): Bitmap? {
        ensureOpenCvLoaded()
        val rgba = Mat()
        val reduced = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        var warped: Mat? = null
        try {
            Utils.bitmapToMat(source, rgba)
            val scale = min(1.0, DETECTION_LONG_SIDE.toDouble() / max(rgba.cols(), rgba.rows()))
            Imgproc.resize(rgba, reduced, Size(rgba.cols() * scale, rgba.rows() * scale))
            Imgproc.cvtColor(reduced, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = reduced.cols().toDouble() * reduced.rows()
            val candidate = contours.asSequence()
                .mapNotNull { contour ->
                    val area = Imgproc.contourArea(contour)
                    val ratio = area / frameArea
                    if (ratio !in MIN_PAGE_AREA_RATIO..MAX_PAGE_AREA_RATIO) return@mapNotNull null
                    val curve = MatOfPoint2f(*contour.toArray())
                    try {
                        val approximation = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve, approximation, POLYGON_EPSILON * Imgproc.arcLength(curve, true), true)
                            val points = approximation.toArray()
                            if (points.size != 4) return@mapNotNull null
                            val polygon = MatOfPoint(*points)
                            try {
                                if (!Imgproc.isContourConvex(polygon)) return@mapNotNull null
                            } finally {
                                polygon.release()
                            }
                            orderCorners(points)?.let { ratio to it }
                        } finally {
                            approximation.release()
                        }
                    } finally {
                        curve.release()
                    }
                }
                .maxByOrNull { it.first }
                ?.second ?: return null

            val xScale = rgba.cols().toDouble() / reduced.cols()
            val yScale = rgba.rows().toDouble() / reduced.rows()
            val sourcePoints = candidate.map { Point(it.x * xScale, it.y * yScale) }
            val width = max(distance(sourcePoints[0], sourcePoints[1]), distance(sourcePoints[3], sourcePoints[2]))
                .roundToInt().coerceAtLeast(MIN_OUTPUT_SIDE)
            val height = max(distance(sourcePoints[0], sourcePoints[3]), distance(sourcePoints[1], sourcePoints[2]))
                .roundToInt().coerceAtLeast(MIN_OUTPUT_SIDE)
            if (width.toLong() * height > MAX_SOURCE_PIXELS) return null

            val from = MatOfPoint2f(*sourcePoints.toTypedArray())
            val to = MatOfPoint2f(
                Point(0.0, 0.0), Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0),
            )
            val transform = Imgproc.getPerspectiveTransform(from, to)
            try {
                val output = Mat()
                warped = output
                Imgproc.warpPerspective(
                    rgba,
                    output,
                    transform,
                    Size(width.toDouble(), height.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                )
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { result ->
                    try {
                        Utils.matToBitmap(output, result)
                    } catch (failure: Throwable) {
                        result.recycle()
                        throw failure
                    }
                }
            } finally {
                from.release()
                to.release()
                transform.release()
            }
        } finally {
            rgba.release()
            reduced.release()
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            contours.forEach(Mat::release)
            warped?.release()
        }
    }

    private fun ensureOpenCvLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                // The resolved OpenCV 4.14.0 Android AAR packages libopencv_java4.so.
                System.loadLibrary("opencv_java4")
                loaded = true
            }
        }
    }

    private fun orderCorners(points: Array<Point>): List<Point>? {
        if (points.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size != 4) return null
        val topLeft = points.minByOrNull { it.x + it.y } ?: return null
        val bottomRight = points.maxByOrNull { it.x + it.y } ?: return null
        val topRight = points.minByOrNull { it.y - it.x } ?: return null
        val bottomLeft = points.maxByOrNull { it.y - it.x } ?: return null
        if (listOf(topLeft, topRight, bottomRight, bottomLeft).distinct().size != 4) return null
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(first: Point, second: Point) = hypot(first.x - second.x, first.y - second.y)

    @Volatile private var loaded = false

    private const val DETECTION_LONG_SIDE = 1000
    private const val CANNY_LOW = 60.0
    private const val CANNY_HIGH = 180.0
    private const val POLYGON_EPSILON = 0.02
    private const val MIN_PAGE_AREA_RATIO = 0.18
    private const val MAX_PAGE_AREA_RATIO = 0.995
    private const val MIN_OUTPUT_SIDE = 100
}
