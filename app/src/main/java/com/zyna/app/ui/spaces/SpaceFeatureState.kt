package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom

data class SpaceRootsState(
    val snapshot: MatrixSpaceListSnapshot = MatrixSpaceListSnapshot()
) {
    val spaces: List<MatrixSpaceRoom>
        get() = snapshot.rooms

    val isKnown: Boolean
        get() = snapshot.isKnown
}

data class SpaceTarget(
    val userId: String,
    val spaceId: String,
    val parentSpaceId: String?,
    val seed: MatrixSpaceRoom
)

enum class SpaceLoadError {
    LOAD
}

data class SpaceChildrenState(
    val target: SpaceTarget? = null,
    val space: MatrixSpaceRoom? = null,
    val tracks: List<MatrixSpaceRoom> = emptyList(),
    val chats: List<MatrixSpaceRoom> = emptyList(),
    val isKnown: Boolean = false,
    val endReached: Boolean = false,
    val isPaginating: Boolean = false,
    val error: SpaceLoadError? = null
) {
    fun roomForId(roomId: String): MatrixSpaceRoom? {
        return tracks.firstOrNull { it.roomId == roomId }
            ?: chats.firstOrNull { it.roomId == roomId }
    }

    fun childForOpen(
        userId: String,
        spaceId: String,
        parentSpaceId: String?,
        childRoomId: String
    ): MatrixSpaceRoom? {
        val currentTarget = target ?: return null
        if (
            currentTarget.userId != userId ||
            currentTarget.spaceId != spaceId ||
            currentTarget.parentSpaceId != parentSpaceId
        ) {
            return null
        }
        return roomForId(childRoomId)
    }
}

data class SpaceFeatureState(
    val roots: SpaceRootsState = SpaceRootsState(),
    val children: SpaceChildrenState = SpaceChildrenState(),
    val join: SpaceJoinState = SpaceJoinState()
)

internal fun visibleChatRootRooms(
    rooms: List<MatrixRoomSummary>,
    roots: SpaceRootsState
): List<MatrixRoomSummary> {
    if (!roots.isKnown) return rooms

    val visible = ArrayList<MatrixRoomSummary>(rooms.size + roots.spaces.size)
    val rootsById = roots.spaces.associateBy(MatrixSpaceRoom::roomId)
    val visibleRootIds = HashSet<String>(roots.spaces.size)
    rooms.forEach { room ->
        val joinedRoot = rootsById[room.id]
        when {
            !room.isSpace -> visible += room
            joinedRoot != null -> {
                visible += room.copy(
                    displayName = joinedRoot.displayName,
                    avatarUrl = joinedRoot.avatarUrl ?: room.avatarUrl,
                    isSpace = true,
                    spaceMembership = MatrixSpaceMembership.JOINED
                )
                visibleRootIds += room.id
            }
            room.spaceMembership == MatrixSpaceMembership.INVITED -> {
                visible += room
            }
        }
    }
    roots.spaces.forEach { root ->
        if (visibleRootIds.add(root.roomId)) {
            visible += root.toJoinedRoomSummary()
        }
    }
    return visible
}

internal class VisibleChatRootRoomsProjection {
    private var sourceRooms: List<MatrixRoomSummary>? = null
    private var sourceSnapshot: MatrixSpaceListSnapshot? = null
    private var result: List<MatrixRoomSummary> = emptyList()

    fun project(
        rooms: List<MatrixRoomSummary>,
        roots: SpaceRootsState
    ): List<MatrixRoomSummary> {
        if (rooms === sourceRooms && roots.snapshot === sourceSnapshot) return result
        sourceRooms = rooms
        sourceSnapshot = roots.snapshot
        result = visibleChatRootRooms(rooms, roots)
        return result
    }
}
