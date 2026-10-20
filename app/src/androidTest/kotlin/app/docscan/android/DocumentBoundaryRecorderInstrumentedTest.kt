package app.docscan.android

import android.Manifest
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
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
                DocumentFrameTiming(
                    sensorTimestampNs = null,
                    receivedTimestampNs = 41_000_000L,
                    preparedTimestampNs = 41_000_010L,
                ), inputMat,
            )
            val result = DocumentBoundaryResult(
                identity = input.identity,
                detectorId = "recorder-test",
                configVersion = "v1",
                outcome = BoundaryOutcome.FOUND,
                candidates = emptyList(),
                detectorStartedNs = 41_000_011L,
                detectorFinishedNs = 41_000_012L,
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
        assertEquals(41_000_010L, event.getLong("preparedNs"))
        assertEquals(2.0, event.getJSONArray("displayBufferQuad").getDouble(0), 0.0)
        assertTrue("Unknown presentation timing remains explicit", start.isNull("presentTimestampNs"))
        assertFalse("Trace never includes image pixels", event.has("pixels"))
        assertEquals("end", end.getString("event"))
        assertFalse(end.getBoolean("writeFailed"))
        assertFalse("Diagnostics creates only its structured trace", directory.listFiles().orEmpty()
            .any { it.extension == "jpg" || it.name.startsWith("frame-") })
    }

    @Test
    fun uiApplyAndDrawMarkersKeepMainEventTimesOrderAndOwnedScreenGeometry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "dbq-ui-markers-${System.nanoTime()}")
        val output = File(directory, "trace.jsonl")
        val diagnostics = DocumentBoundaryDiagnostics(output)
        val applyPoints = floatArrayOf(10f, 11f, 91f, 12f, 89f, 90f, 9f, 88f)
        val drawPoints = floatArrayOf(12f, 13f, 92f, 14f, 90f, 91f, 11f, 89f)
        var applyTimestampNs = 0L
        var drawTimestampNs = 0L
        try {
            instrumentation.runOnMainSync {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                applyTimestampNs = SystemClock.elapsedRealtimeNanos()
                diagnostics.recordMarker("UI_APPLY", 55L, 12L, applyTimestampNs, applyPoints)
            }
            applyPoints[0] = 999f

            instrumentation.runOnMainSync {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                drawTimestampNs = SystemClock.elapsedRealtimeNanos()
                diagnostics.recordMarker("DRAW", 55L, 12L, drawTimestampNs, drawPoints)
            }
            drawPoints[0] = 999f
        } finally {
            diagnostics.close()
        }

        val lines = awaitTraceEnd(output)
        val events = lines.map(::JSONObject)
        val markers = events.filter { it.optString("event") in setOf("UI_APPLY", "DRAW") }
        assertEquals(listOf("UI_APPLY", "DRAW"), markers.map { it.getString("event") })
        assertEquals(listOf(55L, 55L), markers.map { it.getLong("publicationId") })
        assertEquals(listOf(12L, 12L), markers.map { it.getLong("generationId") })
        assertEquals(applyTimestampNs, markers[0].getLong("timestampNs"))
        assertEquals(drawTimestampNs, markers[1].getLong("timestampNs"))
        assertTrue("The queued draw marker follows the UI apply event", drawTimestampNs >= applyTimestampNs)
        assertEquals(10.0, markers[0].getJSONArray("screenQuad").getDouble(0), 0.0)
        assertEquals(12.0, markers[1].getJSONArray("screenQuad").getDouble(0), 0.0)
        assertTrue(markers.all { it.isNull("presentTimestampNs") })
        assertFalse("A UI draw marker is not a display presentation event",
            events.any { it.optString("event") == "PRESENT" })
    }

    @Test
    fun traceSerializesMissingTimesAsNullAndPreservesValidZeroTimestamps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dbq-null-vs-zero-${System.nanoTime()}")
        val output = File(directory, "trace.jsonl")
        val diagnostics = DocumentBoundaryDiagnostics(output)
        val spec = AnalysisBufferSpec(32, 24, BufferRect(0, 0, 32, 24), 0)
        val rgba = Mat()
        try {
            val idleInput = DocumentBoundaryInput(
                BoundaryFrameId(1L, 2L, 1L), spec,
                DocumentFrameTiming(sensorTimestampNs = null, receivedTimestampNs = 0L), rgba,
            )
            val idleResult = DocumentBoundaryResult(
                idleInput.identity, "trace-test", "v1", BoundaryOutcome.NO_DOCUMENT,
                emptyList(), detectorStartedNs = 0L, detectorFinishedNs = 0L,
            )
            val idle = LiveDetectionState(
                generationId = 2L,
                status = LiveDetectionStatus.IDLE,
                analysisSpec = null,
                quadInBufferPixels = null,
                quadInNormalizedViewPort = null,
                rawScore = 0.0,
                frameTimestampMonotonicMs = 0L,
                processedTimestampMonotonicMs = 0L,
                publicationId = 0L,
                lastDetectionTimestampMonotonicMs = 0L,
            )
            diagnostics.record(idleInput, idleResult, idle)

            val foundInput = DocumentBoundaryInput(
                BoundaryFrameId(2L, 3L, 1L), spec,
                DocumentFrameTiming(sensorTimestampNs = null, receivedTimestampNs = 0L), rgba,
            )
            val foundResult = DocumentBoundaryResult(
                foundInput.identity, "trace-test", "v1", BoundaryOutcome.FOUND,
                emptyList(), detectorStartedNs = 0L, detectorFinishedNs = 0L,
            )
            val validAtZero = LiveDetectionState(
                generationId = 3L,
                status = LiveDetectionStatus.CONFIRMED,
                analysisSpec = spec,
                quadInBufferPixels = floatArrayOf(1f, 1f, 30f, 1f, 30f, 22f, 1f, 22f),
                quadInNormalizedViewPort = null,
                rawScore = 1.0,
                frameTimestampMonotonicMs = 0L,
                processedTimestampMonotonicMs = 0L,
                publicationId = 1L,
                lastDetectionTimestampMonotonicMs = 0L,
            )
            diagnostics.record(foundInput, foundResult, validAtZero)
        } finally {
            rgba.release()
            diagnostics.close()
        }

        val rows = awaitTraceEnd(output).map(::JSONObject).filter { it.optString("event") == "result" }
        assertEquals(2, rows.size)
        val idle = rows[0]
        assertEquals(0L, idle.getLong("publicationId"))
        assertTrue(idle.isNull("processedMs"))
        assertTrue(idle.isNull("lastDetectionMs"))
        assertTrue(idle.isNull("geometryMs"))
        assertTrue(idle.isNull("displayBufferQuad"))

        val valid = rows[1]
        assertEquals(1L, valid.getLong("publicationId"))
        assertEquals(0L, valid.getLong("processedMs"))
        assertEquals(0L, valid.getLong("lastDetectionMs"))
        assertEquals(0L, valid.getLong("geometryMs"))
        assertFalse(valid.isNull("displayBufferQuad"))
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

    private fun awaitTraceEnd(file: File): List<String> {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (file.isFile) {
                val lines = file.readLines()
                if (lines.any { it.contains("\"event\":\"end\"") }) return lines
            }
            android.os.SystemClock.sleep(25L)
        }
        throw AssertionError("Timed out waiting for diagnostics writer to close ${file.name}")
    }

    private fun forcePipeline(activity: ProductActivity): DocumentBoundaryPipeline {
        val delegate = ProductActivity::class.java.getDeclaredField("documentBoundaryPipelineDelegate")
            .apply { isAccessible = true }.get(activity) as Lazy<*>
        return requireNotNull(delegate.value) as DocumentBoundaryPipeline
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}
