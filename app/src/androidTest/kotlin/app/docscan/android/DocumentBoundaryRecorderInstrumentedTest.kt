package app.docscan.android

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentBoundaryRecorderInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun frameArchiveOwnsItsCopyBoundsImageSizeAndLabelsDataAsDebugAnalysis() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dbq-frame-archive-${System.nanoTime()}")
        val source = Mat(800, 1200, CvType.CV_8UC4, Scalar(10.0, 40.0, 200.0, 255.0))
        val archive = DocumentFrameArchive(directory)
        try {
            archive.record(input(7L, source, 1200, 800))
            // record() must finish copying/resizing before borrowing returns to CameraX.
            source.setTo(Scalar(0.0, 255.0, 0.0, 255.0))
        } finally {
            source.release()
            archive.close()
        }

        val summaryFile = awaitFile(File(directory, "recording-summary.json"))
        val summary = JSONObject(summaryFile.readText())
        assertEquals(1, summary.getInt("acceptedFrames"))
        assertEquals(450, summary.getInt("limitFrames"))
        assertEquals("DEBUG_ANALYSIS", summary.getString("source"))
        assertFalse(summary.getBoolean("presentationTimeAvailable"))

        val metadata = JSONObject(File(directory, "frame-7.json").readText())
        assertEquals(1200, metadata.getInt("originalWidth"))
        assertEquals(800, metadata.getInt("originalHeight"))
        assertEquals(9, metadata.getJSONArray("originalCentresToImageCentres").length())

        val image = Imgcodecs.imread(File(directory, "frame-7.jpg").absolutePath)
        try {
            assertEquals(720, image.cols())
            assertEquals(480, image.rows())
            val bgr = image.get(240, 360)
            assertNotNull(bgr)
            assertTrue("Archive should preserve the pre-mutation blue channel", bgr[0] > 180.0)
            assertTrue("Archive should not contain the post-record green mutation", bgr[1] < 80.0)
            assertTrue(bgr[2] < 50.0)
        } finally { image.release() }
    }

    @Test
    fun frameArchiveEnforces450FrameCapAndRejectsCallsAfterClose() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dbq-frame-cap-${System.nanoTime()}")
        val source = Mat(24, 32, CvType.CV_8UC4, Scalar(40.0, 80.0, 160.0, 255.0))
        val archive = DocumentFrameArchive(directory)
        for (frame in 0L..451L) archive.record(input(frame, source, 32, 24))
        archive.close()
        archive.record(input(999L, source, 32, 24))
        source.release()

        val summary = JSONObject(awaitFile(File(directory, "recording-summary.json")).readText())
        assertTrue("Accepted frame count cannot exceed the hard cap", summary.getInt("acceptedFrames") in 0..450)
        val writtenImages = directory.listFiles { file -> file.extension == "jpg" }?.size ?: 0
        assertTrue("No more than the configured frame cap may reach disk", writtenImages <= 450)
        assertEquals(summary.getInt("acceptedFrames"), writtenImages)
    }

    @Test
    fun diagnosticsCopiesBorrowedGeometryAndCloseDrainsBoundedEventWriter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dbq-diagnostics-${System.nanoTime()}")
        val output = File(directory, "trace.jsonl")
        val spec = AnalysisBufferSpec(32, 24, BufferRect(0, 0, 32, 24), 0)
        val borrowedPoints = floatArrayOf(2f, 3f, 29f, 3f, 29f, 20f, 2f, 20f)
        val inputMat = Mat()
        val diagnostics = DocumentBoundaryDiagnostics(output)
        try {
            val input = DocumentBoundaryInput(
                BoundaryFrameId(41L, 8L, 3L), spec,
                DocumentFrameTiming(sensorTimestampNs = null, receivedTimestampNs = 41_000_000L), inputMat,
            )
            val result = DocumentBoundaryResult(
                identity = input.identity,
                detectorId = "recorder-test",
                configVersion = "v1",
                outcome = BoundaryOutcome.FOUND,
                candidates = emptyList(),
                detectorStartedNs = 41_000_001L,
                detectorFinishedNs = 41_000_002L,
            )
            val state = LiveDetectionState(
                generationId = 8L,
                status = LiveDetectionStatus.CONFIRMED,
                analysisSpec = spec,
                quadInBufferPixels = borrowedPoints,
                quadInNormalizedViewPort = null,
                rawScore = 0.9,
                frameTimestampMonotonicMs = 41L,
                processedTimestampMonotonicMs = 42L,
                publicationId = 9L,
                visualDeadlineMonotonicMs = 241L,
                lastDetectionTimestampMonotonicMs = 41L,
            )
            diagnostics.record(input, result, state)
            borrowedPoints[0] = 999f
        } finally {
            inputMat.release()
            diagnostics.close()
        }

        val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
        var lines: List<String> = emptyList()
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (output.isFile) {
                lines = output.readLines()
                if (lines.any { it.contains("\"event\":\"end\"") }) break
            }
            android.os.SystemClock.sleep(25L)
        }
        assertTrue("close() must drain accepted snapshots and write its end marker", lines.size >= 3)
        val start = JSONObject(lines.first())
        val event = JSONObject(lines.first { it.contains("\"event\":\"result\"") })
        val end = JSONObject(lines.last())
        assertEquals("start", start.getString("event"))
        assertEquals(41L, event.getLong("frameId"))
        assertEquals(8L, event.getLong("generationId"))
        assertEquals(2.0, event.getJSONArray("displayBufferQuad").getDouble(0), 0.0)
        assertTrue("Unknown presentation timing remains explicit", start.isNull("presentTimestampNs"))
        assertFalse("Trace never includes image pixels", event.has("pixels"))
        assertEquals("end", end.getString("event"))
        assertFalse(end.getBoolean("writeFailed"))
        assertFalse("Diagnostics creates only its structured trace", directory.listFiles().orEmpty()
            .any { it.extension == "jpg" || it.name.startsWith("frame-") })
    }

    @Test
    fun traceAndFrameArchiveAreDisabledByDefaultAndRequireExplicitDebugIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("This debug instrumentation target must be debuggable",
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)

        ActivityScenario.launch(ProductActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val pipeline = forcePipeline(activity)
                assertNull(field(pipeline, "frameArchive"))
                assertNull(field(pipeline, "diagnostics"))
                assertFalse(field(pipeline, "trackingEnabled") as Boolean)
                val detector = requireNotNull(field(pipeline, "detector"))
                assertFalse(field(detector, "enhanced") as Boolean)
            }
        }

        val intent = Intent(context, ProductActivity::class.java)
            .putExtra("docscan.dbq.backend", "A")
            .putExtra("docscan.dbq.tracker", true)
            .putExtra("docscan.dbq.frames", true)
            .putExtra("docscan.dbq.trace", true)
        ActivityScenario.launch<ProductActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pipeline = forcePipeline(activity)
                assertNotNull(field(pipeline, "frameArchive"))
                assertNotNull(field(pipeline, "diagnostics"))
                assertTrue(field(pipeline, "trackingEnabled") as Boolean)
                val detector = requireNotNull(field(pipeline, "detector"))
                assertTrue(field(detector, "enhanced") as Boolean)
            }
        }
    }

    private fun input(frameId: Long, rgba: Mat, width: Int, height: Int) = DocumentBoundaryInput(
        identity = BoundaryFrameId(frameId, generationId = 1L, geometryId = 1L),
        spec = AnalysisBufferSpec(width, height, BufferRect(0, 0, width, height), 0),
        timing = DocumentFrameTiming(sensorTimestampNs = null, receivedTimestampNs = frameId + 1L),
        rgba = rgba,
    )

    private fun awaitFile(file: File): File {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (file.isFile) return file
            android.os.SystemClock.sleep(25L)
        }
        throw AssertionError("Timed out waiting for recorder output ${file.name}")
    }

    private fun forcePipeline(activity: ProductActivity): DocumentBoundaryPipeline {
        val delegate = ProductActivity::class.java.getDeclaredField("documentBoundaryPipelineDelegate")
            .apply { isAccessible = true }.get(activity) as Lazy<*>
        return requireNotNull(delegate.value) as DocumentBoundaryPipeline
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}
