package com.zyna.app.data.matrix

enum class MatrixRoomEncryption {
    ENCRYPTED,
    NOT_ENCRYPTED,
    UNKNOWN
}

enum class MatrixRoomAccess {
    PUBLIC,
    PRIVATE,
    ASK_TO_JOIN,
    RESTRICTED,
    CUSTOM,
    UNKNOWN
}

enum class MatrixRoomHistoryVisibility {
    SHARED,
    INVITED,
    JOINED,
    WORLD_READABLE,
    CUSTOM
}

/**
 * Whether the room version gives creators a distinct, immutable role.
 *
 * UNKNOWN is kept separate from LEGACY so an incomplete first snapshot cannot silently downgrade
 * a privileged creator to an ordinary power-level user.
 */
enum class MatrixRoomCreatorSemantics {
    UNKNOWN,
    LEGACY,
    PRIVILEGED
}

internal fun matrixRoomCreatorSemantics(
    roomVersion: String?,
    privilegedCreatorsRole: Boolean
): MatrixRoomCreatorSemantics {
    return when {
        privilegedCreatorsRole -> MatrixRoomCreatorSemantics.PRIVILEGED
        !roomVersion.isNullOrBlank() -> MatrixRoomCreatorSemantics.LEGACY
        else -> MatrixRoomCreatorSemantics.UNKNOWN
    }
}

data class MatrixRoomCapabilities(
    /** Null means the capability has not been resolved for the active room. */
    val canInviteMembers: Boolean? = null,
    val canChangeName: Boolean? = null,
    val canChangeTopic: Boolean? = null,
    val canChangeAvatar: Boolean? = null
)

/**
 * Immutable Matrix-independent projection used by the room-details feature.
 * SDK-owned values are mapped and disposed before this model crosses the data boundary.
 */
data class MatrixRoomDetails(
    val roomId: String,
    val displayName: String,
    val avatarUrl: String?,
    val directUserId: String?,
    val kind: MatrixRoomKind,
    val topic: String?,
    val joinedMemberCount: Long,
    val encryption: MatrixRoomEncryption,
    val access: MatrixRoomAccess,
    val historyVisibility: MatrixRoomHistoryVisibility,
    val pinnedEventCount: Int,
    val canonicalAlias: String?,
    val roomVersion: String? = null,
    val creatorSemantics: MatrixRoomCreatorSemantics = MatrixRoomCreatorSemantics.UNKNOWN,
    val capabilities: MatrixRoomCapabilities = MatrixRoomCapabilities()
)
