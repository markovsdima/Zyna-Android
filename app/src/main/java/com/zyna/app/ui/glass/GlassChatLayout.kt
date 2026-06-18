package com.zyna.app.ui.glass

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.BuildConfig
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageReplyPreview
import com.zyna.app.ui.chat.render.MessageRenderModel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Chat view shell that gives the list and input glass a shared backdrop source. */
class GlassChatLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    val glassController = GlassBackdropController(this)
    val recyclerView = RecyclerView(context)
    val inputBar = GlassInputBarView(context, glassController)
    var onLoadOlderMessages: () -> Unit = {}
    var onRetryOutgoingEnvelope: (String) -> Unit = {}
    var onDiscardOutgoingEnvelope: (String) -> Unit = {}
    internal var onReplyToMessage: (MessageReplyPreview) -> Unit = {}
    var onRedactMessage: (String) -> Unit = {}
    var onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit = {}
    var onEvaluateVisibleReadReceiptCandidate: () -> Unit = {}

    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        includeFontPadding = true
    }
    private val composerErrorView = TextView(context).apply {
        gravity = Gravity.START
        textSize = 12f
        includeFontPadding = true
        visibility = GONE
    }

    private var palette = defaultPalette()
    private var imeBottomInset = 0
    private var navBottomInset = 0
    private var paletteApplied = false
    private var composerErrorMessage: String? = null
    private var isLoadingOlderMessages = false
    private var canLoadOlderMessages = false
    private var prefetchCheckPosted = false
    private var isContextMenuShowing = false
    private var isContextGestureActive = false
    private var recyclerAccessibilityBeforeMenu = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var hardwareBackdropCapture: HardwareBufferChatCapture? = null
    private var hardwareBackdropCaptureScheduled = false
    private var hardwareBackdropCaptureRequiresFreshImage = false
    private var recyclerDrawListenerAttached = false
    private var lastRecyclerDrawScrollOffset = Int.MIN_VALUE
    private var lastHardwareBackdropCaptureUptimeMs = 0L
    private var vulkanGlassRects: List<VulkanChatGlassRect> = emptyList()
    private var vulkanGlassCaptureBounds = Rect()
    private val vulkanGlassAdaptiveMaterial = VulkanGlassAdaptiveMaterialState()
    private var lastVulkanGlassAdaptiveUpdateNanos = 0L
    private var vulkanGlassAdaptiveRenderScheduled = false
    private var vulkanGlassPerfWindowStartNanos = 0L
    private var vulkanGlassPerfSamples = 0
    private var vulkanGlassPerfDrops = 0
    private var vulkanGlassPerfTotalNanos = 0L
    private var vulkanGlassPerfMaxNanos = 0L
    private var vulkanGlassPerfCaptureNanos = 0L
    private var vulkanGlassPerfCaptureMaxNanos = 0L
    private var vulkanGlassPerfSyncNanos = 0L
    private var vulkanGlassPerfSyncMaxNanos = 0L
    private var vulkanGlassPerfAcquireNanos = 0L
    private var vulkanGlassPerfAcquireMaxNanos = 0L
    private var vulkanGlassPerfOverlayNanos = 0L
    private var vulkanGlassPerfOverlayMaxNanos = 0L
    private var vulkanGlassPerfImportNanos = 0L
    private var vulkanGlassPerfImportMaxNanos = 0L
    private var vulkanGlassPerfRenderNanos = 0L
    private var vulkanGlassPerfRenderMaxNanos = 0L
    private val readReceiptCandidateEvaluationRunnable = Runnable {
        onEvaluateVisibleReadReceiptCandidate()
    }
    private val hardwareBackdropCaptureRunnable = Runnable {
        val requiresFreshImage = hardwareBackdropCaptureRequiresFreshImage
        hardwareBackdropCaptureScheduled = false
        hardwareBackdropCaptureRequiresFreshImage = false
        captureVulkanGlassBackdrop(discardPendingImagesBeforeDraw = requiresFreshImage)
        lastHardwareBackdropCaptureUptimeMs = SystemClock.uptimeMillis()
    }
    private val vulkanGlassAdaptiveRenderRunnable = Runnable {
        vulkanGlassAdaptiveRenderScheduled = false
        renderVulkanGlassAdaptiveTransitionFrame()
    }
    private val recyclerDrawListener = ViewTreeObserver.OnDrawListener {
        val scrollOffset = recyclerView.computeVerticalScrollOffset()
        if (scrollOffset != lastRecyclerDrawScrollOffset) {
            lastRecyclerDrawScrollOffset = scrollOffset
            scheduleVulkanGlassBackdropCapture(VULKAN_GLASS_SCROLL_CAPTURE_DELAY_MS)
        }
    }
    private val chatLayoutManager = LockableLinearLayoutManager(context).apply {
        reverseLayout = true
    }
    private val source = RecyclerViewGlassBackdropSource(recyclerView, palette.background)
    private val vulkanOverlay = VulkanChatOverlayView(context).apply {
        setOverlayEnabled(BuildConfig.DEBUG && ENABLE_VULKAN_CHAT_OVERLAY)
        onBackdropStats = { stats ->
            handleVulkanGlassBackdropStats(stats)
        }
    }
    private val contextMenuLayer = MessageContextMenuLayer(context, glassController).apply {
        onDismissRequested = {
            dismissMessageContextMenu()
        }
        onActionSelected = { message, action ->
            if (handleMessageContextAction(message, action)) {
                dismissMessageContextMenu()
            }
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(palette.background)

        recyclerView.apply {
            clipToPadding = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = null
            layoutManager = chatLayoutManager
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    glassController.invalidateBackdrop()
                    scheduleVulkanGlassBackdropCapture(VULKAN_GLASS_SCROLL_CAPTURE_DELAY_MS)
                    maybeLoadOlderMessages()
                    scheduleVisibleReadReceiptCandidateEvaluation()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    glassController.invalidateBackdrop()
                    if (
                        newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                        newState == RecyclerView.SCROLL_STATE_IDLE
                    ) {
                        forceVulkanGlassBackdropCapture()
                    } else {
                        scheduleVulkanGlassBackdropCapture(VULKAN_GLASS_SCROLL_CAPTURE_DELAY_MS)
                    }
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        maybeLoadOlderMessages()
                    }
                    scheduleVisibleReadReceiptCandidateEvaluation()
                }
            })
        }

        glassController.source = source
        addView(recyclerView)
        addView(vulkanOverlay)
        addView(emptyView)
        addView(composerErrorView)
        addView(inputBar)
        addView(contextMenuLayer)
        inputBar.setVulkanGlassBackgroundEnabled(isVulkanChatInputGlassEnabled())

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (imeBottomInset != ime || navBottomInset != nav) {
                imeBottomInset = ime
                navBottomInset = nav
                requestLayout()
                glassController.invalidateRegions()
            }
            insets
        }

        setPalette(palette)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.x >= recyclerView.left &&
            event.x < recyclerView.right &&
            event.y >= recyclerView.top &&
            event.y < recyclerView.bottom &&
            (inputBar.top <= 0 || event.y < inputBar.top)
        ) {
            forceVulkanGlassBackdropCapture()
        }
        return handled
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachRecyclerDrawListener()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        detachRecyclerDrawListener()
        removeCallbacks(readReceiptCandidateEvaluationRunnable)
        removeCallbacks(hardwareBackdropCaptureRunnable)
        removeCallbacks(vulkanGlassAdaptiveRenderRunnable)
        hardwareBackdropCaptureScheduled = false
        hardwareBackdropCaptureRequiresFreshImage = false
        vulkanGlassAdaptiveRenderScheduled = false
        vulkanOverlay.clearBackdropFrame()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hardwareBackdropCapture?.close()
        }
        hardwareBackdropCapture = null
        super.onDetachedFromWindow()
    }

    private fun attachRecyclerDrawListener() {
        if (recyclerDrawListenerAttached) {
            return
        }
        recyclerView.viewTreeObserver.addOnDrawListener(recyclerDrawListener)
        recyclerDrawListenerAttached = true
    }

    private fun detachRecyclerDrawListener() {
        if (!recyclerDrawListenerAttached) {
            return
        }
        val observer = recyclerView.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnDrawListener(recyclerDrawListener)
        }
        recyclerDrawListenerAttached = false
        lastRecyclerDrawScrollOffset = Int.MIN_VALUE
    }

    fun setPalette(newPalette: GlassPalette) {
        if (paletteApplied && palette == newPalette) {
            return
        }
        paletteApplied = true
        palette = newPalette
        source.fallbackColor = newPalette.background
        setBackgroundColor(newPalette.background)
        emptyView.setTextColor(newPalette.hint)
        inputBar.setPalette(newPalette)
        contextMenuLayer.setPalette(newPalette)
        glassController.invalidateBackdrop()
        scheduleVulkanGlassBackdropCapture()
    }

    fun setEmptyState(isEmpty: Boolean, isLoading: Boolean) {
        emptyView.text = if (isLoading) "Loading messages" else "No messages"
        emptyView.visibility = if (isEmpty) VISIBLE else GONE
    }

    fun setPaginationState(isLoadingOlder: Boolean, canLoadOlder: Boolean) {
        isLoadingOlderMessages = isLoadingOlder
        canLoadOlderMessages = canLoadOlder
    }

    fun setComposerState(isSending: Boolean, errorMessage: String?, errorColor: Int) {
        val normalizedError = errorMessage?.takeIf { it.isNotBlank() }
        inputBar.setSending(
            sending = isSending,
            sendFailed = normalizedError != null
        )

        if (composerErrorMessage == normalizedError && composerErrorView.currentTextColor == errorColor) {
            return
        }

        composerErrorMessage = normalizedError
        composerErrorView.text = normalizedError.orEmpty()
        composerErrorView.setTextColor(errorColor)
        composerErrorView.visibility = if (normalizedError == null) GONE else VISIBLE
        requestLayout()
        glassController.invalidateRegions()
    }

    fun invalidateGlassContent() {
        glassController.invalidateBackdrop()
        scheduleVulkanGlassBackdropCapture()
    }

    internal fun beginMessageContextMenuGesture(request: MessageContextMenuRequest): Boolean {
        isContextGestureActive = true
        setContextScrollLocked(true)
        val didBegin = contextMenuLayer.beginPreview(request)
        if (!didBegin) {
            isContextGestureActive = false
            setContextScrollLocked(false)
            return false
        }
        post { updateVulkanGlassRects() }
        return true
    }

    internal fun showMessageContextMenu(request: MessageContextMenuRequest): Boolean {
        if (!isContextGestureActive) {
            isContextGestureActive = true
            setContextScrollLocked(true)
        }
        val didShow = contextMenuLayer.show(request)
        if (!didShow) {
            dismissMessageContextMenu(animated = false)
            return false
        }
        if (!isContextMenuShowing) {
            recyclerAccessibilityBeforeMenu = recyclerView.importantForAccessibility
        }
        isContextMenuShowing = true
        recyclerView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        post { updateVulkanGlassRects() }
        return true
    }

    internal fun handleMessageContextGestureEvent(action: Int, rawX: Float, rawY: Float) {
        contextMenuLayer.handleGestureEvent(action, rawX, rawY)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isContextGestureActive = false
            if (!isContextMenuShowing) {
                contextMenuLayer.dismiss(animated = true)
                setContextScrollLocked(false)
            }
        }
    }

    internal fun dismissMessageContextMenu(animated: Boolean = true) {
        if (!isContextMenuShowing) {
            contextMenuLayer.dismiss(animated = animated)
            if (!isContextGestureActive) {
                setContextScrollLocked(false)
            }
            post { updateVulkanGlassRects() }
            return
        }
        isContextMenuShowing = false
        recyclerView.importantForAccessibility = recyclerAccessibilityBeforeMenu
        if (!isContextGestureActive) {
            setContextScrollLocked(false)
        }
        contextMenuLayer.dismiss(animated)
        post { updateVulkanGlassRects() }
    }

    fun prefetchOlderMessagesIfNeeded() {
        if (prefetchCheckPosted) {
            return
        }

        prefetchCheckPosted = true
        post {
            prefetchCheckPosted = false
            maybeLoadOlderMessages()
        }
    }

    fun scheduleVisibleReadReceiptCandidateEvaluation(
        delayMillis: Long = READ_RECEIPT_SCROLL_DEBOUNCE_MS
    ) {
        removeCallbacks(readReceiptCandidateEvaluationRunnable)
        postDelayed(readReceiptCandidateEvaluationRunnable, delayMillis)
    }

    private fun maybeLoadOlderMessages() {
        if (isLoadingOlderMessages || !canLoadOlderMessages) {
            return
        }

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = recyclerView.adapter?.itemCount ?: return
        if (itemCount == 0) {
            return
        }
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        val shouldLoadMore =
            itemCount < OLDER_PREFETCH_TARGET_ITEMS || (
                lastVisibleItem != RecyclerView.NO_POSITION &&
                    lastVisibleItem >= itemCount - LOAD_OLDER_THRESHOLD
            )

        if (shouldLoadMore) {
            onLoadOlderMessages()
        }
    }

    fun scrollToBottom(animated: Boolean) {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            return
        }
        if (animated) {
            recyclerView.smoothScrollToPosition(0)
        } else {
            recyclerView.scrollToPosition(0)
        }
        glassController.invalidateBackdrop()
        scheduleVulkanGlassBackdropCapture()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        recyclerView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        vulkanOverlay.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        inputBar.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1, height / 2), MeasureSpec.AT_MOST)
        )
        emptyView.measure(
            MeasureSpec.makeMeasureSpec((width - 48.dpToPx(density)).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        composerErrorView.measure(
            MeasureSpec.makeMeasureSpec((width - 32.dpToPx(density)).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        contextMenuLayer.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        recyclerView.layout(0, 0, width, height)
        vulkanOverlay.layout(0, 0, width, height)

        val bottomInset = if (imeBottomInset > 0) imeBottomInset else navBottomInset
        val bottomMargin = 6.dpToPx(density)
        val inputTop = (height - bottomInset - bottomMargin - inputBar.measuredHeight)
            .coerceAtLeast(0)
        inputBar.layout(0, inputTop, width, inputTop + inputBar.measuredHeight)
        vulkanOverlay.setInputBarBounds(
            inputBar.left,
            inputBar.top,
            inputBar.right,
            inputBar.bottom
        )
        val contentBottom = layoutComposerError(inputTop, width)

        val emptyWidth = emptyView.measuredWidth
        val emptyHeight = emptyView.measuredHeight
        val emptyLeft = (width - emptyWidth) / 2
        val availableBottom = contentBottom.coerceAtLeast(0)
        val emptyTop = ((availableBottom - emptyHeight) / 2).coerceAtLeast(0)
        emptyView.layout(emptyLeft, emptyTop, emptyLeft + emptyWidth, emptyTop + emptyHeight)

        updateRecyclerPadding(contentBottom)
        contextMenuLayer.layout(0, 0, width, height)
        glassController.invalidateRegions()
        updateVulkanGlassRects()
        scheduleVisibleReadReceiptCandidateEvaluation(READ_RECEIPT_CONTENT_UPDATE_DELAY_MS)
    }

    private fun updateVulkanGlassRects() {
        if (!isVulkanGlassBackdropEnabled() || width <= 0 || height <= 0) {
            vulkanGlassRects = emptyList()
            vulkanOverlay.clearBackdropFrame()
            return
        }

        val nextRects = ArrayList<VulkanChatGlassRect>(4)
        if (ENABLE_VULKAN_CHAT_GLASS_PREVIEW_RECT) {
            addVulkanGlassPreviewRect(nextRects)
        } else if (isVulkanChatInputGlassEnabled()) {
            inputBar.collectVulkanGlassRects(nextRects)
        }
        val nextCaptureBounds = buildVulkanGlassCaptureBounds(nextRects)
        if (vulkanGlassRects == nextRects && vulkanGlassCaptureBounds == nextCaptureBounds) {
            return
        }

        vulkanGlassRects = nextRects
        vulkanGlassCaptureBounds = Rect(nextCaptureBounds)
        if (ENABLE_VULKAN_CHAT_VERBOSE_TIMING) {
            Log.d(
                HARDWARE_BUFFER_CAPTURE_TAG,
                "AHB glass rects changed " +
                    "count=${nextRects.size} " +
                    "capture=${nextCaptureBounds.width()}x${nextCaptureBounds.height()} " +
                    "origin=${nextCaptureBounds.left},${nextCaptureBounds.top} " +
                    "input=${inputBar.width}x${inputBar.height} " +
                    "menu=${contextMenuLayer.width}x${contextMenuLayer.height}"
            )
        }
        if (nextRects.isEmpty() || nextCaptureBounds.isEmpty) {
            removeCallbacks(hardwareBackdropCaptureRunnable)
            removeCallbacks(vulkanGlassAdaptiveRenderRunnable)
            hardwareBackdropCaptureScheduled = false
            hardwareBackdropCaptureRequiresFreshImage = false
            vulkanGlassAdaptiveRenderScheduled = false
            vulkanOverlay.clearBackdropFrame()
        } else {
            scheduleVulkanGlassBackdropCapture(delayMillis = 0L)
        }
    }

    private fun addVulkanGlassPreviewRect(out: MutableList<VulkanChatGlassRect>) {
        if (!ENABLE_VULKAN_CHAT_GLASS_PREVIEW_RECT || inputBar.top <= 0) {
            return
        }

        val horizontal = 18f.dpToPx(density)
        val gap = 14f.dpToPx(density)
        val previewHeight = 82f.dpToPx(density)
        val minTop = 18f.dpToPx(density)
        val bottom = (inputBar.top.toFloat() - gap).coerceAtLeast(minTop)
        val top = (bottom - previewHeight).coerceAtLeast(minTop)
        if (bottom - top < 32f.dpToPx(density)) {
            return
        }

        out.add(
            VulkanChatGlassRect(
                left = horizontal,
                top = top,
                right = width.toFloat() - horizontal,
                bottom = bottom,
                cornerRadius = 26f.dpToPx(density),
                opacity = 1f,
                bezelWidth = 36f.dpToPx(density),
                glassThickness = 55f.dpToPx(density)
            )
        )
    }

    private fun buildVulkanGlassCaptureBounds(rects: List<VulkanChatGlassRect>): Rect {
        if (rects.isEmpty() || recyclerView.width <= 0 || recyclerView.height <= 0) {
            return Rect()
        }
        val margin = (
            rects.maxOfOrNull { it.glassThickness } ?: 55f.dpToPx(density)
        ).plus(16f.dpToPx(density)).toInt()
        val left = rects.minOf { it.left }.toInt() - margin
        val top = rects.minOf { it.top }.toInt() - margin
        val right = rects.maxOf { it.right }.toInt() + margin
        val bottom = rects.maxOf { it.bottom }.toInt() + margin
        return Rect(
            left.coerceIn(0, recyclerView.width),
            top.coerceIn(0, recyclerView.height),
            right.coerceIn(0, recyclerView.width),
            bottom.coerceIn(0, recyclerView.height)
        )
    }

    private fun scheduleVulkanGlassBackdropCapture(
        delayMillis: Long = VULKAN_GLASS_CAPTURE_DELAY_MS,
        discardPendingImagesBeforeDraw: Boolean = false
    ) {
        if (!isVulkanGlassBackdropEnabled() || vulkanGlassRects.isEmpty()) {
            return
        }

        if (delayMillis <= 0L) {
            if (hardwareBackdropCaptureScheduled) {
                hardwareBackdropCaptureRequiresFreshImage =
                    hardwareBackdropCaptureRequiresFreshImage || discardPendingImagesBeforeDraw
                return
            }
            hardwareBackdropCaptureScheduled = true
            hardwareBackdropCaptureRequiresFreshImage = discardPendingImagesBeforeDraw
            val elapsed = SystemClock.uptimeMillis() - lastHardwareBackdropCaptureUptimeMs
            val throttleDelay = (
                VULKAN_GLASS_REALTIME_CAPTURE_MIN_INTERVAL_MS - elapsed
            ).coerceAtLeast(0L)
            if (throttleDelay == 0L) {
                postOnAnimation(hardwareBackdropCaptureRunnable)
            } else {
                postDelayed(hardwareBackdropCaptureRunnable, throttleDelay)
            }
            return
        }

        removeCallbacks(hardwareBackdropCaptureRunnable)
        hardwareBackdropCaptureScheduled = true
        hardwareBackdropCaptureRequiresFreshImage = discardPendingImagesBeforeDraw
        postDelayed(hardwareBackdropCaptureRunnable, delayMillis)
    }

    private fun forceVulkanGlassBackdropCapture() {
        if (!isVulkanGlassBackdropEnabled() || vulkanGlassRects.isEmpty()) {
            return
        }

        removeCallbacks(hardwareBackdropCaptureRunnable)
        hardwareBackdropCaptureScheduled = true
        hardwareBackdropCaptureRequiresFreshImage = true
        postOnAnimation(hardwareBackdropCaptureRunnable)
    }

    private fun captureVulkanGlassBackdrop(discardPendingImagesBeforeDraw: Boolean = false) {
        if (
            !isVulkanGlassBackdropEnabled() ||
            vulkanGlassRects.isEmpty() ||
            !isAttachedToWindow ||
            recyclerView.width <= 0 ||
            recyclerView.height <= 0 ||
            vulkanGlassCaptureBounds.isEmpty
        ) {
            return
        }

        val captureBounds = Rect(vulkanGlassCaptureBounds)
        val rects = vulkanGlassRects.toList()
        val capture = hardwareBackdropCapture ?: HardwareBufferChatCapture().also {
            hardwareBackdropCapture = it
        }
        val didRequest = capture.captureAsync(
            source = recyclerView,
            width = captureBounds.width(),
            height = captureBounds.height(),
            backgroundColor = palette.background,
            sourceLeft = captureBounds.left.toFloat(),
            sourceTop = captureBounds.top.toFloat(),
            discardPendingImagesBeforeDraw = discardPendingImagesBeforeDraw
        ) { frame ->
            handleVulkanGlassBackdropFrame(
                frame = frame,
                rects = rects,
                captureBounds = captureBounds
            )
        }
        if (!didRequest) {
            if (ENABLE_VULKAN_CHAT_PERF_LOGGING) {
                recordVulkanGlassPerfDrop()
            }
            if (ENABLE_VULKAN_CHAT_VERBOSE_TIMING) {
                Log.w(HARDWARE_BUFFER_CAPTURE_TAG, "AHB glass backdrop request failed")
            }
        }
    }

    private fun handleVulkanGlassBackdropFrame(
        frame: HardwareBufferChatCapture.CapturedFrame,
        rects: List<VulkanChatGlassRect>,
        captureBounds: Rect
    ) {
        if (
            !isVulkanGlassBackdropEnabled() ||
            !isAttachedToWindow ||
            rects.isEmpty() ||
            vulkanGlassRects.isEmpty()
        ) {
            frame.close()
            return
        }

        val overlayStartNanos = SystemClock.elapsedRealtimeNanos()
        val adaptiveRects = applyVulkanGlassAdaptiveMaterial(
            rects = rects,
            nowNanos = overlayStartNanos
        )
        val overlayResult = vulkanOverlay.setBackdropFrame(
            frame = frame,
            rects = adaptiveRects,
            textureLeft = captureBounds.left.toFloat(),
            textureTop = captureBounds.top.toFloat()
        )
        val overlayCallNanos = SystemClock.elapsedRealtimeNanos() - overlayStartNanos
        val tickTotalNanos = frame.renderNanos + overlayCallNanos

        if (ENABLE_VULKAN_CHAT_VERBOSE_TIMING) {
            val renderMs = frame.renderNanos / 1_000_000.0
            Log.d(
                HARDWARE_BUFFER_CAPTURE_TAG,
                "AHB glass backdrop queued " +
                    "rects=${rects.size} " +
                    "origin=${captureBounds.left},${captureBounds.top} " +
                    "size=${frame.width}x${frame.height} " +
                    "format=${frame.format} " +
                    "usage=0x${frame.usage.toString(16)} " +
                    "renderMs=$renderMs " +
                    "ensureMs=${frame.ensureTargetNanos.msString()} " +
                    "recordMs=${frame.recordNanos.msString()} " +
                    "syncMs=${frame.syncNanos.msString()} " +
                    "acquireMs=${frame.acquireNanos.msString()} " +
                    "bufferMs=${frame.hardwareBufferNanos.msString()} " +
                    "renderResult=${frame.renderResult}"
            )
            Log.d(
                HARDWARE_BUFFER_CAPTURE_TAG,
                "AHB glass tick " +
                    "main=${Looper.myLooper() == Looper.getMainLooper()} " +
                    "rects=${rects.size} " +
                    "size=${frame.width}x${frame.height} " +
                    "totalMs=${tickTotalNanos.msString()} " +
                    "captureCallMs=${frame.renderNanos.msString()} " +
                    "captureRenderMs=${frame.renderNanos.msString()} " +
                    "recordMs=${frame.recordNanos.msString()} " +
                    "syncMs=${frame.syncNanos.msString()} " +
                    "acquireMs=${frame.acquireNanos.msString()} " +
                    "overlayCallMs=${overlayCallNanos.msString()} " +
                    "overlayTotalMs=${overlayResult.totalNanos.msString()} " +
                    "importMs=${overlayResult.importNanos.msString()} " +
                    "renderCallMs=${overlayResult.renderCallNanos.msString()} " +
                    "imported=${overlayResult.imported} " +
                    "rendered=${overlayResult.rendered} " +
                    "continue=${overlayResult.willContinueRendering} " +
                    "overlayMain=${overlayResult.mainThread}"
            )
        }
        if (ENABLE_VULKAN_CHAT_PERF_LOGGING) {
            recordVulkanGlassPerfSample(
                tickTotalNanos = tickTotalNanos,
                captureCallNanos = frame.renderNanos,
                frame = frame,
                overlayCallNanos = overlayCallNanos,
                overlayResult = overlayResult
            )
        }
    }

    private fun applyVulkanGlassAdaptiveMaterial(
        rects: List<VulkanChatGlassRect>,
        nowNanos: Long
    ): List<VulkanChatGlassRect> {
        if (rects.isEmpty()) {
            return rects
        }

        val material = vulkanGlassAdaptiveMaterial.advance(nextVulkanGlassAdaptiveDt(nowNanos))
        if (isVulkanChatInputGlassEnabled()) {
            inputBar.setAdaptiveMaterial(material)
        }
        scheduleVulkanGlassAdaptiveRenderIfNeeded()

        val adapted = ArrayList<VulkanChatGlassRect>(rects.size)
        for (rect in rects) {
            adapted.add(
                rect.copy(
                    adaptiveAppearance = material.appearance,
                    adaptiveContrast = material.contrast
                )
            )
        }
        return adapted
    }

    private fun handleVulkanGlassBackdropStats(stats: VulkanGlassBackdropStats) {
        if (
            !isVulkanGlassBackdropEnabled() ||
            !isAttachedToWindow ||
            vulkanGlassRects.isEmpty() ||
            vulkanGlassCaptureBounds.isEmpty
        ) {
            return
        }

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val dt = nextVulkanGlassAdaptiveDt(nowNanos)
        val material = vulkanGlassAdaptiveMaterial.ingest(
            stats = stats,
            dt = dt
        )
        if (isVulkanChatInputGlassEnabled()) {
            inputBar.setAdaptiveMaterial(material)
        }
        val didRender = renderVulkanGlassMaterial(material)
        if (!didRender) {
            scheduleVulkanGlassBackdropCapture(delayMillis = 0L)
            return
        }
        scheduleVulkanGlassAdaptiveRenderIfNeeded()
    }

    private fun renderVulkanGlassAdaptiveTransitionFrame() {
        if (
            !isVulkanGlassBackdropEnabled() ||
            !isAttachedToWindow ||
            vulkanGlassRects.isEmpty() ||
            vulkanGlassCaptureBounds.isEmpty
        ) {
            return
        }

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val material = vulkanGlassAdaptiveMaterial.advance(nextVulkanGlassAdaptiveDt(nowNanos))
        if (isVulkanChatInputGlassEnabled()) {
            inputBar.setAdaptiveMaterial(material)
        }
        val didRender = renderVulkanGlassMaterial(material)
        if (!didRender) {
            scheduleVulkanGlassBackdropCapture(delayMillis = 0L)
            return
        }
        scheduleVulkanGlassAdaptiveRenderIfNeeded()
    }

    private fun nextVulkanGlassAdaptiveDt(nowNanos: Long): Float {
        val previousNanos = lastVulkanGlassAdaptiveUpdateNanos
        val dt = if (previousNanos > 0L) {
            ((nowNanos - previousNanos).coerceAtLeast(0L) / 1_000_000_000f)
        } else {
            1f / 120f
        }
        lastVulkanGlassAdaptiveUpdateNanos = nowNanos
        return dt
    }

    private fun renderVulkanGlassMaterial(material: GlassAdaptiveMaterial): Boolean {
        val adaptedRects = applyVulkanGlassMaterialToRects(vulkanGlassRects, material)
        return vulkanOverlay.updateBackdropRects(
            rects = adaptedRects,
            textureLeft = vulkanGlassCaptureBounds.left.toFloat(),
            textureTop = vulkanGlassCaptureBounds.top.toFloat()
        ).rendered
    }

    private fun scheduleVulkanGlassAdaptiveRenderIfNeeded() {
        if (
            !vulkanGlassAdaptiveMaterial.isAnimating ||
            vulkanGlassAdaptiveRenderScheduled ||
            hardwareBackdropCaptureScheduled ||
            !isVulkanGlassBackdropEnabled() ||
            vulkanGlassRects.isEmpty() ||
            vulkanGlassCaptureBounds.isEmpty ||
            !isAttachedToWindow
        ) {
            return
        }
        vulkanGlassAdaptiveRenderScheduled = true
        postOnAnimation(vulkanGlassAdaptiveRenderRunnable)
    }

    private fun applyVulkanGlassMaterialToRects(
        rects: List<VulkanChatGlassRect>,
        material: GlassAdaptiveMaterial
    ): List<VulkanChatGlassRect> {
        val adapted = ArrayList<VulkanChatGlassRect>(rects.size)
        for (rect in rects) {
            adapted.add(
                rect.copy(
                    adaptiveAppearance = material.appearance,
                    adaptiveContrast = material.contrast
                )
            )
        }
        return adapted
    }

    private fun recordVulkanGlassPerfDrop() {
        if (vulkanGlassPerfWindowStartNanos == 0L) {
            vulkanGlassPerfWindowStartNanos = SystemClock.elapsedRealtimeNanos()
        }
        vulkanGlassPerfDrops += 1
    }

    private fun recordVulkanGlassPerfSample(
        tickTotalNanos: Long,
        captureCallNanos: Long,
        frame: HardwareBufferChatCapture.CapturedFrame,
        overlayCallNanos: Long,
        overlayResult: BackdropFrameResult
    ) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (vulkanGlassPerfWindowStartNanos == 0L) {
            vulkanGlassPerfWindowStartNanos = nowNanos
        }
        vulkanGlassPerfSamples += 1
        vulkanGlassPerfTotalNanos += tickTotalNanos
        vulkanGlassPerfMaxNanos = max(vulkanGlassPerfMaxNanos, tickTotalNanos)
        vulkanGlassPerfCaptureNanos += captureCallNanos
        vulkanGlassPerfCaptureMaxNanos = max(vulkanGlassPerfCaptureMaxNanos, captureCallNanos)
        vulkanGlassPerfSyncNanos += frame.syncNanos
        vulkanGlassPerfSyncMaxNanos = max(vulkanGlassPerfSyncMaxNanos, frame.syncNanos)
        vulkanGlassPerfAcquireNanos += frame.acquireNanos
        vulkanGlassPerfAcquireMaxNanos = max(vulkanGlassPerfAcquireMaxNanos, frame.acquireNanos)
        vulkanGlassPerfOverlayNanos += overlayCallNanos
        vulkanGlassPerfOverlayMaxNanos = max(vulkanGlassPerfOverlayMaxNanos, overlayCallNanos)
        vulkanGlassPerfImportNanos += overlayResult.importNanos
        vulkanGlassPerfImportMaxNanos = max(vulkanGlassPerfImportMaxNanos, overlayResult.importNanos)
        vulkanGlassPerfRenderNanos += overlayResult.renderCallNanos
        vulkanGlassPerfRenderMaxNanos = max(vulkanGlassPerfRenderMaxNanos, overlayResult.renderCallNanos)

        val windowNanos = nowNanos - vulkanGlassPerfWindowStartNanos
        if (windowNanos < VULKAN_GLASS_PERF_LOG_WINDOW_NANOS) {
            return
        }

        val samples = vulkanGlassPerfSamples.coerceAtLeast(1)
        val hz = samples * 1_000_000_000.0 / windowNanos
        Log.d(
            HARDWARE_BUFFER_CAPTURE_TAG,
            "AHB glass perf " +
                "samples=$vulkanGlassPerfSamples " +
                "drops=$vulkanGlassPerfDrops " +
                "hz=${hz.formatOneDecimal()} " +
                "main=${Looper.myLooper() == Looper.getMainLooper()} " +
                "avgMs=${(vulkanGlassPerfTotalNanos / samples).msString()} " +
                "maxMs=${vulkanGlassPerfMaxNanos.msString()} " +
                "captureAvgMs=${(vulkanGlassPerfCaptureNanos / samples).msString()} " +
                "captureMaxMs=${vulkanGlassPerfCaptureMaxNanos.msString()} " +
                "syncAvgMs=${(vulkanGlassPerfSyncNanos / samples).msString()} " +
                "syncMaxMs=${vulkanGlassPerfSyncMaxNanos.msString()} " +
                "acquireAvgMs=${(vulkanGlassPerfAcquireNanos / samples).msString()} " +
                "acquireMaxMs=${vulkanGlassPerfAcquireMaxNanos.msString()} " +
                "overlayAvgMs=${(vulkanGlassPerfOverlayNanos / samples).msString()} " +
                "overlayMaxMs=${vulkanGlassPerfOverlayMaxNanos.msString()} " +
                "importAvgMs=${(vulkanGlassPerfImportNanos / samples).msString()} " +
                "importMaxMs=${vulkanGlassPerfImportMaxNanos.msString()} " +
                "renderAvgMs=${(vulkanGlassPerfRenderNanos / samples).msString()} " +
                "renderMaxMs=${vulkanGlassPerfRenderMaxNanos.msString()}"
        )
        resetVulkanGlassPerfWindow(nowNanos)
    }

    private fun resetVulkanGlassPerfWindow(startNanos: Long) {
        vulkanGlassPerfWindowStartNanos = startNanos
        vulkanGlassPerfSamples = 0
        vulkanGlassPerfDrops = 0
        vulkanGlassPerfTotalNanos = 0L
        vulkanGlassPerfMaxNanos = 0L
        vulkanGlassPerfCaptureNanos = 0L
        vulkanGlassPerfCaptureMaxNanos = 0L
        vulkanGlassPerfSyncNanos = 0L
        vulkanGlassPerfSyncMaxNanos = 0L
        vulkanGlassPerfAcquireNanos = 0L
        vulkanGlassPerfAcquireMaxNanos = 0L
        vulkanGlassPerfOverlayNanos = 0L
        vulkanGlassPerfOverlayMaxNanos = 0L
        vulkanGlassPerfImportNanos = 0L
        vulkanGlassPerfImportMaxNanos = 0L
        vulkanGlassPerfRenderNanos = 0L
        vulkanGlassPerfRenderMaxNanos = 0L
    }

    private fun isVulkanGlassBackdropEnabled(): Boolean {
        return BuildConfig.DEBUG &&
            ENABLE_VULKAN_CHAT_GLASS_BACKDROP &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private fun isVulkanChatInputGlassEnabled(): Boolean {
        return ENABLE_VULKAN_CHAT_INPUT_GLASS &&
            !ENABLE_VULKAN_CHAT_GLASS_PREVIEW_RECT &&
            isVulkanGlassBackdropEnabled() &&
            NativeVulkanChat.isAvailable
    }

    private fun handleMessageContextAction(
        message: MessageRenderModel,
        action: MessageContextMenuAction
    ): Boolean {
        return when (action) {
            MessageContextMenuAction.REPLY -> {
                message.toReplyPreviewOrNull()?.let(onReplyToMessage)
                true
            }
            MessageContextMenuAction.COPY -> {
                copyMessageText(message)
                true
            }
            MessageContextMenuAction.DELETE -> {
                val messageId = message.redactionTargetMessageId ?: return true
                val target = contextMenuLayer.captureSelectedPaintSplashTarget(this)
                if (target == null) {
                    onRedactMessage(messageId)
                    return true
                }
                val startBurst = {
                    dismissMessageContextMenu(animated = false)
                    target.hideSource()
                    vulkanOverlay.addPaintSplash(target)
                    onRedactMessage(messageId)
                }
                if (!contextMenuLayer.playSelectedCellDeleteAnticipation(startBurst)) {
                    startBurst()
                }
                false
            }
            MessageContextMenuAction.RETRY_SEND -> {
                message.outgoingEnvelopeId?.let(onRetryOutgoingEnvelope)
                true
            }
            MessageContextMenuAction.REMOVE_FAILED_SEND -> {
                message.outgoingEnvelopeId?.let(onDiscardOutgoingEnvelope)
                true
            }
            MessageContextMenuAction.DEBUG_MARK_FAILED -> {
                message.outgoingEnvelopeId?.let(onDebugMarkOutgoingEnvelopeFailed)
                true
            }
        }
    }

    private fun copyMessageText(message: MessageRenderModel) {
        val text = when (val content = message.content) {
            is MessageContent.Text -> content.body
            MessageContent.Redacted -> null
        }?.takeIf { it.isNotBlank() } ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun MessageRenderModel.toReplyPreviewOrNull(): MessageReplyPreview? {
        val eventId = eventId?.takeIf { it.isNotBlank() } ?: return null
        if (content is MessageContent.Redacted || outgoingEnvelopeId != null) {
            return null
        }
        val body = when (val currentContent = content) {
            is MessageContent.Text -> currentContent.body
            MessageContent.Redacted -> return null
        }.takeIf { it.isNotBlank() } ?: return null
        return MessageReplyPreview(
            eventId = eventId,
            senderId = senderId,
            senderText = senderText,
            body = body
        )
    }

    private fun setContextScrollLocked(locked: Boolean) {
        if (chatLayoutManager.isScrollLocked == locked) {
            return
        }
        chatLayoutManager.isScrollLocked = locked
        if (locked) {
            recyclerView.stopScroll()
        }
    }

    private fun layoutComposerError(inputTop: Int, width: Int): Int {
        if (composerErrorView.visibility != VISIBLE) {
            return inputTop
        }

        val horizontal = 16.dpToPx(density)
        val gap = 4.dpToPx(density)
        val errorBottom = (inputTop - gap).coerceAtLeast(0)
        val errorTop = (errorBottom - composerErrorView.measuredHeight).coerceAtLeast(0)
        composerErrorView.layout(
            horizontal,
            errorTop,
            (width - horizontal).coerceAtLeast(horizontal),
            errorTop + composerErrorView.measuredHeight
        )
        return errorTop
    }

    private fun updateRecyclerPadding(contentBottom: Int) {
        val horizontal = 12.dpToPx(density)
        val top = 12.dpToPx(density)
        val bottom = (height - contentBottom) + 12.dpToPx(density)
        if (
            recyclerView.paddingLeft != horizontal ||
            recyclerView.paddingTop != top ||
            recyclerView.paddingRight != horizontal ||
            recyclerView.paddingBottom != bottom
        ) {
            recyclerView.setPadding(horizontal, top, horizontal, bottom)
        }
    }
}

private class LockableLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    var isScrollLocked: Boolean = false

    override fun canScrollVertically(): Boolean {
        return !isScrollLocked && super.canScrollVertically()
    }
}

private class VulkanGlassAdaptiveMaterialState {
    private var initialized = false
    private var filteredLuma = 0.5f
    private var targetAppearance = 1f
    private var targetContrast = 0f
    private var appearance = 1f
    private var contrast = 0f

    val isAnimating: Boolean
        get() = initialized &&
            (abs(appearance - targetAppearance) > 0.003f ||
                abs(contrast - targetContrast) > 0.003f)

    fun ingest(stats: VulkanGlassBackdropStats, dt: Float): GlassAdaptiveMaterial {
        val safeDt = max(dt, 1f / 120f)
        if (!initialized) {
            initialized = true
            filteredLuma = stats.meanLuma
            targetAppearance = appearanceTarget(filteredLuma)
            targetContrast = contrastTarget(stats)
            appearance = targetAppearance
            contrast = targetContrast
            return material()
        }

        val sampleAlpha = alpha(dt = safeDt, tau = 0.12f)
        filteredLuma += (stats.meanLuma - filteredLuma) * sampleAlpha
        targetAppearance = appearanceTarget(filteredLuma)
        targetContrast = contrastTarget(stats)
        return advance(safeDt)
    }

    fun advance(dt: Float): GlassAdaptiveMaterial {
        if (!initialized) {
            return material()
        }
        val safeDt = max(dt, 1f / 120f)
        val appearanceAlpha = alpha(dt = safeDt, tau = 0.24f)
        val contrastAlpha = alpha(dt = safeDt, tau = 0.18f)
        appearance += (targetAppearance - appearance) * appearanceAlpha
        contrast += (targetContrast - contrast) * contrastAlpha
        return material()
    }

    private fun material(): GlassAdaptiveMaterial {
        return GlassAdaptiveMaterial(
            appearance = appearance.coerceIn(0f, 1f),
            contrast = contrast.coerceIn(0f, 1f)
        )
    }

    private fun contrastTarget(stats: VulkanGlassBackdropStats): Float {
        val stdDev = sqrt(max(stats.variance, 0f))
        val mixedExtremes = min(stats.brightFraction, stats.darkFraction)
        val dominantExtreme = max(stats.brightFraction, stats.darkFraction)
        return (stdDev * 2.7f + mixedExtremes * 1.6f + dominantExtreme * 0.2f)
            .coerceIn(0f, 1f)
    }

    private fun appearanceTarget(luma: Float): Float {
        return if (luma >= APPEARANCE_SWITCH_LUMA) 1f else 0f
    }

    private fun alpha(dt: Float, tau: Float): Float {
        val safeTau = max(tau, 0.001f)
        return 1f - exp((-dt / safeTau).toDouble()).toFloat()
    }

    private companion object {
        const val APPEARANCE_SWITCH_LUMA = 0.50f
    }
}

private const val ENABLE_VULKAN_CHAT_OVERLAY = true
private const val ENABLE_VULKAN_CHAT_GLASS_BACKDROP = true
private const val ENABLE_VULKAN_CHAT_GLASS_PREVIEW_RECT = false
private const val ENABLE_VULKAN_CHAT_INPUT_GLASS = true
private const val ENABLE_VULKAN_CHAT_VERBOSE_TIMING = false
private const val ENABLE_VULKAN_CHAT_PERF_LOGGING = false
private const val HARDWARE_BUFFER_CAPTURE_TAG = "ZynaHwBufferCapture"
private const val VULKAN_GLASS_CAPTURE_DELAY_MS = 32L
private const val VULKAN_GLASS_SCROLL_CAPTURE_DELAY_MS = 0L
private const val VULKAN_GLASS_REALTIME_CAPTURE_MIN_INTERVAL_MS = 0L
private const val VULKAN_GLASS_PERF_LOG_WINDOW_NANOS = 1_000_000_000L
private const val LOAD_OLDER_THRESHOLD = 240
private const val OLDER_PREFETCH_TARGET_ITEMS = 1_000
private const val READ_RECEIPT_SCROLL_DEBOUNCE_MS = 150L
private const val READ_RECEIPT_CONTENT_UPDATE_DELAY_MS = 50L

private fun defaultPalette(): GlassPalette {
    return GlassPalette(
        background = 0xfffbfbff.toInt(),
        glassTint = 0xb8ffffff.toInt(),
        glassTintStrong = 0xd9ffffff.toInt(),
        stroke = 0x24000000,
        text = 0xff15151a.toInt(),
        hint = 0x9915151a.toInt()
    )
}

private fun Double.formatOneDecimal(): String {
    return "%.1f".format(this)
}
