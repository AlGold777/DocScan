package app.docscan.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Test-only FP32 CPU adapter for the two pinned DocAligner heatmap ONNX models.
 * It mirrors DocAligner heatmap_reg/infer.py and deliberately never reports
 * FOUND: these model graphs expose no document-presence output.
 */
internal class DocAlignerAndroidTestAdapter(
    private val model: Model,
    assets: android.content.res.AssetManager,
) : DocumentBoundaryDetector {

    enum class Model(val asset: String, val sha256: String) {
        LC100(
            "models/lc100.onnx",
            "f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e",
        ),
        T8(
            "models/t8.onnx",
            "950070fd4a46e25d46db7c35d0ed77612d9063f70d4543c7c504f0da6a943a89",
        ),
    }

    data class RawReference(
        val channelCornersXY: List<Pair<Double, Double>?>,
        val inputShape: LongArray,
        val heatmapShape: LongArray,
        val outputNames: Set<String>,
    )

    override val detectorId: String = "docaligner-${model.name.lowercase()}-android-reference"
    override val configVersion: String = "onnx-opset16-fp32-${model.sha256}"

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = assets.open(model.asset).use { it.readBytes() }
        check(sha256(bytes) == model.sha256) { "Unexpected ${model.name} model asset SHA-256" }
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        session = options.use { environment.createSession(bytes, it) }
        check(session.inputNames == setOf("img")) { "Expected one img input: ${session.inputNames}" }
        check(session.outputNames == setOf("heatmap")) {
            "Expected heatmap-only graph without a document-presence output: ${session.outputNames}"
        }
    }

    /**
     * Raw model candidate evidence independent of the production presence/edge
     * policy. Returned coordinates are pixel-index centroids plus cropRect origin.
     * The later CameraX boundary transform owns the +0.5 pixel-center conversion.
     */
    fun inferRaw(input: DocumentBoundaryInput): RawReference {
        val rgba = input.rgba
        require(!rgba.empty() && rgba.type() == CvType.CV_8UC4) { "Expected non-empty CV_8UC4 RGBA input" }
        require(rgba.cols() == input.spec.bufferWidth && rgba.rows() == input.spec.bufferHeight) {
            "RGBA Mat does not match AnalysisBufferSpec"
        }

        val cropRect = input.spec.cropRect
        val cropView = Mat(rgba, Rect(cropRect.left, cropRect.top, cropRect.width, cropRect.height))
        val bgr = Mat()
        val resized = Mat()
        try {
            Imgproc.cvtColor(cropView, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, resized, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR)
            val pixels = ByteArray(INPUT_SIZE * INPUT_SIZE * 3)
            resized.get(0, 0, pixels)
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val planeSize = INPUT_SIZE * INPUT_SIZE
            // Upstream does HWC BGR -> CHW float32 / 255 with no mean/std normalization.
            for (channel in 0..2) {
                for (pixel in 0 until planeSize) {
                    inputBuffer.put(((pixels[pixel * 3 + channel].toInt() and 0xff).toFloat()) / 255.0f)
                }
            }
            inputBuffer.rewind()
            val tensorShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            OnnxTensor.createTensor(environment, inputBuffer, tensorShape).use { tensor ->
                session.run(mapOf("img" to tensor)).use { result ->
                    val heatmap = result.get("heatmap").orElseThrow() as OnnxTensor
                    val info = heatmap.info as TensorInfo
                    val heatmapShape = info.shape
                    check(heatmapShape.size == 4 && heatmapShape[0] == 1L && heatmapShape[1] == 4L) {
                        "Expected [1,4,H,W] heatmap, got ${heatmapShape.contentToString()}"
                    }
                    val heatmapValues = FloatArray(heatmapShape.fold(1L) { acc, d -> acc * d }.toInt())
                    heatmap.floatBuffer.get(heatmapValues)
                    val corners = postprocess(
                        heatmapValues = heatmapValues,
                        heatmapHeight = heatmapShape[2].toInt(),
                        heatmapWidth = heatmapShape[3].toInt(),
                        outputHeight = cropRect.height,
                        outputWidth = cropRect.width,
                    ).map { it?.let { p -> (p.x + cropRect.left) to (p.y + cropRect.top) } }
                    return RawReference(
                        channelCornersXY = corners,
                        inputShape = tensorShape,
                        heatmapShape = heatmapShape,
                        outputNames = session.outputNames,
                    )
                }
            }
        } finally {
            resized.release()
            bgr.release()
            cropView.release()
        }
    }

    override fun detect(input: DocumentBoundaryInput): DocumentBoundaryResult {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val raw = inferRaw(input)
        val corners = raw.channelCornersXY
        val candidates = if (corners.size == 4 && corners.all { it != null }) {
            val points = corners.map { point ->
                val nonNull = requireNotNull(point)
                BoundaryPoint(nonNull.first, nonNull.second)
            }
            val quad = runCatching { BoundaryQuad(points[0], points[1], points[2], points[3]) }.getOrNull()
            if (quad == null) emptyList() else listOf(
                DocumentHypothesis(
                    candidateId = 0,
                    quad = quad,
                    rawScore = 0.0,
                    documentPresence = null,
                    visibility = BoundaryVisibility.UNKNOWN,
                    source = detectorId,
                ),
            )
        } else emptyList()
        val finished = android.os.SystemClock.elapsedRealtimeNanos()
        return DocumentBoundaryResult(
            identity = input.identity,
            detectorId = detectorId,
            configVersion = configVersion,
            outcome = BoundaryOutcome.AMBIGUOUS,
            candidates = candidates,
            detectorStartedNs = started,
            detectorFinishedNs = finished,
            reason = "Reference heatmap has no document-presence head; raw corners do not authorize FOUND or NO_DOCUMENT",
        )
    }

    override fun close() = session.close()

    private fun postprocess(
        heatmapValues: FloatArray,
        heatmapHeight: Int,
        heatmapWidth: Int,
        outputHeight: Int,
        outputWidth: Int,
    ): List<Point?> {
        val corners = ArrayList<Point?>(4)
        val planeSize = heatmapHeight * heatmapWidth
        for (channel in 0 until 4) {
            val source = Mat(heatmapHeight, heatmapWidth, CvType.CV_32FC1)
            val resized = Mat()
            val byteHeatmap = Mat(outputHeight, outputWidth, CvType.CV_8UC1)
            val binary = Mat()
            val hierarchy = Mat()
            val contours = ArrayList<MatOfPoint>()
            try {
                source.put(0, 0, heatmapValues.copyOfRange(channel * planeSize, (channel + 1) * planeSize))
                Imgproc.resize(source, resized, Size(outputWidth.toDouble(), outputHeight.toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR)
                val floats = FloatArray(outputHeight * outputWidth)
                resized.get(0, 0, floats)
                val bytes = ByteArray(floats.size)
                for (i in floats.indices) {
                    val value = if (floats[i] < HEATMAP_THRESHOLD) 0.0f else floats[i]
                    // NumPy uint8(float32 * 255) truncates; Mat.convertTo would round.
                    bytes[i] = (value * 255.0f).toInt().coerceIn(0, 255).toByte()
                }
                byteHeatmap.put(0, 0, bytes)
                Imgproc.threshold(byteHeatmap, binary, 0.0, 255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                Imgproc.findContours(binary, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                val polygons = contours.filter { it.total() >= 3L }
                val contourAreas = polygons.map { Imgproc.moments(it).m00 }
                val maxArea = contourAreas.maxOrNull()
                val maxAreaIndices = if (maxArea == null) emptyList() else
                    contourAreas.indices.filter { contourAreas[it] == maxArea }
                // Upstream selects all tied maximum polygons, flattens all
                // centroids, then rejects that channel because len(point) != 2.
                if (maxAreaIndices.size != 1) {
                    corners.add(null)
                } else {
                    val moments = Imgproc.moments(polygons[maxAreaIndices.single()])
                    corners.add(Point(
                        moments.m10 / (moments.m00 + 1e-5),
                        moments.m01 / (moments.m00 + 1e-5),
                    ))
                }
            } finally {
                contours.forEach { it.release() }
                hierarchy.release()
                binary.release()
                byteHeatmap.release()
                resized.release()
                source.release()
            }
        }
        return corners
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val INPUT_SIZE = 256
        const val HEATMAP_THRESHOLD = 0.3f
    }
}
