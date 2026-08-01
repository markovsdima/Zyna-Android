package com.zyna.app.data.matrix

enum class MatrixSpaceRoomKind {
    ROOM,
    SPACE
}

enum class MatrixSpaceMembership {
    INVITED,
    JOINED,
    LEFT,
    KNOCKED,
    BANNED,
    UNKNOWN
}

enum class MatrixSpaceJoinRule {
    PUBLIC,
    INVITE,
    KNOCK,
    RESTRICTED,
    KNOCK_RESTRICTED,
    PRIVATE,
    CUSTOM,
    UNKNOWN
}

/**
 * Immutable Matrix-independent projection of a room exposed through the Space graph.
 *
 * Space hierarchy responses may include rooms the current user has not joined, so this model is
 * deliberately separate from [MatrixRoomSummary], whose owner is the joined-room list.
 */
data class MatrixSpaceRoom(
    val roomId: String,
    val displayName: String,
    val avatarUrl: String?,
    val topic: String?,
    val kind: MatrixSpaceRoomKind,
    val membership: MatrixSpaceMembership,
    val joinedMemberCount: Long,
    val childrenCount: Long,
    val canonicalAlias: String?,
    val joinRule: MatrixSpaceJoinRule,
    val worldReadable: Boolean?,
    val guestCanJoin: Boolean,
    val isDirect: Boolean?,
    val isDm: Boolean?,
    val via: List<String>
) {
    val isJoined: Boolean
        get() = membership == MatrixSpaceMembership.JOINED

    fun toJoinedRoomSummary(): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = roomId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            isSpace = kind == MatrixSpaceRoomKind.SPACE
        )
    }
}

/**
 * Cached and live representation of one ordered Space list.
 *
 * `isKnown = false` means no authoritative snapshot has been received yet. This is distinct from
 * a successfully loaded empty list and prevents an incorrect empty-state flash on first render.
 */
data class MatrixSpaceListSnapshot(
    val space: MatrixSpaceRoom? = null,
    val rooms: List<MatrixSpaceRoom> = emptyList(),
    val isKnown: Boolean = false,
    val endReached: Boolean = false,
    val updatedAtMillis: Long? = null
)

sealed interface MatrixSpaceListUpdate {
    data class Append(val values: List<MatrixSpaceRoom>) : MatrixSpaceListUpdate
    data object Clear : MatrixSpaceListUpdate
    data class PushFront(val value: MatrixSpaceRoom) : MatrixSpaceListUpdate
    data class PushBack(val value: MatrixSpaceRoom) : MatrixSpaceListUpdate
    data object PopFront : MatrixSpaceListUpdate
    data object PopBack : MatrixSpaceListUpdate
    data class Insert(val index: Int, val value: MatrixSpaceRoom) : MatrixSpaceListUpdate
    data class Set(val index: Int, val value: MatrixSpaceRoom) : MatrixSpaceListUpdate
    data class Remove(val index: Int) : MatrixSpaceListUpdate
    data class Truncate(val length: Int) : MatrixSpaceListUpdate
    data class Reset(val values: List<MatrixSpaceRoom>) : MatrixSpaceListUpdate
}

internal fun applyMatrixSpaceListUpdates(
    current: List<MatrixSpaceRoom>,
    updates: List<MatrixSpaceListUpdate>
): List<MatrixSpaceRoom> {
    val result = current.toMutableList()
    updates.forEach { update ->
        when (update) {
            is MatrixSpaceListUpdate.Append -> result.addAll(update.values)
            MatrixSpaceListUpdate.Clear -> result.clear()
            is MatrixSpaceListUpdate.PushFront -> result.add(0, update.value)
            is MatrixSpaceListUpdate.PushBack -> result.add(update.value)
            MatrixSpaceListUpdate.PopFront -> if (result.isNotEmpty()) result.removeAt(0)
            MatrixSpaceListUpdate.PopBack -> if (result.isNotEmpty()) {
                result.removeAt(result.lastIndex)
            }
            is MatrixSpaceListUpdate.Insert -> {
                val index = update.index.coerceIn(0, result.size)
                result.add(index, update.value)
            }
            is MatrixSpaceListUpdate.Set -> {
                if (update.index in result.indices) {
                    result[update.index] = update.value
                }
            }
            is MatrixSpaceListUpdate.Remove -> {
                if (update.index in result.indices) {
                    result.removeAt(update.index)
                }
            }
            is MatrixSpaceListUpdate.Truncate -> {
                val length = update.length.coerceIn(0, result.size)
                result.subList(length, result.size).clear()
            }
            is MatrixSpaceListUpdate.Reset -> {
                result.clear()
                result.addAll(update.values)
            }
        }
    }
    // A malformed or racing server graph must not create duplicate stable IDs in the UI.
    return result.distinctBy(MatrixSpaceRoom::roomId)
}
