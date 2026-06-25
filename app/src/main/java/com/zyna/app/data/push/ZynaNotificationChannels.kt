package com.zyna.app.data.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.zyna.app.R

object ZynaNotificationChannels {
    const val MESSAGES_CHANNEL_ID = "messages"

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
    }
}
