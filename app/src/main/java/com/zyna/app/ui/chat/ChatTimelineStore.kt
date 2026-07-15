package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixTimelineUpdate
import com.zyna.app.data.timeline.RoomTimelineWindowStore
import com.zyna.app.util.ZynaPerfLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChatTimelineTarget(
    val userId: String,
    val roomId: String
)

data class ChatTimelineState(
    val roomId: String? = null,
    val messages: List<MatrixChatMessage> = emptyList(),
    val windowChangeOrigin: TimelineWindowChangeOrigin =
        TimelineWindowChangeOrigin.INITIAL_LOAD,
    val isLoading: Boolean = false,
    val isLoadingWindowOperation: Boolean = false,
    val canLoadOlder: Boolean = true,
    val canLoadNewer: Boolean = false,
    val isAtLiveEdge: Boolean = true,
    val errorMessage: String? = null,
    val jumpTargetEventId: String? = null,
    val scrollToLiveEdgeRequested: Boolean = false
)

internal data class ChatTimelineWindowState(
    val canLoadOlder: Boolean,
    val canLoadNewer: Boolean,
    val isAtLiveEdge: Boolean
)

internal data class ChatTimelinePaginationResult(
    val canLoadOlder: Boolean? = null,
    val canLoadNewer: Boolean? = null,
    val isAtLiveEdge: Boolean? = null
)

internal class ChatTimelinePaginationDriver<WindowStore : Any>(
    val expandOlderFromCache: suspend (WindowStore) -> Boolean,
    val expandOlderAfterMaterialization: suspend (WindowStore) -> Boolean,
    val expandNewerFromCache: suspend (WindowStore) -> Boolean,
    val expandNewerAfterMaterialization: suspend (WindowStore) -> Boolean,
    val markNewerFullyLoaded: (WindowStore) -> Unit,
    val windowState: (WindowStore) -> ChatTimelineWindowState,
    val paginateBackwards: suspend (ChatTimelineTarget) -> Boolean,
    val paginateForwards: suspend (ChatTimelineTarget) -> Boolean
)

internal sealed interface ChatTimelineNavigationRequest {
    data class EventJump(val eventId: String) : ChatTimelineNavigationRequest

    data object LiveEdge : ChatTimelineNavigationRequest
}

internal sealed interface ChatTimelineNavigationResult {
    data class EventJump(
        val eventId: String,
        val didJump: Boolean,
        val canLoadOlder: Boolean?,
        val canLoadNewer: Boolean?,
        val isAtLiveEdge: Boolean
    ) : ChatTimelineNavigationResult

    data class LiveEdge(
        val didJump: Boolean,
        val canLoadOlder: Boolean,
        val canLoadNewer: Boolean,
        val isAtLiveEdge: Boolean
    ) : ChatTimelineNavigationResult
}

internal class ChatTimelineNavigationDriver<WindowStore : Any>(
    val jumpToEvent: suspend (WindowStore, eventId: String) -> Boolean,
    val jumpToEventAfterMaterialization: suspend (
        WindowStore,
        eventId: String
    ) -> Boolean,
    val jumpToLiveEdge: suspend (WindowStore) -> Boolean,
    val windowState: (WindowStore) -> ChatTimelineWindowState,
    val paginateBackwards: suspend (ChatTimelineTarget) -> Boolean,
    val maxPaginationAttempts: Int
)

internal class ChatTimelineBootstrapDriver<WindowStore : Any>(
    val createWindowStore: (ChatTimelineTarget) -> WindowStore,
    val initialMessagesSnapshot: suspend (
        ChatTimelineTarget,
        WindowStore
    ) -> List<MatrixChatMessage>
)

/**
 * Owns the subscriptions and window associated with one active chat timeline.
 *
 * This class is main-thread confined. [scope] must dispatch onto the same main
 * thread from which its public methods are called.
 */
