package com.zyna.app.data.push

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zyna.app.ZynaApplication
import com.zyna.app.ZynaForegroundState

class ZynaPushNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val payload = payloadFromInputData() ?: return Result.failure()
        if (ZynaForegroundState.isForeground) {
            Log.d(TAG, "Push notification skipped: app is in foreground")
            return Result.success()
        }
        val renderer = ZynaPushNotificationRenderer(applicationContext)
        val resolution = resolveNotification(payload)
        val didShow = when (resolution) {
            is ZynaPushNotificationResolution.Resolved ->
                renderer.showNotification(payload, resolution.content)
            is ZynaPushNotificationResolution.IncomingCall ->
                (applicationContext as? ZynaApplication)
                    ?.appContainer
                    ?.incomingCallManager
                    ?.showIncomingCall(resolution.call)
                    ?: false
            ZynaPushNotificationResolution.Suppressed -> {
                Log.d(TAG, "Push notification suppressed by Matrix notification resolver")
                false
            }
            ZynaPushNotificationResolution.Unavailable ->
                renderer.showFallbackNotification(payload)
        }
        Log.d(
            TAG,
            "Push notification worker finished event_id=${payload.eventId} " +
                "room_id=${payload.roomId} " +
                "resolution=${resolution.logName()} displayed=$didShow"
        )
        return Result.success()
    }

    private suspend fun resolveNotification(
        payload: MatrixPushPayload
    ): ZynaPushNotificationResolution {
        val application = applicationContext as? ZynaApplication
            ?: return ZynaPushNotificationResolution.Unavailable
        return application.appContainer.matrixClientService.resolvePushNotification(
            roomId = payload.roomId,
            eventId = payload.eventId,
            unreadCount = payload.unreadCount
        )
    }

    private fun payloadFromInputData(): MatrixPushPayload? {
        val eventId = inputData.getString(KEY_EVENT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val roomId = inputData.getString(KEY_ROOM_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val unreadCount = inputData.getInt(KEY_UNREAD, 0)
            .takeIf { it > 0 }
        return MatrixPushPayload(
            eventId = eventId,
            roomId = roomId,
            unreadCount = unreadCount,
            clientSecret = inputData.getString(KEY_CLIENT_SECRET)
                ?.takeIf { it.isNotBlank() }
        )
    }

    companion object {
        private const val TAG = "ZynaPushWorker"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_UNREAD = "unread"
        private const val KEY_CLIENT_SECRET = "cs"

        private fun ZynaPushNotificationResolution.logName(): String {
            return when (this) {
                is ZynaPushNotificationResolution.Resolved -> "resolved"
                is ZynaPushNotificationResolution.IncomingCall -> "incoming_call"
                ZynaPushNotificationResolution.Suppressed -> "suppressed"
                ZynaPushNotificationResolution.Unavailable -> "unavailable"
            }
        }

        fun enqueue(context: Context, payload: MatrixPushPayload) {
            val request = OneTimeWorkRequestBuilder<ZynaPushNotificationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_EVENT_ID to payload.eventId,
                        KEY_ROOM_ID to payload.roomId,
                        KEY_UNREAD to (payload.unreadCount ?: 0),
                        KEY_CLIENT_SECRET to payload.clientSecret.orEmpty()
                    )
                )
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
            Log.d(
                TAG,
                "Push notification worker enqueued event_id=${payload.eventId} " +
                    "room_id=${payload.roomId}"
            )
        }
    }
}
