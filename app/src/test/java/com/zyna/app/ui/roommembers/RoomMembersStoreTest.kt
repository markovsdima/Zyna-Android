package com.zyna.app.ui.roommembers

import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberRole
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_A = "@alice:example.org"
private const val USER_B = "@bob:example.org"
private const val ROOM_A = "!a:example.org"
private const val ROOM_B = "!b:example.org"

class RoomMembersStoreTest {
    @Test
    fun activatePublishesTrustworthyCacheThenReplacesItWithServerMembers() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
        val cached = listOf(member(USER_A, "Cached Alice"))
        val synced = listOf(
            member(USER_A, "Alice"),
            member(USER_B, "Bob")
        )
        fixture.loadBehavior = { _, source ->
            when (source) {
                RoomMembersSource.CACHE -> cached
                RoomMembersSource.SERVER -> serverGate.await()
            }
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 1)

            awaitRoomMembersCondition { fixture.store.state.value.isRefreshing }
            assertEquals(cached, fixture.store.state.value.joinedMembers)
            assertFalse(fixture.store.state.value.isLoading)

            serverGate.complete(synced)
            awaitRoomMembersCondition { !fixture.store.state.value.isRefreshing }

            assertEquals(synced, fixture.store.state.value.joinedMembers)
            assertEquals(2, fixture.store.state.value.totalMemberCount)
            assertNull(fixture.store.state.value.errorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialCacheIsNotPublishedWhenKnownJoinedCountMakesItUntrustworthy() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
        fixture.loadBehavior = { _, source ->
            when (source) {
                RoomMembersSource.CACHE -> listOf(member(USER_A, "Alice"))
                RoomMembersSource.SERVER -> serverGate.await()
            }
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 4)
            awaitRoomMembersCondition {
                fixture.calls.contains(ROOM_A to RoomMembersSource.SERVER)
            }

            assertTrue(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.joinedMembers.isEmpty())

            val synced = listOf(
                member(USER_A, "Alice"),
                member(USER_B, "Bob")
            )
            serverGate.complete(synced)
            awaitRoomMembersCondition { !fixture.store.state.value.isLoading }

            assertEquals(synced, fixture.store.state.value.joinedMembers)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun membersAreGroupedSortedAndFilteredByNameOrUserId() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val owner = member(
            userId = "@owner:example.org",
            displayName = "Zed",
            role = MatrixRoomMemberRole.OWNER,
            powerLevel = Long.MAX_VALUE
        )
        val alice = member(USER_A, "Alice")
        val bob = member(USER_B, "Bob")
        val invited = member(
            userId = "@carol:example.org",
            displayName = "Carol",
            membership = MatrixRoomMemberMembership.INVITED
        )
        fixture.loadBehavior = { _, source ->
            if (source == RoomMembersSource.CACHE) emptyList()
            else listOf(bob, invited, alice, owner)
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = null)
            awaitRoomMembersCondition { !fixture.store.state.value.isLoading }

            assertEquals(listOf(owner, alice, bob), fixture.store.state.value.joinedMembers)
            assertEquals(listOf(invited), fixture.store.state.value.invitedMembers)

            fixture.store.setSearchQuery("BOB")
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(bob) &&
                    fixture.store.state.value.invitedMembers.isEmpty()
            }

