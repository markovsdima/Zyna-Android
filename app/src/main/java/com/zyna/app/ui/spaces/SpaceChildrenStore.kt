package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.local.SpaceCacheRepository
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceRemoteSnapshot
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.MatrixSpaceRoomListSession
import com.zyna.app.data.matrix.MatrixSpaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SpaceChildrenDriver(
    val peekCache: (
        userId: String,
        spaceId: String
    ) -> MatrixSpaceListSnapshot?,
    val observeCache: (
        userId: String,
        spaceId: String
    ) -> Flow<MatrixSpaceListSnapshot>,
    val openLive: suspend (
        userId: String,
        spaceId: String
    ) -> MatrixSpaceRoomListSession,
    val cacheSnapshot: suspend (
        userId: String,
        spaceId: String,
        snapshot: MatrixSpaceListSnapshot
    ) -> Unit
)

internal class SpaceChildrenStore(
    private val scope: CoroutineScope,
    private val driver: SpaceChildrenDriver,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private data class Activation(
        val target: SpaceTarget,
        val generation: Long
    )

    private val _state = MutableStateFlow(SpaceChildrenState())
    val state: StateFlow<SpaceChildrenState> = _state.asStateFlow()

    private var generation = 0L
    private var activation: Activation? = null
    private var cacheJob: Job? = null
    private var liveJob: Job? = null
    private var paginationJob: Job? = null
    private var liveSession: MatrixSpaceRoomListSession? = null
    private var latestCachedSnapshot: MatrixSpaceListSnapshot? = null
    private var isRequestingPagination = false

    @MainThread
    fun activate(target: SpaceTarget) {
        val current = activation
        if (
            current?.target?.userId == target.userId &&
            current.target.spaceId == target.spaceId &&
            current.target.parentSpaceId == target.parentSpaceId
        ) {
            _state.update { state ->
                state.copy(
                    target = target,
                    space = state.space ?: target.seed
                )
            }
            return
        }

        deactivate()
        generation += 1
        val next = Activation(target = target, generation = generation)
        activation = next
        latestCachedSnapshot = driver.peekCache(target.userId, target.spaceId)
            ?.takeIf(MatrixSpaceListSnapshot::isKnown)
        _state.value = latestCachedSnapshot
            ?.toChildrenState(target = target)
            ?: SpaceChildrenState(target = target, space = target.seed)
        cacheJob = launchCacheObservation(next)
        liveJob = launchLiveObservation(next)
    }

    @MainThread
    fun loadMore() {
        val current = activation ?: return
        val session = liveSession ?: return
        requestPagination(current, session, ignoreCachedEnd = false)
    }

    @MainThread
    fun retry() {
        val current = activation ?: return
        val session = liveSession
        if (session != null) {
            requestPagination(current, session, ignoreCachedEnd = true)
            return
        }
        if (liveJob?.isActive == true) return
        _state.update { it.copy(error = null) }
        liveJob = launchLiveObservation(current)
    }

    @MainThread
    fun deactivate() {
        generation += 1
        activation = null
        cacheJob?.cancel()
        cacheJob = null
        liveJob?.cancel()
        liveJob = null
        paginationJob?.cancel()
        paginationJob = null
        liveSession?.close()
        liveSession = null
        latestCachedSnapshot = null
        isRequestingPagination = false
        _state.value = SpaceChildrenState()
    }

    private fun launchCacheObservation(current: Activation): Job {
        return scope.launch {
            try {
                driver.observeCache(
                    current.target.userId,
                    current.target.spaceId
                ).collect { snapshot ->
                    if (!isCurrent(current)) return@collect
                    val latest = latestCachedSnapshot
                    if (snapshot.isKnown) {
                        latestCachedSnapshot = snapshot
                    } else if (latest?.isKnown == true) {
                        return@collect
                    }
                    _state.value = snapshot.toChildrenState(
                        target = current.target,
                        transient = _state.value
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(current)) {
                    onWarning("Failed to observe cached Space children", error)
                }
            }
        }
    }

    private fun launchLiveObservation(current: Activation): Job {
        return scope.launch {
            var openedSession: MatrixSpaceRoomListSession? = null
            try {
                openedSession = driver.openLive(
                    current.target.userId,
                    current.target.spaceId
                )
                if (!isCurrent(current)) return@launch
                liveSession = openedSession
                requestPagination(current, openedSession, ignoreCachedEnd = true)
                openedSession.snapshots.collect { remote ->
                    if (!isCurrent(current)) return@collect
                    _state.update { state ->
                        state.copy(
                            isPaginating = remote.isPaginating,
                            endReached = if (remote.isKnown) {
                                remote.endReached
                            } else {
                                state.endReached
                            }
                        )
                    }
                    val content = reconciledSpaceSnapshot(
                        cached = latestCachedSnapshot,
                        remote = remote,
                        fallbackSpace = _state.value.space ?: current.target.seed
                    )
                    if (
                        content != null &&
                        content != latestCachedSnapshot?.withoutUpdateTime()
                    ) {
                        try {
                            val storedContent = content.copy(
                                updatedAtMillis = System.currentTimeMillis()
                            )
                            driver.cacheSnapshot(
                                current.target.userId,
                                current.target.spaceId,
                                storedContent
                            )
                            latestCachedSnapshot = storedContent
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            if (isCurrent(current)) {
                                onWarning("Failed to cache Space children", error)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(current)) {
                    _state.update { it.copy(isPaginating = false, error = SpaceLoadError.LOAD) }
                    onWarning("Failed to observe Space children", error)
                }
            } finally {
                if (liveSession === openedSession) {
                    liveSession = null
                }
                openedSession?.close()
            }
        }
    }

    private fun requestPagination(
        current: Activation,
        session: MatrixSpaceRoomListSession,
        ignoreCachedEnd: Boolean
    ) {
        val state = _state.value
        if (
            (!ignoreCachedEnd && state.endReached) ||
            state.isPaginating ||
            isRequestingPagination ||
            paginationJob?.isActive == true
        ) {
            return
        }
        _state.update { it.copy(error = null) }
        paginationJob = scope.launch {
            try {
                paginate(current, session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handlePaginationFailure(current, session, error)
            }
        }
    }

    private suspend fun paginate(
        current: Activation,
        session: MatrixSpaceRoomListSession
    ) {
        if (
            !isCurrent(current) ||
            liveSession !== session ||
            isRequestingPagination
        ) {
            return
        }
        isRequestingPagination = true
        try {
            session.paginate()
        } finally {
            isRequestingPagination = false
        }
    }

    private fun handlePaginationFailure(
        current: Activation,
        session: MatrixSpaceRoomListSession,
        error: Throwable
    ) {
        if (isCurrent(current) && liveSession === session) {
            _state.update { it.copy(isPaginating = false, error = SpaceLoadError.LOAD) }
            onWarning("Failed to paginate Space children", error)
        }
    }

    private fun isCurrent(current: Activation): Boolean {
        return activation === current && generation == current.generation
    }
}

internal fun createSpaceChildrenStore(
    scope: CoroutineScope,
    matrixSpaceService: MatrixSpaceService,
    cacheRepository: SpaceCacheRepository,
    onWarning: (String, Throwable) -> Unit
): SpaceChildrenStore {
    return SpaceChildrenStore(
        scope = scope,
        driver = SpaceChildrenDriver(
            peekCache = cacheRepository::peekSpaceChildren,
            observeCache = cacheRepository::observeSpaceChildren,
            openLive = matrixSpaceService::openRoomList,
            cacheSnapshot = cacheRepository::cacheSpaceChildren
        ),
        onWarning = onWarning
    )
}

/**
 * Keeps a previously loaded hierarchy stable while the SDK rebuilds its paginated list.
 * Only a terminal remote snapshot may remove cached children. Until then the loaded
 * remote prefix updates in place and the cached tail remains visible.
 */
internal fun reconciledSpaceSnapshot(
    cached: MatrixSpaceListSnapshot?,
    remote: MatrixSpaceRemoteSnapshot,
    fallbackSpace: MatrixSpaceRoom
): MatrixSpaceListSnapshot? {
    if (!remote.isKnown) return null
    val knownCache = cached?.takeIf(MatrixSpaceListSnapshot::isKnown)
    if (!remote.endReached && remote.rooms.isEmpty() && knownCache == null) {
        return null
    }
    val rooms = if (remote.endReached || knownCache == null) {
        remote.rooms
    } else {
        val remoteIds = remote.rooms.asSequence().map(MatrixSpaceRoom::roomId).toHashSet()
        remote.rooms + knownCache.rooms.filterNot { it.roomId in remoteIds }
    }
    return MatrixSpaceListSnapshot(
        space = remote.space ?: knownCache?.space ?: fallbackSpace,
        rooms = rooms,
        isKnown = true,
        endReached = remote.endReached
    )
}

private fun MatrixSpaceListSnapshot.toChildrenState(
    target: SpaceTarget,
    transient: SpaceChildrenState = SpaceChildrenState()
): SpaceChildrenState {
    return SpaceChildrenState(
        target = target,
        space = space ?: transient.space ?: target.seed,
        tracks = rooms.filter { it.kind == MatrixSpaceRoomKind.SPACE },
        chats = rooms.filter { it.kind == MatrixSpaceRoomKind.ROOM },
        isKnown = isKnown,
        endReached = endReached,
        isPaginating = transient.isPaginating,
        error = transient.error
    )
}

private fun MatrixSpaceListSnapshot.withoutUpdateTime(): MatrixSpaceListSnapshot {
    return copy(updatedAtMillis = null)
}
