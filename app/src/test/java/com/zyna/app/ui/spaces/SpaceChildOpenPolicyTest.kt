package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.spaceRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val OPEN_USER = "@alice:example.org"
private const val OPEN_SPACE = "!space:example.org"
private const val OPEN_PARENT = "!parent:example.org"
private const val OPEN_CHILD = "!child:example.org"

class SpaceChildOpenPolicyTest {
    @Test
    fun resolvesTheCurrentJoinedChildInsteadOfAStaleUiSnapshot() {
        val currentChild = spaceRoom(
            id = OPEN_CHILD,
            kind = MatrixSpaceRoomKind.ROOM,
            displayName = "Current"
        )
        val state = stateWith(currentChild)

        val result = state.joinedChildForOpen(
            userId = OPEN_USER,
            spaceId = OPEN_SPACE,
            parentSpaceId = OPEN_PARENT,
            childRoomId = OPEN_CHILD
        )

        assertEquals(currentChild, result)
    }

    @Test
    fun rejectsAChildThatIsNoLongerJoinedOrOwnedByTheCurrentRoute() {
        val leftChild = spaceRoom(
            id = OPEN_CHILD,
            kind = MatrixSpaceRoomKind.ROOM,
            membership = MatrixSpaceMembership.LEFT
        )
        val state = stateWith(leftChild)

        assertNull(
            state.joinedChildForOpen(
                userId = OPEN_USER,
                spaceId = OPEN_SPACE,
                parentSpaceId = OPEN_PARENT,
                childRoomId = OPEN_CHILD
            )
        )
        assertNull(
            state.copy(
                target = state.target?.copy(parentSpaceId = "!other:example.org")
            ).joinedChildForOpen(
                userId = OPEN_USER,
                spaceId = OPEN_SPACE,
                parentSpaceId = OPEN_PARENT,
                childRoomId = OPEN_CHILD
            )
        )
    }

    private fun stateWith(child: MatrixSpaceRoom): SpaceChildrenState {
        return SpaceChildrenState(
            target = SpaceTarget(
                userId = OPEN_USER,
                spaceId = OPEN_SPACE,
                parentSpaceId = OPEN_PARENT,
                seed = spaceRoom(OPEN_SPACE)
            ),
            chats = listOf(child),
            isKnown = true
        )
    }
}
