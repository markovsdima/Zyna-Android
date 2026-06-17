package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.zyna.app.ui.chat.render.PaintSplashTarget

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
    private var pendingPaintSplash: PendingPaintSplash? = null
    private var pendingBackdropFrame: PendingBackdropFrame? = null
    private var activeBackdropFrame: ActiveBackdropFrame? = null
    private var idleClearFramesRemaining = 0
    private var backdropStatsPollCallbackPosted = false
    private var backdropStatsPollAttemptsRemaining = 0
    var onBackdropStats: (VulkanGlassBackdropStats) -> Unit = {}

    private val frameCallback = Choreographer.FrameCallback {
        frameCallbackPosted = false
        renderActiveEffectsOrDrain()
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
        if (enabled == isEnabled) {
            return
        }
        enabled = isEnabled
        visibility = if (enabled && NativeVulkanChat.isAvailable) INVISIBLE else GONE
        if (enabled) {
            ensureRenderer()
        } else {
            stopFrameCallback()
            stopBackdropStatsPollCallback()
            clearPendingSplash()
            clearBackdropFrame(clearNative = true)
            clearNativeSurface()
        }
    }

    fun setInputBarBounds(left: Int, top: Int, right: Int, bottom: Int) {
        val handle = nativeHandle
        if (handle == 0L) {
            return
        }
        NativeVulkanChat.nativeSetInputBarBounds(
            handle,
            left.toFloat(),
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat()
        )
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
        clearPendingSplash()
        pendingPaintSplash = PendingPaintSplash(
            bitmap = target.bitmap,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "queue splash bounds=$bounds surfaceReady=${surface != null} visible=$visibility")
        }
        idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
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

        clearPendingBackdropFrame()
        pendingBackdropFrame = PendingBackdropFrame(
            frame = frame,
            rectValues = rectValues,
            rectCount = rectValues.size / VULKAN_GLASS_RECT_FLOAT_COUNT,
            textureLeft = textureLeft,
            textureTop = textureTop
        )
        visibility = VISIBLE
        bindCurrentSurface()
        val startResult = startPendingBackdropIfReady()
        return startResult.copy(
            totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos
        )
    }

    fun updateBackdropRects(
        rects: List<VulkanChatGlassRect>,
        textureLeft: Float = 0f,
        textureTop: Float = 0f
    ): BackdropFrameResult {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val rectValues = rects.toNativeRectValues()
        if (rectValues.isEmpty() || activeBackdropFrame == null) {
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

        visibility = VISIBLE
        bindCurrentSurface()
        val updateStartNanos = SystemClock.elapsedRealtimeNanos()
        val didUpdate = runCatching {
            NativeVulkanChat.nativeUpdateBackdropRects(
                nativeHandle,
                rectValues,
                textureLeft,
                textureTop
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to update backdrop rects", error)
            false
        }
        val updateNanos = SystemClock.elapsedRealtimeNanos() - updateStartNanos
        if (!didUpdate) {
            return BackdropFrameResult(
                totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
                importNanos = updateNanos,
                imported = false,
                rendered = false
            )
        }

        val renderStartNanos = SystemClock.elapsedRealtimeNanos()
        val willContinueRendering = renderActiveEffectsOrDrain()
        val renderCallNanos = SystemClock.elapsedRealtimeNanos() - renderStartNanos
        return BackdropFrameResult(
            totalNanos = SystemClock.elapsedRealtimeNanos() - startNanos,
            importNanos = updateNanos,
            renderCallNanos = renderCallNanos,
            imported = true,
            rendered = true,
            willContinueRendering = willContinueRendering,
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (enabled) {
            ensureRenderer()
            if (visibility == VISIBLE) {
                bindCurrentSurface()
                startPendingBackdropIfReady()
                startPendingSplashIfReady() || renderActiveEffectsOrDrain()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopFrameCallback()
        stopBackdropStatsPollCallback()
        clearPendingSplash()
        clearPendingBackdropFrame()
        clearBackdropFrame(clearNative = true)
        clearNativeSurface()
        destroyRenderer()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "surface available ${width}x$height visible=$visibility pending=${pendingPaintSplash != null}")
        }
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        surface = Surface(surfaceTexture)
        if (enabled && visibility == VISIBLE) {
            ensureRenderer()
            setNativeSurface(width, height)
            startPendingBackdropIfReady()
            startPendingSplashIfReady() || renderActiveEffectsOrDrain()
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
            startPendingSplashIfReady() || renderActiveEffectsOrDrain()
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "surface destroyed")
        }
        stopFrameCallback()
        stopBackdropStatsPollCallback()
        clearNativeSurface()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun ensureRenderer() {
        if (nativeHandle != 0L || creationAttempted || !NativeVulkanChat.isAvailable) {
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
        if (activeBackdropFrame == null) {
            clearNativeSurface()
            visibility = if (enabled && NativeVulkanChat.isAvailable) INVISIBLE else GONE
        } else {
            visibility = VISIBLE
        }
    }

    private fun startPendingSplashIfReady(): Boolean {
        val splash = pendingPaintSplash ?: return false
        val handle = nativeHandle
        if (handle == 0L || !shouldRender()) {
            return false
        }
        pendingPaintSplash = null
        try {
            NativeVulkanChat.nativeAddPaintSplashBitmap(
                handle,
                splash.bitmap,
                splash.left,
                splash.top,
                splash.right,
                splash.bottom
            )
        } finally {
            splash.bitmap.recycle()
        }
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "start native splash")
        }
        idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
        return renderActiveEffectsOrDrain()
    }

    private fun startPendingBackdropIfReady(): BackdropFrameResult {
        val backdrop = pendingBackdropFrame ?: return BackdropFrameResult(imported = false)
        val handle = nativeHandle
        if (handle == 0L || !shouldRender()) {
            return BackdropFrameResult(imported = false)
        }
        pendingBackdropFrame = null
        val previousBackdrop = activeBackdropFrame
        activeBackdropFrame = null
        val importStartNanos = SystemClock.elapsedRealtimeNanos()
        val didImport = runCatching {
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
        val importNanos = SystemClock.elapsedRealtimeNanos() - importStartNanos
        previousBackdrop?.frame?.close()

        if (!didImport) {
            backdrop.frame.close()
            if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
                Log.d(TAG, "import backdrop failed nativeMs=${importNanos.msString()}")
            }
            return BackdropFrameResult(
                importNanos = importNanos,
                imported = false
            )
        }

        activeBackdropFrame = ActiveBackdropFrame(backdrop.frame)
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(
                TAG,
                "import backdrop rects=${backdrop.rectCount} " +
                    "origin=${backdrop.textureLeft},${backdrop.textureTop} " +
                    "size=${backdrop.frame.width}x${backdrop.frame.height} " +
                    "nativeMs=${importNanos.msString()}"
            )
        }
        val renderStartNanos = SystemClock.elapsedRealtimeNanos()
        val willContinueRendering = renderActiveEffectsOrDrain()
        val renderCallNanos = SystemClock.elapsedRealtimeNanos() - renderStartNanos
        scheduleBackdropStatsPoll()
        return BackdropFrameResult(
            importNanos = importNanos,
            renderCallNanos = renderCallNanos,
            imported = true,
            rendered = true,
            willContinueRendering = willContinueRendering,
            mainThread = Looper.myLooper() == Looper.getMainLooper()
        )
    }

    private fun clearPendingSplash() {
        pendingPaintSplash?.bitmap?.recycle()
        pendingPaintSplash = null
    }

    private fun clearPendingBackdropFrame() {
        pendingBackdropFrame?.frame?.close()
        pendingBackdropFrame = null
    }

    private fun clearBackdropFrame(clearNative: Boolean) {
        activeBackdropFrame?.frame?.close()
        activeBackdropFrame = null
        stopBackdropStatsPollCallback()
        if (clearNative && nativeHandle != 0L) {
            NativeVulkanChat.nativeClearBackdropHardwareBuffer(nativeHandle)
        }
    }

    private fun renderActiveEffectsOrDrain(): Boolean {
        val handle = nativeHandle
        if (handle == 0L || !shouldRender()) {
            return false
        }
        val renderStartNanos = SystemClock.elapsedRealtimeNanos()
        val hasActiveEffects = NativeVulkanChat.nativeRenderFrame(handle)
        val renderNanos = SystemClock.elapsedRealtimeNanos() - renderStartNanos
        if (ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING) {
            Log.d(TAG, "render frame nativeMs=${renderNanos.msString()} active=$hasActiveEffects")
        }
        var willContinueRendering = false
        if (hasActiveEffects) {
            idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
            postFrameCallback()
            willContinueRendering = true
        } else if (activeBackdropFrame != null) {
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

    private fun destroyRenderer() {
        val handle = nativeHandle
        nativeHandle = 0L
        creationAttempted = false
        if (handle != 0L) {
            NativeVulkanChat.nativeDestroy(handle)
        }
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
        NativeVulkanChat.nativeSetSurface(
            handle,
            surface,
            nextWidth,
            nextHeight
        )
        nativeSurfaceBound = surface != null
        boundSurfaceWidth = if (nativeSurfaceBound) nextWidth else 0
        boundSurfaceHeight = if (nativeSurfaceBound) nextHeight else 0
    }

    private fun clearNativeSurface() {
        val handle = nativeHandle
        if (handle != 0L) {
            NativeVulkanChat.nativeSetSurface(handle, null, 1, 1)
        }
        nativeSurfaceBound = false
        boundSurfaceWidth = 0
        boundSurfaceHeight = 0
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
        if (nativeHandle == 0L || activeBackdropFrame == null || !isAttachedToWindow) {
            return
        }
        backdropStatsPollAttemptsRemaining = BACKDROP_STATS_POLL_ATTEMPTS
        if (!backdropStatsPollCallbackPosted) {
            backdropStatsPollCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(backdropStatsPollCallback)
        }
    }

    private fun runBackdropStatsPoll() {
        if (nativeHandle == 0L || activeBackdropFrame == null || !isAttachedToWindow) {
            backdropStatsPollAttemptsRemaining = 0
            return
        }
        if (pollBackdropStats()) {
            backdropStatsPollAttemptsRemaining = 0
            return
        }
        backdropStatsPollAttemptsRemaining -= 1
        if (backdropStatsPollAttemptsRemaining > 0 && !backdropStatsPollCallbackPosted) {
            backdropStatsPollCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(backdropStatsPollCallback)
        }
    }

    private fun pollBackdropStats(): Boolean {
        val handle = nativeHandle
        if (handle == 0L) {
            return false
        }
        val values = runCatching {
            NativeVulkanChat.nativePollBackdropStats(handle)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to poll backdrop stats", error)
            null
        } ?: return false
        if (values.size < 4) {
            return false
        }
        onBackdropStats(
            VulkanGlassBackdropStats(
                meanLuma = values[0],
                variance = values[1],
                brightFraction = values[2],
                darkFraction = values[3]
            )
        )
        return true
    }

    private fun stopFrameCallback() {
        if (frameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameCallbackPosted = false
        }
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

private data class ActiveBackdropFrame(
    val frame: HardwareBufferChatCapture.CapturedFrame
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

private const val VULKAN_GLASS_RECT_FLOAT_COUNT = 10
private const val ENABLE_VULKAN_CHAT_VERBOSE_RENDER_TIMING = false

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
    }
    return values
}
