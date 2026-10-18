package app.docscan.android

import android.graphics.Bitmap
import android.util.Log
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Finds a strong four-edge page candidate and returns a rectified preview; it never edits input. */
object DocumentCropProcessor {
    const val MAX_SOURCE_PIXELS = 4L * 1024L * 1024L
    private const val TAG = "DocScan"
    private const val MIN_SCORE = 0.45 // Lower than this — "document not found"

    data class Detection(
        val bufferPoints: FloatArray, // TL, TR, BR, BL in buffer pixels (8 floats: x0, y0, x1, y1, x2, y2, x3, y3)
        val imageWidth: Int,
        val imageHeight: Int,
        val score: Double,
    ) {
        fun toNormalized(): CropQuadrilateral {
            fun p(i: Int) = NormalizedCropPoint(
                (bufferPoints[i] / imageWidth).toDouble().coerceIn(0.0, 1.0),
                (bufferPoints[i + 1] / imageHeight).toDouble().coerceIn(0.0, 1.0),
            )
            return CropQuadrilateral(
                topLeft = p(0),
                topRight = p(2),
                bottomRight = p(4),
                bottomLeft = p(6),
            )
        }
    }

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

    fun detectCorners(source: Bitmap): Detection? {
        ensureOpenCvLoaded()
        val rgba = Mat()
        return try {
            Utils.bitmapToMat(source, rgba)
            detectCornersFromRgba(rgba)
        } finally {
            rgba.release()
        }
    }

    fun detectCornersFromRgbaBuffer(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): Detection? {
        ensureOpenCvLoaded()
        val rgba = Mat(height, width, CvType.CV_8UC4, buffer, rowStride.toLong())
        return try {
            detectCornersFromRgba(rgba)
        } finally {
            rgba.release()
        }
    }

    fun rotateNormalizedQuad(quad: CropQuadrilateral, rotationDegrees: Int): CropQuadrilateral {
        val normRot = (rotationDegrees % 360 + 360) % 360
        if (normRot == 0) return quad

        fun rot(p: NormalizedCropPoint): NormalizedCropPoint {
            return when (normRot) {
                90 -> NormalizedCropPoint(1.0 - p.y, p.x)
                180 -> NormalizedCropPoint(1.0 - p.x, 1.0 - p.y)
                270 -> NormalizedCropPoint(p.y, 1.0 - p.x)
                else -> p
            }
        }

        val rawPoints = quad.asList().map(::rot)
        val cx = rawPoints.sumOf { it.x } / 4.0
        val cy = rawPoints.sumOf { it.y } / 4.0
        val sorted = rawPoints.sortedBy { atan2(it.y - cy, it.x - cx) }
        val tlIndex = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val p = (0 until 4).map { sorted[(tlIndex + it) % 4] }
        return CropQuadrilateral(
            topLeft = p[0],
            topRight = p[1],
            bottomRight = p[2],
            bottomLeft = p[3],
        )
    }

