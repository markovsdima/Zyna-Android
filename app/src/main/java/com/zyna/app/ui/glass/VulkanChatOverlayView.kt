package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.SurfaceTexture
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
    private var frameCallbackPosted = false
    private var creationAttempted = false
    private var enabled = false
    private var pendingPaintSplash: PendingPaintSplash? = null
    private var idleClearFramesRemaining = 0

    private val frameCallback = Choreographer.FrameCallback {
        frameCallbackPosted = false
        renderActiveEffectsOrDrain()
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

    fun setDebugOverlayEnabled(isEnabled: Boolean) {
        if (enabled == isEnabled) {
            return
        }
        enabled = isEnabled
        visibility = if (enabled && NativeVulkanChat.isAvailable) INVISIBLE else GONE
        if (enabled) {
            ensureRenderer()
        } else {
            stopFrameCallback()
            clearPendingSplash()
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
        Log.d(TAG, "queue splash bounds=$bounds surfaceReady=${surface != null} visible=$visibility")
        idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
        visibility = VISIBLE
        bindCurrentSurface()
        startPendingSplashIfReady()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (enabled) {
            ensureRenderer()
            if (visibility == VISIBLE) {
                bindCurrentSurface()
                startPendingSplashIfReady() || renderActiveEffectsOrDrain()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopFrameCallback()
        clearPendingSplash()
        clearNativeSurface()
        destroyRenderer()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "surface available ${width}x$height visible=$visibility pending=${pendingPaintSplash != null}")
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        surface = Surface(surfaceTexture)
        if (enabled && visibility == VISIBLE) {
            ensureRenderer()
            setNativeSurface(width, height)
            startPendingSplashIfReady() || renderActiveEffectsOrDrain()
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "surface size changed ${width}x$height visible=$visibility pending=${pendingPaintSplash != null}")
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (enabled && visibility == VISIBLE) {
            setNativeSurface(width, height)
            startPendingSplashIfReady() || renderActiveEffectsOrDrain()
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        Log.d(TAG, "surface destroyed")
        stopFrameCallback()
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
        Log.d(TAG, "hide idle surface")
        stopFrameCallback()
        clearPendingSplash()
        idleClearFramesRemaining = 0
        clearNativeSurface()
        visibility = if (enabled && NativeVulkanChat.isAvailable) INVISIBLE else GONE
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
        Log.d(TAG, "start native splash")
        idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
        return renderActiveEffectsOrDrain()
    }

    private fun clearPendingSplash() {
        pendingPaintSplash?.bitmap?.recycle()
        pendingPaintSplash = null
    }

    private fun renderActiveEffectsOrDrain(): Boolean {
        val handle = nativeHandle
        if (handle == 0L || !shouldRender()) {
            return false
        }
        val hasActiveEffects = NativeVulkanChat.nativeRenderFrame(handle)
        var willContinueRendering = false
        if (hasActiveEffects) {
            idleClearFramesRemaining = IDLE_CLEAR_FRAME_COUNT
            postFrameCallback()
            willContinueRendering = true
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
        }
        setNativeSurface(width, height)
    }

    private fun setNativeSurface(width: Int, height: Int) {
        val handle = nativeHandle
        if (handle == 0L) {
            return
        }
        NativeVulkanChat.nativeSetSurface(
            handle,
            surface,
            width.coerceAtLeast(1),
            height.coerceAtLeast(1)
        )
    }

    private fun clearNativeSurface() {
        val handle = nativeHandle
        if (handle != 0L) {
            NativeVulkanChat.nativeSetSurface(handle, null, 1, 1)
        }
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

    private fun stopFrameCallback() {
        if (frameCallbackPosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameCallbackPosted = false
        }
    }

    private companion object {
        const val TAG = "ZynaVulkanChat"
        const val DEBUG_CLEAR_RED = 0.04f
        const val DEBUG_CLEAR_GREEN = 0.72f
        const val DEBUG_CLEAR_BLUE = 0.86f
        const val DEBUG_MARKER_ALPHA = 0.82f
        const val IDLE_CLEAR_FRAME_COUNT = 4
    }
}

private data class PendingPaintSplash(
    val bitmap: android.graphics.Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
