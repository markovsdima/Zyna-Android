package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRemoteSnapshot
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomListSession
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.spaceRoom
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CHILDREN_USER = "@alice:example.org"
private const val SPACE_A = "!space-a:example.org"
private const val SPACE_B = "!space-b:example.org"

class SpaceChildrenStoreTest {
    @Test
    fun memoryCacheIsImmediateThenLiveWritesThrough() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A, displayName = "Seed")
        val cachedChild = spaceRoom("cached", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed.copy(displayName = "Cached"),
            rooms = listOf(cachedChild),
            isKnown = true,
            endReached = false
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            assertEquals("Cached", fixture.store.state.value.space?.displayName)
            assertEquals(listOf(cachedChild), fixture.store.state.value.chats)
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 1 }

            val liveTrack = spaceRoom("track", kind = MatrixSpaceRoomKind.SPACE)
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                space = seed.copy(displayName = "Live"),
                rooms = listOf(liveTrack),
                isKnown = true,
                endReached = true
            )
            awaitSpaceCondition {
                fixture.store.state.value.space?.displayName == "Live" &&
                    fixture.store.state.value.tracks == listOf(liveTrack)
            }

            assertTrue(fixture.store.state.value.endReached)
            assertEquals(SPACE_A, fixture.writes.last().first)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun incompleteEmptyLiveResetKeepsCachedChildren() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val cachedTrack = spaceRoom("cached-track")
        val cachedChat = spaceRoom("cached-chat", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(cachedTrack, cachedChat),
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1L
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = emptyList(),
                isKnown = true,
                endReached = false
            )
            awaitSpaceCondition { fixture.writes.isNotEmpty() }

            assertEquals(listOf(cachedTrack), fixture.store.state.value.tracks)
            assertEquals(listOf(cachedChat), fixture.store.state.value.chats)
            assertEquals(
                listOf(cachedTrack, cachedChat),
                fixture.writes.last().third.rooms
            )
            assertFalse(fixture.store.state.value.endReached)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun incompleteLivePrefixPreservesCachedTailUntilTerminalSnapshot() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val oldFirst = spaceRoom("first", displayName = "Old")
        val cachedTail = spaceRoom("tail", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(oldFirst, cachedTail),
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1L
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            val refreshedFirst = oldFirst.copy(displayName = "Fresh")
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(refreshedFirst),
                isKnown = true,
                endReached = false
            )
            awaitSpaceCondition {
                fixture.store.state.value.tracks.singleOrNull()?.displayName == "Fresh"
            }

            assertEquals(listOf(cachedTail), fixture.store.state.value.chats)

            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(refreshedFirst),
                isKnown = true,
                endReached = true
            )
            awaitSpaceCondition {
                fixture.store.state.value.endReached &&
                    fixture.store.state.value.chats.isEmpty()
            }

            assertEquals(listOf(refreshedFirst), fixture.writes.last().third.rooms)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun terminalEmptyLiveSnapshotAuthoritativelyClearsCache() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(spaceRoom("cached")),
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1L
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = emptyList(),
                isKnown = true,
                endReached = true
            )
            awaitSpaceCondition {
                fixture.store.state.value.isKnown &&
                    fixture.store.state.value.endReached &&
                    fixture.store.state.value.tracks.isEmpty()
            }

            assertTrue(fixture.writes.last().third.rooms.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun nonTerminalEmptyLiveSnapshotDoesNotCreateAnEmptyFirstLoad() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        try {
            fixture.store.activate(target(SPACE_A, seed))
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = emptyList(),
                isKnown = true,
                endReached = false
            )
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 1 }
            yield()

            assertTrue(fixture.writes.isEmpty())
            assertFalse(fixture.store.state.value.isKnown)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun additionalLivePagesLoadOnlyWhenRequested() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 1 }

            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(spaceRoom("first-page")),
                isKnown = true,
                isPaginating = false,
                endReached = false
            )
            yield()
            assertEquals(1, fixture.session(SPACE_A).paginateCount)

            fixture.store.loadMore()
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 2 }

            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(spaceRoom("first-page"), spaceRoom("last-page")),
                isKnown = true,
                isPaginating = false,
                endReached = true
            )
            awaitSpaceCondition { fixture.store.state.value.endReached }
            yield()

            assertEquals(2, fixture.session(SPACE_A).paginateCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cachedContentKeepsVisibleRetryAfterPaginationFailure() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val cached = spaceRoom("cached", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(cached),
            isKnown = true,
            endReached = true
        )
        fixture.session(SPACE_A).paginateError = IllegalStateException("offline")
        fixture.expectedWarningCount = 1
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.error == SpaceLoadError.LOAD }

            assertEquals(listOf(cached), fixture.store.state.value.chats)
            assertFalse(fixture.session(SPACE_A).closed)
            assertEquals(1, fixture.warnings.size)

            fixture.session(SPACE_A).paginateError = null
            fixture.store.retry()
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 2 }

            assertEquals(null, fixture.store.state.value.error)
            assertFalse(fixture.session(SPACE_A).closed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun knownEmptyCacheIsNotReportedAsUnknown() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = emptyList(),
            isKnown = true,
            endReached = true
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.isKnown }

            assertTrue(fixture.store.state.value.tracks.isEmpty())
            assertTrue(fixture.store.state.value.chats.isEmpty())
            assertTrue(fixture.store.state.value.endReached)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun routeReplacementClosesOldHandleAndRejectsLateSnapshot() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        try {
            fixture.store.activate(target(SPACE_A, spaceRoom(SPACE_A)))
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 1 }
            val oldSession = fixture.session(SPACE_A)

            fixture.store.activate(target(SPACE_B, spaceRoom(SPACE_B)))
            awaitSpaceCondition {
                oldSession.closed && fixture.session(SPACE_B).paginateCount == 1
            }
            oldSession.snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(spaceRoom("stale")),
                isKnown = true,
                endReached = true
            )
            fixture.session(SPACE_B).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(spaceRoom("current")),
                isKnown = true,
                endReached = true
            )
            awaitSpaceCondition {
                fixture.store.state.value.tracks.singleOrNull()?.roomId == "current"
            }

            assertEquals(SPACE_B, fixture.store.state.value.target?.spaceId)
            assertFalse(fixture.writes.any { (spaceId, _, snapshot) ->
                spaceId == SPACE_A && snapshot.rooms.any { it.roomId == "stale" }
            })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun confirmedMembershipStaysVisibleUntilLiveGraphReflectsIt() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val child = spaceRoom(
            id = "child",
            kind = MatrixSpaceRoomKind.ROOM,
            membership = MatrixSpaceMembership.LEFT
        )
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(child),
            isKnown = true,
            endReached = true
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 1 }

            fixture.store.confirmChildMembership(
                userId = CHILDREN_USER,
                spaceId = SPACE_A,
                roomId = child.roomId,
                membership = MatrixSpaceMembership.JOINED
            )
            awaitSpaceCondition {
                fixture.store.state.value.chats.singleOrNull()?.membership ==
                    MatrixSpaceMembership.JOINED
            }

            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(child),
                isKnown = true,
                endReached = false
            )
            yield()
            assertEquals(
                MatrixSpaceMembership.JOINED,
                fixture.store.state.value.chats.single().membership
            )

            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(child.copy(membership = MatrixSpaceMembership.JOINED)),
                isKnown = true,
                endReached = true
            )
            yield()
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(child),
                isKnown = true,
                endReached = true
            )
            awaitSpaceCondition {
                fixture.store.state.value.chats.singleOrNull()?.membership ==
                    MatrixSpaceMembership.LEFT
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun confirmedMembershipYieldsToANewerConcreteLiveTransition() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val child = spaceRoom(
            id = "child",
            kind = MatrixSpaceRoomKind.ROOM,
            membership = MatrixSpaceMembership.LEFT
        )
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(child),
            isKnown = true,
            endReached = true
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.session(SPACE_A).paginateCount == 1 }

            fixture.store.confirmChildMembership(
                userId = CHILDREN_USER,
                spaceId = SPACE_A,
                roomId = child.roomId,
                membership = MatrixSpaceMembership.KNOCKED
            )
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                rooms = listOf(child.copy(membership = MatrixSpaceMembership.INVITED)),
                isKnown = true,
                endReached = true
            )

            awaitSpaceCondition {
                fixture.store.state.value.chats.singleOrNull()?.membership ==
                    MatrixSpaceMembership.INVITED
            }
            assertEquals(
                MatrixSpaceMembership.INVITED,
                fixture.writes.last().third.rooms.single().membership
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialRemovalHidesSuccessAndKeepsOnlyFailureSelected() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val removed = spaceRoom("removed", kind = MatrixSpaceRoomKind.ROOM)
        val failed = spaceRoom("failed", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(removed, failed),
            isKnown = true,
            endReached = true
        )
        fixture.removeBehavior = { roomId ->
            if (roomId == failed.roomId) error("server rejected removal")
        }
        fixture.expectedWarningCount = 1
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.management.canManage }
            fixture.store.enterManagement()
            fixture.store.toggleManagedRoom(removed.roomId)
            fixture.store.toggleManagedRoom(failed.roomId)
            fixture.store.requestSelectedRoomsRemoval()
            fixture.store.confirmSelectedRoomsRemoval()

            awaitSpaceCondition {
                fixture.store.state.value.management.error ==
                    SpaceChildManagementError.PARTIAL_REMOVE
            }

            val state = fixture.store.state.value
            assertEquals(listOf(failed), state.chats)
            assertTrue(state.management.isManaging)
            assertEquals(setOf(failed.roomId), state.management.selectedRoomIds)
            assertEquals(setOf(removed.roomId, failed.roomId), fixture.removalCalls.toSet())
            assertEquals(1, fixture.session(SPACE_A).resetCount)
            assertFalse(
                fixture.writes.last().third.rooms.any { it.roomId == removed.roomId }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successfulRemovalExitsManageModeAndRefreshesHierarchy() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val child = spaceRoom("child", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(child),
            isKnown = true,
            endReached = true
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.management.canManage }
            fixture.store.enterManagement()
            fixture.store.toggleManagedRoom(child.roomId)
            fixture.store.requestSelectedRoomsRemoval()
            fixture.store.confirmSelectedRoomsRemoval()

            awaitSpaceCondition {
                fixture.removalCalls == listOf(child.roomId) &&
                    !fixture.store.state.value.management.isRemoving
            }

            assertTrue(fixture.store.state.value.chats.isEmpty())
            assertFalse(fixture.store.state.value.management.isManaging)
            assertEquals(null, fixture.store.state.value.management.error)
            assertEquals(1, fixture.session(SPACE_A).resetCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refreshedGraphReconcilesRemovalThatSdkReportedAsFailed() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val child = spaceRoom("child", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(child),
            isKnown = true,
            endReached = true
        )
        fixture.removeBehavior = { error("inverse relationship update failed") }
        fixture.session(SPACE_A).resetBehavior = {
            fixture.session(SPACE_A).snapshots.value = MatrixSpaceRemoteSnapshot(
                space = seed,
                rooms = emptyList(),
                isKnown = true,
                endReached = true
            )
        }
        fixture.expectedWarningCount = 1
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.management.canManage }
            fixture.store.enterManagement()
            fixture.store.toggleManagedRoom(child.roomId)
            fixture.store.requestSelectedRoomsRemoval()
            fixture.store.confirmSelectedRoomsRemoval()

            awaitSpaceCondition {
                fixture.store.state.value.chats.isEmpty() &&
                    !fixture.store.state.value.management.isManaging &&
                    fixture.store.state.value.management.error == null
            }

            assertEquals(listOf(child.roomId), fixture.removalCalls)
            assertEquals(1, fixture.session(SPACE_A).resetCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun freshPermissionCheckPreventsRemovalAfterPermissionWasRevoked() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        val child = spaceRoom("child", kind = MatrixSpaceRoomKind.ROOM)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(child),
            isKnown = true,
            endReached = true
        )
        fixture.loadCanManageBehavior = { false }
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.management.canManage }
            fixture.store.enterManagement()
            fixture.store.toggleManagedRoom(child.roomId)
            fixture.store.requestSelectedRoomsRemoval()
            fixture.store.confirmSelectedRoomsRemoval()

            awaitSpaceCondition {
                fixture.store.state.value.management.error ==
                    SpaceChildManagementError.PERMISSION_CHANGED
            }

            assertTrue(fixture.removalCalls.isEmpty())
            assertFalse(fixture.store.state.value.management.canManage)
            assertFalse(fixture.store.state.value.management.isManaging)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun livePermissionRevocationClosesManageMode() = runBlocking {
        val fixture = SpaceChildrenFixture(coroutineContext)
        val seed = spaceRoom(SPACE_A)
        fixture.cache(CHILDREN_USER, SPACE_A).value = MatrixSpaceListSnapshot(
            space = seed,
            rooms = listOf(spaceRoom("child", kind = MatrixSpaceRoomKind.ROOM)),
            isKnown = true,
            endReached = true
        )
        try {
            fixture.store.activate(target(SPACE_A, seed))
            awaitSpaceCondition { fixture.store.state.value.management.canManage }
            fixture.store.enterManagement()
            assertTrue(fixture.store.state.value.management.isManaging)

            fixture.canManage.value = false
            awaitSpaceCondition { !fixture.store.state.value.management.canManage }

            assertFalse(fixture.store.state.value.management.isManaging)
            assertTrue(fixture.store.state.value.management.selectedRoomIds.isEmpty())
            assertEquals(
                SpaceChildManagementError.PERMISSION_CHANGED,
                fixture.store.state.value.management.error
            )

            fixture.canManage.value = true
            awaitSpaceCondition { fixture.store.state.value.management.canManage }
            assertEquals(null, fixture.store.state.value.management.error)
        } finally {
            fixture.close()
        }
    }

    private fun target(spaceId: String, seed: MatrixSpaceRoom) =
        SpaceTarget(
            userId = CHILDREN_USER,
            spaceId = spaceId,
            parentSpaceId = null,
            seed = seed
        )
}

