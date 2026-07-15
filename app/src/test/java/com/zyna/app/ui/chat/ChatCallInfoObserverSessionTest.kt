package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotificationKind
import com.zyna.app.data.matrix.MatrixRoomCallInfo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ROOM_ID = "!room:example.org"
private const val USER_ID = "@me:example.org"
private const val ALICE = "@alice:example.org"
private const val BOB = "@bob:example.org"
private const val NOW_MILLIS = 1_000L
private const val RING_EXPIRY_MILLIS = 10_000L
private const val RING_EXPIRY_DELAY_MS = RING_EXPIRY_MILLIS - NOW_MILLIS

private fun inactiveCallInfo(): MatrixRoomCallInfo = callInfo(
    hasRoomCall = false,
    isAudioCall = false
)

private fun callInfo(
    hasRoomCall: Boolean,
    participants: List<String> = emptyList(),
    isAudioCall: Boolean
): MatrixRoomCallInfo {
    return MatrixRoomCallInfo(
        roomId = ROOM_ID,
        hasRoomCall = hasRoomCall,
        activeParticipantUserIds = participants,
        isAudioCall = isAudioCall
    )
}

private fun ringNotification(): MatrixIncomingRtcCallNotification {
    return MatrixIncomingRtcCallNotification(
        eventId = "ring-event",
        roomId = ROOM_ID,
        senderId = ALICE,
        kind = MatrixIncomingRtcCallNotificationKind.RING,
        isAudioCall = true,
        expiresAtMillis = RING_EXPIRY_MILLIS
    )
}

class ChatCallInfoObserverSessionTest {
    @Test
    fun membershipFallback_isUsedOnlyBeforeRoomFlowObservesAnActiveCall() = runBlocking {
        val fixture = ObserverSessionFixture(coroutineContext)
        fixture.fallbackResult = callInfo(
            hasRoomCall = true,
            participants = listOf(ALICE),
            isAudioCall = true
        )
        try {
            fixture.start()

            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.activeParticipantUserIds == listOf(ALICE)
            }
            assertEquals(1, fixture.fallbackLoadCount)
            awaitCondition {
                MEMBERSHIP_FALLBACK_VALIDATION_DELAY_MS in
                    fixture.delays.requestedDurations
            }

            fixture.delays.resume(MEMBERSHIP_FALLBACK_VALIDATION_DELAY_MS)
            awaitCondition { fixture.fallbackLoadCount == 2 }

            fixture.roomInfo.value = callInfo(
                hasRoomCall = true,
                participants = listOf(BOB),
                isAudioCall = true
            )
            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.activeParticipantUserIds == listOf(BOB)
            }

