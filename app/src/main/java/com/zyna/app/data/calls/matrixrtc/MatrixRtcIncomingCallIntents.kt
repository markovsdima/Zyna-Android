package com.zyna.app.data.calls.matrixrtc

import android.content.Intent

object MatrixRtcIncomingCallIntents {
    const val ACTION_ANSWER = "com.zyna.app.action.ANSWER_MATRIX_RTC_CALL"
    const val ACTION_DECLINE = "com.zyna.app.action.DECLINE_MATRIX_RTC_CALL"
    const val ACTION_SHOW_FULLSCREEN = "com.zyna.app.action.SHOW_MATRIX_RTC_CALL"

    private const val EXTRA_EVENT_ID = "com.zyna.app.extra.MATRIX_RTC_EVENT_ID"
    private const val EXTRA_ROOM_ID = "com.zyna.app.extra.MATRIX_RTC_ROOM_ID"
    private const val EXTRA_SENDER_ID = "com.zyna.app.extra.MATRIX_RTC_SENDER_ID"
    private const val EXTRA_SENDER_NAME = "com.zyna.app.extra.MATRIX_RTC_SENDER_NAME"
    private const val EXTRA_ROOM_NAME = "com.zyna.app.extra.MATRIX_RTC_ROOM_NAME"
    private const val EXTRA_IS_AUDIO_CALL = "com.zyna.app.extra.MATRIX_RTC_IS_AUDIO_CALL"
    private const val EXTRA_EXPIRES_AT = "com.zyna.app.extra.MATRIX_RTC_EXPIRES_AT"

    fun putCall(intent: Intent, call: MatrixRtcIncomingCall): Intent {
        return intent
            .putExtra(EXTRA_EVENT_ID, call.eventId)
            .putExtra(EXTRA_ROOM_ID, call.roomId)
            .putExtra(EXTRA_SENDER_ID, call.senderId)
            .putExtra(EXTRA_SENDER_NAME, call.senderName)
            .putExtra(EXTRA_ROOM_NAME, call.roomName)
            .putExtra(EXTRA_IS_AUDIO_CALL, call.isAudioCall)
            .putExtra(EXTRA_EXPIRES_AT, call.expiresAtMillis)
    }

    fun callFrom(intent: Intent): MatrixRtcIncomingCall? {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val senderId = intent.getStringExtra(EXTRA_SENDER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: senderId
        val expiresAtMillis = intent.getLongExtra(EXTRA_EXPIRES_AT, 0L)
            .takeIf { it > 0L }
            ?: return null

        return MatrixRtcIncomingCall(
            eventId = eventId,
            roomId = roomId,
            senderId = senderId,
            senderName = senderName,
            roomName = intent.getStringExtra(EXTRA_ROOM_NAME)
                ?.takeIf { it.isNotBlank() },
            isAudioCall = intent.getBooleanExtra(EXTRA_IS_AUDIO_CALL, true),
            expiresAtMillis = expiresAtMillis
        )
    }
}
