package com.zyna.app.ui.glass

import android.graphics.Bitmap
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

    external fun nativeRenderFrame(handle: Long): Boolean

    private const val TAG = "ZynaVulkanChat"
}
