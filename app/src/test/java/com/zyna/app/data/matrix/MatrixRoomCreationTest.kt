package com.zyna.app.data.matrix

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationContent
import com.zyna.app.data.calls.matrixrtc.MatrixRtcLegacyCallNotifyContent
import com.zyna.app.data.calls.matrixrtc.MatrixRtcRawMembershipEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

class MatrixRoomCreationTest {
    @Test
    fun privateGroupIsEncryptedInviteOnlyAndHasInvitedHistory() {
        val parameters = request(access = MatrixGroupAccess.PRIVATE).toCreateRoomParameters()

        assertEquals("Friends", parameters.name)
        assertEquals("Weekend plans", parameters.topic)
        assertTrue(parameters.isEncrypted)
        assertEquals(RoomVisibility.Private, parameters.visibility)
        assertEquals(RoomPreset.PRIVATE_CHAT, parameters.preset)
        assertEquals(JoinRule.Invite, parameters.joinRuleOverride)
        assertEquals(RoomHistoryVisibility.Invited, parameters.historyVisibilityOverride)
        assertNull(parameters.canonicalAlias)
    }

    @Test
    fun publicGroupIsDiscoverableUnencryptedAndUsesAliasLocalPart() {
        val parameters = request(access = MatrixGroupAccess.PUBLIC).toCreateRoomParameters()

        assertFalse(parameters.isEncrypted)
        assertEquals(RoomVisibility.Public, parameters.visibility)
        assertEquals(RoomPreset.PUBLIC_CHAT, parameters.preset)
        assertEquals(JoinRule.Public, parameters.joinRuleOverride)
        assertNull(parameters.historyVisibilityOverride)
        assertEquals("friends", parameters.canonicalAlias)
    }

    @Test
    fun moderatorsOnlyRestrictsDefaultEventsButKeepsMatrixRtcAvailableToMembers() {
        val parameters = request(
            access = MatrixGroupAccess.PRIVATE,
            postingPermission = MatrixGroupPostingPermission.MODERATORS_ONLY
        ).toCreateRoomParameters()
        val powerLevels = requireNotNull(parameters.powerLevelContentOverride)

        assertEquals(0, powerLevels.usersDefault)
        assertEquals(50, powerLevels.eventsDefault)
        assertEquals(
            0,
            powerLevels.events[MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE]
        )
        assertEquals(
            0,
            powerLevels.events[MatrixRtcRawMembershipEvent.RTC_MEMBER_EVENT_TYPE]
        )
        assertEquals(0, powerLevels.events[MatrixRtcCallNotificationContent.EVENT_TYPE])
        assertEquals(0, powerLevels.events[MatrixRtcLegacyCallNotifyContent.EVENT_TYPE])
    }

    @Test
    fun allMembersLeavesDefaultPostingLevelsUnchanged() {
        val powerLevels = requireNotNull(
            request(access = MatrixGroupAccess.PRIVATE).toCreateRoomParameters()
                .powerLevelContentOverride
        )

        assertNull(powerLevels.usersDefault)
        assertNull(powerLevels.eventsDefault)
    }

    @Test
    fun rtcOverridesPreserveStandardProtectedRoomEvents() {
        val events = requireNotNull(
            request(access = MatrixGroupAccess.PRIVATE).toCreateRoomParameters()
                .powerLevelContentOverride
        ).events

        assertEquals(50, events["m.room.name"])
        assertEquals(50, events["m.room.canonical_alias"])
        assertEquals(50, events["m.room.avatar"])
        assertEquals(100, events["m.room.power_levels"])
        assertEquals(100, events["m.room.history_visibility"])
        assertEquals(100, events["m.room.tombstone"])
        assertEquals(100, events["m.room.server_acl"])
        assertEquals(100, events["m.room.encryption"])
        DEFAULT_ROOM_EVENT_POWER_LEVELS.forEach { (eventType, powerLevel) ->
            assertEquals(powerLevel, events[eventType])
        }
    }
}

private fun request(
    access: MatrixGroupAccess,
    postingPermission: MatrixGroupPostingPermission = MatrixGroupPostingPermission.ALL_MEMBERS
): MatrixGroupCreationRequest {
    return MatrixGroupCreationRequest(
        name = " Friends ",
        topic = " Weekend plans ",
        avatarUrl = "mxc://example/avatar",
        access = access,
        aliasLocalPart = if (access == MatrixGroupAccess.PUBLIC) "friends" else null,
        postingPermission = postingPermission
    )
}
