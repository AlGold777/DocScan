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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import org.json.JSONObject

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

    @Test
    fun boundaryDrawMarkersAreQueuedAfterApplyOnlyForActualMainLooperDraws() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "dbq-overlay-markers-${System.nanoTime()}")
        val output = File(directory, "trace.jsonl")
        val diagnostics = DocumentBoundaryDiagnostics(output)
        val publicationId = 87L
        val generationId = 23L
        val inputPoints = polygon.clone()
        val drawTimestamps = mutableListOf<Long>()
        var drawCallbackOnMain = false
        var applyTimestamp = 0L
        try {
            ActivityScenario.launch(ProductActivity::class.java).use { scenario ->
                lateinit var overlay: DocumentOverlayView
                scenario.onActivity { activity ->
                    overlay = attachOverlay(activity)
                    overlay.onBoundaryDraw = { publication, generation, screenPoints ->
                        drawCallbackOnMain = Looper.myLooper() === Looper.getMainLooper()
                        val eventTimeNs = SystemClock.elapsedRealtimeNanos()
                        drawTimestamps += eventTimeNs
                        diagnostics.recordMarker("DRAW", publication, generation, eventTimeNs, screenPoints)
                        // The callback owns a snapshot; mutation must not change the View's next draw.
                        screenPoints[0] = 999f
                    }
                    overlay.setPolygonPixels(
                        inputPoints,
                        deadlineMonotonicMs = SystemClock.elapsedRealtime() + 2_000L,
                        isCurrent = { true },
                        publicationId = publicationId,
                        generationId = generationId,
                    )
                    inputPoints[0] = 999f
                    applyTimestamp = SystemClock.elapsedRealtimeNanos()
                    diagnostics.recordMarker("UI_APPLY", publicationId, generationId, applyTimestamp, polygon)
                    assertTrue("A valid polygon is drawn after UI_APPLY", drawMarkerPixel(overlay) != Color.TRANSPARENT)
                    assertTrue("The actual draw callback runs on main", drawCallbackOnMain)
                    assertTrue("Callback geometry cannot mutate the View's stored polygon",
                        drawMarkerPixel(overlay) != Color.TRANSPARENT)
                    assertEquals(2, drawTimestamps.size)

                    overlay.setPolygonPixels(
                        polygon,
                        deadlineMonotonicMs = SystemClock.elapsedRealtime() - 1L,
                        isCurrent = { true },
                        publicationId = publicationId + 1L,
                        generationId = generationId,
                    )
                    assertEquals("An expired lease is cleared before drawing", Color.TRANSPARENT,
                        drawMarkerPixel(overlay))
                    assertEquals("An expired lease emits no DRAW marker", 2, drawTimestamps.size)
                }
            }
        } finally {
            diagnostics.close()
        }

        val lines = awaitTraceEnd(output)
        val events = lines.map(::JSONObject)
        val markers = events.filter { it.optString("event") in setOf("UI_APPLY", "DRAW") }
        assertEquals(listOf("UI_APPLY", "DRAW", "DRAW"), markers.map { it.getString("event") })
        assertEquals(listOf(publicationId, publicationId, publicationId),
            markers.map { it.getLong("publicationId") })
        assertEquals(listOf(generationId, generationId, generationId),
            markers.map { it.getLong("generationId") })
        assertEquals(applyTimestamp, markers.first().getLong("timestampNs"))
        assertEquals(drawTimestamps, markers.drop(1).map { it.getLong("timestampNs") })
        assertTrue(drawTimestamps.all { it >= applyTimestamp })
        assertTrue("Diagnostics snapshots the View's screen quad before callers can mutate it",
            markers.drop(1).all { it.getJSONArray("screenQuad").getDouble(0) == 10.0 })
        assertTrue(markers.all { it.isNull("presentTimestampNs") })
        assertTrue("Drawing is not evidence of physical presentation",
            events.none { it.optString("event") == "PRESENT" })
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

    private fun awaitTraceEnd(file: File): List<String> {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (file.isFile) {
                val lines = file.readLines()
                if (lines.any { it.contains("\"event\":\"end\"") }) return lines
            }
            SystemClock.sleep(25L)
        }
        throw AssertionError("Timed out waiting for diagnostics writer to close ${file.name}")
    }
}
