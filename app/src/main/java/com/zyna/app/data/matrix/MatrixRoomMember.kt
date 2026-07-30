package com.zyna.app.data.matrix

enum class MatrixRoomMemberMembership {
    INVITED,
    JOINED,
    BANNED,
    LEFT
}

enum class MatrixRoomMemberRole {
    CREATOR,
    OWNER,
    ADMIN,
    MODERATOR,
    MEMBER
}

/**
 * Immutable Matrix-independent projection used by the room-members feature.
 */
data class MatrixRoomMember(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val membership: MatrixRoomMemberMembership,
    val role: MatrixRoomMemberRole,
    val powerLevel: Long,
    val isNameAmbiguous: Boolean
) {
    val displayNameOrUserId: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: userId
}

internal fun canOwnUserActOnRoomMember(
    ownUserId: String,
    ownPowerLevel: Long,
    targetUserId: String,
    targetPowerLevel: Long,
    targetRole: MatrixRoomMemberRole
): Boolean {
    return targetUserId != ownUserId &&
        targetRole != MatrixRoomMemberRole.CREATOR &&
        ownPowerLevel > targetPowerLevel
}
