package app.docscan.android

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class ReplayVariant(val id: String, val backend: String, val tracking: Boolean)
internal data class ReplayFrame(
    val frameId: Long,
    val timestampMs: Long,
    val file: File,
    val rotationDegrees: Int,
    val cropRect: BufferRect?,
    val eligible: Boolean,
    val referenceQuad: List<Pair<Double, Double>>?,
)
internal data class ReplayClip(val clipId: String, val group: String, val frames: List<ReplayFrame>)
internal data class ReplayManifest(val variants: List<ReplayVariant>, val clips: List<ReplayClip>)

/** Forwards production detector results unchanged and exposes only the latest synchronous call. */
internal class RecordingDocumentBoundaryDetector(
    private val delegate: DocumentBoundaryDetector,
) : DocumentBoundaryDetector {
    override val detectorId get() = delegate.detectorId
    override val configVersion get() = delegate.configVersion
    var callCount: Long = 0
        private set
    var lastResult: DocumentBoundaryResult? = null
        private set
    val lastHeatmapCorners: List<Pair<Double, Double>?>?
        get() = (delegate as? DocAlignerAndroidTestAdapter)?.lastRawReference?.channelCornersXY

    override fun detect(input: DocumentBoundaryInput): DocumentBoundaryResult {
        val result = delegate.detect(input)
        lastResult = result
        callCount++
        return result
    }

    override fun close() = delegate.close()
}

internal data class ReplayRun(val directory: File, val rowCount: Int, val sourceManifestSha256: String)

