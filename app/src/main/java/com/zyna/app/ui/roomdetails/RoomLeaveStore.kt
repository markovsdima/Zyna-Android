package com.zyna.app.ui.roomdetails

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomLeaveContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomLeaveTarget(
    val userId: String,
    val roomId: String,
    val displayName: String,
    val kind: MatrixRoomKind,
    val access: MatrixRoomAccess?
)

enum class RoomLeaveError {
    PREPARE,
    LEAVE
}

data class PendingRoomLeave(
    val context: MatrixRoomLeaveContext
)

data class RoomLeaveState(
    val target: RoomLeaveTarget? = null,
    val pendingConfirmation: PendingRoomLeave? = null,
    val isPreparing: Boolean = false,
    val isLeaving: Boolean = false,
    val error: RoomLeaveError? = null
) {
    val isBusy: Boolean
        get() = isPreparing || isLeaving
}

internal class RoomLeaveDriver(
    val loadContext: suspend (
        userId: String,
        roomId: String,
        useCachedMembers: Boolean
    ) -> MatrixRoomLeaveContext,
    val leave: suspend (userId: String, roomId: String) -> Unit,
    val isLeft: suspend (userId: String, roomId: String) -> Boolean
)

/** Command owner for leaving one ordinary room; Space graph leaves have a dedicated store. */
internal class RoomLeaveStore(
    private val scope: CoroutineScope,
    private val driver: RoomLeaveDriver,
    private val onLeft: (RoomLeaveTarget) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomLeaveState())
    val state: StateFlow<RoomLeaveState> = _state.asStateFlow()

    private var routeGeneration = 0L
    private var actionGeneration = 0L
    private var job: Job? = null

    @MainThread
    fun activate(target: RoomLeaveTarget) {
        val normalized = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        val current = _state.value
        if (current.target?.sameIdentity(normalized) == true) {
            if (current.target != normalized && !current.isBusy) {
                _state.value = current.copy(target = normalized)
            }
            return
        }
        routeGeneration += 1L
        actionGeneration += 1L
        job?.cancel()
        job = null
        _state.value = RoomLeaveState(target = normalized)
    }

    @MainThread
    fun request() {
        val current = _state.value
        val target = current.target ?: return
        if (target.kind == MatrixRoomKind.SPACE || current.isBusy) return
        actionGeneration += 1L
        val requestActionGeneration = actionGeneration
        val requestRouteGeneration = routeGeneration
        job?.cancel()
        _state.value = current.copy(
            pendingConfirmation = null,
            isPreparing = true,
            error = null
        )
        launch(target, requestRouteGeneration, requestActionGeneration) {
            val context = driver.loadContext(
                target.userId,
                target.roomId,
                true
            )
            if (!isCurrent(target, requestRouteGeneration, requestActionGeneration)) return@launch
            _state.value = _state.value.copy(
                pendingConfirmation = PendingRoomLeave(context),
                isPreparing = false,
                error = null
            )
        }
    }

    @MainThread
    fun cancelConfirmation() {
        val current = _state.value
        if (current.isBusy || current.pendingConfirmation == null) return
        _state.value = current.copy(pendingConfirmation = null)
    }

    @MainThread
    fun confirm() {
        val current = _state.value
        val target = current.target ?: return
        val pending = current.pendingConfirmation ?: return
        if (current.isBusy) return
        actionGeneration += 1L
        val requestActionGeneration = actionGeneration
        val requestRouteGeneration = routeGeneration
        job?.cancel()
        _state.value = current.copy(
            pendingConfirmation = null,
            isLeaving = true,
            error = null
        )
        launch(target, requestRouteGeneration, requestActionGeneration) {
            val fresh = driver.loadContext(
                target.userId,
                target.roomId,
                false
            )
            if (!isCurrent(target, requestRouteGeneration, requestActionGeneration)) return@launch
            if (fresh.needsOwnershipWarning && !pending.context.needsOwnershipWarning) {
                _state.value = _state.value.copy(
                    pendingConfirmation = PendingRoomLeave(fresh),
                    isLeaving = false
                )
                return@launch
            }
            try {
                driver.leave(target.userId, target.roomId)
            } catch (error: CancellationException) {
                throw error
            } catch (writeError: Throwable) {
                val didLeave = try {
                    driver.isLeft(target.userId, target.roomId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                if (!isCurrent(target, requestRouteGeneration, requestActionGeneration)) {
                    return@launch
                }
                if (!didLeave) throw writeError
            }
            if (!isCurrent(target, requestRouteGeneration, requestActionGeneration)) return@launch
            _state.value = _state.value.copy(isLeaving = false)
            onLeft(target)
        }
    }

    @MainThread
    fun deactivate() {
        routeGeneration += 1L
        actionGeneration += 1L
        job?.cancel()
        job = null
        _state.value = RoomLeaveState()
    }

    private fun launch(
        target: RoomLeaveTarget,
        requestRouteGeneration: Long,
        requestActionGeneration: Long,
        block: suspend CoroutineScope.() -> Unit
    ) {
        val nextJob = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(target, requestRouteGeneration, requestActionGeneration)) {
                    val wasLeaving = _state.value.isLeaving
                    onWarning(
                        if (wasLeaving) "Failed to leave Matrix room" else
                            "Failed to prepare Matrix room leave",
                        error
                    )
                    _state.value = _state.value.copy(
                        isPreparing = false,
                        isLeaving = false,
                        error = if (wasLeaving) RoomLeaveError.LEAVE else RoomLeaveError.PREPARE
                    )
                }
            }
        }
        job = nextJob
        nextJob.invokeOnCompletion {
            if (job === nextJob) job = null
        }
    }

    private fun isCurrent(
        target: RoomLeaveTarget,
        requestRouteGeneration: Long,
        requestActionGeneration: Long
    ): Boolean {
        return routeGeneration == requestRouteGeneration &&
            actionGeneration == requestActionGeneration &&
            _state.value.target?.sameIdentity(target) == true
    }
}

private fun RoomLeaveTarget.normalizedOrNull(): RoomLeaveTarget? {
    val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty) ?: return null
    return copy(
        userId = normalizedUserId,
        roomId = normalizedRoomId,
        displayName = displayName.trim().ifBlank { normalizedRoomId }
    )
}

private fun RoomLeaveTarget.sameIdentity(other: RoomLeaveTarget): Boolean {
    return userId == other.userId && roomId == other.roomId
}

internal fun createRoomLeaveStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onLeft: (RoomLeaveTarget) -> Unit,
    onWarning: (String, Throwable) -> Unit
): RoomLeaveStore {
    return RoomLeaveStore(
        scope = scope,
        driver = RoomLeaveDriver(
            loadContext = matrixClientService::loadRoomLeaveContext,
            leave = matrixClientService::leaveRoom,
            isLeft = matrixClientService::isRoomLeft
        ),
        onLeft = onLeft,
        onWarning = onWarning
    )
}
