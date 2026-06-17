package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** Minimal label/icon overlay on top of a GlassPanelView. */
class GlassIconButton @JvmOverloads constructor(
    context: Context,
    controller: GlassBackdropController,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val glass = GlassPanelView(context, controller)
    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        includeFontPadding = false
    }

    init {
        isClickable = true
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        minimumWidth = 44.dpToPx(density)
        minimumHeight = 44.dpToPx(density)
    }

    fun setText(text: CharSequence) {
        label.text = text
    }

    fun setTextColor(color: Int) {
        label.setTextColor(color)
    }

    fun setGlassStyle(style: GlassStyle) {
        glass.glassStyle = style
    }

    fun setGlassEnabled(enabled: Boolean) {
        glass.visibility = if (enabled) VISIBLE else GONE
    }

    fun setSolidBackground(color: Int, strokeColor: Int, strokeWidthPx: Float, cornerRadiusPx: Float) {
        setBackground(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            setStroke(strokeWidthPx.toInt().coerceAtLeast(1), strokeColor)
            setCornerRadius(cornerRadiusPx)
        })
    }
}
