package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineFlushSummary
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal data class ChatTimelineTarget(
    val userId: String,
    val roomId: String
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
    private var activeTimeline: ActiveTimeline<WindowStore>? = null
    private var timelineJob: Job? = null
    private var windowJob: Job? = null
    private var windowOperationJob: Job? = null

    @MainThread
    fun activate(
        target: ChatTimelineTarget,
        windowStore: WindowStore
    ) {
        deactivate()

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
                onWindowUpdate(
                    target,
                    update,
                    isWindowAtLiveEdge(windowStore)
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

                    onTimelineSettled(target, update.messages.size)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    onTimelineError(target, error)
                }
            }
        }
    }

    @MainThread
    fun deactivate() {
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
    }

    @MainThread
    fun loadOlder(target: ChatTimelineTarget): Boolean {
        val driver = paginationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        return launchWindowOperation {
            try {
                val windowStore = activation.windowStore
                val didLoadFromCache = driver.expandOlderFromCache(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                if (didLoadFromCache) {
                    val state = driver.windowState(windowStore)
                    onPaginationResult(
                        target,
                        ChatTimelinePaginationResult(
                            canLoadOlder = true,
                            canLoadNewer = state.canLoadNewer,
                            isAtLiveEdge = state.isAtLiveEdge
                        )
                    )
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
                onPaginationResult(
                    target,
                    ChatTimelinePaginationResult(
                        canLoadOlder = !hasReachedStart || didLoadFromFreshCache,
                        canLoadNewer = state.canLoadNewer,
                        isAtLiveEdge = state.isAtLiveEdge
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    onPaginationError(target, error)
                }
            }
        }
    }

    @MainThread
    fun loadNewer(target: ChatTimelineTarget): Boolean {
        val driver = paginationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        return launchWindowOperation {
            try {
                val windowStore = activation.windowStore
                val didLoadFromCache = driver.expandNewerFromCache(windowStore)
                if (activeTimeline !== activation) {
                    return@launchWindowOperation
                }
                if (didLoadFromCache) {
                    val state = driver.windowState(windowStore)
                    onPaginationResult(
                        target,
                        ChatTimelinePaginationResult(
                            canLoadNewer = state.canLoadNewer,
                            isAtLiveEdge = state.isAtLiveEdge
                        )
                    )
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
                onPaginationResult(
                    target,
                    ChatTimelinePaginationResult(
                        canLoadNewer = if (hasReachedEnd && !didLoadFromFreshCache) {
                            false
                        } else {
                            state.canLoadNewer || !hasReachedEnd
                        },
                        isAtLiveEdge = state.isAtLiveEdge
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    onPaginationError(target, error)
                }
            }
        }
    }

    @MainThread
    fun jumpToEvent(
        target: ChatTimelineTarget,
        eventId: String
    ): Boolean {
        val driver = navigationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        val request = ChatTimelineNavigationRequest.EventJump(eventId)
        return launchWindowOperation {
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
                onNavigationResult(
                    target,
                    ChatTimelineNavigationResult.EventJump(
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
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    onNavigationError(target, request, error)
                }
            }
        }
    }

    @MainThread
    fun jumpToLiveEdge(target: ChatTimelineTarget): Boolean {
        val driver = navigationDriver ?: return false
        val activation = activeTimeline?.takeIf { it.target == target } ?: return false
        return launchWindowOperation {
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
                onNavigationResult(
                    target,
                    ChatTimelineNavigationResult.LiveEdge(
                        didJump = didJump,
                        canLoadOlder = state.canLoadOlder,
                        canLoadNewer = state.canLoadNewer,
                        isAtLiveEdge = state.isAtLiveEdge
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (activeTimeline === activation) {
                    onNavigationError(
                        target,
                        ChatTimelineNavigationRequest.LiveEdge,
                        error
                    )
                }
            }
        }
    }
}

private data class ActiveTimeline<WindowStore : Any>(
    val target: ChatTimelineTarget,
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
    ) -> Unit,
    onTimelineSettled: (ChatTimelineTarget, messageCount: Int) -> Unit,
    onTimelineError: (ChatTimelineTarget, Throwable) -> Unit,
    onPaginationResult: (ChatTimelineTarget, ChatTimelinePaginationResult) -> Unit,
    onPaginationError: (ChatTimelineTarget, Throwable) -> Unit,
    onNavigationResult: (ChatTimelineTarget, ChatTimelineNavigationResult) -> Unit,
    onNavigationError: (
        ChatTimelineTarget,
        ChatTimelineNavigationRequest,
        Throwable
    ) -> Unit,
    onNavigationTrace: (String) -> Unit
): ChatTimelineStore<RoomTimelineWindowStore> {
    return ChatTimelineStore(
        scope = scope,
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
