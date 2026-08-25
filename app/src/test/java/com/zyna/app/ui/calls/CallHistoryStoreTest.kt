package com.zyna.app.ui.calls

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_A = "@alice:example.org"
private const val USER_B = "@bob:example.org"
private const val HISTORY_LIMIT = 25
private const val NOW_MILLIS = 1_000L
private const val EXPIRY_GRACE_MILLIS = 250L

class CallHistoryStoreTest {
    @Test
    fun activationObservesAndRefreshesOnlyTheCurrentUser() = runBlocking {
        val fixture = CallHistoryStoreFixture(coroutineContext)
        fixture.calls(USER_A).value = listOf(callItem(eventId = "call-a"))
        fixture.calls(USER_B).value = listOf(callItem(eventId = "call-b"))
        try {
            fixture.store.activate(USER_A)
            awaitCondition { fixture.store.state.value.calls.singleOrNull()?.eventId == "call-a" }

            fixture.store.activate(USER_A)
            yield()

            assertEquals(listOf(USER_A), fixture.observedUsers)
            assertEquals(listOf(USER_A to HISTORY_LIMIT), fixture.refreshRequests)

            fixture.store.activate(USER_B)
            awaitCondition { fixture.store.state.value.calls.singleOrNull()?.eventId == "call-b" }

            assertEquals(listOf(USER_A, USER_B), fixture.observedUsers)
            assertEquals(
                listOf(USER_A to HISTORY_LIMIT, USER_B to HISTORY_LIMIT),
                fixture.refreshRequests
            )

            fixture.store.activate("   ")
            assertEquals(CallHistoryState(), fixture.store.state.value)
            assertEquals(listOf(USER_A, USER_B), fixture.observedUsers)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun projectionRefreshFailureIsLoggedWithoutReplacingReactiveContent() = runBlocking {
        val fixture = CallHistoryStoreFixture(coroutineContext)
        val calls = listOf(callItem(eventId = "cached"))
        fixture.calls(USER_A).value = calls
        fixture.refreshBehavior = { _, _ ->
            throw IllegalStateException("refresh failure")
        }
        try {
            fixture.store.activate(USER_A)
            awaitCondition {
                fixture.store.state.value.calls == calls && fixture.warnings.size == 1
            }

            assertEquals("refresh failure", fixture.warnings.single().second.message)
            assertEquals(CallHistoryState(calls = calls), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun nearestLiveStartedRingDeterminesExpiryRefreshDelay() {
        val calls = listOf(
            callItem(eventId = "expired", expiresAtMillis = 900L),
            callItem(eventId = "later", expiresAtMillis = 5_000L),
            callItem(eventId = "nearest", expiresAtMillis = 3_000L),
            callItem(
                eventId = "notification",
                notificationType = MatrixRtcCallNotificationType.NOTIFICATION,
                expiresAtMillis = 2_000L
            ),
            callItem(
                eventId = "answered",
                outcome = MatrixRtcCallHistoryOutcome.ANSWERED,
                expiresAtMillis = 1_500L
            )
        )

        assertEquals(
            2_250L,
            nextExpiryRefreshDelayMillis(
                calls = calls,
                nowMillis = NOW_MILLIS,
                graceMillis = EXPIRY_GRACE_MILLIS
            )
        )
        assertNull(
            nextExpiryRefreshDelayMillis(
                calls = calls.filter { it.eventId != "nearest" && it.eventId != "later" },
                nowMillis = NOW_MILLIS,
                graceMillis = EXPIRY_GRACE_MILLIS
            )
        )
    }

    @Test
    fun newerCacheSnapshotReplacesExpiryTimerAndExpiryRefreshesSilently() = runBlocking {
        val fixture = CallHistoryStoreFixture(coroutineContext)
        try {
            fixture.store.activate(USER_A)
            awaitCondition { fixture.refreshRequests.size == 1 }

            fixture.calls(USER_A).value = listOf(
                callItem(eventId = "later", expiresAtMillis = 5_000L)
            )
            awaitCondition { 4_250L in fixture.delays.requestedDurations }

            fixture.calls(USER_A).value = listOf(
                callItem(eventId = "nearer", expiresAtMillis = 3_000L)
            )
            awaitCondition {
                2_250L in fixture.delays.requestedDurations &&
                    4_250L in fixture.delays.cancelledDurations
            }

            fixture.delays.resume(2_250L)
            awaitCondition { fixture.refreshRequests.size == 2 }

            assertTrue(fixture.warnings.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deactivationCancelsCacheRefreshAndExpiryButCanPreserveContent() = runBlocking {
        val fixture = CallHistoryStoreFixture(coroutineContext)
        fixture.calls(USER_A).value = listOf(
            callItem(eventId = "ring", expiresAtMillis = 5_000L)
        )
        fixture.refreshBehavior = { userId, _ ->
            try {
                awaitCancellation()
            } finally {
                fixture.cancelledRefreshUsers += userId
            }
        }
        try {
            fixture.store.activate(USER_A)
            awaitCondition {
                fixture.store.state.value.calls.isNotEmpty() &&
                    4_250L in fixture.delays.requestedDurations &&
                    fixture.refreshRequests.isNotEmpty()
            }

            fixture.store.deactivate(clearState = false)

            awaitCondition {
                USER_A in fixture.completedCacheUsers &&
                    USER_A in fixture.cancelledRefreshUsers &&
                    4_250L in fixture.delays.cancelledDurations
            }
            assertEquals("ring", fixture.store.state.value.calls.single().eventId)
        } finally {
            fixture.close()
        }
    }

    private companion object {
        fun callItem(
            eventId: String,
            notificationType: MatrixRtcCallNotificationType =
                MatrixRtcCallNotificationType.RING,
            outcome: MatrixRtcCallHistoryOutcome = MatrixRtcCallHistoryOutcome.STARTED,
            expiresAtMillis: Long? = null
        ): MatrixRtcCallHistoryItem {
            return MatrixRtcCallHistoryItem(
                eventId = eventId,
                roomId = "!room:example.org",
                roomName = "Room",
                roomAvatarUrl = null,
                senderId = "@caller:example.org",
                senderDisplayName = "Caller",
                isOutgoing = false,
                timestampMillis = 500L,
                notificationType = notificationType,
                callIntent = "m.ring",
                outcome = outcome,
                expiresAtMillis = expiresAtMillis
            )
        }
    }
}

private class CallHistoryStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)
    private val callsByUserId = mutableMapOf<String, MutableStateFlow<List<MatrixRtcCallHistoryItem>>>()

    val observedUsers = mutableListOf<String>()
    val completedCacheUsers = mutableListOf<String>()
    val refreshRequests = mutableListOf<Pair<String, Int>>()
    val cancelledRefreshUsers = mutableListOf<String>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    val delays = CallHistoryControlledDelay()

    var refreshBehavior: suspend (String, Int) -> Unit = { _, _ -> }

    val store = CallHistoryStore(
        scope = scope,
        driver = CallHistoryDriver(
            observeCalls = { userId, _ ->
                calls(userId)
                    .onStart { observedUsers += userId }
                    .onCompletion { completedCacheUsers += userId }
            },
            refreshCalls = { userId, limit ->
                refreshRequests += userId to limit
                refreshBehavior(userId, limit)
            },
            nowMillis = { NOW_MILLIS },
            delayMillis = delays::delay
        ),
        historyLimit = HISTORY_LIMIT,
        expiryRefreshGraceMillis = EXPIRY_GRACE_MILLIS,
        onWarning = { message, error -> warnings += message to error }
    )

    fun calls(userId: String): MutableStateFlow<List<MatrixRtcCallHistoryItem>> {
        return callsByUserId.getOrPut(userId) { MutableStateFlow(emptyList()) }
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private class CallHistoryControlledDelay {
    val requestedDurations = mutableListOf<Long>()
    val cancelledDurations = mutableListOf<Long>()
    private val gates = mutableMapOf<Long, Channel<Unit>>()

    suspend fun delay(durationMillis: Long) {
        requestedDurations += durationMillis
        try {
            gate(durationMillis).receive()
        } catch (error: CancellationException) {
            cancelledDurations += durationMillis
            throw error
        }
    }

    fun resume(durationMillis: Long) {
        check(gate(durationMillis).trySend(Unit).isSuccess)
    }

    private fun gate(durationMillis: Long): Channel<Unit> {
        return gates.getOrPut(durationMillis) {
            Channel(capacity = Channel.UNLIMITED)
        }
    }
}

private suspend fun awaitCondition(predicate: () -> Boolean) {
    withTimeout(1_000L) {
        while (!predicate()) {
            yield()
        }
    }
}
