package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixRoomCallInfo

data class ChatCallBannerState(
    val title: String,
    val actionLabel: String,
    val isLocalCall: Boolean,
    val remoteMembershipCount: Int = 0
)

internal data class ChatMatrixRtcRingOverride(
    val eventId: String,
    val senderId: String,
    val expiresAtMillis: Long,
    val hasObservedActiveCall: Boolean
)

internal object ChatCallBannerPolicy {
    fun mergeMembershipFallback(
        roomInfo: MatrixRoomCallInfo,
        fallback: MatrixRoomCallInfo?
    ): MatrixRoomCallInfo {
        if (
            roomInfo.hasRoomCall ||
            fallback == null ||
            !fallback.hasRoomCall ||
            !fallback.isAudioCall
        ) {
            return roomInfo
        }
        return roomInfo.copy(
            hasRoomCall = true,
            activeParticipantUserIds = fallback.activeParticipantUserIds,
            isAudioCall = true
        )
    }

    fun applyRingOverride(
        observed: MatrixRoomCallInfo,
        override: ChatMatrixRtcRingOverride?,
        nowMillis: Long
    ): MatrixRoomCallInfo {
        val currentOverride = override ?: return observed
        if (
            currentOverride.expiresAtMillis <= nowMillis ||
            observed.hasRoomCall
        ) {
            return observed
        }

        val participantUserIds = observed.activeParticipantUserIds
            .takeIf { it.isNotEmpty() }
            ?: listOf(currentOverride.senderId)
        return observed.copy(
            hasRoomCall = true,
            activeParticipantUserIds = participantUserIds,
            isAudioCall = true
        )
    }

    fun ringOverrideForNotification(
        notification: MatrixIncomingRtcCallNotification,
        roomId: String,
        hasObservedActiveCall: Boolean,
        nowMillis: Long
    ): ChatMatrixRtcRingOverride? {
        if (
            notification.roomId != roomId ||
            !notification.isAudioCall ||
            notification.expiresAtMillis <= nowMillis
        ) {
            return null
        }
        return ChatMatrixRtcRingOverride(
            eventId = notification.eventId,
            senderId = notification.senderId,
            expiresAtMillis = notification.expiresAtMillis,
            hasObservedActiveCall = hasObservedActiveCall
        )
    }

    fun projectBanner(
        roomId: String,
        localCallRoomId: String?,
        callInfo: MatrixRoomCallInfo
    ): ChatCallBannerState? {
        return when {
            localCallRoomId == roomId -> ChatCallBannerState(
                title = "Call in progress",
                actionLabel = "Return",
                isLocalCall = true,
                remoteMembershipCount = callInfo.activeParticipantCount
            )
            callInfo.hasRoomCall && callInfo.isAudioCall -> ChatCallBannerState(
                title = "Call in progress",
                actionLabel = "Join",
                isLocalCall = false,
                remoteMembershipCount = callInfo.activeParticipantCount
            )
            else -> null
        }
    }
}
