package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class DocumentCropProcessorInstrumentedTest {
    private data class Fixture(
        val name: String,
        val width: Int,
        val height: Int,
        val corners: List<Pair<Float, Float>>,
    )

    @Test
    fun rectifiesStrongPageBoundaryWithoutChangingSource() {
        val source = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(30, 30, 30))
        val page = Path().apply {
            moveTo(130f, 100f)
            lineTo(850f, 155f)
            lineTo(790f, 1280f)
            lineTo(190f, 1320f)
            close()
        }
        canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val originalCorner = source.getPixel(0, 0)

        val result = DocumentCropProcessor.rectify(source)

        assertNotNull(result)
        assertEquals(originalCorner, source.getPixel(0, 0))
        assertTrue(requireNotNull(result).width >= 500)
        assertTrue(result.height >= 900)
        result.recycle()
        source.recycle()
    }

    @Test
    fun returnsNoCandidateForUniformImage() {
        val source = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val result = DocumentCropProcessor.rectify(source)

        assertNull(result)
        source.recycle()
    }

    @Test
    fun opencvDetectsNearFullFramePagesAndLongReceiptsInBothOrientations() {
        val fixtures = buildList {
            listOf(0.90, 0.95, 0.98).forEach { occupancy ->
                add(pageFixture("page-${(occupancy * 100).toInt()}pct", 1000, 1400, occupancy))
            }
            listOf(4, 8, 12).forEach { ratio ->
                add(receiptFixture("receipt-1to${ratio}-portrait", ratio, portrait = true))
                add(receiptFixture("receipt-1to${ratio}-landscape", ratio, portrait = false))
            }
            add(skewedFixture())
            add(nestedFixture())
        }

        val measurements = fixtures.map(::measureWithOpenCv)
        measurements.forEach { measurement ->
            Log.i(
                "DocScanCropNative",
                "fixture=${measurement.name} detected=${measurement.detected} score=${measurement.score} " +
                    "iou=${measurement.iou} maxCornerError=${measurement.maxCornerError} " +
                    "relativeCornerError=${measurement.relativeCornerError} " +
                    "output=${measurement.outputWidth}x${measurement.outputHeight}",
            )
        }

        val misses = measurements.filterNot { it.detected }
        assertTrue("OpenCV missed fixtures: ${misses.map { it.name }}", misses.isEmpty())
        measurements.forEach { measurement ->
            assertTrue("${measurement.name}: IoU=${measurement.iou}", measurement.iou >= 0.72)
            assertTrue("${measurement.name}: relative corner error=${measurement.relativeCornerError}", measurement.relativeCornerError <= 0.10)
        }
    }

    private data class Measurement(
        val name: String,
        val detected: Boolean,
        val score: Double,
        val iou: Double,
        val maxCornerError: Double,
        val relativeCornerError: Double,
        val outputWidth: Int,
        val outputHeight: Int,
    )

    private fun measureWithOpenCv(fixture: Fixture): Measurement {
        val source = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        drawFixture(source, fixture.corners)
        try {
            val detected = DocumentCropProcessor.detectCorners(source)
            if (detected == null) {
                return Measurement(fixture.name, false, 0.0, 0.0, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, 0, 0)
            }
            val actual = detected.toNormalized()
            val expected = quad(fixture.corners, fixture.width, fixture.height)
            val agreement = QuadGeometryUtils.checkAgreement(
                liveQuad = expected,
                stillQuad = actual,
                maxRelativeShiftTolerance = 1.0,
                minIouTolerance = 0.0,
            )
            val rectified = DocumentCropProcessor.rectifyManual(source, actual)
            try {
                return Measurement(
                    name = fixture.name,
                    detected = true,
                    score = detected.score,
                    iou = agreement.iou,
                    maxCornerError = agreement.maxCornerDistance * hypot(fixture.width.toDouble(), fixture.height.toDouble()),
                    relativeCornerError = agreement.relativeCornerShift,
                    outputWidth = rectified.width,
                    outputHeight = rectified.height,
                )
            } finally {
                rectified.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun pageFixture(name: String, width: Int, height: Int, occupancy: Double): Fixture {
        val scale = sqrt(occupancy)
        val marginX = width * (1.0 - scale) / 2.0
        val marginY = height * (1.0 - scale) / 2.0
        val inset = minOf(width, height) * 0.006
        return Fixture(name, width, height, listOf(
            (marginX + inset).toFloat() to (marginY + inset * 1.4).toFloat(),
            (width - marginX - inset * 1.3).toFloat() to (marginY + inset * 0.4).toFloat(),
            (width - marginX - inset * 0.4).toFloat() to (height - marginY - inset).toFloat(),
            (marginX + inset * 1.2).toFloat() to (height - marginY - inset * 0.3).toFloat(),
        ))
    }

    private fun receiptFixture(name: String, ratio: Int, portrait: Boolean): Fixture {
        val width = if (portrait) 900 else 2000
        val height = if (portrait) 2000 else 900
        val shortSide = minOf(minOf(width, height) * 0.66f, maxOf(width, height) * 0.72f / ratio)
        val longSide = shortSide * ratio
        val pageWidth = if (portrait) shortSide else longSide
        val pageHeight = if (portrait) longSide else shortSide
        val left = (width - pageWidth) / 2f
        val top = (height - pageHeight) / 2f
        val perspective = minOf(pageWidth, pageHeight) * 0.035f
        return Fixture(name, width, height, listOf(
            left + perspective to top,
            left + pageWidth - perspective * 0.6f to top + perspective * 0.4f,
            left + pageWidth to top + pageHeight,
            left to top + pageHeight - perspective * 0.6f,
        ))
    }

    private fun skewedFixture(): Fixture {
        val width = 1200
        val height = 1200
        return Fixture("page-skewed-45deg", width, height, listOf(
            420f to 80f, 1120f to 780f, 780f to 1120f, 80f to 420f,
        ))
    }

    private fun nestedFixture(): Fixture = Fixture(
        "page-on-nested-background", 1200, 1600,
        listOf(270f to 180f, 960f to 230f, 900f to 1430f, 310f to 1380f),
    )

    private fun drawFixture(bitmap: Bitmap, corners: List<Pair<Float, Float>>) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(55, 63, 69))
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(92, 105, 111); strokeWidth = 3f }
        for (x in 0 until bitmap.width step 47) canvas.drawLine(x.toFloat(), 0f, (x + 120).toFloat(), bitmap.height.toFloat(), background)
        // Nested colored panels exercise cases where a strong background contour surrounds the true sheet.
        if (bitmap.width >= 1200 && bitmap.height >= 1500) {
            canvas.drawRoundRect(100f, 100f, bitmap.width - 100f, bitmap.height - 100f, 18f, 18f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 106, 72) })
        }
        val page = Path().apply {
            moveTo(corners[0].first, corners[0].second)
            for (index in 1..3) lineTo(corners[index].first, corners[index].second)
            close()
        }
        canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 246, 238) })
        // Low-contrast print texture helps distinguish paper from a featureless white screen.
        val print = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 211, 202); strokeWidth = 2f }
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        for (line in -6..6) {
            val y = centerY + line * 28f
            canvas.drawLine(centerX - bitmap.width * 0.18f, y, centerX + bitmap.width * 0.18f, y, print)
        }
    }

    private fun quad(corners: List<Pair<Float, Float>>, width: Int, height: Int) = CropQuadrilateral(
        NormalizedCropPoint((corners[0].first / width).toDouble(), (corners[0].second / height).toDouble()),
        NormalizedCropPoint((corners[1].first / width).toDouble(), (corners[1].second / height).toDouble()),
        NormalizedCropPoint((corners[2].first / width).toDouble(), (corners[2].second / height).toDouble()),
        NormalizedCropPoint((corners[3].first / width).toDouble(), (corners[3].second / height).toDouble()),
    )
}
