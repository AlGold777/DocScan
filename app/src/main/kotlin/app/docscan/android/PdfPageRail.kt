package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

/** Lazy thumbnails, bounded to visible pages; identity is the original PDF page index. */
internal class PdfPageRail(
    context: android.content.Context,
    private val worker: Executor,
    private val render: (Int) -> Bitmap,
    private val navigate: (Int) -> Unit,
) : HorizontalScrollView(context) {
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val images = mutableMapOf<Int, ImageView>()
    private val cache = linkedMapOf<Int, Bitmap>()
    private val pending = mutableSetOf<Int>()
    private var order = emptyList<Int>()
    @Volatile private var closed = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init { tag = InsetDialogHost.RAIL_TAG; isHorizontalScrollBarEnabled = false; addView(strip, ViewGroup.LayoutParams(-2, -1)) }

    fun update(pages: List<Int>, activePosition: Int, busy: Boolean = false) {
        if (closed) return
        if (order != pages) {
            order = pages.toList(); images.clear(); strip.removeAllViews()
            pages.forEachIndexed { position, source ->
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(5), dp(8), dp(5), dp(8)) }
                val image = ImageView(context).apply {
                    tag = "docscan-pdf-thumbnail:$source"
                    contentDescription = context.getString(R.string.page_image_description, position + 1)
                    scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(3), dp(3), dp(3), dp(3))
                    cache[source]?.let(::setImageBitmap)
                    setOnClickListener { if (!closed && isEnabled) navigate(order.indexOf(source)) }
                }
                images[source] = image
                cell.addView(image, LinearLayout.LayoutParams(dp(56), dp(72)))
                cell.addView(TextView(context).apply { text = "${position + 1}"; gravity = Gravity.CENTER; setTextColor(0xffb8c4d5.toInt()); textSize = 10f }, LinearLayout.LayoutParams(-1, dp(22)))
                strip.addView(cell, LinearLayout.LayoutParams(dp(68), -1))
            }
        }
        order.forEachIndexed { position, source ->
            images[source]?.apply {
                isEnabled = !busy
                background = GradientDrawable().apply { setColor(0xff292d35.toInt()); setStroke(dp(if (position == activePosition) 2 else 1), if (position == activePosition) Color.WHITE else 0xff667082.toInt()) }
            }
        }
        post {
            if (!closed) {
                strip.getChildAt(activePosition)?.let { cell ->
                    if (cell.left < scrollX || cell.right > scrollX + width) scrollTo(cell.left, 0)
                }
                loadVisible()
            }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); loadVisible() }

    private fun loadVisible() {
        if (closed || width <= 0) return
        val first = maxOf(0, scrollX / dp(68) - 1)
        val last = minOf(order.lastIndex, (scrollX + width) / dp(68) + 1)
        for (position in first..last) {
            val source = order.getOrNull(position) ?: continue
            if (source in cache || !pending.add(source)) continue
            worker.execute {
                val bitmap = if (closed) null else runCatching { render(source) }.getOrNull()
                main.post {
                    pending.remove(source)
                    if (closed || source !in order) bitmap?.recycle()
                    else if (bitmap != null) {
                        cache[source] = bitmap; images[source]?.setImageBitmap(bitmap)
                        while (cache.size > 24) {
                            val evict = cache.keys.firstOrNull { key -> order.indexOf(key) !in first..last } ?: break
                            images[evict]?.setImageDrawable(null); cache.remove(evict)?.recycle()
                        }
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
        images.values.forEach { it.setImageDrawable(null) }
        cache.values.forEach { it.recycle() }; cache.clear()
    }
}
