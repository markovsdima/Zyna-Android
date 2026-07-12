package com.zyna.app.ui.chat.render

import android.content.Context
import androidx.annotation.StringRes
import com.zyna.app.R
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMembershipEventChange
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixPinnedEventsChange
import com.zyna.app.data.matrix.MatrixRoomStateChange
import com.zyna.app.data.matrix.MatrixSystemEventDetails
import com.zyna.app.ui.time.AndroidTimeTextFormatter
import com.zyna.app.ui.time.TimeTextFormatter

/** Converts locale-independent Matrix timeline details into a pill render model. */
internal class MatrixTimelineEventPresenter(
    private val context: Context,
    private val timeTextFormatter: TimeTextFormatter = AndroidTimeTextFormatter(context)
) {
    fun present(
        message: MatrixChatMessage,
        currentUserId: String?
    ): SystemEventRenderModel {
        val callDetails = message.matrixRtcCallDetails
        if (message.contentType == MatrixMessageContentType.MATRIX_RTC_CALL || callDetails != null) {
            val declinedBy = callDetails?.declinedBy.orEmpty()
            val outcome = callDetails?.outcome ?: when {
                currentUserId != null && currentUserId in declinedBy ->
                    MatrixRtcCallHistoryOutcome.DECLINED_BY_ME
                declinedBy.isNotEmpty() -> MatrixRtcCallHistoryOutcome.DECLINED
                else -> MatrixRtcCallHistoryOutcome.STARTED
            }
            val outcomeText = context.getString(outcome.stringResource())
            val time = timeTextFormatter.format(message.timestampMillis)
            val text = context.getString(R.string.system_event_call_with_time, outcomeText, time)
            val isAudioCall = callDetails?.callIntent.isAudioCompatibleCallIntent()
            val isNegative = outcome.isNegative()
            return SystemEventRenderModel(
                text = text,
                accessibilityText = text,
                kind = SystemEventRenderKind.CALL_EVENT,
                leadingIcon = when {
                    isAudioCall && isNegative -> SystemEventLeadingIcon.PHONE_NEGATIVE
                    isAudioCall -> SystemEventLeadingIcon.PHONE
                    isNegative -> SystemEventLeadingIcon.VIDEO_NEGATIVE
                    else -> SystemEventLeadingIcon.VIDEO
                }
            )
        }

        val text = message.systemEventDetails
            ?.let { details -> systemEventText(message, details, currentUserId) }
            ?: context.getString(R.string.system_event_generic)
        return SystemEventRenderModel(
            text = text,
            accessibilityText = text,
            kind = SystemEventRenderKind.SYSTEM_EVENT
        )
    }

    private fun systemEventText(
        message: MatrixChatMessage,
        details: MatrixSystemEventDetails,
        currentUserId: String?
    ): String {
        return when (details) {
            is MatrixSystemEventDetails.Membership -> membershipText(
                message = message,
                details = details,
                currentUserId = currentUserId
            )
            is MatrixSystemEventDetails.ProfileChange -> profileChangeText(message, details)
            is MatrixSystemEventDetails.RoomState -> roomStateText(message, details.change)
        }
    }

    private fun membershipText(
        message: MatrixChatMessage,
        details: MatrixSystemEventDetails.Membership,
        currentUserId: String?
    ): String {
        val sender = message.sender.displayNameOrId(message.senderDisplayName)
        val member = details.userId.displayNameOrId(details.userDisplayName)
        val senderIsYou = message.isOwn
        val memberIsYou = currentUserId != null && details.userId == currentUserId
        val reason = details.reason?.trim()?.takeIf { it.isNotEmpty() }

        return when (details.change) {
            MatrixMembershipEventChange.JOINED -> string(
                if (memberIsYou) R.string.system_event_you_joined_room
                else R.string.system_event_member_joined_room,
                member
            )
            MatrixMembershipEventChange.LEFT -> string(
                if (memberIsYou) R.string.system_event_you_left_room
                else R.string.system_event_member_left_room,
                member
            )
            MatrixMembershipEventChange.BANNED,
            MatrixMembershipEventChange.KICKED_AND_BANNED -> actorTargetText(
                sender = sender,
                target = member,
                senderIsYou = senderIsYou,
                targetIsYou = memberIsYou,
                reason = reason,
                own = R.string.system_event_you_banned_member,
                ownWithReason = R.string.system_event_you_banned_member_reason,
                targetYou = R.string.system_event_sender_banned_you,
                targetYouWithReason = R.string.system_event_sender_banned_you_reason,
                other = R.string.system_event_sender_banned_member,
                otherWithReason = R.string.system_event_sender_banned_member_reason
            )
            MatrixMembershipEventChange.UNBANNED -> when {
                senderIsYou -> string(R.string.system_event_you_unbanned_member, member)
                memberIsYou -> string(R.string.system_event_sender_unbanned_you, sender)
                else -> string(R.string.system_event_sender_unbanned_member, sender, member)
            }
            MatrixMembershipEventChange.KICKED -> actorTargetText(
                sender = sender,
                target = member,
                senderIsYou = senderIsYou,
                targetIsYou = memberIsYou,
                reason = reason,
                own = R.string.system_event_you_removed_member,
                ownWithReason = R.string.system_event_you_removed_member_reason,
                targetYou = R.string.system_event_sender_removed_you,
                targetYouWithReason = R.string.system_event_sender_removed_you_reason,
                other = R.string.system_event_sender_removed_member,
                otherWithReason = R.string.system_event_sender_removed_member_reason
            )
            MatrixMembershipEventChange.INVITED -> when {
                senderIsYou -> string(R.string.system_event_you_invited_member, member)
                memberIsYou -> string(R.string.system_event_sender_invited_you, sender)
                else -> string(R.string.system_event_sender_invited_member, sender, member)
            }
            MatrixMembershipEventChange.INVITATION_ACCEPTED -> string(
                if (memberIsYou) R.string.system_event_you_accepted_invitation
                else R.string.system_event_member_accepted_invitation,
                member
            )
            MatrixMembershipEventChange.INVITATION_REJECTED -> string(
                if (memberIsYou) R.string.system_event_you_declined_invitation
                else R.string.system_event_member_declined_invitation,
                member
            )
            MatrixMembershipEventChange.INVITATION_REVOKED -> when {
                senderIsYou -> string(R.string.system_event_you_revoked_invitation, member)
                memberIsYou -> string(R.string.system_event_sender_revoked_your_invitation, sender)
                else -> string(R.string.system_event_sender_revoked_invitation, sender, member)
            }
            MatrixMembershipEventChange.KNOCKED -> string(
                if (memberIsYou) R.string.system_event_you_requested_to_join
                else R.string.system_event_member_requested_to_join,
                member
            )
            MatrixMembershipEventChange.KNOCK_ACCEPTED -> when {
                senderIsYou -> string(R.string.system_event_you_accepted_join_request, member)
                memberIsYou -> string(R.string.system_event_sender_accepted_your_join_request, sender)
                else -> string(R.string.system_event_sender_accepted_join_request, sender, member)
            }
            MatrixMembershipEventChange.KNOCK_RETRACTED -> string(
                if (memberIsYou) R.string.system_event_you_withdrew_join_request
                else R.string.system_event_member_withdrew_join_request,
                member
            )
            MatrixMembershipEventChange.KNOCK_DENIED -> when {
                senderIsYou -> string(R.string.system_event_you_denied_join_request, member)
                memberIsYou -> string(R.string.system_event_sender_denied_your_join_request, sender)
                else -> string(R.string.system_event_sender_denied_join_request, sender, member)
            }
        }
    }

    private fun profileChangeText(
        message: MatrixChatMessage,
        details: MatrixSystemEventDetails.ProfileChange
    ): String {
        val member = message.sender.displayNameOrId(message.senderDisplayName)
        return when {
            details.previousDisplayName != null && details.displayName != null && message.isOwn ->
                string(
                    R.string.system_event_you_changed_display_name,
                    details.previousDisplayName,
                    details.displayName
                )
            details.previousDisplayName != null && details.displayName != null -> string(
                R.string.system_event_member_changed_display_name,
                member,
                details.previousDisplayName,
                details.displayName
            )
            details.previousDisplayName == null && details.displayName != null && message.isOwn ->
                string(R.string.system_event_you_set_display_name, details.displayName)
            details.previousDisplayName == null && details.displayName != null -> string(
                R.string.system_event_member_set_display_name,
                member,
                details.displayName
            )
            details.previousDisplayName != null && message.isOwn -> string(
                R.string.system_event_you_removed_display_name,
                details.previousDisplayName
            )
            details.previousDisplayName != null -> string(
                R.string.system_event_member_removed_display_name,
                member,
                details.previousDisplayName
            )
            else -> context.getString(R.string.system_event_generic)
        }
    }

    private fun roomStateText(
        message: MatrixChatMessage,
        change: MatrixRoomStateChange
    ): String {
        val sender = message.sender.displayNameOrId(message.senderDisplayName)
        val own = message.isOwn
        return when (change) {
            is MatrixRoomStateChange.Avatar -> when {
                own && change.url == null -> context.getString(R.string.system_event_you_removed_room_avatar)
                own -> context.getString(R.string.system_event_you_changed_room_avatar)
                change.url == null -> string(R.string.system_event_sender_removed_room_avatar, sender)
                else -> string(R.string.system_event_sender_changed_room_avatar, sender)
            }
            is MatrixRoomStateChange.Created -> string(
                if (own) R.string.system_event_you_created_room
                else R.string.system_event_sender_created_room,
                sender
            )
            MatrixRoomStateChange.EncryptionEnabled ->
                context.getString(R.string.system_event_encryption_enabled)
            is MatrixRoomStateChange.Name -> when {
                own && !change.name.isNullOrEmpty() ->
                    string(R.string.system_event_you_changed_room_name, change.name)
                !change.name.isNullOrEmpty() ->
                    string(R.string.system_event_sender_changed_room_name, sender, change.name)
                own -> context.getString(R.string.system_event_you_removed_room_name)
                else -> string(R.string.system_event_sender_removed_room_name, sender)
            }
            is MatrixRoomStateChange.PinnedEvents -> when (change.change) {
                MatrixPinnedEventsChange.ADDED -> string(
                    if (own) R.string.system_event_you_pinned_messages
                    else R.string.system_event_sender_pinned_messages,
                    sender
                )
                MatrixPinnedEventsChange.REMOVED -> string(
                    if (own) R.string.system_event_you_unpinned_messages
                    else R.string.system_event_sender_unpinned_messages,
                    sender
                )
                MatrixPinnedEventsChange.CHANGED -> string(
                    if (own) R.string.system_event_you_updated_pinned_messages
                    else R.string.system_event_sender_updated_pinned_messages,
                    sender
                )
            }
            is MatrixRoomStateChange.ThirdPartyInvite -> if (own) {
                string(R.string.system_event_you_invited_member, change.displayName)
            } else {
                string(R.string.system_event_sender_invited_member, sender, change.displayName)
            }
            is MatrixRoomStateChange.Topic -> when {
                own && !change.topic.isNullOrBlank() ->
                    string(R.string.system_event_you_changed_room_topic, change.topic)
                !change.topic.isNullOrBlank() ->
                    string(R.string.system_event_sender_changed_room_topic, sender, change.topic)
                own -> context.getString(R.string.system_event_you_removed_room_topic)
                else -> string(R.string.system_event_sender_removed_room_topic, sender)
            }
        }
    }

    private fun actorTargetText(
        sender: String,
        target: String,
        senderIsYou: Boolean,
        targetIsYou: Boolean,
        reason: String?,
        @StringRes own: Int,
        @StringRes ownWithReason: Int,
        @StringRes targetYou: Int,
        @StringRes targetYouWithReason: Int,
        @StringRes other: Int,
        @StringRes otherWithReason: Int
    ): String {
        return when {
            senderIsYou && reason != null -> string(ownWithReason, target, reason)
            senderIsYou -> string(own, target)
            targetIsYou && reason != null -> string(targetYouWithReason, sender, reason)
            targetIsYou -> string(targetYou, sender)
            reason != null -> string(otherWithReason, sender, target, reason)
            else -> string(other, sender, target)
        }
    }

    private fun string(@StringRes resource: Int, vararg arguments: Any): String {
        return if (arguments.isEmpty()) {
            context.getString(resource)
        } else {
            context.getString(resource, *arguments)
        }
    }

    @StringRes
    private fun MatrixRtcCallHistoryOutcome.stringResource(): Int {
        return when (this) {
            MatrixRtcCallHistoryOutcome.STARTED -> R.string.calls_outcome_started
            MatrixRtcCallHistoryOutcome.ANSWERED -> R.string.calls_outcome_answered
            MatrixRtcCallHistoryOutcome.DECLINED -> R.string.calls_outcome_declined
            MatrixRtcCallHistoryOutcome.DECLINED_BY_ME -> R.string.calls_outcome_declined_by_me
            MatrixRtcCallHistoryOutcome.CANCELLED_BY_ME -> R.string.calls_outcome_cancelled_by_me
            MatrixRtcCallHistoryOutcome.MISSED -> R.string.calls_outcome_missed
            MatrixRtcCallHistoryOutcome.UNANSWERED -> R.string.calls_outcome_unanswered
        }
    }

    private fun String.displayNameOrId(displayName: String?): String {
        return displayName?.takeIf { it.isNotEmpty() } ?: this
    }

    private fun MatrixRtcCallHistoryOutcome.isNegative(): Boolean {
        return when (this) {
            MatrixRtcCallHistoryOutcome.DECLINED,
            MatrixRtcCallHistoryOutcome.DECLINED_BY_ME,
            MatrixRtcCallHistoryOutcome.CANCELLED_BY_ME,
            MatrixRtcCallHistoryOutcome.MISSED,
            MatrixRtcCallHistoryOutcome.UNANSWERED -> true
            MatrixRtcCallHistoryOutcome.STARTED,
            MatrixRtcCallHistoryOutcome.ANSWERED -> false
        }
    }
}

/** Legacy RTC notifications omit the intent and are audio-compatible throughout the call stack. */
internal fun String?.isAudioCompatibleCallIntent(): Boolean {
    return when (this?.trim()?.lowercase()) {
        null, "audio", "m.audio" -> true
        "video", "m.video" -> false
        else -> false
    }
}
