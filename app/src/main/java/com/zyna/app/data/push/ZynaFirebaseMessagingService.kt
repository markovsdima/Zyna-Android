package com.zyna.app.data.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zyna.app.ZynaApplication

class ZynaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        FirebaseInstallationIdStore(this).save(installationId)
        (applicationContext as? ZynaApplication)?.registerMatrixPusherForCurrentSession()
        Log.d(
            TAG,
            "FCM registered installation prefix=${installationId.take(INSTALLATION_ID_LOG_PREFIX_LENGTH)}"
        )
    }

    override fun onUnregistered(installationId: String) {
        FirebaseInstallationIdStore(this).clear()
        Log.d(
            TAG,
            "FCM unregistered installation prefix=${installationId.take(INSTALLATION_ID_LOG_PREFIX_LENGTH)}"
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val payload = MatrixPushPayload.fromData(data)
        Log.d(
            TAG,
            "FCM message received " +
                "from=${message.from.orEmpty()} " +
                "event_id=${data["event_id"].orEmpty()} " +
                "room_id=${data["room_id"].orEmpty()} " +
                "unread=${data["unread"].orEmpty()} " +
                "has_client_secret=${data.containsKey("cs")}"
        )
        if (payload == null) {
            Log.w(TAG, "FCM message ignored: missing event_id or room_id")
            return
        }
        ZynaPushNotificationWorker.enqueue(this, payload)
    }

    companion object {
        private const val TAG = "ZynaFCM"
        private const val INSTALLATION_ID_LOG_PREFIX_LENGTH = 12
    }
}
