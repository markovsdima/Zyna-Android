package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.local.SpaceCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomPermission
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRemoteSnapshot
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.MatrixSpaceRoomListSession
import com.zyna.app.data.matrix.MatrixSpaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    ) -> Unit,
    val observeCanManage: (spaceId: String) -> Flow<Boolean>,
    val loadCanManage: suspend (spaceId: String) -> Boolean,
    val removeChild: suspend (
        userId: String,
        spaceId: String,
        childId: String
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
    private var permissionJob: Job? = null
    private var liveJob: Job? = null
    private var paginationJob: Job? = null
    private var removalJob: Job? = null
    private var liveSession: MatrixSpaceRoomListSession? = null
    private var latestCachedSnapshot: MatrixSpaceListSnapshot? = null
    private var isRequestingPagination = false
    private val confirmedMemberships = mutableMapOf<String, ConfirmedMembershipOverlay>()
    private val confirmedRemovedRoomIds = mutableSetOf<String>()

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
        permissionJob = launchPermissionObservation(next)
        liveJob = launchLiveObservation(next)
    }

    @MainThread
    fun loadMore() {
        if (_state.value.management.isRemoving) return
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
    fun enterManagement() {
        _state.update { state ->
            if (
                !state.management.canManage ||
                state.management.isRemoving ||
                state.chats.isEmpty()
            ) {
                state
            } else {
                state.copy(
                    management = state.management.copy(
                        isManaging = true,
                        selectedRoomIds = emptySet(),
                        pendingRemovalRoomIds = emptySet(),
                        error = null
                    )
                )
            }
        }
    }

    @MainThread
    fun exitManagement() {
        _state.update { state ->
            if (state.management.isRemoving) {
                state
            } else {
                state.copy(
                    management = state.management.copy(
                        isManaging = false,
                        selectedRoomIds = emptySet(),
                        pendingRemovalRoomIds = emptySet(),
                        error = null
                    )
                )
            }
        }
    }

    @MainThread
    fun toggleManagedRoom(roomId: String) {
        _state.update { state ->
            val management = state.management
            if (
                !management.isManaging ||
                management.isRemoving ||
                state.chats.none { it.roomId == roomId }
            ) {
                state
            } else {
                val selected = if (roomId in management.selectedRoomIds) {
                    management.selectedRoomIds - roomId
                } else {
                    management.selectedRoomIds + roomId
                }
                state.copy(
                    management = management.copy(
                        selectedRoomIds = selected,
                        pendingRemovalRoomIds = emptySet(),
                        error = null
                    )
                )
            }
        }
    }

    @MainThread
    fun toggleAllManagedRooms() {
        _state.update { state ->
            val management = state.management
            if (!management.isManaging || management.isRemoving || state.chats.isEmpty()) {
                state
            } else {
                val allRoomIds = state.chats.mapTo(linkedSetOf(), MatrixSpaceRoom::roomId)
                state.copy(
                    management = management.copy(
                        selectedRoomIds = if (management.selectedRoomIds == allRoomIds) {
                            emptySet()
                        } else {
                            allRoomIds
                        },
                        pendingRemovalRoomIds = emptySet(),
                        error = null
                    )
                )
            }
        }
    }

    @MainThread
    fun requestSelectedRoomsRemoval() {
        _state.update { state ->
            if (!state.management.canRequestRemoval) {
                state
            } else {
                state.copy(
                    management = state.management.copy(
                        pendingRemovalRoomIds = state.management.selectedRoomIds,
                        error = null
                    )
                )
            }
        }
    }

    @MainThread
    fun cancelSelectedRoomsRemoval() {
        _state.update { state ->
            if (state.management.isRemoving) state else state.copy(
                management = state.management.copy(pendingRemovalRoomIds = emptySet())
            )
        }
    }

    @MainThread
    fun confirmSelectedRoomsRemoval() {
        val current = activation ?: return
        val management = _state.value.management
        val selectedRoomIds = management.pendingRemovalRoomIds
        if (
            selectedRoomIds.isEmpty() ||
            management.isRemoving ||
            removalJob?.isActive == true
        ) {
            return
        }
        _state.update { state ->
            state.copy(
                management = state.management.copy(
                    pendingRemovalRoomIds = emptySet(),
                    isRemoving = true,
                    error = null
                )
            )
        }
        removalJob = scope.launch {
            removeSelectedRooms(current, selectedRoomIds)
        }
    }

    /**
     * Keeps a successful user mutation visible until the hierarchy stream reflects it.
     * The command store persists the same membership before delivering this confirmation.
     */
    @MainThread
    fun confirmChildMembership(
        userId: String,
        spaceId: String,
        roomId: String,
        membership: MatrixSpaceMembership
    ) {
        val current = activation ?: return
        if (
            current.target.userId != userId ||
            current.target.spaceId != spaceId ||
            roomId.isBlank()
        ) {
            return
        }
        val currentRoom = _state.value.roomForId(roomId) ?: return
        if (
            currentRoom.membership == membership &&
            confirmedMemberships[roomId]?.membership == membership
        ) {
            return
        }
        confirmedMemberships[roomId] = ConfirmedMembershipOverlay(
            membership = membership,
            replacedMembership = currentRoom.membership
        )
        _state.update { state -> state.withConfirmedMembership(roomId, membership) }

        latestCachedSnapshot = latestCachedSnapshot
            ?.withConfirmedMemberships(confirmedMemberships)
    }

    @MainThread
    fun deactivate() {
        generation += 1
        activation = null
        cacheJob?.cancel()
        cacheJob = null
        permissionJob?.cancel()
        permissionJob = null
        liveJob?.cancel()
        liveJob = null
        paginationJob?.cancel()
        paginationJob = null
        removalJob?.cancel()
        removalJob = null
        liveSession?.close()
        liveSession = null
        latestCachedSnapshot = null
        isRequestingPagination = false
        confirmedMemberships.clear()
        confirmedRemovedRoomIds.clear()
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
                    val visibleSnapshot = snapshot
                        .withConfirmedMemberships(confirmedMemberships)
                        .withoutConfirmedRemovals(confirmedRemovedRoomIds)
                    val latest = latestCachedSnapshot
                    if (visibleSnapshot.isKnown) {
                        latestCachedSnapshot = visibleSnapshot
                    } else if (latest?.isKnown == true) {
                        return@collect
                    }
                    _state.value = visibleSnapshot.toChildrenState(
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

    private fun launchPermissionObservation(current: Activation): Job {
        return scope.launch {
            try {
                driver.observeCanManage(current.target.spaceId).collect { canManage ->
                    if (!isCurrent(current)) return@collect
                    _state.update { state ->
                        val management = state.management
                        state.copy(
                            management = when {
                                canManage -> management.copy(
                                    canManage = true,
                                    error = management.error.takeUnless {
                                        it == SpaceChildManagementError.PERMISSION_CHANGED
                                    }
                                )
                                management.isRemoving -> management.copy(canManage = false)
                                else -> SpaceChildManagementState(
                                    canManage = false,
                                    error = SpaceChildManagementError.PERMISSION_CHANGED.takeIf {
                                        management.isManaging || management.error ==
                                            SpaceChildManagementError.PERMISSION_CHANGED
                                    }
                                )
                            }
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(current)) {
                    onWarning("Failed to observe Space management permission", error)
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
                    confirmedMemberships.entries.removeAll { (roomId, confirmation) ->
                        remote.rooms
                            .firstOrNull { it.roomId == roomId }
                            ?.membership
                            ?.let(confirmation::isSupersededBy) == true
                    }
                    if (remote.isKnown && remote.endReached) {
                        val remoteRoomIds = remote.rooms
                            .asSequence()
                            .map(MatrixSpaceRoom::roomId)
                            .toHashSet()
                        confirmedRemovedRoomIds.removeAll { it !in remoteRoomIds }
                    }
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
                        ?.withConfirmedMemberships(confirmedMemberships)
                        ?.withoutConfirmedRemovals(confirmedRemovedRoomIds)
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

    private suspend fun removeSelectedRooms(
        current: Activation,
        selectedRoomIds: Set<String>
    ) {
        try {
            val canManage = driver.loadCanManage(current.target.spaceId)
            if (!isCurrent(current)) return
            if (!canManage) {
                _state.update { state ->
                    state.copy(
                        management = SpaceChildManagementState(
                            canManage = false,
                            error = SpaceChildManagementError.PERMISSION_CHANGED
                        )
                    )
                }
                return
            }

            val removalSemaphore = Semaphore(MAX_CONCURRENT_REMOVALS)
            val outcomes = coroutineScope {
                selectedRoomIds.map { roomId ->
                    async {
                        removalSemaphore.withPermit {
                            try {
                                driver.removeChild(
                                    current.target.userId,
                                    current.target.spaceId,
                                    roomId
                                )
                                RoomRemovalOutcome(roomId, succeeded = true)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                onWarning("Failed to remove a room from Space", error)
                                RoomRemovalOutcome(roomId, succeeded = false)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (!isCurrent(current)) return

            val removedRoomIds = outcomes
                .asSequence()
                .filter(RoomRemovalOutcome::succeeded)
                .mapTo(linkedSetOf(), RoomRemovalOutcome::roomId)
            val failedRoomIds = selectedRoomIds - removedRoomIds
            if (removedRoomIds.isNotEmpty()) {
                confirmChildrenRemoved(current, removedRoomIds)
            }
            try {
                refreshHierarchy(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = SpaceLoadError.LOAD) }
                onWarning("Failed to refresh Space hierarchy after removal", error)
            }
            if (!isCurrent(current)) return

            _state.update { state ->
                val visibleChatIds = state.chats
                    .mapTo(hashSetOf(), MatrixSpaceRoom::roomId)
                val remainingFailedRoomIds = failedRoomIds.intersect(visibleChatIds)
                val permissionChanged = !state.management.canManage
                state.copy(
                    management = if (permissionChanged) {
                        SpaceChildManagementState(
                            canManage = false,
                            error = SpaceChildManagementError.PERMISSION_CHANGED
                        )
                    } else {
                        state.management.copy(
                            isManaging = remainingFailedRoomIds.isNotEmpty(),
                            selectedRoomIds = remainingFailedRoomIds,
                            pendingRemovalRoomIds = emptySet(),
                            isRemoving = false,
                            error = when {
                                remainingFailedRoomIds.isEmpty() -> null
                                removedRoomIds.isEmpty() ->
                                    SpaceChildManagementError.REMOVE
                                else -> SpaceChildManagementError.PARTIAL_REMOVE
                            }
                        )
                    }
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(current)) {
                _state.update { state ->
                    state.copy(
                        management = state.management.copy(
                            pendingRemovalRoomIds = emptySet(),
                            isRemoving = false,
                            error = SpaceChildManagementError.REMOVE
                        )
                    )
                }
                onWarning("Failed to update Space children", error)
            }
        }
    }

    private suspend fun confirmChildrenRemoved(
        current: Activation,
        roomIds: Set<String>
    ) {
        confirmedRemovedRoomIds += roomIds
        _state.update { state -> state.withConfirmedRemovals(roomIds) }
        val snapshot = latestCachedSnapshot
            ?.withoutConfirmedRemovals(confirmedRemovedRoomIds)
            ?: return
        latestCachedSnapshot = snapshot
        try {
            driver.cacheSnapshot(
                current.target.userId,
                current.target.spaceId,
                snapshot.copy(updatedAtMillis = System.currentTimeMillis())
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(current)) {
                onWarning("Failed to cache removed Space children", error)
            }
        }
    }

    private suspend fun refreshHierarchy(current: Activation) {
        paginationJob?.join()
        val session = liveSession ?: return
        if (!isCurrent(current) || isRequestingPagination) return
        isRequestingPagination = true
        try {
            session.reset()
            if (!isCurrent(current) || liveSession !== session) return
            _state.update { it.copy(endReached = false, error = null) }
            session.paginate()
        } finally {
            isRequestingPagination = false
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

    private companion object {
        const val MAX_CONCURRENT_REMOVALS = 4
    }
}

private data class RoomRemovalOutcome(
    val roomId: String,
    val succeeded: Boolean
)

private fun SpaceChildrenState.withConfirmedMembership(
    roomId: String,
    membership: MatrixSpaceMembership
): SpaceChildrenState {
    fun List<MatrixSpaceRoom>.patched(): List<MatrixSpaceRoom> {
        return map { room ->
            if (room.roomId == roomId) room.copy(membership = membership) else room
        }
    }
    return copy(tracks = tracks.patched(), chats = chats.patched())
}

private fun SpaceChildrenState.withConfirmedRemovals(
    roomIds: Set<String>
): SpaceChildrenState {
    if (roomIds.isEmpty()) return this
    return copy(
        tracks = tracks.filterNot { it.roomId in roomIds },
        chats = chats.filterNot { it.roomId in roomIds },
        management = management.withAvailableChats(
            chats.asSequence()
                .filterNot { it.roomId in roomIds }
                .map(MatrixSpaceRoom::roomId)
                .toSet()
        )
    )
}

private data class ConfirmedMembershipOverlay(
    val membership: MatrixSpaceMembership,
    val replacedMembership: MatrixSpaceMembership
) {
    /**
     * The expected value acknowledges the command. A different concrete value represents a newer
     * membership transition that may have skipped the expected state in a conflated SDK stream.
     * UNKNOWN carries no ordering information and cannot supersede a successful local command.
     */
    fun isSupersededBy(remoteMembership: MatrixSpaceMembership): Boolean {
        return remoteMembership == membership ||
            (remoteMembership != MatrixSpaceMembership.UNKNOWN &&
                remoteMembership != replacedMembership)
    }
}

private fun MatrixSpaceListSnapshot.withConfirmedMemberships(
    memberships: Map<String, ConfirmedMembershipOverlay>
): MatrixSpaceListSnapshot {
    if (memberships.isEmpty()) return this
    return copy(
        rooms = rooms.map { room ->
            memberships[room.roomId]
                ?.let { confirmation -> room.copy(membership = confirmation.membership) }
                ?: room
        }
    )
}

private fun MatrixSpaceListSnapshot.withoutConfirmedRemovals(
    roomIds: Set<String>
): MatrixSpaceListSnapshot {
    if (roomIds.isEmpty()) return this
    return copy(rooms = rooms.filterNot { it.roomId in roomIds })
}

internal fun createSpaceChildrenStore(
    scope: CoroutineScope,
    matrixSpaceService: MatrixSpaceService,
    matrixClientService: MatrixClientService,
    cacheRepository: SpaceCacheRepository,
    onWarning: (String, Throwable) -> Unit
): SpaceChildrenStore {
    return SpaceChildrenStore(
        scope = scope,
        driver = SpaceChildrenDriver(
            peekCache = cacheRepository::peekSpaceChildren,
            observeCache = cacheRepository::observeSpaceChildren,
            openLive = matrixSpaceService::openRoomList,
            cacheSnapshot = cacheRepository::cacheSpaceChildren,
            observeCanManage = { spaceId ->
                matrixClientService.roomPermissionsUpdates(spaceId)
                    .map { permissions ->
                        permissions.canPerform(MatrixRoomPermission.MANAGE_SPACE_CHILDREN)
                    }
                    .distinctUntilChanged()
            },
            loadCanManage = { spaceId ->
                matrixClientService.canManageSpaceChildren(spaceId)
            },
            removeChild = matrixSpaceService::removeChildFromSpace
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
    val chats = rooms.filter { it.kind == MatrixSpaceRoomKind.ROOM }
    return SpaceChildrenState(
        target = target,
        space = space ?: transient.space ?: target.seed,
        tracks = rooms.filter { it.kind == MatrixSpaceRoomKind.SPACE },
        chats = chats,
        isKnown = isKnown,
        endReached = endReached,
        isPaginating = transient.isPaginating,
        error = transient.error,
        management = transient.management.withAvailableChats(
            chats.mapTo(hashSetOf(), MatrixSpaceRoom::roomId)
        )
    )
}

private fun SpaceChildManagementState.withAvailableChats(
    availableRoomIds: Set<String>
): SpaceChildManagementState {
    val selected = selectedRoomIds.intersect(availableRoomIds)
    val pending = pendingRemovalRoomIds.intersect(availableRoomIds)
    if (
        error in setOf(
            SpaceChildManagementError.REMOVE,
            SpaceChildManagementError.PARTIAL_REMOVE
        ) &&
        selected.isEmpty() &&
        !isRemoving
    ) {
        return copy(
            isManaging = false,
            selectedRoomIds = emptySet(),
            pendingRemovalRoomIds = emptySet(),
            error = null
        )
    }
    return copy(
        selectedRoomIds = selected,
        pendingRemovalRoomIds = pending
    )
}

private fun MatrixSpaceListSnapshot.withoutUpdateTime(): MatrixSpaceListSnapshot {
    return copy(updatedAtMillis = null)
}
