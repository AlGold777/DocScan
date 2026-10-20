package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class DocumentBoundaryNativeInstrumentedTest {
    private val spec = AnalysisBufferSpec(480, 360, BufferRect(0, 0, 480, 360), 0)

    @Test
    fun supportedEdgesRefineWithinBoundAndBlankImageRollsBackExactly() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        val gray = Mat(360, 480, CvType.CV_8UC1, Scalar(28.0))
        val blank = Mat(360, 480, CvType.CV_8UC1, Scalar(28.0))
        val original = quad(84.0, 74.0, 396.0, 286.0)
        try {
            Imgproc.rectangle(gray, Rect(80, 70, 321, 221), Scalar(242.0), Imgproc.FILLED)
            val refined = DocumentBoundaryRefiner.refine(gray, original, radius = 8)
            assertEquals(BoundaryRefinement.REFINED, refined.status)
            assertTrue(refined.edges.all { (it.coverage ?: 0.0) >= 0.60 })
            assertTrue(refined.edges.all { (it.occupiedBins ?: 0) >= 6 && it.residualPx != null })
            refined.quad.points.zip(quad(80.0, 70.0, 400.0, 290.0).points).forEach { (actual, expected) ->
                assertTrue("Refined edge should follow supported pixels: actual=$actual expected=$expected",
                    hypot(actual.x - expected.x, actual.y - expected.y) <= 2.5)
            }

            val rollback = DocumentBoundaryRefiner.refine(blank, original, radius = 8)
            assertEquals(BoundaryRefinement.ORIGINAL, rollback.status)
            assertEquals("Without enough edge support, return the exact coarse quad", original, rollback.quad)
        } finally {
            blank.release()
            gray.release()
        }
    }

    @Test
    fun opticalFlowAcceptsTexturedTranslationAndDropsBlankLowCoverageOrStaleFrames() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        val source = texturedPage()
        val translated = Mat()
        val affine = Mat(2, 3, CvType.CV_64F)
        val tracker = DocumentBoundaryTracker()
        val q = quad(50.0, 40.0, 430.0, 320.0)
        try {
            affine.put(0, 0, 1.0, 0.0, 5.0, 0.0, 1.0, 4.0)
            Imgproc.warpAffine(source, translated, affine, source.size(), Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT, Scalar(18.0))
            tracker.seed(source, q, spec, 1_000L)
            val tracked = tracker.track(translated, spec, 1_050L)
            assertNotNull("Feature-supported translation should produce a track", tracked)
            val result = requireNotNull(tracked)
            assertTrue("Enough LK inliers are required", result.inliers >= 12)
            assertTrue("Features must cover a meaningful portion of the page", result.coverage >= 0.15)
            result.quad.points.zip(q.points).forEach { (actual, expected) ->
                assertTrue("Tracked x should include the synthetic translation", abs(actual.x - expected.x - 5.0) <= 1.5)
                assertTrue("Tracked y should include the synthetic translation", abs(actual.y - expected.y - 4.0) <= 1.5)
            }

            tracker.seed(source, q, spec, 2_000L)
            val blank = Mat(360, 480, CvType.CV_8UC1, Scalar(18.0))
            try {
                assertEquals("Blank frames provide no stable feature field", null, tracker.track(blank, spec, 2_050L))
            } finally { blank.release() }

            tracker.seed(source, q, spec, 3_000L)
            assertEquals("A gap outside the 199ms tracking budget drops the old frame", null,
                tracker.track(translated, spec, 3_200L))

            val featureless = Mat(360, 480, CvType.CV_8UC1, Scalar(18.0))
            try {
                tracker.seed(featureless, q, spec, 4_000L)
                assertEquals("Seed rejects insufficient feature coverage", null, tracker.track(source, spec, 4_020L))
            } finally { featureless.release() }
        } finally {
            tracker.close()
            affine.release()
            translated.release()
            source.release()
        }
    }

    @Test
    fun firstConfirmedCandidateSeedsFlowAndProvisionalReplacementCannotSwitchToAnotherPage() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        var processingMs = 1_000L
        val coordinator = DocumentLiveDetectionCoordinator(clock = { processingMs })
        val pageA = quad(34.0, 28.0, 220.0, 330.0)
        val pageB = quad(260.0, 28.0, 450.0, 330.0)
        var nextCandidate = pageA
        val detector = object : DocumentBoundaryDetector {
            override val detectorId = "seed-switch-regression"
            override val configVersion = "test-v1"
            override fun detect(input: DocumentBoundaryInput): DocumentBoundaryResult {
                val hypothesis = DocumentHypothesis(
                    candidateId = 1,
                    quad = nextCandidate,
                    rawScore = 0.95,
                    visibility = BoundaryVisibility.FULL,
                    source = detectorId,
                )
                return DocumentBoundaryResult(
                    identity = input.identity,
                    detectorId = detectorId,
                    configVersion = configVersion,
                    outcome = BoundaryOutcome.FOUND,
                    candidates = listOf(hypothesis),
                    detectorStartedNs = input.timing.receivedTimestampNs,
                    detectorFinishedNs = input.timing.receivedTimestampNs + 1L,
                )
            }
            override fun close() = Unit
        }
        val pipeline = DocumentBoundaryPipeline(coordinator, detector, trackingEnabled = true,
            clockMs = { processingMs })
        val rgba = Mat(360, 480, CvType.CV_8UC4, Scalar(18.0, 18.0, 18.0, 255.0))
        val random = Random(782_151L)
        for (top in 42 until 315 step 15) for (left in 42 until 440 step 15) {
            val shade = (45 + random.nextInt(205)).toDouble()
            Imgproc.rectangle(rgba, Rect(left, top, 6, 7), Scalar(shade, shade, shade, 255.0), Imgproc.FILLED)
        }
        fun frame(id: Long, timestampMs: Long): DocumentBoundaryInput = DocumentBoundaryInput(
            identity = BoundaryFrameId(id, generationId = 1L, geometryId = 1L),
            spec = spec,
            timing = DocumentFrameTiming(sensorTimestampNs = null, receivedTimestampNs = timestampMs * 1_000_000L),
            rgba = rgba,
        )
        fun assertNearPageA(state: LiveDetectionState, label: String) {
            val points = requireNotNull(state.quadInBufferPixels)
            val a = pageA.toFloatArray()
            val b = pageB.toFloatArray()
            val errorA = points.indices.sumOf { abs(points[it] - (a[it] + 0.5f)).toDouble() } / points.size
            val errorB = points.indices.sumOf { abs(points[it] - (b[it] + 0.5f)).toDouble() } / points.size
            assertTrue("$label must retain confirmed page A (errorA=$errorA errorB=$errorB)", errorA < 8.0 && errorB > 80.0)
        }
        try {
            processingMs = 1_002L
            val first = pipeline.process(frame(1L, 1_000L))
            assertEquals("Flow cannot confirm its first detector observation", LiveDetectionStatus.PROVISIONAL, first.status)

            processingMs = 1_102L
            val confirmedA = pipeline.process(frame(2L, 1_100L))
            assertEquals(LiveDetectionStatus.CONFIRMED, confirmedA.status)
            assertNearPageA(confirmedA, "Confirmed detector result")

            nextCandidate = pageB
            processingMs = 1_202L
            val provisionalB = pipeline.process(frame(3L, 1_200L))
            assertEquals("A disagreeing first B observation is held behind A", LiveDetectionStatus.HELD, provisionalB.status)
            assertNearPageA(provisionalB, "Provisional B result")

            processingMs = 1_252L
            val flowOnly = pipeline.process(frame(4L, 1_250L))
            assertEquals("The next flow-only frame cannot switch to provisional B", LiveDetectionStatus.HELD, flowOnly.status)
            assertNearPageA(flowOnly, "Flow-only result")
        } finally {
            pipeline.close()
            rgba.release()
        }
    }

    @Test
    fun stillRefinementUsesExifOrientedCoordinatesAndLeavesOriginalJpegUntouched() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "dbq-still-exif-${System.nanoTime()}.jpg")
        val raw = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(raw).apply {
                drawColor(Color.rgb(30, 35, 40))
                drawRect(50f, 40f, 550f, 360f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            }
            file.outputStream().use { output -> assertTrue(raw.compress(Bitmap.CompressFormat.JPEG, 98, output)) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val sourceBytes = file.readBytes()
            val oriented = ProductImageDecoder.decodePrecisely(file, 2L * 1024 * 1024, maxSide = 1600)
            try {
                assertEquals("EXIF 90 rotates width into height", 400, oriented.width)
                assertEquals("EXIF 90 rotates height into width", 600, oriented.height)
            } finally { oriented.recycle() }

            val coarse = CropQuadrilateral(
                NormalizedCropPoint(40.0 / 399.0, 50.0 / 599.0),
                NormalizedCropPoint(360.0 / 399.0, 50.0 / 599.0),
                NormalizedCropPoint(360.0 / 399.0, 550.0 / 599.0),
                NormalizedCropPoint(40.0 / 399.0, 550.0 / 599.0),
            )
            val refined = StillBoundaryProcessor.refine(file, coarse)
            assertEquals("Still processing must not rewrite the captured JPEG", sourceBytes.toList(), file.readBytes().toList())
            refined.asList().zip(coarse.asList()).forEach { (actual, expected) ->
                assertTrue("EXIF-oriented still quad remains in the expected portrait space",
                    abs(actual.x - expected.x) <= 0.025 && abs(actual.y - expected.y) <= 0.025)
            }
            assertEquals(32L * 1024 * 1024, StillBoundaryProcessor.WORKING_SET_BYTES)
        } finally {
            raw.recycle()
            file.delete()
        }
    }

    private fun texturedPage(): Mat {
        val image = Mat(360, 480, CvType.CV_8UC1, Scalar(18.0))
        val random = Random(713_031L)
        for (top in 52 until 310 step 16) {
            for (left in 62 until 420 step 17) {
                val color = (45 + random.nextInt(205)).toDouble()
                val width = 5 + random.nextInt(7)
                val height = 5 + random.nextInt(7)
                Imgproc.rectangle(image, Rect(left, top, width, height), Scalar(color), Imgproc.FILLED)
            }
        }
        return image
    }

    private fun quad(left: Double, top: Double, right: Double, bottom: Double) = BoundaryQuad(
        BoundaryPoint(left, top), BoundaryPoint(right, top), BoundaryPoint(right, bottom), BoundaryPoint(left, bottom),
    )
}
