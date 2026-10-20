package app.docscan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentBoundaryReplayInstrumentedTest {

    @Test
    fun realCorpusReplayOrExplicitNotRunSkip() {
        assertTrue("OpenCV Android native library must load", OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val pathArgument = InstrumentationRegistry.getArguments().getString("dbq.replay.manifest")
        val manifestFile = if (pathArgument.isNullOrBlank()) {
            File(app.filesDir, "dbq-replay/manifest.json")
        } else File(pathArgument)
        val replay = DocumentBoundaryReplaySupport(app, instrumentation.context)
        if (!manifestFile.isFile) {
            replay.writeNotRunReport("CORPUS_MANIFEST_MISSING", manifestFile)
            assumeTrue("No real replay corpus; expected ${manifestFile.canonicalPath}", false)
        }

        val manifest = replay.parseManifest(manifestFile)
        val missingModels = replay.missingModelAssets(manifest.variants)
        if (missingModels.isNotEmpty()) {
            replay.writeNotRunReport("OPTIONAL_MODEL_ASSETS_MISSING:$missingModels", manifestFile)
            assumeTrue("Optional model assets missing; replay not run: $missingModels", false)
        }

        val sourceBytes = manifestFile.readBytes()
        val sourceSha = sha256(sourceBytes)
        val output = replay.run(manifestFile, manifest)
        assertEquals("one output JSONL row for every variant/frame", manifest.variants.size * manifest.clips.sumOf { it.frames.size }, output.rowCount)
        assertEquals(sourceSha, output.sourceManifestSha256)
        assertTrue("summary must live in app-private output", output.directory.canonicalPath.startsWith(
            File(app.filesDir, "dbq-replay-results").canonicalPath + File.separator,
        ))
        assertTrue(sourceBytes.contentEquals(manifestFile.readBytes()))
        assertManifestOrderAndIdentity(output.directory, manifest, sourceSha)
    }

    @Test
    fun syntheticFiveFrameReplayWritesAlignedRowsWithoutGeometryThresholds() {
        assertTrue("OpenCV Android native library must load", OpenCVLoader.initLocal())
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val inputDir = File(app.cacheDir, "dbq-replay-synthetic-${UUID.randomUUID()}")
        assertTrue(inputDir.mkdirs())
        val frameJson = JSONArray()
        val frameIds = listOf(101L, 102L, 103L, 104L, 105L)
        val timestamps = listOf(0L, 110L, 220L, 330L, 440L)
        val sourceHashes = ArrayList<String>()

        for (index in frameIds.indices) {
            val name = "synthetic-${index + 1}.png"
            val path = File(inputDir, name)
            writeSyntheticFrame(path, index)
            sourceHashes += sha256(path.readBytes())
            frameJson.put(JSONObject()
                .put("frameId", frameIds[index])
                .put("timestampMs", timestamps[index])
                .put("file", name)
                .put("rotationDegrees", 0)
                .put("eligible", true))
        }
        val manifest = JSONObject()
            .put("schemaVersion", 1)
            .put("variants", JSONArray()
                .put(JSONObject().put("id", "original").put("backend", "original").put("tracking", false))
                .put(JSONObject().put("id", "A_tracking").put("backend", "A").put("tracking", true)))
            .put("clips", JSONArray().put(JSONObject()
                .put("clipId", "synthetic-five-frame")
                .put("group", "SYNTHETIC")
                .put("frames", frameJson)))
        val manifestFile = File(inputDir, "manifest.json").apply { writeText(manifest.toString(2)) }
        val manifestShaBefore = sha256(manifestFile.readBytes())
        val originalFramesBefore = frameIds.indices.map { sha256(File(inputDir, "synthetic-${it + 1}.png").readBytes()) }
        val parsed = DocumentBoundaryReplaySupport(app).parseManifest(manifestFile)
        val output = DocumentBoundaryReplaySupport(app).run(manifestFile, parsed)

        assertEquals(10, output.rowCount)
        assertEquals(manifestShaBefore, output.sourceManifestSha256)
        assertEquals(manifestShaBefore, sha256(manifestFile.readBytes()))
        assertEquals(sourceHashes, originalFramesBefore)
        assertEquals(sourceHashes, frameIds.indices.map { sha256(File(inputDir, "synthetic-${it + 1}.png").readBytes()) })
        assertManifestOrderAndIdentity(output.directory, parsed, manifestShaBefore)

        val summary = JSONObject(File(output.directory, "run.json").readText())
        assertEquals("COMPLETED", summary.getString("status"))
        assertEquals("VIRTUAL_REPLAY_MS", summary.getString("clockDomain"))
        assertFalse(summary.getBoolean("captureOrPresentTimestampsAvailable"))
        assertTrue(summary.getJSONObject("apk").getString("versionName").isNotBlank())
        assertEquals(2, summary.getJSONArray("variants").length())
    }

    @Test
    fun manifestRejectsNonIncreasingFrameIdentityOrTimestamp() {
        assertTrue("OpenCV Android native library must load", OpenCVLoader.initLocal())
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val inputDir = File(app.cacheDir, "dbq-replay-invalid-${UUID.randomUUID()}")
        assertTrue(inputDir.mkdirs())
        val frame = File(inputDir, "frame.png")
        writeSyntheticFrame(frame, 0)
        val replay = DocumentBoundaryReplaySupport(app)
        val invalidSequences = listOf(
            "duplicate-frame-id" to listOf(frameRecord(2, 10, frame.name), frameRecord(2, 20, frame.name)),
            "duplicate-timestamp" to listOf(frameRecord(2, 10, frame.name), frameRecord(3, 10, frame.name)),
        )
        for ((clipId, frames) in invalidSequences) {
            val manifest = JSONObject()
                .put("schemaVersion", 1)
                .put("variants", JSONArray().put(JSONObject()
                    .put("id", "original").put("backend", "original").put("tracking", false)))
                .put("clips", JSONArray().put(JSONObject()
                    .put("clipId", clipId).put("group", "SYNTHETIC")
                    .put("frames", JSONArray().put(frames[0]).put(frames[1]))))
            val manifestFile = File(inputDir, "$clipId.json").apply { writeText(manifest.toString()) }
            val failure = runCatching { replay.parseManifest(manifestFile) }.exceptionOrNull()
            assertTrue("$clipId must fail validation", failure is IllegalArgumentException)
        }
    }

    private fun assertManifestOrderAndIdentity(outputDir: File, manifest: ReplayManifest, manifestSha: String) {
        for (variant in manifest.variants) {
            for (clip in manifest.clips) {
                val file = File(outputDir, "${safeName(variant.id)}--${safeName(clip.clipId)}.jsonl")
                assertTrue("missing variant/clip output: $file", file.isFile)
                val rows = file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
                assertEquals(clip.frames.size, rows.size)
                assertEquals(clip.frames.map { it.frameId }, rows.map { it.getLong("frameId") })
                assertEquals(clip.frames.map { it.timestampMs }, rows.map { it.getLong("virtualReceiptMs") })
                rows.forEachIndexed { index, row ->
                    assertEquals(manifestSha, row.getString("sourceManifestSha256"))
                    assertEquals(variant.id, row.getString("variantId"))
                    assertEquals(clip.clipId, row.getString("clipId"))
                    assertEquals(clip.frames[index].eligible, row.getBoolean("eligible"))
                    assertTrue(row.has("inferenceScheduled"))
                    assertTrue(row.has("status"))
                    if (row.getBoolean("inferenceScheduled")) {
                        assertTrue("real detector runtime must be positive", row.getLong("detectorRuntimeNs") > 0L)
                        assertTrue(row.has("rawCandidates") && row.has("refinedCandidates"))
                    } else {
                        assertFalse("unscheduled frame must not invent runtime", row.has("detectorRuntimeNs"))
                    }
                }
            }
        }
    }

    private fun writeSyntheticFrame(path: File, index: Int) {
        val image = Mat(240, 320, CvType.CV_8UC3, Scalar(92.0, 92.0, 92.0))
        val paper = org.opencv.core.MatOfPoint(
            Point(30.0 + index, 25.0), Point(286.0 + index, 32.0),
            Point(278.0 + index, 215.0), Point(35.0 + index, 208.0),
        )
        try {
            Imgproc.fillConvexPoly(image, paper, Scalar(235.0, 235.0, 235.0))
            Imgproc.polylines(image, listOf(paper), true, Scalar(30.0, 30.0, 30.0), 3)
            for (line in 0..4) {
                Imgproc.line(image, Point(58.0 + index, 68.0 + line * 24),
                    Point(245.0 + index, 68.0 + line * 24), Scalar(75.0, 75.0, 75.0), 2)
            }
            check(Imgcodecs.imwrite(path.absolutePath, image)) { "Unable to write synthetic input $path" }
        } finally {
            paper.release()
            image.release()
        }
    }

    private fun frameRecord(frameId: Long, timestampMs: Long, file: String) = JSONObject()
        .put("frameId", frameId).put("timestampMs", timestampMs).put("file", file)
        .put("rotationDegrees", 0).put("eligible", true)

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
