package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceService
import com.zyna.app.data.matrix.toJoinedSpaceChild
import com.zyna.app.ui.rooms.RoomListStore
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SpaceAddRoomsTarget(
    val userId: String,
    val spaceId: String,
    val parentSpaceId: String?,
    val displayName: String
)

enum class SpaceAddRoomsError {
    ADD,
    PARTIAL_ADD,
    PERMISSION_CHANGED
}

data class SpaceAddRoomsState(
    val target: SpaceAddRoomsTarget? = null,
    val availableRooms: List<MatrixRoomSummary> = emptyList(),
    val visibleRooms: List<MatrixRoomSummary> = emptyList(),
    val selectedRoomIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val canManage: Boolean = false,
    val isSaving: Boolean = false,
    val error: SpaceAddRoomsError? = null
) {
    val selectedRooms: List<MatrixRoomSummary>
        get() = availableRooms.filter { room -> room.id in selectedRoomIds }

    val canSave: Boolean
        get() = canManage && !isSaving && selectedRoomIds.isNotEmpty()
}

internal data class SpaceAddRoomsSource(
    val rooms: List<MatrixRoomSummary>,
    val childRoomIds: Set<String>,
    val canManage: Boolean
)

internal class SpaceAddRoomsDriver(
    val observeSource: (SpaceAddRoomsTarget) -> Flow<SpaceAddRoomsSource>,
    val loadCanManage: suspend (spaceId: String) -> Boolean,
    val addChild: suspend (
        userId: String,
        spaceId: String,
        childId: String
    ) -> Unit,
    val confirmAdded: suspend (
        target: SpaceAddRoomsTarget,
        rooms: Collection<MatrixSpaceRoom>
    ) -> Unit,
    /** Returns requested child ids found before every id is seen or the hierarchy ends. */
    val refreshChildren: suspend (
        target: SpaceAddRoomsTarget,
        roomIdsToFind: Set<String>
    ) -> Set<String>?
)

