package com.zyna.app.data.matrix

enum class MatrixRoomMemberModerationAction {
    KICK,
    BAN,
    UNBAN
}

/**
 * Fresh, target-specific authorization snapshot for room-member moderation.
 *
 * Matrix power-level capabilities answer whether the current user may issue a category of
 * command. The explicit power comparison additionally protects peers, stronger members and the
 * immutable privileged creator used by room version 12.
 */
data class MatrixRoomMemberModerationContext(
    val roomId: String,
    val ownUserId: String,
    val ownPowerLevel: Long,
    val canKickMembers: Boolean,
    val canBanMembers: Boolean,
    val member: MatrixRoomMember
) {
    fun canPerform(action: MatrixRoomMemberModerationAction): Boolean {
        if (
            !canOwnUserActOnRoomMember(
                ownUserId = ownUserId,
                ownPowerLevel = ownPowerLevel,
                targetUserId = member.userId,
                targetPowerLevel = member.powerLevel,
                targetRole = member.role
            )
        ) {
            return false
        }
        return when (action) {
            MatrixRoomMemberModerationAction.KICK -> {
                canKickMembers && (
                    member.membership == MatrixRoomMemberMembership.INVITED ||
                        member.membership == MatrixRoomMemberMembership.JOINED
                    )
            }
            MatrixRoomMemberModerationAction.BAN -> {
                canBanMembers && (
                    member.membership == MatrixRoomMemberMembership.INVITED ||
                        member.membership == MatrixRoomMemberMembership.JOINED ||
                        member.membership == MatrixRoomMemberMembership.LEFT
                    )
            }
            MatrixRoomMemberModerationAction.UNBAN -> {
                canBanMembers &&
                    member.membership == MatrixRoomMemberMembership.BANNED
            }
        }
    }

    fun reflectsCompleted(action: MatrixRoomMemberModerationAction): Boolean {
        return when (action) {
            MatrixRoomMemberModerationAction.KICK,
            MatrixRoomMemberModerationAction.UNBAN -> {
                member.membership == MatrixRoomMemberMembership.LEFT
            }
            MatrixRoomMemberModerationAction.BAN -> {
                member.membership == MatrixRoomMemberMembership.BANNED
            }
        }
    }

    fun withCompleted(action: MatrixRoomMemberModerationAction): MatrixRoomMemberModerationContext {
        val membership = when (action) {
            MatrixRoomMemberModerationAction.KICK,
            MatrixRoomMemberModerationAction.UNBAN -> MatrixRoomMemberMembership.LEFT
            MatrixRoomMemberModerationAction.BAN -> MatrixRoomMemberMembership.BANNED
        }
        return copy(member = member.copy(membership = membership))
    }
}
