package com.zyna.app.ui.glass

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Surface

internal object NativeVulkanChat {
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("zyna_vulkan_chat")
        true
    }.getOrElse { error ->
        Log.w(TAG, "Vulkan chat renderer unavailable", error)
        false
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeSetSurface(
        handle: Long,
        surface: Surface?,
        width: Int,
        height: Int
    )

    external fun nativeSetClearColor(
        handle: Long,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float
    )

    external fun nativeSetInputBarBounds(
        handle: Long,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    )

    external fun nativeAddPaintSplashBitmap(
        handle: Long,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    )

    external fun nativeProbeHardwareBuffer(hardwareBuffer: HardwareBuffer): Boolean

    external fun nativeSetBackdropHardwareBuffer(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        rectValues: FloatArray,
        textureLeft: Float,
        textureTop: Float
    ): Boolean

    external fun nativeUpdateBackdropRects(
        handle: Long,
        rectValues: FloatArray,
        textureLeft: Float,
        textureTop: Float
    ): Boolean

    external fun nativeSetTeleportHardwareBuffers(
        handle: Long,
        oldHardwareBuffer: HardwareBuffer,
        newHardwareBuffer: HardwareBuffer,
        viewportLeft: Float,
        viewportTop: Float,
        viewportRight: Float,
        viewportBottom: Float,
        captureLeft: Float,
        captureTop: Float,
        captureWidth: Int,
        captureHeight: Int,
        directionSign: Float
    ): Boolean

    external fun nativeUpdateTeleportProgress(handle: Long, progress: Float): Boolean

    external fun nativeClearTeleport(handle: Long)

    external fun nativePollBackdropStats(handle: Long): FloatArray?

    external fun nativeClearBackdropHardwareBuffer(handle: Long)

    external fun nativeRenderFrame(handle: Long): Boolean

    private const val TAG = "ZynaVulkanChat"
}
