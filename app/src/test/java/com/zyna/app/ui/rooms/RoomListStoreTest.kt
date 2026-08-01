package com.zyna.app.ui.rooms

import com.zyna.app.data.matrix.MatrixRoomListSession
import com.zyna.app.data.matrix.MatrixRoomListSnapshot
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST_USER_ID = "@first:example.org"
private const val SECOND_USER_ID = "@second:example.org"

class RoomListStoreTest {
    @Test
    fun activationWaitsUntilTheInitialCacheSnapshotIsPublished() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val cachedRooms = MutableSharedFlow<List<MatrixRoomSummary>>(replay = 1)
        val cached = room("!cached:example.org")
        val store = RoomListStore(
            scope = scope,
            driver = RoomListDriver(
                observeCachedRooms = { cachedRooms },
                openLive = { error("Live room list is not expected") },
                cacheSnapshot = { _, _, _, _, _ -> }
            )
        )
        try {
            val activation = async { store.activate(FIRST_USER_ID) }
            awaitRoomListCondition { cachedRooms.subscriptionCount.value == 1 }

            assertFalse(activation.isCompleted)
            cachedRooms.emit(listOf(cached))
            activation.await()

            assertEquals(listOf(cached), store.state.value.rooms)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun activatePublishesCachedRoomsBeforeLiveStarts() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val cached = room("!cached:example.org")
        fixture.cachedRooms(FIRST_USER_ID).value = listOf(cached)
        try {
            fixture.store.activate(FIRST_USER_ID)

            assertEquals(listOf(cached), fixture.store.state.value.rooms)
            assertFalse(fixture.store.state.value.isSynchronizing)
            assertTrue(fixture.openedSessions.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun knownLiveSnapshotIsWrittenThroughWithItsCompletionState() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val first = room("!first:example.org")
        val second = room("!second:example.org")
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)

            assertTrue(fixture.store.state.value.isSynchronizing)
            session.snapshotsState.value = MatrixRoomListSnapshot(
                rooms = listOf(first),
                isKnown = true,
                maximumNumberOfRooms = 2,
                revision = 1
            )
            awaitRoomListCondition { fixture.cachedWrites.size == 1 }
            session.snapshotsState.value = MatrixRoomListSnapshot(
                rooms = listOf(first, second),
                updatedRoomIds = setOf(second.id),
                isKnown = true,
                maximumNumberOfRooms = 2,
                revision = 2
            )
            awaitRoomListCondition {
                fixture.cachedWrites.size == 2 &&
                    fixture.store.state.value.rooms == listOf(first, second)
            }

            assertEquals(false, fixture.cachedWrites[0].isComplete)
            assertEquals(true, fixture.cachedWrites[1].isComplete)
            assertEquals(setOf(first.id), fixture.cachedWrites[0].updatedRoomIds)
            assertEquals(setOf(second.id), fixture.cachedWrites[1].updatedRoomIds)
            assertEquals(listOf(1L, 2L), session.acknowledgedRevisions)
            assertEquals(listOf(first, second), fixture.store.state.value.rooms)
            assertFalse(fixture.store.state.value.isSynchronizing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun duplicateLiveSnapshotDoesNotRewriteCache() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val snapshot = MatrixRoomListSnapshot(
            rooms = listOf(room("!room:example.org")),
            isKnown = true,
            maximumNumberOfRooms = 1
        )
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)
            session.snapshotsState.value = snapshot
            awaitRoomListCondition { fixture.cachedWrites.size == 1 }

            session.snapshotsState.value = snapshot.copy()
            yield()

            assertEquals(1, fixture.cachedWrites.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun visibleRoomsAreDebouncedAndSubscribedWithPrefetchedIds() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)

            fixture.store.updateVisibleRooms("rooms", listOf("!old:example.org"))
            fixture.store.updateVisibleRooms(
                "rooms",
                listOf("!visible:example.org", "!prefetched:example.org")
            )
            awaitRoomListCondition(timeoutMillis = 1_500L) {
                session.subscriptions.isNotEmpty()
            }

            assertEquals(
                listOf(listOf("!visible:example.org", "!prefetched:example.org")),
                session.subscriptions
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun visibleCachedTailRequestsAnotherSdkPageOnlyOncePerLoadedCount() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val live = room("!live:example.org")
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)
            session.snapshotsState.value = MatrixRoomListSnapshot(
                rooms = listOf(live),
                isKnown = true,
                maximumNumberOfRooms = 3
            )
            awaitRoomListCondition { fixture.cachedWrites.isNotEmpty() }

            fixture.store.updateVisibleRooms("rooms", listOf("!cached-tail:example.org"))
            fixture.store.updateVisibleRooms("rooms", listOf("!cached-tail:example.org"))
            awaitRoomListCondition { session.loadMoreCount == 1 }
            yield()

            assertEquals(1, session.loadMoreCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleViewportCannotClearTheCurrentScreenSubscription() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)

            fixture.store.updateVisibleRooms("rooms", listOf("!root:example.org"))
            fixture.store.updateVisibleRooms("forward", listOf("!forward:example.org"))
            fixture.store.clearVisibleRooms("rooms")
            awaitRoomListCondition(timeoutMillis = 1_500L) {
                session.subscriptions.isNotEmpty()
            }

            assertEquals(listOf(listOf("!forward:example.org")), session.subscriptions)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun hiddenSourceEntriesStillPaginateUntilThereAreEnoughRenderableChats() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)

            session.snapshotsState.value = MatrixRoomListSnapshot(
                rooms = emptyList(),
                isKnown = true,
                maximumNumberOfRooms = 80,
                loadedEntryCount = 40
            )
            awaitRoomListCondition { session.loadMoreCount == 1 }

            assertEquals(1, session.loadMoreCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun processorFailureClosesPoisonedSessionAndOpensFreshProjection() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)

            session.failuresFlow.emit(IllegalStateException("bad update"))
            awaitRoomListCondition(timeoutMillis = 2_000L) {
                fixture.warnings.isNotEmpty() &&
                    fixture.openedSessions[FIRST_USER_ID] !== session
            }

            assertEquals("Failed to process a Matrix room-list update", fixture.warnings.single().first)
            assertTrue(session.closed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun repeatedOpenFailureTripsCircuitBreakerInsteadOfRetryingForever() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        fixture.openFailure = IllegalStateException("persistent SDK failure")
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            awaitRoomListCondition(timeoutMillis = 3_000L) {
                fixture.warnings.size == 3 && !fixture.store.state.value.isSynchronizing
            }
            delay(750L)

            assertEquals(3, fixture.openAttemptCount)
            assertEquals(3, fixture.warnings.size)
            assertFalse(fixture.store.state.value.isSynchronizing)
            assertTrue(fixture.store.state.value.hasSynchronizationError)

            fixture.openFailure = null
            fixture.store.retrySynchronization()
            val recoveredSession = fixture.awaitSession(FIRST_USER_ID)
            recoveredSession.snapshotsState.value = MatrixRoomListSnapshot(
                isKnown = true,
                maximumNumberOfRooms = 0,
                revision = 1
            )
            awaitRoomListCondition {
                !fixture.store.state.value.isSynchronizing &&
                    !fixture.store.state.value.hasSynchronizationError
            }

            assertEquals(4, fixture.openAttemptCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialSnapshotCarriesKnownExcludedRoomsToCache() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val excludedRoomId = "!left:example.org"
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val session = fixture.awaitSession(FIRST_USER_ID)

            session.snapshotsState.value = MatrixRoomListSnapshot(
                rooms = emptyList(),
                excludedRoomIds = setOf(excludedRoomId),
                isKnown = true,
                maximumNumberOfRooms = 80,
                loadedEntryCount = 40,
                revision = 1
            )
            awaitRoomListCondition { fixture.cachedWrites.isNotEmpty() }

            assertEquals(setOf(excludedRoomId), fixture.cachedWrites.single().excludedRoomIds)
            assertFalse(fixture.cachedWrites.single().isComplete)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun switchingAccountsClosesOldLiveOwnerAndRejectsItsLateSnapshot() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            val firstSession = fixture.awaitSession(FIRST_USER_ID)

            fixture.store.activate(SECOND_USER_ID)
            firstSession.snapshotsState.value = MatrixRoomListSnapshot(
                rooms = listOf(room("!stale:example.org")),
                isKnown = true,
                maximumNumberOfRooms = 1
            )
            yield()

            assertTrue(firstSession.closed)
            assertTrue(fixture.cachedWrites.isEmpty())
            assertEquals(RoomListState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }
}

private data class CachedWrite(
    val userId: String,
    val rooms: List<MatrixRoomSummary>,
    val updatedRoomIds: Set<String>,
    val excludedRoomIds: Set<String>,
    val isComplete: Boolean
)

private class RoomListStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)
    private val cachedRoomsByUserId =
        mutableMapOf<String, MutableStateFlow<List<MatrixRoomSummary>>>()

    val openedSessions = mutableMapOf<String, FakeRoomListSession>()
    val cachedWrites = mutableListOf<CachedWrite>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var openAttemptCount = 0
    var openFailure: Throwable? = null

    val store = RoomListStore(
        scope = scope,
        driver = RoomListDriver(
            observeCachedRooms = ::cachedRooms,
            openLive = { userId ->
                openAttemptCount += 1
                openFailure?.let { error -> throw error }
                FakeRoomListSession().also { session -> openedSessions[userId] = session }
            },
            cacheSnapshot = { userId, rooms, updatedRoomIds, excludedRoomIds, isComplete ->
                cachedWrites += CachedWrite(
                    userId,
                    rooms,
                    updatedRoomIds,
                    excludedRoomIds,
                    isComplete
                )
                cachedRooms(userId).value = rooms
            }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    fun cachedRooms(userId: String): MutableStateFlow<List<MatrixRoomSummary>> {
        return cachedRoomsByUserId.getOrPut(userId) { MutableStateFlow(emptyList()) }
    }

    suspend fun awaitSession(userId: String): FakeRoomListSession {
        awaitRoomListCondition { userId in openedSessions }
        return requireNotNull(openedSessions[userId])
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private class FakeRoomListSession : MatrixRoomListSession {
    val snapshotsState = MutableStateFlow(MatrixRoomListSnapshot())
    override val snapshots: StateFlow<MatrixRoomListSnapshot> = snapshotsState
    val failuresFlow = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 7)
    override val failures: SharedFlow<Throwable> = failuresFlow
    val subscriptions = mutableListOf<List<String>>()
    val acknowledgedRevisions = mutableListOf<Long>()
    var loadMoreCount = 0
    var closed = false

    override suspend fun loadMore() {
        loadMoreCount += 1
    }

    override suspend fun subscribeToRooms(roomIds: List<String>) {
        subscriptions += roomIds
    }

    override suspend fun acknowledgeCached(revision: Long) {
        acknowledgedRevisions += revision
    }

    override suspend fun close() {
        closed = true
    }
}

private fun room(roomId: String): MatrixRoomSummary {
    return MatrixRoomSummary(id = roomId, displayName = roomId, avatarUrl = null)
}

private suspend fun awaitRoomListCondition(
    timeoutMillis: Long = 1_000L,
    condition: () -> Boolean
) {
    withTimeout(timeoutMillis) {
        while (!condition()) yield()
    }
}
