package com.zyna.app.ui.glass

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.max

/** Chat input panel composed of separate glass surfaces sharing one controller. */
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

    private val editGlass = GlassPanelView(context, controller)
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
    private var isSending = false
    private var pendingSentText: String? = null
    private var palette: GlassPalette? = null

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)

        addView(editGlass)
        addView(attachButton)
        addView(sendButton)
        addView(editText)

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
    }

    fun setPalette(palette: GlassPalette) {
        if (this.palette == palette) {
            return
        }
        this.palette = palette

        val strokeWidth = 1f.dpToPx(density)
        val materialBlur = 4f.dpToPx(density)
        val materialDownscale = 3
        editText.setTextColor(palette.text)
        editText.setHintTextColor(palette.hint)
        attachButton.setTextColor(palette.text)
        sendButton.setTextColor(palette.text)

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableEditWidth = (
            width -
                horizontalPadding * 2 -
                buttonSize * 2 -
                gap * 2
            ).coerceAtLeast(80.dpToPx(density))

        editText.measure(
            MeasureSpec.makeMeasureSpec(availableEditWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(editMaxHeight, MeasureSpec.AT_MOST)
        )
        val editHeight = editText.measuredHeight.coerceIn(editMinHeight, editMaxHeight)
        val totalHeight = max(buttonSize, editHeight) + verticalPadding * 2

        val exactButton = MeasureSpec.makeMeasureSpec(buttonSize, MeasureSpec.EXACTLY)
        attachButton.measure(exactButton, exactButton)
        sendButton.measure(exactButton, exactButton)
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
        val centerY = height / 2
        val buttonTop = centerY - buttonSize / 2
        val editTop = centerY - editHeight / 2

        var x = horizontalPadding
        attachButton.layout(x, buttonTop, x + buttonSize, buttonTop + buttonSize)
        x += buttonSize + gap

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
}
