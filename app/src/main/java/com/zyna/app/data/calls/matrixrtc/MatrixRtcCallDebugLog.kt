package com.zyna.app.data.calls.matrixrtc

import android.util.Log

internal object MatrixRtcCallDebugLog {
    private const val TAG = "MatrixRtcCall"

    fun d(message: String, error: Throwable? = null) {
        try {
            if (error != null) {
                Log.d(TAG, message, error)
            } else {
                Log.d(TAG, message)
            }
        } catch (_: RuntimeException) {
            // Local JVM unit tests do not provide android.util.Log.
        }
    }
}
