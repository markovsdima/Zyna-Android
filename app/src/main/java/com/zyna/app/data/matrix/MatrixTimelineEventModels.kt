package com.zyna.app.data.matrix

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineNotification
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.OtherState
import uniffi.matrix_sdk_ui.RoomPinnedEventsChange

/**
 * Locale-independent details for a Matrix state event rendered in the chat timeline.
 *
 * Keeping the SDK meaning instead of a preformatted label lets presentation react to locale
 * changes and makes the event safe to persist and restore.
 */
sealed interface MatrixSystemEventDetails {
    data class Membership(
        val userId: String,
        val userDisplayName: String?,
        val change: MatrixMembershipEventChange,
        val reason: String?
    ) : MatrixSystemEventDetails

    data class ProfileChange(
        val displayName: String?,
        val previousDisplayName: String?
    ) : MatrixSystemEventDetails

    data class RoomState(
        val stateKey: String,
        val change: MatrixRoomStateChange
    ) : MatrixSystemEventDetails
}

enum class MatrixMembershipEventChange {
    JOINED,
    LEFT,
    BANNED,
    UNBANNED,
    KICKED,
    INVITED,
    KICKED_AND_BANNED,
    INVITATION_ACCEPTED,
    INVITATION_REJECTED,
    INVITATION_REVOKED,
    KNOCKED,
    KNOCK_ACCEPTED,
    KNOCK_RETRACTED,
    KNOCK_DENIED
}

sealed interface MatrixRoomStateChange {
    data class Avatar(val url: String?) : MatrixRoomStateChange

    data class Created(val federate: Boolean) : MatrixRoomStateChange

    data object EncryptionEnabled : MatrixRoomStateChange

    data class Name(val name: String?) : MatrixRoomStateChange

    data class PinnedEvents(val change: MatrixPinnedEventsChange) : MatrixRoomStateChange

    data class ThirdPartyInvite(val displayName: String) : MatrixRoomStateChange

    data class Topic(val topic: String?) : MatrixRoomStateChange
}

enum class MatrixPinnedEventsChange {
    ADDED,
    REMOVED,
    CHANGED
}

internal fun matrixMembershipEventDetailsOrNull(
    userId: String,
    userDisplayName: String?,
    change: MembershipChange?,
    reason: String?
): MatrixSystemEventDetails.Membership? {
    val mappedChange = when (change) {
        MembershipChange.JOINED -> MatrixMembershipEventChange.JOINED
        MembershipChange.LEFT -> MatrixMembershipEventChange.LEFT
        MembershipChange.BANNED -> MatrixMembershipEventChange.BANNED
        MembershipChange.UNBANNED -> MatrixMembershipEventChange.UNBANNED
        MembershipChange.KICKED -> MatrixMembershipEventChange.KICKED
        MembershipChange.INVITED -> MatrixMembershipEventChange.INVITED
        MembershipChange.KICKED_AND_BANNED -> MatrixMembershipEventChange.KICKED_AND_BANNED
        MembershipChange.INVITATION_ACCEPTED -> MatrixMembershipEventChange.INVITATION_ACCEPTED
        MembershipChange.INVITATION_REJECTED -> MatrixMembershipEventChange.INVITATION_REJECTED
        MembershipChange.INVITATION_REVOKED -> MatrixMembershipEventChange.INVITATION_REVOKED
        MembershipChange.KNOCKED -> MatrixMembershipEventChange.KNOCKED
        MembershipChange.KNOCK_ACCEPTED -> MatrixMembershipEventChange.KNOCK_ACCEPTED
        MembershipChange.KNOCK_RETRACTED -> MatrixMembershipEventChange.KNOCK_RETRACTED
        MembershipChange.KNOCK_DENIED -> MatrixMembershipEventChange.KNOCK_DENIED
        MembershipChange.NONE,
        MembershipChange.ERROR,
        MembershipChange.NOT_IMPLEMENTED,
        null -> return null
    }
    return MatrixSystemEventDetails.Membership(
        userId = userId,
        userDisplayName = userDisplayName?.takeIf { it.isNotBlank() },
        change = mappedChange,
        reason = reason?.trim()?.takeIf { it.isNotEmpty() }
    )
}

internal fun matrixProfileChangeEventDetailsOrNull(
    displayName: String?,
    previousDisplayName: String?
): MatrixSystemEventDetails.ProfileChange? {
    if (displayName == previousDisplayName) {
        return null
    }
    return MatrixSystemEventDetails.ProfileChange(
        displayName = displayName,
        previousDisplayName = previousDisplayName
    )
}

internal fun matrixRoomStateEventDetailsOrNull(
    stateKey: String,
    state: OtherState
): MatrixSystemEventDetails.RoomState? {
    val change = when (state) {
        is OtherState.RoomAvatar -> MatrixRoomStateChange.Avatar(state.url)
        is OtherState.RoomCreate -> MatrixRoomStateChange.Created(state.federate)
        OtherState.RoomEncryption -> MatrixRoomStateChange.EncryptionEnabled
        is OtherState.RoomName -> MatrixRoomStateChange.Name(state.name)
        is OtherState.RoomPinnedEvents -> MatrixRoomStateChange.PinnedEvents(
            change = when (state.change) {
                RoomPinnedEventsChange.ADDED -> MatrixPinnedEventsChange.ADDED
                RoomPinnedEventsChange.REMOVED -> MatrixPinnedEventsChange.REMOVED
                RoomPinnedEventsChange.CHANGED -> MatrixPinnedEventsChange.CHANGED
            }
        )
        is OtherState.RoomThirdPartyInvite -> MatrixRoomStateChange.ThirdPartyInvite(
            displayName = state.displayName?.takeIf { it.isNotEmpty() } ?: return null
        )
        is OtherState.RoomTopic -> MatrixRoomStateChange.Topic(state.topic)
        else -> return null
    }
    return MatrixSystemEventDetails.RoomState(stateKey = stateKey, change = change)
}

/** Semantic MatrixRTC notification data stored alongside a timeline row. */
data class MatrixRtcCallEventDetails(
    val parentEventId: String?,
    val callIntent: String?,
    val notificationType: MatrixRtcCallNotificationType,
    val expiresAtMillis: Long?,
    val declinedBy: List<String>,
    /** Null until the call-history projection has enough information to classify the call. */
    val outcome: MatrixRtcCallHistoryOutcome?
)

/**
 * Builds the canonical chat row for an RTC notification sidecar.
 *
 * The cache layer calls this while persisting the sidecar and the row in one transaction. Timeline
 * mapping deliberately does not emit this row separately, avoiding two writers racing to persist
 * different call outcomes.
 */
internal fun MatrixRtcCallTimelineNotification.toMatrixChatMessage(
    outcome: MatrixRtcCallHistoryOutcome? = null
): MatrixChatMessage {
    return MatrixChatMessage(
        id = eventId,
        eventId = eventId,
        sender = senderId,
        senderDisplayName = senderDisplayName,
        body = "",
        timestampMillis = timestampMillis,
        isOwn = isOutgoing,
        contentType = MatrixMessageContentType.MATRIX_RTC_CALL,
        matrixRtcCallDetails = MatrixRtcCallEventDetails(
            parentEventId = parentEventId,
            callIntent = callIntent,
            notificationType = notificationType,
            expiresAtMillis = expiresAtMillis,
            declinedBy = declinedBy
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList(),
            outcome = outcome
        )
    )
}
