package com.zyna.app.ui.chat

import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixTimelineUpdate
import kotlinx.coroutines.CompletableDeferred
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

class ChatTimelineNavigationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val backwardTargets = mutableListOf<ChatTimelineTarget>()
    private val afterMaterializationResults = mutableListOf<Boolean>()
    private val reachedStartResults = mutableListOf<Boolean>()
    private val results = mutableListOf<Pair<ChatTimelineTarget, ChatTimelineNavigationResult>>()
    private val failures = mutableListOf<NavigationFailure>()
    private val traces = mutableListOf<String>()
    private var directJumpResult = false
    private var directJumpError: Throwable? = null
    private var directJumpGate: CompletableDeferred<Boolean>? = null
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
        navigationDriver = ChatTimelineNavigationDriver(
            jumpToEvent = { windowStore, eventId ->
                windowStore.directJumpEventIds += eventId
                directJumpError?.let { throw it }
                directJumpGate?.await() ?: directJumpResult
            },
            jumpToEventAfterMaterialization = { windowStore, eventId ->
                windowStore.materializedJumpEventIds += eventId
                afterMaterializationResults.removeFirstOrFalse()
            },
            jumpToLiveEdge = { windowStore ->
                windowStore.liveEdgeJumpCalls += 1
                windowStore.liveEdgeJumpResult
            },
            windowState = { windowStore -> windowStore.state },
            paginateBackwards = { target ->
                backwardTargets += target
                reachedStartResults.removeFirstOrFalse()
            },
            maxPaginationAttempts = MAX_ATTEMPTS
        ),
        onNavigationResult = { target, result -> results += target to result },
        onNavigationError = { target, request, error ->
            failures += NavigationFailure(target, request, error)
        },
        onNavigationTrace = traces::add
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun navigation_requiresMatchingActiveTimeline() {
        assertFalse(store.jumpToEvent(TARGET, EVENT_ID))
        assertFalse(store.jumpToLiveEdge(TARGET))
        assertTrue(results.isEmpty())
    }

    @Test
    fun eventJump_usesCachedEventBeforeMatrixPagination() {
        directJumpResult = true
        val windowStore = FakeWindowStore(
            state = ChatTimelineWindowState(
                canLoadOlder = false,
                canLoadNewer = true,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.jumpToEvent(TARGET, EVENT_ID))

        assertTrue(backwardTargets.isEmpty())
        assertTrue(windowStore.materializedJumpEventIds.isEmpty())
        assertEquals(
            ChatTimelineNavigationResult.EventJump(
                eventId = EVENT_ID,
                didJump = true,
                canLoadOlder = true,
                canLoadNewer = true,
                isAtLiveEdge = false
            ),
            results.single().second
        )
    }

    @Test
    fun eventJump_paginatesUntilMaterializedEventIsFound() {
        afterMaterializationResults += listOf(false, true)
        val windowStore = FakeWindowStore(
            state = ChatTimelineWindowState(
                canLoadOlder = true,
                canLoadNewer = true,
                isAtLiveEdge = false
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.jumpToEvent(TARGET, EVENT_ID))

        assertEquals(listOf(TARGET, TARGET), backwardTargets)
        assertEquals(listOf(EVENT_ID, EVENT_ID), windowStore.materializedJumpEventIds)
        assertEquals(
            ChatTimelineNavigationResult.EventJump(
                eventId = EVENT_ID,
                didJump = true,
                canLoadOlder = true,
                canLoadNewer = true,
                isAtLiveEdge = false
            ),
            results.single().second
        )
    }

    @Test
    fun eventJump_disablesOlderLoadingAfterConfirmedHistoryStart() {
        reachedStartResults += true
        store.activate(TARGET, FakeWindowStore())

        assertTrue(store.jumpToEvent(TARGET, EVENT_ID))

        assertEquals(listOf(TARGET), backwardTargets)
        assertEquals(
            ChatTimelineNavigationResult.EventJump(
                eventId = EVENT_ID,
                didJump = false,
                canLoadOlder = false,
                canLoadNewer = null,
                isAtLiveEdge = true
            ),
            results.single().second
        )
    }

    @Test
    fun eventJump_stopsAtPaginationAttemptLimit() {
        store.activate(TARGET, FakeWindowStore())

        assertTrue(store.jumpToEvent(TARGET, EVENT_ID))

        assertEquals(MAX_ATTEMPTS, backwardTargets.size)
        assertEquals(
            ChatTimelineNavigationResult.EventJump(
                eventId = EVENT_ID,
                didJump = false,
                canLoadOlder = null,
                canLoadNewer = null,
                isAtLiveEdge = true
            ),
            results.single().second
        )
    }

    @Test
    fun liveEdgeJump_reportsLatestWindowState() {
        val windowStore = FakeWindowStore(
            liveEdgeJumpResult = true,
            state = ChatTimelineWindowState(
                canLoadOlder = true,
                canLoadNewer = false,
                isAtLiveEdge = true
            )
        )
        store.activate(TARGET, windowStore)

        assertTrue(store.jumpToLiveEdge(TARGET))

        assertEquals(1, windowStore.liveEdgeJumpCalls)
        assertEquals(
            ChatTimelineNavigationResult.LiveEdge(
                didJump = true,
                canLoadOlder = true,
                canLoadNewer = false,
                isAtLiveEdge = true
            ),
            results.single().second
        )
    }

    @Test
    fun navigationFailure_isReportedWithItsRequest() {
        val expected = IllegalStateException("jump failed")
        directJumpError = expected
        store.activate(TARGET, FakeWindowStore())

        assertTrue(store.jumpToEvent(TARGET, EVENT_ID))

        assertEquals(
            listOf(
                NavigationFailure(
                    target = TARGET,
                    request = ChatTimelineNavigationRequest.EventJump(EVENT_ID),
                    error = expected
                )
            ),
            failures
        )
        assertTrue(results.isEmpty())
        assertFalse(store.hasActiveWindowOperation())
    }

    @Test
    fun timelineReplacement_cancelsEventJumpWithoutDeliveringStaleResult() {
        val gate = CompletableDeferred<Boolean>()
        directJumpGate = gate
        store.activate(TARGET, FakeWindowStore())

        assertTrue(store.jumpToEvent(TARGET, EVENT_ID))
        store.activate(OTHER_TARGET, FakeWindowStore())
        gate.complete(true)

        assertTrue(results.isEmpty())
        assertTrue(failures.isEmpty())
        assertFalse(store.hasActiveWindowOperation())
    }

    private fun MutableList<Boolean>.removeFirstOrFalse(): Boolean {
        return if (isEmpty()) false else removeAt(0)
    }

    private data class FakeWindowStore(
        var liveEdgeJumpResult: Boolean = false,
        var state: ChatTimelineWindowState = ChatTimelineWindowState(
            canLoadOlder = true,
            canLoadNewer = false,
            isAtLiveEdge = true
        ),
        val directJumpEventIds: MutableList<String> = mutableListOf(),
        val materializedJumpEventIds: MutableList<String> = mutableListOf(),
        var liveEdgeJumpCalls: Int = 0
    )

    private data class NavigationFailure(
        val target: ChatTimelineTarget,
        val request: ChatTimelineNavigationRequest,
        val error: Throwable
    )

    private companion object {
        const val EVENT_ID = "\$event"
        const val MAX_ATTEMPTS = 3
        val TARGET = ChatTimelineTarget(
            userId = "@alice:example.org",
            roomId = "!room:example.org"
        )
        val OTHER_TARGET = ChatTimelineTarget(
            userId = "@alice:example.org",
            roomId = "!other:example.org"
        )
    }
}
