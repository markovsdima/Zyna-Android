package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceJoinContext
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.spaceRoom
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val JOIN_USER = "@alice:example.org"
private const val JOIN_PARENT = "!story:example.org"
private const val JOIN_ROOM = "!child:example.org"

class SpaceJoinStoreTest {
    @Test
    fun primaryActionFollowsMembershipAndFreshRestrictedAccess() {
        assertEquals(
            SpaceJoinPrimaryAction.OPEN,
            action(membership = MatrixSpaceMembership.JOINED)
        )
        assertEquals(
            SpaceJoinPrimaryAction.ACCEPT_INVITE,
            action(membership = MatrixSpaceMembership.INVITED)
        )
        assertEquals(
            SpaceJoinPrimaryAction.JOIN,
            action(joinRule = MatrixSpaceJoinRule.PUBLIC)
        )
        assertEquals(
            SpaceJoinPrimaryAction.KNOCK,
            action(joinRule = MatrixSpaceJoinRule.KNOCK)
        )
        assertEquals(
            SpaceJoinPrimaryAction.JOIN,
            action(
                joinRule = MatrixSpaceJoinRule.KNOCK_RESTRICTED,
                canJoinRestrictedDirectly = true
            )
        )
        assertEquals(
            SpaceJoinPrimaryAction.KNOCK,
            action(
                joinRule = MatrixSpaceJoinRule.KNOCK_RESTRICTED,
                canJoinRestrictedDirectly = false
            )
        )
        assertNull(
            action(
                joinRule = MatrixSpaceJoinRule.RESTRICTED,
                canJoinRestrictedDirectly = false
            )
        )
        assertEquals(
            SpaceJoinPrimaryAction.JOIN,
            action(
                joinRule = MatrixSpaceJoinRule.RESTRICTED,
                canJoinRestrictedDirectly = false,
                hasUnsupportedRestrictedAllowRules = true
            )
        )
        assertEquals(
            SpaceJoinPrimaryAction.KNOCK,
            action(
                joinRule = MatrixSpaceJoinRule.KNOCK_RESTRICTED,
                canJoinRestrictedDirectly = false,
                hasUnsupportedRestrictedAllowRules = true
            )
        )
        assertNull(action(membership = MatrixSpaceMembership.KNOCKED))
        assertNull(action(membership = MatrixSpaceMembership.BANNED))
    }

