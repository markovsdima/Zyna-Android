package com.zyna.app.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadReceiptCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun candidate_requiresMatchingActiveRoomAndBaselinePermission() {
        val sends = mutableListOf<Pair<String, String>>()
        var requestedDelayMillis: Long? = null
        val coordinator = coordinator(
            sends = sends,
            delayBeforeSend = { delayMillis ->
                requestedDelayMillis = delayMillis
            }
        )

        coordinator.updateVisibleCandidate(
            activeRoomId = null,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = OTHER_ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = false
        )

        assertTrue(sends.isEmpty())

        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )

        assertEquals(250L, requestedDelayMillis)
        assertEquals(listOf(ROOM_ID to EVENT_A), sends)
    }

    @Test
    fun newerCandidateSupersedesPendingBootstrap() {
        val delayGate = CompletableDeferred<Unit>()
        val sends = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(
            sends = sends,
            delayBeforeSend = { delayGate.await() }
        )

        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_B,
            canEstablishBaseline = true
        )

        assertTrue(sends.isEmpty())

        delayGate.complete(Unit)

        assertEquals(listOf(ROOM_ID to EVENT_B), sends)
    }

    @Test
    fun blankCandidateCancelsPendingSendWithoutClearingBaseline() {
        val delayGate = CompletableDeferred<Unit>()
        var delayInvocation = 0
        val sends = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(
            sends = sends,
            delayBeforeSend = {
                delayInvocation += 1
                if (delayInvocation > 1) {
                    delayGate.await()
                }
            }
        )

        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_B,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = null,
            canEstablishBaseline = true
        )

        delayGate.complete(Unit)

        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_B,
            canEstablishBaseline = false
        )

        assertEquals(
            listOf(
                ROOM_ID to EVENT_A,
                ROOM_ID to EVENT_B
            ),
            sends
        )
    }

    @Test
    fun establishedBaselineAdvancesOnlyToNewerEvents() {
        val sends = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(sends = sends)

        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_B,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_B,
            canEstablishBaseline = true
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_C,
            canEstablishBaseline = false
        )

        assertEquals(
            listOf(
                ROOM_ID to EVENT_B,
                ROOM_ID to EVENT_C
            ),
            sends
        )
    }

    @Test
    fun failedSendDoesNotEstablishBaselineAndCanBeRetried() {
        var attempts = 0
        val failures = mutableListOf<Throwable>()
        val coordinator = coordinator(
            sends = mutableListOf(),
            sendReadReceipt = { _, _ ->
                attempts += 1
                if (attempts == 1) {
                    error("send failed")
                }
                true
            },
            onSendFailure = failures::add
        )

        repeat(2) {
            coordinator.updateVisibleCandidate(
                activeRoomId = ROOM_ID,
                roomId = ROOM_ID,
                eventId = EVENT_A,
                canEstablishBaseline = true
            )
        }

        assertEquals(2, attempts)
        assertEquals(1, failures.size)
    }

    @Test
    fun reset_requiresNewBaseline() {
        val sends = mutableListOf<Pair<String, String>>()
        val coordinator = coordinator(sends = sends)

        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )
        coordinator.reset()
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = false
        )
        coordinator.updateVisibleCandidate(
            activeRoomId = ROOM_ID,
            roomId = ROOM_ID,
            eventId = EVENT_A,
            canEstablishBaseline = true
        )

        assertEquals(
            listOf(
                ROOM_ID to EVENT_A,
                ROOM_ID to EVENT_A
            ),
            sends
        )
    }

    private fun coordinator(
        sends: MutableList<Pair<String, String>>,
        sendReadReceipt: suspend (String, String) -> Boolean = { roomId, eventId ->
            sends += roomId to eventId
            true
        },
        onSendFailure: (Throwable) -> Unit = {},
        delayBeforeSend: suspend (Long) -> Unit = {}
    ): ChatReadReceiptCoordinator {
        val events = listOf(EVENT_A, EVENT_B, EVENT_C)
        return ChatReadReceiptCoordinator(
            scope = scope,
            messageIndex = { eventId ->
                events.indexOf(eventId).takeIf { it >= 0 }
            },
            sendReadReceipt = sendReadReceipt,
            onSendFailure = onSendFailure,
            delayBeforeSend = delayBeforeSend
        )
    }

    private companion object {
        const val ROOM_ID = "!room:example.org"
        const val OTHER_ROOM_ID = "!other:example.org"
        const val EVENT_A = "\$event-a"
        const val EVENT_B = "\$event-b"
        const val EVENT_C = "\$event-c"
    }
}
