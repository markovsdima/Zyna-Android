package com.zyna.app.ui.chat

import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixTimelineUpdate
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    private val store = ChatTimelineStore<FakeWindowStore>(
        scope = scope,
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
    fun repeatedActivation_keepsSingleSubscription() {
        val target = target(roomId = ROOM_A)
        val windowStore = FakeWindowStore()

        store.activate(target, windowStore)
        store.activate(target, windowStore)

        assertTrue(windowStore.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))

        assertEquals(1, windowDeliveries.size)
        assertEquals(listOf(target), settledTargets)
        assertSame(windowStore, store.windowStoreFor(USER_A, ROOM_A))
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
        assertNull(store.windowStoreFor(USER_A, ROOM_A))
        assertSame(latestWindow, store.windowStoreFor(USER_A, ROOM_B))
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
        assertNull(store.windowStoreFor(USER_A, ROOM_A))
        assertSame(latestWindow, store.windowStoreFor(USER_B, ROOM_A))
    }

    @Test
    fun deactivate_stopsAllUpdatesAndClearsWindow() {
        val target = target(roomId = ROOM_A)
        val windowStore = FakeWindowStore()

        store.activate(target, windowStore)
        store.deactivate()

        assertTrue(windowStore.updates.tryEmit(windowUpdate()))
        assertTrue(timelineFlow(target).tryEmit(timelineUpdate()))

        assertTrue(windowDeliveries.isEmpty())
        assertTrue(settledTargets.isEmpty())
        assertNull(store.windowStoreFor(USER_A, ROOM_A))
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
    }

    private fun timelineFlow(
        target: ChatTimelineTarget
    ): MutableSharedFlow<MatrixTimelineUpdate> {
        return timelineFlows.getOrPut(target) {
            MutableSharedFlow(extraBufferCapacity = 8)
        }
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
        val refreshedFlushes: MutableList<TimelineFlushSummary> = mutableListOf()
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
