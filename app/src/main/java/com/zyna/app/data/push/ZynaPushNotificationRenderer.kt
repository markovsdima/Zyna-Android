package com.zyna.app.data.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zyna.app.MainActivity
import com.zyna.app.R
import com.zyna.app.ZynaForegroundState
import kotlin.math.absoluteValue

class ZynaPushNotificationRenderer(private val context: Context) {
    fun showFallbackNotification(payload: MatrixPushPayload): Boolean {
        if (ZynaForegroundState.isForeground) {
            Log.d(TAG, "Notification skipped: app is in foreground")
            return false
        }
        if (!canPostNotifications()) {
            Log.d(TAG, "Notification skipped: POST_NOTIFICATIONS is not granted")
            return false
        }

        ZynaNotificationChannels.ensureCreated(context)
        val body = payload.unreadCount
            ?.let { count -> context.getString(R.string.push_notification_unread_body, count) }
            ?: context.getString(R.string.push_notification_body)
        val notification = NotificationCompat.Builder(
            context,
            ZynaNotificationChannels.MESSAGES_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_tab_chats_24)
            .setContentTitle(context.getString(R.string.push_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(payload.unreadCount ?: 0)
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

    companion object {
        private const val TAG = "ZynaPushNotify"
        private const val REQUEST_CODE_OPEN_APP = 1001
    }
}
