package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixLeaveSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceLeaveSession
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.spaceRoom
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceLeaveStoreTest {
    @Test
    fun defaultsExcludeDmAndRoomsThatNeedAnotherOwner() = runBlocking {
        val root = leaveRoom("!space:example.org")
        val ordinary = leaveRoom("!ordinary:example.org", kind = MatrixSpaceRoomKind.ROOM)
        val dm = leaveRoom("!dm:example.org", kind = MatrixSpaceRoomKind.ROOM, isDm = true)
        val lastOwner = leaveRoom(
            "!owned:example.org",
            kind = MatrixSpaceRoomKind.ROOM,
            isLastOwner = true,
            joinedMemberCount = 2L
        )
        val fixture = SpaceLeaveFixture(coroutineContext, listOf(root, ordinary, dm, lastOwner))
        try {
            fixture.store.activate(spaceTarget())
            awaitSpaceLeave { !fixture.store.state.value.isLoading }
            val state = fixture.store.state.value

            assertEquals(setOf("!ordinary:example.org"), state.selectedRoomIds)
            assertEquals(
                listOf("!ordinary:example.org", "!owned:example.org"),
                state.descendants.map { it.value.room.roomId }
            )
            assertFalse(state.descendants.last().isSelectable)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rootLastOwnerBlocksLeavingUntilOwnershipChanges() = runBlocking {
        val fixture = SpaceLeaveFixture(
            coroutineContext,
            listOf(leaveRoom("!space:example.org", isLastOwner = true, joinedMemberCount = 2L))
        )
        try {
            fixture.store.activate(spaceTarget())
            awaitSpaceLeave { !fixture.store.state.value.isLoading }
            fixture.store.leave()
            yield()

            assertTrue(fixture.store.state.value.needsOwnerChange)
            assertFalse(fixture.store.state.value.canLeave)
            assertTrue(fixture.session.leaveCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectedDescendantsAreDelegatedAndRootIsReportedAsLeft() = runBlocking {
        val fixture = SpaceLeaveFixture(
            coroutineContext,
            listOf(
                leaveRoom("!space:example.org"),
                leaveRoom("!one:example.org", kind = MatrixSpaceRoomKind.ROOM),
                leaveRoom("!two:example.org", kind = MatrixSpaceRoomKind.ROOM)
            )
        )
        try {
            fixture.store.activate(spaceTarget())
            awaitSpaceLeave { !fixture.store.state.value.isLoading }
            fixture.store.toggle("!two:example.org")
            fixture.store.leave()
            awaitSpaceLeave { fixture.leftRoomIds.isNotEmpty() }

            assertEquals(listOf(listOf("!one:example.org")), fixture.session.leaveCalls)
            assertEquals(
                setOf("!space:example.org", "!one:example.org"),
                fixture.leftRoomIds.single()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialWriteFailureRefreshesGraphAndReportsRoomsThatWereLeft() = runBlocking {
        val root = leaveRoom("!space:example.org")
        val one = leaveRoom("!one:example.org", kind = MatrixSpaceRoomKind.ROOM)
        val two = leaveRoom("!two:example.org", kind = MatrixSpaceRoomKind.ROOM)
        val fixture = SpaceLeaveFixture(
            parentContext = coroutineContext,
            rooms = listOf(root, one, two),
            refreshedRooms = listOf(root, two),
            leaveFailure = IllegalStateException("transport failed during hierarchy leave")
        )
        try {
            fixture.store.activate(spaceTarget())
            awaitSpaceLeave { !fixture.store.state.value.isLoading }
            fixture.store.leave()
            awaitSpaceLeave {
                fixture.store.state.value.error == SpaceLeaveError.PARTIAL_LEAVE
            }

            val state = fixture.store.state.value
            assertTrue(fixture.leftRoomIds.isEmpty())
            assertEquals(listOf(setOf("!one:example.org")), fixture.partiallyLeftRoomIds)
            assertEquals(setOf("!two:example.org"), state.selectedRoomIds)
            assertEquals(
                listOf("!two:example.org"),
                state.descendants.map { it.value.room.roomId }
            )
            fixture.store.toggle("!two:example.org")
            assertEquals(setOf("!two:example.org"), fixture.store.state.value.selectedRoomIds)
            fixture.store.toggleAll()
            assertEquals(setOf("!two:example.org"), fixture.store.state.value.selectedRoomIds)
            assertEquals(
                listOf(listOf("!one:example.org", "!two:example.org")),
                fixture.session.leaveCalls
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun missingRootAfterWriteErrorCompletesTheLeave() = runBlocking {
        val root = leaveRoom("!space:example.org")
        val child = leaveRoom("!child:example.org", kind = MatrixSpaceRoomKind.ROOM)
        val fixture = SpaceLeaveFixture(
            parentContext = coroutineContext,
            rooms = listOf(root, child),
            refreshedRooms = emptyList(),
            leaveFailure = IllegalStateException("response lost after write")
        )
        try {
            fixture.store.activate(spaceTarget())
            awaitSpaceLeave { !fixture.store.state.value.isLoading }
            fixture.store.leave()
            awaitSpaceLeave { fixture.leftRoomIds.isNotEmpty() }

            assertEquals(
                listOf(setOf("!space:example.org", "!child:example.org")),
                fixture.leftRoomIds
            )
            assertFalse(fixture.store.state.value.isLeaving)
            assertEquals(null, fixture.store.state.value.error)
        } finally {
            fixture.close()
        }
    }
}

private class SpaceLeaveFixture(
    parentContext: CoroutineContext,
    rooms: List<MatrixLeaveSpaceRoom>,
    refreshedRooms: List<MatrixLeaveSpaceRoom>? = null,
    leaveFailure: Throwable? = null
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)
    val session = FakeSpaceLeaveSession("!space:example.org", rooms, leaveFailure)
    private val sessions = ArrayDeque<MatrixSpaceLeaveSession>().apply {
        add(session)
        refreshedRooms?.let { add(FakeSpaceLeaveSession("!space:example.org", it)) }
    }
    val leftRoomIds = mutableListOf<Set<String>>()
    val partiallyLeftRoomIds = mutableListOf<Set<String>>()

    val store = SpaceLeaveStore(
        scope = scope,
        driver = SpaceLeaveDriver { _, _ -> sessions.removeFirst() },
        onLeft = { _, roomIds -> leftRoomIds += roomIds },
        onPartiallyLeft = { _, roomIds -> partiallyLeftRoomIds += roomIds }
    )

    suspend fun close() {
        job.cancelAndJoin()
    }
}

private class FakeSpaceLeaveSession(
    override val spaceId: String,
    private val values: List<MatrixLeaveSpaceRoom>,
    private val leaveFailure: Throwable? = null
) : MatrixSpaceLeaveSession {
    val leaveCalls = mutableListOf<List<String>>()
    var closed = false

    override suspend fun rooms(): List<MatrixLeaveSpaceRoom> = values

    override suspend fun leave(roomIds: List<String>) {
        leaveCalls += roomIds
        leaveFailure?.let { throw it }
    }

    override fun close() {
        closed = true
    }
}

private fun spaceTarget(): SpaceLeaveTarget {
    return SpaceLeaveTarget(
        userId = "@alice:example.org",
        spaceId = "!space:example.org",
        parentSpaceId = null,
        displayName = "Story"
    )
}

private fun leaveRoom(
    roomId: String,
    kind: MatrixSpaceRoomKind = MatrixSpaceRoomKind.SPACE,
    isDm: Boolean = false,
    isLastOwner: Boolean = false,
    joinedMemberCount: Long = 1L
): MatrixLeaveSpaceRoom {
    return MatrixLeaveSpaceRoom(
        room = spaceRoom(roomId, kind = kind).copy(
            isDm = isDm,
            joinedMemberCount = joinedMemberCount
        ),
        isLastOwner = isLastOwner,
        areCreatorsPrivileged = false
    )
}

private suspend fun awaitSpaceLeave(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) yield()
    }
}
