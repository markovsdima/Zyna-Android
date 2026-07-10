package com.zyna.app.data.calls.matrixrtc

enum class MatrixRtcCallHistoryOutcome {
    STARTED,
    ANSWERED,
    DECLINED,
    DECLINED_BY_ME,
    CANCELLED_BY_ME,
    MISSED,
    UNANSWERED
}

data class MatrixRtcCallHistoryItem(
    val eventId: String,
    val roomId: String,
    val roomName: String,
    val roomAvatarUrl: String?,
    val senderId: String,
    val senderDisplayName: String?,
    val isOutgoing: Boolean,
    val timestampMillis: Long,
    val notificationType: MatrixRtcCallNotificationType,
    val callIntent: String?,
    val outcome: MatrixRtcCallHistoryOutcome,
    val expiresAtMillis: Long?
) {
    val title: String
        get() = roomName.takeIf { it.isNotBlank() }
            ?: senderDisplayName?.takeIf { it.isNotBlank() }
            ?: senderId

    val isNegativeOutcome: Boolean
        get() = when (outcome) {
            MatrixRtcCallHistoryOutcome.DECLINED,
            MatrixRtcCallHistoryOutcome.DECLINED_BY_ME,
            MatrixRtcCallHistoryOutcome.CANCELLED_BY_ME,
            MatrixRtcCallHistoryOutcome.MISSED,
            MatrixRtcCallHistoryOutcome.UNANSWERED -> true
            MatrixRtcCallHistoryOutcome.STARTED,
            MatrixRtcCallHistoryOutcome.ANSWERED -> false
        }
}

data class MatrixRtcCallTimelineNotification(
    val eventId: String,
    val roomId: String,
    val parentEventId: String?,
    val senderId: String,
    val senderDisplayName: String?,
    val isOutgoing: Boolean,
    val timestampMillis: Long,
    val notificationType: MatrixRtcCallNotificationType,
    val callIntent: String?,
    val expiresAtMillis: Long?,
    val declinedBy: List<String>
)

data class MatrixRtcCallTimelineMembership(
    val eventId: String,
    val roomId: String,
    val eventType: String,
    val stateKey: String?,
    val senderId: String,
    val timestampMillis: Long,
    val isLeave: Boolean,
    val memberUserId: String?,
    val deviceId: String?,
    val memberId: String?,
    val callIntent: String?,
    val expiresAtMillis: Long?
)
