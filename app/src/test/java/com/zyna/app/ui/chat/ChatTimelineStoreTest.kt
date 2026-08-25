package com.zyna.app.ui.chat

import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixTimelineUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val timelineFlows = mutableMapOf<ChatTimelineTarget, MutableSharedFlow<MatrixTimelineUpdate>>()
    private val timelineOverrides = mutableMapOf<ChatTimelineTarget, Flow<MatrixTimelineUpdate>>()
    private val cachedCallTargets = mutableListOf<ChatTimelineTarget>()
    private val cachedMessages = mutableListOf<Pair<ChatTimelineTarget, List<MatrixChatMessage>>>()
    private val windowDeliveries = mutableListOf<WindowDelivery>()
    private val settledTargets = mutableListOf<ChatTimelineTarget>()
    private val errors = mutableListOf<Pair<ChatTimelineTarget, Throwable>>()
    private val initialSnapshotErrors = mutableListOf<Pair<ChatTimelineTarget, Throwable>>()
    private val pendingBootstrapWindows =
        mutableMapOf<ChatTimelineTarget, MutableList<FakeWindowStore>>()
    private val store = ChatTimelineStore<FakeWindowStore>(
        scope = scope,
        bootstrapDriver = ChatTimelineBootstrapDriver(
            createWindowStore = { target ->
                val windows = pendingBootstrapWindows[target]
                if (windows.isNullOrEmpty()) {
                    FakeWindowStore()
                } else {
                    windows.removeAt(0)
                }
            },
            initialMessagesSnapshot = { _, windowStore ->
                windowStore.initialSnapshotError?.let { throw it }
                val gate = windowStore.initialSnapshotGate
                when {
                    gate == null -> windowStore.initialMessages
                    windowStore.ignoreSnapshotCancellation -> {
                        try {
                            gate.await()
                        } catch (_: CancellationException) {
                            windowStore.initialMessages
                        }
                    }
                    else -> gate.await()
                }
            }
        ),
        onInitialSnapshotError = { target, error ->
            initialSnapshotErrors += target to error
        },
        observeTimeline = { target ->
            timelineOverrides[target] ?: timelineFlow(target)
        },
        observeWindow = { windowStore -> windowStore.updates },
        isWindowAtLiveEdge = { windowStore -> windowStore.isAtLiveEdge },
        recordTimelineFlush = { windowStore, summary ->
            windowStore.recordedFlushes += summary
        },
        cacheCallEvents = { target, _ -> cachedCallTargets += target },
        cacheMessages = { target, messages -> cachedMessages += target to messages },
        refreshInitialWindow = { _, windowStore, summary ->
            windowStore.refreshedFlushes += summary
        },
        onWindowUpdate = { target, update, isAtLiveEdge ->
            windowDeliveries += WindowDelivery(target, update, isAtLiveEdge)
        },
        onTimelineSettled = { target, _ -> settledTargets += target },
        onTimelineError = { target, error -> errors += target to error }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun open_loadsInitialSnapshotAndActivatesSubscriptions() {
        val target = target(roomId = ROOM_A)
        val initialMessage = message(EVENT_A)
        val windowStore = FakeWindowStore(initialMessages = listOf(initialMessage))
        enqueueBootstrapWindow(target, windowStore)

        store.open(target)

        assertEquals(ROOM_A, store.state.value.roomId)
        assertEquals(listOf(initialMessage), store.state.value.messages)
        assertFalse(store.state.value.isLoading)

        assertTrue(windowStore.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))
        assertEquals(listOf(target), windowDeliveries.map { it.target })
        assertEquals(listOf(target), settledTargets)
    }

    @Test
    fun open_notifiesActivationAfterBootstrap() {
        val target = target(roomId = ROOM_A)
        val activatedTargets = mutableListOf<ChatTimelineTarget>()
        enqueueBootstrapWindow(
            target,
            FakeWindowStore(initialMessages = listOf(message(EVENT_A)))
        )

        store.open(target) { activatedTarget ->
            activatedTargets += activatedTarget
            store.jumpToEvent(activatedTarget, EVENT_A)
        }

        assertEquals(listOf(target), activatedTargets)
        assertEquals(EVENT_A, store.state.value.jumpTargetEventId)
        assertEquals(
            TimelineWindowChangeOrigin.JUMP,
            store.state.value.windowChangeOrigin
        )
    }

    @Test
    fun failedInitialSnapshot_reportsErrorAndStillActivatesTimeline() {
        val target = target(roomId = ROOM_A)
        val expected = IllegalStateException("snapshot failed")
        enqueueBootstrapWindow(
            target,
            FakeWindowStore(initialSnapshotError = expected)
        )

        store.open(target)
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))

        assertEquals(listOf(target to expected), initialSnapshotErrors)
        assertEquals(listOf(target), settledTargets)
        assertFalse(store.state.value.isLoading)
    }

    @Test
    fun deactivate_ignoresPendingBootstrapCompletion() {
        val target = target(roomId = ROOM_A)
        val gate = CompletableDeferred<List<MatrixChatMessage>>()
        var didActivate = false
        enqueueBootstrapWindow(
            target,
            FakeWindowStore(
                initialSnapshotGate = gate,
                ignoreSnapshotCancellation = true
            )
        )

        store.open(target) { didActivate = true }
        store.deactivate()
        gate.complete(listOf(message(EVENT_A)))

        assertEquals(ChatTimelineState(), store.state.value)
        assertFalse(didActivate)
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))
        assertTrue(settledTargets.isEmpty())
    }

    @Test
    fun reopeningSameRoom_ignoresFirstBootstrapCompletion() {
        val targetA = target(roomId = ROOM_A)
        val targetB = target(roomId = ROOM_B)
        val firstGate = CompletableDeferred<List<MatrixChatMessage>>()
        val firstMessage = message("\$first-a")
        val latestMessage = message("\$latest-a")
        var didActivateFirstBootstrap = false
        enqueueBootstrapWindow(
            targetA,
            FakeWindowStore(
                initialSnapshotGate = firstGate,
                ignoreSnapshotCancellation = true,
                initialMessages = listOf(firstMessage)
            )
        )
        enqueueBootstrapWindow(
            targetB,
            FakeWindowStore(initialMessages = listOf(message("\$room-b")))
        )
        enqueueBootstrapWindow(
            targetA,
            FakeWindowStore(initialMessages = listOf(latestMessage))
        )

        store.open(targetA) { didActivateFirstBootstrap = true }
        store.open(targetB)
        store.open(targetA)

        assertFalse(didActivateFirstBootstrap)
        assertEquals(ROOM_A, store.state.value.roomId)
        assertEquals(listOf(latestMessage), store.state.value.messages)
    }

    @Test
    fun repeatedActivation_keepsSingleSubscription() {
        val target = target(roomId = ROOM_A)
        val windowStore = FakeWindowStore()

        store.activate(target, windowStore)
        store.activate(target, windowStore)

        assertTrue(windowStore.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))

        assertEquals(1, windowDeliveries.size)
        assertEquals(listOf(target), settledTargets)
    }

    @Test
    fun switchingRoom_ignoresOldWindowAndTimelineUpdates() {
        val firstTarget = target(roomId = ROOM_A)
        val latestTarget = target(roomId = ROOM_B)
        val firstWindow = FakeWindowStore()
        val latestWindow = FakeWindowStore()

        store.activate(firstTarget, firstWindow)
        store.activate(latestTarget, latestWindow)

        assertTrue(firstWindow.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(firstTarget).tryEmit(timelineUpdate()))
        assertTrue(latestWindow.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(latestTarget).tryEmit(timelineUpdate()))

        assertEquals(listOf(latestTarget), windowDeliveries.map { it.target })
        assertEquals(listOf(latestTarget), settledTargets)
    }

    @Test
    fun switchingSessionForSameRoom_ignoresPreviousSession() {
        val firstTarget = target(userId = USER_A, roomId = ROOM_A)
        val latestTarget = target(userId = USER_B, roomId = ROOM_A)
        val latestWindow = FakeWindowStore()

        store.activate(firstTarget, FakeWindowStore())
        store.activate(latestTarget, latestWindow)

        assertTrue(timelineFlow(firstTarget).tryEmit(timelineUpdate()))
        assertTrue(timelineFlow(latestTarget).tryEmit(timelineUpdate()))

        assertEquals(listOf(latestTarget), settledTargets)
    }

    @Test
    fun deactivate_stopsAllUpdatesAndClearsWindow() {
        val target = target(roomId = ROOM_A)
        val windowStore = FakeWindowStore()

        store.prepareRoom(target)
        store.activate(target, windowStore)
        store.deactivate()

        assertTrue(windowStore.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))

        assertTrue(windowDeliveries.isEmpty())
        assertTrue(settledTargets.isEmpty())
        assertEquals(ChatTimelineState(), store.state.value)
    }

    @Test
    fun roomPreparationAndInitialSnapshot_areOwnedByStore() {
        val target = target(roomId = ROOM_A)
        val initialMessage = message(EVENT_A)

        store.prepareRoom(target)

        assertEquals(
            ChatTimelineState(
                roomId = ROOM_A,
                isLoading = true,
                canLoadOlder = false
            ),
            store.state.value
        )

        store.applyInitialSnapshot(target, listOf(initialMessage))
        store.applyInitialSnapshot(target(roomId = ROOM_B), emptyList())

        assertEquals(
            ChatTimelineState(
                roomId = ROOM_A,
                messages = listOf(initialMessage),
                isLoading = false
            ),
            store.state.value
        )
    }

    @Test
    fun activeUpdatesAndSettlement_projectTimelineState() {
        val target = target(roomId = ROOM_A)
        val windowStore = FakeWindowStore().apply {
            isAtLiveEdge = false
        }
        val message = message(EVENT_A)
        store.prepareRoom(target)
        store.applyInitialSnapshot(target, emptyList())
        store.activate(target, windowStore)

        assertTrue(
            windowStore.updates.tryEmit(
                TimelineWindowUpdate(
                    messages = listOf(message),
                    origin = TimelineWindowChangeOrigin.JUMP,
                    hasOlderInDb = true,
                    hasNewerInDb = true,
                    flushSummary = null
                )
            )
        )

        assertEquals(listOf(message), store.state.value.messages)
        assertEquals(TimelineWindowChangeOrigin.JUMP, store.state.value.windowChangeOrigin)
        assertFalse(store.state.value.isLoading)
        assertTrue(store.state.value.canLoadNewer)
        assertFalse(store.state.value.isAtLiveEdge)

        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))

        assertFalse(store.state.value.isLoading)
        assertFalse(store.state.value.isLoadingWindowOperation)
        assertEquals(null, store.state.value.errorMessage)
    }

    @Test
    fun activeTimelineUpdate_isCachedAndRefreshesItsWindow() {
        val target = target(roomId = ROOM_A)
        val windowStore = FakeWindowStore().apply {
            isAtLiveEdge = false
        }
        val message = message(EVENT_A)
        val flushSummary = TimelineFlushSummary(pushBackCount = 1)

        store.activate(target, windowStore)

        assertTrue(
            timelineFlow(target).tryEmit(
                MatrixTimelineUpdate(
                    messages = listOf(message),
                    flushSummary = flushSummary
                )
            )
        )
        assertTrue(
            windowStore.updates.tryEmit(
                windowUpdate(flushSummary = flushSummary)
            )
        )

        assertEquals(listOf(flushSummary), windowStore.recordedFlushes)
        assertEquals(listOf(target to listOf(message)), cachedMessages)
        assertEquals(listOf(flushSummary), windowStore.refreshedFlushes)
        assertEquals(listOf(target), settledTargets)
        assertFalse(windowDeliveries.single().isAtLiveEdge)
    }

    @Test
    fun activeTimelineFailure_isReportedOnce() {
        val target = target(roomId = ROOM_A)
        val expected = IllegalStateException("timeline failed")
        timelineOverrides[target] = flow { throw expected }

        store.activate(target, FakeWindowStore())

        assertEquals(listOf(target to expected), errors)
        assertEquals("timeline failed", store.state.value.errorMessage)
        assertFalse(store.state.value.isLoading)
    }

    @Test
    fun windowOperations_areMutuallyExclusiveAndCanBeCancelled() {
        val gate = CompletableDeferred<Unit>()
        var firstCompleted = false
        var rejectedOperationStarted = false
        var replacementStarted = false

        assertTrue(
            store.launchWindowOperation {
                gate.await()
                firstCompleted = true
            }
        )
        assertTrue(store.hasActiveWindowOperation())
        assertFalse(
            store.launchWindowOperation {
                rejectedOperationStarted = true
            }
        )

        store.cancelWindowOperation()

        assertFalse(store.hasActiveWindowOperation())
        assertFalse(firstCompleted)
        assertFalse(rejectedOperationStarted)
        assertTrue(
            store.launchWindowOperation {
                replacementStarted = true
            }
        )
        assertTrue(replacementStarted)
        assertFalse(store.hasActiveWindowOperation())
    }

    @Test
    fun timelineReplacement_cancelsActiveWindowOperation() {
        val gate = CompletableDeferred<Unit>()
        var operationCompleted = false

        store.activate(target(roomId = ROOM_A), FakeWindowStore())
        assertTrue(
            store.launchWindowOperation {
                gate.await()
                operationCompleted = true
            }
        )

        store.activate(target(roomId = ROOM_B), FakeWindowStore())
        gate.complete(Unit)

        assertFalse(operationCompleted)
        assertFalse(store.hasActiveWindowOperation())
    }

    private fun timelineFlow(
        target: ChatTimelineTarget
    ): MutableSharedFlow<MatrixTimelineUpdate> {
        return timelineFlows.getOrPut(target) {
            MutableSharedFlow(extraBufferCapacity = 8)
        }
    }

    private fun enqueueBootstrapWindow(
        target: ChatTimelineTarget,
        windowStore: FakeWindowStore
    ) {
        pendingBootstrapWindows.getOrPut(target) { mutableListOf() } += windowStore
    }

    private fun target(
        userId: String = USER_A,
        roomId: String
    ): ChatTimelineTarget {
        return ChatTimelineTarget(
            userId = userId,
            roomId = roomId
        )
    }

    private fun timelineUpdate(): MatrixTimelineUpdate {
        return MatrixTimelineUpdate(
            messages = emptyList(),
            flushSummary = TimelineFlushSummary()
        )
    }

    private fun windowUpdate(
        flushSummary: TimelineFlushSummary? = null
    ): TimelineWindowUpdate<MatrixChatMessage> {
        return TimelineWindowUpdate(
            messages = emptyList(),
            origin = TimelineWindowChangeOrigin.TIMELINE_FLUSH,
            hasOlderInDb = false,
            hasNewerInDb = false,
            flushSummary = flushSummary
        )
    }

    private fun message(eventId: String): MatrixChatMessage {
        return MatrixChatMessage(
            id = eventId,
            eventId = eventId,
            sender = USER_B,
            body = "Message",
            timestampMillis = 1L,
            isOwn = false
        )
    }

    private data class FakeWindowStore(
        val updates: MutableSharedFlow<TimelineWindowUpdate<MatrixChatMessage>> =
            MutableSharedFlow(extraBufferCapacity = 8),
        var isAtLiveEdge: Boolean = true,
        val recordedFlushes: MutableList<TimelineFlushSummary> = mutableListOf(),
        val refreshedFlushes: MutableList<TimelineFlushSummary> = mutableListOf(),
        val initialMessages: List<MatrixChatMessage> = emptyList(),
        val initialSnapshotGate: CompletableDeferred<List<MatrixChatMessage>>? = null,
        val ignoreSnapshotCancellation: Boolean = false,
        val initialSnapshotError: Throwable? = null
    )

    private data class WindowDelivery(
        val target: ChatTimelineTarget,
        val update: TimelineWindowUpdate<MatrixChatMessage>,
        val isAtLiveEdge: Boolean
    )

    private companion object {
        const val USER_A = "@alice:example.org"
        const val USER_B = "@bob:example.org"
        const val ROOM_A = "!alpha:example.org"
        const val ROOM_B = "!beta:example.org"
        const val EVENT_A = "\$event-a"
    }
}
