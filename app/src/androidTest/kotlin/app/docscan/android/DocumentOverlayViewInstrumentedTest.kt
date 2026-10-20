package app.docscan.android

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses the production Activity window, so no test-only Activity/process is needed. */
@RunWith(AndroidJUnit4::class)
class DocumentOverlayViewInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val polygon = floatArrayOf(10f, 10f, 90f, 10f, 90f, 90f, 10f, 90f)

    @Test
    fun polygonExpiresOnMainLooperWithoutAnyFurtherAnalyzerFrames() {
        ActivityScenario.launch(ProductActivity::class.java).use { scenario ->
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

            // Only the main looper advances. No analyzer frame is submitted during the wait.
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
        ActivityScenario.launch(ProductActivity::class.java).use { scenario ->
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
                (overlay.parent as ViewGroup).removeView(overlay)
                assertEquals(Color.TRANSPARENT, drawMarkerPixel(overlay))
            }
        }
    }

    @Test
    fun earlierPublicationExpiryCallbackCannotInvalidateNewerPolygon() {
        ActivityScenario.launch(ProductActivity::class.java).use { scenario ->
            lateinit var overlay: DocumentOverlayView
            val currentPublication = AtomicInteger(1)
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
                        currentPublication.get() == 2
                    },
                )
            }

            // Cross the old deadline while staying well before the new lease deadline.
            val looperAdvanced = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ looperAdvanced.countDown() }, 400L)
            assertTrue(looperAdvanced.await(3, TimeUnit.SECONDS))

            scenario.onActivity {
                assertTrue(drawMarkerPixel(overlay) != Color.TRANSPARENT)
            }
        }
    }

    private fun attachOverlay(activity: ProductActivity): DocumentOverlayView =
        DocumentOverlayView(activity).also { view ->
            activity.addContentView(view, FrameLayout.LayoutParams(100, 100))
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
