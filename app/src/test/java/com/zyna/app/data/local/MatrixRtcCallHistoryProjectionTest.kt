package com.zyna.app.data.local

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRtcCallHistoryProjectionTest {
    @Test
    fun project_outgoingDirectCallWithRemoteJoin_isAnswered() {
        val call = call(isOutgoing = true)
        val projection = MatrixRtcCallHistoryProjection.project(
            call = call,
            isDirect = true,
            currentUserId = OWN_USER_ID,
            memberships = listOf(membership(senderId = REMOTE_USER_ID)),
            nowMillis = call.timestampMillis + 1_000L
        )

        assertEquals(MatrixRtcCallHistoryOutcome.ANSWERED, projection.outcome)
        assertEquals(true, projection.hasRemoteJoin)
    }

    @Test
    fun project_outgoingDirectRingWithOwnLeaveBeforeRemoteJoin_isCancelledByMe() {
        val call = call(isOutgoing = true)
        val projection = MatrixRtcCallHistoryProjection.project(
            call = call,
            isDirect = true,
            currentUserId = OWN_USER_ID,
            memberships = listOf(
                membership(senderId = OWN_USER_ID, stateKey = "own-device"),
                membership(senderId = OWN_USER_ID, stateKey = "own-device", isLeave = true)
            ),
            nowMillis = call.timestampMillis + 5_000L
        )

        assertEquals(MatrixRtcCallHistoryOutcome.CANCELLED_BY_ME, projection.outcome)
        assertEquals(true, projection.hasOwnLeave)
    }

    @Test
    fun project_incomingExpiredDirectRingWithoutOwnJoin_isMissed() {
        val call = call(isOutgoing = false, expiresAtMillis = 1_030_000L)
        val projection = MatrixRtcCallHistoryProjection.project(
            call = call,
            isDirect = true,
            currentUserId = OWN_USER_ID,
            memberships = emptyList(),
            nowMillis = 1_031_000L
        )

        assertEquals(MatrixRtcCallHistoryOutcome.MISSED, projection.outcome)
    }

    @Test
    fun project_incomingDeclinedByCurrentUser_isDeclinedByMe() {
        val call = call(
            isOutgoing = false,
            declinedByJson = MatrixRtcCallHistoryProjection.encodeDeclinedBy(listOf(OWN_USER_ID))
        )
        val projection = MatrixRtcCallHistoryProjection.project(
            call = call,
            isDirect = true,
            currentUserId = OWN_USER_ID,
            memberships = emptyList(),
            nowMillis = call.timestampMillis + 1_000L
        )

        assertEquals(MatrixRtcCallHistoryOutcome.DECLINED_BY_ME, projection.outcome)
    }

    private fun call(
        isOutgoing: Boolean,
        expiresAtMillis: Long? = 1_030_000L,
        declinedByJson: String = "[]"
    ): MatrixRtcCallEntity {
        return MatrixRtcCallEntity(
            userId = OWN_USER_ID,
            eventId = "\$call",
            roomId = ROOM_ID,
            parentEventId = null,
            senderId = if (isOutgoing) OWN_USER_ID else REMOTE_USER_ID,
            senderDisplayName = null,
            isOutgoing = isOutgoing,
            timestampMillis = 1_000_000L,
            notificationType = MatrixRtcCallNotificationType.RING.name,
            callIntent = "audio",
            expiresAtMillis = expiresAtMillis,
            declinedByJson = declinedByJson,
            isDirect = true,
            hasOwnJoin = false,
            hasRemoteJoin = false,
            hasOwnLeave = false,
            hasRemoteLeave = false,
            lastMembershipEventTimestampMillis = null,
            lastOwnLeaveTimestampMillis = null,
            lastRemoteLeaveTimestampMillis = null,
            outcome = MatrixRtcCallHistoryOutcome.STARTED.name,
            updatedAtMillis = 1_000_000L
        )
    }

    private fun membership(
        senderId: String,
        stateKey: String = "remote-device",
        isLeave: Boolean = false
    ): MatrixRtcCallMembershipEntity {
        return MatrixRtcCallMembershipEntity(
            userId = OWN_USER_ID,
            eventId = "\$membership-$senderId-$isLeave",
            roomId = ROOM_ID,
            eventType = "org.matrix.msc3401.call.member",
            stateKey = stateKey,
            senderId = senderId,
            timestampMillis = 1_001_000L,
            isLeave = isLeave,
            memberUserId = senderId,
            deviceId = "DEVICE",
            memberId = stateKey,
            callIntent = "audio",
            expiresAtMillis = 1_030_000L,
            updatedAtMillis = 1_001_000L
        )
    }

    private companion object {
        const val OWN_USER_ID = "@me:example.org"
        const val REMOTE_USER_ID = "@alice:example.org"
        const val ROOM_ID = "!room:example.org"
    }
}
