package com.zyna.app.data.calls.matrixrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRtcContentTest {
    @Test
    fun computesRtcBackendIdentity() {
        val identity = MatrixRtcMembershipIdentity(
            userId = "@alice:example.com",
            deviceId = "DEVICE123",
            memberId = "memberABC"
        )

        assertEquals("J+T45tGruxc+HrUOqJJlyQSV33m728Cme4+vt8/SWrU", identity.rtcBackendIdentity)
    }

    @Test
    fun encodesCallEncryptionKeysContent() {
        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 7, key = "base64-key"),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = "member-id",
                claimedDeviceId = "DEVICEID"
            ),
            roomId = "!room:example.org",
            sentTimestamp = 123_456
        )

        val decoded = MatrixRtcCallEncryptionKeysContent.fromJson(content.jsonString())

        assertEquals(7, decoded.keys.index)
        assertEquals("base64-key", decoded.keys.key)
        assertEquals("member-id", decoded.member.id)
        assertEquals("DEVICEID", decoded.member.claimedDeviceId)
        assertEquals("!room:example.org", decoded.roomId)
        assertEquals(MatrixRtcCallEncryptionKeysContent.Session.MATRIX_CALL_ROOM, decoded.session)
        assertEquals(123_456L, decoded.sentTimestamp)
    }

    @Test
    fun encodesCallNotificationContent() {
        val content = MatrixRtcCallNotificationContent.create(
            parentEventId = "\$membership",
            notificationType = MatrixRtcCallNotificationType.RING,
            senderTimestamp = 123_456,
            callIntent = "audio"
        )

        val decoded = MatrixRtcCallNotificationContent.fromJson(content.jsonString())

        assertEquals(MatrixRtcCallNotificationContent.Mentions.ROOM_WIDE, decoded.mentions)
        assertEquals(MatrixRtcCallNotificationType.RING, decoded.notificationType)
        assertEquals(
            MatrixRtcCallNotificationContent.Relation.reference("\$membership"),
            decoded.relation
        )
        assertEquals(123_456L, decoded.senderTimestamp)
        assertEquals(30_000L, decoded.lifetime)
        assertEquals("audio", decoded.callIntent)
    }

    @Test
    fun encodesLegacyCallNotifyContent() {
        val content = MatrixRtcLegacyCallNotifyContent.create(
            slot = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            notificationType = MatrixRtcCallNotificationType.NOTIFICATION
        )

        val decoded = MatrixRtcLegacyCallNotifyContent.fromJson(content.jsonString())

        assertEquals("m.call", decoded.application)
        assertEquals(MatrixRtcCallNotificationContent.Mentions.ROOM_WIDE, decoded.mentions)
        assertEquals("notify", decoded.notifyType)
        assertEquals("ROOM", decoded.callId)
    }
}
