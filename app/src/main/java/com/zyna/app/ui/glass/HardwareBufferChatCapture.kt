package com.zyna.app.ui.glass

import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import java.io.Closeable

/**
 * GPU-backed chat backdrop producer for the future Vulkan glass path.
 *
 * This deliberately does not expose pixels to the CPU: Android records a View into a RenderNode,
 * HardwareRenderer renders it into an ImageReader surface, and the resulting Image owns a
 * HardwareBuffer that native Vulkan can import while the Image remains open.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class HardwareBufferChatCapture(
    private val name: String = "ZynaChatBackdropCapture"
) : Closeable {
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private var renderer: HardwareRenderer? = null
    private var contentNode: RenderNode? = null
    private var targetWidth = 0
    private var targetHeight = 0

    fun capture(
        source: View,
        width: Int = source.width,
        height: Int = source.height,
        backgroundColor: Int = Color.TRANSPARENT
    ): CapturedFrame? {
        val captureWidth = width.coerceAtLeast(1)
        val captureHeight = height.coerceAtLeast(1)
        if (!ensureTarget(captureWidth, captureHeight)) {
            return null
        }

        val targetRenderer = renderer ?: return null
        val targetNode = contentNode ?: return null

        Trace.beginSection("ZynaHardwareBufferChatCapture")
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val renderResult: Int
        try {
            targetNode.setPosition(0, 0, captureWidth, captureHeight)
            val canvas = targetNode.beginRecording(captureWidth, captureHeight)
            try {
                canvas.drawColor(backgroundColor, BlendMode.SRC)
                source.draw(canvas)
            } finally {
                targetNode.endRecording()
            }

            renderResult = targetRenderer
                .createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()
        } finally {
            Trace.endSection()
        }

        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "No ImageReader frame after ${renderStatusName(renderResult)}")
            return null
        }

        val hardwareBuffer = image.hardwareBuffer
        if (hardwareBuffer == null) {
            image.close()
            Log.w(TAG, "ImageReader frame has no HardwareBuffer")
            return null
        }

        return CapturedFrame(
            image = image,
            hardwareBuffer = hardwareBuffer,
            width = image.width,
            height = image.height,
            format = image.format,
            usage = imageReader?.usage ?: BUFFER_USAGE,
            renderResult = renderResult,
            renderNanos = SystemClock.elapsedRealtimeNanos() - startNanos
        )
    }

    override fun close() {
        renderer?.run {
            setSurface(null)
            stop()
            destroy()
        }
        renderer = null

        contentNode?.discardDisplayList()
        contentNode = null

        surface?.release()
        surface = null

        imageReader?.close()
        imageReader = null

        targetWidth = 0
        targetHeight = 0
    }

    private fun ensureTarget(width: Int, height: Int): Boolean {
        if (renderer != null && targetWidth == width && targetHeight == height) {
            return true
        }

        close()
        if (!HardwareBuffer.isSupported(
                width,
                height,
                HardwareBuffer.RGBA_8888,
                1,
                BUFFER_USAGE
            )
        ) {
            Log.w(TAG, "HardwareBuffer RGBA_8888 unsupported for ${width}x$height")
            return false
        }

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
            BUFFER_USAGE
        )
        val targetSurface = reader.surface
        val node = RenderNode("$name.Content")
        val hardwareRenderer = HardwareRenderer().apply {
            setName(name)
            setOpaque(false)
            setContentRoot(node)
            setSurface(targetSurface)
            start()
        }

        imageReader = reader
        surface = targetSurface
        contentNode = node
        renderer = hardwareRenderer
        targetWidth = width
        targetHeight = height
        return true
    }

    internal data class CapturedFrame(
        val image: Image,
        val hardwareBuffer: HardwareBuffer,
        val width: Int,
        val height: Int,
        val format: Int,
        val usage: Long,
        val renderResult: Int,
        val renderNanos: Long
    ) : Closeable {
        override fun close() {
            image.close()
        }
    }

    private companion object {
        const val TAG = "ZynaHwBufferCapture"
        const val MAX_IMAGES = 3
        val BUFFER_USAGE: Long =
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE

        fun renderStatusName(status: Int): String {
            return when (status) {
                HardwareRenderer.SYNC_OK -> "SYNC_OK"
                HardwareRenderer.SYNC_REDRAW_REQUESTED -> "SYNC_REDRAW_REQUESTED"
                HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND -> {
                    "SYNC_LOST_SURFACE_REWARD_IF_FOUND"
                }
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED -> "SYNC_CONTEXT_IS_STOPPED"
                HardwareRenderer.SYNC_FRAME_DROPPED -> "SYNC_FRAME_DROPPED"
                else -> "SYNC_$status"
            }
        }
    }
}
