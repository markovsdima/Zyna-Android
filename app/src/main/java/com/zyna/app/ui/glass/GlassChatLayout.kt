package com.zyna.app.ui.glass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.BuildConfig
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.ui.chat.render.MessageCellView
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageEditPreview
import com.zyna.app.ui.chat.render.MessageForwardPreview
import com.zyna.app.ui.chat.render.MessageReplyPreview
import com.zyna.app.ui.chat.render.MessageRenderModel
import com.zyna.app.ui.chat.render.PaintSplashTarget
import com.zyna.app.util.ZynaPerfLog
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Chat view shell that gives the list and input glass a shared backdrop source. */
internal class GlassChatLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val sharedVulkanOverlay: VulkanChatOverlayView? = null,
    private val sharedForegroundHost: FrameLayout? = null
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    val glassController = GlassBackdropController(this)
    val recyclerView = RecyclerView(context)
    val inputBar = GlassInputBarView(context, glassController)
    var onLoadOlderMessages: () -> Unit = {}
    var onLoadNewerMessages: () -> Unit = {}
    var onScrollToLiveEdge: () -> Unit = {}
    var onRetryOutgoingEnvelope: (String) -> Unit = {}
    var onDiscardOutgoingEnvelope: (String) -> Unit = {}
    internal var onReplyToMessage: (MessageReplyPreview) -> Unit = {}
    internal var onEditMessage: (MessageEditPreview) -> Unit = {}
    internal var onForwardMessage: (MessageForwardPreview) -> Unit = {}
    var onRedactMessage: (String) -> Unit = {}
    var onRedactMessages: (List<String>) -> Unit = { messageIds ->
        messageIds.forEach { messageId -> onRedactMessage(messageId) }
    }
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
    private var canLoadNewerMessages = false
    private var isAtLiveEdge = true
    private var isScrollToLiveButtonVisible = false
    private var isScrollToLiveButtonActionPending = false
    private var hasScrollToLiveButtonStableViewport = false
    private var prefetchCheckPosted = false
    private var isContextMenuShowing = false
    private var isContextGestureActive = false
    private var recyclerAccessibilityBeforeMenu = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var teleportSnapshotView: ImageView? = null
    private var teleportSnapshotBitmap: Bitmap? = null
    private var teleportAnimator: ValueAnimator? = null
    private var teleportDirection = ChatTeleportDirection.TO_OLDER
    private var scrollToLiveButtonAnimator: ValueAnimator? = null
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
    private var attachStartNanos = 0L
    private var measureLogCount = 0
    private var layoutLogCount = 0
    private var dispatchDrawLogCount = 0
    private var didLogFirstRecyclerPreDraw = false
    private val layoutLocationOnScreen = IntArray(2)
    private val overlayLocationOnScreen = IntArray(2)
    private val foregroundHostLocationOnScreen = IntArray(2)
    private var inputBarLocalLeft = 0
    private var inputBarLocalTop = 0
    private var inputBarLocalRight = 0
    private var inputBarLocalBottom = 0
    private var externalInputBarPreDrawListenerAttached = false
    private val externalInputBarPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        syncExternalInputBarLayout()
        syncExternalContextMenuLayerLayout()
        true
    }
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
    private val scrollToLiveButtonPendingResetRunnable = Runnable {
        isScrollToLiveButtonActionPending = false
        updateScrollToLiveButtonVisibility()
    }
    private val teleportTimeoutRunnable = Runnable {
        cancelSnapshotTeleport()
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
    private val ownsVulkanOverlay = sharedVulkanOverlay == null
    private val usesExternalInputBar = sharedVulkanOverlay != null && sharedForegroundHost != null
    private val usesExternalContextMenuLayer = sharedForegroundHost != null
    private val vulkanOverlay = (sharedVulkanOverlay ?: VulkanChatOverlayView(context).apply {
        setOverlayEnabled(BuildConfig.VULKAN_CHAT_GLASS_ENABLED && ENABLE_VULKAN_CHAT_OVERLAY)
    }).apply {
        onBackdropStats = { stats ->
            handleVulkanGlassBackdropStats(stats)
        }
    }
    private val scrollToLiveButton = ScrollToLiveButtonView(context).apply {
        alpha = 0f
        visibility = GONE
        setPalette(palette)
        setOnClickListener {
            isScrollToLiveButtonActionPending = true
            removeCallbacks(scrollToLiveButtonPendingResetRunnable)
            postDelayed(
                scrollToLiveButtonPendingResetRunnable,
                SCROLL_TO_LIVE_BUTTON_PENDING_TIMEOUT_MS
            )
            setScrollToLiveButtonVisible(visible = false, animated = true)
            if (isAtLiveEdge) {
                scrollToBottom(animated = true)
            } else {
                onScrollToLiveEdge()
            }
        }
    }
    private val contextMenuLayer = MessageContextMenuLayer(context, glassController).apply {
        onDismissRequested = {
            dismissMessageContextMenu()
        }
        onActionSelected = { request, action ->
            if (handleMessageContextAction(request, action)) {
                dismissMessageContextMenu()
            }
        }
        onGlassGeometryChanged = {
            glassController.invalidateRegions()
            glassController.invalidateBackdrop()
            scheduleVulkanGlassBackdropCapture(
                delayMillis = 0L,
                discardPendingImagesBeforeDraw = true
            )
        }
        onDismissFullyHidden = {
            redrawGlassBackdropAfterContextMenuDismiss()
        }
    }
    private val contextCellLayer = contextMenuLayer.selectedCellLayer
    private val backdropContentLayer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(
            recyclerView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            contextCellLayer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
    private val source = RecyclerViewGlassBackdropSource(
        recyclerView = recyclerView,
        fallbackColor = palette.background,
        overlayViews = listOf(contextCellLayer)
    )

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
                    updateScrollToLiveButtonVisibility()
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
                    updateScrollToLiveButtonVisibility()
                    scheduleVisibleReadReceiptCandidateEvaluation()
                }
            })
        }

        glassController.source = source
        addView(backdropContentLayer)
        if (ownsVulkanOverlay) {
            addView(vulkanOverlay)
        }
        addView(emptyView)
        addView(composerErrorView)
        if (!usesExternalInputBar) {
            addView(inputBar)
        }
        addView(scrollToLiveButton)
        if (!usesExternalContextMenuLayer) {
            addView(contextMenuLayer)
        }
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
            (inputBarLocalTop <= 0 || event.y < inputBarLocalTop)
        ) {
            forceVulkanGlassBackdropCapture()
        }
        return handled
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachStartNanos = ZynaPerfLog.start()
        ZynaPerfLog.mark {
            "glass.attach adapterCount=${recyclerView.adapter?.itemCount ?: 0} " +
                "vulkanBackdrop=${isVulkanGlassBackdropEnabled()} " +
                "vulkanInput=${isVulkanChatInputGlassEnabled()}"
        }
        attachExternalInputBarIfNeeded()
        attachExternalContextMenuLayerIfNeeded()
        attachExternalInputBarPreDrawListener()
        attachRecyclerDrawListener()
        attachFirstRecyclerPreDrawLogger()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        cancelSnapshotTeleport()
        scrollToLiveButtonAnimator?.cancel()
        scrollToLiveButtonAnimator = null
        detachExternalInputBarPreDrawListener()
        detachExternalContextMenuLayer()
        detachExternalInputBar()
        detachRecyclerDrawListener()
        removeCallbacks(readReceiptCandidateEvaluationRunnable)
        removeCallbacks(hardwareBackdropCaptureRunnable)
        removeCallbacks(vulkanGlassAdaptiveRenderRunnable)
        removeCallbacks(scrollToLiveButtonPendingResetRunnable)
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

    private fun attachFirstRecyclerPreDrawLogger() {
        if (didLogFirstRecyclerPreDraw) {
            return
        }
        val observer = recyclerView.viewTreeObserver
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    didLogFirstRecyclerPreDraw = true
                    val currentObserver = recyclerView.viewTreeObserver
                    if (currentObserver.isAlive) {
                        currentObserver.removeOnPreDrawListener(this)
                    } else {
                        observer.removeOnPreDrawListener(this)
                    }
                    ZynaPerfLog.end(
                        attachStartNanos,
                        "glass.recycler.firstPreDraw"
                    ) {
                        "adapterCount=${recyclerView.adapter?.itemCount ?: 0} " +
                            "children=${recyclerView.childCount}"
                    }
                    return true
                }
            }
        )
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

    private fun attachExternalInputBarIfNeeded(): Boolean {
        if (!usesExternalInputBar) {
            return false
        }
        val host = sharedForegroundHost ?: return false
        val localWidth = inputBarLocalRight - inputBarLocalLeft
        val localHeight = inputBarLocalBottom - inputBarLocalTop
        if (localWidth <= 0 || localHeight <= 0) {
            return false
        }
        if (inputBar.parent === host) {
            bringExternalForegroundChildrenToFront()
            return true
        }
        (inputBar.parent as? ViewGroup)?.removeView(inputBar)
        host.addView(
            inputBar,
            FrameLayout.LayoutParams(localWidth, localHeight).apply {
                gravity = Gravity.START or Gravity.TOP
            }
        )
        bringExternalForegroundChildrenToFront()
        return true
    }

    private fun detachExternalInputBar() {
        if (!usesExternalInputBar) {
            return
        }
        if (inputBar.parent === sharedForegroundHost) {
            sharedForegroundHost?.removeView(inputBar)
        }
    }

    private fun attachExternalContextMenuLayerIfNeeded(): Boolean {
        if (!usesExternalContextMenuLayer) {
            return false
        }
        val host = sharedForegroundHost ?: return false
        if (width <= 0 || height <= 0) {
            return false
        }
        if (contextMenuLayer.parent === host) {
            bringExternalForegroundChildrenToFront()
            return true
        }
        (contextMenuLayer.parent as? ViewGroup)?.removeView(contextMenuLayer)
        host.addView(
            contextMenuLayer,
            FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.START or Gravity.TOP
            }
        )
        bringExternalForegroundChildrenToFront()
        return true
    }

    private fun detachExternalContextMenuLayer() {
        if (!usesExternalContextMenuLayer) {
            return
        }
        if (contextMenuLayer.parent === sharedForegroundHost) {
            sharedForegroundHost?.removeView(contextMenuLayer)
        }
    }

    private fun syncExternalContextMenuLayerLayout() {
        if (!usesExternalContextMenuLayer || !isAttachedToWindow || width <= 0 || height <= 0) {
            return
        }
        val host = sharedForegroundHost ?: return
        if (!attachExternalContextMenuLayerIfNeeded()) {
            return
        }

        getLocationOnScreen(layoutLocationOnScreen)
        host.getLocationOnScreen(foregroundHostLocationOnScreen)
        val hostLeft = layoutLocationOnScreen[0] - foregroundHostLocationOnScreen[0]
        val hostTop = layoutLocationOnScreen[1] - foregroundHostLocationOnScreen[1]
        val hostRight = hostLeft + width
        val hostBottom = hostTop + height

        val params = (contextMenuLayer.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        if (
            params.width != width ||
            params.height != height ||
            params.leftMargin != hostLeft ||
            params.topMargin != hostTop
        ) {
            params.width = width
            params.height = height
            params.leftMargin = hostLeft
            params.topMargin = hostTop
            params.gravity = Gravity.START or Gravity.TOP
            contextMenuLayer.layoutParams = params
        }
        if (
            contextMenuLayer.measuredWidth != width ||
            contextMenuLayer.measuredHeight != height ||
            contextMenuLayer.width != width ||
            contextMenuLayer.height != height ||
            contextMenuLayer.isLayoutRequested
        ) {
            contextMenuLayer.forceLayout()
            contextMenuLayer.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        contextMenuLayer.layout(hostLeft, hostTop, hostRight, hostBottom)
        bringExternalForegroundChildrenToFront()
    }

    private fun bringExternalForegroundChildrenToFront() {
        if (usesExternalInputBar && inputBar.parent === sharedForegroundHost) {
            inputBar.bringToFront()
        }
        if (
            usesExternalContextMenuLayer &&
            contextMenuLayer.parent === sharedForegroundHost &&
            contextMenuLayer.visibility == VISIBLE
        ) {
            contextMenuLayer.bringToFront()
        }
    }

    private fun attachExternalInputBarPreDrawListener() {
        if (!usesExternalInputBar || externalInputBarPreDrawListenerAttached) {
            return
        }
        viewTreeObserver.addOnPreDrawListener(externalInputBarPreDrawListener)
        externalInputBarPreDrawListenerAttached = true
    }

    private fun detachExternalInputBarPreDrawListener() {
        if (!externalInputBarPreDrawListenerAttached) {
            return
        }
        val observer = viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(externalInputBarPreDrawListener)
        }
        externalInputBarPreDrawListenerAttached = false
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
        scrollToLiveButton.setPalette(newPalette)
        glassController.invalidateBackdrop()
        scheduleVulkanGlassBackdropCapture()
    }

    fun setEmptyState(isEmpty: Boolean, isLoading: Boolean) {
        emptyView.text = if (isLoading) "Loading messages" else "No messages"
        emptyView.visibility = if (isEmpty) VISIBLE else GONE
        updateScrollToLiveButtonVisibility()
    }

    fun setPaginationState(
        isLoadingOlder: Boolean,
        canLoadOlder: Boolean,
        canLoadNewer: Boolean
    ) {
        isLoadingOlderMessages = isLoadingOlder
        canLoadOlderMessages = canLoadOlder
        canLoadNewerMessages = canLoadNewer
        updateScrollToLiveButtonVisibility()
    }

    fun setLiveEdgeState(isLiveEdge: Boolean) {
        if (isAtLiveEdge == isLiveEdge) {
            updateScrollToLiveButtonVisibility()
            return
        }
        isAtLiveEdge = isLiveEdge
        updateScrollToLiveButtonVisibility()
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

    fun beginSnapshotTeleport(direction: ChatTeleportDirection): Boolean {
        val blockedReason = when {
            isContextGestureActive -> "contextGestureActive"
            isContextMenuShowing -> "contextMenuShowing"
            recyclerView.width <= 0 -> "recyclerWidth=${recyclerView.width}"
            recyclerView.height <= 0 -> "recyclerHeight=${recyclerView.height}"
            recyclerView.childCount == 0 -> "childCount=0"
            else -> null
        }
        if (blockedReason != null) {
            logChatTeleport("begin skipped reason=$blockedReason direction=$direction")
            return false
        }

        cancelSnapshotTeleport()
        val bitmap = try {
            Bitmap.createBitmap(recyclerView.width, recyclerView.height, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            Log.w(CHAT_TELEPORT_TAG, "Unable to allocate teleport snapshot", error)
            return false
        } catch (error: IllegalArgumentException) {
            Log.w(CHAT_TELEPORT_TAG, "Unable to allocate teleport snapshot", error)
            return false
        }

        Canvas(bitmap).also { canvas ->
            canvas.drawColor(palette.background)
            recyclerView.draw(canvas)
        }

        val snapshotView = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(palette.background)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = true
        }
        teleportSnapshotBitmap = bitmap
        teleportSnapshotView = snapshotView
        teleportDirection = direction
        logChatTeleport(
            "begin snapshot direction=$direction " +
                "size=${recyclerView.width}x${recyclerView.height} childCount=${recyclerView.childCount}"
        )

        val inputIndex = indexOfChild(inputBar).takeIf { it >= 0 } ?: childCount
        addView(
            snapshotView,
            inputIndex,
            LayoutParams(recyclerView.width, recyclerView.height)
        )
        layoutTeleportSnapshot()
        setContextScrollLocked(true)
        removeCallbacks(teleportTimeoutRunnable)
        postDelayed(teleportTimeoutRunnable, CHAT_TELEPORT_TIMEOUT_MS)
        return true
    }

    fun completeSnapshotTeleport(onComplete: () -> Unit = {}) {
        val snapshotView = teleportSnapshotView
        if (snapshotView == null) {
            onComplete()
            return
        }

        removeCallbacks(teleportTimeoutRunnable)
        teleportAnimator?.removeAllListeners()
        teleportAnimator?.cancel()

        val distance = recyclerView.height.toFloat().takeIf { it > 0f }
            ?: height.toFloat().coerceAtLeast(1f)
        val directionSign = when (teleportDirection) {
            ChatTeleportDirection.TO_OLDER -> 1f
            ChatTeleportDirection.TO_NEWER -> -1f
        }
        logChatTeleport(
            "complete start direction=$teleportDirection distance=$distance " +
                "childCount=${recyclerView.childCount}"
        )

        recyclerView.translationY = -directionSign * distance
        snapshotView.translationY = 0f

        var didFinish = false
        fun finishOnce(reason: String) {
            if (didFinish) {
                return
            }
            didFinish = true
            logChatTeleport("complete finish reason=$reason")
            cleanupSnapshotTeleport()
            onComplete()
        }

        teleportAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CHAT_TELEPORT_DURATION_MS
            interpolator = DecelerateInterpolator(CHAT_TELEPORT_DECELERATION)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                snapshotView.translationY = directionSign * distance * progress
                recyclerView.translationY = -directionSign * distance * (1f - progress)
                invalidateGlassContent()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    finishOnce("cancel")
                }

                override fun onAnimationEnd(animation: Animator) {
                    finishOnce("end")
                }
            })
            start()
        }
    }

    fun cancelSnapshotTeleport() {
        removeCallbacks(teleportTimeoutRunnable)
        teleportAnimator?.removeAllListeners()
        teleportAnimator?.cancel()
        cleanupSnapshotTeleport()
    }

    fun highlightMessageAtAdapterPosition(position: Int, delayMillis: Long = CHAT_TARGET_HIGHLIGHT_DELAY_MS) {
        recyclerView.postDelayed(
            {
                val cell = recyclerView
                    .findViewHolderForAdapterPosition(position)
                    ?.itemView as? MessageCellView
                cell?.highlightBubble()
            },
            delayMillis
        )
    }

    internal fun beginMessageContextMenuGesture(request: MessageContextMenuRequest): Boolean {
        isContextGestureActive = true
        setContextScrollLocked(true)
        syncExternalContextMenuLayerLayout()
        val didBegin = contextMenuLayer.beginPreview(request)
        if (!didBegin) {
            isContextGestureActive = false
            setContextScrollLocked(false)
            updateScrollToLiveButtonVisibility()
            return false
        }
        syncExternalContextMenuLayerLayout()
        updateScrollToLiveButtonVisibility()
        post { updateVulkanGlassRects() }
        return true
    }

    internal fun showMessageContextMenu(request: MessageContextMenuRequest): Boolean {
        if (!isContextGestureActive) {
            isContextGestureActive = true
            setContextScrollLocked(true)
        }
        syncExternalContextMenuLayerLayout()
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
        syncExternalContextMenuLayerLayout()
        updateScrollToLiveButtonVisibility()
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
                updateScrollToLiveButtonVisibility()
            }
        }
    }

    internal fun dismissMessageContextMenu(animated: Boolean = true) {
        if (!isContextMenuShowing) {
            contextMenuLayer.dismiss(animated = animated)
            if (!isContextGestureActive) {
                setContextScrollLocked(false)
            }
            updateScrollToLiveButtonVisibility()
            post { updateVulkanGlassRects() }
            return
        }
        isContextMenuShowing = false
        recyclerView.importantForAccessibility = recyclerAccessibilityBeforeMenu
        if (!isContextGestureActive) {
            setContextScrollLocked(false)
        }
        contextMenuLayer.dismiss(animated)
        updateScrollToLiveButtonVisibility()
        post { updateVulkanGlassRects() }
    }

    private fun redrawGlassBackdropAfterContextMenuDismiss() {
        syncExternalContextMenuLayerLayout()
        glassController.invalidateRegions()
        glassController.invalidateBackdrop()
        updateVulkanGlassRects()
        forceVulkanGlassBackdropCapture()
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
        if (isLoadingOlderMessages || teleportSnapshotView != null || teleportAnimator != null) {
            return
        }

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = recyclerView.adapter?.itemCount ?: return
        if (itemCount == 0) {
            return
        }
        val firstVisibleItem = layoutManager.findFirstVisibleItemPosition()
        if (
            canLoadNewerMessages &&
            firstVisibleItem != RecyclerView.NO_POSITION &&
            firstVisibleItem <= NEWER_LOAD_THRESHOLD
        ) {
            onLoadNewerMessages()
            return
        }

        if (!canLoadOlderMessages) {
            return
        }

        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        val shouldLoadMore = lastVisibleItem != RecyclerView.NO_POSITION &&
            lastVisibleItem >= itemCount - LOAD_OLDER_THRESHOLD

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
            val firstVisibleItem = chatLayoutManager.findFirstVisibleItemPosition()
            val shouldTeleport = firstVisibleItem != RecyclerView.NO_POSITION &&
                firstVisibleItem > SCROLL_TO_LIVE_SMOOTH_MAX_DISTANCE
            if (shouldTeleport) {
                val didBeginTeleport = beginSnapshotTeleport(ChatTeleportDirection.TO_NEWER)
                recyclerView.stopScroll()
                recyclerView.scrollToPosition(0)
                if (didBeginTeleport) {
                    runAfterRecyclerPreDraw {
                        completeSnapshotTeleport()
                    }
                }
            } else {
                recyclerView.smoothScrollToPosition(0)
            }
        } else {
            recyclerView.scrollToPosition(0)
        }
        updateScrollToLiveButtonVisibility(animated = false)
        glassController.invalidateBackdrop()
        scheduleVulkanGlassBackdropCapture()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val start = ZynaPerfLog.start()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        backdropContentLayer.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        if (ownsVulkanOverlay) {
            vulkanOverlay.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
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
        val scrollToLiveButtonSize = SCROLL_TO_LIVE_BUTTON_SIZE_DP.dpToPx(density)
        scrollToLiveButton.measure(
            MeasureSpec.makeMeasureSpec(scrollToLiveButtonSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(scrollToLiveButtonSize, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, height)
        if (measureLogCount < FIRST_LAYOUT_LOG_LIMIT) {
            measureLogCount += 1
            ZynaPerfLog.end(
                start,
                "glass.onMeasure"
            ) {
                "size=${width}x$height adapterCount=${recyclerView.adapter?.itemCount ?: 0}"
            }
        } else {
            ZynaPerfLog.endIfSlow(
                start,
                "glass.onMeasure.slow",
                thresholdMs = 4.0
            ) {
                "size=${width}x$height adapterCount=${recyclerView.adapter?.itemCount ?: 0}"
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val start = ZynaPerfLog.start()
        val width = right - left
        val height = bottom - top

        val bottomInset = if (imeBottomInset > 0) imeBottomInset else navBottomInset
        val bottomMargin = 6.dpToPx(density)
        val inputTop = (height - bottomInset - bottomMargin - inputBar.measuredHeight)
            .coerceAtLeast(0)
        val contentBottom = layoutComposerError(inputTop, width)
        updateRecyclerPadding(contentBottom)

        backdropContentLayer.layout(0, 0, width, height)
        if (ownsVulkanOverlay) {
            vulkanOverlay.layout(0, 0, width, height)
        }
        layoutTeleportSnapshot()

        inputBarLocalLeft = 0
        inputBarLocalTop = inputTop
        inputBarLocalRight = width
        inputBarLocalBottom = inputTop + inputBar.measuredHeight
        if (usesExternalInputBar) {
            syncExternalInputBarLayout()
        } else {
            inputBar.layout(
                inputBarLocalLeft,
                inputBarLocalTop,
                inputBarLocalRight,
                inputBarLocalBottom
            )
        }
        val overlayOffset = vulkanOverlayOffset()
        vulkanOverlay.setInputBarBounds(
            inputBarLocalLeft + overlayOffset.x,
            inputBarLocalTop + overlayOffset.y,
            inputBarLocalRight + overlayOffset.x,
            inputBarLocalBottom + overlayOffset.y
        )
        layoutScrollToLiveButton(contentBottom, width)

        val emptyWidth = emptyView.measuredWidth
        val emptyHeight = emptyView.measuredHeight
        val emptyLeft = (width - emptyWidth) / 2
        val availableBottom = contentBottom.coerceAtLeast(0)
        val emptyTop = ((availableBottom - emptyHeight) / 2).coerceAtLeast(0)
        emptyView.layout(emptyLeft, emptyTop, emptyLeft + emptyWidth, emptyTop + emptyHeight)

        if (usesExternalContextMenuLayer) {
            syncExternalContextMenuLayerLayout()
        } else {
            contextMenuLayer.layout(0, 0, width, height)
        }
        glassController.invalidateRegions()
        updateVulkanGlassRects()
        updateScrollToLiveButtonVisibility(animated = false)
        scheduleVisibleReadReceiptCandidateEvaluation(READ_RECEIPT_CONTENT_UPDATE_DELAY_MS)
        if (layoutLogCount < FIRST_LAYOUT_LOG_LIMIT) {
            layoutLogCount += 1
            ZynaPerfLog.end(
                start,
                "glass.onLayout"
            ) {
                "changed=$changed size=${width}x$height " +
                    "adapterCount=${recyclerView.adapter?.itemCount ?: 0} children=${recyclerView.childCount}"
            }
        } else {
            ZynaPerfLog.endIfSlow(
                start,
                "glass.onLayout.slow",
                thresholdMs = 4.0
            ) {
                "changed=$changed size=${width}x$height " +
                    "adapterCount=${recyclerView.adapter?.itemCount ?: 0} children=${recyclerView.childCount}"
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val start = ZynaPerfLog.start()
        super.dispatchDraw(canvas)
        if (dispatchDrawLogCount < FIRST_LAYOUT_LOG_LIMIT) {
            dispatchDrawLogCount += 1
            ZynaPerfLog.end(
                start,
                "glass.dispatchDraw"
            ) {
                "adapterCount=${recyclerView.adapter?.itemCount ?: 0} children=${recyclerView.childCount}"
            }
        } else {
            ZynaPerfLog.endIfSlow(
                start,
                "glass.dispatchDraw.slow",
                thresholdMs = 8.0
            ) {
                "adapterCount=${recyclerView.adapter?.itemCount ?: 0} children=${recyclerView.childCount}"
            }
        }
    }

    private fun vulkanOverlayOffset(): VulkanOverlayOffset {
        if (ownsVulkanOverlay) {
            return VulkanOverlayOffset.Zero
        }

        getLocationOnScreen(layoutLocationOnScreen)
        vulkanOverlay.getLocationOnScreen(overlayLocationOnScreen)
        return VulkanOverlayOffset(
            x = layoutLocationOnScreen[0] - overlayLocationOnScreen[0],
            y = layoutLocationOnScreen[1] - overlayLocationOnScreen[1]
        )
    }

    private fun syncExternalInputBarLayout() {
        if (!usesExternalInputBar || !isAttachedToWindow) {
            return
        }
        val host = sharedForegroundHost ?: return
        val localWidth = inputBarLocalRight - inputBarLocalLeft
        val localHeight = inputBarLocalBottom - inputBarLocalTop
        if (localWidth <= 0 || localHeight <= 0) {
            return
        }
        if (!attachExternalInputBarIfNeeded()) {
            return
        }

        getLocationOnScreen(layoutLocationOnScreen)
        host.getLocationOnScreen(foregroundHostLocationOnScreen)
        val hostLeft = layoutLocationOnScreen[0] -
            foregroundHostLocationOnScreen[0] +
            inputBarLocalLeft
        val hostTop = layoutLocationOnScreen[1] -
            foregroundHostLocationOnScreen[1] +
            inputBarLocalTop
        val hostRight = hostLeft + (inputBarLocalRight - inputBarLocalLeft)
        val hostBottom = hostTop + (inputBarLocalBottom - inputBarLocalTop)

        val params = (inputBar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        val nextWidth = (hostRight - hostLeft).coerceAtLeast(0)
        val nextHeight = (hostBottom - hostTop).coerceAtLeast(0)
        if (
            params.width != nextWidth ||
            params.height != nextHeight ||
            params.leftMargin != hostLeft ||
            params.topMargin != hostTop
        ) {
            params.width = nextWidth
            params.height = nextHeight
            params.leftMargin = hostLeft
            params.topMargin = hostTop
            params.gravity = Gravity.START or Gravity.TOP
            inputBar.layoutParams = params
        }
        if (
            inputBar.measuredWidth != nextWidth ||
            inputBar.measuredHeight != nextHeight ||
            inputBar.width != nextWidth ||
            inputBar.height != nextHeight ||
            inputBar.isLayoutRequested
        ) {
            inputBar.forceLayout()
            inputBar.measure(
                MeasureSpec.makeMeasureSpec(nextWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(nextHeight, MeasureSpec.EXACTLY)
            )
        }
        inputBar.layout(hostLeft, hostTop, hostRight, hostBottom)
        bringExternalForegroundChildrenToFront()
    }

    private fun List<VulkanChatGlassRect>.toVulkanOverlayCoordinates(
        offset: VulkanOverlayOffset
    ): List<VulkanChatGlassRect> {
        if (offset == VulkanOverlayOffset.Zero) {
            return this
        }
        return map { rect ->
            rect.copy(
                left = rect.left + offset.x,
                top = rect.top + offset.y,
                right = rect.right + offset.x,
                bottom = rect.bottom + offset.y
            )
        }
    }

    private fun PaintSplashTarget.toVulkanOverlayCoordinates(
        offset: VulkanOverlayOffset
    ): PaintSplashTarget {
        if (offset == VulkanOverlayOffset.Zero) {
            return this
        }
        val bounds = RectF(boundsInRoot)
        bounds.offset(offset.x.toFloat(), offset.y.toFloat())
        return copy(boundsInRoot = bounds)
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
            if (usesExternalInputBar) {
                syncExternalInputBarLayout()
            }
            inputBar.collectVulkanGlassRects(
                out = nextRects,
                originLeft = inputBarLocalLeft,
                originTop = inputBarLocalTop
            )
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
        if (!ENABLE_VULKAN_CHAT_GLASS_PREVIEW_RECT || inputBarLocalTop <= 0) {
            return
        }

        val horizontal = 18f.dpToPx(density)
        val gap = 14f.dpToPx(density)
        val previewHeight = 82f.dpToPx(density)
        val minTop = 18f.dpToPx(density)
        val bottom = (inputBarLocalTop.toFloat() - gap).coerceAtLeast(minTop)
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
            source = backdropContentLayer,
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
        val overlayOffset = vulkanOverlayOffset()
        val overlayResult = vulkanOverlay.setBackdropFrame(
            frame = frame,
            rects = adaptiveRects.toVulkanOverlayCoordinates(overlayOffset),
            textureLeft = (captureBounds.left + overlayOffset.x).toFloat(),
            textureTop = (captureBounds.top + overlayOffset.y).toFloat()
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
        val overlayOffset = vulkanOverlayOffset()
        return vulkanOverlay.updateBackdropRects(
            rects = adaptedRects.toVulkanOverlayCoordinates(overlayOffset),
            textureLeft = (vulkanGlassCaptureBounds.left + overlayOffset.x).toFloat(),
            textureTop = (vulkanGlassCaptureBounds.top + overlayOffset.y).toFloat()
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
        return BuildConfig.VULKAN_CHAT_GLASS_ENABLED &&
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
        request: MessageContextMenuRequest,
        action: MessageContextMenuAction
    ): Boolean {
        val message = request.message
        return when (action) {
            MessageContextMenuAction.REPLY -> {
                message.toReplyPreviewOrNull()?.let(onReplyToMessage)
                true
            }
            MessageContextMenuAction.EDIT -> {
                message.editInfo?.let(onEditMessage)
                true
            }
            MessageContextMenuAction.FORWARD -> {
                message.toForwardPreviewOrNull()?.let(onForwardMessage)
                true
            }
            MessageContextMenuAction.COPY -> {
                copyMessageText(message)
                true
            }
            MessageContextMenuAction.DELETE -> {
                val messageId = message.redactionTargetMessageId ?: return true
                val target = contextMenuLayer.captureSelectedPaintSplashTarget(this)
                beginContextRedaction(listOf(messageId), target)
            }
            MessageContextMenuAction.DELETE_PHOTO -> {
                val messageId = contextMenuLayer.selectedPhotoRedactionMessageId() ?: return true
                val target = contextMenuLayer.captureSelectedPhotoPaintSplashTarget(this)
                beginContextRedaction(listOf(messageId), target)
            }
            MessageContextMenuAction.DELETE_GROUP -> {
                val messageIds = contextMenuLayer.selectedPhotoGroupRedactionMessageIds()
                val target = contextMenuLayer.captureSelectedPaintSplashTarget(this)
                beginContextRedaction(messageIds, target)
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

    private fun beginContextRedaction(
        messageIds: List<String>,
        target: PaintSplashTarget?
    ): Boolean {
        val redactionIds = messageIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (redactionIds.isEmpty()) {
            return true
        }
        if (target == null) {
            onRedactMessages(redactionIds)
            return true
        }
        val startBurst = {
            dismissMessageContextMenu(animated = false)
            target.hideSource()
            vulkanOverlay.addPaintSplash(
                target.toVulkanOverlayCoordinates(vulkanOverlayOffset())
            )
            onRedactMessages(redactionIds)
        }
        if (!contextMenuLayer.playSelectedCellDeleteAnticipation(startBurst)) {
            startBurst()
        }
        return false
    }

    private fun copyMessageText(message: MessageRenderModel) {
        val text = when (val content = message.content) {
            is MessageContent.Text -> content.body
            is MessageContent.Image -> content.caption.normalizedMessageCaption()
            is MessageContent.PhotoGroup -> content.caption.normalizedMessageCaption()
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
            is MessageContent.Image -> currentContent.caption.normalizedMessageCaption() ?: "Photo"
            is MessageContent.PhotoGroup -> currentContent.caption.normalizedMessageCaption() ?: "Photo group"
            MessageContent.Redacted -> return null
        }.takeIf { it.isNotBlank() } ?: return null
        return MessageReplyPreview(
            eventId = eventId,
            senderId = senderId,
            senderText = senderText,
            body = body
        )
    }

    private fun MessageRenderModel.toForwardPreviewOrNull(): MessageForwardPreview? {
        eventId?.takeIf { it.isNotBlank() } ?: return null
        if (!canForward || content is MessageContent.Redacted || outgoingEnvelopeId != null) {
            return null
        }
        return when (val currentContent = content) {
            is MessageContent.Text -> {
                val body = currentContent.body.takeIf { it.isNotBlank() } ?: return null
                MessageForwardPreview(
                    body = body,
                    forwardedFrom = senderDisplayName?.takeIf { it.isNotBlank() }
                )
            }
            is MessageContent.Image -> {
                val caption = currentContent.caption.normalizedMessageCaption()
                MessageForwardPreview(
                    body = caption ?: "Photo",
                    forwardedFrom = senderDisplayName?.takeIf { it.isNotBlank() },
                    caption = caption,
                    imageItems = listOf(currentContent.imageInfo.toForwardImageItem()),
                    captionPlacement = currentContent.captionPlacement
                )
            }
            is MessageContent.PhotoGroup -> {
                val caption = currentContent.caption.normalizedMessageCaption()
                MessageForwardPreview(
                    body = caption ?: "Photo group",
                    forwardedFrom = senderDisplayName?.takeIf { it.isNotBlank() },
                    caption = caption,
                    imageItems = currentContent.items.map { item ->
                        item.imageInfo.toForwardImageItem()
                    },
                    captionPlacement = currentContent.captionPlacement,
                    layoutOverride = currentContent.layoutOverride
                )
            }
            MessageContent.Redacted -> return null
        }
    }

    private fun com.zyna.app.data.matrix.MatrixImageInfo.toForwardImageItem(): MatrixForwardImageItem {
        return MatrixForwardImageItem(
            sourceJson = sourceJson,
            thumbnailSourceJson = thumbnailSourceJson,
            width = width,
            height = height,
            caption = caption.normalizedMessageCaption(),
            mimeType = mimeType,
            blurhash = blurhash
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

    private fun cleanupSnapshotTeleport() {
        teleportAnimator = null
        removeCallbacks(teleportTimeoutRunnable)
        recyclerView.translationY = 0f
        teleportSnapshotView?.let { snapshotView ->
            snapshotView.setImageDrawable(null)
            removeView(snapshotView)
        }
        teleportSnapshotView = null
        teleportSnapshotBitmap?.recycle()
        teleportSnapshotBitmap = null
        if (!isContextGestureActive && !isContextMenuShowing) {
            setContextScrollLocked(false)
        }
        updateScrollToLiveButtonVisibility()
        invalidateGlassContent()
    }

    private fun layoutTeleportSnapshot() {
        val snapshotView = teleportSnapshotView ?: return
        val width = recyclerView.width
        val height = recyclerView.height
        snapshotView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        snapshotView.layout(recyclerView.left, recyclerView.top, recyclerView.right, recyclerView.bottom)
    }

    private fun runAfterRecyclerPreDraw(action: () -> Unit) {
        val observer = recyclerView.viewTreeObserver
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val currentObserver = recyclerView.viewTreeObserver
                    if (currentObserver.isAlive) {
                        currentObserver.removeOnPreDrawListener(this)
                    } else {
                        observer.removeOnPreDrawListener(this)
                    }
                    action()
                    return true
                }
            }
        )
        recyclerView.invalidate()
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

    private fun layoutScrollToLiveButton(contentBottom: Int, width: Int) {
        val buttonWidth = scrollToLiveButton.measuredWidth
        val buttonHeight = scrollToLiveButton.measuredHeight
        if (buttonWidth <= 0 || buttonHeight <= 0) {
            return
        }

        val bottomGap = SCROLL_TO_LIVE_BUTTON_BOTTOM_GAP_DP.dpToPx(density)
        val centerX = inputBarLocalLeft + inputBar.sendButtonCenterX()
        val left = (centerX - buttonWidth / 2).coerceIn(0, (width - buttonWidth).coerceAtLeast(0))
        val right = left + buttonWidth
        val bottom = (contentBottom - bottomGap).coerceAtLeast(buttonHeight)
        val top = bottom - buttonHeight
        scrollToLiveButton.layout(left, top, right, bottom)
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

    private fun updateScrollToLiveButtonVisibility(animated: Boolean = true) {
        setScrollToLiveButtonVisible(shouldShowScrollToLiveButton(), animated)
    }

    private fun shouldShowScrollToLiveButton(): Boolean {
        if (
            isContextGestureActive ||
            isContextMenuShowing ||
            teleportSnapshotView != null ||
            teleportAnimator != null
        ) {
            return false
        }

        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            isScrollToLiveButtonActionPending = false
            hasScrollToLiveButtonStableViewport = false
            removeCallbacks(scrollToLiveButtonPendingResetRunnable)
            return false
        }
        val firstVisibleItem = chatLayoutManager.findFirstVisibleItemPosition()
        if (firstVisibleItem == RecyclerView.NO_POSITION) {
            return false
        }
        val isViewportAtLiveEdge = firstVisibleItem != RecyclerView.NO_POSITION &&
            firstVisibleItem <= LIVE_EDGE_VISIBLE_THRESHOLD
        if (!hasScrollToLiveButtonStableViewport) {
            if (isAtLiveEdge && !isViewportAtLiveEdge) {
                return false
            }
            hasScrollToLiveButtonStableViewport = true
        }
        if (isScrollToLiveButtonActionPending) {
            if (isAtLiveEdge && isViewportAtLiveEdge) {
                isScrollToLiveButtonActionPending = false
                removeCallbacks(scrollToLiveButtonPendingResetRunnable)
            } else {
                return false
            }
        }

        if (!isAtLiveEdge) {
            return true
        }

        return !isViewportAtLiveEdge
    }

    private fun setScrollToLiveButtonVisible(visible: Boolean, animated: Boolean) {
        if (isScrollToLiveButtonVisible == visible && scrollToLiveButton.visibility != GONE) {
            return
        }
        if (!visible && !isScrollToLiveButtonVisible && scrollToLiveButton.visibility == GONE) {
            return
        }

        isScrollToLiveButtonVisible = visible
        scrollToLiveButtonAnimator?.cancel()
        scrollToLiveButtonAnimator = null

        if (!animated) {
            scrollToLiveButton.alpha = if (visible) 1f else 0f
            scrollToLiveButton.visibility = if (visible) VISIBLE else GONE
            return
        }

        if (visible) {
            scrollToLiveButton.visibility = VISIBLE
        }
        val targetAlpha = if (visible) 1f else 0f
        scrollToLiveButtonAnimator = ValueAnimator.ofFloat(scrollToLiveButton.alpha, targetAlpha).apply {
            duration = SCROLL_TO_LIVE_BUTTON_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                scrollToLiveButton.alpha = animation.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scrollToLiveButtonAnimator = null
                    if (!visible) {
                        scrollToLiveButton.visibility = GONE
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    scrollToLiveButtonAnimator = null
                }
            })
            start()
        }
    }
}

private class ScrollToLiveButtonView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var palette = defaultPalette()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dpToPx(density)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f.dpToPx(density)
    }

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        elevation = 8.dpToPx(density).toFloat()
        contentDescription = "Scroll to latest messages"
    }

    fun setPalette(newPalette: GlassPalette) {
        palette = newPalette
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - 1f.dpToPx(density)

        fillPaint.color = palette.glassTintStrong
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        strokePaint.color = palette.stroke
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        glyphPaint.color = palette.text
        val halfWidth = 6.5f.dpToPx(density)
        val topY = centerY - 3f.dpToPx(density)
        val bottomY = centerY + 4f.dpToPx(density)
        canvas.drawLine(centerX - halfWidth, topY, centerX, bottomY, glyphPaint)
        canvas.drawLine(centerX, bottomY, centerX + halfWidth, topY, glyphPaint)
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

private fun logChatTeleport(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d(CHAT_TELEPORT_TAG, message)
    }
}

enum class ChatTeleportDirection {
    TO_OLDER,
    TO_NEWER
}

private data class VulkanOverlayOffset(
    val x: Int,
    val y: Int
) {
    companion object {
        val Zero = VulkanOverlayOffset(0, 0)
    }
}

private const val ENABLE_VULKAN_CHAT_OVERLAY = true
private const val ENABLE_VULKAN_CHAT_GLASS_BACKDROP = true
private const val ENABLE_VULKAN_CHAT_GLASS_PREVIEW_RECT = false
private const val ENABLE_VULKAN_CHAT_INPUT_GLASS = true
private const val ENABLE_VULKAN_CHAT_VERBOSE_TIMING = false
private const val ENABLE_VULKAN_CHAT_PERF_LOGGING = false
private const val FIRST_LAYOUT_LOG_LIMIT = 5
private const val CHAT_TELEPORT_TAG = "ZynaChatTeleport"
private const val CHAT_TELEPORT_DURATION_MS = 340L
private const val CHAT_TELEPORT_TIMEOUT_MS = 900L
private const val CHAT_TELEPORT_DECELERATION = 1.7f
private const val CHAT_TARGET_HIGHLIGHT_DELAY_MS = 80L
private const val HARDWARE_BUFFER_CAPTURE_TAG = "ZynaHwBufferCapture"
private const val VULKAN_GLASS_CAPTURE_DELAY_MS = 32L
private const val VULKAN_GLASS_SCROLL_CAPTURE_DELAY_MS = 0L
private const val VULKAN_GLASS_REALTIME_CAPTURE_MIN_INTERVAL_MS = 0L
private const val VULKAN_GLASS_PERF_LOG_WINDOW_NANOS = 1_000_000_000L
private const val LOAD_OLDER_THRESHOLD = 16
private const val NEWER_LOAD_THRESHOLD = 16
private const val LIVE_EDGE_VISIBLE_THRESHOLD = 1
private const val SCROLL_TO_LIVE_SMOOTH_MAX_DISTANCE = 8
private const val SCROLL_TO_LIVE_BUTTON_SIZE_DP = 44
private const val SCROLL_TO_LIVE_BUTTON_BOTTOM_GAP_DP = 12
private const val SCROLL_TO_LIVE_BUTTON_FADE_MS = 160L
private const val SCROLL_TO_LIVE_BUTTON_PENDING_TIMEOUT_MS = 4_000L
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
