package com.zyna.app.ui.roomdetails

import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomDetails
import com.zyna.app.data.matrix.MatrixRoomEncryption
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

private const val USER_ID = "@alice:example.org"
private const val ROOM_A = "!a:example.org"
private const val ROOM_B = "!b:example.org"

class RoomDetailsStoreTest {
    @Test
    fun activateUsesInMemoryRoomListDetailsForTheFirstFrame() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        val cached = roomDetails(ROOM_A, "Cached room")
        val seed = roomSummary(ROOM_A, "Seed room").copy(roomDetails = cached)
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), seed)

            assertEquals(cached, fixture.store.state.value.details)
            assertFalse(fixture.store.state.value.isLoading)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun activatePublishesCachedDetailsThenCollectsAndCachesLiveDetails() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        val seed = roomSummary(ROOM_A, "Seed room")
        val cached = roomDetails(ROOM_A, "Cached room")
        fixture.cachedDetails(USER_ID, ROOM_A).value = cached
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), seed)

            assertEquals(seed, fixture.store.state.value.seed)
            awaitRoomDetailsCondition { fixture.store.state.value.details == cached }
            assertFalse(fixture.store.state.value.isLoading)
            awaitRoomDetailsCondition { fixture.updates(ROOM_A).subscriptionCount.value > 0 }

            val live = roomDetails(ROOM_A, "Loaded room")
            fixture.updates(ROOM_A).emit(live)
            awaitRoomDetailsCondition {
                fixture.store.state.value.details?.displayName == "Loaded room"
            }

            assertEquals(listOf(USER_ID to live), fixture.cachedWrites)
            assertFalse(fixture.store.state.value.isLoading)
            assertNull(fixture.store.state.value.errorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingRouteCancelsOldObservationAndIgnoresOldRoomUpdates() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), roomSummary(ROOM_A, "A"))
            awaitRoomDetailsCondition { fixture.updates(ROOM_A).subscriptionCount.value > 0 }

            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_B), roomSummary(ROOM_B, "B"))
            awaitRoomDetailsCondition {
                fixture.updates(ROOM_A).subscriptionCount.value == 0 &&
                    fixture.updates(ROOM_B).subscriptionCount.value > 0
            }

            fixture.updates(ROOM_A).emit(roomDetails(ROOM_A, "Stale A"))
            fixture.updates(ROOM_B).emit(roomDetails(ROOM_B, "Loaded B"))
            awaitRoomDetailsCondition {
                fixture.store.state.value.details?.displayName == "Loaded B"
            }

            assertEquals(ROOM_B, fixture.store.state.value.target?.roomId)
            assertEquals(ROOM_B, fixture.store.state.value.details?.roomId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun activatingSameTargetUpdatesSeedWithoutRestartingObservation() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        val initialSeed = roomSummary(ROOM_A, "Seed room")
        val updatedSeed = initialSeed.copy(unreadCount = 4)
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), initialSeed)
            awaitRoomDetailsCondition { fixture.updates(ROOM_A).subscriptionCount.value > 0 }
            fixture.updates(ROOM_A).emit(roomDetails(ROOM_A, "Loaded room"))
            awaitRoomDetailsCondition { fixture.store.state.value.details != null }

            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), updatedSeed)

            assertEquals(updatedSeed, fixture.store.state.value.seed)
            assertEquals(1, fixture.liveObservationCount)
            assertEquals("Loaded room", fixture.store.state.value.details?.displayName)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failureKeepsSeedAndRefreshStartsANewObservation() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        fixture.liveBehavior = { roomId ->
            if (fixture.liveObservationCount == 1) {
                flow { error("details failed") }
            } else {
                flow { emit(roomDetails(roomId, "Recovered")) }
            }
        }
        val seed = roomSummary(ROOM_A, "Seed room")
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), seed)
            awaitRoomDetailsCondition { fixture.store.state.value.errorMessage == "details failed" }

            assertEquals(seed, fixture.store.state.value.seed)
            assertEquals("Failed to observe live room details", fixture.warnings.single().first)

            fixture.store.refresh()
            awaitRoomDetailsCondition {
                fixture.store.state.value.details?.displayName == "Recovered"
            }

            assertEquals(2, fixture.liveObservationCount)
            assertFalse(fixture.store.state.value.isLoading)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun liveFailureKeepsCachedDetailsWithoutVisibleError() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        val cached = roomDetails(ROOM_A, "Cached room")
        fixture.cachedDetails(USER_ID, ROOM_A).value = cached
        fixture.liveBehavior = { flow { error("offline") } }
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), roomSummary(ROOM_A, "A"))
            awaitRoomDetailsCondition {
                fixture.warnings.isNotEmpty() && fixture.store.state.value.details == cached
            }

            assertEquals(cached, fixture.store.state.value.details)
            assertFalse(fixture.store.state.value.isLoading)
            assertNull(fixture.store.state.value.errorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cacheWriteFailureDoesNotHideLiveDetails() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        fixture.cacheBehavior = { _, _ -> error("cache failed") }
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), roomSummary(ROOM_A, "A"))
            awaitRoomDetailsCondition { fixture.updates(ROOM_A).subscriptionCount.value > 0 }

            val live = roomDetails(ROOM_A, "Live room")
            fixture.updates(ROOM_A).emit(live)
            awaitRoomDetailsCondition { fixture.warnings.isNotEmpty() }

            assertEquals(live, fixture.store.state.value.details)
            assertFalse(fixture.store.state.value.isLoading)
            assertNull(fixture.store.state.value.errorMessage)
            assertEquals("Failed to cache room details", fixture.warnings.single().first)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deactivateClearsRouteScopedState() = runBlocking {
        val fixture = RoomDetailsStoreFixture(coroutineContext)
        try {
            fixture.store.activate(RoomDetailsTarget(USER_ID, ROOM_A), roomSummary(ROOM_A, "A"))
            awaitRoomDetailsCondition { fixture.updates(ROOM_A).subscriptionCount.value > 0 }

            fixture.store.deactivate()
            awaitRoomDetailsCondition {
                fixture.updates(ROOM_A).subscriptionCount.value == 0 &&
                    fixture.cachedDetails(USER_ID, ROOM_A).subscriptionCount.value == 0
            }

            assertEquals(RoomDetailsState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }
}

private class RoomDetailsStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)
    private val updatesByRoomId = mutableMapOf<String, MutableSharedFlow<MatrixRoomDetails>>()
    private val cachedDetailsByTarget =
        mutableMapOf<Pair<String, String>, MutableStateFlow<MatrixRoomDetails?>>()

    val warnings = mutableListOf<Pair<String, Throwable>>()
    val cachedWrites = mutableListOf<Pair<String, MatrixRoomDetails>>()
    var liveObservationCount = 0
    var liveBehavior: (String) -> Flow<MatrixRoomDetails> = ::updates
    var cacheBehavior: suspend (String, MatrixRoomDetails) -> Unit = { userId, details ->
        cachedWrites += userId to details
        cachedDetails(userId, details.roomId).value = details
    }

    val store = RoomDetailsStore(
        scope = scope,
        driver = RoomDetailsDriver(
            observeCachedDetails = ::cachedDetails,
            observeLiveDetails = { roomId ->
                liveObservationCount += 1
                liveBehavior(roomId)
            },
            cacheDetails = { userId, details -> cacheBehavior(userId, details) }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    fun updates(roomId: String): MutableSharedFlow<MatrixRoomDetails> {
        return updatesByRoomId.getOrPut(roomId) { MutableSharedFlow(extraBufferCapacity = 4) }
    }

    fun cachedDetails(
        userId: String,
        roomId: String
    ): MutableStateFlow<MatrixRoomDetails?> {
        return cachedDetailsByTarget.getOrPut(userId to roomId) { MutableStateFlow(null) }
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun roomSummary(roomId: String, displayName: String): MatrixRoomSummary {
    return MatrixRoomSummary(
        id = roomId,
        displayName = displayName,
        avatarUrl = null
    )
}

private fun roomDetails(roomId: String, displayName: String): MatrixRoomDetails {
    return MatrixRoomDetails(
        roomId = roomId,
        displayName = displayName,
        avatarUrl = "mxc://example.org/avatar",
        directUserId = null,
        kind = MatrixRoomKind.GROUP,
        topic = "Topic",
        joinedMemberCount = 3,
        encryption = MatrixRoomEncryption.ENCRYPTED,
        access = MatrixRoomAccess.PRIVATE,
        historyVisibility = MatrixRoomHistoryVisibility.INVITED,
        pinnedEventCount = 2,
        canonicalAlias = null
    )
}

private suspend fun awaitRoomDetailsCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
