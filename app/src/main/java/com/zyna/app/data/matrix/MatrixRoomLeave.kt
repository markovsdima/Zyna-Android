package com.zyna.app.data.matrix

/** Membership and ownership context used to authorize leaving a room. */
data class MatrixRoomLeaveContext(
    val joinedMemberCount: Long,
    val isLastOwner: Boolean,
    val areCreatorsPrivileged: Boolean
) {
    val needsOwnershipWarning: Boolean
        get() = isLastOwner && joinedMemberCount > 1L
}
