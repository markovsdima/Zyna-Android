package com.zyna.app.data.matrix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRoomMemberModerationContextTest {
    @Test
    fun joinedMemberCanBeKickedOrBannedWithMatchingCapabilities() {
        val context = context()

        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.BAN))
        assertFalse(context.canPerform(MatrixRoomMemberModerationAction.UNBAN))
    }

    @Test
    fun invitedMemberCanBeRevokedOrBanned() {
        val context = context(membership = MatrixRoomMemberMembership.INVITED)

        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.BAN))
    }

    @Test
    fun bannedMemberCanOnlyBeUnbanned() {
        val context = context(membership = MatrixRoomMemberMembership.BANNED)

        assertFalse(context.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertFalse(context.canPerform(MatrixRoomMemberModerationAction.BAN))
        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.UNBAN))
    }

    @Test
    fun departedMemberCanBeBannedButNotKickedOrUnbanned() {
        val context = context(membership = MatrixRoomMemberMembership.LEFT)

        assertFalse(context.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.BAN))
        assertFalse(context.canPerform(MatrixRoomMemberModerationAction.UNBAN))
    }

    @Test
    fun capabilitiesAreCheckedIndependently() {
        val noKick = context(canKickMembers = false)
        val noBan = context(canBanMembers = false)

        assertFalse(noKick.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertTrue(noKick.canPerform(MatrixRoomMemberModerationAction.BAN))
        assertTrue(noBan.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertFalse(noBan.canPerform(MatrixRoomMemberModerationAction.BAN))
    }

    @Test
    fun selfCreatorAndEqualPowerPeerCannotBeModerated() {
        assertFalse(
            context(targetUserId = OWN_USER_ID)
                .canPerform(MatrixRoomMemberModerationAction.KICK)
        )
        assertFalse(
            context(
                targetPowerLevel = Long.MAX_VALUE,
                targetRole = MatrixRoomMemberRole.CREATOR
            ).canPerform(MatrixRoomMemberModerationAction.BAN)
        )
        assertFalse(
            context(targetPowerLevel = 100)
                .canPerform(MatrixRoomMemberModerationAction.KICK)
        )
    }

    @Test
    fun privilegedCreatorCanModerateADelegatedOwner() {
        val context = context(
            ownPowerLevel = Long.MAX_VALUE,
            targetPowerLevel = 150,
            targetRole = MatrixRoomMemberRole.OWNER
        )

        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.KICK))
        assertTrue(context.canPerform(MatrixRoomMemberModerationAction.BAN))
    }

    @Test
    fun completionMembershipIsRecognizedPerAction() {
        assertTrue(
            context(membership = MatrixRoomMemberMembership.LEFT)
                .reflectsCompleted(MatrixRoomMemberModerationAction.KICK)
        )
        assertTrue(
            context(membership = MatrixRoomMemberMembership.LEFT)
                .reflectsCompleted(MatrixRoomMemberModerationAction.UNBAN)
        )
        assertTrue(
            context(membership = MatrixRoomMemberMembership.BANNED)
                .reflectsCompleted(MatrixRoomMemberModerationAction.BAN)
        )
        assertFalse(
            context(membership = MatrixRoomMemberMembership.JOINED)
                .reflectsCompleted(MatrixRoomMemberModerationAction.BAN)
        )
    }

    private fun context(
        ownPowerLevel: Long = 100,
        canKickMembers: Boolean = true,
        canBanMembers: Boolean = true,
        targetUserId: String = TARGET_USER_ID,
        targetPowerLevel: Long = 0,
        targetRole: MatrixRoomMemberRole = MatrixRoomMemberRole.MEMBER,
        membership: MatrixRoomMemberMembership = MatrixRoomMemberMembership.JOINED
    ): MatrixRoomMemberModerationContext {
        return MatrixRoomMemberModerationContext(
            roomId = ROOM_ID,
            ownUserId = OWN_USER_ID,
            ownPowerLevel = ownPowerLevel,
            canKickMembers = canKickMembers,
            canBanMembers = canBanMembers,
            member = MatrixRoomMember(
                userId = targetUserId,
                displayName = "Bob",
                avatarUrl = null,
                membership = membership,
                role = targetRole,
                powerLevel = targetPowerLevel,
                isNameAmbiguous = false
            )
        )
    }
}

private const val OWN_USER_ID = "@alice:example.org"
private const val TARGET_USER_ID = "@bob:example.org"
private const val ROOM_ID = "!room:example.org"
