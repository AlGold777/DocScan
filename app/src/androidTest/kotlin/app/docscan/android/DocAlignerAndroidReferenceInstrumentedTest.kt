package app.docscan.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.Closeable

/** Instrumentation parity against host FP32 CPU run; never a camera quality gate. */
@RunWith(AndroidJUnit4::class)
class DocAlignerAndroidReferenceInstrumentedTest {

    @Before
    fun requireOptionalReferenceAssets() {
        val required = listOf(
            "models/lc100.onnx", "models/t8.onnx",
            "docaligner_run_test_card.png", "negative_white.png", "negative_black.png",
            "negative_uniform_gray.png", "negative_low_contrast_noise.png",
            "negative_checker_clutter.png", "negative_graphic_panel.png", "synthetic_document.png",
        )
        val assets = instrumentationContext().assets
        val missing = required.filter { name ->
            runCatching { assets.open(name).use { } }.isFailure
        }
        assumeTrue("Fetch optional DocAligner reference assets first; missing: $missing", missing.isEmpty())
    }

    private data class LoadedMat(val mat: Mat, val width: Int, val height: Int) : Closeable {
        override fun close() = mat.release()
    }

    private data class Run(val raw: DocAlignerAndroidTestAdapter.RawReference,
                           val result: DocumentBoundaryResult)

    @Test
    fun upstreamCardMatchesPinnedHostCornersAndCannotClaimFound() {
        assertTrue("OpenCV Android native library must load", OpenCVLoader.initLocal())
        val expected = mapOf(
            DocAlignerAndroidTestAdapter.Model.LC100 to listOf(
                47.1987608889 to 223.2925990884,
                387.1165100209 to 198.0532896779,
                423.4226855013 to 345.6081579118,
                40.4768732285 to 361.3888288091,
            ),
            DocAlignerAndroidTestAdapter.Model.T8 to listOf(
                49.9575748014 to 223.6944135816,
                387.1555049372 to 198.4516612004,
                423.5801911235 to 345.8444163450,
                40.3199731446 to 361.2965064588,
            ),
        )
        for ((model, points) in expected) {
            val run = runModel(model, "docaligner_run_test_card.png")
            assertEquals("output names must pin absence of classification", setOf("heatmap"), run.raw.outputNames)
            assertEquals(listOf(1L, 3L, 256L, 256L), run.raw.inputShape.toList())
            assertEquals(listOf(1L, 4L, 128L, 128L), run.raw.heatmapShape.toList())
            assertCorners(points, run.raw.channelCornersXY, tolerancePx = 3.0)
            assertEquals(BoundaryOutcome.AMBIGUOUS, run.result.outcome)
            assertEquals("no model presence score", null, run.result.candidates.single().documentPresence)
        }
    }

    @Test
    fun featurelessNegativesDoNotCreateHeatmapCentroidsButRemainAmbiguous() {
        assertTrue(OpenCVLoader.initLocal())
        val names = listOf(
            "negative_white.png", "negative_black.png", "negative_uniform_gray.png",
            "negative_low_contrast_noise.png", "negative_checker_clutter.png",
        )
        for (model in DocAlignerAndroidTestAdapter.Model.entries) {
            for (name in names) {
                val run = runModel(model, name)
                assertTrue("$model $name: ${run.raw.channelCornersXY}", run.raw.channelCornersXY.all { it == null })
                assertTrue("heatmap-only graph cannot prove NO_DOCUMENT", run.result.outcome == BoundaryOutcome.AMBIGUOUS)
                assertTrue(run.result.candidates.isEmpty())
            }
        }
    }

    @Test
    fun graphicPanelDemonstratesFalseLockRiskWithoutPresenceGate() {
        assertTrue(OpenCVLoader.initLocal())
        val expected = listOf(
            120.9677579876 to 37.2225953929,
            531.2148502946 to 86.4328728669,
            496.9694637464 to 423.6012031309,
            97.3560715914 to 386.5349353322,
        )
        val lc100 = runModel(DocAlignerAndroidTestAdapter.Model.LC100, "negative_graphic_panel.png")
        assertCorners(expected, lc100.raw.channelCornersXY, tolerancePx = 4.0)
        assertEquals(BoundaryOutcome.AMBIGUOUS, lc100.result.outcome)
        assertEquals(1, lc100.result.candidates.size)
        assertNull(lc100.result.candidates.single().documentPresence)

        val t8 = runModel(DocAlignerAndroidTestAdapter.Model.T8, "negative_graphic_panel.png")
        assertEquals(4, t8.raw.channelCornersXY.count { it != null })
        assertEquals(BoundaryOutcome.AMBIGUOUS, t8.result.outcome)
    }