private class SpaceChildrenFixture(parentContext: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)
    private val cacheByTarget =
        mutableMapOf<Pair<String, String>, MutableStateFlow<MatrixSpaceListSnapshot>>()
    private val sessions = mutableMapOf<String, FakeSpaceRoomListSession>()

    val writes = mutableListOf<Triple<String, String, MatrixSpaceListSnapshot>>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    val canManage = MutableStateFlow(true)
    val removalCalls = mutableListOf<String>()
    var loadCanManageBehavior: suspend () -> Boolean = { canManage.value }
    var removeBehavior: suspend (String) -> Unit = {}
    var expectedWarningCount = 0

    val store = SpaceChildrenStore(
        scope = scope,
        driver = SpaceChildrenDriver(
            peekCache = { userId, spaceId ->
                cache(userId, spaceId).value.takeIf { it.isKnown }
            },
            observeCache = ::cache,
            openLive = { _, spaceId -> session(spaceId) },
            cacheSnapshot = { userId, spaceId, snapshot ->
                writes += Triple(spaceId, userId, snapshot)
                cache(userId, spaceId).value = snapshot
            },
            observeCanManage = { canManage },
            loadCanManage = { loadCanManageBehavior() },
            removeChild = { _, _, childId ->
                removalCalls += childId
                removeBehavior(childId)
            }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    fun cache(
        userId: String,
        spaceId: String
    ): MutableStateFlow<MatrixSpaceListSnapshot> {
        return cacheByTarget.getOrPut(userId to spaceId) {
            MutableStateFlow(MatrixSpaceListSnapshot())
        }
    }

    fun session(spaceId: String): FakeSpaceRoomListSession {
        return sessions.getOrPut(spaceId) { FakeSpaceRoomListSession() }
    }

    suspend fun close() {
        try {
            assertEquals(
                "Unexpected warnings: $warnings",
                expectedWarningCount,
                warnings.size
            )
        } finally {
            job.cancelAndJoin()
        }
    }
}

private class FakeSpaceRoomListSession : MatrixSpaceRoomListSession {
    override val snapshots = MutableStateFlow(MatrixSpaceRemoteSnapshot())
    var paginateCount = 0
    var paginateError: Throwable? = null
    var resetCount = 0
    var resetBehavior: suspend () -> Unit = {}
    var closed = false

    override suspend fun paginate() {
        paginateCount += 1
        paginateError?.let { throw it }
    }

    override suspend fun reset() {
        resetCount += 1
        resetBehavior()
    }

    override fun close() {
        closed = true
    }
}
