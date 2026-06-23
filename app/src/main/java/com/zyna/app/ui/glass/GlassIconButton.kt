package com.zyna.app.ui.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes

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
    private val icon = ImageView(context).apply {
        visibility = GONE
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var displayedText: String? = null
    @DrawableRes
    private var displayedIconRes: Int = 0
    private var displayedColor: Int? = null

    init {
        isClickable = true
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            icon,
            LayoutParams(24.dpToPx(density), 24.dpToPx(density), Gravity.CENTER)
        )
        minimumWidth = 44.dpToPx(density)
        minimumHeight = 44.dpToPx(density)
    }

    fun setText(text: CharSequence) {
        val nextText = text.toString()
        if (displayedText != nextText) {
            label.text = nextText
            displayedText = nextText
        }
        if (label.visibility != VISIBLE) {
            label.visibility = VISIBLE
        }
        if (icon.visibility != GONE) {
            icon.visibility = GONE
        }
        displayedIconRes = 0
    }

    fun setIconResource(@DrawableRes iconRes: Int) {
        if (displayedIconRes != iconRes) {
            icon.setImageResource(iconRes)
            displayedIconRes = iconRes
        }
        if (label.visibility != GONE) {
            label.visibility = GONE
        }
        if (icon.visibility != VISIBLE) {
            icon.visibility = VISIBLE
        }
        displayedText = null
    }

    fun setTextColor(color: Int) {
        if (displayedColor == color) {
            return
        }
        displayedColor = color
        label.setTextColor(color)
        icon.imageTintList = ColorStateList.valueOf(color)
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
