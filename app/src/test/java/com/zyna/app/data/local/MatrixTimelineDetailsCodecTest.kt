package com.zyna.app.data.local

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.matrix.MatrixMembershipEventChange
import com.zyna.app.data.matrix.MatrixPinnedEventsChange
import com.zyna.app.data.matrix.MatrixRoomStateChange
import com.zyna.app.data.matrix.MatrixRtcCallEventDetails
import com.zyna.app.data.matrix.MatrixSystemEventDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixTimelineDetailsCodecTest {
    @Test
    fun systemEvent_membership_roundTripsNullableFields() {
        val details = MatrixSystemEventDetails.Membership(
            userId = "@alice:example.org",
            userDisplayName = "Alice",
            change = MatrixMembershipEventChange.KICKED,
            reason = null
        )

        assertEquals(
            details,
            MatrixTimelineDetailsCodec.decodeSystemEvent(
                MatrixTimelineDetailsCodec.encodeSystemEvent(details)
            )
        )
    }

    @Test
    fun systemEvent_profileChange_roundTripsNullDisplayName() {
        val details = MatrixSystemEventDetails.ProfileChange(
            displayName = null,
            previousDisplayName = "Old name"
        )

        assertEquals(
            details,
            MatrixTimelineDetailsCodec.decodeSystemEvent(
                MatrixTimelineDetailsCodec.encodeSystemEvent(details)
            )
        )
    }

    @Test
    fun systemEvent_allRoomStateChanges_roundTrip() {
        val changes = listOf(
            MatrixRoomStateChange.Avatar(url = null),
            MatrixRoomStateChange.Created(federate = false),
            MatrixRoomStateChange.EncryptionEnabled,
            MatrixRoomStateChange.Name(name = "Room"),
            MatrixRoomStateChange.PinnedEvents(MatrixPinnedEventsChange.CHANGED),
            MatrixRoomStateChange.ThirdPartyInvite(displayName = "Guest"),
            MatrixRoomStateChange.Topic(topic = "Topic")
        )

        changes.forEach { change ->
            val details = MatrixSystemEventDetails.RoomState(
                stateKey = "state-key",
                change = change
            )
            assertEquals(
                details,
                MatrixTimelineDetailsCodec.decodeSystemEvent(
                    MatrixTimelineDetailsCodec.encodeSystemEvent(details)
                )
            )
        }
    }

    @Test
    fun matrixRtcCall_roundTripsAndNormalizesDeclinedBy() {
        val details = MatrixRtcCallEventDetails(
            parentEventId = "\$parent",
            callIntent = "audio",
            notificationType = MatrixRtcCallNotificationType.RING,
            expiresAtMillis = 123_456L,
            declinedBy = listOf("@z:example.org", "", "@a:example.org", "@z:example.org"),
            outcome = MatrixRtcCallHistoryOutcome.MISSED
        )

        assertEquals(
            details.copy(declinedBy = listOf("@a:example.org", "@z:example.org")),
            MatrixTimelineDetailsCodec.decodeMatrixRtcCall(
                MatrixTimelineDetailsCodec.encodeMatrixRtcCall(details)
            )
        )
    }

    @Test
    fun matrixRtcCall_roundTripsNullProjectionFields() {
        val details = MatrixRtcCallEventDetails(
            parentEventId = null,
            callIntent = null,
            notificationType = MatrixRtcCallNotificationType.NOTIFICATION,
            expiresAtMillis = null,
            declinedBy = emptyList(),
            outcome = null
        )

        assertEquals(
            details,
            MatrixTimelineDetailsCodec.decodeMatrixRtcCall(
                MatrixTimelineDetailsCodec.encodeMatrixRtcCall(details)
            )
        )
    }

    @Test
    fun malformedOrUnsupportedPayload_isIgnored() {
        assertNull(MatrixTimelineDetailsCodec.decodeSystemEvent("not-json"))
        assertNull(
            MatrixTimelineDetailsCodec.decodeSystemEvent(
                """{"version":2,"kind":"profile_change"}"""
            )
        )
        assertNull(
            MatrixTimelineDetailsCodec.decodeMatrixRtcCall(
                """{"version":1,"kind":"profile_change"}"""
            )
        )
    }
}
