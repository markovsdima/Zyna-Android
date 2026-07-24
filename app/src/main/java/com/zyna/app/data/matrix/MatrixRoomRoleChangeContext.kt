package com.zyna.app.data.matrix

/**
 * Fresh, Matrix-independent authorization snapshot for changing one room member's role.
 *
 * This is intentionally target-specific: the save path can recheck the current user and the
 * selected member without loading every member in a large room.
 */
data class MatrixRoomRoleChangeContext(
    val roomId: String,
    val ownUserId: String,
    val ownPowerLevel: Long,
    val canEditPowerLevels: Boolean,
    val targetUserId: String,
    val targetPowerLevel: Long,
    val targetMembership: MatrixRoomMemberMembership,
    val targetRole: MatrixRoomMemberRole
) {
    fun canChangeTarget(): Boolean {
        return canEditPowerLevels &&
            targetMembership == MatrixRoomMemberMembership.JOINED &&
            targetUserId != ownUserId &&
            targetRole != MatrixRoomMemberRole.OWNER &&
            ownPowerLevel > targetPowerLevel
    }

    fun canAssign(powerLevel: Long): Boolean {
        return canChangeTarget() && ownPowerLevel >= powerLevel
    }
}