    fun detectCornersFromRgba(input: Mat): Detection? {
        ensureOpenCvLoaded()
        if (input.empty()) return null
        val ow = input.cols()
        val oh = input.rows()
        val ds = min(1.0, 720.0 / max(ow, oh).toDouble())
        val work = Mat()
        val gray = Mat()
        val blur = Mat()
        val edgeMask = Mat()
        val paperMask = Mat()
        val tmp = Mat()
        try {
            Imgproc.resize(input, work, Size(ow * ds, oh * ds), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(work, gray, if (input.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            // ---- Карта A: Canny OR grad(Adaptive) ----
            val otsuLevel = Imgproc.threshold(blur, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val canny = Mat()
            val low = max(20.0, otsuLevel * 0.45)
            val high = max(low + 20.0, otsuLevel * 1.20)
            Imgproc.Canny(blur, canny, low, high, 3, true)

            // Adaptive threshold большим блоком переживает неравномерное освещение
            val adapt = Mat()
            var block = (min(gray.cols(), gray.rows()) * 0.10).roundToInt()
            if (block % 2 == 0) block++
            Imgproc.adaptiveThreshold(blur, adapt, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block.coerceIn(31, 101), 7.0)
            val gradMask = Mat()
            val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(adapt, gradMask, Imgproc.MORPH_GRADIENT, k5)
            adapt.release()

            Core.bitwise_or(canny, gradMask, edgeMask)
            canny.release()
            gradMask.release()

            Imgproc.morphologyEx(edgeMask, edgeMask, Imgproc.MORPH_CLOSE, k5, Point(-1.0, -1.0), 2)
            k5.release()
            val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edgeMask, edgeMask, k3)
            k3.release()

            // ---- Карта B: "бумага" = светлое + малонасыщенное пятно ----
            buildPaperMask(work, paperMask)

            // ---- Сбор и скоринг кандидатов с обеих карт ----
            val frameArea = work.cols().toDouble() * work.rows()
            var best: Candidate? = null
            var bestMap = ""
            for ((mask, name) in listOf(edgeMask to "edges", paperMask to "paper")) {
                for (cand in extractCandidates(mask, gray, edgeMask, frameArea)) {
                    if (best == null || cand.score > best.score) {
                        best = cand
                        bestMap = name
                    }
                }
            }

            // ---- Фоллбек HoughLinesP при слабом скоре контуров ----
            if (best == null || best.score < MIN_SCORE) {
                val hough = houghLinesFallback(edgeMask, gray, frameArea)
                if (hough != null && (best == null || hough.score > best.score)) {
                    best = hough
                    bestMap = "hough"
                }
            }

            if (best != null) {
                Log.d(TAG, "Document detected: score=%.3f map=%s".format(best.score, bestMap))
            }
            if (best == null || best.score < MIN_SCORE) return null

            // ---- Субпиксельное уточнение граней через fitLine ----
            val refined = refineQuadWithFitLine(best.pts, edgeMask)

            val inv = 1.0f / ds.toFloat()
            val pts = FloatArray(8)
            refined.forEachIndexed { i, p ->
                pts[i * 2] = (p.x * inv).toFloat().coerceIn(0f, ow.toFloat() - 1f)
                pts[i * 2 + 1] = (p.y * inv).toFloat().coerceIn(0f, oh.toFloat() - 1f)
            }
            return Detection(pts, ow, oh, best.score)
        } finally {
            work.release()
            gray.release()
            blur.release()
            edgeMask.release()
            paperMask.release()
            tmp.release()
        }
    }

    fun rectify(source: Bitmap): Bitmap? {
        val detection = detectCorners(source) ?: return null
        return runCatching { rectifyManual(source, detection.toNormalized()) }.getOrNull()
    }

    private fun ensureOpenCvLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                if (!OpenCVLoader.initLocal()) {
                    System.loadLibrary("opencv_java4")
                }
                loaded = true
            }
        }
    }

    // ---------------- Карта "бумаги" ----------------
    private fun buildPaperMask(src: Mat, out: Mat) {
        val rgb = Mat()
        val hsv = Mat()
        val ch = ArrayList<Mat>()
        val vMask = Mat()
        val sMask = Mat()
        val vBlur = Mat()
        try {
            if (src.channels() == 4) {
                Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
            } else {
                src.copyTo(rgb)
            }
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, ch)
            Imgproc.GaussianBlur(ch[2], vBlur, Size(9.0, 9.0), 0.0)
            Imgproc.threshold(vBlur, vMask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.threshold(ch[1], sMask, 70.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(vMask, sMask, out)
            val k15 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, k15)
            Imgproc.morphologyEx(out, out, Imgproc.MORPH_OPEN, k7)
            fillHoles(out)
            k15.release()
            k7.release()
        } finally {
            rgb.release()
            hsv.release()
            vMask.release()
            sMask.release()
            vBlur.release()
            ch.forEach { it.release() }
        }
    }

