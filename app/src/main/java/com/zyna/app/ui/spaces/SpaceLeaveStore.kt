package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixLeaveSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceLeaveSession
import com.zyna.app.data.matrix.MatrixSpaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpaceLeaveTarget(
    val userId: String,
    val spaceId: String,
    val parentSpaceId: String?,
    val displayName: String
)

enum class SpaceLeaveError {
    LOAD,
    LEAVE,
    PARTIAL_LEAVE
}

data class SelectableSpaceLeaveRoom(
    val value: MatrixLeaveSpaceRoom,
    val isSelected: Boolean
) {
    val isSelectable: Boolean
        get() = !value.needsOwnershipTransfer
}

data class SpaceLeaveState(
    val target: SpaceLeaveTarget? = null,
    val rooms: List<MatrixLeaveSpaceRoom> = emptyList(),
    val descendants: List<SelectableSpaceLeaveRoom> = emptyList(),
    val selectedRoomIds: Set<String> = emptySet(),
    val selectableRoomIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isLeaving: Boolean = false,
    val error: SpaceLeaveError? = null
) {
    val root: MatrixLeaveSpaceRoom?
        get() = target?.spaceId?.let { id -> rooms.firstOrNull { it.room.roomId == id } }

    val needsOwnerChange: Boolean
        get() = root?.needsOwnershipTransfer == true

    val areCreatorsPrivileged: Boolean
        get() = root?.areCreatorsPrivileged == true

    val areAllSelected: Boolean
        get() = selectableRoomIds.isNotEmpty() && selectableRoomIds.all(selectedRoomIds::contains)

    val canLeave: Boolean
        get() = !isLoading && !isLeaving && error == null &&
            root != null && !needsOwnerChange
}

internal class SpaceLeaveDriver(
    val open: suspend (userId: String, spaceId: String) -> MatrixSpaceLeaveSession
)

/** Owns one SDK leave handle, selection, and stale-route rejection for a Space graph leave. */
internal class SpaceLeaveStore(
    private val scope: CoroutineScope,
    private val driver: SpaceLeaveDriver,
    private val onLeft: (SpaceLeaveTarget, Set<String>) -> Unit,
    private val onPartiallyLeft: (SpaceLeaveTarget, Set<String>) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(SpaceLeaveState())
    val state: StateFlow<SpaceLeaveState> = _state.asStateFlow()

    private var generation = 0L
    private var session: MatrixSpaceLeaveSession? = null
    private var job: Job? = null

    @MainThread
    fun activate(target: SpaceLeaveTarget) {
        val normalized = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        if (_state.value.target == normalized && (job?.isActive == true || session != null)) return
        start(normalized)
    }

    @MainThread
    fun retry() {
        val target = _state.value.target ?: return
        if (_state.value.isLeaving) return
        start(target)
    }

    @MainThread
    fun toggle(roomId: String) {
        val current = _state.value
        if (
            current.isLoading ||
            current.isLeaving ||
            current.error != null ||
            roomId !in current.selectableRoomIds
        ) {
            return
        }
        _state.value = current.copy(
            selectedRoomIds = if (roomId in current.selectedRoomIds) {
                current.selectedRoomIds - roomId
            } else {
                current.selectedRoomIds + roomId
            },
            descendants = current.descendants.map { item ->
                if (item.value.room.roomId == roomId) {
                    item.copy(isSelected = !item.isSelected)
                } else {
                    item
                }
            }
        )
    }

    @MainThread
    fun toggleAll() {
        val current = _state.value
        if (
            current.isLoading ||
            current.isLeaving ||
            current.error != null ||
            current.selectableRoomIds.isEmpty()
        ) {
            return
        }
        val selectAll = !current.areAllSelected
        _state.value = current.copy(
            selectedRoomIds = if (selectAll) current.selectableRoomIds else emptySet(),
            descendants = current.descendants.map { item ->
                item.copy(isSelected = selectAll && item.isSelectable)
            }
        )
    }

    @MainThread
    fun leave() {
        val current = _state.value
        val target = current.target ?: return
        val activeSession = session ?: return
        if (!current.canLeave) return
        generation += 1L
        val requestGeneration = generation
        job?.cancel()
        _state.value = current.copy(isLeaving = true, error = null)
        val selectedIds = current.selectedRoomIds.intersect(current.selectableRoomIds)
        val nextJob = scope.launch {
            try {
                activeSession.leave(selectedIds.toList())
                if (!isCurrent(target, requestGeneration, activeSession)) return@launch
                val leftIds = selectedIds + target.spaceId
                _state.value = _state.value.copy(isLeaving = false)
                onLeft(target, leftIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(target, requestGeneration, activeSession)) {
                    onWarning("Failed to leave Space hierarchy", error)
                    if (!reconcileAfterWriteFailure(
                            target = target,
                            selectedRoomIds = selectedIds,
                            routeGeneration = requestGeneration,
                            failedSession = activeSession
                        )
                    ) {
                        _state.value = _state.value.copy(
                            isLeaving = false,
                            error = SpaceLeaveError.LEAVE
                        )
                    }
                }
            }
        }
        job = nextJob
        nextJob.invokeOnCompletion {
            if (job === nextJob) job = null
        }
    }

    @MainThread
    fun deactivate() {
        generation += 1L
        job?.cancel()
        job = null
        closeSession(session)
        session = null
        _state.value = SpaceLeaveState()
    }

    private fun start(target: SpaceLeaveTarget) {
        generation += 1L
        val requestGeneration = generation
        job?.cancel()
        closeSession(session)
        session = null
        _state.value = SpaceLeaveState(target = target, isLoading = true)
        val nextJob = scope.launch {
            var opened: MatrixSpaceLeaveSession? = null
            try {
                opened = driver.open(target.userId, target.spaceId)
                val rooms = opened.rooms()
                require(rooms.any { it.room.roomId == target.spaceId }) {
                    "Space leave graph does not contain its root"
                }
                if (!isCurrentTarget(target, requestGeneration)) return@launch
                session = opened
                opened = null
                val projection = rooms.toSelectionProjection(target)
                _state.value = _state.value.copy(
                    rooms = rooms,
                    descendants = projection.descendants,
                    selectedRoomIds = projection.selectedRoomIds,
                    selectableRoomIds = projection.selectableRoomIds,
                    isLoading = false,
                    error = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrentTarget(target, requestGeneration)) {
                    onWarning("Failed to prepare Space hierarchy leave", error)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = SpaceLeaveError.LOAD
                    )
                }
            } finally {
                opened?.let(::closeSession)
            }
        }
        job = nextJob
        nextJob.invokeOnCompletion {
            if (job === nextJob) job = null
        }
    }

    private fun isCurrent(
        target: SpaceLeaveTarget,
        requestGeneration: Long,
        owner: MatrixSpaceLeaveSession
    ): Boolean {
        return isCurrentTarget(target, requestGeneration) && session === owner
    }

    private suspend fun reconcileAfterWriteFailure(
        target: SpaceLeaveTarget,
        selectedRoomIds: Set<String>,
        routeGeneration: Long,
        failedSession: MatrixSpaceLeaveSession
    ): Boolean {
        if (!isCurrent(target, routeGeneration, failedSession)) return true
        var refreshedSession: MatrixSpaceLeaveSession? = null
        return try {
            refreshedSession = driver.open(target.userId, target.spaceId)
            val refreshedRooms = refreshedSession.rooms()
            if (!isCurrent(target, routeGeneration, failedSession)) return true
            val remainingIds = refreshedRooms.mapTo(HashSet(refreshedRooms.size)) {
                it.room.roomId
            }
            val requestedIds = selectedRoomIds + target.spaceId
            val completedIds = requestedIds - remainingIds
            if (target.spaceId !in remainingIds) {
                closeSession(refreshedSession)
                refreshedSession = null
                _state.value = _state.value.copy(isLeaving = false)
                onLeft(target, requestedIds)
                true
            } else {
                val projection = refreshedRooms.toSelectionProjection(
                    target = target,
                    requestedSelection = selectedRoomIds
                )
                session = refreshedSession
                refreshedSession = null
                closeSession(failedSession)
                if (completedIds.isNotEmpty()) {
                    onPartiallyLeft(target, completedIds)
                }
                _state.value = _state.value.copy(
                    rooms = refreshedRooms,
                    descendants = projection.descendants,
                    selectedRoomIds = projection.selectedRoomIds,
                    selectableRoomIds = projection.selectableRoomIds,
                    isLeaving = false,
                    error = if (completedIds.isEmpty()) {
                        SpaceLeaveError.LEAVE
                    } else {
                        SpaceLeaveError.PARTIAL_LEAVE
                    }
                )
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        } finally {
            refreshedSession?.let(::closeSession)
        }
    }

    private fun isCurrentTarget(target: SpaceLeaveTarget, requestGeneration: Long): Boolean {
        return generation == requestGeneration && _state.value.target == target
    }

    private fun closeSession(value: MatrixSpaceLeaveSession?) {
        value ?: return
        scope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { value.close() }
        }
    }
}

