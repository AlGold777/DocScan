package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentQuadFilterTest {
    @Test
    fun `time EMA aligns cyclic corner numbering before filtering motion`() {
        val filter = DocumentQuadFilter(DocumentQuadFilterMode.TIME_EMA)
        val first = quad(20f, 30f, 180f, 160f)
        val translated = quad(40f, 40f, 200f, 170f)
        filter.update(first, 1_000L)

        // Same polygon with a different cyclic start; this must not collapse opposite corners.
        val cyclicTranslation = BoundaryQuad.from(floatArrayOf(200f, 40f, 200f, 170f, 40f, 170f, 40f, 40f))
        val filtered = filter.update(cyclicTranslation, 1_100L)

        assertTrue(filtered.points.zip(translated.points).all { (actual, expected) ->
            kotlin.math.abs(actual.x - expected.x) < 15.0 && kotlin.math.abs(actual.y - expected.y) < 15.0
        })
        assertTrue("The filter must return a valid convex quad", filtered.inside(BufferRect(0, 0, 240, 220)))
    }

    @Test
    fun `reset and discontinuous time restart from the current measurement`() {
        val filter = DocumentQuadFilter(DocumentQuadFilterMode.ONE_EURO)
        val first = quad(20f, 30f, 180f, 160f)
        val moved = quad(40f, 40f, 200f, 170f)
        filter.update(first, 100L)
        val smoothed = filter.update(moved, 200L)
        assertTrue(smoothed.a.x > first.a.x && smoothed.a.x < moved.a.x)

        val afterGap = quad(60f, 50f, 220f, 180f)
        assertEquals(afterGap, filter.update(afterGap, 501L))
        filter.reset()
        assertEquals(first, filter.update(first, 10_000L))
    }

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) = BoundaryQuad(
        BoundaryPoint(left.toDouble(), top.toDouble()), BoundaryPoint(right.toDouble(), top.toDouble()),
        BoundaryPoint(right.toDouble(), bottom.toDouble()), BoundaryPoint(left.toDouble(), bottom.toDouble()),
    )
}
