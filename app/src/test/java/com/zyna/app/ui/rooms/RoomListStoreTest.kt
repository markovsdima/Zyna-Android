package com.zyna.app.ui.rooms

import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun activateObservesCachedRoomsForSession() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val room = room("!cached:example.org")
        fixture.cachedRooms(FIRST_USER_ID).value = listOf(room)
        try {
            fixture.store.activate(FIRST_USER_ID)
            awaitRoomListCondition { fixture.store.state.value.rooms == listOf(room) }

            assertFalse(fixture.store.state.value.isSynchronizing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun enablingReactiveSyncLoadsCachesAndPublishesInitialSnapshot() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val room = room("!live:example.org")
        fixture.loadBehavior = {
            loadStarted.complete(Unit)
            releaseLoad.await()
            listOf(room)
        }
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            loadStarted.await()

            assertTrue(fixture.store.state.value.isSynchronizing)
            releaseLoad.complete(Unit)
            awaitRoomListCondition {
                fixture.store.state.value.rooms == listOf(room) &&
                    !fixture.store.state.value.isSynchronizing
            }

            assertEquals(listOf(FIRST_USER_ID to listOf(room)), fixture.cachedSnapshots)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun snapshotFailureDoesNotStopLaterReactiveSignals() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val room = room("!recovered:example.org")
        fixture.loadBehavior = {
            if (fixture.snapshotLoadCount == 1) {
                error("snapshot failed")
            }
            listOf(room)
        }
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            awaitRoomListCondition { fixture.warnings.isNotEmpty() }
            awaitRoomListCondition { fixture.changeSignals.subscriptionCount.value > 0 }

            fixture.changeSignals.emit(Unit)
            awaitRoomListCondition { fixture.store.state.value.rooms == listOf(room) }

            assertEquals(2, fixture.snapshotLoadCount)
            assertEquals(
                "Failed to synchronize room list snapshot",
                fixture.warnings.single().first
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun switchingSessionRejectsCancellationSwallowingSnapshot() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        fixture.loadBehavior = {
            loadStarted.complete(Unit)
            try {
                releaseLoad.await()
                listOf(room("!unexpected:example.org"))
            } catch (_: CancellationException) {
                listOf(room("!stale:example.org"))
            }
        }
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            loadStarted.await()

            fixture.store.activate(SECOND_USER_ID)
            releaseLoad.complete(Unit)
            yield()

            assertTrue(fixture.cachedSnapshots.isEmpty())
            assertEquals(RoomListState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deactivateClearsStateAndIgnoresOldCache() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val room = room("!cached:example.org")
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.cachedRooms(FIRST_USER_ID).value = listOf(room)
            awaitRoomListCondition { fixture.store.state.value.rooms == listOf(room) }

            fixture.store.deactivate()
            fixture.cachedRooms(FIRST_USER_ID).value = listOf(room("!late:example.org"))
            yield()

            assertEquals(RoomListState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun enablingReactiveSyncTwiceKeepsSingleInitialSync() = runBlocking {
        val fixture = RoomListStoreFixture(coroutineContext)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        fixture.loadBehavior = {
            loadStarted.complete(Unit)
            releaseLoad.await()
            emptyList()
        }
        try {
            fixture.store.activate(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            fixture.store.enableReactiveSynchronization(FIRST_USER_ID)
            loadStarted.await()

            assertEquals(1, fixture.snapshotLoadCount)
            releaseLoad.complete(Unit)
            awaitRoomListCondition { !fixture.store.state.value.isSynchronizing }
        } finally {
            fixture.close()
        }
    }
}

private class RoomListStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)
    private val cachedRoomsByUserId =
        mutableMapOf<String, MutableStateFlow<List<MatrixRoomSummary>>>()

    val changeSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val cachedSnapshots = mutableListOf<Pair<String, List<MatrixRoomSummary>>>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var snapshotLoadCount = 0
    var loadBehavior: suspend () -> List<MatrixRoomSummary> = { emptyList() }

    val store = RoomListStore(
        scope = scope,
        driver = RoomListDriver(
            observeCachedRooms = ::cachedRooms,
            observeChangeSignals = { changeSignals },
            loadSnapshot = {
                snapshotLoadCount += 1
                loadBehavior()
            },
            cacheSnapshot = { userId, rooms ->
                cachedSnapshots += userId to rooms
                cachedRooms(userId).value = rooms
            }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    fun cachedRooms(userId: String): MutableStateFlow<List<MatrixRoomSummary>> {
        return cachedRoomsByUserId.getOrPut(userId) { MutableStateFlow(emptyList()) }
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun room(roomId: String): MatrixRoomSummary {
    return MatrixRoomSummary(
        id = roomId,
        displayName = roomId,
        avatarUrl = null
    )
}

private suspend fun awaitRoomListCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