            fixture.roomInfo.value = inactiveCallInfo()
            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.hasRoomCall == false
            }

            assertEquals(2, fixture.fallbackLoadCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ringWithoutMembership_isShownThenClearedAfterValidation() = runBlocking {
        val fixture = ObserverSessionFixture(coroutineContext)
        fixture.hasActiveMembershipResult = false
        try {
            fixture.start()
            fixture.awaitNotificationCollector()
            fixture.notifications.emit(ringNotification())

            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.let { info ->
                    info.hasRoomCall && info.activeParticipantUserIds == listOf(ALICE)
                } == true
            }
            awaitCondition {
                RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS in
                    fixture.delays.requestedDurations
            }

            fixture.delays.resume(RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS)

            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.hasRoomCall == false
            }
            assertEquals(listOf(ROOM_ID to ALICE), fixture.membershipChecks)
            assertTrue(
                fixture.logs.any {
                    it.contains("reason=membershipNotObserved") && it.contains("cleared")
                }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ringOverride_isClearedAtNotificationExpiry() = runBlocking {
        val fixture = ObserverSessionFixture(coroutineContext)
        try {
            fixture.start()
            fixture.awaitNotificationCollector()
            fixture.notifications.emit(ringNotification())

            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.hasRoomCall == true
            }
            awaitCondition { RING_EXPIRY_DELAY_MS in fixture.delays.requestedDurations }

            fixture.delays.resume(RING_EXPIRY_DELAY_MS)

            awaitCondition {
                fixture.observedCallInfo.lastOrNull()?.hasRoomCall == false
            }
            assertTrue(fixture.membershipChecks.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellingObservation_cancelsRingExpiryAndValidation() = runBlocking {
        val fixture = ObserverSessionFixture(coroutineContext)
        try {
            val observationJob = fixture.start()
            fixture.awaitNotificationCollector()
            fixture.notifications.emit(ringNotification())
            awaitCondition {
                fixture.delays.requestedDurations.containsAll(
                    listOf(
                        RING_EXPIRY_DELAY_MS,
                        RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS
                    )
                )
            }

            observationJob.cancelAndJoin()

            awaitCondition {
                fixture.delays.cancelledDurations.containsAll(
                    listOf(
                        RING_EXPIRY_DELAY_MS,
                        RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS
                    )
                )
            }
            assertFalse(observationJob.isActive)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun roomFlowFailure_isReportedForTheObservedTarget() = runBlocking {
        val fixture = ObserverSessionFixture(coroutineContext)
        val failure = IllegalStateException("room flow failed")
        fixture.roomUpdates = flow { throw failure }
        try {
            val observationJob = fixture.start()

            awaitCondition { fixture.observationErrors.isNotEmpty() }
            observationJob.join()

            assertEquals(1, fixture.observationErrors.size)
            assertEquals(failure.message, fixture.observationErrors.single().message)
            assertTrue(fixture.observedCallInfo.isEmpty())
        } finally {
            fixture.close()
        }
    }
}

private class ObserverSessionFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val roomInfo = MutableStateFlow(inactiveCallInfo())
    var roomUpdates: Flow<MatrixRoomCallInfo> = roomInfo
    val nativeCallState = MutableStateFlow(Unit)
    val notifications = MutableSharedFlow<MatrixIncomingRtcCallNotification>(
        extraBufferCapacity = 1
    )
    val observedCallInfo = mutableListOf<MatrixRoomCallInfo>()
    val observationErrors = mutableListOf<Throwable>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    val logs = mutableListOf<String>()
    val membershipChecks = mutableListOf<Pair<String, String>>()
    val delays = ControlledDelay()

    var fallbackResult: MatrixRoomCallInfo = inactiveCallInfo()
    var fallbackLoadCount = 0
    var hasActiveMembershipResult = true

    private val session = ChatCallInfoObserverSession(
        driver = ChatCallInfoObserverDriver(
            roomCallInfoUpdates = { roomId ->
                check(roomId == ROOM_ID)
                roomUpdates
            },
            nativeCallStateUpdates = nativeCallState,
            incomingCallNotifications = notifications,
            loadRoomCallInfo = { roomId ->
                check(roomId == ROOM_ID)
                fallbackLoadCount += 1
                fallbackResult
            },
            hasActiveMembership = { roomId, senderId ->
                membershipChecks += roomId to senderId
                hasActiveMembershipResult
            },
            nowMillis = { NOW_MILLIS },
            delayMillis = delays::delay
        ),
        onCallInfo = { target, callInfo ->
            check(target == TARGET)
            observedCallInfo += callInfo
        },
        onObservationError = { target, error ->
            check(target == TARGET)
            observationErrors += error
        },
        onWarning = { message, error -> warnings += message to error },
        onLog = logs::add
    )

    fun start(): Job = scope.launch {
        session.observe(TARGET)
    }

    suspend fun awaitNotificationCollector() {
        awaitCondition { notifications.subscriptionCount.value > 0 }
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }

    private companion object {
        val TARGET = ChatCallInfoTarget(
            userId = USER_ID,
            roomId = ROOM_ID
        )
    }
}

private class ControlledDelay {
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
