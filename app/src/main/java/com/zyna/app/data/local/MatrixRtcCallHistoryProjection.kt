package com.zyna.app.data.local

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import org.json.JSONArray

internal data class MatrixRtcCallProjection(
    val isDirect: Boolean,
    val hasOwnJoin: Boolean,
    val hasRemoteJoin: Boolean,
    val hasOwnLeave: Boolean,
    val hasRemoteLeave: Boolean,
    val lastMembershipEventTimestampMillis: Long?,
    val lastOwnLeaveTimestampMillis: Long?,
    val lastRemoteLeaveTimestampMillis: Long?,
    val outcome: MatrixRtcCallHistoryOutcome
)

internal object MatrixRtcCallHistoryProjection {
    private const val PRE_NOTIFICATION_MATCH_WINDOW_MILLIS = 5_000L
    private const val POST_EXPIRY_MATCH_WINDOW_MILLIS = 10_000L
    private const val DEFAULT_CALL_MATCH_WINDOW_MILLIS = 4L * 60L * 60L * 1000L

    fun membershipLowerBound(callTimestampMillis: Long): Long {
        return callTimestampMillis - PRE_NOTIFICATION_MATCH_WINDOW_MILLIS
    }

    fun membershipUpperBound(call: MatrixRtcCallEntity): Long {
        return (call.expiresAtMillis ?: (call.timestampMillis + DEFAULT_CALL_MATCH_WINDOW_MILLIS)) +
            POST_EXPIRY_MATCH_WINDOW_MILLIS
    }

    fun affectedCallLowerBound(membershipTimestampMillis: Long): Long {
        return membershipTimestampMillis - DEFAULT_CALL_MATCH_WINDOW_MILLIS -
            POST_EXPIRY_MATCH_WINDOW_MILLIS
    }

    fun affectedCallUpperBound(membershipTimestampMillis: Long): Long {
        return membershipTimestampMillis + PRE_NOTIFICATION_MATCH_WINDOW_MILLIS
    }

    fun project(
        call: MatrixRtcCallEntity,
        isDirect: Boolean,
        currentUserId: String,
        memberships: List<MatrixRtcCallMembershipEntity>,
        nowMillis: Long
    ): MatrixRtcCallProjection {
        val evidence = membershipEvidence(
            call = call,
            currentUserId = currentUserId,
            memberships = memberships
        )
        return MatrixRtcCallProjection(
            isDirect = isDirect,
            hasOwnJoin = evidence.hasOwnJoin,
            hasRemoteJoin = evidence.hasRemoteJoin,
            hasOwnLeave = evidence.hasOwnLeave,
            hasRemoteLeave = evidence.hasRemoteLeave,
            lastMembershipEventTimestampMillis = evidence.lastMembershipEventTimestampMillis,
            lastOwnLeaveTimestampMillis = evidence.lastOwnLeaveTimestampMillis,
            lastRemoteLeaveTimestampMillis = evidence.lastRemoteLeaveTimestampMillis,
            outcome = projectedOutcome(
                isDirect = isDirect,
                isOutgoing = call.isOutgoing,
                notificationType = call.notificationType,
                expiresAtMillis = call.expiresAtMillis,
                declinedBy = decodeDeclinedBy(call.declinedByJson),
                hasOwnJoin = evidence.hasOwnJoin,
                hasRemoteJoin = evidence.hasRemoteJoin,
                hasOwnLeave = evidence.hasOwnLeave,
                hasRemoteLeave = evidence.hasRemoteLeave,
                currentUserId = currentUserId,
                nowMillis = nowMillis
            )
        )
    }

    fun encodeDeclinedBy(userIds: List<String>): String {
        return JSONArray().also { array ->
            userIds.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .forEach { array.put(it) }
        }.toString()
    }