/** Schema-1 replay engine. All generated files are written below app-private filesDir/dbq-replay-results. */
internal class DocumentBoundaryReplaySupport(
    private val appContext: Context,
    private val assetContext: Context = InstrumentationRegistry.getInstrumentation().context,
) {
    private val outputRoot = File(appContext.filesDir, "dbq-replay-results")

    fun parseManifest(manifestFile: File): ReplayManifest {
        val canonicalManifest = manifestFile.canonicalFile
        require(canonicalManifest.isFile) { "Replay manifest does not exist: $canonicalManifest" }
        val parent = canonicalManifest.parentFile.canonicalFile
        val root = JSONObject(canonicalManifest.readText(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1) { "Only replay schemaVersion 1 is supported" }
        val variantsJson = root.getJSONArray("variants")
        val variants = (0 until variantsJson.length()).map { index ->
            val value = variantsJson.getJSONObject(index)
            val variant = ReplayVariant(
                id = value.getString("id"),
                backend = value.getString("backend"),
                tracking = value.getBoolean("tracking"),
            )
            require(isSafeId(variant.id)) { "Variant id must use only letters, digits, dot, underscore, and dash" }
            require(variant.backend in BACKENDS) { "Unsupported backend '${variant.backend}'" }
            variant
        }
        require(variants.isNotEmpty() && variants.map { it.id }.toSet().size == variants.size) {
            "Manifest needs unique, non-empty variants"
        }

        val clipsJson = root.getJSONArray("clips")
        val clips = (0 until clipsJson.length()).map { clipIndex ->
            val clipJson = clipsJson.getJSONObject(clipIndex)
            val clipId = clipJson.getString("clipId")
            require(isSafeId(clipId)) { "clipId must use only letters, digits, dot, underscore, and dash" }
            val group = clipJson.getString("group")
            val framesJson = clipJson.getJSONArray("frames")
            require(framesJson.length() > 0) { "Clip '$clipId' must contain frames" }
            var previousFrameId: Long? = null
            var previousTimestamp: Long? = null
            val frames = (0 until framesJson.length()).map { frameIndex ->
                val frameJson = framesJson.getJSONObject(frameIndex)
                val frameId = frameJson.getLong("frameId")
                val timestampMs = frameJson.getLong("timestampMs")
                require(frameId >= 0 && (previousFrameId == null || frameId > previousFrameId!!)) {
                    "frameId must be unique and strictly increasing in clip '$clipId'"
                }
                require(timestampMs >= 0 && timestampMs <= Long.MAX_VALUE / 1_000_000L &&
                    (previousTimestamp == null || timestampMs > previousTimestamp!!)) {
                    "timestampMs must be non-negative, representable as ns, and strictly increasing in '$clipId'"
                }
                previousFrameId = frameId
                previousTimestamp = timestampMs

                val relative = File(frameJson.getString("file"))
                require(!relative.isAbsolute) { "Frame file must be relative to the manifest parent" }
                val canonical = File(parent, relative.path).canonicalFile
                require(canonical.path.startsWith(parent.path + File.separator)) {
                    "Frame file escapes manifest parent: ${relative.path}"
                }
                require(canonical.isFile) { "Frame file does not exist: $canonical" }
                val rotation = frameJson.getInt("rotationDegrees")
                require(rotation in ROTATIONS) { "Unsupported rotationDegrees=$rotation" }
                val crop = if (frameJson.has("cropRect") && !frameJson.isNull("cropRect")) {
                    val rect = frameJson.getJSONObject("cropRect")
                    BufferRect(rect.getInt("left"), rect.getInt("top"), rect.getInt("right"), rect.getInt("bottom"))
                } else null
                val reference = if (frameJson.has("quad") && !frameJson.isNull("quad")) {
                    val array = frameJson.getJSONArray("quad")
                    require(array.length() == 8) { "quad must be 8 flat pixel-centre numbers" }
                    (0 until 4).map { i ->
                        val x = array.getDouble(i * 2)
                        val y = array.getDouble(i * 2 + 1)
                        require(x.isFinite() && y.isFinite()) { "quad coordinates must be finite" }
                        x to y
                    }
                } else null
                ReplayFrame(frameId, timestampMs, canonical, rotation, crop,
                    frameJson.getBoolean("eligible"), reference)
            }
            ReplayClip(clipId, group, frames)
        }
        require(clips.isNotEmpty() && clips.map { it.clipId }.toSet().size == clips.size) {
            "Manifest needs unique clips"
        }
        return ReplayManifest(variants, clips)
    }

    fun missingModelAssets(variants: List<ReplayVariant>): List<String> {
        val required = variants.mapNotNull { variant -> when (variant.backend) {
            "B_LC100" -> "models/lc100.onnx"
            "B_T8" -> "models/t8.onnx"
            else -> null
        } }.toSet()
        return required.filter { asset ->
            runCatching { assetContext.assets.open(asset).use { } }.isFailure
        }
    }

    fun writeNotRunReport(reason: String, manifestFile: File?): File {
        outputRoot.mkdirs()
        val directory = newRunDirectory()
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("status", "NOT_RUN")
            .put("reason", reason)
            .put("sourceManifest", manifestFile?.canonicalPath ?: JSONObject.NULL)
            .put("sourceManifestSha256", manifestFile?.takeIf { it.isFile }?.let { sha256(it.readBytes()) } ?: JSONObject.NULL)
            .put("clockDomain", "VIRTUAL_REPLAY_MS")
            .put("createdAtElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
        atomicWrite(File(directory, "run.json"), record.toString(2))
        return directory
    }

    fun run(manifestFile: File, manifest: ReplayManifest): ReplayRun {
        val manifestBytes = manifestFile.canonicalFile.readBytes()
        val manifestSha = sha256(manifestBytes)
        val originalFrameHashes = manifest.clips.flatMap { it.frames }.associate { frame ->
            frame.file.canonicalPath to sha256(frame.file.readBytes())
        }
        val directory = newRunDirectory()
        var rowCount = 0
        val variantConfigs = JSONArray()

        for (variant in manifest.variants) {
            variantConfigs.put(JSONObject()
                .put("variantId", variant.id)
                .put("backend", variant.backend)
                .put("tracking", variant.tracking)
                .put("detectorId", detectorId(variant.backend))
                .put("configVersion", configVersion(variant.backend))
                .put("rawModelSha256", modelSha(variant.backend).let { it ?: JSONObject.NULL }))

            for (clip in manifest.clips) {
                var virtualNowMs = 0L
                val coordinator = DocumentLiveDetectionCoordinator(clock = { virtualNowMs })
                val recording = RecordingDocumentBoundaryDetector(createBackend(variant))
                val filters = DocumentQuadFilterMode.entries.associateWith(::DocumentQuadFilter)
                val pipeline = DocumentBoundaryPipeline(
                    coordinator = coordinator,
                    detector = recording,
                    trackingEnabled = variant.tracking,
                    clockMs = { virtualNowMs },
                )
                try {
                    val rows = JSONArray()
                    var previousSpec: AnalysisBufferSpec? = null
                    var geometryId = 0L
                    for (frame in clip.frames) {
                        val sourceBytes = frame.file.readBytes()
                        val sourceSha = sha256(sourceBytes)
                        val decoded = decodeFullResolution(frame.file)
                        val rgba = decoded.second
                        try {
                            val imageWidth = rgba.cols()
                            val imageHeight = rgba.rows()
                            val crop = frame.cropRect ?: BufferRect(0, 0, imageWidth, imageHeight)
                            val spec = AnalysisBufferSpec(imageWidth, imageHeight, crop, frame.rotationDegrees)
                            if (previousSpec != spec) geometryId++
                            previousSpec = spec

                            val generation = coordinator.generationForFrame(spec, coordinator.currentGeneration())
                                ?: error("Coordinator rejected replay frame generation")
                            val input = DocumentBoundaryInput(
                                identity = BoundaryFrameId(frame.frameId, generation, geometryId),
                                spec = spec,
                                timing = DocumentFrameTiming(
                                    sensorTimestampNs = null,
                                    receivedTimestampNs = frame.timestampMs * 1_000_000L,
                                ),
                                rgba = rgba,
                            )
                            // The virtual process clock is deterministic and separate from measured detector runtime.
                            virtualNowMs = frame.timestampMs + 1L
                            val callsBefore = recording.callCount
                            val state = pipeline.process(input)
                            val inferred = recording.callCount > callsBefore
                            val detectorResult = if (inferred) recording.lastResult
                                ?: error("Detector scheduled without a captured result") else null
                            val filterOutputs = if (detectorResult?.outcome == BoundaryOutcome.FOUND) {
                                val visible = DocumentBoundarySelection.visible(detectorResult, spec.cropRect)
                                visible.firstOrNull()?.let { candidate ->
                                    DocumentQuadFilterMode.entries.associateWith { mode ->
                                        filters.getValue(mode).update(candidate.rawQuad, frame.timestampMs)
                                    }
                                }
                            } else null
                            rows.put(serializeFrame(
                                manifestSha, variant, clip, frame, sourceSha, input, state,
                                    inferred, detectorResult, filterOutputs,
                                    if (inferred) recording.lastHeatmapCorners else null,
                            ))
                            rowCount++
                        } finally {
                            rgba.release()
                            decoded.first.recycle()
                        }
                        check(sha256(frame.file.readBytes()) == sourceSha) {
                            "Replay changed an input frame: ${frame.file}"
                        }
                    }
                    val filename = "${safeName(variant.id)}--${safeName(clip.clipId)}.jsonl"
                    val file = File(directory, filename)
                    atomicWrite(file, buildString {
                        for (i in 0 until rows.length()) append(rows.getJSONObject(i).toString()).append('\n')
                    })
                } finally {
                    pipeline.close()
                }
            }
        }

        check(sha256(manifestFile.canonicalFile.readBytes()) == manifestSha) { "Replay modified the source manifest" }
        originalFrameHashes.forEach { (path, hash) ->
            check(sha256(File(path).readBytes()) == hash) { "Replay modified input frame $path" }
        }
        val summary = JSONObject()
            .put("schemaVersion", 1)
            .put("status", "COMPLETED")
            .put("sourceManifestSha256", manifestSha)
            .put("sourceManifestCanonicalPath", manifestFile.canonicalPath)
            .put("clockDomain", "VIRTUAL_REPLAY_MS")
            .put("captureOrPresentTimestampsAvailable", false)
            .put("apk", apkMetadata())
            .put("android", androidMetadata())
            .put("variants", variantConfigs)
            .put("clipIds", JSONArray(manifest.clips.map { it.clipId }))
            .put("rowCount", rowCount)
            .put("rawLegacyModelLicenseScopeResolved", false)
            .put("qualityClaim", JSONObject.NULL)
        atomicWrite(File(directory, "run.json"), summary.toString(2))
        return ReplayRun(directory, rowCount, manifestSha)
    }

    private fun createBackend(variant: ReplayVariant): DocumentBoundaryDetector = when (variant.backend) {
        "original" -> ClassicalDocumentBoundaryDetector(enhanced = false)
        "A" -> ClassicalDocumentBoundaryDetector(enhanced = true)
        "B_LC100" -> DocAlignerAndroidTestAdapter(DocAlignerAndroidTestAdapter.Model.LC100, assetContext.assets)
        "B_T8" -> DocAlignerAndroidTestAdapter(DocAlignerAndroidTestAdapter.Model.T8, assetContext.assets)
        else -> error("Unsupported backend ${variant.backend}")
    }

    private fun serializeFrame(
        manifestSha: String,
        variant: ReplayVariant,
        clip: ReplayClip,
        frame: ReplayFrame,
        sourceSha: String,
        input: DocumentBoundaryInput,
        state: LiveDetectionState,
        inferenceScheduled: Boolean,
        detectorResult: DocumentBoundaryResult?,
        filterOutputs: Map<DocumentQuadFilterMode, BoundaryQuad>?,
        heatmapCorners: List<Pair<Double, Double>?>?,
    ): JSONObject {
        val published = state.quadInBufferPixels?.let { points ->
            require(points.size == 8) { "Published buffer quad must have 8 coordinates" }
            JSONArray().apply {
                for (i in 0 until 4) put(JSONArray().put(points[2 * i].toDouble() - 0.5)
                    .put(points[2 * i + 1].toDouble() - 0.5))
            }
        } ?: JSONObject.NULL
        val row = JSONObject()
            .put("schemaVersion", 1)
            .put("sourceManifestSha256", manifestSha)
            .put("variantId", variant.id)
            .put("backend", variant.backend)
            .put("tracking", variant.tracking)
            .put("clipId", clip.clipId)
            .put("group", clip.group)
            .put("frameId", frame.frameId)
            .put("generationId", input.identity.generationId)
            .put("geometryId", input.identity.geometryId)
            .put("virtualReceiptMs", frame.timestampMs)
            .put("clockDomain", "VIRTUAL_REPLAY_MS")
            .put("inputFile", frame.file.name)
            .put("inputSha256", sourceSha)
            .put("rotationDegrees", frame.rotationDegrees)
            .put("cropRect", rectJson(input.spec.cropRect))
            .put("eligible", frame.eligible)
            .put("referenceQuadPixelCentres", frame.referenceQuad?.let(::pairListJson) ?: JSONObject.NULL)
            .put("inferenceScheduled", inferenceScheduled)
            .put("status", state.status.name)
            .put("confirmed", state.status == LiveDetectionStatus.CONFIRMED)
            .put("held", state.status == LiveDetectionStatus.HELD)
            .put("trackedGeometry", state.isTrackedGeometry)
            .put("publishedQuadPixelCentres", published)
            .put("trackId", state.trackId ?: JSONObject.NULL)
            .put("frameTimestampMonotonicMs", state.quadInBufferPixels?.let { state.frameTimestampMonotonicMs } ?: JSONObject.NULL)
            .put("lastDetectionTimestampMonotonicMs", state.quadInBufferPixels?.let { state.lastDetectionTimestampMonotonicMs } ?: JSONObject.NULL)
            .put("visualDeadlineMonotonicMs", state.quadInBufferPixels?.let { state.visualDeadlineMonotonicMs } ?: JSONObject.NULL)
            .put("processedTimestampMonotonicMs", state.quadInBufferPixels?.let { state.processedTimestampMonotonicMs } ?: JSONObject.NULL)
        if (inferenceScheduled) {
            val result = requireNotNull(detectorResult)
            val runtimeNs = result.detectorFinishedNs - result.detectorStartedNs
            require(result.identity == input.identity) { "Detector returned a result for a different frame identity" }
            require(result.detectorStartedNs > 0 && runtimeNs >= 0) { "Detector runtime clock was invalid" }
            row.put("detectorId", result.detectorId)
                .put("detectorConfigVersion", result.configVersion)
                .put("detectorOutcome", result.outcome.name)
                .put("detectorReason", result.reason ?: JSONObject.NULL)
                .put("detectorRuntimeNs", runtimeNs)
                .put("rawCandidates", candidatesJson(result.candidates, raw = true))
                .put("refinedCandidates", candidatesJson(result.candidates, raw = false))
            if (heatmapCorners != null) row.put("rawHeatmapCornersPixelCentres", nullablePointListJson(heatmapCorners))
        }
        if (filterOutputs != null) {
            val geometryOnly = JSONObject()
            filterOutputs.forEach { (mode, quad) -> geometryOnly.put(mode.name, quadJson(quad)) }
            row.put("filterGeometryOnly", geometryOnly)
        }
        return row
    }

    private fun candidatesJson(candidates: List<DocumentHypothesis>, raw: Boolean): JSONArray = JSONArray().apply {
        candidates.forEach { candidate ->
            val quad = if (raw) candidate.rawQuad else candidate.quad
            put(JSONObject()
                .put("candidateId", candidate.candidateId)
                .put("quadPixelCentres", quadJson(quad))
                .put("rawScore", candidate.rawScore)
                .put("documentPresence", candidate.documentPresence ?: JSONObject.NULL)
                .put("visibility", candidate.visibility.name)
                .put("refinement", candidate.refinement.name)
                .put("rankMargin", candidate.rankMargin ?: JSONObject.NULL)
                .put("cornerUncertaintyPx", JSONArray(candidate.cornerUncertaintyPx.map { it ?: JSONObject.NULL })))
        }
    }

    private fun decodeFullResolution(file: File): Pair<Bitmap, Mat> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "Unreadable frame: $file" }
        require(width.toLong() * height.toLong() <= MAX_FRAME_PIXELS) {
            "Frame exceeds $MAX_FRAME_PIXELS pixels: $width x $height"
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Unable to decode full-resolution frame: $file")
        if (bitmap.width != width || bitmap.height != height) {
            bitmap.recycle()
            error("Decoded dimensions differ from bounds; replay never resizes inputs")
        }
        val rgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            require(rgba.type() == CvType.CV_8UC4 && rgba.cols() == width && rgba.rows() == height) {
                "Expected full-resolution RGBA CV_8UC4 input"
            }
            return bitmap to rgba
        } catch (failure: Throwable) {
            rgba.release()
            bitmap.recycle()
            throw failure
        }
    }

    private fun apkMetadata(): JSONObject {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        @Suppress("DEPRECATION")
        val legacyVersionCode = info.versionCode.toLong()
        return JSONObject()
            .put("packageName", appContext.packageName)
            .put("versionName", info.versionName ?: JSONObject.NULL)
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode)
    }

    private fun androidMetadata() = JSONObject()
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

    private fun newRunDirectory(): File {
        outputRoot.mkdirs()
        val directory = File(outputRoot, "run-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Unable to create replay output directory $directory" }
        return directory
    }

    private fun atomicWrite(destination: File, text: String) {
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        check(temporary.renameTo(destination)) { "Unable to atomically publish replay output $destination" }
    }

    private fun safeName(value: String): String {
        require(isSafeId(value)) { "Unsafe replay id: $value" }
        return value
    }

    private fun isSafeId(value: String): Boolean =
        value.length in 1..80 && value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9._-]+"))

    private fun modelSha(backend: String): String? = when (backend) {
        "B_LC100" -> DocAlignerAndroidTestAdapter.Model.LC100.sha256
        "B_T8" -> DocAlignerAndroidTestAdapter.Model.T8.sha256
        else -> null
    }

    private fun detectorId(backend: String): String = when (backend) {
        "original" -> "opencv-original"
        "A" -> "opencv-A"
        "B_LC100" -> "docaligner-lc100-android-reference"
        "B_T8" -> "docaligner-t8-android-reference"
        else -> error("Unsupported backend $backend")
    }

    private fun configVersion(backend: String): String = when (backend) {
        "original" -> "original-0.4.279"
        "A" -> "A-normal-huber-1"
        "B_LC100" -> "onnx-opset16-fp32-${DocAlignerAndroidTestAdapter.Model.LC100.sha256}"
        "B_T8" -> "onnx-opset16-fp32-${DocAlignerAndroidTestAdapter.Model.T8.sha256}"
        else -> error("Unsupported backend $backend")
    }

    private fun rectJson(rect: BufferRect) = JSONObject()
        .put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)

    private fun quadJson(quad: BoundaryQuad) = JSONArray().apply {
        quad.points.forEach { put(JSONArray().put(it.x).put(it.y)) }
    }

    private fun pairListJson(points: List<Pair<Double, Double>>) = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.first).put(it.second)) }
    }

    private fun nullablePointListJson(points: List<Pair<Double, Double>?>) = JSONArray().apply {
        points.forEach { point ->
            put(point?.let { JSONArray().put(it.first).put(it.second) } ?: JSONObject.NULL)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_FRAME_PIXELS = 4_000_000L
        val BACKENDS = setOf("original", "A", "B_LC100", "B_T8")
        val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
