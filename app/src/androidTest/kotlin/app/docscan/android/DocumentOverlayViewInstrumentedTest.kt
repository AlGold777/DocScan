package app.docscan.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DocumentOverlayViewInstrumentedTest {

    private val polygon = floatArrayOf(10f, 10f, 90f, 10f, 90f, 90f, 10f, 90f)

    @Test
    fun polygonExpiresOnMainLooperWithoutAnyFurtherAnalyzerFrames() {
        ActivityScenario.launch(DocumentOverlayTestActivity::class.java).use { scenario ->
            lateinit var overlay: DocumentOverlayView
            scenario.onActivity { activity ->
                overlay = attachOverlay(activity)
                overlay.setPolygonPixels(
                    polygon,
                    deadlineMonotonicMs = SystemClock.elapsedRealtime() + 250L,
                    isCurrent = { true },
                )
                assertTrue(drawMarkerPixel(overlay) != Color.TRANSPARENT)
            }

            // Let the UI looper process the overlay's expiry callback without feeding another frame.
            val looperAdvanced = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ looperAdvanced.countDown() }, 400L)
            assertTrue("Main looper did not reach the post-expiry marker", looperAdvanced.await(3, TimeUnit.SECONDS))

            scenario.onActivity {
                assertEquals(Color.TRANSPARENT, drawMarkerPixel(overlay))
            }
        }
    }

    @Test
    fun clearAndWindowDetachRemoveThePolygon() {
        ActivityScenario.launch(DocumentOverlayTestActivity::class.java).use { scenario ->
            lateinit var overlay: DocumentOverlayView
            scenario.onActivity { activity ->
                overlay = attachOverlay(activity)
                overlay.setPolygonPixels(
                    polygon,
                    deadlineMonotonicMs = SystemClock.elapsedRealtime() + 2_000L,
                    isCurrent = { true },
                )
                assertTrue(drawMarkerPixel(overlay) != Color.TRANSPARENT)

                overlay.clear()
                assertEquals(Color.TRANSPARENT, drawMarkerPixel(overlay))

                overlay.setPolygonPixels(
                    polygon,
                    deadlineMonotonicMs = SystemClock.elapsedRealtime() + 2_000L,
                    isCurrent = { true },
                )
                assertTrue(drawMarkerPixel(overlay) != Color.TRANSPARENT)
                activity.host.removeView(overlay)
                assertEquals(Color.TRANSPARENT, drawMarkerPixel(overlay))
            }
        }
    }

    @Test
    fun earlierPublicationExpiryCallbackCannotInvalidateNewerPolygon() {
        ActivityScenario.launch(DocumentOverlayTestActivity::class.java).use { scenario ->
            lateinit var overlay: DocumentOverlayView
            val currentPublication = AtomicInteger(1)
            val newPublicationChecks = AtomicInteger(0)
            scenario.onActivity { activity ->
                overlay = attachOverlay(activity)
                overlay.setPolygonPixels(
                    polygon,
                    deadlineMonotonicMs = SystemClock.elapsedRealtime() + 300L,
                    isCurrent = { currentPublication.get() == 1 },
                )
            }

            SystemClock.sleep(50L)
            scenario.onActivity {
                currentPublication.set(2)
                overlay.setPolygonPixels(
                    polygon,
                    deadlineMonotonicMs = SystemClock.elapsedRealtime() + 1_200L,
                    isCurrent = {
                        newPublicationChecks.incrementAndGet()
                        currentPublication.get() == 2
                    },
                )
            }
            val checksAfterPublication = newPublicationChecks.get()

            // This crosses the old deadline but remains well before the new publication's deadline.
            val looperAdvanced = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ looperAdvanced.countDown() }, 400L)
            assertTrue(looperAdvanced.await(3, TimeUnit.SECONDS))
            assertEquals("The old expiry callback must have been cancelled", checksAfterPublication,
                newPublicationChecks.get())

            scenario.onActivity {
                assertTrue(drawMarkerPixel(overlay) != Color.TRANSPARENT)
            }
        }
    }

    private fun attachOverlay(activity: DocumentOverlayTestActivity): DocumentOverlayView =
        DocumentOverlayView(activity).also { view ->
            activity.host.addView(view, FrameLayout.LayoutParams(100, 100))
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 100, 100)
        }

    private fun drawMarkerPixel(view: DocumentOverlayView): Int {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return bitmap.getPixel(50, 10)
        } finally {
            bitmap.recycle()
        }
    }
}

class DocumentOverlayTestActivity : Activity() {
    lateinit var host: FrameLayout
        private set

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}
