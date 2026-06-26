package com.zyna.app.data.calls.matrixrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zyna.app.ZynaApplication

class MatrixRtcIncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MatrixRtcIncomingCallIntents.ACTION_DECLINE) {
            return
        }
        val call = MatrixRtcIncomingCallIntents.callFrom(intent) ?: return
        val pendingResult = goAsync()
        val manager = (context.applicationContext as? ZynaApplication)
            ?.appContainer
            ?.incomingCallManager
        if (manager == null) {
            Log.w(TAG, "Incoming call decline ignored: app container is missing")
            pendingResult.finish()
            return
        }
        manager.declineIncomingCallAsync(call) {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "MatrixRtcCallAction"
    }
}
