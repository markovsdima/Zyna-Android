package com.zyna.app.ui.chat.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.util.Log
import com.zyna.app.BuildConfig
import com.zyna.app.data.media.AudioPlaybackSnapshot
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.data.matrix.MatrixMediaGroupItem
import com.zyna.app.data.matrix.MatrixImageInfo
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.ui.chat.viewer.PhotoViewerItem
import com.zyna.app.ui.chat.viewer.PhotoViewerOpenRequest
import com.zyna.app.ui.chat.viewer.PhotoViewerSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class MessageCellView(
    context: Context,
    private val imageLoader: MatrixMediaLoader? = null
) : View(context), PhotoViewerSource {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val contextCancelDistance = touchSlop * 2f
    private val bubbleRenderer = BubbleRenderer(density)
    private var localBubbleGradientRenderer: MessageBubbleGradientRenderer? = null
    private var audioPlaybackSnapshot = AudioPlaybackSnapshot()
    private val textRenderer = TextMessageRenderer(context)
    private val imageRenderer = ImageMessageRenderer(context, imageLoader)
    private val photoGroupRenderer = PhotoGroupMessageRenderer(context, imageLoader)
    private val voiceRenderer = VoiceMessageRenderer(context) { audioPlaybackSnapshot }
    private val contentRenderers: List<MessageContentRenderer> = listOf(
        photoGroupRenderer,
        imageRenderer,
        voiceRenderer,
        textRenderer
    )
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
    private var drawsContextPhotoSelection = true
    private var isPhotoTapCandidate = false
    private var isVoiceTapCandidate = false
    private var replyHeaderTapEventId: String? = null
    private var bubbleHighlightProgress = 0f
    private var bubbleHighlightAnimator: ValueAnimator? = null
    private var imageLoadHandles: List<AutoCloseable> = emptyList()
    private var imageLoadRequests: List<ImageLoadRequest> = emptyList()
    private val contextPhotoSelectionBounds = RectF()
    private val contextPhotoSelectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFFFFF
        style = Paint.Style.FILL
    }
    private val contextPhotoSelectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.dpToPx(density).toFloat()
    }
    private val contextPhotoSelectionRadius = 10.dpToPx(density).toFloat()
    private var hasContextPhotoSelection = false

    var onContextMenuPreviewRequested: ((request: MessageContextMenuRequest) -> Boolean)? = null
    var onContextMenuRequested: ((request: MessageContextMenuRequest) -> Boolean)? = null
    var onContextMenuGestureEvent: ((action: Int, rawX: Float, rawY: Float) -> Unit)? = null
    var onReplyHeaderClicked: ((eventId: String) -> Unit)? = null
    var onPhotoViewerRequested: ((request: PhotoViewerOpenRequest) -> Unit)? = null
    var onVoicePlaybackRequested: ((messageId: String, audioInfo: MatrixAudioInfo) -> Unit)? = null

    override val isPhotoViewerSourceAvailable: Boolean
        get() = isAttachedToWindow && isShown

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
        closeImageLoadHandles()
        renderModel = model
        renderTheme = theme
        contentDescription = model.accessibilityText()
        if (needsLayout) {
            layout = null
            requestLayout()
        }
        startImageLoadIfNeeded(model)
        invalidate()
    }

    fun setAudioPlaybackSnapshot(snapshot: AudioPlaybackSnapshot) {
        if (audioPlaybackSnapshot == snapshot) {
            return
        }
        val previous = audioPlaybackSnapshot
        audioPlaybackSnapshot = snapshot
        val model = renderModel ?: return
        if (model.content !is MessageContent.Voice) {
            return
        }
        if (previous.messageId == model.id || snapshot.messageId == model.id) {
            invalidate()
        }
    }

    fun highlightBubble() {
        bubbleHighlightAnimator?.cancel()
        bubbleHighlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BUBBLE_HIGHLIGHT_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                bubbleHighlightProgress = when {
                    progress < BUBBLE_HIGHLIGHT_RAMP_UP_FRACTION ->
                        progress / BUBBLE_HIGHLIGHT_RAMP_UP_FRACTION
                    progress < BUBBLE_HIGHLIGHT_HOLD_FRACTION -> 1f
                    else -> {
                        val fadeProgress = (progress - BUBBLE_HIGHLIGHT_HOLD_FRACTION) /
                            (1f - BUBBLE_HIGHLIGHT_HOLD_FRACTION)
                        (1f - fadeProgress).coerceIn(0f, 1f)
                    }
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    bubbleHighlightProgress = 0f
                    invalidate()
                }

                override fun onAnimationEnd(animation: Animator) {
                    bubbleHighlightAnimator = null
                    bubbleHighlightProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveMeasuredWidth(widthMeasureSpec)
        lastMeasuredWidth = width
        val nextLayout = buildLayout(width)
        layout = nextLayout
        setMeasuredDimension(width, nextLayout.height)
        renderModel?.let { model -> startImageLoadIfNeeded(model) }
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

        if (currentLayout.drawBubble && !drawsBubbleGradientInParent(currentLayout, model, theme)) {
            if (!drawBubbleGradientLocally(canvas, currentLayout, model, theme)) {
                bubbleRenderer.draw(
                    canvas = canvas,
                    rect = currentLayout.bubbleRect,
                    fillColor = theme.bubbleColor(model),
                    message = model
                )
            }
        }
        if (bubbleHighlightProgress > 0f) {
            bubbleRenderer.drawOverlay(
                canvas = canvas,
                rect = currentLayout.bubbleRect,
                color = highlightColor(theme.textColor(model), bubbleHighlightProgress),
                message = model
            )
        }

        val save = canvas.save()
        canvas.translate(currentLayout.contentLeft.toFloat(), currentLayout.contentTop.toFloat())
        currentLayout.renderer.draw(canvas, currentLayout.contentLayout)
        canvas.restoreToCount(save)
        drawContextPhotoSelection(canvas)
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
                isPhotoTapCandidate = photoViewerRequestAt(event.x, event.y) != null
                isVoiceTapCandidate = voicePlaybackTargetAt(event.x, event.y) != null
                replyHeaderTapEventId = replyHeaderEventIdAt(event.x, event.y)
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
                if (replyHeaderTapEventId != null && movedPastTouchSlop(event.x, event.y)) {
                    replyHeaderTapEventId = null
                }
                if (isPhotoTapCandidate && movedPastTouchSlop(event.x, event.y)) {
                    isPhotoTapCandidate = false
                }
                if (isVoiceTapCandidate && movedPastTouchSlop(event.x, event.y)) {
                    isVoiceTapCandidate = false
                }
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
                val wasPhotoTapCandidate = isPhotoTapCandidate
                val wasVoiceTapCandidate = isVoiceTapCandidate
                val replyHeaderClickEventId = if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    !wasContextMenuGesture &&
                    !movedPastTouchSlop(event.x, event.y)
                ) {
                    val downEventId = replyHeaderTapEventId
                    replyHeaderEventIdAt(event.x, event.y)
                        ?.takeIf { it == downEventId }
                } else {
                    null
                }
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
                if (replyHeaderClickEventId != null) {
                    onReplyHeaderClicked?.invoke(replyHeaderClickEventId)
                    performClick()
                    return true
                }
                if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    wasVoiceTapCandidate &&
                    !movedPastTouchSlop(event.x, event.y)
                ) {
                    val target = voicePlaybackTargetAt(event.x, event.y)
                    if (target != null) {
                        onVoicePlaybackRequested?.invoke(target.messageId, target.audioInfo)
                        performClick()
                        return true
                    }
                }
                if (
                    event.actionMasked == MotionEvent.ACTION_UP &&
                    wasPhotoTapCandidate &&
                    !movedPastTouchSlop(event.x, event.y)
                ) {
                    val request = photoViewerRequestAt(event.x, event.y)
                    if (request != null) {
                        onPhotoViewerRequested?.invoke(request)
                        performClick()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderModel?.let { model ->
            closeImageLoadHandles()
            startImageLoadIfNeeded(model)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(beginContextMenuPreviewRunnable)
        removeCallbacks(openContextMenuRunnable)
        bubbleHighlightAnimator?.cancel()
        bubbleHighlightAnimator = null
        closeImageLoadHandles()
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

    private fun replyHeaderEventIdAt(x: Float, y: Float): String? {
        val currentLayout = layout ?: return null
        val model = renderModel ?: return null
        val replyEventId = model.replyInfo
            ?.eventId
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val (replyLayout, replyY) = when (val contentLayout = currentLayout.contentLayout) {
            is TextMessageLayout -> contentLayout.replyHeaderLayout to contentLayout.replyY
            is ImageMessageLayout -> contentLayout.replyHeaderLayout to contentLayout.replyY
            is PhotoGroupMessageLayout -> contentLayout.replyHeaderLayout to contentLayout.replyY
            is VoiceMessageLayout -> contentLayout.replyHeaderLayout to contentLayout.replyY
            else -> null to 0
        }
        val replyHeaderLayout = replyLayout ?: return null
        val left = currentLayout.contentLeft.toFloat()
        val top = (currentLayout.contentTop + replyY).toFloat()
        val right = left + replyHeaderLayout.width
        val bottom = top + replyHeaderLayout.height
        return if (x >= left && x <= right && y >= top && y <= bottom) {
            replyEventId
        } else {
            null
        }
    }

    fun bubbleBoundsInScreen(out: RectF): Boolean {
        val currentLayout = layout ?: return false
        getLocationOnScreen(screenLocation)
        out.set(currentLayout.bubbleRect)
        out.offset(screenLocation[0].toFloat(), screenLocation[1].toFloat())
        return true
    }

    internal fun drawBubbleBackgroundInParent(
        canvas: Canvas,
        gradientRenderer: MessageBubbleGradientRenderer,
        viewportWidth: Int,
        viewportHeight: Int,
        viewportOffsetX: Float,
        viewportOffsetY: Float,
        alpha: Int = 255
    ): Boolean {
        if (isContextMenuSourceHidden) {
            return false
        }
        val model = renderModel ?: return false
        val theme = renderTheme ?: return false
        val currentLayout = layout ?: buildLayout(
            lastMeasuredWidth.takeIf { it > 0 } ?: width
        ).also {
            layout = it
        }
        if (!currentLayout.drawBubble) {
            return false
        }
        val gradient = theme.bubbleGradient(model) ?: return false
        return gradientRenderer.drawBubble(
            canvas = canvas,
            bubbleRenderer = bubbleRenderer,
            rect = currentLayout.bubbleRect,
            message = model,
            spec = gradient,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            viewportOffsetX = viewportOffsetX,
            viewportOffsetY = viewportOffsetY,
            alpha = alpha
        )
    }

    override fun photoSourceBoundsInScreen(itemId: String): RectF? {
        val currentLayout = layout ?: return null
        val model = renderModel ?: return null
        return photoHitTargets(currentLayout, model)
            .firstOrNull { hit -> hit.item.id == itemId }
            ?.boundsInView
            ?.let(::viewRectToScreen)
    }

    fun setContextMenuSourceHidden(hidden: Boolean) {
        if (!hidden) {
            clearContextPhotoSelection()
        }
        if (isContextMenuSourceHidden == hidden) {
            return
        }
        isContextMenuSourceHidden = hidden
        invalidate()
    }

    fun drawForContextMenu(canvas: Canvas) {
        val wasDrawingContextMenuCopy = isDrawingContextMenuCopy
        val wasDrawingContextSelection = drawsContextPhotoSelection
        isDrawingContextMenuCopy = true
        drawsContextPhotoSelection = true
        try {
            draw(canvas)
        } finally {
            isDrawingContextMenuCopy = wasDrawingContextMenuCopy
            drawsContextPhotoSelection = wasDrawingContextSelection
        }
    }

    fun capturePaintSplashTarget(root: View): PaintSplashTarget? {
        return capturePaintSplashTarget(
            root = root,
            targetBoundsInView = null,
            hideSource = {
                setContextMenuSourceHidden(true)
            }
        )
    }

    fun capturePhotoGroupSelectionPaintSplashTarget(
        root: View,
        selection: PhotoGroupContextSelection
    ): PaintSplashTarget? {
        return capturePaintSplashTarget(
            root = root,
            targetBoundsInView = selection.boundsInView,
            hideSource = {}
        )
    }

    private fun capturePaintSplashTarget(
        root: View,
        targetBoundsInView: RectF?,
        hideSource: () -> Unit
    ): PaintSplashTarget? {
        val currentLayout = layout ?: return null
        val targetBounds = targetBoundsInView ?: currentLayout.bubbleRect
        if (targetBounds.width() <= 0f || targetBounds.height() <= 0f) {
            return null
        }

        val screenBounds = viewRectToScreen(targetBounds)
        if (screenBounds.width() <= 0f || screenBounds.height() <= 0f) {
            return null
        }

        val bitmapWidth = targetBounds.width().toInt().coerceAtLeast(1)
        val bitmapHeight = targetBounds.height().toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-targetBounds.left, -targetBounds.top)

        val wasDrawingContextMenuCopy = isDrawingContextMenuCopy
        val wasDrawingContextSelection = drawsContextPhotoSelection
        isDrawingContextMenuCopy = true
        drawsContextPhotoSelection = false
        try {
            draw(canvas)
        } finally {
            isDrawingContextMenuCopy = wasDrawingContextMenuCopy
            drawsContextPhotoSelection = wasDrawingContextSelection
        }

        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val boundsInRoot = RectF(screenBounds)
        boundsInRoot.offset(-rootLocation[0].toFloat(), -rootLocation[1].toFloat())

        return PaintSplashTarget(
            hideSource = hideSource,
            bitmap = bitmap,
            boundsInScreen = RectF(screenBounds),
            boundsInRoot = boundsInRoot
        )
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
        val currentLayout = layout ?: return null
        if (!bubbleBoundsInScreen(screenBubbleRect)) {
            return null
        }
        val photoGroupSelection = photoGroupContextSelectionAt(
            x = lastTouchX,
            y = lastTouchY,
            currentLayout = currentLayout,
            model = model
        )
        setContextPhotoSelection(
            photoGroupSelection
                ?.takeUnless { it.isOverflowTile }
                ?.boundsInView
        )
        logContextMenuBubble(model, currentLayout)
        return MessageContextMenuRequest(
            cell = this,
            message = model,
            bubbleBoundsInScreen = RectF(screenBubbleRect),
            touchRawX = lastTouchRawX,
            touchRawY = lastTouchRawY,
            photoGroupSelection = photoGroupSelection
        )
    }

    private fun logContextMenuBubble(model: MessageRenderModel, currentLayout: MessageCellLayout) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val content = model.content
        val chrome = currentLayout.renderer.chrome(model)
        Log.d(
            CONTEXT_MENU_BUBBLE_LOG_TAG,
            buildString {
                append("id=").append(model.id)
                append(" eventId=").append(model.eventId)
                append(" outgoing=").append(model.isOutgoing)
                append(" content=").append(content::class.java.simpleName)
                append(" chrome=").append(chrome)
                append(" drawBubble=").append(currentLayout.drawBubble)
                append(" bubble=").append(currentLayout.bubbleRect.toDebugString())
                append(" screen=").append(screenBubbleRect.toDebugString())
                append(" contentOffset=")
                    .append(currentLayout.contentLeft)
                    .append(',')
                    .append(currentLayout.contentTop)
                append(" contentSize=")
                    .append(currentLayout.contentLayout.width)
                    .append('x')
                    .append(currentLayout.contentLayout.height)
                append(' ')
                append(content.debugSummary())
                append(' ')
                append(currentLayout.contentLayout.debugSummary())
            }
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
        isPhotoTapCandidate = false
        isVoiceTapCandidate = false
        replyHeaderTapEventId = null
    }

    private fun setContextPhotoSelection(boundsInView: RectF?) {
        if (boundsInView == null) {
            clearContextPhotoSelection()
            return
        }
        contextPhotoSelectionBounds.set(boundsInView)
        hasContextPhotoSelection = true
        invalidate()
    }

    private fun clearContextPhotoSelection() {
        if (!hasContextPhotoSelection) {
            return
        }
        hasContextPhotoSelection = false
        contextPhotoSelectionBounds.setEmpty()
        invalidate()
    }

    private fun drawContextPhotoSelection(canvas: Canvas) {
        if (!isDrawingContextMenuCopy || !drawsContextPhotoSelection || !hasContextPhotoSelection) {
            return
        }
        canvas.drawRoundRect(
            contextPhotoSelectionBounds,
            contextPhotoSelectionRadius,
            contextPhotoSelectionRadius,
            contextPhotoSelectionFillPaint
        )
        canvas.drawRoundRect(
            contextPhotoSelectionBounds,
            contextPhotoSelectionRadius,
            contextPhotoSelectionRadius,
            contextPhotoSelectionStrokePaint
        )
    }

    private fun drawsBubbleGradientInParent(
        currentLayout: MessageCellLayout,
        model: MessageRenderModel,
        theme: MessageRenderTheme
    ): Boolean {
        if (isDrawingContextMenuCopy || parent !is GradientBubbleRecyclerView) {
            return false
        }
        return currentLayout.drawBubble && theme.bubbleGradient(model) != null
    }

    private fun drawBubbleGradientLocally(
        canvas: Canvas,
        currentLayout: MessageCellLayout,
        model: MessageRenderModel,
        theme: MessageRenderTheme
    ): Boolean {
        val gradient = theme.bubbleGradient(model) ?: return false
        val viewport = parent as? GradientBubbleRecyclerView
        val viewportWidth = viewport?.width ?: width
        val viewportHeight = viewport?.height ?: height
        val viewportOffsetX = if (viewport != null) left.toFloat() + translationX else 0f
        val viewportOffsetY = if (viewport != null) top.toFloat() + translationY else 0f
        return localBubbleGradientRenderer().drawBubble(
            canvas = canvas,
            bubbleRenderer = bubbleRenderer,
            rect = currentLayout.bubbleRect,
            message = model,
            spec = gradient,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            viewportOffsetX = viewportOffsetX,
            viewportOffsetY = viewportOffsetY
        )
    }

    private fun localBubbleGradientRenderer(): MessageBubbleGradientRenderer {
        return localBubbleGradientRenderer ?: MessageBubbleGradientRenderer().also {
            localBubbleGradientRenderer = it
        }
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
                drawBubble = false,
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
        val chrome = renderer.chrome(model)
        val contentHorizontalInset = if (chrome == MessageContentChrome.PADDED_BUBBLE) {
            bubbleHorizontalInset
        } else {
            0
        }
        val contentVerticalInset = if (chrome == MessageContentChrome.PADDED_BUBBLE) {
            bubbleVerticalInset
        } else {
            0
        }
        val maxBubbleWidth = maxBubbleWidth(width)
        val maxContentWidth = max(
            minBubbleContentWidth,
            maxBubbleWidth - contentHorizontalInset * 2
        )
        val contentLayout = renderer.measure(model, theme, maxContentWidth)
        val bubbleWidth = (contentLayout.width + contentHorizontalInset * 2)
            .coerceAtMost(maxBubbleWidth)
        val bubbleHeight = contentLayout.height + contentVerticalInset * 2
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
            contentLeft = bubbleLeft + contentHorizontalInset,
            contentTop = bubbleTop + contentVerticalInset,
            drawBubble = chrome != MessageContentChrome.BARE,
            renderer = renderer,
            contentLayout = contentLayout
        )
    }

    private fun photoViewerRequestAt(x: Float, y: Float): PhotoViewerOpenRequest? {
        val currentLayout = layout ?: return null
        val model = renderModel ?: return null
        val targets = photoHitTargets(currentLayout, model)
        val hit = targets.firstOrNull { target -> target.boundsInView.contains(x, y) }
            ?: return null
        val selectedIndex = targets.indexOf(hit).takeIf { it >= 0 } ?: return null
        return PhotoViewerOpenRequest(
            source = this,
            messageId = model.id,
            items = targets.map { it.item },
            selectedIndex = selectedIndex,
            sourceBoundsInScreen = viewRectToScreen(hit.boundsInView),
            sourceCornerRadiusPx = PHOTO_SOURCE_CORNER_RADIUS_DP.dpToPx(density).toFloat()
        )
    }

    private fun voicePlaybackTargetAt(x: Float, y: Float): VoicePlaybackTarget? {
        val currentLayout = layout ?: return null
        val model = renderModel ?: return null
        val content = model.content as? MessageContent.Voice ?: return null
        if (currentLayout.contentLayout !is VoiceMessageLayout) {
            return null
        }
        val left = currentLayout.contentLeft.toFloat()
        val top = currentLayout.contentTop.toFloat()
        val right = left + currentLayout.contentLayout.width
        val bottom = top + currentLayout.contentLayout.height
        return if (x >= left && x <= right && y >= top && y <= bottom) {
            VoicePlaybackTarget(
                messageId = model.id,
                audioInfo = content.audioInfo
            )
        } else {
            null
        }
    }

    private fun photoHitTargets(
        currentLayout: MessageCellLayout,
        model: MessageRenderModel
    ): List<PhotoHitTarget> {
        return when (val content = model.content) {
            is MessageContent.Image -> {
                val imageLayout = currentLayout.contentLayout as? ImageMessageLayout
                    ?: return emptyList()
                val bounds = RectF(
                    currentLayout.contentLeft.toFloat(),
                    (currentLayout.contentTop + imageLayout.imageY).toFloat(),
                    (currentLayout.contentLeft + imageLayout.imageWidth).toFloat(),
                    (currentLayout.contentTop + imageLayout.imageY + imageLayout.imageHeight).toFloat()
                )
                listOf(
                    PhotoHitTarget(
                        item = PhotoViewerItem(
                            id = model.eventId ?: model.id,
                            imageInfo = content.imageInfo,
                            caption = content.caption
                        ),
                        boundsInView = bounds
                    )
                )
            }
            is MessageContent.PhotoGroup -> {
                val groupLayout = currentLayout.contentLayout as? PhotoGroupMessageLayout
                    ?: return emptyList()
                val frames = groupLayout.mediaFramesInView(currentLayout)
                val visibleCount = PhotoGroupLayout.visibleItemCount(groupLayout.items.size)
                val overflowFrame = frames.getOrNull(visibleCount - 1)
                groupLayout.items.mapIndexedNotNull { index, groupItem ->
                    val frame = frames.getOrNull(index)
                        ?: overflowFrame
                        ?: return@mapIndexedNotNull null
                    PhotoHitTarget(
                        item = PhotoViewerItem(
                            id = groupItem.eventId ?: groupItem.transactionId ?: groupItem.messageId,
                            imageInfo = groupItem.imageInfo,
                            caption = groupItem.imageInfo.caption ?: content.caption
                        ),
                        boundsInView = RectF(frame)
                    )
                }
            }
            else -> emptyList()
        }
    }

    private fun photoGroupContextSelectionAt(
        x: Float,
        y: Float,
        currentLayout: MessageCellLayout,
        model: MessageRenderModel
    ): PhotoGroupContextSelection? {
        val content = model.content as? MessageContent.PhotoGroup ?: return null
        val groupLayout = currentLayout.contentLayout as? PhotoGroupMessageLayout ?: return null
        val frames = groupLayout.mediaFramesInView(currentLayout)
        val hitIndex = frames.indexOfFirst { frame -> frame.contains(x, y) }
            .takeIf { it >= 0 }
            ?: return null
        val visibleCount = PhotoGroupLayout.visibleItemCount(groupLayout.items.size)
        val isOverflowTile = content.items.size > visibleCount && hitIndex == visibleCount - 1
        val item = content.items.getOrNull(hitIndex) ?: return null
        val boundsInView = RectF(frames[hitIndex])
        return PhotoGroupContextSelection(
            item = item,
            itemIndex = hitIndex,
            boundsInView = boundsInView,
            boundsInScreen = viewRectToScreen(boundsInView),
            isOverflowTile = isOverflowTile
        )
    }

    private fun viewRectToScreen(rect: RectF): RectF {
        getLocationOnScreen(screenLocation)
        return RectF(rect).apply {
            offset(screenLocation[0].toFloat(), screenLocation[1].toFloat())
        }
    }

    private fun rendererFor(content: MessageContent): MessageContentRenderer {
        return contentRenderers.firstOrNull { it.supports(content) }
            ?: error("No renderer registered for ${content::class.java.simpleName}")
    }

    private fun startImageLoadIfNeeded(model: MessageRenderModel) {
        val loader = imageLoader ?: return
        val requests = imageLoadRequestsFor(model, layout)
        if (requests == imageLoadRequests) {
            return
        }
        closeImageLoadHandles()
        imageLoadRequests = requests
        if (requests.isEmpty()) {
            return
        }
        val boundMessageId = model.id
        val missingRequests = requests.filter {
                !loader.hasCellImageCovering(
                    imageInfo = it.imageInfo,
                    targetWidthPx = it.targetWidthPx,
                    targetHeightPx = it.targetHeightPx
                )
            }
        imageLoadHandles = missingRequests
            .map { request ->
                loader.loadImage(
                    imageInfo = request.imageInfo,
                    targetWidthPx = request.targetWidthPx,
                    targetHeightPx = request.targetHeightPx
                ) {
                    if (renderModel?.id == boundMessageId) {
                        invalidate()
                    }
                }
            }
    }

    private fun closeImageLoadHandles() {
        imageLoadHandles.forEach { it.close() }
        imageLoadHandles = emptyList()
        imageLoadRequests = emptyList()
    }

    private fun imageLoadRequestsFor(
        model: MessageRenderModel,
        currentLayout: MessageCellLayout?
    ): List<ImageLoadRequest> {
        currentLayout ?: return emptyList()
        return when (val content = model.content) {
            is MessageContent.Image -> {
                val imageLayout = currentLayout.contentLayout as? ImageMessageLayout
                    ?: return emptyList()
                listOf(
                    ImageLoadRequest(
                        imageInfo = content.imageInfo,
                        targetWidthPx = imageLayout.imageWidth,
                        targetHeightPx = imageLayout.imageHeight
                    )
                )
            }
            is MessageContent.PhotoGroup -> {
                val groupLayout = currentLayout.contentLayout as? PhotoGroupMessageLayout
                    ?: return emptyList()
                val visibleCount = PhotoGroupLayout.visibleItemCount(groupLayout.items.size)
                groupLayout.items
                    .take(visibleCount)
                    .mapIndexedNotNull { index, item ->
                        val frame = groupLayout.mediaFrames.getOrNull(index)
                            ?: return@mapIndexedNotNull null
                        ImageLoadRequest(
                            imageInfo = item.imageInfo,
                            targetWidthPx = frame.width().roundToInt().coerceAtLeast(1),
                            targetHeightPx = frame.height().roundToInt().coerceAtLeast(1)
                        )
                    }
            }
            else -> emptyList()
        }
    }

    private fun maxBubbleWidth(width: Int): Int {
        val available = (width - horizontalChrome)
            .coerceAtLeast(min(width - outerHorizontalPadding * 2, minTextMaxWidth))
            .coerceAtMost(maxTextMaxWidth)
        return available.coerceAtLeast(minBubbleContentWidth + bubbleHorizontalInset * 2)
    }

    private fun highlightColor(baseColor: Int, progress: Float): Int {
        val alpha = (BUBBLE_HIGHLIGHT_MAX_ALPHA * progress).roundToInt()
            .coerceIn(0, 255)
        return (baseColor and 0x00FFFFFF) or (alpha shl 24)
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
    val drawBubble: Boolean,
    val renderer: MessageContentRenderer,
    val contentLayout: MessageContentLayout
)

private data class PhotoHitTarget(
    val item: PhotoViewerItem,
    val boundsInView: RectF
)

private data class ImageLoadRequest(
    val imageInfo: MatrixImageInfo,
    val targetWidthPx: Int,
    val targetHeightPx: Int
)

private data class VoicePlaybackTarget(
    val messageId: String,
    val audioInfo: MatrixAudioInfo
)

internal data class MessageContextMenuRequest(
    val cell: MessageCellView,
    val message: MessageRenderModel,
    val bubbleBoundsInScreen: RectF,
    val touchRawX: Float,
    val touchRawY: Float,
    val photoGroupSelection: PhotoGroupContextSelection? = null
)

internal data class PhotoGroupContextSelection(
    val item: MatrixMediaGroupItem,
    val itemIndex: Int,
    val boundsInView: RectF,
    val boundsInScreen: RectF,
    val isOverflowTile: Boolean
)

internal data class PaintSplashTarget(
    val hideSource: () -> Unit,
    val bitmap: Bitmap,
    val boundsInScreen: RectF,
    val boundsInRoot: RectF
)

private const val CONTEXT_MENU_PREVIEW_DELAY_MS = 90L
private const val BUBBLE_HIGHLIGHT_DURATION_MS = 920L
private const val BUBBLE_HIGHLIGHT_MAX_ALPHA = 72
private const val BUBBLE_HIGHLIGHT_RAMP_UP_FRACTION = 0.16f
private const val BUBBLE_HIGHLIGHT_HOLD_FRACTION = 0.42f
private const val CONTEXT_MENU_BUBBLE_LOG_TAG = "ZynaBubbleLog"
private const val PHOTO_SOURCE_CORNER_RADIUS_DP = 10

private fun PhotoGroupMessageLayout.mediaFramesInView(layout: MessageCellLayout): List<RectF> {
    return mediaFrames.map { frame ->
        RectF(frame).apply {
            offset(layout.contentLeft.toFloat(), layout.contentTop.toFloat())
        }
    }
}

private fun MessageContent.debugSummary(): String {
    return when (this) {
        is MessageContent.Image -> "imageCaption=${caption.debugCaption()} placement=$captionPlacement"
        is MessageContent.PhotoGroup -> {
            "groupItems=${items.size} totalHint=$totalHint " +
                "groupCaption=${caption.debugCaption()} placement=$captionPlacement " +
                "layoutOverride=$layoutOverride"
        }
        is MessageContent.Voice -> {
            "voice duration=${audioInfo.durationMillis} waveform=${audioInfo.waveform.size} " +
                "isVoice=${audioInfo.isVoice}"
        }
        is MessageContent.Text -> "textLength=${body.length}"
        MessageContent.Redacted -> "redacted=true"
    }
}

private fun MessageContentLayout.debugSummary(): String {
    return when (this) {
        is PhotoGroupMessageLayout -> {
            val bottomCaptionStrip = if (
                captionLayout != null &&
                captionPlacement == CaptionPlacement.BOTTOM
            ) {
                height - mediaY - mediaHeight
            } else {
                0
            }
            "groupLayout captionLayout=${captionLayout != null} " +
                "hasHeader=$hasHeader mediaY=$mediaY media=${mediaWidth}x$mediaHeight " +
                "height=$height captionY=$captionY captionTextHeight=${captionLayout?.height ?: 0} " +
                "bottomCaptionStrip=$bottomCaptionStrip trailing=${height - mediaY - mediaHeight}"
        }
        is ImageMessageLayout -> {
            val bottomCaptionStrip = if (
                captionLayout != null &&
                captionPlacement == CaptionPlacement.BOTTOM
            ) {
                height - imageY - imageHeight
            } else {
                0
            }
            "imageLayout bare=$isBareImage hasHeader=$hasHeader hasCaption=$hasCaption " +
                "imageY=$imageY image=${imageWidth}x$imageHeight height=$height " +
                "captionY=$captionY captionTextHeight=${captionLayout?.height ?: 0} " +
                "bottomCaptionStrip=$bottomCaptionStrip trailing=${height - imageY - imageHeight}"
        }
        else -> "layout=${this::class.java.simpleName}"
    }
}

private fun String?.debugCaption(): String {
    if (this == null) {
        return "null"
    }
    val preview = replace('\n', ' ')
        .replace('\r', ' ')
        .take(32)
    return "len=$length blank=${isBlank()} preview='$preview'"
}

private fun RectF.toDebugString(): String {
    return "${left.roundToInt()},${top.roundToInt()}-" +
        "${right.roundToInt()},${bottom.roundToInt()} " +
        "${width().roundToInt()}x${height().roundToInt()}"
}
