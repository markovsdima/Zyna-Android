package com.zyna.app.data.matrix

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationContent
import com.zyna.app.data.calls.matrixrtc.MatrixRtcLegacyCallNotifyContent
import com.zyna.app.data.calls.matrixrtc.MatrixRtcRawMembershipEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

class MatrixRoomCreationTest {
    @Test
    fun privateGroupIsEncryptedInviteOnlyAndHasInvitedHistory() {
        val parameters = request(access = MatrixRoomCreationAccess.Private)
            .toCreateRoomParameters()

        assertEquals("Friends", parameters.name)
        assertEquals("Weekend plans", parameters.topic)
        assertTrue(parameters.isEncrypted)
        assertFalse(parameters.isSpace)
        assertEquals(RoomVisibility.Private, parameters.visibility)
        assertEquals(RoomPreset.PRIVATE_CHAT, parameters.preset)
        assertEquals(JoinRule.Invite, parameters.joinRuleOverride)
        assertEquals(RoomHistoryVisibility.Invited, parameters.historyVisibilityOverride)
        assertNull(parameters.canonicalAlias)
    }

    @Test
    fun publicGroupIsDiscoverableUnencryptedAndUsesAliasLocalPart() {
        val parameters = request(access = MatrixRoomCreationAccess.Public)
            .toCreateRoomParameters()

        assertFalse(parameters.isEncrypted)
        assertFalse(parameters.isSpace)
        assertEquals(RoomVisibility.Public, parameters.visibility)
        assertEquals(RoomPreset.PUBLIC_CHAT, parameters.preset)
        assertEquals(JoinRule.Public, parameters.joinRuleOverride)
        assertNull(parameters.historyVisibilityOverride)
        assertEquals("friends", parameters.canonicalAlias)
    }

    @Test
    fun privateSpaceIsUnencryptedAndOwnerManaged() {
        val parameters = request(
            access = MatrixRoomCreationAccess.Private,
            kind = MatrixRoomCreationKind.SPACE
        ).toCreateRoomParameters()
        val powerLevels = requireNotNull(parameters.powerLevelContentOverride)

        assertTrue(parameters.isSpace)
        assertFalse(parameters.isEncrypted)
        assertEquals(RoomVisibility.Private, parameters.visibility)
        assertEquals(RoomPreset.PRIVATE_CHAT, parameters.preset)
        assertEquals(JoinRule.Invite, parameters.joinRuleOverride)
        assertEquals(RoomHistoryVisibility.Invited, parameters.historyVisibilityOverride)
        assertEquals(100, powerLevels.eventsDefault)
        assertEquals(50, powerLevels.invite)
        assertTrue(powerLevels.events.isEmpty())
    }

    @Test
    fun publicSpaceIsDiscoverableAndAllowsMemberInvites() {
        val parameters = request(
            access = MatrixRoomCreationAccess.Public,
            kind = MatrixRoomCreationKind.SPACE
        ).toCreateRoomParameters()
        val powerLevels = requireNotNull(parameters.powerLevelContentOverride)

        assertTrue(parameters.isSpace)
        assertFalse(parameters.isEncrypted)
        assertEquals(RoomVisibility.Public, parameters.visibility)
        assertEquals(JoinRule.Public, parameters.joinRuleOverride)
        assertEquals("friends", parameters.canonicalAlias)
        assertEquals(100, powerLevels.eventsDefault)
        assertEquals(0, powerLevels.invite)
    }

    @Test
    fun parentMembersAccessUsesRestrictedJoinRuleAndSharedUnencryptedHistory() {
        val parameters = request(
            access = MatrixRoomCreationAccess.Restricted(PARENT_SPACE_ID)
        ).toCreateRoomParameters()
        val joinRule = parameters.joinRuleOverride as JoinRule.Restricted

        assertEquals(
            listOf(AllowRule.RoomMembership(PARENT_SPACE_ID)),
            joinRule.rules
        )
        assertEquals(RoomVisibility.Private, parameters.visibility)
        assertEquals(RoomPreset.PRIVATE_CHAT, parameters.preset)
        assertEquals(RoomHistoryVisibility.Shared, parameters.historyVisibilityOverride)
        assertFalse(parameters.isEncrypted)
    }

    @Test
    fun moderatorsOnlyRestrictsDefaultEventsButKeepsMatrixRtcAvailableToMembers() {
        val parameters = request(
            access = MatrixRoomCreationAccess.Private,
            postingPermission = MatrixRoomPostingPermission.MODERATORS_ONLY
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
            request(access = MatrixRoomCreationAccess.Private).toCreateRoomParameters()
                .powerLevelContentOverride
        )

        assertNull(powerLevels.usersDefault)
        assertNull(powerLevels.eventsDefault)
    }

    @Test
    fun rtcOverridesPreserveStandardProtectedRoomEvents() {
        val events = requireNotNull(
            request(access = MatrixRoomCreationAccess.Private).toCreateRoomParameters()
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
    access: MatrixRoomCreationAccess,
    kind: MatrixRoomCreationKind = MatrixRoomCreationKind.ROOM,
    postingPermission: MatrixRoomPostingPermission =
        MatrixRoomPostingPermission.ALL_MEMBERS
): MatrixRoomCreationRequest {
    return MatrixRoomCreationRequest(
        name = " Friends ",
        topic = " Weekend plans ",
        avatarUrl = "mxc://example/avatar",
        kind = kind,
        access = access,
        aliasLocalPart = if (access == MatrixRoomCreationAccess.Public) "friends" else null,
        postingPermission = postingPermission
    )
}

private const val PARENT_SPACE_ID = "!parent:example.org"
