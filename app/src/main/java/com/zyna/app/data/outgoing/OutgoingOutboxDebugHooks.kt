package com.zyna.app.data.outgoing

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.zyna.app.BuildConfig
import kotlin.system.exitProcess

object OutgoingOutboxDebugHooks {
    const val EXTRA_CRASH_AFTER_NEXT_IMAGE_UPLOAD =
        "zyna.debug.crashAfterNextImageUpload"
    const val EXTRA_CLEAR_IMAGE_UPLOAD_CRASH =
        "zyna.debug.clearImageUploadCrash"

    fun handleIntent(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) {
            return
        }
        if (intent.getBooleanExtra(EXTRA_CLEAR_IMAGE_UPLOAD_CRASH, false)) {
            preferences(context).edit()
                .remove(KEY_CRASH_AFTER_NEXT_IMAGE_UPLOAD)
                .commit()
            Log.d(TAG, "Cleared image upload crash hook")
        }
        if (intent.getBooleanExtra(EXTRA_CRASH_AFTER_NEXT_IMAGE_UPLOAD, false)) {
            preferences(context).edit()
                .putBoolean(KEY_CRASH_AFTER_NEXT_IMAGE_UPLOAD, true)
                .commit()
            Log.d(TAG, "Armed image upload crash hook")
        }
    }

    fun crashAfterImageUploadCheckpointIfRequested(
        context: Context,
        envelopeId: String,
        transactionId: String
    ) {
        if (!BuildConfig.DEBUG || !consumeCrashAfterNextImageUpload(context)) {
            return
        }
        Log.w(
            TAG,
            "Debug crash after image upload checkpoint envelope=$envelopeId tx=$transactionId"
        )
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun consumeCrashAfterNextImageUpload(context: Context): Boolean {
        val preferences = preferences(context)
        if (!preferences.getBoolean(KEY_CRASH_AFTER_NEXT_IMAGE_UPLOAD, false)) {
            return false
        }
        preferences.edit()
            .remove(KEY_CRASH_AFTER_NEXT_IMAGE_UPLOAD)
            .commit()
        return true
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val TAG = "ZynaOutboxDebug"
    private const val PREFERENCES_NAME = "zyna_outbox_debug"
    private const val KEY_CRASH_AFTER_NEXT_IMAGE_UPLOAD =
        "crash_after_next_image_upload"
}
