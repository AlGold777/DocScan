package app.docscan.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Overlay view that draws a green polygon boundary over detected documents in the live camera preview.
 * Points are ready PreviewView screen pixels (FloatArray of 8 elements: TL.x, TL.y, TR.x, TR.y, BR.x, BR.y, BL.x, BL.y).
 */
class DocumentOverlayView(context: Context) : View(context) {

    private var points: FloatArray? = null
    private val path = Path()

    init {
        setWillNotDraw(false)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2422C55E // Subtle emerald green transparent fill
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3.0f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xFF22C55E.toInt() // Emerald Green
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.0f
        color = 0xFF22C55E.toInt()
    }

    fun setPolygonPixels(newPoints: FloatArray?) {
        points = newPoints?.takeIf { it.size == 8 }?.clone()
        invalidate()
    }

    fun clear() {
        points = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = points ?: return
        path.reset()
        path.moveTo(p[0], p[1])
        path.lineTo(p[2], p[3])
        path.lineTo(p[4], p[5])
        path.lineTo(p[6], p[7])
        path.close()

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        val radius = resources.displayMetrics.density * 5f
        for (i in 0 until 4) {
            val px = p[i * 2]
            val py = p[i * 2 + 1]
            canvas.drawCircle(px, py, radius, cornerPaint)
            canvas.drawCircle(px, py, radius, cornerStrokePaint)
        }
    }
}