    private fun fillHoles(mask: Mat) {
        val contours = ArrayList<MatOfPoint>()
        val work = mask.clone()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.drawContours(mask, contours, -1, Scalar(255.0), Imgproc.FILLED)
        } finally {
            work.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    // ---------------- Кандидаты ----------------
    private data class Candidate(val pts: List<Point>, val score: Double)

    private fun extractCandidates(
        mask: Mat,
        gray: Mat,
        edgeMapRef: Mat,
        frameArea: Double,
    ): List<Candidate> {
        val work = mask.clone()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val result = ArrayList<Candidate>()
        try {
            // RETR_LIST обязателен: лист может лежать ВНУТРИ внешнего контура стола
            Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val top = contours
                .map { it to Imgproc.contourArea(it) }
                .filter { it.second > frameArea * 0.07 && it.second < frameArea * 0.96 }
                .sortedByDescending { it.second }
                .take(10)

            for ((cnt, _) in top) {
                val hull = hullOf(cnt) ?: continue
                val peri = Imgproc.arcLength(hull, true)
                if (peri < min(gray.cols(), gray.rows()) * 0.8) {
                    hull.release()
                    continue
                }
                val quad = approxQuad(hull, peri) ?: minAreaRectFallback(hull)
                hull.release()
                if (quad == null) continue
                val ordered = orderCorners(quad) ?: continue
                val score = scoreQuad(ordered, gray, edgeMapRef, frameArea) ?: continue
                result += Candidate(ordered, score)
            }
        } finally {
            work.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
        return result
    }

    /** Бинарный поиск epsilon: быстрее и стабильнее, чем фиксированный список */
    private fun approxQuad(hull: MatOfPoint2f, peri: Double): Array<Point>? {
        var lo = 0.005 * peri
        var hi = 0.08 * peri
        var found: Array<Point>? = null
        repeat(12) {
            val eps = (lo + hi) / 2.0
            val approx = MatOfPoint2f()
            try {
                Imgproc.approxPolyDP(hull, approx, eps, true)
                val n = approx.total().toInt()
                if (n == 4) {
                    found = approx.toArray()
                    lo = eps
                } else if (n > 4) {
                    lo = eps
                } else {
                    hi = eps
                }
            } finally {
                approx.release()
            }
        }
        return found
    }

    private fun minAreaRectFallback(hull: MatOfPoint2f): Array<Point>? {
        val rr = Imgproc.minAreaRect(hull)
        val rectArea = rr.size.width * rr.size.height
        if (rectArea <= 0) return null
        if (Imgproc.contourArea(hull) / rectArea < 0.85) return null
        val pts = arrayOfNulls<Point>(4)
        rr.points(pts)
        return pts.filterNotNull().toTypedArray().takeIf { it.size == 4 }
    }

    private fun hullOf(c: MatOfPoint): MatOfPoint2f? {
        val idx = MatOfInt()
        Imgproc.convexHull(c, idx)
        val src = c.toArray()
        val idxArr = idx.toArray()
        idx.release()
        if (idxArr.size < 4) return null
        return MatOfPoint2f(*Array(idxArr.size) { src[idxArr[it]] })
    }

    /** TL, TR, BR, BL — геометрический расчет, устойчивый к углам наклона */
    private fun orderCorners(p: Array<Point>): List<Point>? {
        if (p.size != 4) return null
        val tl = p.minByOrNull { it.x + it.y } ?: return null
        val br = p.maxByOrNull { it.x + it.y } ?: return null
        val tr = p.minByOrNull { it.y - it.x } ?: return null
        val bl = p.maxByOrNull { it.y - it.x } ?: return null
        val candidate = listOf(tl, tr, br, bl)
        if (candidate.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size == 4) {
            return candidate
        }
        // Fallback: Clockwise around centroid
        val cx = p.sumOf { it.x } / 4.0
        val cy = p.sumOf { it.y } / 4.0
        val clockwise = p.sortedBy { atan2(it.y - cy, it.x - cx) }
        val tlIdx = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: return null
        val ordered = (0 until 4).map { clockwise[(tlIdx + it) % 4] }
        if (ordered.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size != 4) return null
        return ordered
    }

    // ---------------- Скоринг ----------------
    private fun scoreQuad(
        p: List<Point>,
        gray: Mat,
        edgeMap: Mat,
        frameArea: Double,
    ): Double? {
        if (p.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size < 4) return null
        if (!isConvex(p)) return null
        val w = gray.cols()
        val h = gray.rows()
        val area = polygonArea(p)
        val areaRatio = area / frameArea
        if (areaRatio < 0.07 || areaRatio > 0.96) return null

        val sides = doubleArrayOf(
            dist(p[0], p[1]),
            dist(p[1], p[2]),
            dist(p[2], p[3]),
            dist(p[3], p[0]),
        )
        if (sides.any { it < min(w, h) * 0.06 }) return null

        val angles = DoubleArray(4) { cornerAngle(p[(it + 3) % 4], p[it], p[(it + 1) % 4]) }
        // Предел 45..135 отсекает перспективные "стрелы"
        if (angles.any { it < 45.0 || it > 135.0 }) return null

        val width = (sides[0] + sides[2]) / 2.0
        val height = (sides[1] + sides[3]) / 2.0
        val aspect = max(width, height) / max(1.0, min(width, height))
        if (aspect > 3.3) return null

        // Параллельность проверяем и направлением, и длинами противоположных сторон
        val parDir = (parallelDir(p[0], p[1], p[3], p[2]) + parallelDir(p[0], p[3], p[1], p[2])) / 2.0
        val parLen = (min(sides[0], sides[2]) / max(sides[0], sides[2]) + min(sides[1], sides[3]) / max(sides[1], sides[3])) / 2.0
        if (parLen < 0.50) return null

        // ---- Взвешенный score ----
        val areaScore = ((areaRatio - 0.07) / 0.65).coerceIn(0.0, 1.0)
        val angleScore = exp(-angles.map { abs(it - 90.0) }.average() / 24.0)
        val parallelScore = (parDir + parLen) / 2.0
        val aspectScore = when {
            aspect <= 1.8 -> 1.0
            aspect <= 2.4 -> 1.0 - (aspect - 1.8) / 1.2
            else -> (0.5 - (aspect - 2.4) / 1.8).coerceAtLeast(0.0)
        }
        val edgeSupport = edgeSupportScore(p, edgeMap) // есть ли реальные края вдоль сторон
        val contrast = ringContrastScore(p, gray) // бумага отличается от фона по кольцу
        val centerScore = centerScore(p, w, h)
        val borderPenalty = borderPenalty(p, w, h) // касание краёв = признак стола

        return 0.20 * areaScore +
                0.22 * angleScore +
                0.14 * parallelScore +
                0.12 * aspectScore +
                0.12 * edgeSupport +
                0.10 * contrast +
                0.10 * centerScore -
                0.20 * borderPenalty
    }

    // --- Геометрия ---
    private fun isConvex(p: List<Point>): Boolean {
        var sign = 0
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cr > 0) 1 else if (cr < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return true
    }

    private fun polygonArea(p: List<Point>): Double {
        var s = 0.0
        for (i in 0..3) {
            val j = (i + 1) % 4
            s += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(s) / 2.0
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun distance(first: Point, second: Point) = hypot(first.x - second.x, first.y - second.y)

    private fun cornerAngle(prev: Point, cur: Point, next: Point): Double {
        val ax = prev.x - cur.x
        val ay = prev.y - cur.y
        val bx = next.x - cur.x
        val by = next.y - cur.y
        val m = hypot(ax, ay) * hypot(bx, by)
        if (m < 1e-9) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by) / m).coerceIn(-1.0, 1.0)))
    }

