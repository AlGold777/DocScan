package app.docscan.android

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropSelectionViewInstrumentedTest {
    @Test
    fun draggingCornerMovesItsNormalizedPointAndRejectsCrossingGeometry() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            val canvas = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val view = CropSelectionView(context).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 400, 400)
                bitmap = source
            }
            try {
                view.draw(android.graphics.Canvas(canvas))
                val before = view.quadrilateral.topLeft
                dispatch(view, MotionEvent.ACTION_DOWN, 20f, 20f)
                dispatch(view, MotionEvent.ACTION_MOVE, 60f, 60f)
                dispatch(view, MotionEvent.ACTION_UP, 60f, 60f)
                assertTrue(view.quadrilateral.topLeft.x > before.x)
                assertTrue(view.quadrilateral.topLeft.y > before.y)

                val afterValidMove = view.quadrilateral
                dispatch(view, MotionEvent.ACTION_DOWN, 60f, 60f)
                dispatch(view, MotionEvent.ACTION_MOVE, 380f, 380f)
                dispatch(view, MotionEvent.ACTION_UP, 380f, 380f)
                assertTrue(view.quadrilateral == afterValidMove)
            } finally {
                view.bitmap = null
                source.recycle()
                canvas.recycle()
            }
        }
    }

    @Test
    fun draggingEdgeTranslatesBothEndpointsOfEdge() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            val canvas = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val view = CropSelectionView(context).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 400, 400)
                bitmap = source
            }
            try {
                view.draw(android.graphics.Canvas(canvas))
                val before = view.quadrilateral

                // Drag top edge (midpoint is at 200, 20) downwards to (200, 60)
                dispatch(view, MotionEvent.ACTION_DOWN, 200f, 20f)
                dispatch(view, MotionEvent.ACTION_MOVE, 200f, 60f)
                dispatch(view, MotionEvent.ACTION_UP, 200f, 60f)

                assertTrue("Top-left y should move down", view.quadrilateral.topLeft.y > before.topLeft.y)
                assertTrue("Top-right y should move down", view.quadrilateral.topRight.y > before.topRight.y)
                // Bottom corners must stay unchanged
                org.junit.Assert.assertEquals(before.bottomLeft, view.quadrilateral.bottomLeft)
                org.junit.Assert.assertEquals(before.bottomRight, view.quadrilateral.bottomRight)

                // Drag right edge (midpoint is around 380, 200) inwards to (340, 200)
                val beforeRightDrag = view.quadrilateral
                dispatch(view, MotionEvent.ACTION_DOWN, 380f, 200f)
                dispatch(view, MotionEvent.ACTION_MOVE, 340f, 200f)
                dispatch(view, MotionEvent.ACTION_UP, 340f, 200f)

                assertTrue("Top-right x should move left", view.quadrilateral.topRight.x < beforeRightDrag.topRight.x)
                assertTrue("Bottom-right x should move left", view.quadrilateral.bottomRight.x < beforeRightDrag.bottomRight.x)
                // Left corners must stay unchanged
                org.junit.Assert.assertEquals(beforeRightDrag.topLeft, view.quadrilateral.topLeft)
                org.junit.Assert.assertEquals(beforeRightDrag.bottomLeft, view.quadrilateral.bottomLeft)
            } finally {
                view.bitmap = null
                source.recycle()
                canvas.recycle()
            }
        }
    }

    private fun dispatch(view: CropSelectionView, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
