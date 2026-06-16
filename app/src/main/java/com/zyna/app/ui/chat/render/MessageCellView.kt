package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

internal class MessageCellView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val bubbleRenderer = BubbleRenderer(density)
    private val textRenderer = TextMessageRenderer(context)
    private val contentRenderers: List<MessageContentRenderer> = listOf(textRenderer)
    private val bubbleRect = RectF()
    private val screenBubbleRect = RectF()
    private val screenLocation = IntArray(2)

    private var renderModel: MessageRenderModel? = null
    private var renderTheme: MessageRenderTheme? = null
    private var layout: MessageCellLayout? = null
    private var lastMeasuredWidth = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var onContextMenuRequested: ((message: MessageRenderModel, bubbleBoundsInScreen: RectF) -> Unit)? = null

    private val outerHorizontalPadding = 12.dpToPx(density)
    private val outerTopPadding = 2.dpToPx(density)
    private val outerBottomPadding = 8.dpToPx(density)
    private val bubbleHorizontalInset = 14.dpToPx(density)
    private val bubbleVerticalInset = 10.dpToPx(density)
    private val minBubbleContentWidth = 28.dpToPx(density)
    private val horizontalChrome = 96.dpToPx(density)
    private val minTextMaxWidth = 180.dpToPx(density)
    private val maxTextMaxWidth = 520.dpToPx(density)

    init {
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(false)
        setOnLongClickListener {
            val model = renderModel ?: return@setOnLongClickListener false
            val callback = onContextMenuRequested ?: return@setOnLongClickListener false
            if (hitTest(lastTouchX, lastTouchY) != MessageHitTarget.BUBBLE) {
                return@setOnLongClickListener false
            }
            bubbleBoundsInScreen(screenBubbleRect)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callback(model, screenBubbleRect)
            true
        }
    }

    fun bind(model: MessageRenderModel, theme: MessageRenderTheme) {
        val needsLayout = renderModel != model || renderTheme != theme
        renderModel = model
        renderTheme = theme
        contentDescription = model.accessibilityText()
        if (needsLayout) {
            layout = null
            requestLayout()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveMeasuredWidth(widthMeasureSpec)
        lastMeasuredWidth = width
        val nextLayout = buildLayout(width)
        layout = nextLayout
        setMeasuredDimension(width, nextLayout.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = renderModel ?: return
        val theme = renderTheme ?: return
        val currentLayout = layout ?: buildLayout(lastMeasuredWidth.takeIf { it > 0 } ?: width).also {
            layout = it
        }

        bubbleRenderer.draw(
            canvas = canvas,
            rect = currentLayout.bubbleRect,
            fillColor = theme.bubbleColor(model),
            message = model
        )

        val save = canvas.save()
        canvas.translate(currentLayout.contentLeft.toFloat(), currentLayout.contentTop.toFloat())
        currentLayout.renderer.draw(canvas, currentLayout.contentLayout)
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            lastTouchX = event.x
            lastTouchY = event.y
        }
        return super.onTouchEvent(event)
    }

    fun hitTest(x: Float, y: Float): MessageHitTarget {
        return if (layout?.bubbleRect?.contains(x, y) == true) {
            MessageHitTarget.BUBBLE
        } else {
            MessageHitTarget.OUTSIDE
        }
    }

    fun bubbleBoundsInScreen(out: RectF): Boolean {
        val currentLayout = layout ?: return false
        getLocationOnScreen(screenLocation)
        out.set(currentLayout.bubbleRect)
        out.offset(screenLocation[0].toFloat(), screenLocation[1].toFloat())
        return true
    }

    private fun buildLayout(width: Int): MessageCellLayout {
        val model = renderModel
        val theme = renderTheme
        if (model == null || theme == null || width <= 0) {
            bubbleRect.set(0f, 0f, 0f, 0f)
            return MessageCellLayout(
                height = max(1, suggestedMinimumHeight),
                bubbleRect = RectF(bubbleRect),
                contentLeft = 0,
                contentTop = 0,
                renderer = textRenderer,
                contentLayout = textRenderer.measure(
                    message = MessageRenderModel(
                        id = "",
                        senderText = "",
                        content = MessageContent.Text(""),
                        timestampText = "",
                        isOutgoing = false,
                        deliveryState = RenderDeliveryState.SENT
                    ),
                    theme = MessageRenderTheme(0, 0, 0, 0, 0, 0),
                    maxWidthPx = 1
                )
            )
        }

        val renderer = rendererFor(model.content)
        val maxBubbleWidth = maxBubbleWidth(width)
        val maxContentWidth = max(
            minBubbleContentWidth,
            maxBubbleWidth - bubbleHorizontalInset * 2
        )
        val contentLayout = renderer.measure(model, theme, maxContentWidth)
        val bubbleWidth = (contentLayout.width + bubbleHorizontalInset * 2)
            .coerceAtMost(maxBubbleWidth)
        val bubbleHeight = contentLayout.height + bubbleVerticalInset * 2
        val bubbleLeft = if (model.isOutgoing) {
            width - outerHorizontalPadding - bubbleWidth
        } else {
            outerHorizontalPadding
        }.coerceAtLeast(outerHorizontalPadding)
        val bubbleTop = outerTopPadding
        bubbleRect.set(
            bubbleLeft.toFloat(),
            bubbleTop.toFloat(),
            (bubbleLeft + bubbleWidth).toFloat(),
            (bubbleTop + bubbleHeight).toFloat()
        )

        return MessageCellLayout(
            height = bubbleTop + bubbleHeight + outerBottomPadding,
            bubbleRect = RectF(bubbleRect),
            contentLeft = bubbleLeft + bubbleHorizontalInset,
            contentTop = bubbleTop + bubbleVerticalInset,
            renderer = renderer,
            contentLayout = contentLayout
        )
    }

    private fun rendererFor(content: MessageContent): MessageContentRenderer {
        return contentRenderers.firstOrNull { it.supports(content) }
            ?: error("No renderer registered for ${content::class.java.simpleName}")
    }

    private fun maxBubbleWidth(width: Int): Int {
        val available = (width - horizontalChrome)
            .coerceAtLeast(min(width - outerHorizontalPadding * 2, minTextMaxWidth))
            .coerceAtMost(maxTextMaxWidth)
        return available.coerceAtLeast(minBubbleContentWidth + bubbleHorizontalInset * 2)
    }

    private fun resolveMeasuredWidth(widthMeasureSpec: Int): Int {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> size
            else -> resources.displayMetrics.widthPixels
        }.coerceAtLeast(1)
    }
}

private data class MessageCellLayout(
    val height: Int,
    val bubbleRect: RectF,
    val contentLeft: Int,
    val contentTop: Int,
    val renderer: MessageContentRenderer,
    val contentLayout: MessageContentLayout
)
