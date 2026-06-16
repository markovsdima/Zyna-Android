package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.max
import kotlin.math.min

internal class MessageCellView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val contextCancelDistance = touchSlop * 2f
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
    private var lastTouchRawX = 0f
    private var lastTouchRawY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var isContextMenuCandidate = false
    private var isContextMenuPreviewing = false
    private var isContextMenuOpened = false
    private var isContextMenuSourceHidden = false
    private var isDrawingContextMenuCopy = false

    var onContextMenuPreviewRequested: ((request: MessageContextMenuRequest) -> Boolean)? = null
    var onContextMenuRequested: ((request: MessageContextMenuRequest) -> Boolean)? = null
    var onContextMenuGestureEvent: ((action: Int, rawX: Float, rawY: Float) -> Unit)? = null

    private val beginContextMenuPreviewRunnable = Runnable {
        beginContextMenuPreview()
    }
    private val openContextMenuRunnable = Runnable {
        openContextMenu()
    }

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
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(false)
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
        if (isContextMenuSourceHidden && !isDrawingContextMenuCopy) {
            return
        }
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
            lastTouchRawX = event.rawX
            lastTouchRawY = event.rawY
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hitTest(event.x, event.y) == MessageHitTarget.BUBBLE && renderModel != null) {
                    downTouchX = event.x
                    downTouchY = event.y
                    isContextMenuCandidate = true
                    isContextMenuPreviewing = false
                    isContextMenuOpened = false
                    removeCallbacks(beginContextMenuPreviewRunnable)
                    removeCallbacks(openContextMenuRunnable)
                    postDelayed(beginContextMenuPreviewRunnable, CONTEXT_MENU_PREVIEW_DELAY_MS)
                    postDelayed(openContextMenuRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isContextMenuCandidate && !isContextMenuPreviewing && movedPastTouchSlop(event.x, event.y)) {
                    cancelContextMenuCandidate()
                }
                if (isContextMenuPreviewing && !isContextMenuOpened && movedPastContextCancelDistance(event.x, event.y)) {
                    cancelContextMenuCandidate()
                    return true
                }
                if (isContextMenuPreviewing || isContextMenuOpened) {
                    onContextMenuGestureEvent?.invoke(event.actionMasked, event.rawX, event.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val wasContextMenuGesture = isContextMenuPreviewing || isContextMenuOpened
                removeCallbacks(beginContextMenuPreviewRunnable)
                removeCallbacks(openContextMenuRunnable)
                if (wasContextMenuGesture) {
                    onContextMenuGestureEvent?.invoke(event.actionMasked, event.rawX, event.rawY)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                resetContextMenuTouchState()
                if (wasContextMenuGesture) {
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(beginContextMenuPreviewRunnable)
        removeCallbacks(openContextMenuRunnable)
        resetContextMenuTouchState()
        isContextMenuSourceHidden = false
        super.onDetachedFromWindow()
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

    fun setContextMenuSourceHidden(hidden: Boolean) {
        if (isContextMenuSourceHidden == hidden) {
            return
        }
        isContextMenuSourceHidden = hidden
        invalidate()
    }

    fun drawForContextMenu(canvas: Canvas) {
        val wasDrawingContextMenuCopy = isDrawingContextMenuCopy
        isDrawingContextMenuCopy = true
        try {
            draw(canvas)
        } finally {
            isDrawingContextMenuCopy = wasDrawingContextMenuCopy
        }
    }

    private fun beginContextMenuPreview() {
        if (!isContextMenuCandidate || isContextMenuPreviewing || movedPastTouchSlop(lastTouchX, lastTouchY)) {
            return
        }
        val request = buildContextMenuRequest() ?: run {
            cancelContextMenuCandidate()
            return
        }
        val didBegin = onContextMenuPreviewRequested?.invoke(request) ?: false
        if (!didBegin) {
            cancelContextMenuCandidate()
            return
        }
        isContextMenuPreviewing = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun openContextMenu() {
        if (!isContextMenuCandidate) {
            return
        }
        if (!isContextMenuPreviewing) {
            beginContextMenuPreview()
        }
        if (!isContextMenuPreviewing || isContextMenuOpened) {
            return
        }
        val request = buildContextMenuRequest() ?: run {
            cancelContextMenuCandidate()
            return
        }
        val didOpen = onContextMenuRequested?.invoke(request) ?: false
        if (!didOpen) {
            cancelContextMenuCandidate()
            return
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        isContextMenuOpened = true
    }

    private fun buildContextMenuRequest(): MessageContextMenuRequest? {
        val model = renderModel ?: return null
        if (!bubbleBoundsInScreen(screenBubbleRect)) {
            return null
        }
        return MessageContextMenuRequest(
            cell = this,
            message = model,
            bubbleBoundsInScreen = RectF(screenBubbleRect),
            touchRawX = lastTouchRawX,
            touchRawY = lastTouchRawY
        )
    }

    private fun movedPastTouchSlop(x: Float, y: Float): Boolean {
        val dx = x - downTouchX
        val dy = y - downTouchY
        return dx * dx + dy * dy > touchSlop * touchSlop
    }

    private fun movedPastContextCancelDistance(x: Float, y: Float): Boolean {
        val dx = x - downTouchX
        val dy = y - downTouchY
        return dx * dx + dy * dy > contextCancelDistance * contextCancelDistance
    }

    private fun cancelContextMenuCandidate() {
        removeCallbacks(beginContextMenuPreviewRunnable)
        removeCallbacks(openContextMenuRunnable)
        if (isContextMenuPreviewing || isContextMenuOpened) {
            onContextMenuGestureEvent?.invoke(MotionEvent.ACTION_CANCEL, lastTouchRawX, lastTouchRawY)
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        resetContextMenuTouchState()
    }

    private fun resetContextMenuTouchState() {
        isContextMenuCandidate = false
        isContextMenuPreviewing = false
        isContextMenuOpened = false
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

internal data class MessageContextMenuRequest(
    val cell: MessageCellView,
    val message: MessageRenderModel,
    val bubbleBoundsInScreen: RectF,
    val touchRawX: Float,
    val touchRawY: Float
)

private const val CONTEXT_MENU_PREVIEW_DELAY_MS = 90L