private fun SpaceLeaveTarget.normalizedOrNull(): SpaceLeaveTarget? {
    val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedSpaceId = spaceId.trim().takeIf(String::isNotEmpty) ?: return null
    return copy(
        userId = normalizedUserId,
        spaceId = normalizedSpaceId,
        parentSpaceId = parentSpaceId?.trim()?.takeIf(String::isNotEmpty),
        displayName = displayName.trim().ifBlank { normalizedSpaceId }
    )
}

internal fun createSpaceLeaveStore(
    scope: CoroutineScope,
    matrixSpaceService: MatrixSpaceService,
    onLeft: (SpaceLeaveTarget, Set<String>) -> Unit,
    onPartiallyLeft: (SpaceLeaveTarget, Set<String>) -> Unit,
    onWarning: (String, Throwable) -> Unit
): SpaceLeaveStore {
    return SpaceLeaveStore(
        scope = scope,
        driver = SpaceLeaveDriver(matrixSpaceService::openLeaveSession),
        onLeft = onLeft,
        onPartiallyLeft = onPartiallyLeft,
        onWarning = onWarning
    )
}

private data class SpaceLeaveSelectionProjection(
    val descendants: List<SelectableSpaceLeaveRoom>,
    val selectedRoomIds: Set<String>,
    val selectableRoomIds: Set<String>
)

private fun List<MatrixLeaveSpaceRoom>.toSelectionProjection(
    target: SpaceLeaveTarget,
    requestedSelection: Set<String>? = null
): SpaceLeaveSelectionProjection {
    val leaveableRooms = asSequence()
        .filterNot { it.room.roomId == target.spaceId || it.room.isDm == true }
        .toList()
    val selectableRoomIds = leaveableRooms.asSequence()
        .filterNot(MatrixLeaveSpaceRoom::needsOwnershipTransfer)
        .mapTo(linkedSetOf()) { it.room.roomId }
    val selectedRoomIds = requestedSelection
        ?.intersect(selectableRoomIds)
        ?: selectableRoomIds
    return SpaceLeaveSelectionProjection(
        descendants = leaveableRooms.map { room ->
            SelectableSpaceLeaveRoom(
                value = room,
                isSelected = room.room.roomId in selectedRoomIds
            )
        },
        selectedRoomIds = selectedRoomIds,
        selectableRoomIds = selectableRoomIds
    )
}
