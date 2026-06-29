package com.zyna.app.ui.glass

import android.annotation.SuppressLint
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

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
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private var renderer: HardwareRenderer? = null
    private var contentNode: RenderNode? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var targetGeneration = 0L
    private var latestRequest: PendingCaptureRequest? = null

    fun captureAsync(
        source: View,
        width: Int = source.width,
        height: Int = source.height,
        backgroundColor: Int = Color.TRANSPARENT,
        sourceLeft: Float = 0f,
        sourceTop: Float = 0f,
        discardPendingImagesBeforeDraw: Boolean = false,
        onFrameReady: (CapturedFrame) -> Unit
    ): Boolean {
        val captureWidth = width.coerceAtLeast(1)
        val captureHeight = height.coerceAtLeast(1)
        val ensureTargetStartNanos = SystemClock.elapsedRealtimeNanos()
        if (!ensureTarget(captureWidth, captureHeight)) {
            return false
        }
        val ensureTargetNanos = SystemClock.elapsedRealtimeNanos() - ensureTargetStartNanos

        val targetRenderer = renderer ?: return false
        val targetNode = contentNode ?: return false
        if (discardPendingImagesBeforeDraw) {
            discardPendingImages()
        }

        Trace.beginSection("ZynaHardwareBufferChatCapture")
        val renderResult: Int
        val recordNanos: Long
        val syncNanos: Long
        try {
            val recordStartNanos = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("ZynaHardwareBufferRecord")
            try {
                targetNode.setPosition(0, 0, captureWidth, captureHeight)
                val canvas = targetNode.beginRecording(captureWidth, captureHeight)
                try {
                    canvas.drawColor(backgroundColor, BlendMode.SRC)
                    val saveCount = canvas.save()
                    canvas.translate(-sourceLeft, -sourceTop)
                    source.draw(canvas)
                    canvas.restoreToCount(saveCount)
                } finally {
                    targetNode.endRecording()
                }
            } finally {
                Trace.endSection()
            }
            recordNanos = SystemClock.elapsedRealtimeNanos() - recordStartNanos

            val syncStartNanos = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("ZynaHardwareBufferSyncAndDraw")
            renderResult = targetRenderer
                .createRenderRequest()
                .syncAndDraw()
            Trace.endSection()
            syncNanos = SystemClock.elapsedRealtimeNanos() - syncStartNanos
        } finally {
            Trace.endSection()
        }

        latestRequest = PendingCaptureRequest(
            generation = targetGeneration,
            ensureTargetNanos = ensureTargetNanos,
            recordNanos = recordNanos,
            syncNanos = syncNanos,
            renderResult = renderResult,
            onFrameReady = onFrameReady
        )
        return true
    }

    override fun close() {
        latestRequest = null
        targetGeneration += 1
        renderer?.run {
            stop()
            destroy()
        }
        renderer = null

        contentNode?.discardDisplayList()
        contentNode = null

        surface = null

        imageReader?.setOnImageAvailableListener(null, null)
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
        ).apply {
            setOnImageAvailableListener({ reader ->
                handleImageAvailable(reader)
            }, callbackHandler)
        }
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

    private fun handleImageAvailable(reader: ImageReader) {
        val request = latestRequest
        if (request == null || reader !== imageReader || request.generation != targetGeneration) {
            drainReader(reader)
            return
        }

        val acquireStartNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("ZynaHardwareBufferAcquireImage")
        val image = try {
            reader.acquireLatestImage()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Failed to acquire latest ImageReader frame", error)
            null
        } finally {
            Trace.endSection()
        }
        val acquireNanos = SystemClock.elapsedRealtimeNanos() - acquireStartNanos
        if (image == null) {
            if (ENABLE_HARDWARE_BUFFER_CAPTURE_VERBOSE_TIMING) {
                Log.w(
                    TAG,
                    "No ImageReader frame after ${renderStatusName(request.renderResult)} " +
                        "ensureMs=${request.ensureTargetNanos.msString()} " +
                        "recordMs=${request.recordNanos.msString()} " +
                        "syncMs=${request.syncNanos.msString()} " +
                        "acquireMs=${acquireNanos.msString()}"
                )
            }
            return
        }

        val hardwareBufferStartNanos = SystemClock.elapsedRealtimeNanos()
        val hardwareBuffer = image.hardwareBuffer
        val hardwareBufferNanos = SystemClock.elapsedRealtimeNanos() - hardwareBufferStartNanos
        if (hardwareBuffer == null) {
            image.close()
            Log.w(TAG, "ImageReader frame has no HardwareBuffer")
            return
        }

        request.onFrameReady(
            CapturedFrame(
                image = image,
                hardwareBuffer = hardwareBuffer,
                width = image.width,
                height = image.height,
                format = image.format,
                usage = BUFFER_USAGE,
                renderResult = request.renderResult,
                renderNanos = request.ensureTargetNanos +
                    request.recordNanos +
                    request.syncNanos +
                    acquireNanos +
                    hardwareBufferNanos,
                ensureTargetNanos = request.ensureTargetNanos,
                recordNanos = request.recordNanos,
                syncNanos = request.syncNanos,
                acquireNanos = acquireNanos,
                hardwareBufferNanos = hardwareBufferNanos
            )
        )
    }

    private fun discardPendingImages() {
        val reader = imageReader ?: return
        drainReader(reader)
    }

    private fun drainReader(reader: ImageReader) {
        repeat(MAX_IMAGES) {
            val image = try {
                reader.acquireLatestImage()
            } catch (_: IllegalStateException) {
                return
            } ?: return
            image.close()
        }
    }

    private data class PendingCaptureRequest(
        val generation: Long,
        val ensureTargetNanos: Long,
        val recordNanos: Long,
        val syncNanos: Long,
        val renderResult: Int,
        val onFrameReady: (CapturedFrame) -> Unit
    )

    internal data class CapturedFrame(
        val image: Image,
        val hardwareBuffer: HardwareBuffer,
        val width: Int,
        val height: Int,
        val format: Int,
        val usage: Long,
        val renderResult: Int,
        val renderNanos: Long,
        val ensureTargetNanos: Long,
        val recordNanos: Long,
        val syncNanos: Long,
        val acquireNanos: Long,
        val hardwareBufferNanos: Long
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            try {
                hardwareBuffer.close()
            } finally {
                image.close()
            }
        }
    }

    private companion object {
        const val TAG = "ZynaHwBufferCapture"
        const val MAX_IMAGES = 4
        const val ENABLE_HARDWARE_BUFFER_CAPTURE_VERBOSE_TIMING = false
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

@SuppressLint("NewApi")
internal fun HardwareBufferChatCapture.CapturedFrame.closeCapturedFrame() {
    close()
}

internal fun Long.msString(): String {
    return "%.3f".format(this / 1_000_000.0)
}
