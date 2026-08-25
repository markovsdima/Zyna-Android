package com.zyna.app.ui.contacts

import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SESSION_USER = "@me:example.org"
private const val CONTACT_USER = "@alice:example.org"
private const val OWNER = "contacts"

class DirectRoomActionCoordinatorTest {
    @Test
    fun submitResolvesCachesAndDeliversResult() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        try {
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)

            assertEquals(CONTACT_USER, fixture.coordinator.state.value.activeUserId)
            assertEquals(
                DirectRoomActionIntent.OPEN_CHAT,
                fixture.coordinator.state.value.activeIntent
            )
            awaitCondition { fixture.results.isNotEmpty() }

            assertEquals(listOf(SESSION_USER to fixture.room), fixture.cachedRooms)
            assertEquals(DirectRoomActionIntent.OPEN_CHAT, fixture.results.single().request.intent)
            assertEquals(DirectRoomActionState(), fixture.coordinator.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun identicalPendingRequestIsCoalesced() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        val resolveGate = CompletableDeferred<Unit>()
        fixture.resolveBehavior = { _, _ ->
            resolveGate.await()
            fixture.room
        }
        try {
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)
            awaitCondition { fixture.resolveRequests.size == 1 }
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)
            yield()

            assertEquals(1, fixture.resolveRequests.size)
            resolveGate.complete(Unit)
            awaitCondition { fixture.results.size == 1 }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun changingIntentReplacesPendingRequestForSameUser() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        var invocation = 0
        fixture.resolveBehavior = { _, _ ->
            invocation += 1
            if (invocation == 1) {
                firstStarted.complete(Unit)
                try {
                    firstGate.await()
                    fixture.room.copy(displayName = "Unexpected")
                } catch (_: CancellationException) {
                    fixture.room.copy(displayName = "Stale")
                }
            } else {
                fixture.room
            }
        }
        try {
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)
            firstStarted.await()
            fixture.submit(DirectRoomActionIntent.START_CALL)
            awaitCondition { fixture.results.isNotEmpty() }
            firstGate.complete(Unit)
            yield()

            assertEquals(2, fixture.resolveRequests.size)
            assertEquals(1, fixture.results.size)
            assertEquals(DirectRoomActionIntent.START_CALL, fixture.results.single().request.intent)
            assertEquals(fixture.room, fixture.results.single().room)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cacheFailureIsBestEffortAndDoesNotBlockResult() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        fixture.cacheBehavior = { _, _ -> error("cache failed") }
        try {
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)
            awaitCondition { fixture.results.isNotEmpty() }

            assertEquals(fixture.room, fixture.results.single().room)
            assertEquals("Failed to cache resolved direct room", fixture.warnings.single().first)
            assertNull(fixture.coordinator.state.value.errorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidOwnerRejectsCompletionWithoutShowingError() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        val resolveGate = CompletableDeferred<Unit>()
        fixture.resolveBehavior = { _, _ ->
            resolveGate.await()
            fixture.room
        }
        try {
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)
            awaitCondition { fixture.resolveRequests.isNotEmpty() }
            fixture.canDeliver = false
            resolveGate.complete(Unit)
            awaitCondition { fixture.coordinator.state.value.activeUserId == null }

            assertTrue(fixture.results.isEmpty())
            assertTrue(fixture.warnings.isEmpty())
            assertNull(fixture.coordinator.state.value.errorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun resolutionFailureIsExposedForCurrentOwner() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        fixture.resolveBehavior = { _, _ -> error("resolve failed") }
        try {
            fixture.submit(DirectRoomActionIntent.START_CALL)
            awaitCondition { fixture.coordinator.state.value.errorMessage == "resolve failed" }

            assertTrue(fixture.results.isEmpty())
            assertEquals("Failed to resolve direct room", fixture.warnings.single().first)
            assertNull(fixture.coordinator.state.value.activeUserId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelRejectsLateCompletion() = runBlocking {
        val fixture = DirectRoomActionFixture(coroutineContext)
        val resolveStarted = CompletableDeferred<Unit>()
        val resolveGate = CompletableDeferred<Unit>()
        fixture.resolveBehavior = { _, _ ->
            resolveStarted.complete(Unit)
            try {
                resolveGate.await()
                fixture.room.copy(displayName = "Unexpected")
            } catch (_: CancellationException) {
                fixture.room.copy(displayName = "Stale")
            }
        }
        try {
            fixture.submit(DirectRoomActionIntent.OPEN_CHAT)
            resolveStarted.await()
            fixture.coordinator.cancel()
            resolveGate.complete(Unit)
            yield()

            assertEquals(DirectRoomActionState(), fixture.coordinator.state.value)
            assertTrue(fixture.results.isEmpty())
            assertTrue(fixture.cachedRooms.isEmpty())
        } finally {
            fixture.close()
        }
    }
}

private class DirectRoomActionFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val room = MatrixRoomSummary(
        id = "!alice:example.org",
        displayName = "Alice",
        avatarUrl = null,
        directUserId = CONTACT_USER
    )
    val contact = MatrixContact(
        userId = CONTACT_USER,
        displayName = "Alice",
        avatarUrl = null,
        roomId = null
    )
    val resolveRequests = mutableListOf<MatrixContact>()
    val cachedRooms = mutableListOf<Pair<String, MatrixRoomSummary>>()
    val results = mutableListOf<ResolvedDirectRoomAction>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var canDeliver = true
    var resolveBehavior: suspend (
        MatrixContact,
        List<MatrixRoomSummary>
    ) -> MatrixRoomSummary = { _, _ -> room }
    var cacheBehavior: suspend (String, MatrixRoomSummary) -> Unit = { _, _ -> }

    val coordinator = DirectRoomActionCoordinator(
        scope = scope,
        driver = DirectRoomActionDriver(
            resolveRoom = { target, rooms ->
                resolveRequests += target
                resolveBehavior(target, rooms)
            },
            cacheRoom = { userId, resolvedRoom ->
                cachedRooms += userId to resolvedRoom
                cacheBehavior(userId, resolvedRoom)
            }
        ),
        canDeliver = { canDeliver },
        onResolved = results::add,
        onWarning = { message, error -> warnings += message to error }
    )

    fun submit(intent: DirectRoomActionIntent) {
        coordinator.submit(
            sessionUserId = SESSION_USER,
            ownerKey = OWNER,
            contact = contact,
            rooms = emptyList(),
            intent = intent
        )
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
