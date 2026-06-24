package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.zyna.app.ui.chat.render.PaintSplashTarget
import com.zyna.app.util.ZynaPerfLog

/**
 * First chat-only Vulkan backend foothold.
 *
 * This view owns the chat-local Vulkan surface while RecyclerView/input/context-menu
 * remain normal Android Views. It leaves the view hierarchy composition while idle,
 * because some devices keep the last swapchain buffer visible after transparent clears.
 */
internal class VulkanChatOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private var nativeHandle = 0L
    private var surface: Surface? = null
    private var nativeSurfaceBound = false
    private var boundSurfaceWidth = 0
    private var boundSurfaceHeight = 0
    private var frameCallbackPosted = false
    private var creationAttempted = false
    private var enabled = false
    private var keepSurfaceWarm = false
    private var presentationSuppressed = false
    private var pendingPaintSplash: PendingPaintSplash? = null
    private var pendingBackdropFrame: PendingBackdropFrame? = null
    private var pendingBackdropRectUpdate: PendingBackdropRectUpdate? = null
    private var activeBackdropFrame: ActiveBackdropFrame? = null
    private var idleClearFramesRemaining = 0
    private var backdropStatsPollCallbackPosted = false
    private var backdropStatsPollAttemptsRemaining = 0
    private var renderPacingFrameId = 0L
    private var lastRenderFrameTimeNanos = 0L
    private var lastRenderHadActiveEffects = false
    private var renderPacingLogsRemaining = VULKAN_CHAT_PACING_LOG_LIMIT
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nativeCallLock = Any()
    private val renderStateLock = Any()
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var renderFrameInFlight = false
    var onBackdropStats: (VulkanGlassBackdropStats) -> Unit = {}

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        val callbackNowNanos = System.nanoTime()
        val previousFrameTimeNanos = lastRenderFrameTimeNanos
        val vsyncDeltaNanos = if (previousFrameTimeNanos > 0L) {
            frameTimeNanos - previousFrameTimeNanos
        } else {
            0L
        }
        lastRenderFrameTimeNanos = frameTimeNanos
        renderPacingFrameId += 1L
        if (shouldFrontScheduleNextRenderFrame()) {
            postFrameCallback()
        }
        postNativeRenderWork(
            frameId = renderPacingFrameId,
            frameTimeNanos = frameTimeNanos,
            callbackNowNanos = callbackNowNanos,
            vsyncDeltaNanos = vsyncDeltaNanos
        )
    }
    private val backdropStatsPollCallback = Choreographer.FrameCallback {
        backdropStatsPollCallbackPosted = false
        runBackdropStatsPoll()
    }

    init {
        alpha = 1f
        setOpaque(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
        visibility = GONE
    }

    fun setOverlayEnabled(isEnabled: Boolean) {
        val start = ZynaPerfLog.start()
        if (enabled == isEnabled) {
            ZynaPerfLog.end(
                start,
                "vulkanOverlay.setEnabled.noop"
            ) {
                "enabled=$enabled available=${NativeVulkanChat.isAvailable}"
            }
            return
        }
        enabled = isEnabled
        visibility = if (enabled && NativeVulkanChat.isAvailable) {
            if (keepSurfaceWarm) VISIBLE else INVISIBLE
        } else {
            GONE
        }
        if (visibility == VISIBLE && keepSurfaceWarm) {
            alpha = 0f
        }
        if (enabled) {
            ensureRenderer()
        } else {
            stopFrameCallback()
            stopBackdropStatsPollCallback()
            stopRenderThread()
            clearPendingSplash()
            clearBackdropFrame(clearNative = true)
            clearNativeSurface()
        }
        ZynaPerfLog.end(
            start,
            "vulkanOverlay.setEnabled"
        ) {
            "enabled=$enabled available=${NativeVulkanChat.isAvailable} " +
                "visibility=$visibility handle=${nativeHandle != 0L}"
        }
    }

    fun warmSurface() {
        val start = ZynaPerfLog.start()
        keepSurfaceWarm = true
        if (!enabled || !NativeVulkanChat.isAvailable) {
            ZynaPerfLog.end(
                start,
                "vulkanOverlay.warmSurface.skipped"
            ) {
                "enabled=$enabled available=${NativeVulkanChat.isAvailable}"
            }
            return
        }

        alpha = 0f
        visibility = VISIBLE
        ensureRenderer()
        bindCurrentSurface()
        ZynaPerfLog.end(
            start,
            "vulkanOverlay.warmSurface"
        ) {
            "surface=${surface != null} handle=${nativeHandle != 0L} size=${width}x$height"
        }
    }

    fun setPresentationSuppressed(suppressed: Boolean) {
        if (presentationSuppressed == suppressed) {
            return
        }
        presentationSuppressed = suppressed
        if (suppressed || (keepSurfaceWarm && !hasActiveBackdropFrame())) {
            alpha = 0f
        } else if (visibility == VISIBLE) {
            alpha = 1f
        }
    }

    fun setInputBarBounds(left: Int, top: Int, right: Int, bottom: Int) {
        val handle = nativeHandle
        if (handle == 0L) {
            return
        }
        synchronized(nativeCallLock) {
            if (nativeHandle == 0L) {
                return
            }
            NativeVulkanChat.nativeSetInputBarBounds(
                nativeHandle,
                left.toFloat(),
                top.toFloat(),
                right.toFloat(),
                bottom.toFloat()
            )
        }
    }

    fun addPaintSplash(target: PaintSplashTarget) {
        val bounds = target.boundsInRoot
        if (!enabled) {
            target.bitmap.recycle()
            return
        }
        ensureRenderer()
        if (nativeHandle == 0L) {
            target.bitmap.recycle()
            return
        }
        synchronized(renderStateLock) {
            clearPendingSplashLocked()
            pendingPaintSplash = PendingPaintSplash(
                bitmap = target.bitmap,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            )
        }
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "queue splash bounds=$bounds surfaceReady=${surface != null} visible=$visibility")
        }
        renderPacingLogsRemaining = VULKAN_CHAT_PACING_LOG_LIMIT
        idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
        alpha = presentedAlpha()
        visibility = VISIBLE
        bindCurrentSurface()
        startPendingSplashIfReady()
    }

    fun setBackdropFrame(
        frame: HardwareBufferChatCapture.CapturedFrame,
        rects: List<VulkanChatGlassRect>,
        textureLeft: Float = 0f,
        textureTop: Float = 0f
    ): BackdropFrameResult {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val rectValues = rects.toNativeRectValues()
        if (rectValues.isEmpty()) {
            frame.close()
            clearBackdropFrame(clearNative = true)
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                imported = false,
                rendered = false
            )
        }
        if (!enabled) {
            frame.close()
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                imported = false,
                rendered = false
            )
        }
        ensureRenderer()
        if (nativeHandle == 0L) {
            frame.close()
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                imported = false,
                rendered = false
            )
        }

        synchronized(renderStateLock) {
            clearPendingBackdropFrameLocked()
            pendingBackdropFrame = PendingBackdropFrame(
                frame = frame,
                rectValues = rectValues,
                rectCount = rectValues.size / VULKAN_GLASS_RECT_FLOAT_COUNT,
                textureLeft = textureLeft,
                textureTop = textureTop
            )
        }
        alpha = presentedAlpha()
        visibility = VISIBLE
        bindCurrentSurface()
        val didQueue = startPendingBackdropIfReady()
        return BackdropFrameResult(
            totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
            imported = didQueue,
            rendered = didQueue,
            willContinueRendering = didQueue
        )
    }

    fun updateBackdropRects(
        rects: List<VulkanChatGlassRect>,
        textureLeft: Float = 0f,
        textureTop: Float = 0f
    ): BackdropFrameResult {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val rectValues = rects.toNativeRectValues()
        if (rectValues.isEmpty() || !hasActiveBackdropFrame()) {
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                imported = false,
                rendered = false
            )
        }
        if (!enabled) {
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                imported = false,
                rendered = false
            )
        }
        ensureRenderer()
        if (nativeHandle == 0L) {
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                imported = false,
                rendered = false
            )
        }

        alpha = presentedAlpha()
        visibility = VISIBLE
        bindCurrentSurface()
        synchronized(renderStateLock) {
            pendingBackdropRectUpdate = PendingBackdropRectUpdate(
                rectValues = rectValues,
                textureLeft = textureLeft,
                textureTop = textureTop
            )
        }
        val didQueue = requestRenderFrame()
        return BackdropFrameResult(
            totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
            imported = didQueue,
            rendered = didQueue,
            willContinueRendering = didQueue,
            mainThread = Looper.myLooper() == Looper.getMainLooper()
        )
    }

    fun clearBackdropFrame() {
        clearPendingBackdropFrame()
        clearBackdropFrame(clearNative = true)
        if (pendingPaintSplash == null) {
            hideIdleSurface()
        }
    }

    fun discardBackdropFrame() {
        clearPendingBackdropFrame()
        clearBackdropFrame(clearNative = false)
        if (pendingPaintSplash == null) {
            stopFrameCallback()
            stopBackdropStatsPollCallback()
            idleClearFramesRemaining = 0
            alpha = 0f
        }
    }

    override fun onAttachedToWindow() {
        val start = ZynaPerfLog.start()
        super.onAttachedToWindow()
        if (enabled) {
            ensureRenderer()
            if (visibility == VISIBLE) {
                bindCurrentSurface()
                startPendingBackdropIfReady()
                startPendingSplashIfReady() || requestRenderFrame()
            }
        }
        ZynaPerfLog.end(
            start,
            "vulkanOverlay.attach"
        ) {
            "enabled=$enabled visibility=$visibility handle=${nativeHandle != 0L}"
        }
    }

    override fun onDetachedFromWindow() {
        stopFrameCallback()
        stopBackdropStatsPollCallback()
        stopRenderThread()
        clearPendingSplash()
        clearPendingBackdropFrame()
        clearBackdropFrame(clearNative = true)
        clearNativeSurface()
        destroyRenderer()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val start = ZynaPerfLog.start()
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "surface available ${width}x$height visible=$visibility pending=${pendingPaintSplash != null}")
        }
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        surface = Surface(surfaceTexture)
        if (enabled && visibility == VISIBLE) {
            ensureRenderer()
            setNativeSurface(width, height)
            startPendingBackdropIfReady()
            startPendingSplashIfReady() || requestRenderFrame()
        }
        ZynaPerfLog.end(
            start,
            "vulkanOverlay.surfaceAvailable"
        ) {
            "size=${width}x$height enabled=$enabled visibility=$visibility handle=${nativeHandle != 0L}"
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "surface size changed ${width}x$height visible=$visibility pending=${pendingPaintSplash != null}")
        }
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (enabled && visibility == VISIBLE) {
            setNativeSurface(width, height)
            startPendingBackdropIfReady()
            startPendingSplashIfReady() || requestRenderFrame()
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "surface destroyed")
        }
        stopFrameCallback()
        stopBackdropStatsPollCallback()
        stopRenderThread()
        clearNativeSurface()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun ensureRenderer() {
        val start = ZynaPerfLog.start()
        if (nativeHandle != 0L || creationAttempted || !NativeVulkanChat.isAvailable) {
            ZynaPerfLog.endIfSlow(
                start,
                "vulkanOverlay.ensureRenderer.skip.slow",
                thresholdMs = 1.0
            ) {
                "handle=${nativeHandle != 0L} attempted=$creationAttempted " +
                    "available=${NativeVulkanChat.isAvailable}"
            }
            return
        }
        creationAttempted = true
        nativeHandle = runCatching {
            NativeVulkanChat.nativeCreate()
        }.getOrElse { error ->
            Log.w(TAG, "Failed to create Vulkan chat renderer", error)
            0L
        }
        if (nativeHandle != 0L) {
            NativeVulkanChat.nativeSetClearColor(
                nativeHandle,
                DEBUG_CLEAR_RED,
                DEBUG_CLEAR_GREEN,
                DEBUG_CLEAR_BLUE,
                DEBUG_MARKER_ALPHA
            )
            if (width > 0 && height > 0) {
                setInputBarBounds(0, height, width, height)
            }
        } else {
            visibility = GONE
        }
        ZynaPerfLog.end(
            start,
            "vulkanOverlay.ensureRenderer"
        ) {
            "handle=${nativeHandle != 0L} visibility=$visibility size=${width}x$height"
        }
    }

    private fun hideIdleSurface() {
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "hide idle surface")
        }
        stopFrameCallback()
        stopBackdropStatsPollCallback()
        clearPendingSplash()
        clearPendingBackdropFrame()
        idleClearFramesRemaining = 0
        if (!hasActiveBackdropFrame()) {
            clearNativeSurface()
            if (keepSurfaceWarm && enabled && NativeVulkanChat.isAvailable) {
                alpha = 0f
                visibility = VISIBLE
                bindCurrentSurface()
            } else {
                visibility = if (enabled && NativeVulkanChat.isAvailable) INVISIBLE else GONE
            }
        } else {
            alpha = presentedAlpha()
            visibility = VISIBLE
        }
    }

    private fun presentedAlpha(): Float {
        return if (presentationSuppressed) 0f else 1f
    }

    private fun startPendingSplashIfReady(): Boolean {
        if (nativeHandle == 0L || !shouldRender()) {
            return false
        }
        synchronized(renderStateLock) {
            if (pendingPaintSplash == null) {
                return false
            }
        }
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "queue native splash render work")
        }
        idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
        return requestRenderFrame()
    }

    private fun startPendingBackdropIfReady(): Boolean {
        if (nativeHandle == 0L || !shouldRender()) {
            return false
        }
        synchronized(renderStateLock) {
            if (pendingBackdropFrame == null) {
                return false
            }
        }
        return requestRenderFrame()
    }

    private fun clearPendingSplash() {
        synchronized(renderStateLock) {
            clearPendingSplashLocked()
        }
    }

    private fun clearPendingSplashLocked() {
        pendingPaintSplash?.bitmap?.recycle()
        pendingPaintSplash = null
    }

    private fun clearPendingBackdropFrame() {
        synchronized(renderStateLock) {
            clearPendingBackdropFrameLocked()
        }
    }

    private fun clearPendingBackdropFrameLocked() {
        pendingBackdropFrame?.frame?.close()
        pendingBackdropFrame = null
        pendingBackdropRectUpdate = null
    }

    private fun clearBackdropFrame(clearNative: Boolean) {
        synchronized(renderStateLock) {
            activeBackdropFrame?.frame?.close()
            activeBackdropFrame = null
            pendingBackdropRectUpdate = null
        }
        stopBackdropStatsPollCallback()
        if (clearNative && nativeHandle != 0L) {
            synchronized(nativeCallLock) {
                if (nativeHandle != 0L) {
                    NativeVulkanChat.nativeClearBackdropHardwareBuffer(nativeHandle)
                }
            }
        }
    }

    private fun postNativeRenderWork(
        frameId: Long,
        frameTimeNanos: Long,
        callbackNowNanos: Long,
        vsyncDeltaNanos: Long
    ): Boolean {
        if (nativeHandle == 0L || !shouldRender()) {
            return false
        }
        if (renderFrameInFlight) {
            logRenderPacing(
                frameId = frameId,
                frameTimeNanos = frameTimeNanos,
                callbackNowNanos = callbackNowNanos,
                vsyncDeltaNanos = vsyncDeltaNanos,
                renderThreadStartNanos = 0L,
                renderThreadEndNanos = 0L,
                mainReturnNanos = System.nanoTime(),
                renderResult = null,
                skippedInFlight = true
            )
            return true
        }
        val handler = ensureRenderHandler() ?: return false
        renderFrameInFlight = true
        val postedToRenderThreadNanos = System.nanoTime()
        handler.post {
            val renderThreadStartNanos = System.nanoTime()
            val renderResult = drainPendingNativeWorkAndRender()
            val renderThreadEndNanos = System.nanoTime()
            val renderNanos = renderThreadEndNanos - renderThreadStartNanos
            mainHandler.post {
                val mainReturnNanos = System.nanoTime()
                renderFrameInFlight = false
                if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
                    Log.d(
                        TAG,
                        "render frame async nativeMs=${renderNanos.msString()} " +
                            "active=${renderResult.hasActiveEffects} " +
                            "backdrop=${renderResult.didUpdateBackdrop}"
                    )
                }
                if (renderResult.didUpdateBackdrop) {
                    scheduleBackdropStatsPoll()
                }
                lastRenderHadActiveEffects = renderResult.hasActiveEffects
                logRenderPacing(
                    frameId = frameId,
                    frameTimeNanos = frameTimeNanos,
                    callbackNowNanos = callbackNowNanos,
                    vsyncDeltaNanos = vsyncDeltaNanos,
                    renderThreadStartNanos = renderThreadStartNanos,
                    renderThreadEndNanos = renderThreadEndNanos,
                    mainReturnNanos = mainReturnNanos,
                    renderResult = renderResult,
                    skippedInFlight = false,
                    postedToRenderThreadNanos = postedToRenderThreadNanos
                )
                applyRenderFrameResult(renderResult.hasActiveEffects)
                if (hasPendingNativeWork() && shouldRender()) {
                    requestRenderFrame()
                }
            }
        }
        return true
    }

    private fun logRenderPacing(
        frameId: Long,
        frameTimeNanos: Long,
        callbackNowNanos: Long,
        vsyncDeltaNanos: Long,
        renderThreadStartNanos: Long,
        renderThreadEndNanos: Long,
        mainReturnNanos: Long,
        renderResult: RenderFrameResult?,
        skippedInFlight: Boolean,
        postedToRenderThreadNanos: Long = callbackNowNanos
    ) {
        if (!ENABLE_VULKAN_CHAT_PACING_LOGGING || renderPacingLogsRemaining <= 0) {
            return
        }
        val active = renderResult?.hasActiveEffects == true
        val backdrop = renderResult?.didUpdateBackdrop == true
        val shouldLog = skippedInFlight ||
            active ||
            backdrop ||
            vsyncDeltaNanos >= VULKAN_CHAT_PACING_SLOW_VSYNC_NANOS
        if (!shouldLog) {
            return
        }
        renderPacingLogsRemaining -= 1
        val callbackLagNanos = callbackNowNanos - frameTimeNanos
        val queueNanos = if (renderThreadStartNanos > 0L) {
            renderThreadStartNanos - postedToRenderThreadNanos
        } else {
            0L
        }
        val renderNanos = if (renderThreadEndNanos > renderThreadStartNanos) {
            renderThreadEndNanos - renderThreadStartNanos
        } else {
            0L
        }
        val mainReturnNanosDelta = if (renderThreadEndNanos > 0L) {
            mainReturnNanos - renderThreadEndNanos
        } else {
            0L
        }
        val loopNanos = mainReturnNanos - callbackNowNanos
        Log.d(
            TAG,
            "pacing frame=$frameId " +
                "vsyncDeltaMs=${vsyncDeltaNanos.msString()} " +
                "callbackLagMs=${callbackLagNanos.msString()} " +
                "queueMs=${queueNanos.msString()} " +
                "renderMs=${renderNanos.msString()} " +
                "mainReturnMs=${mainReturnNanosDelta.msString()} " +
                "loopMs=${loopNanos.msString()} " +
                "active=$active " +
                "backdrop=$backdrop " +
                "pending=${hasPendingNativeWork()} " +
                "skippedInFlight=$skippedInFlight"
        )
    }

    private fun applyRenderFrameResult(hasActiveEffects: Boolean): Boolean {
        if (!shouldRender()) {
            return false
        }
        var willContinueRendering = false
        if (hasActiveEffects || hasPendingNativeWork()) {
            idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
            postFrameCallback()
            willContinueRendering = true
        } else if (hasActiveBackdropFrame()) {
            idleClearFramesRemaining = 0
        } else if (idleClearFramesRemaining > 0) {
            idleClearFramesRemaining -= 1
            postFrameCallback()
            willContinueRendering = true
        } else {
            hideIdleSurface()
        }
        return willContinueRendering
    }

    private fun shouldFrontScheduleNextRenderFrame(): Boolean {
        return shouldRender() &&
            (renderFrameInFlight ||
                lastRenderHadActiveEffects ||
                hasPendingNativeWork() ||
                idleClearFramesRemaining > 0)
    }

    private fun requestRenderFrame(): Boolean {
        if (nativeHandle == 0L || !shouldRender()) {
            return false
        }
        postFrameCallback()
        return true
    }

    private fun renderNativeFrame(): Boolean {
        return synchronized(nativeCallLock) {
            val handle = nativeHandle
            if (handle == 0L) {
                false
            } else {
                NativeVulkanChat.nativeRenderFrame(handle)
            }
        }
    }

    private fun drainPendingNativeWorkAndRender(): RenderFrameResult {
        val splash = synchronized(renderStateLock) {
            pendingPaintSplash.also { pendingPaintSplash = null }
        }
        if (splash != null) {
            synchronized(nativeCallLock) {
                val handle = nativeHandle
                if (handle != 0L) {
                    runCatching {
                        NativeVulkanChat.nativeAddPaintSplashBitmap(
                            handle,
                            splash.bitmap,
                            splash.left,
                            splash.top,
                            splash.right,
                            splash.bottom
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to add native splash", error)
                    }
                }
            }
            splash.bitmap.recycle()
        }

        var didUpdateBackdrop = false
        val backdrop = synchronized(renderStateLock) {
            pendingBackdropFrame.also { pendingBackdropFrame = null }
        }
        if (backdrop != null) {
            val previousBackdrop = synchronized(renderStateLock) {
                activeBackdropFrame.also { activeBackdropFrame = null }
            }
            val didImport = synchronized(nativeCallLock) {
                val handle = nativeHandle
                if (handle == 0L) {
                    false
                } else {
                    runCatching {
                        NativeVulkanChat.nativeSetBackdropHardwareBuffer(
                            handle,
                            backdrop.frame.hardwareBuffer,
                            backdrop.rectValues,
                            backdrop.textureLeft,
                            backdrop.textureTop
                        )
                    }.getOrElse { error ->
                        Log.w(TAG, "Failed to import backdrop HardwareBuffer", error)
                        false
                    }
                }
            }
            previousBackdrop?.frame?.close()
            if (didImport) {
                synchronized(renderStateLock) {
                    activeBackdropFrame = ActiveBackdropFrame(backdrop.frame)
                }
                didUpdateBackdrop = true
            } else {
                backdrop.frame.close()
            }
        }

        val rectUpdate = synchronized(renderStateLock) {
            pendingBackdropRectUpdate.also { pendingBackdropRectUpdate = null }
        }
        if (rectUpdate != null && hasActiveBackdropFrame()) {
            synchronized(nativeCallLock) {
                val handle = nativeHandle
                if (handle == 0L) {
                    false
                } else {
                    runCatching {
                        NativeVulkanChat.nativeUpdateBackdropRects(
                            handle,
                            rectUpdate.rectValues,
                            rectUpdate.textureLeft,
                            rectUpdate.textureTop
                        )
                    }.getOrElse { error ->
                        Log.w(TAG, "Failed to update backdrop rects", error)
                        false
                    }
                }
            }
        }

        return RenderFrameResult(
            hasActiveEffects = renderNativeFrame(),
            didUpdateBackdrop = didUpdateBackdrop
        )
    }

    private fun hasPendingNativeWork(): Boolean {
        return synchronized(renderStateLock) {
            pendingPaintSplash != null ||
                pendingBackdropFrame != null ||
                pendingBackdropRectUpdate != null
        }
    }

    private fun hasActiveBackdropFrame(): Boolean {
        return synchronized(renderStateLock) {
            activeBackdropFrame != null
        }
    }

    private fun destroyRenderer() {
        synchronized(nativeCallLock) {
            val handle = nativeHandle
            nativeHandle = 0L
            creationAttempted = false
            if (handle != 0L) {
                NativeVulkanChat.nativeDestroy(handle)
            }
        }
    }

    private fun ensureRenderHandler(): Handler? {
        renderHandler?.let { return it }
        val thread = HandlerThread("ZynaVulkanChatRender").also { it.start() }
        renderThread = thread
        return Handler(thread.looper).also { renderHandler = it }
    }

    private fun stopRenderThread() {
        renderHandler?.removeCallbacksAndMessages(null)
        renderHandler = null
        renderThread?.quitSafely()
        renderThread = null
        renderFrameInFlight = false
        lastRenderHadActiveEffects = false
    }

    private fun bindCurrentSurface() {
        val texture = surfaceTexture ?: return
        if (surface == null) {
            surface = Surface(texture)
            nativeSurfaceBound = false
        }
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        if (
            nativeSurfaceBound &&
            boundSurfaceWidth == nextWidth &&
            boundSurfaceHeight == nextHeight
        ) {
            return
        }
        setNativeSurface(nextWidth, nextHeight)
        nativeSurfaceBound = true
        boundSurfaceWidth = nextWidth
        boundSurfaceHeight = nextHeight
    }

    private fun setNativeSurface(width: Int, height: Int) {
        val handle = nativeHandle
        if (handle == 0L) {
            return
        }
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        synchronized(nativeCallLock) {
            if (nativeHandle == 0L) {
                return
            }
            NativeVulkanChat.nativeSetSurface(
                nativeHandle,
                surface,
                nextWidth,
                nextHeight
            )
        }
        nativeSurfaceBound = surface != null
        boundSurfaceWidth = if (nativeSurfaceBound) nextWidth else 0
        boundSurfaceHeight = if (nativeSurfaceBound) nextHeight else 0
    }

    private fun clearNativeSurface() {
        synchronized(nativeCallLock) {
            val handle = nativeHandle
            if (handle != 0L) {
                NativeVulkanChat.nativeSetSurface(handle, null, 1, 1)
            }
        }
        nativeSurfaceBound = false
        boundSurfaceWidth = 0
        boundSurfaceHeight = 0
        lastRenderHadActiveEffects = false
    }

    private fun shouldRender(): Boolean {
        return enabled &&
            nativeHandle != 0L &&
            surface != null &&
            isAttachedToWindow &&
            visibility == View.VISIBLE &&
            width > 0 &&
            height > 0
    }

    private fun postFrameCallback() {
        if (!frameCallbackPosted && shouldRender()) {
            frameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun scheduleBackdropStatsPoll() {
        if (nativeHandle == 0L || !hasActiveBackdropFrame() || !isAttachedToWindow) {
            return
        }
        backdropStatsPollAttemptsRemaining = BACKDROP_STATS_POLL_ATTEMPTS
        if (!backdropStatsPollCallbackPosted) {
            backdropStatsPollCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(backdropStatsPollCallback)
        }
    }

    private fun runBackdropStatsPoll() {
        if (nativeHandle == 0L || !hasActiveBackdropFrame() || !isAttachedToWindow) {
            backdropStatsPollAttemptsRemaining = 0
            return
        }
        val handler = ensureRenderHandler()
        if (handler == null) {
            finishBackdropStatsPoll(values = null)
            return
        }
        handler.post {
            val values = pollBackdropStatsOnRenderThread()
            mainHandler.post {
                finishBackdropStatsPoll(values)
            }
        }
    }

    private fun finishBackdropStatsPoll(values: FloatArray?) {
        if (values != null && values.size >= 4) {
            backdropStatsPollAttemptsRemaining = 0
            onBackdropStats(
                VulkanGlassBackdropStats(
                    meanLuma = values[0],
                    variance = values[1],
                    brightFraction = values[2],
                    darkFraction = values[3]
                )
            )
            return
        }
        backdropStatsPollAttemptsRemaining -= 1
        if (backdropStatsPollAttemptsRemaining > 0 && !backdropStatsPollCallbackPosted) {
            backdropStatsPollCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(backdropStatsPollCallback)
        }
    }

    private fun pollBackdropStatsOnRenderThread(): FloatArray? {
        return synchronized(nativeCallLock) {
            if (nativeHandle == 0L) {
                null
            } else {
                runCatching {
                    NativeVulkanChat.nativePollBackdropStats(nativeHandle)
                }.getOrElse { error ->
                    Log.w(TAG, "Failed to poll backdrop stats", error)
                    null
                }
            }
        }
    }

    private fun stopFrameCallback() {
        if (frameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameCallbackPosted = false
        }
        lastRenderFrameTimeNanos = 0L
        lastRenderHadActiveEffects = false
    }

    private fun stopBackdropStatsPollCallback() {
        if (backdropStatsPollCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(backdropStatsPollCallback)
            backdropStatsPollCallbackPosted = false
        }
        backdropStatsPollAttemptsRemaining = 0
    }

    private companion object {
        const val TAG = "ZynaVulkanChat"
        const val DEBUG_CLEAR_RED = 0.04f
        const val DEBUG_CLEAR_GREEN = 0.72f
        const val DEBUG_CLEAR_BLUE = 0.86f
        const val DEBUG_MARKER_ALPHA = 0.82f
        const val IDLE_CLEAR_FRAME_COUNT = 4
        const val BACKDROP_STATS_POLL_ATTEMPTS = 4
        const val VULKAN_CHAT_PACING_LOG_LIMIT = 120
        const val VULKAN_CHAT_PACING_SLOW_VSYNC_NANOS = 12_000_000L
    }
}

private data class PendingPaintSplash(
    val bitmap: android.graphics.Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

private data class PendingBackdropFrame(
    val frame: HardwareBufferChatCapture.CapturedFrame,
    val rectValues: FloatArray,
    val rectCount: Int,
    val textureLeft: Float,
    val textureTop: Float
)

private data class PendingBackdropRectUpdate(
    val rectValues: FloatArray,
    val textureLeft: Float,
    val textureTop: Float
)

private data class ActiveBackdropFrame(
    val frame: HardwareBufferChatCapture.CapturedFrame
)

private data class RenderFrameResult(
    val hasActiveEffects: Boolean,
    val didUpdateBackdrop: Boolean
)

internal data class BackdropFrameResult(
    val totalNanos: Long = 0L,
    val importNanos: Long = 0L,
    val renderCallNanos: Long = 0L,
    val imported: Boolean,
    val rendered: Boolean = false,
    val willContinueRendering: Boolean = false,
    val mainThread: Boolean = Looper.myLooper() == Looper.getMainLooper()
)

internal data class VulkanGlassBackdropStats(
    val meanLuma: Float,
    val variance: Float,
    val brightFraction: Float,
    val darkFraction: Float
)

private const val VULKAN_GLASS_RECT_FLOAT_COUNT = 11
private const val ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING = false
private const val ENABLE_VULKAN_CHAT_PACING_LOGGING = false

private fun List<VulkanChatGlassRect>.toNativeRectValues(): FloatArray {
    val validRects = filter { it.isValid() }
    if (validRects.isEmpty()) {
        return FloatArray(0)
    }
    val values = FloatArray(validRects.size * VULKAN_GLASS_RECT_FLOAT_COUNT)
    var index = 0
    for (rect in validRects) {
        values[index++] = rect.left
        values[index++] = rect.top
        values[index++] = rect.right
        values[index++] = rect.bottom
        values[index++] = rect.cornerRadius
        values[index++] = rect.opacity
        values[index++] = rect.bezelWidth
        values[index++] = rect.glassThickness
        values[index++] = rect.adaptiveAppearance
        values[index++] = rect.adaptiveContrast
        values[index++] = rect.shapeKind
    }
    return values
}
