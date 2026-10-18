package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog

/** One surface policy for every application-owned dialog; system pickers remain system-owned. */
internal class InsetDialogHost(private val background: View) {
    private val hosts = mutableListOf<View>()
    private val windows = mutableMapOf<View, android.view.Window>()
    private val dialogs = mutableSetOf<AlertDialog>()
    val foregroundView: View? get() = hosts.lastOrNull()
    fun dismissAll() { dialogs.toList().asReversed().forEach { it.dismiss() }; dialogs.clear() }
    private fun dp(value: Int) = (value * background.resources.displayMetrics.density).toInt()

    fun builder(context: Context): AlertDialog.Builder = object : AlertDialog.Builder(context) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialogs += dialog
            val decor = dialog.window!!.decorView
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    // AlertController has installed its panels before the first posted layout.
                    view.post { if (dialog.isShowing) install(dialog) }
                }
                override fun onViewDetachedFromWindow(view: View) { dialogs.remove(dialog) }
            })
        }
    }

    private fun install(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val content = window.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(HOST_TAG) != null) return
        val panel = dialog.findViewById<ViewGroup>(androidx.appcompat.R.id.parentPanel) ?: return
        val rail = panel.findViewWithTag<View>(RAIL_TAG)
        (rail?.parent as? ViewGroup)?.removeView(rail)
        (panel.parent as? ViewGroup)?.removeView(panel)
        val host = FrameLayout(dialog.context).apply { tag = HOST_TAG }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        var fallback: Bitmap? = null
        var detached = false
        var fallbackView: ImageView? = null
        // Android 29/30 has no RenderEffect. A small, filtered snapshot keeps memory bounded.
        if (Build.VERSION.SDK_INT < 31 && background.width > 0 && background.height > 0) {
            val sample = dp(24).coerceAtLeast(1)
            fallback = Bitmap.createBitmap(maxOf(1, background.width / sample), maxOf(1, background.height / sample), Bitmap.Config.ARGB_8888)
            Canvas(fallback).apply { scale(1f / sample, 1f / sample); background.draw(this) }
            fallbackView = ImageView(dialog.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, fallback).apply { isFilterBitmap = true })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            host.addView(fallbackView, FrameLayout.LayoutParams(-1, -1))
            val sourceWindow = hosts.lastOrNull()?.let { windows[it] } ?: (background.context as? android.app.Activity)?.window
            if (sourceWindow != null) {
                val capture = Bitmap.createBitmap(fallback!!.width, fallback!!.height, Bitmap.Config.ARGB_8888)
                runCatching { android.view.PixelCopy.request(sourceWindow, capture, { result ->
                    if (detached || result != android.view.PixelCopy.SUCCESS) capture.recycle()
                    else {
                        val previous = fallback
                        fallback = capture
                        fallbackView?.setImageDrawable(android.graphics.drawable.BitmapDrawable(background.resources, capture).apply { isFilterBitmap = true })
                        previous?.recycle()
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper())) }.onFailure { capture.recycle() }
            }
        }
        hosts.forEach { blur(it, true) }
        hosts += host
        windows[host] = window
        blur(background, true)
        val railHeight = rail?.layoutParams?.height?.takeIf { it > 0 } ?: dp(172)
        val region = FrameLayout(dialog.context)
        val hostBottomMargin = if (rail != null) railHeight + dp(20) else dp(24)
        host.addView(region, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(24); bottomMargin = hostBottomMargin
        })
        val value = android.util.TypedValue()
        dialog.context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        panel.background = GradientDrawable().apply { setColor(value.data); cornerRadius = dp(20).toFloat() }
        panel.clipToOutline = true
        panel.tag = PANEL_TAG
        region.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        if (rail != null) {
            rail.background = ColorDrawable(0xff17191d.toInt())
            host.addView(rail, FrameLayout.LayoutParams(-1, railHeight, Gravity.BOTTOM))
        }
        // Preserve framework outside-touch policy, including setCanceledOnTouchOutside(false).
        // A transparent full-window host otherwise turns margins into inside-window touches.
        host.setOnTouchListener { _, event ->
            val bounds = android.graphics.Rect()
            panel.getGlobalVisibleRect(bounds)
            val insidePanel = bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            rail?.getGlobalVisibleRect(bounds)
            val insideRail = rail != null && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            if (insidePanel || insideRail) false else {
                val outside = android.view.MotionEvent.obtain(event)
                outside.setLocation(-dp(64).toFloat(), -dp(64).toFloat())
                dialog.onTouchEvent(outside)
                outside.recycle()
                true
            }
        }
        content.removeAllViews()
        content.addView(host, ViewGroup.LayoutParams(-1, -1))
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(.28f)
        window.setLayout(-1, -1)
        // Dismissal callbacks belong to the editor. Attachment cleanup never replaces them.
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                hosts.remove(host)
                windows.remove(host)
                blur(host, false)
                hosts.lastOrNull()?.let { blur(it, false) }
                if (hosts.isEmpty()) blur(background, false)
                detached = true
                fallbackView?.setImageDrawable(null)
                fallback?.recycle(); fallback = null
            }
        })
    }

    private fun blur(view: View, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) view.setRenderEffect(
            if (enabled) RenderEffect.createBlurEffect(dp(10).toFloat(), dp(10).toFloat(), Shader.TileMode.CLAMP) else null,
        )
    }

    companion object {
        const val HOST_TAG = "docscan-inset-dialog-host"
        const val PANEL_TAG = "docscan-inset-dialog-panel"
        const val RAIL_TAG = "docscan-dialog-bottom-rail"
    }
}
