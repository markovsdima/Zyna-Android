package com.zyna.app.ui.chat

import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixTimelineUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelinePaginationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val backwardTargets = mutableListOf<ChatTimelineTarget>()
    private val forwardTargets = mutableListOf<ChatTimelineTarget>()
    private val results = mutableListOf<Pair<ChatTimelineTarget, ChatTimelinePaginationResult>>()
    private val errors = mutableListOf<Pair<ChatTimelineTarget, Throwable>>()
    private var hasReachedStart = false
    private var hasReachedEnd = false
    private var backwardError: Throwable? = null
    private var forwardError: Throwable? = null
    private val store = ChatTimelineStore<FakeWindowStore>(
        scope = scope,
        observeTimeline = { emptyFlow<MatrixTimelineUpdate>() },
        observeWindow = { emptyFlow<TimelineWindowUpdate<MatrixChatMessage>>() },
        isWindowAtLiveEdge = { it.state.isAtLiveEdge },
        recordTimelineFlush = { _, _ -> },
        cacheCallEvents = { _, _ -> },
        cacheMessages = { _, _ -> },
        refreshInitialWindow = { _, _, _ -> },
        onWindowUpdate = { _, _, _ -> },
        onTimelineSettled = { _, _ -> },
        onTimelineError = { _, _ -> },
        paginationDriver = ChatTimelinePaginationDriver(
            expandOlderFromCache = { windowStore ->
                windowStore.expandOlderCalls += 1
                windowStore.expandOlderResult
            },
            expandOlderAfterMaterialization = { windowStore ->
                windowStore.expandOlderAfterMaterializationCalls += 1
                windowStore.expandOlderAfterMaterializationResult
            },
            expandNewerFromCache = { windowStore ->
                windowStore.expandNewerCalls += 1
                windowStore.expandNewerResult
            },
            expandNewerAfterMaterialization = { windowStore ->
                windowStore.expandNewerAfterMaterializationCalls += 1
                windowStore.expandNewerAfterMaterializationResult
            },
            markNewerFullyLoaded = { windowStore ->
                windowStore.markNewerFullyLoadedCalls += 1
                windowStore.state = windowStore.state.copy(
                    canLoadNewer = false,
                    isAtLiveEdge = true
                )
            },
            windowState = { windowStore -> windowStore.state },
            paginateBackwards = { target ->
                backwardTargets += target
                backwardError?.let { throw it }
                hasReachedStart
            },
            paginateForwards = { target ->
                forwardTargets += target
                forwardError?.let { throw it }
                hasReachedEnd
            }
        ),
        onPaginationResult = { target, result -> results += target to result },
        onPaginationError = { target, error -> errors += target to error }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pagination_requiresMatchingActiveTimeline() {
        assertFalse(store.loadOlder(TARGET))
        assertFalse(store.loadNewer(TARGET))
        assertTrue(results.isEmpty())
    }

    @Test
    fun olderPagination_usesCachedWindowBeforeMatrix() {
        val windowStore = FakeWindowStore(
            expandOlderResult = true,
            state = ChatTimelineWindowState(
                canLoadOlder = false,
                canLoadNewer = true,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.loadOlder(TARGET))

        assertTrue(backwardTargets.isEmpty())
        assertEquals(0, windowStore.expandOlderAfterMaterializationCalls)
        assertEquals(
            listOf(
                TARGET to ChatTimelinePaginationResult(
                    canLoadOlder = true,
                    canLoadNewer = true,
                    isAtLiveEdge = false
                )
            ),
            results
        )
    }

    @Test
    fun olderPagination_fallsBackToMatrixAndFreshCache() {
        hasReachedStart = true
        val windowStore = FakeWindowStore(
            expandOlderAfterMaterializationResult = true,
            state = ChatTimelineWindowState(
                canLoadOlder = true,
                canLoadNewer = false,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.loadOlder(TARGET))

        assertEquals(listOf(TARGET), backwardTargets)
        assertEquals(1, windowStore.expandOlderAfterMaterializationCalls)
        assertEquals(true, results.single().second.canLoadOlder)
        assertEquals(false, results.single().second.canLoadNewer)
    }

    @Test
    fun olderPagination_disablesHistoryAtConfirmedStart() {
        hasReachedStart = true
        val windowStore = FakeWindowStore()
        store.activate(TARGET, windowStore)

        assertTrue(store.loadOlder(TARGET))

        assertEquals(false, results.single().second.canLoadOlder)
    }

    @Test
    fun newerPagination_usesCachedWindowBeforeMatrix() {
        val windowStore = FakeWindowStore(
            expandNewerResult = true,
            state = ChatTimelineWindowState(
                canLoadOlder = true,
                canLoadNewer = true,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.loadNewer(TARGET))

        assertTrue(forwardTargets.isEmpty())
        assertEquals(0, windowStore.expandNewerAfterMaterializationCalls)
        assertEquals(
            ChatTimelinePaginationResult(
                canLoadNewer = true,
                isAtLiveEdge = false
            ),
            results.single().second
        )
    }

    @Test
    fun newerPagination_marksLiveEdgeAtConfirmedEnd() {
        hasReachedEnd = true
        val windowStore = FakeWindowStore(
            state = ChatTimelineWindowState(
                canLoadOlder = true,
                canLoadNewer = true,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.loadNewer(TARGET))

        assertEquals(listOf(TARGET), forwardTargets)
        assertEquals(1, windowStore.markNewerFullyLoadedCalls)
        assertEquals(
            ChatTimelinePaginationResult(
                canLoadNewer = false,
                isAtLiveEdge = true
            ),
            results.single().second
        )
    }

    @Test
    fun newerPagination_remainsAvailableBeforeConfirmedEnd() {
        val windowStore = FakeWindowStore(
            state = ChatTimelineWindowState(
                canLoadOlder = true,
                canLoadNewer = false,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.loadNewer(TARGET))

        assertEquals(listOf(TARGET), forwardTargets)
        assertEquals(0, windowStore.markNewerFullyLoadedCalls)
        assertEquals(
            ChatTimelinePaginationResult(
                canLoadNewer = true,
                isAtLiveEdge = false
            ),
            results.single().second
        )
    }

    @Test
    fun paginationFailure_isReportedWithoutResult() {
        val expected = IllegalStateException("pagination failed")
        backwardError = expected
        store.activate(TARGET, FakeWindowStore())

        assertTrue(store.loadOlder(TARGET))

        assertEquals(listOf(TARGET to expected), errors)
        assertTrue(results.isEmpty())
        assertFalse(store.hasActiveWindowOperation())
    }

    private data class FakeWindowStore(
        var expandOlderResult: Boolean = false,
        var expandOlderAfterMaterializationResult: Boolean = false,
        var expandNewerResult: Boolean = false,
        var expandNewerAfterMaterializationResult: Boolean = false,
        var state: ChatTimelineWindowState = ChatTimelineWindowState(
            canLoadOlder = true,
            canLoadNewer = false,
            isAtLiveEdge = true
        ),
        var expandOlderCalls: Int = 0,
        var expandOlderAfterMaterializationCalls: Int = 0,
        var expandNewerCalls: Int = 0,
        var expandNewerAfterMaterializationCalls: Int = 0,
        var markNewerFullyLoadedCalls: Int = 0
    )

    private companion object {
        val TARGET = ChatTimelineTarget(
            userId = "@alice:example.org",
            roomId = "!room:example.org"
        )
    }
}
