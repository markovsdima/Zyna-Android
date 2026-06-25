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

class ZynaPushNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val payload = payloadFromInputData() ?: return Result.failure()
        val didShow = ZynaPushNotificationRenderer(applicationContext)
            .showFallbackNotification(payload)
        Log.d(
            TAG,
            "Push notification worker finished event_id=${payload.eventId} " +
                "room_id=${payload.roomId} displayed=$didShow"
        )
        return Result.success()
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
