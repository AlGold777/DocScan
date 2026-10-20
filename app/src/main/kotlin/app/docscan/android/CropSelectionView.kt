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

/** Fits one immutable preview and permits dragging four ordered convex crop corners and edges. */
class CropSelectionView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var quadrilateral = defaultQuadrilateral()
        set(value) {
            field = value
            dragStartQuad = value
            accessibilityHelper.invalidateRoot()
            invalidate()
        }

    private var activeCorner = -1
    private var activeEdge = -1
    private var dragStartQuad = defaultQuadrilateral()
    private var dragStartX = 0f
    private var dragStartY = 0f

    private val handleRadius = 24f * resources.displayMetrics.density
    private val edgeTouchThreshold = 28f * resources.displayMetrics.density
    private val edgePillHalfLength = 14f * resources.displayMetrics.density
    private val edgePillHalfThickness = 4.5f * resources.displayMetrics.density
    private val edgePillCornerRadius = 4.5f * resources.displayMetrics.density

    private val imageBounds = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val polygonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt() // Emerald Green (#22C55E)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2422C55E // Subtle emerald green transparent fill
        style = Paint.Style.FILL
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val edgeHandleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (bitmap == null || imageBounds.isEmpty) return INVALID_ID
            val cornerHit = quadrilateral.asList().mapIndexed { index, point ->
                val center = viewPoint(point)
                index to hypot((center.first - x).toDouble(), (center.second - y).toDouble())
            }.minByOrNull { it.second }
                ?.takeIf { it.second <= handleRadius.toDouble() }
                ?.first
            if (cornerHit != null) return cornerHit

            return (0 until 4).map { index ->
                val center = edgeMidpoint(index)
                (index + 4) to hypot((center.first - x).toDouble(), (center.second - y).toDouble())
            }.minByOrNull { it.second }
                ?.takeIf { it.second <= edgeTouchThreshold.toDouble() }
                ?.first ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (bitmap != null && !imageBounds.isEmpty) {
                virtualViewIds.addAll(CORNER_IDS)
                virtualViewIds.addAll(EDGE_IDS)
            }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val targetSize = ceil(48f * resources.displayMetrics.density).toInt()
            node.className = "android.view.View"
            node.isEnabled = bitmap != null

            if (virtualViewId in CORNER_IDS) {
                val label = cornerLabel(virtualViewId)
                node.contentDescription = context.getString(label)
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
                val left = (center.first - targetSize / 2f).roundToInt().coerceIn(0, (width - targetSize).coerceAtLeast(0))
                val top = (center.second - targetSize / 2f).roundToInt().coerceIn(0, (height - targetSize).coerceAtLeast(0))
                node.setBoundsInParent(Rect(left, top, left + targetSize, top + targetSize))
            } else if (virtualViewId in EDGE_IDS) {
                val edgeIndex = virtualViewId - 4
                val label = edgeLabel(edgeIndex)
                node.contentDescription = context.getString(label)
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_MOVE_LEFT, context.getString(R.string.manual_crop_move_edge_left),
                ))
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_MOVE_RIGHT, context.getString(R.string.manual_crop_move_edge_right),
                ))
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_MOVE_UP, context.getString(R.string.manual_crop_move_edge_up),
                ))
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_MOVE_DOWN, context.getString(R.string.manual_crop_move_edge_down),
                ))
                val center = edgeMidpoint(edgeIndex)
                val left = (center.first - targetSize / 2f).roundToInt().coerceIn(0, (width - targetSize).coerceAtLeast(0))
                val top = (center.second - targetSize / 2f).roundToInt().coerceIn(0, (height - targetSize).coerceAtLeast(0))
                node.setBoundsInParent(Rect(left, top, left + targetSize, top + targetSize))
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (bitmap == null) return false
            if (virtualViewId in CORNER_IDS) {
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
            } else if (virtualViewId in EDGE_IDS) {
                val edgeIndex = virtualViewId - 4
                val (dx, dy) = when (action) {
                    ACTION_MOVE_LEFT -> -ACCESSIBLE_NUDGE to 0.0
                    ACTION_MOVE_RIGHT -> ACCESSIBLE_NUDGE to 0.0
                    ACTION_MOVE_UP -> 0.0 to -ACCESSIBLE_NUDGE
                    ACTION_MOVE_DOWN -> 0.0 to ACCESSIBLE_NUDGE
                    else -> return false
                }
                val updated = quadrilateral.withMovedEdge(edgeIndex, dx, dy)
                if (updated != quadrilateral) {
                    quadrilateral = updated
                    invalidate()
                    invalidateVirtualView(virtualViewId)
                    return true
                }
                return false
            }
            return false
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
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, polygonPaint)

        // Draw midpoint pill handles for edges
        for (i in 0 until 4) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val edgeLen = hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat()
            if (edgeLen > edgePillHalfLength * 2.5f) {
                val mx = (a.first + b.first) / 2f
                val my = (a.second + b.second) / 2f
                val angle = Math.toDegrees(kotlin.math.atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())).toFloat()
                canvas.save()
                canvas.translate(mx, my)
                canvas.rotate(angle)
                val rect = RectF(-edgePillHalfLength, -edgePillHalfThickness, edgePillHalfLength, edgePillHalfThickness)
                canvas.drawRoundRect(rect, edgePillCornerRadius, edgePillCornerRadius, handleFill)
                canvas.drawRoundRect(rect, edgePillCornerRadius, edgePillCornerRadius, edgeHandleStroke)
                canvas.restore()
            }
        }

        // Draw corner handles on top of polygon and edge handles
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
                val points = quadrilateral.asList().map(::viewPoint)
                // 1. Check nearest corner first (touch within corner handle touch area)
                val nearestCorner = points.mapIndexed { index, (cx, cy) ->
                    index to hypot((cx - event.x).toDouble(), (cy - event.y).toDouble())
                }.minByOrNull { it.second }

                if (nearestCorner != null && nearestCorner.second <= handleRadius * 1.8) {
                    activeCorner = nearestCorner.first
                    activeEdge = -1
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                // 2. If not corner, check nearest edge segment
                val nearestEdge = (0 until 4).map { index ->
                    val a = points[index]
                    val b = points[(index + 1) % 4]
                    index to distanceToSegment(event.x, event.y, a.first, a.second, b.first, b.second)
                }.minByOrNull { it.second }

                if (nearestEdge != null && nearestEdge.second <= edgeTouchThreshold) {
                    activeEdge = nearestEdge.first
                    activeCorner = -1
                    dragStartQuad = quadrilateral
                    dragStartX = event.x
                    dragStartY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                // 3. Fallback: corner within handleRadius * 2
                if (nearestCorner != null && nearestCorner.second <= handleRadius * 2.0) {
                    activeCorner = nearestCorner.first
                    activeEdge = -1
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner >= 0) {
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
                } else if (activeEdge >= 0) {
                    val deltaX = ((event.x - dragStartX) / imageBounds.width()).toDouble()
                    val deltaY = ((event.y - dragStartY) / imageBounds.height()).toDouble()
                    val updated = dragStartQuad.withMovedEdge(activeEdge, deltaX, deltaY)
                    if (updated != quadrilateral) {
                        quadrilateral = updated
                        invalidate()
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCorner < 0 && activeEdge < 0) return false
                activeCorner = -1
                activeEdge = -1
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

    private fun edgeMidpoint(edgeIndex: Int): Pair<Float, Float> {
        val points = quadrilateral.asList()
        val p1 = viewPoint(points[edgeIndex])
        val p2 = viewPoint(points[(edgeIndex + 1) % 4])
        return ((p1.first + p2.first) / 2f) to ((p1.second + p2.second) / 2f)
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-4f) {
            return hypot((px - ax).toDouble(), (py - ay).toDouble())
        }
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return hypot((px - projX).toDouble(), (py - projY).toDouble())
    }

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

    private fun edgeLabel(edge: Int): Int = when (edge) {
        0 -> R.string.manual_crop_edge_top
        1 -> R.string.manual_crop_edge_right
        2 -> R.string.manual_crop_edge_bottom
        3 -> R.string.manual_crop_edge_left
        else -> throw IllegalArgumentException("Unknown crop edge $edge")
    }

    internal companion object {
        val CORNER_IDS = listOf(0, 1, 2, 3)
        val EDGE_IDS = listOf(4, 5, 6, 7)
        const val ACCESSIBLE_NUDGE = 0.02
        const val ACTION_MOVE_LEFT = 0x01020001
        const val ACTION_MOVE_RIGHT = 0x01020002
        const val ACTION_MOVE_UP = 0x01020003
        const val ACTION_MOVE_DOWN = 0x01020004
    }
}
