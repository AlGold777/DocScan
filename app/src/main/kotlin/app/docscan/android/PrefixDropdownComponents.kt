package app.docscan.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

internal class DropdownBackgroundDrawable(
    private val backgroundColor: Int,
    private val strokeColor: Int,
    private val arrowColor: Int,
    private val cornerRadius: Float,
    private val strokeWidth: Float,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPath = Path()

    override fun draw(canvas: Canvas) {
        val r = bounds
        val rectF = RectF(
            r.left + strokeWidth / 2f,
            r.top + strokeWidth / 2f,
            r.right - strokeWidth / 2f,
            r.bottom - strokeWidth / 2f,
        )

        // Background
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)

        // Stroke
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = strokeColor
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        }

        // Dropdown arrow ▼
        paint.style = Paint.Style.FILL
        paint.color = arrowColor
        arrowPath.reset()
        val arrowSize = 4f * density
        val arrowCenterX = r.right - 16f * density
        val arrowCenterY = r.centerY().toFloat()
        arrowPath.moveTo(arrowCenterX - arrowSize, arrowCenterY - arrowSize * 0.6f)
        arrowPath.lineTo(arrowCenterX + arrowSize, arrowCenterY - arrowSize * 0.6f)
        arrowPath.lineTo(arrowCenterX, arrowCenterY + arrowSize * 0.7f)
        arrowPath.close()
        canvas.drawPath(arrowPath, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class PrefixSpinnerAdapter(
    context: Context,
    items: List<String>,
) : ArrayAdapter<String>(context, 0, items) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val textView = (convertView as? TextView) ?: TextView(context).apply {
            textSize = 15f
            setPadding(dp(14), dp(10), dp(32), dp(10))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val text = getItem(position).orEmpty()
        textView.text = text
        if (position == 0) {
            textView.setTextColor(0xFF94A3B8.toInt()) // Light gray for "template"
        } else {
            textView.setTextColor(0xFF0F172A.toInt()) // Dark text for actual prefix
        }
        return textView
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val textView = (convertView as? TextView) ?: TextView(context).apply {
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val text = getItem(position).orEmpty()
        textView.text = text
        if (position == 0) {
            textView.setTextColor(0xFF94A3B8.toInt())
        } else {
            textView.setTextColor(0xFF0F172A.toInt())
        }
        return textView
    }
}

internal object PrefixApplicator {
    fun applyPrefix(currentText: String, oldPrefix: String, newPrefix: String): String {
        if (newPrefix.isBlank()) return removePrefix(currentText, oldPrefix)
        if (oldPrefix.isNotBlank()) {
            val prefixWithSpace = "$oldPrefix "
            if (currentText.startsWith(prefixWithSpace)) {
                return "$newPrefix " + currentText.removePrefix(prefixWithSpace)
            } else if (currentText.startsWith(oldPrefix)) {
                return "$newPrefix " + currentText.removePrefix(oldPrefix).trimStart()
            }
        }
        return "$newPrefix $currentText"
    }

    fun removePrefix(currentText: String, prefixToRemove: String): String {
        if (prefixToRemove.isBlank()) return currentText
        val prefixWithSpace = "$prefixToRemove "
        return if (currentText.startsWith(prefixWithSpace)) {
            currentText.removePrefix(prefixWithSpace)
        } else if (currentText.startsWith(prefixToRemove)) {
            currentText.removePrefix(prefixToRemove).trimStart()
        } else {
            currentText
        }
    }
}

