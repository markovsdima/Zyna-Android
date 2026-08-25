package com.zyna.app.ui.roompermissions

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomPermission
import com.zyna.app.data.matrix.MatrixRoomPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomPermissionsTarget(
    val userId: String,
    val roomId: String
)

enum class RoomPermissionAudience(val powerLevel: Long) {
    EVERYONE(0),
    MODERATORS(50),
    ADMINISTRATORS(100);

    companion object {
        fun fromPowerLevel(powerLevel: Long): RoomPermissionAudience {
            return when {
                powerLevel >= ADMINISTRATORS.powerLevel -> ADMINISTRATORS
                powerLevel > EVERYONE.powerLevel -> MODERATORS
                else -> EVERYONE
            }
        }
    }
}

enum class RoomPermissionsError {
    LOAD,
    SAVE,
    PERMISSION_CHANGED
}

data class RoomPermissionsState(
    val target: RoomPermissionsTarget? = null,
    val permissions: MatrixRoomPermissions? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSaving: Boolean = false,
    val error: RoomPermissionsError? = null
)

internal class RoomPermissionsDriver(
    val observePermissions: (roomId: String) -> Flow<MatrixRoomPermissions>,
    val loadPermissions: suspend (roomId: String) -> MatrixRoomPermissions,
    val updatePermission: suspend (
        roomId: String,
        permission: MatrixRoomPermission,
        level: Long
    ) -> Unit,
    val delayMillis: suspend (Long) -> Unit
)

/**
 * Owns the roles-and-permissions route and applies one narrow Matrix power-level patch at a time.
 *
 * Every mutation is preceded by a fresh capability check. Confirmed values are retained until a
 * live SDK snapshot catches up, preventing the UI from snapping back to stale local state without
 * hiding unrelated remote changes. Target and session identity guard every suspended result.
 */