internal class SpaceAddRoomsStore(
    private val scope: CoroutineScope,
    private val driver: SpaceAddRoomsDriver,
    private val onAdded: (SpaceAddRoomsTarget, Set<String>) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private data class Activation(
        val target: SpaceAddRoomsTarget,
        val generation: Long
    )

    private val _state = MutableStateFlow(SpaceAddRoomsState())
    val state: StateFlow<SpaceAddRoomsState> = _state.asStateFlow()

    private var generation = 0L
    private var activation: Activation? = null
    private var sourceJob: Job? = null
    private var saveJob: Job? = null
    private val locallyAddedRoomIds = linkedSetOf<String>()

    @MainThread
    fun activate(target: SpaceAddRoomsTarget) {
        val normalized = target.copy(
            userId = target.userId.trim(),
            spaceId = target.spaceId.trim()
        )
        if (normalized.userId.isEmpty() || normalized.spaceId.isEmpty()) return
        if (activation?.target == normalized) return

        deactivate()
        generation += 1
        val next = Activation(normalized, generation)
        activation = next
        _state.value = SpaceAddRoomsState(target = normalized)
        sourceJob = scope.launch {
            driver.observeSource(normalized).collect { source ->
                if (!isCurrent(next)) return@collect
                _state.update { current ->
                    val available = eligibleSpaceChildCandidates(
                        rooms = source.rooms,
                        childRoomIds = source.childRoomIds + locallyAddedRoomIds
                    )
                    val permissionWasRevoked = current.canManage && !source.canManage
                    current.copy(
                        availableRooms = available,
                        visibleRooms = available.filteredBy(current.searchQuery),
                        selectedRoomIds = current.selectedRoomIds.intersect(
                            available.mapTo(hashSetOf(), MatrixRoomSummary::id)
                        ),
                        canManage = source.canManage,
                        error = when {
                            permissionWasRevoked && !current.isSaving ->
                                SpaceAddRoomsError.PERMISSION_CHANGED
                            source.canManage &&
                                current.error == SpaceAddRoomsError.PERMISSION_CHANGED -> null
                            else -> current.error
                        }
                    )
                }
            }
        }
    }

    @MainThread
    fun setSearchQuery(query: String) {
        _state.update { current ->
            if (current.isSaving) current else current.copy(
                searchQuery = query,
                visibleRooms = current.availableRooms.filteredBy(query)
            )
        }
    }

    @MainThread
    fun toggleRoom(roomId: String) {
        _state.update { current ->
            if (
                current.isSaving ||
                !current.canManage ||
                current.availableRooms.none { room -> room.id == roomId }
            ) {
                current
            } else {
                current.copy(
                    selectedRoomIds = if (roomId in current.selectedRoomIds) {
                        current.selectedRoomIds - roomId
                    } else {
                        current.selectedRoomIds + roomId
                    },
                    error = null
                )
            }
        }
    }

    @MainThread
    fun save() {
        val current = activation ?: return
        val selectedRooms = _state.value.selectedRooms
        if (!_state.value.canSave || selectedRooms.isEmpty() || saveJob?.isActive == true) return
        _state.update { state -> state.copy(isSaving = true, error = null) }
        saveJob = scope.launch { addSelectedRooms(current, selectedRooms) }
    }

    @MainThread
    fun deactivate() {
        generation += 1
        sourceJob?.cancel()
        sourceJob = null
        saveJob?.cancel()
        saveJob = null
        activation = null
        locallyAddedRoomIds.clear()
        _state.value = SpaceAddRoomsState()
    }

    private suspend fun addSelectedRooms(
        current: Activation,
        selectedRooms: List<MatrixRoomSummary>
    ) {
        try {
            val canManage = driver.loadCanManage(current.target.spaceId)
            if (!isCurrent(current)) return
            if (!canManage) {
                _state.update { state ->
                    state.copy(
                        canManage = false,
                        isSaving = false,
                        error = SpaceAddRoomsError.PERMISSION_CHANGED
                    )
                }
                return
            }

            val semaphore = Semaphore(MAX_CONCURRENT_ADDITIONS)
            val outcomes = coroutineScope {
                selectedRooms.map { room ->
                    async {
                        semaphore.withPermit {
                            try {
                                driver.addChild(
                                    current.target.userId,
                                    current.target.spaceId,
                                    room.id
                                )
                                RoomAdditionOutcome(room, succeeded = true)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                onWarning("Failed to add a room to Space", error)
                                RoomAdditionOutcome(room, succeeded = false)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (!isCurrent(current)) return

            val confirmedRooms = outcomes
                .filter(RoomAdditionOutcome::succeeded)
                .map { outcome -> outcome.room.toJoinedSpaceChild() }
            if (confirmedRooms.isNotEmpty()) {
                locallyAddedRoomIds += confirmedRooms.map(MatrixSpaceRoom::roomId)
                driver.confirmAdded(current.target, confirmedRooms)
            }

            val selectedIds = selectedRooms.mapTo(linkedSetOf(), MatrixRoomSummary::id)
            val reportedSuccessIds = confirmedRooms
                .mapTo(linkedSetOf(), MatrixSpaceRoom::roomId)
            val ambiguousRoomIds = selectedIds - reportedSuccessIds
            val refreshedChildRoomIds = try {
                driver.refreshChildren(current.target, ambiguousRoomIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onWarning("Failed to refresh Space hierarchy after addition", error)
                null
            }
            if (!isCurrent(current)) return

            val reconciledSuccessIds = ambiguousRoomIds
                .intersect(refreshedChildRoomIds.orEmpty())
            val addedRoomIds = reportedSuccessIds + reconciledSuccessIds
            locallyAddedRoomIds += addedRoomIds

            if (reconciledSuccessIds.isNotEmpty()) {
                val reconciledRooms = selectedRooms
                    .filter { room -> room.id in reconciledSuccessIds }
                    .map(MatrixRoomSummary::toJoinedSpaceChild)
                driver.confirmAdded(current.target, reconciledRooms)
            }
            if (!isCurrent(current)) return

            val failedRoomIds = selectedIds - addedRoomIds
            val permissionChanged = !_state.value.canManage
            _state.update { state ->
                val remainingAvailable = state.availableRooms
                    .filterNot { room -> room.id in addedRoomIds }
                state.copy(
                    availableRooms = remainingAvailable,
                    visibleRooms = remainingAvailable.filteredBy(state.searchQuery),
                    selectedRoomIds = failedRoomIds,
                    isSaving = false,
                    error = when {
                        permissionChanged -> SpaceAddRoomsError.PERMISSION_CHANGED
                        failedRoomIds.isEmpty() -> null
                        addedRoomIds.isEmpty() -> SpaceAddRoomsError.ADD
                        else -> SpaceAddRoomsError.PARTIAL_ADD
                    }
                )
            }
            if (!permissionChanged && failedRoomIds.isEmpty()) {
                onAdded(current.target, addedRoomIds)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent(current)) {
                _state.update { state ->
                    state.copy(isSaving = false, error = SpaceAddRoomsError.ADD)
                }
                onWarning("Failed to update Space children", error)
            }
        }
    }

    private fun isCurrent(current: Activation): Boolean {
        return activation === current && generation == current.generation
    }

    private data class RoomAdditionOutcome(
        val room: MatrixRoomSummary,
        val succeeded: Boolean
    )

    private companion object {
        const val MAX_CONCURRENT_ADDITIONS = 4
    }
}

internal fun eligibleSpaceChildCandidates(
    rooms: List<MatrixRoomSummary>,
    childRoomIds: Set<String>
): List<MatrixRoomSummary> {
    return rooms.filter { room ->
        room.kind == MatrixRoomKind.GROUP &&
            room.isJoined &&
            room.id !in childRoomIds
    }
}

private fun List<MatrixRoomSummary>.filteredBy(query: String): List<MatrixRoomSummary> {
    val normalized = query.trim()
    return if (normalized.isEmpty()) this else filter { room ->
        room.displayName.contains(normalized, ignoreCase = true)
    }
}

internal fun createSpaceAddRoomsStore(
    scope: CoroutineScope,
    roomListStore: RoomListStore,
    spaceChildrenStore: SpaceChildrenStore,
    matrixClientService: MatrixClientService,
    matrixSpaceService: MatrixSpaceService,
    onAdded: (SpaceAddRoomsTarget, Set<String>) -> Unit,
    onWarning: (String, Throwable) -> Unit
): SpaceAddRoomsStore {
    return SpaceAddRoomsStore(
        scope = scope,
        driver = SpaceAddRoomsDriver(
            observeSource = { target ->
                combine(roomListStore.state, spaceChildrenStore.state) { roomList, children ->
                    val matchingChildren = children.takeIf { state ->
                        state.target?.userId == target.userId &&
                            state.target.spaceId == target.spaceId &&
                            state.target.parentSpaceId == target.parentSpaceId
                    }
                    SpaceAddRoomsSource(
                        rooms = roomList.rooms,
                        childRoomIds = matchingChildren
                            ?.let { state -> state.tracks + state.chats }
                            .orEmpty()
                            .mapTo(hashSetOf(), MatrixSpaceRoom::roomId),
                        canManage = matchingChildren?.management?.canManage == true
                    )
                }
            },
            loadCanManage = matrixClientService::canManageSpaceChildren,
            addChild = matrixSpaceService::addChildToSpace,
            confirmAdded = { target, rooms ->
                spaceChildrenStore.confirmChildrenAdded(
                    userId = target.userId,
                    spaceId = target.spaceId,
                    rooms = rooms
                )
            },
            refreshChildren = { target, roomIdsToFind ->
                spaceChildrenStore.refreshAfterChildMutation(
                    userId = target.userId,
                    spaceId = target.spaceId,
                    roomIdsToFind = roomIdsToFind
                )
            }
        ),
        onAdded = onAdded,
        onWarning = onWarning
    )
}