    @Test
    fun simpleSyntheticPaperSeparatesTheTwoPinnedCandidates() {
        assertTrue(OpenCVLoader.initLocal())
        val lc100 = runModel(DocAlignerAndroidTestAdapter.Model.LC100, "synthetic_document.png")
        assertEquals(4, lc100.raw.channelCornersXY.count { it != null })
        assertEquals(BoundaryOutcome.AMBIGUOUS, lc100.result.outcome)
        val t8 = runModel(DocAlignerAndroidTestAdapter.Model.T8, "synthetic_document.png")
        assertTrue(t8.raw.channelCornersXY.all { it == null })
        assertEquals(BoundaryOutcome.AMBIGUOUS, t8.result.outcome)
    }

    @Test
    fun viewportCropIsResizedAndCornersReturnInFullBufferCoordinates() {
        assertTrue(OpenCVLoader.initLocal())
        val crop = BufferRect(20, 15, 620, 465)
        val expected = mapOf(
            DocAlignerAndroidTestAdapter.Model.LC100 to listOf(
                124.9344243181 to 40.3163322650,
                531.0639795417 to 87.0995741672,
                496.9672394802 to 423.7144093900,
                97.6457513968 to 386.2705848532,
            ),
            DocAlignerAndroidTestAdapter.Model.T8 to listOf(
                123.2424222021 to 42.4137016980,
                527.7433331238 to 90.9300836465,
                492.4548638677 to 416.5868717045,
                96.8197644644 to 381.6175568494,
            ),
        )
        for ((model, points) in expected) {
            val run = runModel(model, "negative_graphic_panel.png", crop)
            assertCorners(points, run.raw.channelCornersXY, tolerancePx = 4.0)
            assertEquals(BoundaryOutcome.AMBIGUOUS, run.result.outcome)
        }
    }

    private fun runModel(
        model: DocAlignerAndroidTestAdapter.Model,
        assetName: String,
        viewport: BufferRect? = null,
    ): Run {
        val image = loadRgba(assetName)
        image.use {
            val spec = AnalysisBufferSpec(
                bufferWidth = image.width,
                bufferHeight = image.height,
                cropRect = viewport ?: BufferRect(0, 0, image.width, image.height),
                rotationDegrees = 0,
            )
            val identity = BoundaryFrameId(1, 1, 1)
            val input = DocumentBoundaryInput(
                identity = identity,
                spec = spec,
                timing = DocumentFrameTiming(null, SystemClock.elapsedRealtimeNanos()),
                rgba = image.mat,
            )
            DocAlignerAndroidTestAdapter(model, instrumentationContext().assets).use { adapter ->
                val raw = adapter.inferRaw(input)
                android.util.Log.i(
                    "DBQ_MODEL_RAW",
                    "model=${model.name} asset=$assetName crop=${input.spec.cropRect} " +
                        "input=${raw.inputShape.contentToString()} heatmap=${raw.heatmapShape.contentToString()} " +
                        "outputs=${raw.outputNames.sorted()} corners=${raw.channelCornersXY}",
                )
                val result = adapter.detect(input)
                assertEquals(identity, result.identity)
                assertTrue(result.reason.orEmpty().contains("no document-presence head"))
                return Run(raw, result)
            }
        }
    }

    private fun loadRgba(name: String): LoadedMat {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = instrumentationContext().assets.open(name).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("Unable to decode androidTest asset $name")
        val mat = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat) // Android OpenCV produces RGBA CV_8UC4.
            assertEquals(4, mat.channels())
            return LoadedMat(mat, bitmap.width, bitmap.height)
        } catch (t: Throwable) {
            mat.release()
            throw t
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertCorners(expected: List<Pair<Double, Double>>,
                              actual: List<Pair<Double, Double>?>,
                              tolerancePx: Double) {
        assertEquals("one centroid slot per heatmap channel", 4, actual.size)
        for (i in expected.indices) {
            val got = actual[i]
            assertNotNull("corner $i missing", got)
            assertEquals("corner $i x", expected[i].first, got!!.first, tolerancePx)
            assertEquals("corner $i y", expected[i].second, got.second, tolerancePx)
        }
    }

    private fun instrumentationContext() = InstrumentationRegistry.getInstrumentation().context
}
