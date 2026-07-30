package com.zyna.app.ui.roommembers

import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberModerationAction
import com.zyna.app.data.matrix.MatrixRoomMemberModerationContext
import com.zyna.app.data.matrix.MatrixRoomMemberRole
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

class RoomMemberModerationStoreTest {
    @Test
    fun activationUsesSeedThenPublishesFreshContext() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val seed = member(displayName = "Cached Bob")
        fixture.contextBehavior = { _, _, _ ->
            context(member = member(displayName = "Current Bob"))
        }
        try {
            fixture.activate(seed)

            assertEquals(seed, fixture.store.state.value.member)
            assertTrue(fixture.store.state.value.isLoading)
            awaitCondition { !fixture.store.state.value.isLoading }

            assertEquals("Current Bob", fixture.store.state.value.member?.displayName)
            assertTrue(
                fixture.store.state.value.canPerform(
                    MatrixRoomMemberModerationAction.KICK
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun kickUsesConfirmationFreshPreflightAndTrimmedReason() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activateAndAwait()

            fixture.store.request(MatrixRoomMemberModerationAction.KICK)

            assertEquals(
                MatrixRoomMemberModerationAction.KICK,
                fixture.store.state.value.pendingConfirmation?.action
            )
            assertTrue(fixture.moderations.isEmpty())

            fixture.store.confirm("  repeated spam  ")
            awaitCondition { fixture.completed.isNotEmpty() }

            assertEquals(
                ModerationCall(
                    userId = TARGET_USER_ID,
                    action = MatrixRoomMemberModerationAction.KICK,
                    reason = "repeated spam"
                ),
                fixture.moderations.single()
            )
            assertEquals(2, fixture.loads)
            assertEquals(1, fixture.refreshRequests)
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellingConfirmationDoesNotWrite() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.BAN)

            fixture.store.cancelConfirmation()
            yield()

            assertNull(fixture.store.state.value.pendingConfirmation)
            assertTrue(fixture.moderations.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun bannedMemberOffersUnbanButNotKickOrBan() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { _, _, _ ->
            context(member = member(membership = MatrixRoomMemberMembership.BANNED))
        }
        try {
            fixture.activateAndAwait(
                seed = member(membership = MatrixRoomMemberMembership.BANNED)
            )
            val state = fixture.store.state.value

            assertTrue(state.canPerform(MatrixRoomMemberModerationAction.UNBAN))
            assertFalse(state.canPerform(MatrixRoomMemberModerationAction.KICK))
            assertFalse(state.canPerform(MatrixRoomMemberModerationAction.BAN))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun capabilityLossDuringPreflightRejectsWrite() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { _, _, call ->
            context(canKickMembers = call == 1)
        }
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.KICK)
            fixture.store.confirm(null)

            awaitCondition {
                fixture.store.state.value.error ==
                    RoomMemberModerationError.PERMISSION_CHANGED
            }

            assertTrue(fixture.moderations.isEmpty())
            assertEquals(1, fixture.refreshRequests)
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun memberLeavingBeforeBanPreflightCanStillBeBanned() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { _, _, call ->
            context(
                member = member(
                    membership = if (call == 1) {
                        MatrixRoomMemberMembership.JOINED
                    } else {
                        MatrixRoomMemberMembership.LEFT
                    }
                )
            )
        }
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.BAN)
            fixture.store.confirm("spam")

            awaitCondition { fixture.completed.isNotEmpty() }

            assertEquals(
                ModerationCall(
                    userId = TARGET_USER_ID,
                    action = MatrixRoomMemberModerationAction.BAN,
                    reason = "spam"
                ),
                fixture.moderations.single()
            )
            assertEquals(
                MatrixRoomMemberMembership.BANNED,
                fixture.store.state.value.member?.membership
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun loadFailureKeepsSeedAndRetryRecovers() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { _, _, call ->
            if (call == 1) error("offline") else context()
        }
        val seed = member(displayName = "Cached Bob")
        try {
            fixture.activate(seed)
            awaitCondition {
                fixture.store.state.value.error == RoomMemberModerationError.LOAD
            }

            assertEquals(seed, fixture.store.state.value.member)

            fixture.store.retry()
            awaitCondition {
                fixture.store.state.value.error == null &&
                    !fixture.store.state.value.isLoading
            }

            assertEquals(2, fixture.loads)
            assertTrue(
                fixture.store.state.value.canPerform(
                    MatrixRoomMemberModerationAction.BAN
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun mismatchedLoadContextIsRejectedWithoutReplacingSeed() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val seed = member(displayName = "Cached Bob")
        fixture.contextBehavior = { _, _, _ ->
            context(member = member(displayName = "Wrong Bob"))
                .copy(roomId = "!other:example.org")
        }
        try {
            fixture.activate(seed)
            awaitCondition {
                fixture.store.state.value.error == RoomMemberModerationError.LOAD
            }

            assertEquals(seed, fixture.store.state.value.member)
            assertNull(fixture.store.state.value.context)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun mismatchedPreflightContextCannotAuthorizeMutation() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { _, _, call ->
            if (call == 1) {
                context()
            } else {
                context().copy(ownUserId = "@mallory:example.org")
            }
        }
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.BAN)
            fixture.store.confirm(null)

            awaitCondition {
                fixture.store.state.value.error ==
                    RoomMemberModerationError.PERMISSION_CHANGED
            }

            assertTrue(fixture.moderations.isEmpty())
            assertEquals(1, fixture.refreshRequests)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryRejectsLateResultFromCancellationSwallowingLoad() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val firstLoadStarted = CompletableDeferred<Unit>()
        val firstLoadGate = CompletableDeferred<Unit>()
        fixture.contextBehavior = { _, _, call ->
            if (call == 1) {
                firstLoadStarted.complete(Unit)
                withContext(NonCancellable) {
                    firstLoadGate.await()
                    context(member = member(displayName = "Stale Bob"))
                }
            } else {
                context(member = member(displayName = "Current Bob"))
            }
        }
        try {
            fixture.activate()
            firstLoadStarted.await()

            fixture.store.retry()
            awaitCondition {
                fixture.store.state.value.member?.displayName == "Current Bob" &&
                    !fixture.store.state.value.isLoading
            }

            firstLoadGate.complete(Unit)
            yield()

            assertEquals("Current Bob", fixture.store.state.value.member?.displayName)
            assertEquals(2, fixture.loads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun mutationFailureShowsErrorAndRequestsRefresh() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.moderateBehavior = { error("network closed") }
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.BAN)
            fixture.store.confirm(null)

            awaitCondition {
                fixture.store.state.value.error == RoomMemberModerationError.SAVE
            }

            assertEquals(3, fixture.loads)
            assertEquals(1, fixture.refreshRequests)
            assertTrue(fixture.completed.isEmpty())
            assertEquals(1, fixture.warnings.count { it.first == "Failed to moderate room member" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverAppliedWriteIsAcceptedWhenMutationReportsFailure() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { _, _, call ->
            if (call >= 3) {
                context(member = member(membership = MatrixRoomMemberMembership.BANNED))
            } else {
                context()
            }
        }
        fixture.moderateBehavior = { error("connection closed after response") }
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.BAN)
            fixture.store.confirm("spam")

            awaitCondition { fixture.completed.isNotEmpty() }

            assertNull(fixture.store.state.value.error)
            assertEquals(1, fixture.refreshRequests)
            assertEquals(MatrixRoomMemberModerationAction.BAN, fixture.completed.single().second)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leavingRouteRejectsLateNonCancellableMutation() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val mutationStarted = CompletableDeferred<Unit>()
        val mutationGate = CompletableDeferred<Unit>()
        fixture.moderateBehavior = {
            mutationStarted.complete(Unit)
            withContext(NonCancellable) { mutationGate.await() }
        }
        try {
            fixture.activateAndAwait()
            fixture.store.request(MatrixRoomMemberModerationAction.KICK)
            fixture.store.confirm(null)
            mutationStarted.await()

            fixture.store.deactivate()
            mutationGate.complete(Unit)
            yield()

            assertEquals(RoomMemberModerationState(), fixture.store.state.value)
            assertEquals(0, fixture.refreshRequests)
            assertTrue(fixture.completed.isEmpty())
        } finally {
            fixture.close()
        }
    }
}

private class Fixture(parentContext: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)

    var loads = 0
    var refreshRequests = 0
    val moderations = mutableListOf<ModerationCall>()
    val completed =
        mutableListOf<Pair<RoomMemberModerationTarget, MatrixRoomMemberModerationAction>>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var contextBehavior: suspend (
        roomId: String,
        targetUserId: String,
        call: Int
    ) -> MatrixRoomMemberModerationContext = { _, targetUserId, _ ->
        context(member = member(userId = targetUserId))
    }
    var moderateBehavior: suspend () -> Unit = {}

    val store = RoomMemberModerationStore(
        scope = scope,
        driver = RoomMemberModerationDriver(
            loadContext = { roomId, targetUserId ->
                loads += 1
                contextBehavior(roomId, targetUserId, loads)
            },
            moderate = { _, targetUserId, action, reason ->
                moderations += ModerationCall(targetUserId, action, reason)
                moderateBehavior()
            }
        ),
        onMembersRefreshRequested = { refreshRequests += 1 },
        onCompleted = { target, action -> completed += target to action },
        onWarning = { message, error -> warnings += message to error }
    )

    fun activate(seed: MatrixRoomMember = member()) {
        store.activate(
            target = RoomMemberModerationTarget(
                userId = OWN_USER_ID,
                roomId = ROOM_ID,
                memberUserId = seed.userId
            ),
            seed = seed
        )
    }

    suspend fun activateAndAwait(seed: MatrixRoomMember = member()) {
        activate(seed)
        awaitCondition { !store.state.value.isLoading }
    }

    suspend fun close() {
        job.cancelAndJoin()
    }
}

private data class ModerationCall(
    val userId: String,
    val action: MatrixRoomMemberModerationAction,
    val reason: String?
)

private fun context(
    ownPowerLevel: Long = 100,
    canKickMembers: Boolean = true,
    canBanMembers: Boolean = true,
    member: MatrixRoomMember = member()
): MatrixRoomMemberModerationContext {
    return MatrixRoomMemberModerationContext(
        roomId = ROOM_ID,
        ownUserId = OWN_USER_ID,
        ownPowerLevel = ownPowerLevel,
        canKickMembers = canKickMembers,
        canBanMembers = canBanMembers,
        member = member
    )
}

private fun member(
    userId: String = TARGET_USER_ID,
    displayName: String = "Bob",
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

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(2_000) {
        while (!condition()) yield()
    }
}

private const val OWN_USER_ID = "@alice:example.org"
private const val TARGET_USER_ID = "@bob:example.org"
private const val ROOM_ID = "!room:example.org"