            fixture.store.setSearchQuery("@carol")
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers.isEmpty() &&
                    fixture.store.state.value.invitedMembers == listOf(invited)
            }
            assertEquals(4, fixture.store.state.value.totalMemberCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverFailureKeepsCachedMembersWithoutVisibleError() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val cached = listOf(member(USER_A, "Alice"))
        fixture.loadBehavior = { _, source ->
            if (source == RoomMembersSource.CACHE) cached else error("offline")
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 1)
            awaitRoomMembersCondition { fixture.warnings.isNotEmpty() }

            assertEquals(cached, fixture.store.state.value.joinedMembers)
            assertFalse(fixture.store.state.value.isLoading)
            assertFalse(fixture.store.state.value.isRefreshing)
            assertNull(fixture.store.state.value.errorMessage)
            assertEquals("Failed to load current room members", fixture.warnings.single().first)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverFailureWithoutUsableCacheShowsRetryableError() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        fixture.loadBehavior = { _, source ->
            if (source == RoomMembersSource.CACHE) emptyList() else error("members failed")
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 2)
            awaitRoomMembersCondition { fixture.store.state.value.errorMessage != null }

            assertEquals("members failed", fixture.store.state.value.errorMessage)
            assertFalse(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.joinedMembers.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingTargetRejectsLateResultFromPreviousRoom() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val oldServerGate = CompletableDeferred<Unit>()
        val oldMember = member("@old:example.org", "Old")
        val newMember = member("@new:example.org", "New")
        fixture.loadBehavior = { roomId, source ->
            when {
                source == RoomMembersSource.CACHE -> emptyList()
                roomId == ROOM_A -> withContext(NonCancellable) {
                    oldServerGate.await()
                    listOf(oldMember)
                }
                else -> listOf(newMember)
            }
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = null)
            awaitRoomMembersCondition {
                fixture.calls.contains(ROOM_A to RoomMembersSource.SERVER)
            }

            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_B), expectedJoinedCount = null)
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(newMember)
            }
            oldServerGate.complete(Unit)
            yield()

            assertEquals(ROOM_B, fixture.store.state.value.target?.roomId)
            assertEquals(listOf(newMember), fixture.store.state.value.joinedMembers)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingSessionRejectsLateResultForTheSameRoomId() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val oldServerGate = CompletableDeferred<Unit>()
        val oldMember = member("@old:example.org", "Old")
        val newMember = member("@new:example.org", "New")
        var serverLoadCount = 0
        fixture.loadBehavior = { _, source ->
            if (source == RoomMembersSource.CACHE) {
                emptyList()
            } else {
                serverLoadCount += 1
                if (serverLoadCount == 1) {
                    withContext(NonCancellable) {
                        oldServerGate.await()
                        listOf(oldMember)
                    }
                } else {
                    listOf(newMember)
                }
            }
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = null)
            awaitRoomMembersCondition { serverLoadCount == 1 }

            fixture.store.activate(RoomMembersTarget(USER_B, ROOM_A), expectedJoinedCount = null)
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(newMember)
            }
            oldServerGate.complete(Unit)
            yield()

            assertEquals(USER_B, fixture.store.state.value.target?.userId)
            assertEquals(listOf(newMember), fixture.store.state.value.joinedMembers)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryRetainsMembersAndSearchWhileReloadingServerState() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val initial = listOf(member(USER_A, "Alice"), member(USER_B, "Bob"))
        val refreshGate = CompletableDeferred<List<MatrixRoomMember>>()
        var serverLoadCount = 0
        fixture.loadBehavior = { _, source ->
            when (source) {
                RoomMembersSource.CACHE -> initial
                RoomMembersSource.SERVER -> {
                    serverLoadCount += 1
                    if (serverLoadCount == 1) initial else refreshGate.await()
                }
            }
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 2)
            awaitRoomMembersCondition {
                fixture.store.state.value.totalMemberCount == 2 &&
                    !fixture.store.state.value.isLoading &&
                    !fixture.store.state.value.isRefreshing
            }
            fixture.store.setSearchQuery("Alice")
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(initial.first())
            }

            fixture.store.retry()
            awaitRoomMembersCondition { fixture.store.state.value.isRefreshing }

            assertEquals("Alice", fixture.store.state.value.searchQuery)
            assertEquals(listOf(initial.first()), fixture.store.state.value.joinedMembers)

            val refreshed = listOf(member(USER_A, "Alice Updated"))
            refreshGate.complete(refreshed)
            awaitRoomMembersCondition {
                !fixture.store.state.value.isRefreshing &&
                    fixture.store.state.value.joinedMembers == listOf(refreshed.first())
            }

            assertEquals(listOf(refreshed.first()), fixture.store.state.value.joinedMembers)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun searchQueryUpdatesImmediatelyAndDebounceRejectsAnOlderQuery() = runBlocking {
        val fixture = RoomMembersStoreFixture(
            parentContext = coroutineContext,
            searchDebounceMillis = 80
        )
        val alice = member(USER_A, "Alice")
        val bob = member(USER_B, "Bob")
        fixture.loadBehavior = { _, _ -> listOf(alice, bob) }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 2)
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(alice, bob) &&
                    !fixture.store.state.value.isRefreshing
            }

            fixture.store.setSearchQuery("Alice")
            assertEquals("Alice", fixture.store.state.value.searchQuery)
            assertEquals(listOf(alice, bob), fixture.store.state.value.joinedMembers)

            fixture.store.setSearchQuery("Bob")
            assertEquals("Bob", fixture.store.state.value.searchQuery)
            assertEquals(listOf(alice, bob), fixture.store.state.value.joinedMembers)

            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(bob)
            }
            delay(100)
            assertEquals("Bob", fixture.store.state.value.searchQuery)
            assertEquals(listOf(bob), fixture.store.state.value.joinedMembers)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverSnapshotReplacementRejectsFilteringFromThePreviousMemberList() = runBlocking {
        val fixture = RoomMembersStoreFixture(
            parentContext = coroutineContext,
            searchDebounceMillis = 100
        )
        val legacyMember = member(USER_A, "Legacy name")
        val currentMember = member(USER_B, "Current name")
        var serverLoadCount = 0
        fixture.loadBehavior = { _, source ->
            when (source) {
                RoomMembersSource.CACHE -> listOf(legacyMember)
                RoomMembersSource.SERVER -> {
                    serverLoadCount += 1
                    if (serverLoadCount == 1) listOf(legacyMember) else listOf(currentMember)
                }
            }
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 1)
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == listOf(legacyMember) &&
                    !fixture.store.state.value.isRefreshing
            }

            fixture.store.setSearchQuery("Legacy")
            fixture.store.retry()

            awaitRoomMembersCondition {
                serverLoadCount == 2 &&
                    !fixture.store.state.value.isRefreshing &&
                    fixture.store.state.value.joinedMembers.isEmpty()
            }
            delay(120)
            assertEquals("Legacy", fixture.store.state.value.searchQuery)
            assertTrue(fixture.store.state.value.joinedMembers.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deactivateClearsRouteScopedStateAndCancelsLoading() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
        fixture.loadBehavior = { _, source ->
            if (source == RoomMembersSource.CACHE) emptyList() else serverGate.await()
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = null)
            awaitRoomMembersCondition {
                fixture.calls.contains(ROOM_A to RoomMembersSource.SERVER)
            }

            fixture.store.deactivate()

            assertEquals(RoomMembersState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deactivateRetainsSessionSnapshotForAnImmediateReopen() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val initial = listOf(member(USER_A, "Alice"), member(USER_B, "Bob"))
        fixture.loadBehavior = { _, _ -> initial }
        val target = RoomMembersTarget(USER_A, ROOM_A)
        try {
            fixture.store.activate(target, expectedJoinedCount = 2)
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == initial &&
                    !fixture.store.state.value.isRefreshing
            }
            assertEquals(1, fixture.calls.count { it.second == RoomMembersSource.CACHE })

            fixture.store.deactivate()
            val refreshGate = CompletableDeferred<List<MatrixRoomMember>>()
            fixture.loadBehavior = { _, source ->
                when (source) {
                    RoomMembersSource.CACHE -> error("The SDK cache should be skipped")
                    RoomMembersSource.SERVER -> refreshGate.await()
                }
            }
            fixture.store.activate(target, expectedJoinedCount = 2)

            assertEquals(initial, fixture.store.state.value.joinedMembers)
            assertFalse(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.isRefreshing)
            assertEquals(1, fixture.calls.count { it.second == RoomMembersSource.CACHE })

            refreshGate.complete(listOf(member(USER_A, "Alice Updated")))
            awaitRoomMembersCondition { !fixture.store.state.value.isRefreshing }
            assertEquals(
                "Alice Updated",
                fixture.store.state.value.joinedMembers.single().displayName
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clearSessionRemovesTheRetainedSnapshot() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val initial = listOf(member(USER_A, "Alice"))
        fixture.loadBehavior = { _, _ -> initial }
        val target = RoomMembersTarget(USER_A, ROOM_A)
        try {
            fixture.store.activate(target, expectedJoinedCount = 1)
            awaitRoomMembersCondition {
                fixture.store.state.value.joinedMembers == initial &&
                    !fixture.store.state.value.isRefreshing
            }

            fixture.store.clearSession()
            val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
            fixture.loadBehavior = { _, source ->
                if (source == RoomMembersSource.CACHE) emptyList() else serverGate.await()
            }
            fixture.store.activate(target, expectedJoinedCount = 1)

            assertTrue(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.joinedMembers.isEmpty())
            awaitRoomMembersCondition {
                fixture.calls.count { it.second == RoomMembersSource.CACHE } == 2
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun prefetchSeedsFirstActivationWithoutTakingOwnershipOfRouteState() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val cached = listOf(member(USER_A, "Alice"))
        val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
        fixture.loadBehavior = { _, source ->
            if (source == RoomMembersSource.CACHE) cached else serverGate.await()
        }
        val target = RoomMembersTarget(USER_A, ROOM_A)
        try {
            fixture.store.prefetch(target, expectedJoinedCount = 1)
            awaitRoomMembersCondition {
                fixture.calls.contains(ROOM_A to RoomMembersSource.CACHE)
            }
            yield()

            assertEquals(RoomMembersState(), fixture.store.state.value)

            fixture.store.activate(target, expectedJoinedCount = 1)

            assertEquals(cached, fixture.store.state.value.joinedMembers)
            assertFalse(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.isRefreshing)
            assertEquals(1, fixture.calls.count { it.second == RoomMembersSource.CACHE })
            awaitRoomMembersCondition {
                fixture.calls.contains(ROOM_A to RoomMembersSource.SERVER)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stoppedPrefetchCannotPublishALateSnapshot() = runBlocking {
        val fixture = RoomMembersStoreFixture(coroutineContext)
        val prefetchGate = CompletableDeferred<Unit>()
        val stale = listOf(member(USER_A, "Stale Alice"))
        fixture.loadBehavior = { _, source ->
            check(source == RoomMembersSource.CACHE)
            withContext(NonCancellable) {
                prefetchGate.await()
                stale
            }
        }
        val target = RoomMembersTarget(USER_A, ROOM_A)
        try {
            fixture.store.prefetch(target, expectedJoinedCount = 1)
            awaitRoomMembersCondition {
                fixture.calls.contains(ROOM_A to RoomMembersSource.CACHE)
            }

            fixture.store.stopPrefetch()
            prefetchGate.complete(Unit)
            yield()

            val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
            fixture.loadBehavior = { _, source ->
                if (source == RoomMembersSource.CACHE) emptyList() else serverGate.await()
            }
            fixture.store.activate(target, expectedJoinedCount = 1)

            assertTrue(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.joinedMembers.isEmpty())
            awaitRoomMembersCondition {
                fixture.calls.count { it.second == RoomMembersSource.CACHE } == 2
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sessionSnapshotCacheEvictsTheLeastRecentlyUsedRoom() = runBlocking {
        val fixture = RoomMembersStoreFixture(
            parentContext = coroutineContext,
            maxCachedRooms = 1
        )
        fixture.loadBehavior = { roomId, _ ->
            listOf(member("@${roomId.first { it.isLetter() }}:example.org", roomId))
        }
        try {
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 1)
            awaitRoomMembersCondition {
                !fixture.store.state.value.isLoading &&
                    !fixture.store.state.value.isRefreshing &&
                    fixture.store.state.value.totalMemberCount == 1
            }
            fixture.store.deactivate()

            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_B), expectedJoinedCount = 1)
            awaitRoomMembersCondition {
                !fixture.store.state.value.isLoading &&
                    !fixture.store.state.value.isRefreshing &&
                    fixture.store.state.value.totalMemberCount == 1
            }
            fixture.store.deactivate()

            val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
            fixture.loadBehavior = { _, source ->
                if (source == RoomMembersSource.CACHE) emptyList() else serverGate.await()
            }
            fixture.store.activate(RoomMembersTarget(USER_A, ROOM_A), expectedJoinedCount = 1)

            assertTrue(fixture.store.state.value.isLoading)
            awaitRoomMembersCondition {
                fixture.calls.count {
                    it.first == ROOM_A && it.second == RoomMembersSource.CACHE
                } == 2
            }
        } finally {
            fixture.close()
        }
    }
}

private class RoomMembersStoreFixture(
    parentContext: CoroutineContext,
    maxCachedRooms: Int = 8,
    searchDebounceMillis: Long = 0
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val calls = mutableListOf<Pair<String, RoomMembersSource>>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var loadBehavior: suspend (String, RoomMembersSource) -> List<MatrixRoomMember> =
        { _, _ -> emptyList() }

    val store = RoomMembersStore(
        scope = scope,
        driver = RoomMembersDriver { roomId, source ->
            calls += roomId to source
            loadBehavior(roomId, source)
        },
        workerDispatcher = Dispatchers.Unconfined,
        maxCachedRooms = maxCachedRooms,
        searchDebounceMillis = searchDebounceMillis,
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun member(
    userId: String,
    displayName: String,
    membership: MatrixRoomMemberMembership = MatrixRoomMemberMembership.JOINED,
    role: MatrixRoomMemberRole = MatrixRoomMemberRole.MEMBER,
    powerLevel: Long = 0
): MatrixRoomMember {
    return MatrixRoomMember(
        userId = userId,
        displayName = displayName,
        avatarUrl = null,
        membership = membership,
        role = role,
        powerLevel = powerLevel,
        isNameAmbiguous = false
    )
}

private suspend fun awaitRoomMembersCondition(condition: () -> Boolean) {
    withTimeout(2_000) {
        while (!condition()) {
            yield()
        }
    }
}
