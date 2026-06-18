package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** Chat input panel composed of separate glass surfaces sharing one controller. */
data class GlassComposerPreview(
    val title: String,
    val body: String
)

class GlassInputBarView @JvmOverloads constructor(
    context: Context,
    private val controller: GlassBackdropController,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val horizontalPadding = 12.dpToPx(density)
    private val verticalPadding = 10.dpToPx(density)
    private val buttonSize = 44.dpToPx(density)
    private val gap = 8.dpToPx(density)
    private val editMinHeight = 44.dpToPx(density)
    private val editMaxHeight = 132.dpToPx(density)
    private val previewHeight = 52.dpToPx(density)
    private val previewTitleHeight = 18.dpToPx(density)
    private val previewBodyHeight = 18.dpToPx(density)
    private val previewHorizontalPadding = 14.dpToPx(density)
    private val previewCancelSize = 32.dpToPx(density)

    private val previewGlass = GlassPanelView(context, controller).apply {
        visibility = GONE
    }
    private val editGlass = GlassPanelView(context, controller)
    private val previewTitle = TextView(context).apply {
        visibility = GONE
        textSize = 12f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val previewBody = TextView(context).apply {
        visibility = GONE
        textSize = 12f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val previewCancel = TextView(context).apply {
        visibility = GONE
        text = "x"
        textSize = 16f
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        contentDescription = "Cancel reply"
    }
    private val attachButton = GlassIconButton(context, controller).apply {
        setText("+")
        contentDescription = "Attach"
    }
    private val sendButton = GlassIconButton(context, controller).apply {
        setText(">")
        contentDescription = "Send"
    }
    private val editText = EditText(context).apply {
        background = null
        minLines = 1
        maxLines = 5
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 16f
        setPadding(14.dpToPx(density), 0, 14.dpToPx(density), 0)
        hint = "Message"
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
    }

    var onSendMessage: (String) -> Boolean = { false }
    var onPreviewCancelled: () -> Unit = {}
    private var isSending = false
    private var pendingSentText: String? = null
    private var preview: GlassComposerPreview? = null
    private var palette: GlassPalette? = null
    private var vulkanGlassBackgroundEnabled = false
    private var adaptiveMaterial = GlassAdaptiveMaterial.Light

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)

        addView(previewGlass)
        addView(editGlass)
        addView(attachButton)
        addView(sendButton)
        addView(editText)
        addView(previewTitle)
        addView(previewBody)
        addView(previewCancel)
        applyInputGlassState()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                requestLayout()
                controller.invalidateRegions()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendDraft()
                true
            } else {
                false
            }
        }
        sendButton.setOnClickListener { sendDraft() }
        previewCancel.setOnClickListener { onPreviewCancelled() }
    }

    fun setPalette(palette: GlassPalette) {
        if (this.palette == palette) {
            return
        }
        this.palette = palette

        val strokeWidth = 1f.dpToPx(density)
        val materialBlur = 4f.dpToPx(density)
        val materialDownscale = 3
        applyInputFallbackStyle(palette, strokeWidth)
        applyForegroundColors()

        editGlass.glassStyle = GlassStyle(
            cornerRadiusPx = 22f.dpToPx(density),
            blurRadiusPx = materialBlur,
            downscale = materialDownscale,
            tintColor = palette.glassTint,
            strokeColor = palette.stroke,
            strokeWidthPx = strokeWidth,
            refractionIntensity = 1.1f,
            bevelWidthPx = 36f.dpToPx(density),
            refractionThicknessPx = 55f.dpToPx(density),
            chromaSpread = 0.02f,
            adaptiveContrast = 0.24f
        )
        previewGlass.glassStyle = GlassStyle(
            cornerRadiusPx = 16f.dpToPx(density),
            blurRadiusPx = materialBlur,
            downscale = materialDownscale,
            tintColor = palette.glassTint,
            strokeColor = palette.stroke,
            strokeWidthPx = strokeWidth,
            refractionIntensity = 1.05f,
            bevelWidthPx = 30f.dpToPx(density),
            refractionThicknessPx = 46f.dpToPx(density),
            chromaSpread = 0.02f,
            adaptiveContrast = 0.22f
        )
        attachButton.setGlassStyle(
            GlassStyle(
                cornerRadiusPx = 22f.dpToPx(density),
                blurRadiusPx = materialBlur,
                downscale = materialDownscale,
                tintColor = palette.glassTintStrong,
                strokeColor = palette.stroke,
                strokeWidthPx = strokeWidth,
                refractionIntensity = 1.1f,
                bevelWidthPx = 32f.dpToPx(density),
                refractionThicknessPx = 48f.dpToPx(density),
                chromaSpread = 0.02f,
                adaptiveContrast = 0.24f
            )
        )
        sendButton.setGlassStyle(
            GlassStyle(
                cornerRadiusPx = 22f.dpToPx(density),
                blurRadiusPx = materialBlur,
                downscale = materialDownscale,
                tintColor = palette.glassTintStrong,
                strokeColor = palette.stroke,
                strokeWidthPx = strokeWidth,
                refractionIntensity = 1.1f,
                bevelWidthPx = 32f.dpToPx(density),
                refractionThicknessPx = 48f.dpToPx(density),
                chromaSpread = 0.02f,
                adaptiveContrast = 0.24f
            )
        )
    }

    internal fun setAdaptiveMaterial(material: GlassAdaptiveMaterial) {
        if (adaptiveMaterial.isVisiblyCloseTo(material)) {
            return
        }
        adaptiveMaterial = material
        applyForegroundColors()
    }

    fun setVulkanGlassBackgroundEnabled(enabled: Boolean) {
        if (vulkanGlassBackgroundEnabled == enabled) {
            return
        }
        vulkanGlassBackgroundEnabled = enabled
        applyInputGlassState()
        palette?.let { currentPalette ->
            applyInputFallbackStyle(currentPalette, 1f.dpToPx(density))
        }
        applyForegroundColors()
        controller.invalidateRegions()
    }

    fun setSending(sending: Boolean, sendFailed: Boolean) {
        val wasSending = isSending
        if (wasSending && !sending) {
            finishPendingSend(sendFailed)
        }

        if (isSending != sending) {
            isSending = sending
            sendButton.isEnabled = !sending
            sendButton.alpha = if (sending) 0.48f else 1f
            sendButton.setText(if (sending) "..." else ">")
        }
    }

    fun setPreview(preview: GlassComposerPreview?) {
        if (this.preview == preview) {
            return
        }
        this.preview = preview
        val isVisible = preview != null
        previewTitle.text = preview?.title.orEmpty()
        previewBody.text = preview?.body.orEmpty()
        previewTitle.visibility = if (isVisible) VISIBLE else GONE
        previewBody.visibility = if (isVisible) VISIBLE else GONE
        previewCancel.visibility = if (isVisible) VISIBLE else GONE
        applyInputGlassState()
        requestLayout()
        controller.invalidateRegions()
    }

    internal fun collectVulkanGlassRects(out: MutableList<VulkanChatGlassRect>) {
        if (
            !vulkanGlassBackgroundEnabled ||
            !isShown ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        if (preview != null && previewGlass.width > 0 && previewGlass.height > 0) {
            out.add(
                VulkanChatGlassRect(
                    left = (left + previewGlass.left).toFloat(),
                    top = (top + previewGlass.top).toFloat(),
                    right = (left + previewGlass.right).toFloat(),
                    bottom = (top + previewGlass.bottom).toFloat(),
                    cornerRadius = 16f.dpToPx(density),
                    opacity = 1f,
                    bezelWidth = 30f.dpToPx(density),
                    glassThickness = 46f.dpToPx(density),
                    shapeKind = VulkanChatGlassRect.SHAPE_ROUNDED_RECT
                )
            )
        }
        val radius = 22f.dpToPx(density)
        addChildGlassRect(
            out = out,
            child = attachButton,
            cornerRadius = radius,
            bevelWidth = 32f.dpToPx(density),
            glassThickness = 48f.dpToPx(density),
            shapeKind = VulkanChatGlassRect.SHAPE_CIRCLE
        )
        addChildGlassRect(
            out = out,
            child = editText,
            cornerRadius = radius,
            bevelWidth = 36f.dpToPx(density),
            glassThickness = 55f.dpToPx(density),
            shapeKind = VulkanChatGlassRect.SHAPE_ROUNDED_RECT
        )
        addChildGlassRect(
            out = out,
            child = sendButton,
            cornerRadius = radius,
            bevelWidth = 32f.dpToPx(density),
            glassThickness = 48f.dpToPx(density),
            shapeKind = VulkanChatGlassRect.SHAPE_CIRCLE
        )
    }

    private fun applyInputGlassState() {
        val glassEnabled = !vulkanGlassBackgroundEnabled && !DISABLE_CHAT_INPUT_HWUI_GLASS
        previewGlass.visibility = if (glassEnabled && preview != null) VISIBLE else GONE
        editGlass.visibility = if (glassEnabled) VISIBLE else GONE
        attachButton.setGlassEnabled(glassEnabled)
        sendButton.setGlassEnabled(glassEnabled)
    }

    private fun applyInputFallbackStyle(palette: GlassPalette, strokeWidth: Float) {
        if (!DISABLE_CHAT_INPUT_HWUI_GLASS || vulkanGlassBackgroundEnabled) {
            editText.background = null
            attachButton.setBackground(null)
            sendButton.setBackground(null)
            return
        }

        val cornerRadius = 22f.dpToPx(density)
        val fallbackColor = 0x66303034
        editText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fallbackColor)
            setStroke(strokeWidth.toInt().coerceAtLeast(1), palette.stroke)
            setCornerRadius(cornerRadius)
        }
        attachButton.setSolidBackground(fallbackColor, palette.stroke, strokeWidth, cornerRadius)
        sendButton.setSolidBackground(fallbackColor, palette.stroke, strokeWidth, cornerRadius)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableEditWidth = (
            width -
                horizontalPadding * 2 -
                buttonSize * 2 -
                gap * 2
            ).coerceAtLeast(80.dpToPx(density))
        val hasPreview = preview != null

        editText.measure(
            MeasureSpec.makeMeasureSpec(availableEditWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(editMaxHeight, MeasureSpec.AT_MOST)
        )
        val editHeight = editText.measuredHeight.coerceIn(editMinHeight, editMaxHeight)
        val rowHeight = max(buttonSize, editHeight)
        val totalHeight = rowHeight + verticalPadding * 2 +
            if (hasPreview) previewHeight + gap else 0

        val exactButton = MeasureSpec.makeMeasureSpec(buttonSize, MeasureSpec.EXACTLY)
        attachButton.measure(exactButton, exactButton)
        sendButton.measure(exactButton, exactButton)
        val exactPreviewWidth = MeasureSpec.makeMeasureSpec(availableEditWidth, MeasureSpec.EXACTLY)
        val exactPreviewHeight = MeasureSpec.makeMeasureSpec(
            if (hasPreview) previewHeight else 0,
            MeasureSpec.EXACTLY
        )
        previewGlass.measure(exactPreviewWidth, exactPreviewHeight)
        val previewTextWidth = (
            availableEditWidth -
                previewHorizontalPadding * 2 -
                previewCancelSize -
                gap
            ).coerceAtLeast(1)
        previewTitle.measure(
            MeasureSpec.makeMeasureSpec(previewTextWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(if (hasPreview) previewTitleHeight else 0, MeasureSpec.EXACTLY)
        )
        previewBody.measure(
            MeasureSpec.makeMeasureSpec(previewTextWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(if (hasPreview) previewBodyHeight else 0, MeasureSpec.EXACTLY)
        )
        previewCancel.measure(
            MeasureSpec.makeMeasureSpec(if (hasPreview) previewCancelSize else 0, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(if (hasPreview) previewCancelSize else 0, MeasureSpec.EXACTLY)
        )
        editGlass.measure(
            MeasureSpec.makeMeasureSpec(availableEditWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(editHeight, MeasureSpec.EXACTLY)
        )
        editText.measure(
            MeasureSpec.makeMeasureSpec(availableEditWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(editHeight, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val height = bottom - top
        val editHeight = editGlass.measuredHeight
        val hasPreview = preview != null
        val rowTop = verticalPadding + if (hasPreview) previewHeight + gap else 0
        val rowHeight = max(buttonSize, editHeight)
        val centerY = rowTop + rowHeight / 2
        val buttonTop = centerY - buttonSize / 2
        val editTop = centerY - editHeight / 2

        var x = horizontalPadding
        attachButton.layout(x, buttonTop, x + buttonSize, buttonTop + buttonSize)
        x += buttonSize + gap

        if (hasPreview) {
            val previewLeft = x
            val previewTop = verticalPadding
            val previewRight = previewLeft + editGlass.measuredWidth
            val previewBottom = previewTop + previewHeight
            previewGlass.layout(previewLeft, previewTop, previewRight, previewBottom)

            val textLeft = previewLeft + previewHorizontalPadding
            val titleTop = previewTop + 8.dpToPx(density)
            val bodyTop = titleTop + previewTitleHeight + 2.dpToPx(density)
            previewTitle.layout(
                textLeft,
                titleTop,
                textLeft + previewTitle.measuredWidth,
                titleTop + previewTitle.measuredHeight
            )
            previewBody.layout(
                textLeft,
                bodyTop,
                textLeft + previewBody.measuredWidth,
                bodyTop + previewBody.measuredHeight
            )
            val cancelLeft = previewRight - previewHorizontalPadding - previewCancelSize
            val cancelTop = previewTop + (previewHeight - previewCancelSize) / 2
            previewCancel.layout(
                cancelLeft,
                cancelTop,
                cancelLeft + previewCancelSize,
                cancelTop + previewCancelSize
            )
        } else {
            previewGlass.layout(0, 0, 0, 0)
            previewTitle.layout(0, 0, 0, 0)
            previewBody.layout(0, 0, 0, 0)
            previewCancel.layout(0, 0, 0, 0)
        }

        editGlass.layout(x, editTop, x + editGlass.measuredWidth, editTop + editHeight)
        editText.layout(x, editTop, x + editText.measuredWidth, editTop + editHeight)
        x += editGlass.measuredWidth + gap

        sendButton.layout(x, buttonTop, x + buttonSize, buttonTop + buttonSize)
    }

    private fun sendDraft() {
        if (isSending) {
            return
        }
        val text = editText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            return
        }
        if (onSendMessage(text)) {
            pendingSentText = text
            setSending(sending = true, sendFailed = false)
        }
    }

    private fun finishPendingSend(sendFailed: Boolean) {
        val pendingText = pendingSentText ?: return
        pendingSentText = null
        if (sendFailed) {
            return
        }

        val currentText = editText.text?.toString()?.trim().orEmpty()
        if (currentText == pendingText) {
            editText.text?.clear()
        }
    }

    private fun applyForegroundColors() {
        val currentPalette = palette ?: return
        if (vulkanGlassBackgroundEnabled) {
            editText.setTextColor(adaptiveMaterial.primaryForeground)
            editText.setHintTextColor(adaptiveMaterial.secondaryForeground)
            previewTitle.setTextColor(adaptiveMaterial.primaryForeground)
            previewBody.setTextColor(adaptiveMaterial.secondaryForeground)
            previewCancel.setTextColor(adaptiveMaterial.glyphForeground)
            attachButton.setTextColor(adaptiveMaterial.glyphForeground)
            sendButton.setTextColor(adaptiveMaterial.glyphForeground)
        } else {
            editText.setTextColor(currentPalette.text)
            editText.setHintTextColor(currentPalette.hint)
            previewTitle.setTextColor(currentPalette.text)
            previewBody.setTextColor(currentPalette.hint)
            previewCancel.setTextColor(currentPalette.text)
            attachButton.setTextColor(currentPalette.text)
            sendButton.setTextColor(currentPalette.text)
        }
    }

    private fun addChildGlassRect(
        out: MutableList<VulkanChatGlassRect>,
        child: android.view.View,
        cornerRadius: Float,
        bevelWidth: Float,
        glassThickness: Float,
        shapeKind: Float
    ) {
        if (child.width <= 0 || child.height <= 0 || child.visibility != VISIBLE) {
            return
        }
        out.add(
            VulkanChatGlassRect(
                left = (left + child.left).toFloat(),
                top = (top + child.top).toFloat(),
                right = (left + child.right).toFloat(),
                bottom = (top + child.bottom).toFloat(),
                cornerRadius = cornerRadius,
                opacity = 1f,
                bezelWidth = bevelWidth,
                glassThickness = glassThickness,
                shapeKind = shapeKind
            )
        )
    }
}

private const val DISABLE_CHAT_INPUT_HWUI_GLASS = false

private fun GlassAdaptiveMaterial.isVisiblyCloseTo(other: GlassAdaptiveMaterial): Boolean {
    return abs(appearance - other.appearance) <= 0.012f &&
        abs(contrast - other.contrast) <= 0.03f
}
