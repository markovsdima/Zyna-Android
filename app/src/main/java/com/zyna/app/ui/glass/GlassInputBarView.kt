package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.TextPaint
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.zyna.app.R
import kotlin.math.abs
import kotlin.math.max

/** Chat input panel composed of separate glass surfaces sharing one controller. */
data class GlassComposerPreview(
    val title: String,
    val body: String,
    val kind: GlassComposerPreviewKind
)

enum class GlassComposerPreviewKind {
    REPLY,
    FORWARD,
    EDIT
}

sealed interface GlassVoiceComposerState {
    data object Idle : GlassVoiceComposerState

    data class Recording(
        val durationMillis: Long,
        val waveform: List<Float>
    ) : GlassVoiceComposerState

    data class Preview(
        val durationMillis: Long,
        val waveform: List<Float>,
        val isLoading: Boolean,
        val isPlaying: Boolean
    ) : GlassVoiceComposerState

    data class Error(
        val message: String
    ) : GlassVoiceComposerState
}

private enum class VoiceActionMode {
    IDLE,
    RECORDING,
    PREVIEW,
    ERROR
}

private fun GlassVoiceComposerState.actionMode(): VoiceActionMode {
    return when (this) {
        GlassVoiceComposerState.Idle -> VoiceActionMode.IDLE
        is GlassVoiceComposerState.Recording -> VoiceActionMode.RECORDING
        is GlassVoiceComposerState.Preview -> VoiceActionMode.PREVIEW
        is GlassVoiceComposerState.Error -> VoiceActionMode.ERROR
    }
}

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
    }
    private val attachButton = GlassIconButton(context, controller).apply {
        setText("+")
        contentDescription = context.getText(R.string.chat_composer_action_attach)
    }
    private val sendButton = GlassIconButton(context, controller).apply {
        setText(">")
        contentDescription = context.getText(R.string.chat_composer_action_send)
    }
    private val voiceContentView = VoiceComposerContentView(context).apply {
        visibility = GONE
    }
    private val voiceLockIndicator = VoiceLockIndicatorView(context).apply {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val editText = EditText(context).apply {
        background = null
        minLines = 1
        maxLines = 5
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 16f
        setPadding(14.dpToPx(density), 0, 14.dpToPx(density), 0)
        hint = context.getText(R.string.chat_composer_hint_message)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
    }

    var onSendMessage: (String) -> Boolean = { false }
    var onAttachClicked: () -> Unit = {}
    var onVoiceRecordClicked: () -> Boolean = { false }
    var onVoiceStopClicked: () -> Unit = {}
    var onVoiceCancelClicked: () -> Unit = {}
    var onVoiceFinishForSendClicked: () -> Boolean = { false }
    var onVoiceSendClicked: () -> Boolean = { false }
    var onVoicePreviewPlaybackClicked: () -> Unit = {}
    var onPreviewCancelled: () -> Unit = {}
    var onEditCancelled: () -> Unit = {}
    var onContentLayoutChanged: () -> Unit = {}
    var allowEmptySend: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            updateActionButtons()
        }
    private var isSending = false
    private var pendingSentText: String? = null
    private var preview: GlassComposerPreview? = null
    private var editPreview: GlassComposerPreview? = null
    private var editDraftKey: String? = null
    private var hasDraftText = false
    private var voiceState: GlassVoiceComposerState = GlassVoiceComposerState.Idle
    private var palette: GlassPalette? = null
    private var vulkanGlassBackgroundEnabled = false
    private var adaptiveMaterial = GlassAdaptiveMaterial.Light
    private var voiceGesturePhase = VoiceGesturePhase.Idle
    private var voiceGestureTouchActive = false
    private var voiceGestureStartX = 0f
    private var voiceGestureStartY = 0f
    private var voiceCancelProgress = 0f
    private var voiceLockProgress = 0f
    private val voiceRecordStartRunnable = Runnable {
        if (voiceGesturePhase != VoiceGesturePhase.Pending) {
            return@Runnable
        }
        if (onVoiceRecordClicked()) {
            voiceGesturePhase = VoiceGesturePhase.Holding
            updateVoiceGestureProgress(cancelProgress = 0f, lockProgress = 0f)
            updatePreviewViews()
            updateActionButtons()
        } else {
            resetVoiceGesture()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)

        addView(previewGlass)
        addView(editGlass)
        addView(attachButton)
        addView(sendButton)
        addView(editText)
        addView(voiceContentView)
        addView(voiceLockIndicator)
        addView(previewTitle)
        addView(previewBody)
        addView(previewCancel)
        applyInputGlassState()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val nextHasDraftText = !s.isNullOrBlank()
                if (hasDraftText != nextHasDraftText) {
                    hasDraftText = nextHasDraftText
                    updateActionButtons()
                }
                updateEditorGeometryIfNeeded()
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
        sendButton.setOnTouchListener { _, event -> handleSendButtonTouch(event) }
        sendButton.setOnClickListener { handleSendButtonClicked() }
        attachButton.setOnClickListener { handleAttachButtonClicked() }
        voiceContentView.onPreviewPlaybackClicked = {
            onVoicePreviewPlaybackClicked()
        }
        previewCancel.setOnClickListener {
            if (editPreview != null) {
                onEditCancelled()
            } else {
                onPreviewCancelled()
            }
        }
        updateActionButtons()
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

    internal fun sendButtonCenterX(): Int {
        if (sendButton.width > 0) {
            return sendButton.left + sendButton.width / 2
        }
        return measuredWidth - horizontalPadding - buttonSize / 2
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
            updateActionButtons()
        }
    }

    fun setPreview(preview: GlassComposerPreview?) {
        if (this.preview == preview) {
            return
        }
        this.preview = preview
        updatePreviewViews()
        updateActionButtons()
    }

    fun setEditPreview(preview: GlassComposerPreview?) {
        if (editPreview == preview) {
            return
        }
        editPreview = preview
        updatePreviewViews()
        updateActionButtons()
    }

    fun setEditDraft(key: String?, body: String?) {
        if (editDraftKey == key) {
            return
        }
        val hadEditDraft = editDraftKey != null
        editDraftKey = key
        if (key != null) {
            val draft = body.orEmpty()
            editText.setText(draft)
            editText.setSelection(draft.length)
            editText.requestFocus()
        } else if (hadEditDraft && pendingSentText == null) {
            editText.text?.clear()
        }
        updateActionButtons()
    }

    fun setVoiceComposerState(state: GlassVoiceComposerState) {
        if (voiceState == state) {
            return
        }
        val previousActionMode = voiceState.actionMode()
        voiceState = state
        if (state !is GlassVoiceComposerState.Recording) {
            resetVoiceGesture(updateLayout = false)
        }
        updateVoiceGestureOverlay()
        if (previousActionMode != state.actionMode()) {
            updateActionButtons()
        }
    }

    private fun updatePreviewViews() {
        val activePreview = activePreview()
        val isVisible = activePreview != null
        previewTitle.text = activePreview?.title.orEmpty()
        previewBody.text = activePreview?.body.orEmpty()
        previewCancel.contentDescription = when (activePreview?.kind) {
            GlassComposerPreviewKind.REPLY -> context.getText(R.string.chat_composer_action_cancel_reply)
            GlassComposerPreviewKind.FORWARD -> context.getText(R.string.chat_composer_action_cancel_forward)
            GlassComposerPreviewKind.EDIT -> context.getText(R.string.chat_composer_action_cancel_edit)
            null -> null
        }
        previewTitle.visibility = if (isVisible) VISIBLE else GONE
        previewBody.visibility = if (isVisible) VISIBLE else GONE
        previewCancel.visibility = if (isVisible) VISIBLE else GONE
        updateVoiceGestureOverlay()
        applyInputGlassState()
        requestContentLayout()
    }

    private fun requestContentLayout() {
        requestLayout()
        onContentLayoutChanged()
    }

    private fun updateEditorGeometryIfNeeded() {
        val textLayout = editText.layout ?: run {
            requestContentLayout()
            return
        }
        val maxLines = editText.maxLines
        val layoutHeight = if (textLayout.lineCount > maxLines) {
            textLayout.getLineTop(maxLines)
        } else {
            textLayout.height
        }
        val desiredHeight = (
            layoutHeight + editText.compoundPaddingTop + editText.compoundPaddingBottom
        ).coerceIn(editMinHeight, editMaxHeight)
        if (editGlass.measuredHeight == desiredHeight) {
            return
        }

        // Discard the final exact-height measurement before remeasuring content.
        editText.requestLayout()
        requestContentLayout()
    }

    private fun activePreview(): GlassComposerPreview? {
        return editPreview ?: preview
    }

    internal fun collectVulkanGlassRects(
        out: MutableList<VulkanChatGlassRect>,
        originLeft: Int = left,
        originTop: Int = top
    ) {
        if (
            !vulkanGlassBackgroundEnabled ||
            !isShown ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        if (activePreview() != null && previewGlass.width > 0 && previewGlass.height > 0) {
            out.add(
                VulkanChatGlassRect(
                    left = (originLeft + previewGlass.left).toFloat(),
                    top = (originTop + previewGlass.top).toFloat(),
                    right = (originLeft + previewGlass.right).toFloat(),
                    bottom = (originTop + previewGlass.bottom).toFloat(),
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
            originLeft = originLeft,
            originTop = originTop,
            cornerRadius = radius,
            bevelWidth = 32f.dpToPx(density),
            glassThickness = 48f.dpToPx(density),
            shapeKind = VulkanChatGlassRect.SHAPE_CIRCLE
        )
        addInputGlassRect(
            out = out,
            originLeft = originLeft,
            originTop = originTop,
            cornerRadius = radius,
            bevelWidth = 36f.dpToPx(density),
            glassThickness = 55f.dpToPx(density),
            shapeKind = VulkanChatGlassRect.SHAPE_ROUNDED_RECT
        )
        addChildGlassRect(
            out = out,
            child = sendButton,
            originLeft = originLeft,
            originTop = originTop,
            cornerRadius = radius,
            bevelWidth = 32f.dpToPx(density),
            glassThickness = 48f.dpToPx(density),
            shapeKind = VulkanChatGlassRect.SHAPE_CIRCLE
        )
    }

    private fun applyInputGlassState() {
        val glassEnabled = !vulkanGlassBackgroundEnabled && !DISABLE_CHAT_INPUT_HWUI_GLASS
        previewGlass.visibility = if (glassEnabled && activePreview() != null) VISIBLE else GONE
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
        val hasPreview = activePreview() != null

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
        voiceContentView.measure(
            MeasureSpec.makeMeasureSpec(availableEditWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(editHeight, MeasureSpec.EXACTLY)
        )
        voiceLockIndicator.measure(
            MeasureSpec.makeMeasureSpec(VOICE_LOCK_INDICATOR_WIDTH_DP.dpToPx(density), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(VOICE_LOCK_INDICATOR_HEIGHT_DP.dpToPx(density), MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val height = bottom - top
        val editHeight = editGlass.measuredHeight
        val hasPreview = activePreview() != null
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
        voiceContentView.layout(x, editTop, x + voiceContentView.measuredWidth, editTop + editHeight)
        x += editGlass.measuredWidth + gap

        sendButton.layout(x, buttonTop, x + buttonSize, buttonTop + buttonSize)
        val lockLeft = sendButton.left + sendButton.width / 2 - voiceLockIndicator.measuredWidth / 2
        val lockTop = buttonTop - voiceLockIndicator.measuredHeight - 8.dpToPx(density)
        voiceLockIndicator.layout(
            lockLeft,
            lockTop,
            lockLeft + voiceLockIndicator.measuredWidth,
            lockTop + voiceLockIndicator.measuredHeight
        )
        updateVoiceGestureOverlay()
    }

    private fun handleAttachButtonClicked() {
        when (voiceState) {
            GlassVoiceComposerState.Idle -> onAttachClicked()
            is GlassVoiceComposerState.Recording,
            is GlassVoiceComposerState.Error,
            is GlassVoiceComposerState.Preview -> onVoiceCancelClicked()
        }
    }

    private fun handleSendButtonTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!shouldHandleVoiceGesture()) {
                    return false
                }
                voiceGestureStartX = event.x
                voiceGestureStartY = event.y
                voiceGesturePhase = VoiceGesturePhase.Pending
                voiceGestureTouchActive = true
                sendButton.isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(voiceRecordStartRunnable)
                postDelayed(voiceRecordStartRunnable, VOICE_RECORD_START_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!voiceGestureTouchActive) {
                    return false
                }
                handleVoiceGestureMove(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!voiceGestureTouchActive) {
                    return false
                }
                handleVoiceGestureUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!voiceGestureTouchActive) {
                    return false
                }
                handleVoiceGestureCancel()
                return true
            }
        }
        return voiceGestureTouchActive
    }

    private fun handleVoiceGestureMove(event: MotionEvent) {
        when (voiceGesturePhase) {
            VoiceGesturePhase.Idle,
            VoiceGesturePhase.Cancelled -> return
            VoiceGesturePhase.Locked -> return
            VoiceGesturePhase.Pending -> {
                val horizontalMovement = abs(event.x - voiceGestureStartX)
                val verticalMovement = abs(event.y - voiceGestureStartY)
                if (
                    horizontalMovement > VOICE_RECORD_PRE_START_SLOP_DP.dpToPx(density) ||
                    verticalMovement > VOICE_RECORD_PRE_START_SLOP_DP.dpToPx(density)
                ) {
                    resetVoiceGesture()
                }
            }
            VoiceGesturePhase.Holding -> {
                val dx = voiceGestureStartX - event.x
                val dy = voiceGestureStartY - event.y
                val deadZone = VOICE_SLIDE_DEAD_ZONE_DP.dpToPx(density).toFloat()
                val cancelDistance = VOICE_CANCEL_DISTANCE_DP.dpToPx(density).toFloat()
                val lockDistance = VOICE_LOCK_DISTANCE_DP.dpToPx(density).toFloat()
                when {
                    dy > lockDistance -> {
                        voiceGesturePhase = VoiceGesturePhase.Locked
                        sendButton.isPressed = false
                        updateVoiceGestureProgress(cancelProgress = 0f, lockProgress = 1f)
                        updatePreviewViews()
                        updateActionButtons()
                    }
                    dy > deadZone -> {
                        val lockProgress = (dy / lockDistance).coerceIn(0f, 1f)
                        updateVoiceGestureProgress(cancelProgress = 0f, lockProgress = lockProgress)
                    }
                    dx > deadZone -> {
                        val cancelProgress = ((dx - deadZone) / cancelDistance).coerceIn(0f, 1f)
                        updateVoiceGestureProgress(cancelProgress = cancelProgress, lockProgress = 0f)
                        if (cancelProgress >= 1f) {
                            voiceGesturePhase = VoiceGesturePhase.Cancelled
                            onVoiceCancelClicked()
                            resetVoiceGesture()
                        }
                    }
                    else -> {
                        updateVoiceGestureProgress(cancelProgress = 0f, lockProgress = 0f)
                    }
                }
            }
        }
    }

    private fun handleVoiceGestureUp() {
        when (voiceGesturePhase) {
            VoiceGesturePhase.Pending -> resetVoiceGesture()
            VoiceGesturePhase.Holding -> {
                onVoiceFinishForSendClicked()
                resetVoiceGesture()
            }
            VoiceGesturePhase.Locked -> {
                voiceGestureTouchActive = false
                sendButton.isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                updatePreviewViews()
                updateActionButtons()
            }
            VoiceGesturePhase.Cancelled,
            VoiceGesturePhase.Idle -> resetVoiceGesture()
        }
    }

    private fun handleVoiceGestureCancel() {
        when (voiceGesturePhase) {
            VoiceGesturePhase.Pending -> Unit
            VoiceGesturePhase.Holding -> {
                // ACTION_CANCEL is system-owned, not an explicit user cancel.
                onVoiceStopClicked()
            }
            VoiceGesturePhase.Locked -> {
                voiceGestureTouchActive = false
                sendButton.isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                updatePreviewViews()
                updateActionButtons()
                return
            }
            VoiceGesturePhase.Cancelled,
            VoiceGesturePhase.Idle -> Unit
        }
        resetVoiceGesture()
    }

    private fun resetVoiceGesture(updateLayout: Boolean = true) {
        removeCallbacks(voiceRecordStartRunnable)
        if (
            voiceGesturePhase == VoiceGesturePhase.Idle &&
            !voiceGestureTouchActive &&
            !sendButton.isPressed
        ) {
            return
        }
        voiceGesturePhase = VoiceGesturePhase.Idle
        voiceGestureTouchActive = false
        voiceCancelProgress = 0f
        voiceLockProgress = 0f
        sendButton.isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (updateLayout) {
            updatePreviewViews()
        } else {
            updateVoiceGestureOverlay()
        }
        updateActionButtons()
    }

    private fun updateVoiceGestureProgress(cancelProgress: Float, lockProgress: Float) {
        val nextCancelProgress = cancelProgress.coerceIn(0f, 1f)
        val nextLockProgress = lockProgress.coerceIn(0f, 1f)
        if (
            voiceCancelProgress == nextCancelProgress &&
            voiceLockProgress == nextLockProgress
        ) {
            return
        }
        voiceCancelProgress = nextCancelProgress
        voiceLockProgress = nextLockProgress
        updateVoiceGestureOverlay()
    }

    private fun updateVoiceGestureOverlay() {
        val isVisible = voiceState !is GlassVoiceComposerState.Idle
        voiceContentView.visibility = if (isVisible) VISIBLE else GONE
        voiceContentView.setContentState(
            state = voiceState,
            locked = voiceGesturePhase == VoiceGesturePhase.Locked,
            cancelProgress = voiceCancelProgress,
            lockProgress = voiceLockProgress
        )
        val showLockIndicator = voiceState is GlassVoiceComposerState.Recording &&
            voiceGesturePhase != VoiceGesturePhase.Idle &&
            voiceCancelProgress <= 0.01f
        voiceLockIndicator.visibility = if (showLockIndicator) VISIBLE else GONE
        voiceLockIndicator.setLockProgress(
            if (voiceGesturePhase == VoiceGesturePhase.Locked) 1f else voiceLockProgress
        )
    }

    private fun shouldHandleVoiceGesture(): Boolean {
        return !isSending &&
            voiceState is GlassVoiceComposerState.Idle &&
            shouldRecordVoice()
    }

    private fun handleSendButtonClicked() {
        if (isSending) {
            return
        }
        when (voiceState) {
            GlassVoiceComposerState.Idle -> {
                if (!shouldRecordVoice()) {
                    sendDraft()
                }
            }
            is GlassVoiceComposerState.Recording -> onVoiceStopClicked()
            is GlassVoiceComposerState.Preview -> {
                if (onVoiceSendClicked()) {
                    setSending(sending = true, sendFailed = false)
                }
            }
            is GlassVoiceComposerState.Error -> onVoiceCancelClicked()
        }
    }

    private fun updateActionButtons() {
        val sending = isSending
        val state = voiceState
        val voiceActive = state !is GlassVoiceComposerState.Idle
        editText.isEnabled = !voiceActive && !sending
        editText.hint = if (voiceActive) {
            null
        } else {
            context.getText(R.string.chat_composer_hint_message)
        }
        editText.visibility = if (voiceActive) INVISIBLE else VISIBLE
        sendButton.isEnabled = !sending
        sendButton.alpha = if (sending) 0.48f else 1f
        attachButton.isEnabled = !sending
        attachButton.alpha = if (sending) 0.48f else 1f

        if (sending) {
            sendButton.setText("...")
            sendButton.contentDescription = context.getText(R.string.chat_composer_state_sending)
            return
        }

        when (state) {
            GlassVoiceComposerState.Idle -> {
                attachButton.setText("+")
                attachButton.contentDescription = context.getText(R.string.chat_composer_action_attach)
                if (shouldRecordVoice()) {
                    sendButton.setIconResource(R.drawable.ic_input_mic_24)
                    sendButton.contentDescription = context.getText(R.string.chat_composer_action_record_voice)
                } else {
                    sendButton.setText(">")
                    sendButton.contentDescription = context.getText(R.string.chat_composer_action_send)
                }
            }
            is GlassVoiceComposerState.Recording -> {
                attachButton.setIconResource(R.drawable.ic_input_trash_24)
                attachButton.contentDescription = context.getText(R.string.chat_composer_action_cancel_voice_recording)
                if (voiceGesturePhase == VoiceGesturePhase.Locked) {
                    sendButton.setIconResource(R.drawable.ic_input_pause_24)
                    sendButton.contentDescription = context.getText(R.string.chat_composer_action_stop_voice_recording)
                } else {
                    sendButton.setIconResource(R.drawable.ic_input_mic_24)
                    sendButton.contentDescription = context.getText(R.string.chat_composer_state_recording_voice)
                }
            }
            is GlassVoiceComposerState.Preview -> {
                attachButton.setIconResource(R.drawable.ic_input_trash_24)
                attachButton.contentDescription = context.getText(R.string.chat_composer_action_delete_voice_preview)
                sendButton.setIconResource(R.drawable.ic_input_send_24)
                sendButton.contentDescription = context.getText(R.string.chat_composer_action_send_voice)
            }
            is GlassVoiceComposerState.Error -> {
                attachButton.setText("x")
                attachButton.contentDescription = context.getText(R.string.chat_composer_action_dismiss_voice_error)
                sendButton.setText("x")
                sendButton.contentDescription = context.getText(R.string.chat_composer_action_dismiss_voice_error)
            }
        }
    }

    private fun shouldRecordVoice(): Boolean {
        return !hasDraftText &&
            !allowEmptySend &&
            editPreview == null &&
            editDraftKey == null
    }

    private fun sendDraft() {
        if (isSending) {
            return
        }
        val text = editText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && !allowEmptySend) {
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
            voiceContentView.setColors(
                primary = adaptiveMaterial.primaryForeground,
                secondary = adaptiveMaterial.secondaryForeground,
                destructive = VOICE_DESTRUCTIVE_COLOR
            )
            voiceLockIndicator.setColors(
                primary = adaptiveMaterial.primaryForeground,
                secondary = adaptiveMaterial.secondaryForeground
            )
        } else {
            editText.setTextColor(currentPalette.text)
            editText.setHintTextColor(currentPalette.hint)
            previewTitle.setTextColor(currentPalette.text)
            previewBody.setTextColor(currentPalette.hint)
            previewCancel.setTextColor(currentPalette.text)
            attachButton.setTextColor(currentPalette.text)
            sendButton.setTextColor(currentPalette.text)
            voiceContentView.setColors(
                primary = currentPalette.text,
                secondary = currentPalette.hint,
                destructive = VOICE_DESTRUCTIVE_COLOR
            )
            voiceLockIndicator.setColors(
                primary = currentPalette.text,
                secondary = currentPalette.hint
            )
        }
    }

    private fun addChildGlassRect(
        out: MutableList<VulkanChatGlassRect>,
        child: android.view.View,
        originLeft: Int,
        originTop: Int,
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
                left = (originLeft + child.left).toFloat(),
                top = (originTop + child.top).toFloat(),
                right = (originLeft + child.right).toFloat(),
                bottom = (originTop + child.bottom).toFloat(),
                cornerRadius = cornerRadius,
                opacity = 1f,
                bezelWidth = bevelWidth,
                glassThickness = glassThickness,
                shapeKind = shapeKind
            )
        )
    }

    private fun addInputGlassRect(
        out: MutableList<VulkanChatGlassRect>,
        originLeft: Int,
        originTop: Int,
        cornerRadius: Float,
        bevelWidth: Float,
        glassThickness: Float,
        shapeKind: Float
    ) {
        if (editGlass.width <= 0 || editGlass.height <= 0) {
            return
        }
        out.add(
            VulkanChatGlassRect(
                left = (originLeft + editGlass.left).toFloat(),
                top = (originTop + editGlass.top).toFloat(),
                right = (originLeft + editGlass.right).toFloat(),
                bottom = (originTop + editGlass.bottom).toFloat(),
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
private const val VOICE_RECORD_START_DELAY_MS = 50L
private const val VOICE_RECORD_PRE_START_SLOP_DP = 20
private const val VOICE_SLIDE_DEAD_ZONE_DP = 15
private const val VOICE_CANCEL_DISTANCE_DP = 120
private const val VOICE_LOCK_DISTANCE_DP = 80
private const val VOICE_DESTRUCTIVE_COLOR = 0xFFE5484D.toInt()
private const val VOICE_CANCEL_LABEL_TEXT_SP = 13f
private const val VOICE_TIMER_TEXT_SP = 16f
private const val VOICE_PREVIEW_DURATION_TEXT_SP = 14f
private const val VOICE_MESSAGE_TEXT_SP = 14f
private const val VOICE_RECORDING_MAX_BARS = 24
private const val VOICE_PREVIEW_MAX_BARS = 32
private const val VOICE_HOLDING_WAVEFORM_GAP_DP = 10f
private const val VOICE_HOLDING_WAVEFORM_ALPHA = 104
private const val VOICE_LOCK_INDICATOR_WIDTH_DP = 40
private const val VOICE_LOCK_INDICATOR_HEIGHT_DP = 80
private const val VOICE_SLIDE_CHEVRON_WIDTH_DP = 8f
private const val VOICE_SLIDE_CHEVRON_GAP_DP = 6f

private enum class VoiceGesturePhase {
    Idle,
    Pending,
    Holding,
    Locked,
    Cancelled
}

private class VoiceLockIndicatorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f.dpToPx(density)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private var lockProgress = 0f
    private var primary = 0xFFFFFFFF.toInt()
    private var secondary = 0xB3FFFFFF.toInt()

    fun setColors(primary: Int, secondary: Int) {
        if (this.primary == primary && this.secondary == secondary) {
            return
        }
        this.primary = primary
        this.secondary = secondary
        invalidate()
    }

    fun setLockProgress(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (lockProgress == next) {
            return
        }
        lockProgress = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            return
        }
        val radius = w / 2f
        fillPaint.color = secondary.withAlpha(42)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val locked = lockProgress >= 0.7f
        val iconColor = if (locked) primary else secondary
        drawLock(canvas, w / 2f, 27f.dpToPx(density), locked, iconColor)
        drawChevron(canvas, w / 2f, 57f.dpToPx(density), secondary)
    }

    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, locked: Boolean, color: Int) {
        val bodyWidth = 14f.dpToPx(density)
        val bodyHeight = 10f.dpToPx(density)
        val bodyTop = cy + 2f.dpToPx(density)
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = color.withAlpha(245)
        rect.set(
            cx - bodyWidth / 2f,
            bodyTop,
            cx + bodyWidth / 2f,
            bodyTop + bodyHeight
        )
        canvas.drawRoundRect(rect, 2.5f.dpToPx(density), 2.5f.dpToPx(density), iconPaint)

        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 2f.dpToPx(density)
        iconPaint.color = color.withAlpha(245)
        val shackleWidth = 12f.dpToPx(density)
        val shackleHeight = 12f.dpToPx(density)
        val shackleTop = bodyTop - shackleHeight * 0.76f
        val shackleBottom = bodyTop + shackleHeight * 0.48f
        if (locked) {
            rect.set(
                cx - shackleWidth / 2f,
                shackleTop,
                cx + shackleWidth / 2f,
                shackleBottom
            )
            canvas.drawArc(rect, 205f, 130f, false, iconPaint)
        } else {
            val bodyLeft = cx - bodyWidth / 2f
            val leftX = bodyLeft - shackleWidth * 0.82f
            val rightX = bodyLeft + shackleWidth * 0.18f
            rect.set(
                leftX,
                shackleTop,
                rightX,
                shackleBottom
            )
            canvas.drawArc(rect, 205f, 130f, false, iconPaint)
        }
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val alpha = ((1f - lockProgress * 1.8f).coerceIn(0f, 1f) * 210f).toInt()
        if (alpha <= 0) {
            return
        }
        chevronPaint.color = color.withAlpha(alpha)
        val halfWidth = 5f.dpToPx(density)
        val halfHeight = 4f.dpToPx(density)
        canvas.drawLine(cx - halfWidth, cy + halfHeight, cx, cy - halfHeight, chevronPaint)
        canvas.drawLine(cx, cy - halfHeight, cx + halfWidth, cy + halfHeight, chevronPaint)
    }
}

