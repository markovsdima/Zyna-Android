package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.spaceRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SpaceRootPresentationTest {
    @Test
    fun knownRootsHideNestedTracksAndKeepOrdinaryRooms() {
        val chat = room("chat")
        val root = room("root", isSpace = true)
        val nested = room("nested", isSpace = true)
        val roots = SpaceRootsState(
            MatrixSpaceListSnapshot(
                rooms = listOf(spaceRoom("root")),
                isKnown = true,
                endReached = true
            )
        )

        val result = visibleChatRootRooms(listOf(chat, nested, root), roots)

        assertEquals(listOf(chat, root), result)
    }

    @Test
    fun rootMissingFromJoinedRoomProjectionIsStillVisible() {
        val roots = SpaceRootsState(
            MatrixSpaceListSnapshot(
                rooms = listOf(spaceRoom("root", displayName = "Storyline")),
                isKnown = true,
                endReached = true
            )
        )

        val result = visibleChatRootRooms(emptyList(), roots)

        assertEquals("root", result.single().id)
        assertEquals("Storyline", result.single().displayName)
        assertEquals(true, result.single().isSpace)
    }

    @Test
    fun unknownRootsPreserveExistingFirstFrame() {
        val rooms = listOf(room("nested", isSpace = true))

        assertEquals(rooms, visibleChatRootRooms(rooms, SpaceRootsState()))
    }

    @Test
    fun renderProjectionReusesWorkUntilEitherSourceChanges() {
        val projection = VisibleChatRootRoomsProjection()
        val rooms = listOf(room("chat"), room("root", isSpace = true))
        val roots = SpaceRootsState(
            MatrixSpaceListSnapshot(
                rooms = listOf(spaceRoom("root")),
                isKnown = true,
                endReached = true
            )
        )

        val first = projection.project(rooms, roots)

        assertSame(first, projection.project(rooms, roots))
        assertNotSame(first, projection.project(rooms.toList(), roots))
    }

    private fun room(id: String, isSpace: Boolean = false): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = id,
            displayName = id,
            avatarUrl = null,
            isSpace = isSpace
        )
    }
}
