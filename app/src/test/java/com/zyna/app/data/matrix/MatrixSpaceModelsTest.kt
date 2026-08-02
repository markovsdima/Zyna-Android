package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixSpaceModelsTest {
    @Test
    fun bulkSpaceLeaveAlwaysDeduplicatesAndLeavesRootLast() {
        assertEquals(
            listOf("!one:example.org", "!two:example.org", "!space:example.org"),
            matrixSpaceLeaveOrder(
                spaceId = "!space:example.org",
                roomIds = listOf(
                    "!space:example.org",
                    "!one:example.org",
                    "!one:example.org",
                    "!two:example.org"
                )
            )
        )
    }

    @Test
    fun roomListSpaceInviteKeepsMembershipAcrossPreviewMapping() {
        val summary = MatrixRoomSummary(
            id = "!invite:example.org",
            displayName = "Invited story",
            avatarUrl = null,
            isSpace = true,
            membership = MatrixSpaceMembership.INVITED
        )

        val preview = summary.toSpaceRoom()

        assertEquals(MatrixSpaceMembership.INVITED, preview.membership)
        assertEquals(
            MatrixSpaceMembership.JOINED,
            preview.toJoinedRoomSummary().membership
        )
    }

    @Test
    fun joinedGroupSummaryBecomesAnOptimisticSpaceChild() {
        val summary = MatrixRoomSummary(
            id = "!chat:example.org",
            displayName = "Chat",
            avatarUrl = "mxc://example.org/avatar",
            membership = MatrixSpaceMembership.JOINED,
            roomDetails = MatrixRoomDetails(
                roomId = "!chat:example.org",
                displayName = "Chat",
                avatarUrl = null,
                directUserId = null,
                kind = MatrixRoomKind.GROUP,
                topic = "Topic",
                joinedMemberCount = 7L,
                encryption = MatrixRoomEncryption.ENCRYPTED,
                access = MatrixRoomAccess.RESTRICTED,
                historyVisibility = MatrixRoomHistoryVisibility.SHARED,
                pinnedEventCount = 0,
                canonicalAlias = "#chat:example.org"
            )
        )

        val child = summary.toJoinedSpaceChild()

        assertEquals(MatrixSpaceRoomKind.ROOM, child.kind)
        assertEquals(MatrixSpaceMembership.JOINED, child.membership)
        assertEquals(MatrixSpaceJoinRule.RESTRICTED, child.joinRule)
        assertEquals(7L, child.joinedMemberCount)
    }

    @Test
    fun vectorUpdatesPreserveSdkOrderAndApplyEveryMutationKind() {
        val a = spaceRoom("a")
        val b = spaceRoom("b")
        val c = spaceRoom("c")
        val d = spaceRoom("d")

        val result = applyMatrixSpaceListUpdates(
            current = listOf(a, b),
            updates = listOf(
                MatrixSpaceListUpdate.Set(1, c),
                MatrixSpaceListUpdate.Insert(1, d),
                MatrixSpaceListUpdate.Remove(0),
                MatrixSpaceListUpdate.PushFront(a),
                MatrixSpaceListUpdate.PushBack(b),
                MatrixSpaceListUpdate.PopFront,
                MatrixSpaceListUpdate.PopBack,
                MatrixSpaceListUpdate.Append(listOf(a, b)),
                MatrixSpaceListUpdate.Truncate(3)
            )
        )

        assertEquals(listOf(d, c, a), result)
    }

    @Test
    fun resetClearAndInvalidIndicesAreSafe() {
        val a = spaceRoom("a")
        val b = spaceRoom("b")

        val reset = applyMatrixSpaceListUpdates(
            current = emptyList(),
            updates = listOf(
                MatrixSpaceListUpdate.Remove(8),
                MatrixSpaceListUpdate.Set(4, a),
                MatrixSpaceListUpdate.Reset(listOf(a, b, a))
            )
        )
        val cleared = applyMatrixSpaceListUpdates(
            current = reset,
            updates = listOf(
                MatrixSpaceListUpdate.Clear,
                MatrixSpaceListUpdate.PopFront,
                MatrixSpaceListUpdate.PopBack
            )
        )

        assertEquals(listOf(a, b), reset)
        assertEquals(emptyList<MatrixSpaceRoom>(), cleared)
    }
}

internal fun spaceRoom(
    id: String,
    kind: MatrixSpaceRoomKind = MatrixSpaceRoomKind.SPACE,
    membership: MatrixSpaceMembership = MatrixSpaceMembership.JOINED,
    displayName: String = id
): MatrixSpaceRoom {
    return MatrixSpaceRoom(
        roomId = id,
        displayName = displayName,
        avatarUrl = null,
        topic = null,
        kind = kind,
        membership = membership,
        joinedMemberCount = 1L,
        childrenCount = 0L,
        canonicalAlias = null,
        joinRule = MatrixSpaceJoinRule.UNKNOWN,
        worldReadable = null,
        guestCanJoin = false,
        isDirect = false,
        isDm = false,
        via = emptyList()
    )
}
