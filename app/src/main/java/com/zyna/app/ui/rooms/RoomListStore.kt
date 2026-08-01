package com.zyna.app.ui.rooms

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomListSession
import com.zyna.app.data.matrix.MatrixRoomListSnapshot
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoomListState(
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val isSynchronizing: Boolean = false,
    val hasSynchronizationError: Boolean = false
) {
    fun roomForId(roomId: String): MatrixRoomSummary? {
        return rooms.firstOrNull { it.id == roomId }
    }

    fun roomIdForDirectUser(userId: String): String? {
        return rooms.firstOrNull { it.directUserId == userId }?.id
    }
}

internal class RoomListDriver(
    val observeCachedRooms: (userId: String) -> Flow<List<MatrixRoomSummary>>,
    val openLive: suspend (userId: String) -> MatrixRoomListSession,
    val cacheSnapshot: suspend (
        userId: String,
        rooms: List<MatrixRoomSummary>,
        updatedRoomIds: Set<String>,
        excludedRoomIds: Set<String>,
        isComplete: Boolean
    ) -> Unit
)

/**
 * Cache-first owner of the ordered Matrix room list.
 *
 * The SDK owns live order and pagination, Room owns the rendered projection, and only the visible
 * range is subscribed for richer room info. Partial live pages are never allowed to erase the
 * cached tail; the cache repository removes absent rooms only for a terminal snapshot.
 */
