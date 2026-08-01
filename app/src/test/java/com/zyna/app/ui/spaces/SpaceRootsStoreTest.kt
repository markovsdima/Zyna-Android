package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceListUpdate
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.spaceRoom
import kotlin.coroutines.CoroutineContext
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

private const val ROOTS_USER_A = "@a:example.org"
private const val ROOTS_USER_B = "@b:example.org"

class SpaceRootsStoreTest {
    @Test
    fun cachedRootsAreTheFirstFrameBeforeLiveStarts() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        val cached = MatrixSpaceListSnapshot(
            rooms = listOf(spaceRoom("cached")),
            isKnown = true,
            endReached = true
        )
        fixture.cache(ROOTS_USER_A).value = cached
        try {
            fixture.store.activate(ROOTS_USER_A)

            assertEquals(cached, fixture.store.state.value.snapshot)
            assertTrue(fixture.store.state.value.isKnown)
            assertEquals("cached", fixture.store.state.value.spaces.single().roomId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun liveDiffsWriteThroughCacheAndPreserveOrder() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        try {
            fixture.store.activate(ROOTS_USER_A)
            fixture.store.enableLive(ROOTS_USER_A)
            fixture.readiness.value = true
            awaitSpaceCondition { fixture.live(ROOTS_USER_A).subscriptionCount.value > 0 }

            fixture.live(ROOTS_USER_A).emit(
                listOf(
                    MatrixSpaceListUpdate.Reset(
                        listOf(spaceRoom("first"), spaceRoom("second"))
                    )
                )
            )
            awaitSpaceCondition { fixture.store.state.value.spaces.size == 2 }
            fixture.live(ROOTS_USER_A).emit(
                listOf(MatrixSpaceListUpdate.PushFront(spaceRoom("new")))
            )
            awaitSpaceCondition {
                fixture.store.state.value.spaces.map(MatrixSpaceRoom::roomId) ==
                    listOf("new", "first", "second")
            }

            assertTrue(fixture.writes.all { it.first == ROOTS_USER_A })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun liveDiffsCannotEraseCacheBeforeRoomListIsLoaded() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        val cached = MatrixSpaceListSnapshot(
            rooms = listOf(spaceRoom("cached")),
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1L
        )
        fixture.cache(ROOTS_USER_A).value = cached
        try {
            fixture.store.activate(ROOTS_USER_A)
            fixture.store.enableLive(ROOTS_USER_A)
            yield()

            assertEquals(0, fixture.live(ROOTS_USER_A).subscriptionCount.value)
            assertTrue(fixture.writes.isEmpty())
            assertEquals(listOf("cached"), fixture.store.state.value.spaces.map { it.roomId })

            fixture.currentRooms[ROOTS_USER_A] = listOf(spaceRoom("cached"))
            fixture.readiness.value = true
            awaitSpaceCondition { fixture.live(ROOTS_USER_A).subscriptionCount.value > 0 }

            assertTrue(fixture.writes.isEmpty())
            assertEquals(listOf("cached"), fixture.store.state.value.spaces.map { it.roomId })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun freshLoadedEmptySnapshotAuthoritativelyClearsRootCache() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        fixture.cache(ROOTS_USER_A).value = MatrixSpaceListSnapshot(
            rooms = listOf(spaceRoom("cached")),
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1L
        )
        try {
            fixture.store.activate(ROOTS_USER_A)
            fixture.store.enableLive(ROOTS_USER_A)
            fixture.readiness.value = true
            awaitSpaceCondition { fixture.store.state.value.spaces.isEmpty() }

            assertTrue(fixture.writes.last().second.rooms.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun duplicateLiveContentDoesNotRewriteRootCache() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        try {
            fixture.store.activate(ROOTS_USER_A)
            fixture.store.enableLive(ROOTS_USER_A)
            fixture.currentRooms[ROOTS_USER_A] = listOf(spaceRoom("same"))
            fixture.readiness.value = true
            awaitSpaceCondition { fixture.live(ROOTS_USER_A).subscriptionCount.value > 0 }
            val reset = listOf(
                MatrixSpaceListUpdate.Reset(listOf(spaceRoom("same")))
            )

            fixture.live(ROOTS_USER_A).emit(reset)
            awaitSpaceCondition { fixture.writes.size == 1 }
            fixture.live(ROOTS_USER_A).emit(reset)
            yield()

            assertEquals(1, fixture.writes.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedAuthoritativeLoadRetriesBeforeAcceptingLiveDiffs() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        fixture.cache(ROOTS_USER_A).value = MatrixSpaceListSnapshot(
            rooms = listOf(spaceRoom("cached")),
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1L
        )
        fixture.currentRooms[ROOTS_USER_A] = listOf(spaceRoom("authoritative"))
        fixture.currentLoadFailures = 1
        fixture.expectedWarningCount = 1
        try {
            fixture.store.activate(ROOTS_USER_A)
            fixture.store.enableLive(ROOTS_USER_A)
            fixture.readiness.value = true

            awaitSpaceCondition {
                fixture.store.state.value.spaces.singleOrNull()?.roomId == "authoritative" &&
                    fixture.live(ROOTS_USER_A).subscriptionCount.value > 0
            }

            assertEquals(
                listOf("authoritative"),
                fixture.writes.last().second.rooms.map(MatrixSpaceRoom::roomId)
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun switchingAccountRejectsLateOldSessionUpdates() = runBlocking {
        val fixture = SpaceRootsFixture(coroutineContext)
        try {
            fixture.store.activate(ROOTS_USER_A)
            fixture.store.enableLive(ROOTS_USER_A)
            fixture.readiness.value = true
            awaitSpaceCondition { fixture.live(ROOTS_USER_A).subscriptionCount.value > 0 }

            fixture.store.activate(ROOTS_USER_B)
            fixture.store.enableLive(ROOTS_USER_B)
            awaitSpaceCondition {
                fixture.live(ROOTS_USER_A).subscriptionCount.value == 0 &&
                    fixture.live(ROOTS_USER_B).subscriptionCount.value > 0
            }
            fixture.live(ROOTS_USER_A).emit(
                listOf(MatrixSpaceListUpdate.Reset(listOf(spaceRoom("stale"))))
            )
            fixture.live(ROOTS_USER_B).emit(
                listOf(MatrixSpaceListUpdate.Reset(listOf(spaceRoom("current"))))
            )
            awaitSpaceCondition {
                fixture.store.state.value.spaces.singleOrNull()?.roomId == "current"
            }

            assertFalse(fixture.writes.any { (userId, snapshot) ->
                userId == ROOTS_USER_A && snapshot.rooms.any { it.roomId == "stale" }
            })
        } finally {
            fixture.close()
        }
    }
}

private class SpaceRootsFixture(parentContext: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)
    private val cacheByUser = mutableMapOf<String, MutableStateFlow<MatrixSpaceListSnapshot>>()
    private val liveByUser =
        mutableMapOf<String, MutableSharedFlow<List<MatrixSpaceListUpdate>>>()
    val readiness = MutableStateFlow(false)
    val currentRooms = mutableMapOf<String, List<MatrixSpaceRoom>>()

    val writes = mutableListOf<Pair<String, MatrixSpaceListSnapshot>>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var currentLoadFailures = 0
    var expectedWarningCount = 0

    val store = SpaceRootsStore(
        scope = scope,
        driver = SpaceRootsDriver(
            peekCache = { userId -> cache(userId).value.takeIf { it.isKnown } },
            observeCache = ::cache,
            observeReadiness = { readiness },
            loadCurrent = { userId ->
                if (currentLoadFailures > 0) {
                    currentLoadFailures -= 1
                    error("current roots unavailable")
                }
                currentRooms[userId].orEmpty()
            },
            observeLiveUpdates = ::live,
            cacheSnapshot = { userId, snapshot ->
                writes += userId to snapshot
                cache(userId).value = snapshot
            }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    fun cache(userId: String): MutableStateFlow<MatrixSpaceListSnapshot> {
        return cacheByUser.getOrPut(userId) { MutableStateFlow(MatrixSpaceListSnapshot()) }
    }

    fun live(userId: String): MutableSharedFlow<List<MatrixSpaceListUpdate>> {
        return liveByUser.getOrPut(userId) {
            MutableSharedFlow(extraBufferCapacity = 8)
        }
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

internal suspend fun awaitSpaceCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) yield()
    }
}
