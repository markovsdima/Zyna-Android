package com.zyna.app.ui.rooms

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomListState(
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val isSynchronizing: Boolean = false
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
    val observeChangeSignals: () -> Flow<Unit>,
    val loadSnapshot: suspend () -> List<MatrixRoomSummary>,
    val cacheSnapshot: suspend (
        userId: String,
        rooms: List<MatrixRoomSummary>
    ) -> Unit
)

/**
 * Owns the cached room list and its reactive synchronization with Matrix.
 *
 * There is no user-triggered refresh contract. Once reactive synchronization
 * is enabled, SDK room-list signals request coalesced authoritative snapshots.
 * Public methods and driver callbacks are main-thread confined.
 */
internal class RoomListStore(
    private val scope: CoroutineScope,
    private val driver: RoomListDriver,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private data class Session(
        val userId: String,
        val generation: Long
    )

    private val _state = MutableStateFlow(RoomListState())
    val state: StateFlow<RoomListState> = _state.asStateFlow()

    private val snapshotCoordinator = CoalescingRoomSnapshotCoordinator(
        scope = scope,
        operation = ::synchronizeSnapshot
    )
    private var nextSessionGeneration = 0L
    private var activeSession: Session? = null
    private var cacheJob: Job? = null
    private var liveJob: Job? = null
    private var initialSyncJob: Job? = null
    private var visibleSyncRequestCount = 0

    @MainThread
    suspend fun activate(userId: String) {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return
        val currentSession = activeSession
        if (currentSession?.userId == normalizedUserId) {
            if (cacheJob?.isActive != true) {
                cacheJob = launchCacheObservation(currentSession)
            }
            return
        }
        if (currentSession != null) {
            deactivate()
        }

        nextSessionGeneration += 1
        val session = Session(
            userId = normalizedUserId,
            generation = nextSessionGeneration
        )
        activeSession = session
        snapshotCoordinator.activateSession(normalizedUserId)
        cacheJob = launchCacheObservation(session)
    }

    @MainThread
    fun enableReactiveSynchronization(userId: String) {
        val session = activeSession?.takeIf { it.userId == userId } ?: return
        if (liveJob?.isActive == true) {
            return
        }

        liveJob = scope.launch {
            try {
                driver.observeChangeSignals().collect {
                    synchronize(session = session, showProgress = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session)) {
                    onWarning("Failed to observe room list changes", error)
                }
            }
        }
        initialSyncJob = scope.launch {
            synchronize(session = session, showProgress = true)
        }
    }

    @MainThread
    suspend fun deactivate() {
        nextSessionGeneration += 1
        activeSession = null
        cacheJob?.cancel()
        cacheJob = null
        liveJob?.cancel()
        liveJob = null
        initialSyncJob?.cancel()
        initialSyncJob = null
        visibleSyncRequestCount = 0
        snapshotCoordinator.deactivateSession()
        _state.value = RoomListState()
    }

    private fun launchCacheObservation(session: Session): Job {
        return scope.launch {
            try {
                driver.observeCachedRooms(session.userId).collect { rooms ->
                    if (isCurrent(session)) {
                        _state.update { current -> current.copy(rooms = rooms) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session)) {
                    onWarning("Failed to observe cached rooms", error)
                }
            }
        }
    }

    private suspend fun synchronize(session: Session, showProgress: Boolean) {
        if (!isCurrent(session)) {
            return
        }
        if (showProgress) {
            beginVisibleSynchronization(session)
        }
        try {
            snapshotCoordinator.synchronize()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(session)) {
                onWarning("Failed to synchronize room list snapshot", error)
            }
        } finally {
            if (showProgress) {
                endVisibleSynchronization(session)
            }
        }
    }

    private suspend fun synchronizeSnapshot(userId: String) {
        val rooms = driver.loadSnapshot()
        currentCoroutineContext().ensureActive()
        if (activeSession?.userId != userId) {
            return
        }
        driver.cacheSnapshot(userId, rooms)
    }

    private fun beginVisibleSynchronization(session: Session) {
        if (!isCurrent(session)) {
            return
        }
        visibleSyncRequestCount += 1
        if (visibleSyncRequestCount == 1) {
            _state.update { current -> current.copy(isSynchronizing = true) }
        }
    }

    private fun endVisibleSynchronization(session: Session) {
        if (!isCurrent(session)) {
            return
        }
        visibleSyncRequestCount = (visibleSyncRequestCount - 1).coerceAtLeast(0)
        if (visibleSyncRequestCount == 0) {
            _state.update { current -> current.copy(isSynchronizing = false) }
        }
    }

    private fun isCurrent(session: Session): Boolean {
        return activeSession === session && nextSessionGeneration == session.generation
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
            observeChangeSignals = matrixClientService::roomListChangeSignals,
            loadSnapshot = matrixClientService::roomsSnapshot,
            cacheSnapshot = localCacheRepository::cacheRoomsSnapshot
        ),
        onWarning = onWarning
    )
}
