package com.zyna.app.data.push

import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCall

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

    data class IncomingCall(
        val call: MatrixRtcIncomingCall
    ) : ZynaPushNotificationResolution

    data object Suppressed : ZynaPushNotificationResolution
    data object Unavailable : ZynaPushNotificationResolution
}