internal class RoomPermissionsStore(
    private val scope: CoroutineScope,
    private val driver: RoomPermissionsDriver,
    maxCachedRooms: Int = ROOM_PERMISSIONS_MAX_CACHED_ROOMS,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomPermissionsState())
    val state: StateFlow<RoomPermissionsState> = _state.asStateFlow()

    private val snapshotCache = RoomPermissionsSnapshotCache(maxCachedRooms)
    private val confirmedOverrides = mutableMapOf<MatrixRoomPermission, Long>()
    private var generation = 0L
    private var saveGeneration = 0L
    private var observationJob: Job? = null
    private var saveJob: Job? = null
    private var confirmationJob: Job? = null

    @MainThread
    fun activate(target: RoomPermissionsTarget) {
        val normalizedTarget = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        if (
            _state.value.target == normalizedTarget &&
            observationJob?.isActive == true
        ) {
            return
        }
        startObservation(normalizedTarget, snapshotCache[normalizedTarget])
    }

    @MainThread
    fun retry() {
        val target = _state.value.target ?: return
        confirmedOverrides.clear()
        startObservation(target, _state.value.permissions)
    }

    @MainThread
    fun setPermission(
        permission: MatrixRoomPermission,
        audience: RoomPermissionAudience
    ) {
        val current = _state.value
        val target = current.target ?: return
        val permissions = current.permissions ?: return
        if (
            !permissions.canEdit(permission) ||
            !permissions.canSet(audience.powerLevel) ||
            current.isSaving
        ) {
            return
        }
        val currentLevel = permissions.level(permission) ?: return
        if (RoomPermissionAudience.fromPowerLevel(currentLevel) == audience) return

        saveGeneration += 1
        val requestSaveGeneration = saveGeneration
        val requestGeneration = generation
        confirmationJob?.cancel()
        confirmationJob = null
        saveJob?.cancel()
        _state.value = current.copy(isSaving = true, error = null)
        val nextJob = scope.launch {
            try {
                val fresh = driver.loadPermissions(target.roomId)
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                if (
                    !fresh.canEdit(permission) ||
                    !fresh.canSet(audience.powerLevel)
                ) {
                    publishPermissions(target, fresh)
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = RoomPermissionsError.PERMISSION_CHANGED
                    )
                    return@launch
                }
                val freshLevel = fresh.level(permission) ?: error("Permission is unavailable")
                if (RoomPermissionAudience.fromPowerLevel(freshLevel) == audience) {
                    publishPermissions(target, fresh)
                    _state.value = _state.value.copy(isSaving = false)
                    return@launch
                }

                driver.updatePermission(target.roomId, permission, audience.powerLevel)
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }

                confirmedOverrides[permission] = audience.powerLevel
                val confirmed = overlayConfirmedValues(
                    fresh.copy(
                        levels = fresh.levels + (permission to audience.powerLevel)
                    )
                )
                snapshotCache.put(target, confirmed)
                _state.value = _state.value.copy(
                    permissions = confirmed,
                    isLoading = false,
                    isRefreshing = false,
                    isSaving = false,
                    error = null
                )
                scheduleConfirmationRecheck(
                    target = target,
                    requestGeneration = requestGeneration,
                    requestSaveGeneration = requestSaveGeneration
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                onWarning("Failed to update room permission", error)
                val refreshed = try {
                    driver.loadPermissions(target.roomId)
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (refreshError: Throwable) {
                    onWarning("Failed to refresh room permissions after save error", refreshError)
                    null
                }
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                refreshed?.let { publishPermissions(target, it) }
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = if (
                        refreshed != null &&
                        (
                            !refreshed.canEdit(permission) ||
                                !refreshed.canSet(audience.powerLevel)
                        )
                    ) {
                        RoomPermissionsError.PERMISSION_CHANGED
                    } else {
                        RoomPermissionsError.SAVE
                    }
                )
            }
        }
        saveJob = nextJob
        nextJob.invokeOnCompletion {
            if (saveJob === nextJob) saveJob = null
        }
    }

    @MainThread
    fun deactivate() {
        if (_state.value.target == null && observationJob == null && saveJob == null) return
        generation += 1
        saveGeneration += 1
        observationJob?.cancel()
        observationJob = null
        saveJob?.cancel()
        saveJob = null
        confirmationJob?.cancel()
        confirmationJob = null
        confirmedOverrides.clear()
        _state.value = RoomPermissionsState()
    }

    @MainThread
    fun clearSession() {
        deactivate()
        snapshotCache.clear()
    }

    private fun startObservation(
        target: RoomPermissionsTarget,
        retained: MatrixRoomPermissions?
    ) {
        generation += 1
        val requestGeneration = generation
        saveGeneration += 1
        observationJob?.cancel()
        saveJob?.cancel()
        saveJob = null
        confirmationJob?.cancel()
        confirmationJob = null
        confirmedOverrides.clear()
        _state.value = RoomPermissionsState(
            target = target,
            permissions = retained,
            isLoading = retained == null,
            isRefreshing = retained != null
        )

        val nextJob = scope.launch {
            try {
                driver.observePermissions(target.roomId).collect { permissions ->
                    if (!isCurrentTarget(target, requestGeneration)) return@collect
                    publishPermissions(target, permissions)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentTarget(target, requestGeneration)) return@launch
                onWarning("Failed to observe room permissions", error)
                _state.value = _state.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = RoomPermissionsError.LOAD
                )
            }
        }
        observationJob = nextJob
        nextJob.invokeOnCompletion {
            if (observationJob === nextJob) observationJob = null
        }
    }

    private fun publishPermissions(
        target: RoomPermissionsTarget,
        raw: MatrixRoomPermissions
    ) {
        val confirmedIterator = confirmedOverrides.iterator()
        while (confirmedIterator.hasNext()) {
            val (permission, level) = confirmedIterator.next()
            if (raw.level(permission) == level) {
                confirmedIterator.remove()
            }
        }
        val permissions = overlayConfirmedValues(raw)
        snapshotCache.put(target, permissions)
        _state.value = _state.value.copy(permissions = permissions)
        if (confirmedOverrides.isEmpty()) {
            confirmationJob?.cancel()
            confirmationJob = null
        }
    }

    private fun scheduleConfirmationRecheck(
        target: RoomPermissionsTarget,
        requestGeneration: Long,
        requestSaveGeneration: Long
    ) {
        confirmationJob?.cancel()
        val nextJob = scope.launch {
            try {
                driver.delayMillis(ROOM_PERMISSIONS_CONFIRMATION_RECHECK_DELAY_MS)
                val refreshed = driver.loadPermissions(target.roomId)
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                confirmedOverrides.clear()
                publishPermissions(target, refreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                onWarning("Failed to confirm updated room permissions", error)
                confirmedOverrides.clear()
            }
        }
        confirmationJob = nextJob
        nextJob.invokeOnCompletion {
            if (confirmationJob === nextJob) confirmationJob = null
        }
    }

    private fun overlayConfirmedValues(
        permissions: MatrixRoomPermissions
    ): MatrixRoomPermissions {
        if (confirmedOverrides.isEmpty()) return permissions
        return permissions.copy(levels = permissions.levels + confirmedOverrides)
    }

    private fun isCurrent(
        target: RoomPermissionsTarget,
        requestGeneration: Long,
        requestSaveGeneration: Long
    ): Boolean {
        return isCurrentTarget(target, requestGeneration) &&
            saveGeneration == requestSaveGeneration
    }

    private fun isCurrentTarget(
        target: RoomPermissionsTarget,
        requestGeneration: Long
    ): Boolean {
        return generation == requestGeneration && _state.value.target == target
    }

    private fun RoomPermissionsTarget.normalizedOrNull(): RoomPermissionsTarget? {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedRoomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        return RoomPermissionsTarget(normalizedUserId, normalizedRoomId)
    }
}

internal fun createRoomPermissionsStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onWarning: (String, Throwable) -> Unit
): RoomPermissionsStore {
    return RoomPermissionsStore(
        scope = scope,
        driver = RoomPermissionsDriver(
            observePermissions = matrixClientService::roomPermissionsUpdates,
            loadPermissions = matrixClientService::loadRoomPermissions,
            updatePermission = matrixClientService::updateRoomPermission,
            delayMillis = { durationMillis -> delay(durationMillis) }
        ),
        onWarning = onWarning
    )
}

private class RoomPermissionsSnapshotCache(
    private val maxRoomCount: Int
) {
    private val snapshots = LinkedHashMap<RoomPermissionsTarget, MatrixRoomPermissions>(
        maxRoomCount.coerceAtLeast(1),
        0.75f,
        true
    )

    operator fun get(target: RoomPermissionsTarget): MatrixRoomPermissions? = snapshots[target]

    fun put(target: RoomPermissionsTarget, permissions: MatrixRoomPermissions) {
        if (maxRoomCount <= 0) return
        snapshots[target] = permissions
        while (snapshots.size > maxRoomCount) {
            val iterator = snapshots.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun clear() {
        snapshots.clear()
    }
}

private const val ROOM_PERMISSIONS_MAX_CACHED_ROOMS = 8
private const val ROOM_PERMISSIONS_CONFIRMATION_RECHECK_DELAY_MS = 1_000L
