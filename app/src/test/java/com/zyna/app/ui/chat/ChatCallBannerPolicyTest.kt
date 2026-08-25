package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotificationKind
import com.zyna.app.data.matrix.MatrixRoomCallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCallBannerPolicyTest {
    @Test
    fun membershipFallback_appliesOnlyActiveAudioFallbackWhenRoomInactive() {
        val observed = callInfo(hasRoomCall = false, isAudioCall = false)
        val fallback = callInfo(
            hasRoomCall = true,
            participants = listOf("@alice:example.org"),
            isAudioCall = true
        )

        assertEquals(
            observed.copy(
                hasRoomCall = true,
                activeParticipantUserIds = fallback.activeParticipantUserIds,
                isAudioCall = true
            ),
            ChatCallBannerPolicy.mergeMembershipFallback(observed, fallback)
        )
        assertEquals(
            observed.copy(hasRoomCall = true),
            ChatCallBannerPolicy.mergeMembershipFallback(
                observed.copy(hasRoomCall = true),
                fallback
            )
        )
        assertEquals(
            observed,
            ChatCallBannerPolicy.mergeMembershipFallback(observed, null)
        )
        assertEquals(
            observed,
            ChatCallBannerPolicy.mergeMembershipFallback(
                observed,
                fallback.copy(hasRoomCall = false)
            )
        )
        assertEquals(
            observed,
            ChatCallBannerPolicy.mergeMembershipFallback(
                observed,
                fallback.copy(isAudioCall = false)
            )
        )
    }

    @Test
    fun ringOverride_appliesOnlyWhileLiveAndObservedRoomIsInactive() {
        val observed = callInfo(hasRoomCall = false, isAudioCall = false)
        val override = ChatMatrixRtcRingOverride(
            eventId = "ring-event",
            senderId = "@alice:example.org",
            expiresAtMillis = 2_000L,
            hasObservedActiveCall = false
        )

        assertEquals(
            observed,
            ChatCallBannerPolicy.applyRingOverride(observed, null, nowMillis = 1_000L)
        )
        assertEquals(
            observed.copy(
                hasRoomCall = true,
                activeParticipantUserIds = listOf("@alice:example.org"),
                isAudioCall = true
            ),
            ChatCallBannerPolicy.applyRingOverride(
                observed = observed,
                override = override,
                nowMillis = 1_000L
            )
        )
        assertEquals(
            observed,
            ChatCallBannerPolicy.applyRingOverride(
                observed,
                override,
                nowMillis = 2_000L
            )
        )

        val activeObserved = callInfo(
            hasRoomCall = true,
            participants = listOf("@bob:example.org"),
            isAudioCall = true
        )
        assertEquals(
            activeObserved,
            ChatCallBannerPolicy.applyRingOverride(
                activeObserved,
                override,
                nowMillis = 1_000L
            )
        )
        assertEquals(
            observed.copy(
                hasRoomCall = true,
                activeParticipantUserIds = listOf("@bob:example.org"),
                isAudioCall = true
            ),
            ChatCallBannerPolicy.applyRingOverride(
                observed.copy(activeParticipantUserIds = listOf("@bob:example.org")),
                override,
                nowMillis = 1_000L
            )
        )
    }

    @Test
    fun notificationOverride_acceptsLiveAudioAndRejectsInvalidTargets() {
        val notification = notification()

        assertEquals(
            ChatMatrixRtcRingOverride(
                eventId = notification.eventId,
                senderId = notification.senderId,
                expiresAtMillis = notification.expiresAtMillis,
                hasObservedActiveCall = true
            ),
            ChatCallBannerPolicy.ringOverrideForNotification(
                notification = notification,
                roomId = ROOM_ID,
                hasObservedActiveCall = true,
                nowMillis = 1_000L
            )
        )
        assertEquals(
            ChatMatrixRtcRingOverride(
                eventId = notification.eventId,
                senderId = notification.senderId,
                expiresAtMillis = notification.expiresAtMillis,
                hasObservedActiveCall = false
            ),
            ChatCallBannerPolicy.ringOverrideForNotification(
                notification = notification.copy(
                    kind = MatrixIncomingRtcCallNotificationKind.NOTIFICATION
                ),
                roomId = ROOM_ID,
                hasObservedActiveCall = false,
                nowMillis = 1_000L
            )
        )
        assertNull(
            ChatCallBannerPolicy.ringOverrideForNotification(
                notification = notification.copy(roomId = "!other:example.org"),
                roomId = ROOM_ID,
                hasObservedActiveCall = false,
                nowMillis = 1_000L
            )
        )
        assertNull(
            ChatCallBannerPolicy.ringOverrideForNotification(
                notification = notification.copy(isAudioCall = false),
                roomId = ROOM_ID,
                hasObservedActiveCall = false,
                nowMillis = 1_000L
            )
        )
        assertNull(
            ChatCallBannerPolicy.ringOverrideForNotification(
                notification = notification,
                roomId = ROOM_ID,
                hasObservedActiveCall = false,
                nowMillis = notification.expiresAtMillis
            )
        )
    }

    @Test
    fun bannerProjection_prefersLocalCallThenRemoteAudioCall() {
        val inactive = callInfo(
            hasRoomCall = false,
            participants = listOf("@alice:example.org", "@bob:example.org"),
            isAudioCall = false
        )
        assertEquals(
            ChatCallBannerState(
                title = "Call in progress",
                actionLabel = "Return",
                isLocalCall = true,
                remoteMembershipCount = 2
            ),
            ChatCallBannerPolicy.projectBanner(
                roomId = ROOM_ID,
                localCallRoomId = ROOM_ID,
                callInfo = inactive
            )
        )

        val remoteAudio = inactive.copy(hasRoomCall = true, isAudioCall = true)
        assertEquals(
            ChatCallBannerState(
                title = "Call in progress",
                actionLabel = "Join",
                isLocalCall = false,
                remoteMembershipCount = 2
            ),
            ChatCallBannerPolicy.projectBanner(
                roomId = ROOM_ID,
                localCallRoomId = null,
                callInfo = remoteAudio
            )
        )
        assertNull(
            ChatCallBannerPolicy.projectBanner(
                roomId = ROOM_ID,
                localCallRoomId = null,
                callInfo = remoteAudio.copy(isAudioCall = false)
            )
        )
        assertNull(
            ChatCallBannerPolicy.projectBanner(
                roomId = ROOM_ID,
                localCallRoomId = null,
                callInfo = inactive.copy(isAudioCall = true)
            )
        )
    }

    private companion object {
        const val ROOM_ID = "!room:example.org"

        fun callInfo(
            hasRoomCall: Boolean,
            participants: List<String> = emptyList(),
            isAudioCall: Boolean
        ): MatrixRoomCallInfo {
            return MatrixRoomCallInfo(
                roomId = ROOM_ID,
                hasRoomCall = hasRoomCall,
                activeParticipantUserIds = participants,
                isAudioCall = isAudioCall
            )
        }

        fun notification(): MatrixIncomingRtcCallNotification {
            return MatrixIncomingRtcCallNotification(
                eventId = "ring-event",
                roomId = ROOM_ID,
                senderId = "@alice:example.org",
                kind = MatrixIncomingRtcCallNotificationKind.RING,
                isAudioCall = true,
                expiresAtMillis = 2_000L
            )
        }
    }
}