    private fun parallelDir(a1: Point, a2: Point, b1: Point, b2: Point): Double {
        var ax = a2.x - a1.x
        var ay = a2.y - a1.y
        var bx = b2.x - b1.x
        var by = b2.y - b1.y
        val am = hypot(ax, ay)
        val bm = hypot(bx, by)
        if (am < 1e-9 || bm < 1e-9) return 0.0
        ax /= am
        ay /= am
        bx /= bm
        by /= bm
        return abs(ax * bx + ay * by).coerceIn(0.0, 1.0)
    }

    // --- Фотометрические проверки ---
    /** Сэмплируем 40 точек на каждой стороне: есть ли реальный edge-пиксель в окрестности ±2px */
    private fun edgeSupportScore(p: List<Point>, edgeMap: Mat): Double {
        var good = 0
        var total = 0
        val cols = edgeMap.cols()
        val rows = edgeMap.rows()
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            for (j in 0..40) {
                val t = j / 40.0
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x !in 2 until cols - 2 || y !in 2 until rows - 2) continue
                total++
                var found = false
                loop@ for (dy in -2..2) {
                    for (dx in -2..2) {
                        val pixel = edgeMap.get(y + dy, x + dx)
                        if (pixel != null && pixel[0] > 0) {
                            found = true
                            break@loop
                        }
                    }
                }
                if (found) good++
            }
        }
        return if (total == 0) 0.0 else good.toDouble() / total
    }

    /** Разница яркости внутри листа и в кольце вокруг него — сильнейший сигнал против стола */
    private fun ringContrastScore(p: List<Point>, gray: Mat): Double {
        val inner = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val outer = Mat()
        val ring = Mat()
        val poly = MatOfPoint(*p.toTypedArray())
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0))
        return try {
            Imgproc.fillConvexPoly(inner, poly, Scalar(255.0))
            Imgproc.dilate(inner, outer, kernel)
            Core.subtract(outer, inner, ring)
            val meanInner = Core.mean(gray, inner).`val`[0]
            val meanRing = Core.mean(gray, ring).`val`[0]
            val diff = abs(meanInner - meanRing)
            (diff / 50.0).coerceIn(0.0, 1.0)
        } finally {
            inner.release()
            outer.release()
            ring.release()
            poly.release()
            kernel.release()
        }
    }

    private fun centerScore(p: List<Point>, w: Int, h: Int): Double {
        val cx = p.sumOf { it.x } / 4.0
        val cy = p.sumOf { it.y } / 4.0
        val dx = (cx - w / 2.0) / (w / 2.0)
        val dy = (cy - h / 2.0) / (h / 2.0)
        return (1.0 - sqrt(dx * dx + dy * dy) / 1.414).coerceIn(0.0, 1.0)
    }

    private fun borderPenalty(p: List<Point>, w: Int, h: Int): Double {
        val m = min(w, h) * 0.025
        return p.count { it.x < m || it.y < m || it.x > w - m || it.y > h - m } / 4.0
    }

    private fun refineQuadWithFitLine(pts: List<Point>, edgeMask: Mat): List<Point> {
        if (pts.size != 4) return pts
        val cols = edgeMask.cols()
        val rows = edgeMask.rows()

        data class LineEq(val a: Double, val b: Double, val c: Double)

        val lines = mutableListOf<LineEq>()
        val byteData = ByteArray(cols * rows)
        edgeMask.get(0, 0, byteData)

        for (i in 0 until 4) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val len = hypot(dx, dy)
            if (len < 10.0) {
                val a = -dy
                val b = dx
                val c = -(a * p1.x + b * p1.y)
                lines.add(LineEq(a, b, c))
                continue
            }
            val tx = dx / len
            val ty = dy / len
            val nx = -ty
            val ny = tx

            val edgePoints = mutableListOf<Point>()
            var s = 0.05 * len
            while (s <= 0.95 * len) {
                val mx = p1.x + tx * s
                val my = p1.y + ty * s
                for (k in -5..5) {
                    val qx = (mx + nx * k).roundToInt()
                    val qy = (my + ny * k).roundToInt()
                    if (qx in 0 until cols && qy in 0 until rows) {
                        if (byteData[qy * cols + qx].toInt() != 0) {
                            edgePoints.add(Point(qx.toDouble(), qy.toDouble()))
                        }
                    }
                }
                s += 2.0
            }

            if (edgePoints.size >= 15) {
                val ptsMat = MatOfPoint2f(*edgePoints.toTypedArray())
                val lineMat = Mat()
                try {
                    Imgproc.fitLine(ptsMat, lineMat, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
                    val vx = lineMat.get(0, 0)[0]
                    val vy = lineMat.get(1, 0)[0]
                    val x0 = lineMat.get(2, 0)[0]
                    val y0 = lineMat.get(3, 0)[0]
                    val a = -vy
                    val b = vx
                    val c = vy * x0 - vx * y0
                    lines.add(LineEq(a, b, c))
                } finally {
                    ptsMat.release()
                    lineMat.release()
                }
            } else {
                val a = -dy
                val b = dx
                val c = -(a * p1.x + b * p1.y)
                lines.add(LineEq(a, b, c))
            }
        }

        fun intersect(l1: LineEq, l2: LineEq): Point? {
            val det = l1.a * l2.b - l2.a * l1.b
            if (abs(det) < 1e-5) return null
            val x = (l1.b * l2.c - l2.b * l1.c) / det
            val y = (l2.a * l1.c - l1.a * l2.c) / det
            return Point(x, y)
        }

        val refined = mutableListOf<Point>()
        for (i in 0 until 4) {
            val lPrev = lines[(i + 3) % 4]
            val lCur = lines[i]
            val pt = intersect(lPrev, lCur)
            if (pt == null || dist(pt, pts[i]) > max(20.0, 0.15 * dist(pts[i], pts[(i + 1) % 4]))) {
                return pts
            }
            refined.add(pt)
        }

        if (!isConvex(refined)) return pts
        return orderCorners(refined.toTypedArray()) ?: pts
    }

    private fun houghLinesFallback(edgeMask: Mat, gray: Mat, frameArea: Double): Candidate? {
        val linesMat = Mat()
        return try {
            Imgproc.HoughLinesP(edgeMask, linesMat, 1.0, Math.PI / 180.0, 35, 30.0, 10.0)
            if (linesMat.rows() < 4) return null
            val w = edgeMask.cols()
            val h = edgeMask.rows()

            data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val len: Double, val angle: Double)

            val hLines = mutableListOf<Segment>()
            val vLines = mutableListOf<Segment>()

            for (i in 0 until min(linesMat.rows(), 80)) {
                val vec = linesMat.get(i, 0) ?: continue
                val x1 = vec[0]
                val y1 = vec[1]
                val x2 = vec[2]
                val y2 = vec[3]
                val dx = x2 - x1
                val dy = y2 - y1
                val len = hypot(dx, dy)
                if (len < 25.0) continue
                val angle = abs(atan2(dy, dx) * 180.0 / Math.PI)
                if (angle < 25.0 || angle > 155.0) {
                    hLines.add(Segment(x1, y1, x2, y2, len, angle))
                } else if (angle in 65.0..115.0) {
                    vLines.add(Segment(x1, y1, x2, y2, len, angle))
                }
            }

            if (hLines.size < 2 || vLines.size < 2) return null

            val top = hLines.minByOrNull { min(it.y1, it.y2) } ?: return null
            val bottom = hLines.maxByOrNull { max(it.y1, it.y2) } ?: return null
            val left = vLines.minByOrNull { min(it.x1, it.x2) } ?: return null
            val right = vLines.maxByOrNull { max(it.x1, it.x2) } ?: return null

            fun lineIntersect(s1: Segment, s2: Segment): Point? {
                val a1 = -(s1.y2 - s1.y1)
                val b1 = s1.x2 - s1.x1
                val c1 = -(a1 * s1.x1 + b1 * s1.y1)
                val a2 = -(s2.y2 - s2.y1)
                val b2 = s2.x2 - s2.x1
                val c2 = -(a2 * s2.x1 + b2 * s2.y1)
                val det = a1 * b2 - a2 * b1
                if (abs(det) < 1e-4) return null
                val x = (b1 * c2 - b2 * c1) / det
                val y = (a2 * c1 - a1 * c2) / det
                return Point(x, y)
            }

            val pTL = lineIntersect(top, left) ?: return null
            val pTR = lineIntersect(top, right) ?: return null
            val pBR = lineIntersect(bottom, right) ?: return null
            val pBL = lineIntersect(bottom, left) ?: return null

            val rawPts = arrayOf(pTL, pTR, pBR, pBL)
            val ordered = orderCorners(rawPts) ?: return null
            if (!isConvex(ordered)) return null

            val sc = scoreQuad(ordered, gray, edgeMask, frameArea)
            if (sc != null && sc >= 0.40) Candidate(ordered, sc) else null
        } catch (e: Throwable) {
            null
        } finally {
            linesMat.release()
        }
    }

    @Volatile private var loaded = false
}
