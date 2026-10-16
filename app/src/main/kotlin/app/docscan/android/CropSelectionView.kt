package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.ViewCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.min

/** Fits one immutable preview and permits dragging four ordered convex crop corners. */
class CropSelectionView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var quadrilateral = defaultQuadrilateral()
        private set

    private var activeCorner = -1
    private val handleRadius = 24f * resources.displayMetrics.density
    private val imageBounds = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val polygonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff6750a4.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff6750a4.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (bitmap == null || imageBounds.isEmpty) return INVALID_ID
            return quadrilateral.asList().mapIndexed { index, point ->
                val center = viewPoint(point)
                index to hypot((center.first - x).toDouble(), (center.second - y).toDouble())
            }.minByOrNull { it.second }
                ?.takeIf { it.second <= handleRadius.toDouble() }
                ?.first ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (bitmap != null && !imageBounds.isEmpty) virtualViewIds.addAll(CORNER_IDS)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val label = cornerLabel(virtualViewId)
            node.className = "android.view.View"
            node.contentDescription = context.getString(label)
            node.isEnabled = bitmap != null
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                ACTION_MOVE_LEFT, context.getString(R.string.manual_crop_move_left),
            ))
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                ACTION_MOVE_RIGHT, context.getString(R.string.manual_crop_move_right),
            ))
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                ACTION_MOVE_UP, context.getString(R.string.manual_crop_move_up),
            ))
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                ACTION_MOVE_DOWN, context.getString(R.string.manual_crop_move_down),
            ))
            val center = viewPoint(quadrilateral.asList()[virtualViewId])
            val targetSize = ceil(48f * resources.displayMetrics.density).toInt()
            val left = (center.first - targetSize / 2f).roundToInt().coerceIn(0, (width - targetSize).coerceAtLeast(0))
            val top = (center.second - targetSize / 2f).roundToInt().coerceIn(0, (height - targetSize).coerceAtLeast(0))
            node.setBoundsInParent(Rect(
                left, top, left + targetSize, top + targetSize,
            ))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (bitmap == null || virtualViewId !in CORNER_IDS) return false
            val (dx, dy) = when (action) {
                ACTION_MOVE_LEFT -> -ACCESSIBLE_NUDGE to 0.0
                ACTION_MOVE_RIGHT -> ACCESSIBLE_NUDGE to 0.0
                ACTION_MOVE_UP -> 0.0 to -ACCESSIBLE_NUDGE
                ACTION_MOVE_DOWN -> 0.0 to ACCESSIBLE_NUDGE
                else -> return false
            }
            val points = quadrilateral.asList().toMutableList()
            val current = points[virtualViewId]
            val candidate = runCatching {
                NormalizedCropPoint(current.x + dx, current.y + dy)
            }.getOrNull() ?: return false
            points[virtualViewId] = candidate
            val updated = runCatching { fromPoints(points) }.getOrNull() ?: return false
            quadrilateral = updated
            invalidate()
            invalidateVirtualView(virtualViewId)
            return true
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val shownWidth = source.width * scale
        val shownHeight = source.height * scale
        imageBounds.set(
            (width - shownWidth) / 2f,
            (height - shownHeight) / 2f,
            (width + shownWidth) / 2f,
            (height + shownHeight) / 2f,
        )
        canvas.drawBitmap(source, null, imageBounds, bitmapPaint)
        val points = quadrilateral.asList().map(::viewPoint)
        val path = android.graphics.Path().apply {
            moveTo(points[0].first, points[0].second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
            close()
        }
        canvas.drawPath(path, polygonPaint)
        points.forEach { (x, y) ->
            canvas.drawCircle(x, y, handleRadius, handleFill)
            canvas.drawCircle(x, y, handleRadius, handleStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        if (imageBounds.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearest = quadrilateral.asList().mapIndexed { index, point ->
                    val center = viewPoint(point)
                    index to hypot((center.first - event.x).toDouble(), (center.second - event.y).toDouble())
                }.minByOrNull { it.second } ?: return false
                if (nearest.second > (handleRadius * 2).toDouble()) return false
                activeCorner = nearest.first
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner < 0) return false
                val normalized = NormalizedCropPoint(
                    ((event.x - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f).toDouble(),
                    ((event.y - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f).toDouble(),
                )
                val points = quadrilateral.asList().toMutableList()
                points[activeCorner] = normalized
                runCatching { fromPoints(points) }.onSuccess {
                    quadrilateral = it
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCorner < 0) return false
                activeCorner = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun viewPoint(point: NormalizedCropPoint): Pair<Float, Float> =
        (imageBounds.left + point.x.toFloat() * imageBounds.width()) to
            (imageBounds.top + point.y.toFloat() * imageBounds.height())

    private fun fromPoints(points: List<NormalizedCropPoint>) = CropQuadrilateral(
        topLeft = points[0], topRight = points[1],
        bottomRight = points[2], bottomLeft = points[3],
    )

    private fun defaultQuadrilateral() = CropQuadrilateral(
        NormalizedCropPoint(0.05, 0.05), NormalizedCropPoint(0.95, 0.05),
        NormalizedCropPoint(0.95, 0.95), NormalizedCropPoint(0.05, 0.95),
    )

    private fun cornerLabel(corner: Int): Int = when (corner) {
        0 -> R.string.manual_crop_corner_top_left
        1 -> R.string.manual_crop_corner_top_right
        2 -> R.string.manual_crop_corner_bottom_right
        3 -> R.string.manual_crop_corner_bottom_left
        else -> throw IllegalArgumentException("Unknown crop corner $corner")
    }

    internal companion object {
        val CORNER_IDS = listOf(0, 1, 2, 3)
        const val ACCESSIBLE_NUDGE = 0.02
        const val ACTION_MOVE_LEFT = 0x01020001
        const val ACTION_MOVE_RIGHT = 0x01020002
        const val ACTION_MOVE_UP = 0x01020003
        const val ACTION_MOVE_DOWN = 0x01020004
    }
}
