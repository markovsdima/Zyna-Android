package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import com.zyna.app.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SystemEventRenderKind {
    SYSTEM_EVENT,
    CALL_EVENT
}

internal enum class SystemEventLeadingIcon {
    PHONE,
    PHONE_NEGATIVE,
    VIDEO,
    VIDEO_NEGATIVE
}

/** A semantic render model so Matrix event wording can evolve independently from the cell. */
internal data class SystemEventRenderModel(
    val text: CharSequence,
    val accessibilityText: CharSequence = text,
    val kind: SystemEventRenderKind = SystemEventRenderKind.SYSTEM_EVENT,
    val leadingIcon: SystemEventLeadingIcon? = null
)

internal data class SystemEventRenderTheme(
    val backgroundColor: Int,
    val textColor: Int
)

/**
 * Lightweight, centered system-event pill.
 *
 * Text layout is rebuilt only when the bound model/theme or measured width changes. The view has
 * no child hierarchy and allocates nothing on the draw path.
 */
internal class SystemEventCellView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.system_event_text_size)
    }
    private val pillRect = RectF()

    private var renderModel: SystemEventRenderModel? = null
    private var renderTheme: SystemEventRenderTheme? = null
    private var textLayout: StaticLayout? = null
    private var leadingIconDrawable: Drawable? = null
    private var lastLayoutAvailableWidth = -1
    private var pillWidth = 0
    private var pillHeight = 0
    private var textDrawLeft = 0f
    private var textDrawTop = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(model: SystemEventRenderModel, theme: SystemEventRenderTheme) {
        if (renderModel == model && renderTheme == theme) {
            return
        }
        val previousLeadingIcon = renderModel?.leadingIcon
        renderModel = model
        renderTheme = theme
        fillPaint.color = theme.backgroundColor
        textPaint.color = theme.textColor
        if (previousLeadingIcon != model.leadingIcon) {
            leadingIconDrawable?.callback = null
            leadingIconDrawable = model.leadingIcon
                ?.let { icon -> ContextCompat.getDrawable(context, icon.drawableResId()) }
                ?.mutate()
                ?.also { it.callback = this }
        }
        leadingIconDrawable?.setTint(theme.textColor)
        contentDescription = model.accessibilityText
        textLayout = null
        lastLayoutAvailableWidth = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveMeasuredWidth(widthMeasureSpec)
        val outerHorizontalPadding = OUTER_HORIZONTAL_PADDING_DP.dpToPx()
        val contentHorizontalPadding = CONTENT_HORIZONTAL_PADDING_DP.dpToPx()
        val iconBlockWidth = if (leadingIconDrawable != null) {
            ICON_SIZE_DP.dpToPx() + ICON_TEXT_GAP_DP.dpToPx()
        } else {
            0
        }
        val availableTextWidth = (
            measuredWidth - outerHorizontalPadding * 2 - contentHorizontalPadding * 2 -
                iconBlockWidth
            ).coerceAtLeast(1)
        ensureTextLayout(availableTextWidth)

        val layout = textLayout
        val textWidth = layout?.width?.coerceAtLeast(1) ?: 1
        val textHeight = layout?.height ?: 0
        val iconSize = if (leadingIconDrawable != null) ICON_SIZE_DP.dpToPx() else 0
        val contentWidth = iconBlockWidth + textWidth
        val contentHeight = max(textHeight, iconSize)
        pillWidth = (contentWidth + contentHorizontalPadding * 2)
            .coerceAtMost((measuredWidth - outerHorizontalPadding * 2).coerceAtLeast(1))
        pillHeight = max(
            MIN_PILL_HEIGHT_DP.dpToPx(),
            contentHeight + CONTENT_VERTICAL_PADDING_DP.dpToPx() * 2
        )

        val desiredHeight = pillHeight + OUTER_VERTICAL_PADDING_DP.dpToPx() * 2
        val measuredHeight = resolveSize(
            desiredHeight.coerceAtLeast(suggestedMinimumHeight),
            heightMeasureSpec
        )
        setMeasuredDimension(
            measuredWidth,
            measuredHeight
        )
        updateDrawGeometry(
            measuredWidth = measuredWidth,
            measuredHeight = measuredHeight,
            textWidth = textWidth,
            textHeight = textHeight,
            iconBlockWidth = iconBlockWidth,
            iconSize = iconSize
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = textLayout ?: return
        val radius = PILL_RADIUS_DP * density
        canvas.drawRoundRect(pillRect, radius, radius, fillPaint)
        leadingIconDrawable?.draw(canvas)
        canvas.withTranslation(textDrawLeft, textDrawTop) {
            layout.draw(this)
        }
    }

    private fun ensureTextLayout(availableWidth: Int) {
        if (textLayout != null && lastLayoutAvailableWidth == availableWidth) {
            return
        }
        lastLayoutAvailableWidth = availableWidth
        val text = renderModel?.text ?: ""
        val desiredWidth = ceil(Layout.getDesiredWidth(text, textPaint).toDouble())
            .roundToInt()
            .coerceIn(1, availableWidth)
        textLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, desiredWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun updateDrawGeometry(
        measuredWidth: Int,
        measuredHeight: Int,
        textWidth: Int,
        textHeight: Int,
        iconBlockWidth: Int,
        iconSize: Int
    ) {
        val pillLeft = (measuredWidth - pillWidth) / 2f
        val pillTop = (measuredHeight - pillHeight) / 2f
        pillRect.set(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)

        val groupWidth = iconBlockWidth + textWidth
        val groupLeft = pillLeft + (pillWidth - groupWidth) / 2f
        textDrawLeft = groupLeft + iconBlockWidth
        textDrawTop = pillTop + (pillHeight - textHeight) / 2f
        leadingIconDrawable?.setBounds(
            groupLeft.roundToInt(),
            (pillTop + (pillHeight - iconSize) / 2f).roundToInt(),
            (groupLeft + iconSize).roundToInt(),
            (pillTop + (pillHeight + iconSize) / 2f).roundToInt()
        )
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return who === leadingIconDrawable || super.verifyDrawable(who)
    }

    override fun getAccessibilityClassName(): CharSequence = TextView::class.java.name

    private fun SystemEventLeadingIcon.drawableResId(): Int {
        return when (this) {
            SystemEventLeadingIcon.PHONE -> R.drawable.ic_system_event_phone_16
            SystemEventLeadingIcon.PHONE_NEGATIVE -> R.drawable.ic_system_event_phone_negative_16
            SystemEventLeadingIcon.VIDEO -> R.drawable.ic_system_event_video_16
            SystemEventLeadingIcon.VIDEO_NEGATIVE -> R.drawable.ic_system_event_video_negative_16
        }
    }

    private fun resolveMeasuredWidth(widthMeasureSpec: Int): Int {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        return when (mode) {
            MeasureSpec.EXACTLY,
            MeasureSpec.AT_MOST -> size
            else -> resources.displayMetrics.widthPixels
        }.coerceAtLeast(1)
    }

    private fun Int.dpToPx(): Int = (this * density).roundToInt()

    private companion object {
        const val OUTER_HORIZONTAL_PADDING_DP = 24
        const val OUTER_VERTICAL_PADDING_DP = 4
        const val CONTENT_HORIZONTAL_PADDING_DP = 10
        const val CONTENT_VERTICAL_PADDING_DP = 5
        const val MIN_PILL_HEIGHT_DP = 26
        const val PILL_RADIUS_DP = 10f
        const val ICON_SIZE_DP = 16
        const val ICON_TEXT_GAP_DP = 5
    }
}
