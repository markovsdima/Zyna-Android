package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRoomListSessionTest {
    @Test
    fun appliesOrderedSdkUpdates() {
        val first = entry("!first:example.org")
        val second = entry("!second:example.org")
        val replacement = entry("!replacement:example.org")

        val result = applyMatrixRoomListUpdates(
            current = listOf(first),
            updates = listOf(
                MatrixRoomListUpdate.PushBack(second),
                MatrixRoomListUpdate.Insert(1, replacement),
                MatrixRoomListUpdate.Remove(2),
                MatrixRoomListUpdate.PushFront(second)
            )
        )

        assertEquals(listOf(second, first, replacement), result)
    }

    @Test
    fun appliesEverySdkBoundaryUpdateKind() {
        val first = entry("!first:example.org")
        val second = entry("!second:example.org")
        val third = entry("!third:example.org")
        val replacement = entry("!replacement:example.org")

        var result = applyMatrixRoomListUpdates(
            current = emptyList(),
            updates = listOf(MatrixRoomListUpdate.Append(listOf(first, second, third)))
        )
        result = applyMatrixRoomListUpdates(
            current = result,
            updates = listOf(
                MatrixRoomListUpdate.Set(1, replacement),
                MatrixRoomListUpdate.PopFront,
                MatrixRoomListUpdate.PopBack
            )
        )
        assertEquals(listOf(replacement), result)

        result = applyMatrixRoomListUpdates(
            current = result,
            updates = listOf(
                MatrixRoomListUpdate.PushBack(first),
                MatrixRoomListUpdate.PushBack(second),
                MatrixRoomListUpdate.Truncate(2)
            )
        )
        assertEquals(listOf(replacement, first), result)

        result = applyMatrixRoomListUpdates(
            current = result,
            updates = listOf(MatrixRoomListUpdate.Reset(listOf(third, second)))
        )
        assertEquals(listOf(third, second), result)

        result = applyMatrixRoomListUpdates(
            current = result,
            updates = listOf(MatrixRoomListUpdate.Clear)
        )
        assertEquals(emptyList<MatrixRoomListEntry>(), result)
    }

    @Test
    fun malformedIndexesAreRejectedBecauseThePositionalBaseIsLost() {
        val first = entry("!first:example.org")

        val error = runCatching {
            applyMatrixRoomListUpdates(
                current = listOf(first),
                updates = listOf(
                    MatrixRoomListUpdate.Set(
                        index = 50,
                        value = entry("!ignored:example.org")
                    )
                )
            )
        }.exceptionOrNull()

        check(error is IllegalArgumentException)
    }

    @Test
    fun duplicateStableIdsAreRejectedInsteadOfChangingPositionalLength() {
        val first = entry("!first:example.org")

        val error = runCatching {
            applyMatrixRoomListUpdates(
                current = listOf(first),
                updates = listOf(MatrixRoomListUpdate.PushBack(first))
            )
        }.exceptionOrNull()

        check(error is IllegalStateException)
    }

    @Test
    fun terminalStateRequiresKnownMaximumToBeLoaded() {
        assertEquals(
            false,
            MatrixRoomListSnapshot(
                rooms = listOf(room("!room:example.org")),
                isKnown = true,
                maximumNumberOfRooms = null
            ).endReached
        )
        assertEquals(
            true,
            MatrixRoomListSnapshot(
                rooms = listOf(room("!room:example.org")),
                isKnown = true,
                maximumNumberOfRooms = 2,
                loadedEntryCount = 2
            ).endReached
        )
    }

    private fun room(roomId: String): MatrixRoomSummary {
        return MatrixRoomSummary(id = roomId, displayName = roomId, avatarUrl = null)
    }

    private fun entry(roomId: String): MatrixRoomListEntry {
        return MatrixRoomListEntry(id = roomId, room = room(roomId))
    }
}
