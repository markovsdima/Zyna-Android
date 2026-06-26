package com.zyna.app.data.push

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.zyna.app.MainActivity
import com.zyna.app.R
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCall
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCallActionReceiver
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCallIntents
import com.zyna.app.ui.calls.MatrixRtcIncomingCallActivity
import kotlin.math.absoluteValue

class ZynaPushNotificationRenderer(private val context: Context) {
    fun showFallbackNotification(payload: MatrixPushPayload): Boolean {
        val body = payload.unreadCount
            ?.let { count -> context.getString(R.string.push_notification_unread_body, count) }
            ?: context.getString(R.string.push_notification_body)
        return showNotification(
            payload = payload,
            content = ZynaPushNotificationContent(
                title = context.getString(R.string.push_notification_title),
                body = body,
                isNoisy = true,
                unreadCount = payload.unreadCount
            )
        )
    }

    fun showNotification(
        payload: MatrixPushPayload,
        content: ZynaPushNotificationContent
    ): Boolean {
        if (!canPostNotifications()) {
            Log.d(TAG, "Notification skipped: POST_NOTIFICATIONS is not granted")
            return false
        }

        ZynaNotificationChannels.ensureCreated(context)
        val notification = NotificationCompat.Builder(
            context,
            ZynaNotificationChannels.MESSAGES_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_tab_chats_24)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(
                if (content.isNoisy) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                }
            )
            .setSilent(!content.isNoisy)
            .setNumber(content.unreadCount ?: 0)
            .build()

        return try {
            NotificationManagerCompat.from(context)
                .notify(notificationId(payload.eventId), notification)
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification skipped: permission rejected by system", error)
            false
        }
    }

    fun showIncomingCallNotification(call: MatrixRtcIncomingCall): Boolean {
        if (!canPostNotifications()) {
            Log.d(TAG, "Incoming call notification skipped: POST_NOTIFICATIONS is not granted")
            return false
        }

        ZynaNotificationChannels.ensureCreated(context)
        val caller = Person.Builder()
            .setName(call.senderName)
            .setImportant(true)
            .build()
        val answerIntent = PendingIntent.getActivity(
            context,
            requestCode(call.eventId, REQUEST_CODE_ANSWER_CALL),
            MatrixRtcIncomingCallIntents.putCall(
                Intent(context, MatrixRtcIncomingCallActivity::class.java).apply {
                    action = MatrixRtcIncomingCallIntents.ACTION_ANSWER
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                call
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declineIntent = PendingIntent.getBroadcast(
            context,
            requestCode(call.eventId, REQUEST_CODE_DECLINE_CALL),
            MatrixRtcIncomingCallIntents.putCall(
                Intent(context, MatrixRtcIncomingCallActionReceiver::class.java).apply {
                    action = MatrixRtcIncomingCallIntents.ACTION_DECLINE
                },
                call
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            requestCode(call.eventId, REQUEST_CODE_FULLSCREEN_CALL),
            MatrixRtcIncomingCallIntents.putCall(
                Intent(context, MatrixRtcIncomingCallActivity::class.java).apply {
                    action = MatrixRtcIncomingCallIntents.ACTION_SHOW_FULLSCREEN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                call
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = context.getString(R.string.incoming_call_audio_body)
        val notification = NotificationCompat.Builder(
            context,
            ZynaNotificationChannels.INCOMING_CALLS_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_tab_chats_24)
            .setContentTitle(context.getString(R.string.incoming_call_title, call.senderName))
            .setContentText(body)
            .setContentIntent(fullScreenIntent)
            .setDeleteIntent(declineIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                NotificationCompat.CallStyle
                    .forIncomingCall(caller, declineIntent, answerIntent)
                    .setIsVideo(!call.isAudioCall)
            )
            .addPerson(caller)
            .setAutoCancel(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(Settings.System.DEFAULT_RINGTONE_URI, AudioManager.STREAM_RING)
            .setTimeoutAfter((call.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L))
            .setFullScreenIntent(fullScreenIntent, true)
            .build()
            .apply {
                flags = flags or Notification.FLAG_INSISTENT
            }

        return try {
            NotificationManagerCompat.from(context)
                .notify(notificationId(call.eventId), notification)
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Incoming call notification skipped: permission rejected by system", error)
            false
        }
    }

    fun cancelIncomingCallNotification(call: MatrixRtcIncomingCall) {
        NotificationManagerCompat.from(context)
            .cancel(notificationId(call.eventId))
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationId(eventId: String): Int {
        return eventId.hashCode().takeUnless { it == Int.MIN_VALUE }
            ?.absoluteValue
            ?: 0
    }

    private fun requestCode(eventId: String, salt: Int): Int {
        return (notificationId(eventId) * 31 + salt)
            .takeUnless { it == Int.MIN_VALUE }
            ?.absoluteValue
            ?: salt
    }

    companion object {
        private const val TAG = "ZynaPushNotify"
        private const val REQUEST_CODE_OPEN_APP = 1001
        private const val REQUEST_CODE_ANSWER_CALL = 2001
        private const val REQUEST_CODE_DECLINE_CALL = 2002
        private const val REQUEST_CODE_FULLSCREEN_CALL = 2003
    }
}
