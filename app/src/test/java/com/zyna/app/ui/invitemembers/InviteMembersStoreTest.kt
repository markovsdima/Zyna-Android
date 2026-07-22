package com.zyna.app.ui.invitemembers

import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberRole
import com.zyna.app.data.matrix.MatrixUserProfile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
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

private const val SESSION_USER = "@me:example.org"
private const val ROOM = "!room:example.org"
private const val ALICE = "@alice:example.org"
private const val BOB = "@bob:example.org"

class InviteMembersStoreTest {
    @Test
    fun activationUsesSeedPermissionAndRefreshesMembersBeforeEnablingSend() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        val serverGate = CompletableDeferred<List<MatrixRoomMember>>()
        fixture.membersBehavior = { source ->
            when (source) {
                InviteMembersSource.CACHE -> listOf(member(ALICE, MatrixRoomMemberMembership.JOINED))
                InviteMembersSource.SERVER -> serverGate.await()
            }
        }
        fixture.searchBehavior = { listOf(profile(ALICE, "Alice")) }
        try {
            fixture.store.activate(target(), seedCanInviteMembers = true)
            awaitCondition { fixture.memberSources.contains(InviteMembersSource.SERVER) }

            assertTrue(fixture.store.state.value.canInviteMembers)
            assertTrue(fixture.store.state.value.isPreparing)

            fixture.store.setSearchQuery("alice")
            awaitCondition { !fixture.store.state.value.isSearching }
            assertEquals(
                MatrixRoomMemberMembership.JOINED,
                fixture.store.state.value.searchResults.single().membership
            )
            fixture.store.toggleSelection(fixture.store.state.value.searchResults.single().profile)
            assertTrue(fixture.store.state.value.selectedMembers.isEmpty())

            serverGate.complete(emptyList())
            awaitCondition { !fixture.store.state.value.isPreparing }
            assertNull(fixture.store.state.value.preparationErrorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectionSurvivesSearchChangesAndSelectedUsersMoveToTheirOwnSection() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        fixture.searchBehavior = { query ->
            when (query) {
                "alice" -> listOf(profile(ALICE, "Alice"))
                else -> listOf(profile(BOB, "Bob"))
            }
        }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("alice")
            awaitCondition { fixture.store.state.value.searchResults.isNotEmpty() }
            fixture.store.toggleSelection(fixture.store.state.value.searchResults.single().profile)

            assertEquals(ALICE, fixture.store.state.value.selectedMembers.single().profile.userId)
            assertTrue(fixture.store.state.value.searchResults.isEmpty())

            fixture.store.setSearchQuery("bob")
            awaitCondition {
                fixture.store.state.value.searchResults.singleOrNull()?.profile?.userId == BOB
            }
            assertEquals(ALICE, fixture.store.state.value.selectedMembers.single().profile.userId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun changedQueryRejectsLateSearchEvenWhenDependencySwallowsCancellation() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        fixture.searchBehavior = { query ->
            if (query == "alice") {
                firstStarted.complete(Unit)
                try {
                    firstGate.await()
                    listOf(profile(ALICE, "Alice"))
                } catch (_: CancellationException) {
                    listOf(profile(ALICE, "Stale Alice"))
                }
            } else {
                listOf(profile(BOB, "Bob"))
            }
        }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("alice")
            firstStarted.await()
            fixture.store.setSearchQuery("bobby")
            awaitCondition {
                fixture.store.state.value.searchResults.singleOrNull()?.profile?.userId == BOB
            }
            firstGate.complete(Unit)
            yield()

            assertEquals("bobby", fixture.store.state.value.searchQuery)
            assertEquals(BOB, fixture.store.state.value.searchResults.single().profile.userId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedSearchCanBeRetriedWithoutChangingTheQuery() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        var searchCalls = 0
        fixture.searchBehavior = {
            searchCalls += 1
            if (searchCalls == 1) error("offline") else listOf(profile(ALICE, "Alice"))
        }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("alice")
            awaitCondition { fixture.store.state.value.searchErrorMessage == "offline" }

            fixture.store.retrySearch()
            awaitCondition { fixture.store.state.value.searchResults.isNotEmpty() }

            assertEquals("alice", fixture.store.state.value.searchQuery)
            assertEquals(2, searchCalls)
            assertNull(fixture.store.state.value.searchErrorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingSessionRejectsLateMemberSnapshotForTheSameRoom() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        val oldServerStarted = CompletableDeferred<Unit>()
        val oldServerGate = CompletableDeferred<Unit>()
        var serverCalls = 0
        fixture.membersBehavior = { source ->
            if (source == InviteMembersSource.CACHE) {
                emptyList()
            } else {
                serverCalls += 1
                if (serverCalls == 1) {
                    oldServerStarted.complete(Unit)
                    withContext(NonCancellable) {
                        oldServerGate.await()
                        emptyList()
                    }
                } else {
                    listOf(member(ALICE, MatrixRoomMemberMembership.INVITED))
                }
            }
        }
        fixture.searchBehavior = { listOf(profile(ALICE, "Alice")) }
        try {
            fixture.store.activate(target(), seedCanInviteMembers = true)
            oldServerStarted.await()

            val replacement = InviteMembersTarget("@other:example.org", ROOM)
            fixture.store.activate(replacement, seedCanInviteMembers = true)
            awaitCondition {
                fixture.store.state.value.target == replacement &&
                    !fixture.store.state.value.isPreparing
            }
            oldServerGate.complete(Unit)
            yield()

            fixture.store.setSearchQuery("alice")
            awaitCondition { fixture.store.state.value.searchResults.isNotEmpty() }
            assertEquals(
                MatrixRoomMemberMembership.INVITED,
                fixture.store.state.value.searchResults.single().membership
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialSendKeepsFailuresSelectedAndMarksSuccessAsInvited() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        fixture.searchBehavior = {
            listOf(profile(ALICE, "Alice"), profile(BOB, "Bob"))
        }
        fixture.inviteBehavior = { userId ->
            if (userId == BOB) error("invite failed")
        }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("people")
            awaitCondition { fixture.store.state.value.searchResults.size == 2 }
            fixture.store.state.value.searchResults.forEach { candidate ->
                fixture.store.toggleSelection(candidate.profile)
            }

            fixture.store.sendInvites()
            awaitCondition { fixture.store.state.value.failedInviteCount == 1 }

            val state = fixture.store.state.value
            assertFalse(state.isSending)
            assertEquals(listOf(BOB), state.selectedMembers.map { it.profile.userId })
            assertEquals(
                MatrixRoomMemberMembership.INVITED,
                state.searchResults.single { it.profile.userId == ALICE }.membership
            )
            assertTrue(fixture.completions.isEmpty())
            assertEquals(listOf(ALICE, BOB), fixture.invitedUserIds)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successfulSendRechecksPermissionAndCompletesMatchingTarget() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        fixture.searchBehavior = { listOf(profile(ALICE, "Alice")) }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("alice")
            awaitCondition { fixture.store.state.value.searchResults.isNotEmpty() }
            fixture.store.toggleSelection(fixture.store.state.value.searchResults.single().profile)

            fixture.store.sendInvites()
            awaitCondition { fixture.completions.isNotEmpty() }

            assertEquals(target(), fixture.completions.single())
            assertEquals(listOf(ALICE), fixture.invitedUserIds)
            assertEquals(2, fixture.permissionChecks)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revokedPermissionPreventsEveryInviteAndKeepsSelection() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        fixture.searchBehavior = { listOf(profile(ALICE, "Alice")) }
        fixture.permissionBehavior = { checkIndex -> checkIndex == 1 }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("alice")
            awaitCondition { fixture.store.state.value.searchResults.isNotEmpty() }
            fixture.store.toggleSelection(fixture.store.state.value.searchResults.single().profile)

            fixture.store.sendInvites()
            awaitCondition { fixture.store.state.value.permissionDenied }

            assertFalse(fixture.store.state.value.canInviteMembers)
            assertEquals(ALICE, fixture.store.state.value.selectedMembers.single().profile.userId)
            assertTrue(fixture.invitedUserIds.isEmpty())
            assertTrue(fixture.completions.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leavingDuringFirstInvitePreventsSendingRemainingSelection() = runBlocking {
        val fixture = InviteMembersStoreFixture(coroutineContext)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        fixture.searchBehavior = {
            listOf(profile(ALICE, "Alice"), profile(BOB, "Bob"))
        }
        fixture.inviteBehavior = { userId ->
            if (userId == ALICE) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { firstGate.await() }
            }
        }
        try {
            fixture.activateReady()
            fixture.store.setSearchQuery("people")
            awaitCondition { fixture.store.state.value.searchResults.size == 2 }
            fixture.store.state.value.searchResults.forEach { candidate ->
                fixture.store.toggleSelection(candidate.profile)
            }
            fixture.store.sendInvites()
            firstStarted.await()

            fixture.store.deactivate()
            firstGate.complete(Unit)
            yield()

            assertEquals(InviteMembersState(), fixture.store.state.value)
            assertEquals(listOf(ALICE), fixture.invitedUserIds)
            assertTrue(fixture.completions.isEmpty())
        } finally {
            fixture.close()
        }
    }
}

private class InviteMembersStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val memberSources = mutableListOf<InviteMembersSource>()
    val searchTerms = mutableListOf<String>()
    val invitedUserIds = mutableListOf<String>()
    val completions = mutableListOf<InviteMembersTarget>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var permissionChecks = 0
    var permissionBehavior: suspend (Int) -> Boolean = { true }
    var membersBehavior: suspend (InviteMembersSource) -> List<MatrixRoomMember> = { emptyList() }
    var searchBehavior: suspend (String) -> List<MatrixUserProfile> = { emptyList() }
    var inviteBehavior: suspend (String) -> Unit = {}

    val store = InviteMembersStore(
        scope = scope,
        driver = InviteMembersDriver(
            canInviteMembers = {
                permissionChecks += 1
                permissionBehavior(permissionChecks)
            },
            loadMembers = { _, source ->
                memberSources += source
                membersBehavior(source)
            },
            searchUsers = { query, _ ->
                searchTerms += query
                searchBehavior(query)
            },
            inviteUser = { _, userId ->
                invitedUserIds += userId
                inviteBehavior(userId)
            },
            delayMillis = {}
        ),
        onAllInvitesSent = { target -> completions += target },
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun activateReady() {
        store.activate(target(), seedCanInviteMembers = true)
        awaitCondition {
            !store.state.value.isPreparing &&
                permissionChecks > 0 &&
                store.state.value.canInviteMembers
        }
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun target(): InviteMembersTarget = InviteMembersTarget(SESSION_USER, ROOM)

private fun profile(userId: String, displayName: String): MatrixUserProfile {
    return MatrixUserProfile(userId = userId, displayName = displayName, avatarUrl = null)
}

private fun member(
    userId: String,
    membership: MatrixRoomMemberMembership
): MatrixRoomMember {
    return MatrixRoomMember(
        userId = userId,
        displayName = null,
        avatarUrl = null,
        membership = membership,
        role = MatrixRoomMemberRole.MEMBER,
        powerLevel = 0,
        isNameAmbiguous = false
    )
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