private class VoiceComposerContentView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val slideToCancelLabel = context.getString(R.string.chat_composer_slide_to_cancel)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = VOICE_CANCEL_LABEL_TEXT_SP.spToPx(resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val slideToCancelLabelWidth = textPaint.measureText(slideToCancelLabel)
    private val timerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = VOICE_TIMER_TEXT_SP.spToPx(resources.displayMetrics)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val durationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = VOICE_PREVIEW_DURATION_TEXT_SP.spToPx(resources.displayMetrics)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = VOICE_MESSAGE_TEXT_SP.spToPx(resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f.dpToPx(density)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val rect = RectF()
    private val iconPath = Path()
    private val playButtonRect = RectF()

    var onPreviewPlaybackClicked: () -> Unit = {}

    private var state: GlassVoiceComposerState = GlassVoiceComposerState.Idle
    private var locked = false
    private var cancelProgress = 0f
    private var lockProgress = 0f
    private var primary = 0xFFFFFFFF.toInt()
    private var secondary = 0xB3FFFFFF.toInt()
    private var destructive = VOICE_DESTRUCTIVE_COLOR
    private var playPressed = false
    private var previewAccessibilityState = VoicePreviewAccessibilityState.HIDDEN

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        updateSlideLabelShadow(secondary)
    }

    fun setColors(primary: Int, secondary: Int, destructive: Int) {
        if (
            this.primary == primary &&
            this.secondary == secondary &&
            this.destructive == destructive
        ) {
            return
        }
        this.primary = primary
        this.secondary = secondary
        this.destructive = destructive
        updateSlideLabelShadow(secondary)
        invalidate()
    }

    fun setContentState(
        state: GlassVoiceComposerState,
        locked: Boolean,
        cancelProgress: Float,
        lockProgress: Float
    ) {
        val nextCancelProgress = cancelProgress.coerceIn(0f, 1f)
        val nextLockProgress = lockProgress.coerceIn(0f, 1f)
        val previousState = this.state
        if (
            previousState == state &&
            this.locked == locked &&
            this.cancelProgress == nextCancelProgress &&
            this.lockProgress == nextLockProgress
        ) {
            return
        }
        this.state = state
        this.locked = locked
        this.cancelProgress = nextCancelProgress
        this.lockProgress = nextLockProgress
        updatePreviewAccessibility(state)
        if (previousState !is GlassVoiceComposerState.Preview ||
            state !is GlassVoiceComposerState.Preview
        ) {
            playPressed = false
        }
        invalidate()
    }

    override fun performClick(): Boolean {
        val preview = state as? GlassVoiceComposerState.Preview
        if (preview == null || preview.isLoading) {
            return super.performClick()
        }
        super.performClick()
        onPreviewPlaybackClicked()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        when (val current = state) {
            GlassVoiceComposerState.Idle -> return
            is GlassVoiceComposerState.Recording -> drawRecording(canvas, current)
            is GlassVoiceComposerState.Preview -> drawPreview(canvas, current)
            is GlassVoiceComposerState.Error -> drawError(canvas, current.message)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state !is GlassVoiceComposerState.Preview) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!playButtonRect.contains(event.x, event.y)) {
                    return false
                }
                playPressed = true
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = playPressed
                playPressed = false
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasPressed && playButtonRect.contains(event.x, event.y)) {
                    performClick()
                }
                return wasPressed
            }
            MotionEvent.ACTION_CANCEL -> {
                if (playPressed) {
                    playPressed = false
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return playPressed
    }

    private fun drawRecording(canvas: Canvas, recording: GlassVoiceComposerState.Recording) {
        val centerY = height / 2f
        val contentLeft = 14f.dpToPx(density)
        val contentRight = width - 14f.dpToPx(density)
        val durationText = recording.durationMillis.formatVoiceDuration()
        if (locked) {
            val nextX = drawDotAndTimer(canvas, contentLeft, centerY, durationText)
            drawWaveform(
                canvas = canvas,
                waveform = recording.waveform,
                startX = nextX + 10f.dpToPx(density),
                centerY = centerY,
                maxWidth = contentRight - nextX - 10f.dpToPx(density),
                maxBars = VOICE_RECORDING_MAX_BARS,
                tail = true
            )
            return
        }
        val timerEndX = drawDotAndTimer(canvas, contentLeft, centerY, durationText)
        drawHoldingWaveformAndSlide(canvas, recording.waveform, timerEndX)
    }

    private fun drawDotAndTimer(
        canvas: Canvas,
        startX: Float,
        centerY: Float,
        durationText: String
    ): Float {
        val dotRadius = 4f.dpToPx(density)
        val dotCenterX = startX + dotRadius
        dotPaint.color = destructive.withAlpha(255)
        canvas.drawCircle(dotCenterX, centerY, dotRadius, dotPaint)

        timerPaint.color = primary.withAlpha(255)
        val baseline = centerY - (timerPaint.descent() + timerPaint.ascent()) / 2f
        val textX = dotCenterX + 13f.dpToPx(density)
        canvas.drawText(durationText, textX, baseline, timerPaint)
        return textX + timerPaint.measureText(durationText)
    }

    private fun drawHoldingWaveformAndSlide(
        canvas: Canvas,
        waveform: List<Float>,
        timerEndX: Float
    ) {
        if (locked) {
            return
        }
        val centerY = height / 2f
        val waveGap = VOICE_HOLDING_WAVEFORM_GAP_DP.dpToPx(density)
        val waveStartX = timerEndX + waveGap
        val rightLimit = width - 14f.dpToPx(density)
        drawWaveform(
            canvas = canvas,
            waveform = waveform,
            startX = waveStartX,
            centerY = centerY,
            maxWidth = rightLimit - waveStartX,
            maxBars = VOICE_RECORDING_MAX_BARS,
            tail = true,
            alpha = VOICE_HOLDING_WAVEFORM_ALPHA
        )

        val alpha = ((1f - cancelProgress * 1.5f).coerceIn(0f, 1f) * 255f).toInt()
        if (alpha <= 0) {
            return
        }
        val label = slideToCancelLabel
        textPaint.color = secondary
        textPaint.alpha = alpha
        chevronPaint.color = secondary.withAlpha(alpha)

        val chevronWidth = VOICE_SLIDE_CHEVRON_WIDTH_DP.dpToPx(density)
        val gap = VOICE_SLIDE_CHEVRON_GAP_DP.dpToPx(density)
        val totalWidth = chevronWidth + gap + slideToCancelLabelWidth
        val centeredX = (waveStartX + rightLimit - totalWidth) / 2f -
            cancelProgress * 20f.dpToPx(density)
        val x = centeredX
            .coerceAtLeast(waveStartX)
            .coerceAtMost((rightLimit - totalWidth).coerceAtLeast(waveStartX))

        val chevronCenterX = x + chevronWidth / 2f
        val chevronHalfH = 5f.dpToPx(density)
        canvas.drawLine(
            chevronCenterX + 2f.dpToPx(density),
            centerY - chevronHalfH,
            chevronCenterX - 2f.dpToPx(density),
            centerY,
            chevronPaint
        )
        canvas.drawLine(
            chevronCenterX - 2f.dpToPx(density),
            centerY,
            chevronCenterX + 2f.dpToPx(density),
            centerY + chevronHalfH,
            chevronPaint
        )

        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, x + chevronWidth + gap, baseline, textPaint)
    }

    private fun drawPreview(canvas: Canvas, preview: GlassVoiceComposerState.Preview) {
        val centerY = height / 2f
        val buttonSize = 36f.dpToPx(density)
        val buttonLeft = 8f.dpToPx(density)
        playButtonRect.set(
            buttonLeft,
            centerY - buttonSize / 2f,
            buttonLeft + buttonSize,
            centerY + buttonSize / 2f
        )
        drawPlayPauseButton(canvas, preview.isLoading, preview.isPlaying)

        durationPaint.color = secondary.withAlpha(255)
        val durationText = preview.durationMillis.formatVoiceDuration()
        val durationWidth = durationPaint.measureText(durationText)
        val durationX = width - 10f.dpToPx(density) - durationWidth
        val waveformStart = playButtonRect.right + 8f.dpToPx(density)
        val waveformEnd = durationX - 10f.dpToPx(density)
        drawWaveform(
            canvas = canvas,
            waveform = preview.waveform,
            startX = waveformStart,
            centerY = centerY,
            maxWidth = waveformEnd - waveformStart,
            maxBars = VOICE_PREVIEW_MAX_BARS,
            tail = false
        )
        val baseline = centerY - (durationPaint.descent() + durationPaint.ascent()) / 2f
        canvas.drawText(durationText, durationX, baseline, durationPaint)
    }

    private fun drawPlayPauseButton(canvas: Canvas, loading: Boolean, playing: Boolean) {
        val centerX = playButtonRect.centerX()
        val centerY = playButtonRect.centerY()
        val radius = playButtonRect.width() / 2f
        buttonFillPaint.color = secondary.withAlpha(if (playPressed) 56 else 28)
        canvas.drawCircle(centerX, centerY, radius, buttonFillPaint)

        iconPaint.color = primary.withAlpha(if (loading) 150 else 255)
        if (loading) {
            val dotRadius = 1.8f.dpToPx(density)
            val spacing = 5f.dpToPx(density)
            for (i in -1..1) {
                canvas.drawCircle(centerX + i * spacing, centerY, dotRadius, iconPaint)
            }
        } else if (playing) {
            val barWidth = 4f.dpToPx(density)
            val barHeight = 15f.dpToPx(density)
            val gap = 4f.dpToPx(density)
            rect.set(
                centerX - gap / 2f - barWidth,
                centerY - barHeight / 2f,
                centerX - gap / 2f,
                centerY + barHeight / 2f
            )
            canvas.drawRoundRect(rect, 1.5f.dpToPx(density), 1.5f.dpToPx(density), iconPaint)
            rect.offset(barWidth + gap, 0f)
            canvas.drawRoundRect(rect, 1.5f.dpToPx(density), 1.5f.dpToPx(density), iconPaint)
        } else {
            val triangleWidth = 13f.dpToPx(density)
            val triangleHeight = 16f.dpToPx(density)
            iconPath.reset()
            iconPath.moveTo(centerX - triangleWidth * 0.36f, centerY - triangleHeight / 2f)
            iconPath.lineTo(centerX - triangleWidth * 0.36f, centerY + triangleHeight / 2f)
            iconPath.lineTo(centerX + triangleWidth * 0.54f, centerY)
            iconPath.close()
            canvas.drawPath(iconPath, iconPaint)
        }
    }

    private fun drawWaveform(
        canvas: Canvas,
        waveform: List<Float>,
        startX: Float,
        centerY: Float,
        maxWidth: Float,
        maxBars: Int,
        tail: Boolean,
        alpha: Int = 210
    ) {
        if (waveform.isEmpty() || maxWidth <= 0f) {
            return
        }
        val barWidth = 3f.dpToPx(density)
        val gap = 2f.dpToPx(density)
        val fitBars = ((maxWidth + gap) / (barWidth + gap)).toInt().coerceAtLeast(0)
        val count = minOf(maxBars, waveform.size, fitBars)
        if (count <= 0) {
            return
        }
        waveformPaint.color = primary.withAlpha(alpha)
        val maxHeight = 20f.dpToPx(density)
        val minHeight = 3f.dpToPx(density)
        val startIndex = if (tail) {
            (waveform.size - count).coerceAtLeast(0)
        } else {
            0
        }
        var x = startX
        repeat(count) { index ->
            val sampleIndex = if (tail) {
                startIndex + index
            } else {
                (index * waveform.size / count).coerceIn(0, waveform.lastIndex)
            }
            val sample = waveform[sampleIndex].coerceIn(0f, 1f)
            val barHeight = max(minHeight, sample * maxHeight)
            rect.set(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, waveformPaint)
            x += barWidth + gap
        }
    }

    private fun drawError(canvas: Canvas, message: String) {
        messagePaint.color = destructive.withAlpha(255)
        val baseline = height / 2f - (messagePaint.descent() + messagePaint.ascent()) / 2f
        canvas.drawText(message, width / 2f, baseline, messagePaint)
    }

    private fun updatePreviewAccessibility(state: GlassVoiceComposerState) {
        val nextState = when (state) {
            is GlassVoiceComposerState.Preview -> when {
                state.isLoading -> VoicePreviewAccessibilityState.LOADING
                state.isPlaying -> VoicePreviewAccessibilityState.PAUSE
                else -> VoicePreviewAccessibilityState.PLAY
            }
            else -> VoicePreviewAccessibilityState.HIDDEN
        }
        if (previewAccessibilityState == nextState) {
            return
        }
        previewAccessibilityState = nextState
        isClickable = nextState == VoicePreviewAccessibilityState.PLAY ||
            nextState == VoicePreviewAccessibilityState.PAUSE
        isFocusable = isClickable
        importantForAccessibility = if (nextState == VoicePreviewAccessibilityState.HIDDEN) {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        contentDescription = when (nextState) {
            VoicePreviewAccessibilityState.HIDDEN -> null
            VoicePreviewAccessibilityState.LOADING -> context.getText(R.string.chat_composer_voice_preview_loading)
            VoicePreviewAccessibilityState.PLAY -> context.getText(R.string.chat_composer_voice_preview_play)
            VoicePreviewAccessibilityState.PAUSE -> context.getText(R.string.chat_composer_voice_preview_pause)
        }
    }

    private fun updateSlideLabelShadow(foreground: Int) {
        val red = foreground ushr 16 and 0xFF
        val green = foreground ushr 8 and 0xFF
        val blue = foreground and 0xFF
        val isLight = red * 299 + green * 587 + blue * 114 >= 128_000
        val shadowColor = if (isLight) 0xB0000000.toInt() else 0x8CFFFFFF.toInt()
        textPaint.setShadowLayer(
            2.25f.dpToPx(density),
            0f,
            0.5f.dpToPx(density),
            shadowColor
        )
    }
}

private enum class VoicePreviewAccessibilityState {
    HIDDEN,
    LOADING,
    PLAY,
    PAUSE
}

private fun GlassAdaptiveMaterial.isVisiblyCloseTo(other: GlassAdaptiveMaterial): Boolean {
    return abs(appearance - other.appearance) <= 0.012f &&
        abs(contrast - other.contrast) <= 0.03f
}

private fun Int.withAlpha(alpha: Int): Int {
    return (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

private fun Float.spToPx(metrics: DisplayMetrics): Float {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, metrics)
}

private fun Long.formatVoiceDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