internal class RoomListStore(
    private val scope: CoroutineScope,
    private val driver: RoomListDriver,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private data class Session(
        val userId: String,
        val generation: Long,
        val initialCacheReady: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private sealed interface LiveSignal {
        data class Snapshot(val value: MatrixRoomListSnapshot) : LiveSignal
        data class Failure(val error: Throwable) : LiveSignal
    }

    private class RoomListProjectionFailure(
        val source: Throwable
    ) : RuntimeException(source)

    private val _state = MutableStateFlow(RoomListState())
    val state: StateFlow<RoomListState> = _state.asStateFlow()

    private var nextSessionGeneration = 0L
    private var activeSession: Session? = null
    private var cacheJob: Job? = null
    private var liveJob: Job? = null
    private var visibleSubscriptionJob: Job? = null
    private var paginationJob: Job? = null
    private var liveSession: MatrixRoomListSession? = null
    private var latestLiveSnapshot = MatrixRoomListSnapshot()
    private var visibleRoomsOwnerId: String? = null
    private var visibleRoomIds: List<String> = emptyList()
    private var subscribedRoomIds: List<String>? = null
    private var paginationRequestedForEntryCount: Int? = null
    private var lastCachedRevision: Long? = null
    private var lastCachedCompletion: Boolean? = null

    @MainThread
    suspend fun activate(userId: String) {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return
        val currentSession = activeSession
        if (currentSession?.userId == normalizedUserId) {
            if (cacheJob?.isActive != true) {
                cacheJob = launchCacheObservation(currentSession)
            }
            currentSession.initialCacheReady.await()
            return
        }
        if (currentSession != null) deactivate()

        nextSessionGeneration += 1
        val session = Session(normalizedUserId, nextSessionGeneration)
        activeSession = session
        cacheJob = launchCacheObservation(session)
        session.initialCacheReady.await()
    }

    @MainThread
    fun enableReactiveSynchronization(userId: String) {
        val session = activeSession?.takeIf { it.userId == userId } ?: return
        if (liveJob?.isActive == true) return

        _state.update { current ->
            current.copy(isSynchronizing = true, hasSynchronizationError = false)
        }
        liveJob = launchLiveObservation(session)
    }

    @MainThread
    fun retrySynchronization() {
        val session = activeSession ?: return
        if (liveJob?.isActive == true) return
        _state.update { current ->
            current.copy(isSynchronizing = true, hasSynchronizationError = false)
        }
        liveJob = launchLiveObservation(session)
    }

    @MainThread
    fun updateVisibleRooms(ownerId: String, roomIds: List<String>) {
        val normalizedOwnerId = ownerId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalized = roomIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        visibleRoomsOwnerId = normalizedOwnerId
        updateVisibleRoomIds(normalized)
    }

    @MainThread
    fun clearVisibleRooms(ownerId: String) {
        val normalizedOwnerId = ownerId.trim().takeIf { it.isNotEmpty() } ?: return
        if (visibleRoomsOwnerId != normalizedOwnerId) return
        visibleRoomsOwnerId = null
        updateVisibleRoomIds(emptyList())
    }

    private fun updateVisibleRoomIds(normalized: List<String>) {
        if (visibleRoomIds != normalized) {
            visibleRoomIds = normalized
            scheduleVisibleSubscription()
        }
        requestMoreIfNeeded()
    }

    @MainThread
    suspend fun deactivate() {
        nextSessionGeneration += 1
        activeSession = null
        val jobs = listOfNotNull(
            cacheJob,
            liveJob,
            visibleSubscriptionJob,
            paginationJob
        ).distinct()
        cacheJob = null
        liveJob = null
        visibleSubscriptionJob = null
        paginationJob = null
        jobs.forEach(Job::cancel)
        jobs.forEach { job -> job.join() }
        liveSession?.let { session -> closeOffMain(session) }
        liveSession = null
        latestLiveSnapshot = MatrixRoomListSnapshot()
        visibleRoomsOwnerId = null
        visibleRoomIds = emptyList()
        subscribedRoomIds = null
        paginationRequestedForEntryCount = null
        lastCachedRevision = null
        lastCachedCompletion = null
        _state.value = RoomListState()
    }

    private fun launchCacheObservation(session: Session): Job {
        return scope.launch {
            try {
                driver.observeCachedRooms(session.userId).collect { rooms ->
                    if (isCurrent(session)) {
                        _state.update { current -> current.copy(rooms = rooms) }
                    }
                    session.initialCacheReady.complete(Unit)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session)) onWarning("Failed to observe cached rooms", error)
            } finally {
                session.initialCacheReady.complete(Unit)
            }
        }
    }

    private fun launchLiveObservation(session: Session): Job {
        return scope.launch {
            var consecutiveFailures = 0
            try {
                while (isCurrent(session)) {
                    var openedSession: MatrixRoomListSession? = null
                    var iterationFailure: Throwable? = null
                    val openedAtNanos = System.nanoTime()
                    try {
                        openedSession = driver.openLive(session.userId)
                        if (!isCurrent(session)) return@launch
                        liveSession = openedSession
                        scheduleVisibleSubscription()
                        merge(
                            openedSession.snapshots.map { snapshot ->
                                LiveSignal.Snapshot(snapshot)
                            },
                            openedSession.failures.map { error ->
                                LiveSignal.Failure(error)
                            }
                        ).collect { signal ->
                            when (signal) {
                                is LiveSignal.Failure -> {
                                    throw RoomListProjectionFailure(signal.error)
                                }
                                is LiveSignal.Snapshot -> consumeLiveSnapshot(
                                    session = session,
                                    owner = openedSession,
                                    snapshot = signal.value
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        iterationFailure = error
                    } finally {
                        if (liveSession === openedSession) {
                            liveSession = null
                            visibleSubscriptionJob?.cancel()
                            visibleSubscriptionJob = null
                            paginationJob?.cancel()
                            paginationJob = null
                            subscribedRoomIds = null
                            paginationRequestedForEntryCount = null
                            lastCachedRevision = null
                            lastCachedCompletion = null
                        }
                        openedSession?.let { owner -> closeOffMain(owner) }
                    }
                    if (!isCurrent(session)) break
                    val failure = iterationFailure ?: break
                    if (System.nanoTime() - openedAtNanos >= LIVE_STABILITY_RESET_NANOS) {
                        consecutiveFailures = 0
                    }
                    consecutiveFailures += 1
                    val source = (failure as? RoomListProjectionFailure)?.source ?: failure
                    val message = if (failure is RoomListProjectionFailure) {
                        "Failed to process a Matrix room-list update"
                    } else {
                        "Failed to observe the Matrix room list"
                    }
                    onWarning(message, source)
                    if (consecutiveFailures >= MAX_CONSECUTIVE_LIVE_FAILURES) {
                        _state.update { current ->
                            current.copy(
                                isSynchronizing = false,
                                hasSynchronizationError = true
                            )
                        }
                        break
                    }

                    _state.update { current -> current.copy(isSynchronizing = true) }
                    val retryDelay = (LIVE_REOPEN_INITIAL_DELAY_MILLIS shl
                        (consecutiveFailures - 1))
                        .coerceAtMost(LIVE_REOPEN_MAX_DELAY_MILLIS)
                    delay(retryDelay)
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (isCurrent(session)) {
                    _state.update { current -> current.copy(isSynchronizing = false) }
                }
            }
        }
    }

    private suspend fun consumeLiveSnapshot(
        session: Session,
        owner: MatrixRoomListSession,
        snapshot: MatrixRoomListSnapshot
    ) {
        if (!isCurrent(session)) return
        latestLiveSnapshot = snapshot
        if (paginationRequestedForEntryCount != null &&
            paginationRequestedForEntryCount != snapshot.loadedEntryCount
        ) {
            paginationRequestedForEntryCount = null
        }
        if (snapshot.isKnown) {
            cacheSnapshotIfChanged(session, owner, snapshot)
            _state.update { current ->
                current.copy(
                    isSynchronizing = false,
                    hasSynchronizationError = false
                )
            }
        }
        requestMoreIfNeeded()
    }

    private suspend fun cacheSnapshotIfChanged(
        session: Session,
        owner: MatrixRoomListSession,
        snapshot: MatrixRoomListSnapshot
    ) {
        val isComplete = snapshot.endReached
        if (lastCachedRevision == snapshot.revision && lastCachedCompletion == isComplete) return
        try {
            val updatedRoomIds = if (lastCachedRevision == null) {
                snapshot.rooms.mapTo(mutableSetOf(), MatrixRoomSummary::id)
            } else {
                snapshot.updatedRoomIds
            }
            driver.cacheSnapshot(
                session.userId,
                snapshot.rooms,
                updatedRoomIds,
                snapshot.excludedRoomIds,
                isComplete
            )
            if (isCurrent(session)) {
                lastCachedRevision = snapshot.revision
                lastCachedCompletion = isComplete
                if (liveSession === owner) owner.acknowledgeCached(snapshot.revision)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(session)) onWarning("Failed to cache the Matrix room list", error)
        }
    }

    private fun scheduleVisibleSubscription() {
        val session = activeSession ?: return
        val openedSession = liveSession ?: return
        val requestedIds = visibleRoomIds
        if (requestedIds == subscribedRoomIds) return
        visibleSubscriptionJob?.cancel()
        visibleSubscriptionJob = scope.launch {
            delay(VISIBLE_SUBSCRIPTION_DEBOUNCE_MILLIS)
            if (!isCurrent(session) || liveSession !== openedSession) return@launch
            try {
                openedSession.subscribeToRooms(requestedIds)
                if (isCurrent(session) && liveSession === openedSession) {
                    subscribedRoomIds = requestedIds
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session)) {
                    onWarning("Failed to subscribe to visible Matrix rooms", error)
                }
            }
        }
    }

    private fun requestMoreIfNeeded() {
        val session = activeSession ?: return
        val openedSession = liveSession ?: return
        val snapshot = latestLiveSnapshot
        if (!snapshot.isKnown || snapshot.endReached) return
        if (paginationJob?.isActive == true) return

        val needsInitialChatFill = snapshot.renderableChatCount <
            MINIMUM_LOADED_CHAT_ROOMS
        if (visibleRoomIds.isEmpty() && !needsInitialChatFill) return
        val loadedIndexes = snapshot.roomIndexById
        val hasVisibleCachedTail = visibleRoomIds.any { roomId -> roomId !in loadedIndexes }
        val lastVisibleLoadedIndex = visibleRoomIds.maxOfOrNull { roomId ->
            loadedIndexes[roomId] ?: -1
        } ?: -1
        val isNearLoadedEnd = lastVisibleLoadedIndex >=
            (snapshot.rooms.size - PAGINATION_THRESHOLD).coerceAtLeast(0)
        if (!needsInitialChatFill && !hasVisibleCachedTail && !isNearLoadedEnd) return
        // addOnePage expands the SDK's desired window synchronously; returning without an
        // immediate diff does not mean that request failed. Keep the guard until the source
        // entry count advances instead of repeatedly expanding farther while the server catches up.
        if (paginationRequestedForEntryCount == snapshot.loadedEntryCount) return

        paginationRequestedForEntryCount = snapshot.loadedEntryCount
        paginationJob = scope.launch {
            try {
                openedSession.loadMore()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session) && liveSession === openedSession) {
                    paginationRequestedForEntryCount = null
                    onWarning("Failed to load more Matrix rooms", error)
                }
            }
        }
    }

    private fun isCurrent(session: Session): Boolean {
        return activeSession === session && nextSessionGeneration == session.generation
    }

    private suspend fun closeOffMain(session: MatrixRoomListSession) {
        withContext(NonCancellable + Dispatchers.IO) {
            session.close()
        }
    }

    private companion object {
        const val VISIBLE_SUBSCRIPTION_DEBOUNCE_MILLIS = 300L
        const val LIVE_REOPEN_INITIAL_DELAY_MILLIS = 500L
        const val LIVE_REOPEN_MAX_DELAY_MILLIS = 8_000L
        const val MAX_CONSECUTIVE_LIVE_FAILURES = 3
        const val LIVE_STABILITY_RESET_NANOS = 30_000_000_000L
        const val PAGINATION_THRESHOLD = 20
        const val MINIMUM_LOADED_CHAT_ROOMS = 40
    }
}

internal fun createRoomListStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    onWarning: (String, Throwable) -> Unit
): RoomListStore {
    return RoomListStore(
        scope = scope,
        driver = RoomListDriver(
            observeCachedRooms = localCacheRepository::observeRooms,
            openLive = matrixClientService::openRoomListSession,
            cacheSnapshot = localCacheRepository::cacheRoomsSnapshot
        ),
        onWarning = onWarning
    )
}
