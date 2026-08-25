package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRoomSummaryTest {
    @Test
    fun kindPrioritizesSpaceThenDirectThenGroup() {
        assertEquals(MatrixRoomKind.SPACE, room(isSpace = true, directUserId = "@alice:example.org").kind)
        assertEquals(MatrixRoomKind.DIRECT, room(directUserId = "@alice:example.org").kind)
        assertEquals(MatrixRoomKind.GROUP, room().kind)
    }
}

private fun room(
    isSpace: Boolean = false,
    directUserId: String? = null
): MatrixRoomSummary {
    return MatrixRoomSummary(
        id = "!room:example.org",
        displayName = "Room",
        avatarUrl = null,
        directUserId = directUserId,
        isSpace = isSpace
    )
}
