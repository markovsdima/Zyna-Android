package com.zyna.app.data.matrix

enum class MatrixRoomMemberMembership {
    INVITED,
    JOINED
}

enum class MatrixRoomMemberRole {
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
