package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.local.SpaceCacheRepository
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceListUpdate
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.MatrixSpaceService
import com.zyna.app.data.matrix.applyMatrixSpaceListUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SpaceRootsDriver(
    val peekCache: (userId: String) -> MatrixSpaceListSnapshot?,
    val observeCache: (userId: String) -> Flow<MatrixSpaceListSnapshot>,
    val observeReadiness: () -> Flow<Boolean>,
    val loadCurrent: suspend (userId: String) -> List<MatrixSpaceRoom>,
    val observeLiveUpdates: (userId: String) -> Flow<List<MatrixSpaceListUpdate>>,
    val cacheSnapshot: suspend (userId: String, snapshot: MatrixSpaceListSnapshot) -> Unit
)

internal class SpaceRootsStore(
    private val scope: CoroutineScope,
    private val driver: SpaceRootsDriver,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private data class Session(
        val userId: String,
        val generation: Long
    )

    private val _state = MutableStateFlow(SpaceRootsState())
    val state: StateFlow<SpaceRootsState> = _state.asStateFlow()

    private var generation = 0L
    private var activeSession: Session? = null
    private var cacheJob: Job? = null
    private var liveJob: Job? = null
    private var latestCachedSnapshot: MatrixSpaceListSnapshot? = null
    private var latestLiveRootIds: Set<String>? = null
    private val confirmedJoinedRoots = linkedMapOf<String, MatrixSpaceRoom>()

    @MainThread
    fun activate(userId: String) {
        val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return
        if (activeSession?.userId == normalizedUserId) {
            if (cacheJob?.isActive != true) {
                cacheJob = launchCacheObservation(activeSession ?: return)
            }
            return
        }
        deactivate()
        generation += 1
        val session = Session(normalizedUserId, generation)
        activeSession = session
        latestCachedSnapshot = driver.peekCache(normalizedUserId)
        latestCachedSnapshot?.let { snapshot ->
            _state.value = SpaceRootsState(snapshot)
        }
        cacheJob = launchCacheObservation(session)
    }

    @MainThread
    fun enableLive(userId: String) {
        val session = activeSession?.takeIf { it.userId == userId } ?: return
        if (liveJob?.isActive == true) return

        liveJob = scope.launch {
            var liveRooms = emptyList<MatrixSpaceRoom>()

            suspend fun cacheIfChanged() {
                if (!isCurrent(session)) return
                val liveRoomIds = liveRooms.mapTo(HashSet(liveRooms.size)) { it.roomId }
                latestLiveRootIds = liveRoomIds
                val content = MatrixSpaceListSnapshot(
                    rooms = liveRooms,
                    isKnown = true,
                    endReached = true
                )
                if (content == latestCachedSnapshot?.withoutUpdateTime()) {
                    confirmedJoinedRoots.keys.removeAll(liveRoomIds)
                    return
                }
                try {
                    val storedContent = content.copy(
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    driver.cacheSnapshot(userId, storedContent)
                    latestCachedSnapshot = storedContent
                    confirmedJoinedRoots.keys.removeAll(liveRoomIds)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (isCurrent(session)) {
                        onWarning("Failed to cache top-level Spaces", error)
                    }
                }
            }

            try {
                driver.observeReadiness().first { isReady -> isReady }
                var retryDelayMillis = ROOT_LOAD_RETRY_INITIAL_MILLIS
                var didWarnAboutCurrentLoad = false
                while (isCurrent(session)) {
                    try {
                        liveRooms = driver.loadCurrent(userId)
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (!didWarnAboutCurrentLoad && isCurrent(session)) {
                            didWarnAboutCurrentLoad = true
                            onWarning("Failed to load current top-level Spaces", error)
                        }
                        delay(retryDelayMillis)
                        retryDelayMillis = (retryDelayMillis * 2)
                            .coerceAtMost(ROOT_LOAD_RETRY_MAX_MILLIS)
                    }
                }
                if (!isCurrent(session)) return@launch
                cacheIfChanged()
                driver.observeLiveUpdates(userId).collect { updates ->
                    if (!isCurrent(session)) return@collect
                    liveRooms = applyMatrixSpaceListUpdates(liveRooms, updates)
                    cacheIfChanged()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session)) {
                    onWarning("Failed to observe top-level Spaces", error)
                }
            }
        }
    }

    /**
     * Keeps a newly accepted root invitation visible until the joined-Spaces projection catches
     * up. The ordinary room list and the Spaces graph are independent SDK projections and may
     * acknowledge the same successful join in different frames.
     */
    @MainThread
    fun confirmJoinedRoot(userId: String, room: MatrixSpaceRoom) {
        val session = activeSession?.takeIf { it.userId == userId } ?: return
        if (
            !isCurrent(session) ||
            room.roomId.isBlank() ||
            room.kind != MatrixSpaceRoomKind.SPACE ||
            !room.isJoined
        ) {
            return
        }
        val isAlreadyAcknowledged = room.roomId in latestLiveRootIds.orEmpty() &&
            latestCachedSnapshot?.rooms?.any { it.roomId == room.roomId } == true
        if (isAlreadyAcknowledged) {
            confirmedJoinedRoots.remove(room.roomId)
            return
        }
        confirmedJoinedRoots[room.roomId] = room
        _state.update { state ->
            SpaceRootsState(state.snapshot.withConfirmedJoinedRoots(confirmedJoinedRoots))
        }
    }

    @MainThread
    fun deactivate() {
        generation += 1
        activeSession = null
        cacheJob?.cancel()
        cacheJob = null
        liveJob?.cancel()
        liveJob = null
        latestCachedSnapshot = null
        latestLiveRootIds = null
        confirmedJoinedRoots.clear()
        _state.value = SpaceRootsState()
    }

    private fun launchCacheObservation(session: Session): Job {
        return scope.launch {
            try {
                driver.observeCache(session.userId).collect { snapshot ->
                    if (isCurrent(session)) {
                        if (snapshot.isKnown) {
                            latestCachedSnapshot = snapshot
                        }
                        _state.value = SpaceRootsState(
                            snapshot.withConfirmedJoinedRoots(confirmedJoinedRoots)
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(session)) {
                    onWarning("Failed to observe cached top-level Spaces", error)
                }
            }
        }
    }

    private fun isCurrent(session: Session): Boolean {
        return activeSession === session && generation == session.generation
    }

    private companion object {
        const val ROOT_LOAD_RETRY_INITIAL_MILLIS = 100L
        const val ROOT_LOAD_RETRY_MAX_MILLIS = 2_000L
    }
}

internal fun createSpaceRootsStore(
    scope: CoroutineScope,
    matrixSpaceService: MatrixSpaceService,
    cacheRepository: SpaceCacheRepository,
    onWarning: (String, Throwable) -> Unit
): SpaceRootsStore {
    return SpaceRootsStore(
        scope = scope,
        driver = SpaceRootsDriver(
            peekCache = cacheRepository::peekTopLevelSpaces,
            observeCache = cacheRepository::observeTopLevelSpaces,
            observeReadiness = matrixSpaceService::topLevelSpaceReadiness,
            loadCurrent = matrixSpaceService::currentTopLevelSpaces,
            observeLiveUpdates = matrixSpaceService::topLevelSpaceUpdates,
            cacheSnapshot = cacheRepository::cacheTopLevelSpaces
        ),
        onWarning = onWarning
    )
}

private fun MatrixSpaceListSnapshot.withoutUpdateTime(): MatrixSpaceListSnapshot {
    return copy(updatedAtMillis = null)
}

private fun MatrixSpaceListSnapshot.withConfirmedJoinedRoots(
    confirmedRoots: Map<String, MatrixSpaceRoom>
): MatrixSpaceListSnapshot {
    if (confirmedRoots.isEmpty()) return this
    val roomIds = rooms.mapTo(HashSet(rooms.size)) { it.roomId }
    val missingRoots = confirmedRoots.values.filterNot { it.roomId in roomIds }
    return if (missingRoots.isEmpty()) this else copy(rooms = rooms + missingRoots)
}