internal class ChatTimelineStore<WindowStore : Any>(
    private val scope: CoroutineScope,
    private val observeTimeline: (ChatTimelineTarget) -> Flow<MatrixTimelineUpdate>,
    private val observeWindow: (WindowStore) -> Flow<TimelineWindowUpdate<MatrixChatMessage>>,
    private val isWindowAtLiveEdge: (WindowStore) -> Boolean,
    private val recordTimelineFlush: (WindowStore, TimelineFlushSummary) -> Unit,
    private val cacheCallEvents: suspend (ChatTimelineTarget, MatrixTimelineUpdate) -> Unit,
    private val cacheMessages: suspend (ChatTimelineTarget, List<MatrixChatMessage>) -> Unit,
    private val refreshInitialWindow: suspend (
        ChatTimelineTarget,
        WindowStore,
        TimelineFlushSummary
    ) -> Unit,
    private val bootstrapDriver: ChatTimelineBootstrapDriver<WindowStore>? = null,
    private val onInitialSnapshotError: (ChatTimelineTarget, Throwable) -> Unit = { _, _ -> },
    private val onWindowUpdate: (
        ChatTimelineTarget,
        TimelineWindowUpdate<MatrixChatMessage>,
        isAtLiveEdge: Boolean
    ) -> Unit,
    private val onTimelineSettled: (ChatTimelineTarget, messageCount: Int) -> Unit,
    private val onTimelineError: (ChatTimelineTarget, Throwable) -> Unit,
    private val paginationDriver: ChatTimelinePaginationDriver<WindowStore>? = null,
    private val onPaginationResult: (
        ChatTimelineTarget,
        ChatTimelinePaginationResult
    ) -> Unit = { _, _ -> },
    private val onPaginationError: (ChatTimelineTarget, Throwable) -> Unit = { _, _ -> },
    private val navigationDriver: ChatTimelineNavigationDriver<WindowStore>? = null,
    private val onNavigationResult: (
        ChatTimelineTarget,
        ChatTimelineNavigationResult
    ) -> Unit = { _, _ -> },
    private val onNavigationError: (
        ChatTimelineTarget,
        ChatTimelineNavigationRequest,
        Throwable
    ) -> Unit = { _, _, _ -> },
    private val onNavigationTrace: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(ChatTimelineState())
    val state: StateFlow<ChatTimelineState> = _state.asStateFlow()

    private var stateTarget: ChatTimelineTarget? = null
    private var activeBootstrap: TimelineBootstrap<WindowStore>? = null
    private var bootstrapJob: Job? = null
    private var activeTimeline: ActiveTimeline<WindowStore>? = null
    private var timelineJob: Job? = null
    private var windowJob: Job? = null
    private var windowOperationJob: Job? = null

    /**
     * Loads the cached window and activates subscriptions for [target].
     * [onActivated] runs only for the latest bootstrap, after activation.
     */
    @MainThread
    fun open(
        target: ChatTimelineTarget,
        onActivated: (ChatTimelineTarget) -> Unit = {}
    ) {
        val driver = checkNotNull(bootstrapDriver) {
            "Chat timeline bootstrap is not configured"
        }
        prepareRoom(target)

        val bootstrap = TimelineBootstrap(
            windowStore = driver.createWindowStore(target)
        )
        activeBootstrap = bootstrap
        val job = scope.launch {
            val initialMessages = try {
                driver.initialMessagesSnapshot(target, bootstrap.windowStore)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeBootstrap !== bootstrap) {
                    return@launch
                }
                onInitialSnapshotError(target, error)
                emptyList()
            }
            if (activeBootstrap !== bootstrap) {
                return@launch
            }

            applyInitialSnapshot(target, initialMessages)
            if (activeBootstrap !== bootstrap) {
                return@launch
            }

            activateTimeline(target, bootstrap.windowStore)
            if (activeBootstrap !== bootstrap) {
                return@launch
            }
            onActivated(target)
            if (activeBootstrap === bootstrap) {
                activeBootstrap = null
            }
        }
        bootstrapJob = job
        job.invokeOnCompletion {
            if (activeBootstrap === bootstrap) {
                activeBootstrap = null
            }
            if (bootstrapJob === job) {
                bootstrapJob = null
            }
        }
    }

    @MainThread
    fun prepareRoom(target: ChatTimelineTarget) {
        cancelBootstrap()
        stopActiveTimeline()
        stateTarget = target
        _state.value = ChatTimelineState(
            roomId = target.roomId,
            isLoading = true,
            canLoadOlder = false
        )
    }

    @MainThread
    fun applyInitialSnapshot(
        target: ChatTimelineTarget,
        messages: List<MatrixChatMessage>
    ) {
        updateState(target) {
            it.copy(
                messages = messages,
                windowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
                isLoading = messages.isEmpty(),
                isLoadingWindowOperation = false,
                canLoadOlder = true,
                canLoadNewer = false,
                isAtLiveEdge = true,
                errorMessage = null
            )
        }
    }

    @MainThread
    fun activate(
        target: ChatTimelineTarget,
        windowStore: WindowStore
    ) {
        cancelBootstrap()
        activateTimeline(target, windowStore)
    }

    private fun activateTimeline(
        target: ChatTimelineTarget,
        windowStore: WindowStore
    ) {
        stopActiveTimeline()
        if (stateTarget != target) {
            stateTarget = target
            _state.value = ChatTimelineState(
                roomId = target.roomId,
                isLoading = true
            )
        } else {
            updateState(target) {
                it.copy(
                    isLoading = it.messages.isEmpty(),
                    isLoadingWindowOperation = false,
                    canLoadOlder = true,
                    errorMessage = null
                )
            }
        }

        val activation = ActiveTimeline(
            target = target,
            windowStore = windowStore
        )
        activeTimeline = activation

        windowJob = scope.launch {
            observeWindow(windowStore).collect { update ->
                if (activeTimeline !== activation) {
                    return@collect
                }
                val isAtLiveEdge = isWindowAtLiveEdge(windowStore)
                applyWindowUpdate(
                    target = target,
                    update = update,
                    isAtLiveEdge = isAtLiveEdge
                )
                onWindowUpdate(
                    target,
                    update,
                    isAtLiveEdge
                )
            }
        }
        timelineJob = scope.launch {
            try {
                observeTimeline(target).collect { update ->
                    if (activeTimeline !== activation) {
                        return@collect
                    }
                    if (
                        update.callNotifications.isNotEmpty() ||
                        update.callMemberships.isNotEmpty()
                    ) {
                        cacheCallEvents(target, update)
                    }
                    if (activeTimeline !== activation) {
                        return@collect
                    }

                    if (update.messages.isNotEmpty()) {
                        recordTimelineFlush(windowStore, update.flushSummary)
                        cacheMessages(target, update.messages)
                        if (activeTimeline !== activation) {
                            return@collect
                        }
                        refreshInitialWindow(target, windowStore, update.flushSummary)
                    }
                    if (activeTimeline !== activation) {
                        return@collect
                    }

                    settleTimeline(target)
                    onTimelineSettled(target, update.messages.size)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    failTimeline(target, error)
                    onTimelineError(target, error)
                }
            }
        }
    }

    @MainThread
    fun deactivate() {
        cancelBootstrap()
        stopActiveTimeline()
        stateTarget = null
        _state.value = ChatTimelineState()
    }

    private fun cancelBootstrap() {
        activeBootstrap = null
        bootstrapJob?.cancel()
        bootstrapJob = null
    }

    private fun stopActiveTimeline() {
        activeTimeline = null
        timelineJob?.cancel()
        timelineJob = null
        windowJob?.cancel()
        windowJob = null
        cancelWindowOperation()
    }

    @MainThread
    fun hasActiveWindowOperation(): Boolean = windowOperationJob?.isActive == true

    @MainThread
    fun launchWindowOperation(operation: suspend () -> Unit): Boolean {
        if (hasActiveWindowOperation()) {
            return false
        }
        windowOperationJob = scope.launch {
            operation()
        }
        return true
    }

    @MainThread
    fun cancelWindowOperation() {
        windowOperationJob?.cancel()
        windowOperationJob = null
        stateTarget?.let { target ->
            updateState(target) { it.copy(isLoadingWindowOperation = false) }
        }
    }

    @MainThread
    fun loadOlder(target: ChatTimelineTarget): Boolean {
        val driver = paginationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        updateState(target) { it.copy(isLoadingWindowOperation = true) }
        val didLaunch = launchWindowOperation {
            try {
                val windowStore = activation.windowStore
                val didLoadFromCache = driver.expandOlderFromCache(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                if (didLoadFromCache) {
                    val state = driver.windowState(windowStore)
                    val result = ChatTimelinePaginationResult(
                        canLoadOlder = true,
                        canLoadNewer = state.canLoadNewer,
                        isAtLiveEdge = state.isAtLiveEdge
                    )
                    applyPaginationResult(target, result)
                    onPaginationResult(target, result)
                    return@launchWindowOperation
                }

                val hasReachedStart = driver.paginateBackwards(target)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                val didLoadFromFreshCache =
                    driver.expandOlderAfterMaterialization(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                val state = driver.windowState(windowStore)
                val result = ChatTimelinePaginationResult(
                    canLoadOlder = !hasReachedStart || didLoadFromFreshCache,
                    canLoadNewer = state.canLoadNewer,
                    isAtLiveEdge = state.isAtLiveEdge
                )
                applyPaginationResult(target, result)
                onPaginationResult(target, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    clearWindowOperation(target)
                    onPaginationError(target, error)
                }
            }
        }
        if (!didLaunch) {
            clearWindowOperation(target)
        }
        return didLaunch
    }

    @MainThread
    fun loadNewer(target: ChatTimelineTarget): Boolean {
        val driver = paginationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        updateState(target) { it.copy(isLoadingWindowOperation = true) }
        val didLaunch = launchWindowOperation {
            try {
                val windowStore = activation.windowStore
                val didLoadFromCache = driver.expandNewerFromCache(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                if (didLoadFromCache) {
                    val state = driver.windowState(windowStore)
                    val result = ChatTimelinePaginationResult(
                        canLoadNewer = state.canLoadNewer,
                        isAtLiveEdge = state.isAtLiveEdge
                    )
                    applyPaginationResult(target, result)
                    onPaginationResult(target, result)
                    return@launchWindowOperation
                }

                val hasReachedEnd = driver.paginateForwards(target)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                val didLoadFromFreshCache =
                    driver.expandNewerAfterMaterialization(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                if (hasReachedEnd && !didLoadFromFreshCache) {
                    driver.markNewerFullyLoaded(windowStore)
                }
                val state = driver.windowState(windowStore)
                val result = ChatTimelinePaginationResult(
                    canLoadNewer = if (hasReachedEnd && !didLoadFromFreshCache) {
                        false
                    } else {
                        state.canLoadNewer || !hasReachedEnd
                    },
                    isAtLiveEdge = state.isAtLiveEdge
                )
                applyPaginationResult(target, result)
                onPaginationResult(target, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    clearWindowOperation(target)
                    onPaginationError(target, error)
                }
            }
        }
        if (!didLaunch) {
            clearWindowOperation(target)
        }
        return didLaunch
    }

    @MainThread
    fun jumpToEvent(
        target: ChatTimelineTarget,
        eventId: String
    ): Boolean {
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        cancelWindowOperation()
        if (_state.value.messages.any { it.eventId == eventId || it.id == eventId }) {
            updateState(target) {
                it.copy(
                    windowChangeOrigin = TimelineWindowChangeOrigin.JUMP,
                    isLoadingWindowOperation = false,
                    jumpTargetEventId = eventId
                )
            }
            return true
        }
        val driver = navigationDriver ?: return false
        val request = ChatTimelineNavigationRequest.EventJump(eventId)
        updateState(target) {
            it.copy(
                isLoadingWindowOperation = true,
                jumpTargetEventId = eventId
            )
        }
        val didLaunch = launchWindowOperation {
            try {
                val windowStore = activation.windowStore
                var didJump = driver.jumpToEvent(windowStore, eventId)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                var state = driver.windowState(windowStore)
                onNavigationTrace(
                    "jump cache target=${eventId.shortLogId()} didJump=$didJump " +
                        "canOlder=${state.canLoadOlder} canNewer=${state.canLoadNewer} " +
                        "live=${state.isAtLiveEdge}"
                )

                var attempts = 0
                var hasReachedStart = false
                while (
                    !didJump &&
                    !hasReachedStart &&
                    attempts < driver.maxPaginationAttempts
                ) {
                    attempts += 1
                    hasReachedStart = driver.paginateBackwards(target)
                    if (activeTimeline !== activation) {
                        return@launchWindowOperation
                    }
                    didJump = driver.jumpToEventAfterMaterialization(windowStore, eventId)
                    if (activeTimeline !== activation) {
                        return@launchWindowOperation
                    }
                    onNavigationTrace(
                        "jump attempt=$attempts target=${eventId.shortLogId()} " +
                            "reachedStart=$hasReachedStart didJump=$didJump"
                    )
                }

                state = driver.windowState(windowStore)
                onNavigationTrace(
                    "jump final target=${eventId.shortLogId()} didJump=$didJump " +
                        "attempts=$attempts reachedStart=$hasReachedStart " +
                        "canOlder=${state.canLoadOlder} canNewer=${state.canLoadNewer} " +
                        "live=${state.isAtLiveEdge}"
                )
                val result = ChatTimelineNavigationResult.EventJump(
                    eventId = eventId,
                    didJump = didJump,
                    canLoadOlder = when {
                        didJump -> state.canLoadOlder || !hasReachedStart
                        hasReachedStart -> false
                        else -> null
                    },
                    canLoadNewer = if (didJump) state.canLoadNewer else null,
                    isAtLiveEdge = state.isAtLiveEdge
                )
                applyNavigationResult(target, result)
                onNavigationResult(target, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    failNavigation(target, request)
                    onNavigationError(target, request, error)
                }
            }
        }
        if (!didLaunch) {
            failNavigation(target, request)
        }
        return didLaunch
    }

    @MainThread
    fun jumpToLiveEdge(target: ChatTimelineTarget): Boolean {
        val driver = navigationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        cancelWindowOperation()
        updateState(target) {
            it.copy(
                isLoadingWindowOperation = true,
                jumpTargetEventId = null,
                scrollToLiveEdgeRequested = true
            )
        }
        val request = ChatTimelineNavigationRequest.LiveEdge
        val didLaunch = launchWindowOperation {
            try {
                val windowStore = activation.windowStore
                val didJump = driver.jumpToLiveEdge(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                val state = driver.windowState(windowStore)
                onNavigationTrace(
                    "live final didJump=$didJump canOlder=${state.canLoadOlder} " +
                        "canNewer=${state.canLoadNewer} live=${state.isAtLiveEdge}"
                )
                val result = ChatTimelineNavigationResult.LiveEdge(
                    didJump = didJump,
                    canLoadOlder = state.canLoadOlder,
                    canLoadNewer = state.canLoadNewer,
                    isAtLiveEdge = state.isAtLiveEdge
                )
                applyNavigationResult(target, result)
                onNavigationResult(target, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    failNavigation(target, request)
                    onNavigationError(
                        target,
                        request,
                        error
                    )
                }
            }
        }
        if (!didLaunch) {
            failNavigation(target, request)
        }
        return didLaunch
    }

    @MainThread
    fun consumeJumpTarget(eventId: String) {
        stateTarget?.let { target ->
            updateState(target) {
                if (it.jumpTargetEventId == eventId) {
                    it.copy(jumpTargetEventId = null)
                } else {
                    it
                }
            }
        }
    }

    @MainThread
    fun consumeScrollToLiveEdgeRequest() {
        stateTarget?.let { target ->
            updateState(target) {
                if (it.scrollToLiveEdgeRequested) {
                    it.copy(scrollToLiveEdgeRequested = false)
                } else {
                    it
                }
            }
        }
    }

    private fun applyWindowUpdate(
        target: ChatTimelineTarget,
        update: TimelineWindowUpdate<MatrixChatMessage>,
        isAtLiveEdge: Boolean
    ) {
        updateState(target) {
            it.copy(
                messages = update.messages,
                windowChangeOrigin = update.origin,
                isLoading = if (update.messages.isNotEmpty()) false else it.isLoading,
                canLoadNewer = update.hasNewerInDb,
                isAtLiveEdge = isAtLiveEdge
            )
        }
    }

    private fun settleTimeline(target: ChatTimelineTarget) {
        updateState(target) {
            it.copy(
                isLoading = false,
                isLoadingWindowOperation = false,
                errorMessage = null
            )
        }
    }

    private fun failTimeline(target: ChatTimelineTarget, error: Throwable) {
        updateState(target) {
            it.copy(
                isLoading = false,
                isLoadingWindowOperation = false,
                errorMessage = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun applyPaginationResult(
        target: ChatTimelineTarget,
        result: ChatTimelinePaginationResult
    ) {
        updateState(target) {
            it.copy(
                isLoadingWindowOperation = false,
                canLoadOlder = result.canLoadOlder ?: it.canLoadOlder,
                canLoadNewer = result.canLoadNewer ?: it.canLoadNewer,
                isAtLiveEdge = result.isAtLiveEdge ?: it.isAtLiveEdge
            )
        }
    }

    private fun clearWindowOperation(target: ChatTimelineTarget) {
        updateState(target) { it.copy(isLoadingWindowOperation = false) }
    }

    private fun applyNavigationResult(
        target: ChatTimelineTarget,
        result: ChatTimelineNavigationResult
    ) {
        updateState(target) {
            when (result) {
                is ChatTimelineNavigationResult.EventJump -> it.copy(
                    isLoadingWindowOperation = false,
                    canLoadOlder = result.canLoadOlder ?: it.canLoadOlder,
                    canLoadNewer = result.canLoadNewer ?: it.canLoadNewer,
                    isAtLiveEdge = result.isAtLiveEdge,
                    jumpTargetEventId = if (result.didJump) {
                        it.jumpTargetEventId
                    } else if (it.jumpTargetEventId == result.eventId) {
                        null
                    } else {
                        it.jumpTargetEventId
                    }
                )

                is ChatTimelineNavigationResult.LiveEdge -> it.copy(
                    isLoadingWindowOperation = false,
                    canLoadOlder = result.canLoadOlder,
                    canLoadNewer = result.canLoadNewer,
                    isAtLiveEdge = result.isAtLiveEdge,
                    scrollToLiveEdgeRequested = result.didJump
                )
            }
        }
    }

    private fun failNavigation(
        target: ChatTimelineTarget,
        request: ChatTimelineNavigationRequest
    ) {
        updateState(target) {
            when (request) {
                is ChatTimelineNavigationRequest.EventJump -> it.copy(
                    isLoadingWindowOperation = false,
                    jumpTargetEventId = if (it.jumpTargetEventId == request.eventId) {
                        null
                    } else {
                        it.jumpTargetEventId
                    }
                )

                ChatTimelineNavigationRequest.LiveEdge -> it.copy(
                    isLoadingWindowOperation = false,
                    scrollToLiveEdgeRequested = false
                )
            }
        }
    }

    private inline fun updateState(
        target: ChatTimelineTarget,
        transform: (ChatTimelineState) -> ChatTimelineState
    ) {
        if (stateTarget == target) {
            _state.update(transform)
        }
    }
}

private data class ActiveTimeline<WindowStore : Any>(
    val target: ChatTimelineTarget,
    val windowStore: WindowStore
)

private data class TimelineBootstrap<WindowStore : Any>(
    val windowStore: WindowStore
)

internal fun createChatTimelineStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    onWindowUpdate: (
        ChatTimelineTarget,
        TimelineWindowUpdate<MatrixChatMessage>,
        isAtLiveEdge: Boolean
    ) -> Unit = { _, _, _ -> },
    onTimelineSettled: (ChatTimelineTarget, messageCount: Int) -> Unit = { _, _ -> },
    onTimelineError: (ChatTimelineTarget, Throwable) -> Unit = { _, _ -> },
    onInitialSnapshotError: (ChatTimelineTarget, Throwable) -> Unit = { _, _ -> },
    onPaginationResult: (ChatTimelineTarget, ChatTimelinePaginationResult) -> Unit = { _, _ -> },
    onPaginationError: (ChatTimelineTarget, Throwable) -> Unit = { _, _ -> },
    onNavigationResult: (ChatTimelineTarget, ChatTimelineNavigationResult) -> Unit = { _, _ -> },
    onNavigationError: (
        ChatTimelineTarget,
        ChatTimelineNavigationRequest,
        Throwable
    ) -> Unit = { _, _, _ -> },
    onNavigationTrace: (String) -> Unit = {}
): ChatTimelineStore<RoomTimelineWindowStore> {
    return ChatTimelineStore(
        scope = scope,
        bootstrapDriver = ChatTimelineBootstrapDriver(
            createWindowStore = { target ->
                val start = ZynaPerfLog.start()
                RoomTimelineWindowStore(
                    userId = target.userId,
                    roomId = target.roomId,
                    localCacheRepository = localCacheRepository
                ).also {
                    ZynaPerfLog.end(start, "openRoom.createStore") {
                        "roomId=${target.roomId}"
                    }
                }
            },
            initialMessagesSnapshot = { target, windowStore ->
                val start = ZynaPerfLog.start()
                windowStore.initialMessagesSnapshot().also { messages ->
                    ZynaPerfLog.end(start, "openRoom.initialSnapshot") {
                        "roomId=${target.roomId} count=${messages.size}"
                    }
                }
            }
        ),
        onInitialSnapshotError = onInitialSnapshotError,
        observeTimeline = { target ->
            matrixClientService.roomTimelineMessageUpserts(target.roomId)
                .onEach { update ->
                    ZynaPerfLog.mark {
                        "chatTimeline.upsert.collect roomId=${target.roomId} " +
                            "messages=${update.messages.size} " +
                            "calls=${update.callNotifications.size} " +
                            "memberships=${update.callMemberships.size} " +
                            "flush=${update.flushSummary}"
                    }
                }
        },
        observeWindow = { windowStore -> windowStore.messages },
        isWindowAtLiveEdge = { windowStore -> windowStore.isAtLiveEdge },
        recordTimelineFlush = { windowStore, summary ->
            windowStore.recordTimelineFlush(summary)
        },
        cacheCallEvents = { target, update ->
            val start = ZynaPerfLog.start()
            localCacheRepository.cacheMatrixRtcCallTimelineEvents(
                userId = target.userId,
                notifications = update.callNotifications,
                memberships = update.callMemberships
            )
            ZynaPerfLog.end(
                start,
                "chatTimeline.cacheMatrixRtcCalls"
            ) {
                "roomId=${target.roomId} notifications=${update.callNotifications.size} " +
                    "memberships=${update.callMemberships.size}"
            }
        },
        cacheMessages = { target, messages ->
            val start = ZynaPerfLog.start()
            localCacheRepository.cacheRoomTimelineMessages(
                userId = target.userId,
                roomId = target.roomId,
                messages = messages
            )
            ZynaPerfLog.end(
                start,
                "chatTimeline.cacheMessages"
            ) {
                "roomId=${target.roomId} count=${messages.size}"
            }
        },
        refreshInitialWindow = { target, windowStore, summary ->
            val start = ZynaPerfLog.start()
            windowStore.refreshInitialWindowFromCacheIfNeeded(summary)
            ZynaPerfLog.end(
                start,
                "chatTimeline.refreshInitialWindow"
            ) {
                "roomId=${target.roomId}"
            }
        },
        onWindowUpdate = onWindowUpdate,
        onTimelineSettled = onTimelineSettled,
        onTimelineError = onTimelineError,
        paginationDriver = ChatTimelinePaginationDriver(
            expandOlderFromCache = { windowStore ->
                windowStore.expandOlderFromCache()
            },
            expandOlderAfterMaterialization = { windowStore ->
                windowStore.expandOlderFromCacheAfterMaterialization()
            },
            expandNewerFromCache = { windowStore ->
                windowStore.expandNewerFromCache()
            },
            expandNewerAfterMaterialization = { windowStore ->
                windowStore.expandNewerFromCacheAfterMaterialization()
            },
            markNewerFullyLoaded = { windowStore ->
                windowStore.markNewerFullyLoaded()
            },
            windowState = { windowStore ->
                ChatTimelineWindowState(
                    canLoadOlder = windowStore.canLoadOlderFromCache,
                    canLoadNewer = windowStore.canLoadNewerFromCache,
                    isAtLiveEdge = windowStore.isAtLiveEdge
                )
            },
            paginateBackwards = { target ->
                matrixClientService.paginateRoomTimelineBackwards(target.roomId)
            },
            paginateForwards = { target ->
                matrixClientService.paginateRoomTimelineForwards(target.roomId)
            }
        ),
        onPaginationResult = onPaginationResult,
        onPaginationError = onPaginationError,
        navigationDriver = ChatTimelineNavigationDriver(
            jumpToEvent = { windowStore, eventId ->
                windowStore.jumpToEvent(eventId)
            },
            jumpToEventAfterMaterialization = { windowStore, eventId ->
                windowStore.jumpToEventAfterMaterialization(eventId)
            },
            jumpToLiveEdge = { windowStore ->
                windowStore.jumpToLiveEdge()
            },
            windowState = { windowStore ->
                ChatTimelineWindowState(
                    canLoadOlder = windowStore.canLoadOlderFromCache,
                    canLoadNewer = windowStore.canLoadNewerFromCache,
                    isAtLiveEdge = windowStore.isAtLiveEdge
                )
            },
            paginateBackwards = { target ->
                matrixClientService.paginateRoomTimelineBackwards(target.roomId)
            },
            maxPaginationAttempts = MAX_JUMP_PAGINATION_ATTEMPTS
        ),
        onNavigationResult = onNavigationResult,
        onNavigationError = onNavigationError,
        onNavigationTrace = onNavigationTrace
    )
}

private fun String.shortLogId(): String = takeLast(10)

private const val MAX_JUMP_PAGINATION_ATTEMPTS = 8
