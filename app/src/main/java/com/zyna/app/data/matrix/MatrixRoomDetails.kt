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
    val canonicalAlias: String?
)