    fun decodeDeclinedBy(json: String): List<String> {
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun membershipEvidence(
        call: MatrixRtcCallEntity,
        currentUserId: String,
        memberships: List<MatrixRtcCallMembershipEntity>
    ): MembershipEvidence {
        val matchingMemberships = matchingMemberships(call, memberships)
        val matchingJoinStateKeys = matchingMemberships
            .asSequence()
            .filterNot { it.isLeave }
            .mapNotNull { it.stateKey }
            .toSet()

        return matchingMemberships.fold(MembershipEvidence()) { evidence, membership ->
            if (membership.isLeave) {
                val stateKey = membership.stateKey
                if (stateKey == null || stateKey !in matchingJoinStateKeys) {
                    return@fold evidence
                }
            }

            val nextEvidence = evidence.copy(
                lastMembershipEventTimestampMillis = maxOfNullable(
                    evidence.lastMembershipEventTimestampMillis,
                    membership.timestampMillis
                )
            )
            if (membership.senderId == currentUserId) {
                if (membership.isLeave) {
                    nextEvidence.copy(
                        hasOwnLeave = true,
                        lastOwnLeaveTimestampMillis = maxOfNullable(
                            nextEvidence.lastOwnLeaveTimestampMillis,
                            membership.timestampMillis
                        )
                    )
                } else {
                    nextEvidence.copy(hasOwnJoin = true)
                }
            } else {
                if (membership.isLeave) {
                    nextEvidence.copy(
                        hasRemoteLeave = true,
                        lastRemoteLeaveTimestampMillis = maxOfNullable(
                            nextEvidence.lastRemoteLeaveTimestampMillis,
                            membership.timestampMillis
                        )
                    )
                } else {
                    nextEvidence.copy(hasRemoteJoin = true)
                }
            }
        }
    }

    private fun matchingMemberships(
        call: MatrixRtcCallEntity,
        memberships: List<MatrixRtcCallMembershipEntity>
    ): List<MatrixRtcCallMembershipEntity> {
        val lowerBound = membershipLowerBound(call.timestampMillis)
        val upperBound = membershipUpperBound(call)
        return memberships.filter { membership ->
            membership.roomId == call.roomId &&
                membership.timestampMillis >= lowerBound &&
                membership.timestampMillis <= upperBound &&
                (
                    membership.eventId == call.parentEventId ||
                        membership.callIntent == null ||
                        call.callIntent == null ||
                        membership.callIntent == call.callIntent
                    )
        }
    }

    private fun projectedOutcome(
        isDirect: Boolean,
        isOutgoing: Boolean,
        notificationType: String,
        expiresAtMillis: Long?,
        declinedBy: List<String>,
        hasOwnJoin: Boolean,
        hasRemoteJoin: Boolean,
        hasOwnLeave: Boolean,
        hasRemoteLeave: Boolean,
        currentUserId: String,
        nowMillis: Long
    ): MatrixRtcCallHistoryOutcome {
        if (currentUserId.isBlank()) {
            return MatrixRtcCallHistoryOutcome.STARTED
        }

        if (isDirect) {
            when {
                currentUserId in declinedBy -> return MatrixRtcCallHistoryOutcome.DECLINED_BY_ME
                declinedBy.isNotEmpty() -> return MatrixRtcCallHistoryOutcome.DECLINED
                isOutgoing && hasRemoteJoin -> return MatrixRtcCallHistoryOutcome.ANSWERED
                !isOutgoing && hasOwnJoin -> return MatrixRtcCallHistoryOutcome.ANSWERED
                notificationType == MatrixRtcCallNotificationType.RING.name -> {
                    if (isOutgoing && hasOwnLeave && !hasRemoteJoin) {
                        return MatrixRtcCallHistoryOutcome.CANCELLED_BY_ME
                    }
                    if (!isOutgoing && hasRemoteLeave && !hasOwnJoin) {
                        return MatrixRtcCallHistoryOutcome.MISSED
                    }
                }
            }
        }

        if (
            !isDirect ||
            notificationType != MatrixRtcCallNotificationType.RING.name ||
            expiresAtMillis == null ||
            expiresAtMillis > nowMillis
        ) {
            return MatrixRtcCallHistoryOutcome.STARTED
        }

        return if (isOutgoing) {
            if (hasRemoteJoin) {
                MatrixRtcCallHistoryOutcome.STARTED
            } else {
                MatrixRtcCallHistoryOutcome.UNANSWERED
            }
        } else {
            if (hasOwnJoin) {
                MatrixRtcCallHistoryOutcome.STARTED
            } else {
                MatrixRtcCallHistoryOutcome.MISSED
            }
        }
    }

    private fun maxOfNullable(current: Long?, next: Long): Long {
        return current?.let { maxOf(it, next) } ?: next
    }

    private data class MembershipEvidence(
        val hasOwnJoin: Boolean = false,
        val hasRemoteJoin: Boolean = false,
        val hasOwnLeave: Boolean = false,
        val hasRemoteLeave: Boolean = false,
        val lastMembershipEventTimestampMillis: Long? = null,
        val lastOwnLeaveTimestampMillis: Long? = null,
        val lastRemoteLeaveTimestampMillis: Long? = null
    )
}
