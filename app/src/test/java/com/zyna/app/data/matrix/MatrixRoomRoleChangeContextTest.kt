package com.zyna.app.data.matrix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRoomRoleChangeContextTest {
    @Test
    fun administratorCanPromoteAJoinedMemberToAdministrator() {
        val context = context(ownPowerLevel = 100, targetPowerLevel = 0)

        assertTrue(context.canChangeTarget())
        assertTrue(context.canAssign(100))
    }

    @Test
    fun moderatorCannotPromoteAnotherMemberAboveTheirOwnLevel() {
        val context = context(ownPowerLevel = 50, targetPowerLevel = 0)

        assertTrue(context.canAssign(50))
        assertFalse(context.canAssign(100))
    }

    @Test
    fun selfCreatorAndEqualPowerPeerCannotBeChanged() {
        assertFalse(context(targetUserId = USER_ID).canChangeTarget())
        assertFalse(context(targetRole = MatrixRoomMemberRole.CREATOR).canChangeTarget())
        assertFalse(
            context(ownPowerLevel = 100, targetPowerLevel = 100).canChangeTarget()
        )
    }

    @Test
    fun privilegedCreatorCanChangeADelegatedOwner() {
        assertTrue(
            context(
                ownPowerLevel = Long.MAX_VALUE,
                targetPowerLevel = 150,
                targetRole = MatrixRoomMemberRole.OWNER
            ).canChangeTarget()
        )
    }

    @Test
    fun invitedMemberAndPermissionLossCannotBeChanged() {
        assertFalse(
            context(targetMembership = MatrixRoomMemberMembership.INVITED).canChangeTarget()
        )
        assertFalse(context(canEditPowerLevels = false).canChangeTarget())
    }

    private fun context(
        ownPowerLevel: Long = 100,
        canEditPowerLevels: Boolean = true,
        targetUserId: String = OTHER_USER_ID,
        targetPowerLevel: Long = 0,
        targetMembership: MatrixRoomMemberMembership = MatrixRoomMemberMembership.JOINED,
        targetRole: MatrixRoomMemberRole = MatrixRoomMemberRole.MEMBER
    ): MatrixRoomRoleChangeContext {
        return MatrixRoomRoleChangeContext(
            roomId = ROOM_ID,
            ownUserId = USER_ID,
            ownPowerLevel = ownPowerLevel,
            canEditPowerLevels = canEditPowerLevels,
            targetUserId = targetUserId,
            targetPowerLevel = targetPowerLevel,
            targetMembership = targetMembership,
            targetRole = targetRole
        )
    }
}

private const val USER_ID = "@alice:example.org"
private const val OTHER_USER_ID = "@bob:example.org"
private const val ROOM_ID = "!room:example.org"