    @Test
    fun cacheSeedIsImmediateThenFreshContextReplacesIt() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val seed = child(joinRule = MatrixSpaceJoinRule.PUBLIC, displayName = "Cached")
        fixture.contextBehavior = {
            MatrixSpaceJoinContext(seed.copy(displayName = "Fresh"))
        }
        try {
            fixture.activate(seed)

            assertEquals("Cached", fixture.store.state.value.room?.displayName)
            assertTrue(fixture.store.state.value.isRefreshing)
            awaitJoinCondition { !fixture.store.state.value.isRefreshing }

            assertEquals("Fresh", fixture.store.state.value.room?.displayName)
            assertEquals(SpaceJoinPrimaryAction.JOIN, fixture.store.state.value.primaryAction)
            assertEquals(listOf(seed), fixture.contextFallbacks)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sameRouteSeedChangeRefreshesANonActionablePreview() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val knocked = child(
            membership = MatrixSpaceMembership.KNOCKED,
            displayName = "Request pending"
        )
        val invited = knocked.copy(
            membership = MatrixSpaceMembership.INVITED,
            displayName = "Invite ready"
        )
        fixture.contextBehavior = { call ->
            MatrixSpaceJoinContext(if (call == 1) knocked else invited)
        }
        try {
            fixture.activateAndAwait(knocked)
            assertNull(fixture.store.state.value.primaryAction)

            fixture.activate(invited)
            assertEquals(MatrixSpaceMembership.INVITED, fixture.store.state.value.room?.membership)
            assertTrue(fixture.store.state.value.isRefreshing)
            awaitJoinCondition { !fixture.store.state.value.isRefreshing }

            assertEquals(2, fixture.loadCalls)
            assertEquals("Invite ready", fixture.store.state.value.room?.displayName)
            assertEquals(
                SpaceJoinPrimaryAction.ACCEPT_INVITE,
                fixture.store.state.value.primaryAction
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invitedRoomJoinsCachesAndOpens() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val invited = child(
            membership = MatrixSpaceMembership.INVITED,
            displayName = "Invite"
        )
        fixture.contextBehavior = { MatrixSpaceJoinContext(invited) }
        fixture.joinBehavior = {
            MatrixRoomSummary(
                id = JOIN_ROOM,
                displayName = "Joined",
                avatarUrl = "mxc://example/avatar"
            )
        }
        try {
            fixture.activateAndAwait(invited)
            fixture.store.performPrimaryAction()
            awaitJoinCondition { fixture.joined.isNotEmpty() }

            assertEquals(1, fixture.joinCalls.size)
            assertTrue(fixture.knockCalls.isEmpty())
            assertEquals(MatrixSpaceMembership.JOINED, fixture.membershipChanges.single().membership)
            assertEquals("Joined", fixture.joined.single().displayName)
            assertEquals(true, fixture.cachedRooms.single().isSpace)
            assertEquals(listOf(MatrixSpaceMembership.JOINED), fixture.cachedMemberships)
            assertEquals(listOf(invited, invited), fixture.contextFallbacks)
            assertFalse(fixture.store.state.value.isSubmitting)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rootSpaceInviteJoinsWithoutWritingAChildHierarchyEntry() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val invited = child(membership = MatrixSpaceMembership.INVITED)
        fixture.contextBehavior = { MatrixSpaceJoinContext(invited) }
        try {
            fixture.activateAndAwait(invited, parentSpaceId = null)
            fixture.store.performPrimaryAction()
            awaitJoinCondition { fixture.joined.isNotEmpty() }

            assertEquals(MatrixSpaceMembership.JOINED, fixture.joined.single().membership)
            assertEquals(MatrixSpaceMembership.JOINED, fixture.cachedRooms.single().membership)
            assertTrue(fixture.cachedMemberships.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun knockRestrictedWithoutMembershipSendsRequestAndStaysOnPreview() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val room = child(joinRule = MatrixSpaceJoinRule.KNOCK_RESTRICTED)
        fixture.contextBehavior = {
            MatrixSpaceJoinContext(room, canJoinRestrictedDirectly = false)
        }
        try {
            fixture.activateAndAwait(room)
            fixture.store.performPrimaryAction()
            awaitJoinCondition {
                fixture.store.state.value.room?.membership == MatrixSpaceMembership.KNOCKED
            }

            assertEquals(1, fixture.knockCalls.size)
            assertTrue(fixture.joinCalls.isEmpty())
            assertTrue(fixture.joined.isEmpty())
            assertEquals(MatrixSpaceMembership.KNOCKED, fixture.membershipChanges.single().membership)
            assertNull(fixture.store.state.value.primaryAction)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun changedAccessIsShownWithoutExecutingDifferentAction() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val publicRoom = child(joinRule = MatrixSpaceJoinRule.PUBLIC)
        fixture.contextBehavior = { call ->
            if (call == 1) {
                MatrixSpaceJoinContext(publicRoom)
            } else {
                MatrixSpaceJoinContext(publicRoom.copy(joinRule = MatrixSpaceJoinRule.KNOCK))
            }
        }
        try {
            fixture.activateAndAwait(publicRoom)
            fixture.store.performPrimaryAction()
            awaitJoinCondition { fixture.store.state.value.error == SpaceJoinError.ACCESS_CHANGED }

            assertTrue(fixture.joinCalls.isEmpty())
            assertTrue(fixture.knockCalls.isEmpty())
            assertEquals(SpaceJoinPrimaryAction.KNOCK, fixture.store.state.value.primaryAction)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverAppliedJoinIsAcceptedWhenSdkCallReportsFailure() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val publicRoom = child(joinRule = MatrixSpaceJoinRule.PUBLIC)
        fixture.contextBehavior = { call ->
            MatrixSpaceJoinContext(
                if (call >= 3) {
                    publicRoom.copy(membership = MatrixSpaceMembership.JOINED)
                } else {
                    publicRoom
                }
            )
        }
        fixture.joinBehavior = { error("connection closed after response") }
        try {
            fixture.activateAndAwait(publicRoom)
            fixture.store.performPrimaryAction()
            awaitJoinCondition { fixture.joined.isNotEmpty() }

            assertNull(fixture.store.state.value.error)
            assertEquals(MatrixSpaceMembership.JOINED, fixture.joined.single().membership)
            assertEquals(1, fixture.cachedRooms.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leavingRouteRejectsLateNonCancellableJoinResult() = runBlocking {
        val fixture = SpaceJoinFixture(coroutineContext)
        val mutationStarted = CompletableDeferred<Unit>()
        val mutationGate = CompletableDeferred<Unit>()
        val publicRoom = child(joinRule = MatrixSpaceJoinRule.PUBLIC)
        fixture.contextBehavior = { MatrixSpaceJoinContext(publicRoom) }
        fixture.joinBehavior = {
            mutationStarted.complete(Unit)
            withContext(NonCancellable) { mutationGate.await() }
            summary()
        }
        try {
            fixture.activateAndAwait(publicRoom)
            fixture.store.performPrimaryAction()
            mutationStarted.await()

            fixture.store.deactivate()
            mutationGate.complete(Unit)
            yield()

            assertEquals(SpaceJoinState(), fixture.store.state.value)
            assertTrue(fixture.cachedRooms.isEmpty())
            assertTrue(fixture.cachedMemberships.isEmpty())
            assertTrue(fixture.membershipChanges.isEmpty())
            assertTrue(fixture.joined.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private fun action(
        membership: MatrixSpaceMembership = MatrixSpaceMembership.LEFT,
        joinRule: MatrixSpaceJoinRule = MatrixSpaceJoinRule.UNKNOWN,
        canJoinRestrictedDirectly: Boolean? = null,
        hasUnsupportedRestrictedAllowRules: Boolean = false
    ): SpaceJoinPrimaryAction? {
        return spaceJoinPrimaryAction(
            MatrixSpaceJoinContext(
                room = child(membership = membership, joinRule = joinRule),
                canJoinRestrictedDirectly = canJoinRestrictedDirectly,
                hasUnsupportedRestrictedAllowRules = hasUnsupportedRestrictedAllowRules
            )
        )
    }
}

private class SpaceJoinFixture(parentContext: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)

    var loadCalls = 0
    val joinCalls = mutableListOf<List<String>>()
    val knockCalls = mutableListOf<List<String>>()
    val cachedRooms = mutableListOf<MatrixRoomSummary>()
    val cachedMemberships = mutableListOf<MatrixSpaceMembership>()
    val contextFallbacks = mutableListOf<MatrixSpaceRoom>()
    val membershipChanges = mutableListOf<MatrixSpaceRoom>()
    val joined = mutableListOf<MatrixSpaceRoom>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var expectedWarningCount = 0
    var contextBehavior: suspend (call: Int) -> MatrixSpaceJoinContext = {
        MatrixSpaceJoinContext(child(joinRule = MatrixSpaceJoinRule.PUBLIC))
    }
    var joinBehavior: suspend () -> MatrixRoomSummary = { summary() }
    var knockBehavior: suspend () -> Unit = {}

    val store = SpaceJoinStore(
        scope = scope,
        driver = SpaceJoinDriver(
            loadContext = { _, _, fallbackRoom ->
                loadCalls += 1
                contextFallbacks += fallbackRoom
                contextBehavior(loadCalls)
            },
            join = { _, _, serverNames ->
                joinCalls += serverNames
                joinBehavior()
            },
            knock = { _, _, serverNames ->
                knockCalls += serverNames
                knockBehavior()
            },
            cacheJoinedRoom = { _, room -> cachedRooms += room },
            cacheChildMembership = { _, _, _, membership ->
                cachedMemberships += membership
            }
        ),
        onMembershipChanged = { _, room -> membershipChanges += room },
        onJoined = { _, room -> joined += room },
        onWarning = { message, error -> warnings += message to error }
    )

    fun activate(seed: MatrixSpaceRoom, parentSpaceId: String? = JOIN_PARENT) {
        store.activate(
            SpaceJoinTarget(
                userId = JOIN_USER,
                parentSpaceId = parentSpaceId,
                roomId = JOIN_ROOM,
                seed = seed
            )
        )
    }

    suspend fun activateAndAwait(
        seed: MatrixSpaceRoom,
        parentSpaceId: String? = JOIN_PARENT
    ) {
        activate(seed, parentSpaceId)
        awaitJoinCondition { !store.state.value.isRefreshing }
    }

    suspend fun close() {
        try {
            assertEquals("Unexpected warnings: $warnings", expectedWarningCount, warnings.size)
        } finally {
            job.cancelAndJoin()
        }
    }
}

private fun child(
    membership: MatrixSpaceMembership = MatrixSpaceMembership.LEFT,
    joinRule: MatrixSpaceJoinRule = MatrixSpaceJoinRule.UNKNOWN,
    displayName: String = "Child"
): MatrixSpaceRoom {
    return spaceRoom(
        id = JOIN_ROOM,
        kind = MatrixSpaceRoomKind.SPACE,
        membership = membership,
        displayName = displayName
    ).copy(joinRule = joinRule, via = listOf("example.org"))
}

private fun summary(): MatrixRoomSummary {
    return MatrixRoomSummary(id = JOIN_ROOM, displayName = "Child", avatarUrl = null)
}

private suspend fun awaitJoinCondition(condition: () -> Boolean) {
    withTimeout(2_000) {
        while (!condition()) yield()
    }
}
