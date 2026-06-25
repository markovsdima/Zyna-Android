package com.zyna.app.data.push

data class ZynaPushNotificationContent(
    val title: String,
    val body: String,
    val isNoisy: Boolean,
    val unreadCount: Int?
)

sealed interface ZynaPushNotificationResolution {
    data class Resolved(
        val content: ZynaPushNotificationContent
    ) : ZynaPushNotificationResolution

    data object Suppressed : ZynaPushNotificationResolution
    data object Unavailable : ZynaPushNotificationResolution
}
