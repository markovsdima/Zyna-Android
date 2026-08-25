package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.OtherState
import uniffi.matrix_sdk_ui.RoomPinnedEventsChange

class MatrixTimelineEventModelsTest {
    @Test
    fun membership_mapsSupportedChangeAndNormalizesReason() {
        val result = matrixMembershipEventDetailsOrNull(
            userId = "@alice:example.org",
            userDisplayName = "Alice",
            change = MembershipChange.KICKED,
            reason = "  violated room rules  "
        )

        assertEquals(
            MatrixSystemEventDetails.Membership(
                userId = "@alice:example.org",
                userDisplayName = "Alice",
                change = MatrixMembershipEventChange.KICKED,
                reason = "violated room rules"
            ),
            result
        )
    }

    @Test
    fun membership_ignoresNoneAndBlankReason() {
        assertNull(
            matrixMembershipEventDetailsOrNull(
                userId = "@alice:example.org",
                userDisplayName = null,
                change = MembershipChange.NONE,
                reason = "not rendered"
            )
        )

        assertNull(
            matrixMembershipEventDetailsOrNull(
                userId = "@alice:example.org",
                userDisplayName = null,
                change = MembershipChange.JOINED,
                reason = " \n\t "
            )?.reason
        )
    }

    @Test
    fun profileChange_ignoresUnchangedNameAndMapsChangedName() {
        assertNull(
            matrixProfileChangeEventDetailsOrNull(
                displayName = "Alice",
                previousDisplayName = "Alice"
            )
        )
        assertEquals(
            MatrixSystemEventDetails.ProfileChange(
                displayName = "Alice 2",
                previousDisplayName = "Alice"
            ),
            matrixProfileChangeEventDetailsOrNull(
                displayName = "Alice 2",
                previousDisplayName = "Alice"
            )
        )
    }

    @Test
    fun roomState_mapsSupportedSdkStates() {
        val cases = listOf(
            OtherState.RoomAvatar(url = "mxc://example.org/avatar") to
                MatrixRoomStateChange.Avatar("mxc://example.org/avatar"),
            OtherState.RoomCreate(federate = false) to
                MatrixRoomStateChange.Created(federate = false),
            OtherState.RoomEncryption to MatrixRoomStateChange.EncryptionEnabled,
            OtherState.RoomName(name = "Project") to MatrixRoomStateChange.Name("Project"),
            OtherState.RoomPinnedEvents(RoomPinnedEventsChange.REMOVED) to
                MatrixRoomStateChange.PinnedEvents(MatrixPinnedEventsChange.REMOVED),
            OtherState.RoomThirdPartyInvite(displayName = "Guest") to
                MatrixRoomStateChange.ThirdPartyInvite("Guest"),
            OtherState.RoomTopic(topic = "Roadmap") to MatrixRoomStateChange.Topic("Roadmap")
        )

        cases.forEach { (sdkState, expectedChange) ->
            assertEquals(
                MatrixSystemEventDetails.RoomState(
                    stateKey = "state-key",
                    change = expectedChange
                ),
                matrixRoomStateEventDetailsOrNull(
                    stateKey = "state-key",
                    state = sdkState
                )
            )
        }
    }

    @Test
    fun roomState_ignoresUnsupportedAndEmptyThirdPartyInvite() {
        assertNull(
            matrixRoomStateEventDetailsOrNull(
                stateKey = "",
                state = OtherState.RoomCanonicalAlias
            )
        )
        assertNull(
            matrixRoomStateEventDetailsOrNull(
                stateKey = "",
                state = OtherState.RoomThirdPartyInvite(displayName = "")
            )
        )
    }
}
