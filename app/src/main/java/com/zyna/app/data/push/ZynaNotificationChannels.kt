package com.zyna.app.data.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import com.zyna.app.R

object ZynaNotificationChannels {
    const val MESSAGES_CHANNEL_ID = "messages"
    const val INCOMING_CALLS_CHANNEL_ID = "incoming_calls"
    const val ONGOING_CALLS_CHANNEL_ID = "ongoing_calls"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            MESSAGES_CHANNEL_ID,
            context.getString(R.string.notification_channel_messages_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
        }
        manager.createNotificationChannel(channel)

        val callChannel = NotificationChannel(
            INCOMING_CALLS_CHANNEL_ID,
            context.getString(R.string.notification_channel_incoming_calls_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(
                R.string.notification_channel_incoming_calls_description
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(
                Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
        }
        manager.createNotificationChannel(callChannel)

        val ongoingCallChannel = NotificationChannel(
            ONGOING_CALLS_CHANNEL_ID,
            context.getString(R.string.notification_channel_ongoing_calls_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(
                R.string.notification_channel_ongoing_calls_description
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(ongoingCallChannel)
    }
}
