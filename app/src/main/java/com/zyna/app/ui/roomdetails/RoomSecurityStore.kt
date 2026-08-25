package com.zyna.app.ui.roomdetails

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomAliasAvailability
import com.zyna.app.data.matrix.MatrixRoomDirectoryVisibility
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomSecurityJoinRule
import com.zyna.app.data.matrix.MatrixRoomSecurityPermissions
import com.zyna.app.data.matrix.MatrixRoomSecuritySnapshot
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceService
import com.zyna.app.data.matrix.withCanonicalMatrixServerName
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomSecurityTarget(
    val userId: String,
    val roomId: String,
    val displayName: String
)

data class RoomSecurityParentSpace(
    val roomId: String,
    val displayName: String
)

enum class RoomSecurityAccessOption {
    INVITE_ONLY,
    PARENT_SPACE_MEMBERS,
    PUBLIC
}

enum class RoomSecurityAddressAvailability {
    UNCHANGED,
    CHECKING,
    AVAILABLE,
    OWNED_BY_ROOM,
    REMOVAL_READY,
    TAKEN,
    INVALID,
    ERROR
}

enum class RoomSecurityError {
    LOAD,
    ADDRESS_CHECK,
    PERMISSION_CHANGED,
    REMOTE_CHANGED,
    SAVE,
    PARTIAL_SAVE
}

data class RoomSecurityState(
    val target: RoomSecurityTarget? = null,
    val access: RoomSecurityAccessOption? = null,
    val editAccess: RoomSecurityAccessOption? = null,
    val authorizedSpaceIds: Set<String> = emptySet(),
    val editAuthorizedSpaceIds: Set<String> = emptySet(),
    val parentSpaces: List<RoomSecurityParentSpace> = emptyList(),
    val historyVisibility: MatrixRoomHistoryVisibility? = null,
    val editHistoryVisibility: MatrixRoomHistoryVisibility? = null,
    val isEncrypted: Boolean? = null,
    val editIsEncrypted: Boolean = false,
    val displayedAddress: String? = null,
    val localAddress: String? = null,
    val editAddressLocalPart: String = "",
    val serverName: String = "",
    val isVisibleInDirectory: Boolean? = null,
    val editIsVisibleInDirectory: Boolean = false,
    val canChangeAccess: Boolean = false,
    val canChangeHistoryVisibility: Boolean = false,
    val canEnableEncryption: Boolean = false,
    val canChangeAddress: Boolean = false,
    val canChangeDirectoryVisibility: Boolean = false,
    val addressAvailability: RoomSecurityAddressAvailability =
        RoomSecurityAddressAvailability.UNCHANGED,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: RoomSecurityError? = null,
    val editSessionId: Long = 0L,
    val isDiscardConfirmationVisible: Boolean = false,
    val isEncryptionConfirmationVisible: Boolean = false
) {
    val isJoinRuleSupported: Boolean
        get() = access != null && editAccess != null

    val isHistoryVisibilitySupported: Boolean
        get() = historyVisibility != null && editHistoryVisibility != null

    val isDirectoryVisibilitySupported: Boolean
        get() = isVisibleInDirectory != null

    val selectableSpaceIds: Set<String>
        get() = parentSpaces.mapTo(linkedSetOf()) { it.roomId } +
            authorizedSpaceIds + editAuthorizedSpaceIds

    val editFullAddress: String?
        get() = editAddressLocalPart.takeIf(String::isNotBlank)?.let { localPart ->
            serverName.takeIf(String::isNotBlank)?.let { server -> "#$localPart:$server" }
        }

    val hasAccessChange: Boolean
        get() = isJoinRuleSupported && (
            editAccess != access ||
                editAccess == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS &&
                editAuthorizedSpaceIds != authorizedSpaceIds
            )

    val hasHistoryChange: Boolean
        get() = isHistoryVisibilitySupported && editHistoryVisibility != historyVisibility

    val hasEncryptionChange: Boolean
        get() = isEncrypted == false && editIsEncrypted

    val hasAddressChange: Boolean
        get() = editFullAddress != localAddress

    val hasDirectoryChange: Boolean
        get() = isVisibleInDirectory?.let { it != editIsVisibleInDirectory } == true

    val hasUnsavedChanges: Boolean
        get() = editSessionId != 0L && (
            hasAccessChange ||
                hasHistoryChange ||
                hasEncryptionChange ||
                hasAddressChange ||
                hasDirectoryChange
            )

    val hasUsableLocalAddress: Boolean
        get() = when {
            hasAddressChange -> editFullAddress != null && addressAvailability in setOf(
                RoomSecurityAddressAvailability.AVAILABLE,
                RoomSecurityAddressAvailability.OWNED_BY_ROOM
            )
            else -> !localAddress.isNullOrBlank()
        }

    val hasInvalidWorldReadableCombination: Boolean
        get() = editHistoryVisibility == MatrixRoomHistoryVisibility.WORLD_READABLE &&
            (editAccess != RoomSecurityAccessOption.PUBLIC || editIsEncrypted) &&
            (hasAccessChange || hasHistoryChange || hasEncryptionChange)

    val canSave: Boolean
        get() {
            if (isLoading || isSaving || !hasUnsavedChanges) return false
            if (hasAccessChange) {
                if (!canChangeAccess) return false
                if (
                    editAccess == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS &&
                    editAuthorizedSpaceIds.isEmpty()
                ) {
                    return false
                }
            }
            if (hasHistoryChange && !canChangeHistoryVisibility) return false
            if (hasEncryptionChange && !canEnableEncryption) return false
            if (hasAddressChange) {
                if (!canChangeAddress) return false
                if (editFullAddress == null) {
                    if (addressAvailability != RoomSecurityAddressAvailability.REMOVAL_READY) {
                        return false
                    }
                } else if (!hasUsableLocalAddress) {
                    return false
                }
            }
            if (hasDirectoryChange && !canChangeDirectoryVisibility) return false
            if (hasDirectoryChange && editIsVisibleInDirectory) {
                if (editAccess != RoomSecurityAccessOption.PUBLIC || !hasUsableLocalAddress) {
                    return false
                }
            }
            if (hasInvalidWorldReadableCombination) return false
            return true
        }
}

internal class RoomSecurityDriver(
    val load: suspend (userId: String, roomId: String) -> MatrixRoomSecuritySnapshot,
    val loadParentSpaces: suspend (userId: String, roomId: String) -> List<MatrixSpaceRoom>,
    val observePermissions: (
        userId: String,
        roomId: String
    ) -> Flow<MatrixRoomSecurityPermissions>,
    val isAliasValid: (String) -> Boolean,
    val checkAlias: suspend (
        userId: String,
        roomId: String,
        alias: String
    ) -> MatrixRoomAliasAvailability,
    val setAddress: suspend (userId: String, roomId: String, alias: String?) -> Unit,
    val setJoinRule: suspend (
        userId: String,
        roomId: String,
        joinRule: MatrixRoomSecurityJoinRule
    ) -> Unit,
    val setHistoryVisibility: suspend (
        userId: String,
        roomId: String,
        visibility: MatrixRoomHistoryVisibility
    ) -> Unit,
    val enableEncryption: suspend (userId: String, roomId: String) -> Unit,
    val setDirectoryVisibility: suspend (
        userId: String,
        roomId: String,
        isVisible: Boolean
    ) -> Unit
)

/**
 * Owns one route-scoped room-security edit session.
 *
 * Matrix has no transaction spanning these settings. Writes are ordered to close public exposure
 * first and open it last, while every completed field becomes the retry baseline. Both preflight
 * and post-failure reconciliation protect remote edits and SDK calls that fail after applying.
 */
internal class RoomSecurityStore(
    private val scope: CoroutineScope,
    private val driver: RoomSecurityDriver,
    private val onFinished: (target: RoomSecurityTarget, didSave: Boolean) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> },
    private val aliasCheckDebounceMillis: Long = ROOM_ALIAS_CHECK_DEBOUNCE_MILLIS
) {
    private val _state = MutableStateFlow(RoomSecurityState())
    val state: StateFlow<RoomSecurityState> = _state.asStateFlow()

    private var generation = 0L
    private var aliasGeneration = 0L
    private var editSessionCounter = 0L
    private var loadJob: Job? = null
    private var permissionJob: Job? = null
    private var saveJob: Job? = null
    private var aliasJob: Job? = null

    @MainThread
    fun activate(target: RoomSecurityTarget) {
        val normalized = target.normalizedOrNull() ?: return
        if (_state.value.target == normalized && _state.value.editSessionId != 0L) return
        cancelAll()
        editSessionCounter += 1L
        val sessionId = editSessionCounter
        _state.value = RoomSecurityState(
            target = normalized,
            isLoading = true,
            editSessionId = sessionId
        )
        observePermissions(normalized, sessionId)
        load(normalized, sessionId)
    }

    @MainThread
    fun deactivate() {
        cancelAll()
        _state.value = RoomSecurityState()
    }

    @MainThread
    fun retry() {
        val current = _state.value
        val target = current.target ?: return
        if (current.isSaving || current.editSessionId == 0L) return
        loadJob?.cancel()
        _state.value = current.copy(isLoading = true, error = null)
        load(target, current.editSessionId)
    }

    @MainThread
    fun setAccess(access: RoomSecurityAccessOption) {
        val current = editableStateOrNull() ?: return
        if (!current.isJoinRuleSupported || !current.canChangeAccess) return
        if (
            access == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS &&
            current.selectableSpaceIds.isEmpty()
        ) {
            return
        }
        val selectedSpaces = when {
            access != RoomSecurityAccessOption.PARENT_SPACE_MEMBERS ->
                current.editAuthorizedSpaceIds
            current.editAuthorizedSpaceIds.isNotEmpty() -> current.editAuthorizedSpaceIds
            current.authorizedSpaceIds.isNotEmpty() -> current.authorizedSpaceIds
            current.selectableSpaceIds.size == 1 -> current.selectableSpaceIds
            else -> emptySet()
        }
        _state.value = current.copy(
            editAccess = access,
            editAuthorizedSpaceIds = selectedSpaces,
            editIsVisibleInDirectory = if (access == RoomSecurityAccessOption.PUBLIC) {
                current.editIsVisibleInDirectory
            } else {
                false
            },
            error = null
        )
    }

    @MainThread
    fun toggleAuthorizedSpace(spaceId: String) {
        val current = editableStateOrNull() ?: return
        val normalizedId = spaceId.trim().takeIf(String::isNotEmpty) ?: return
        if (
            !current.isJoinRuleSupported ||
            !current.canChangeAccess ||
            current.editAccess != RoomSecurityAccessOption.PARENT_SPACE_MEMBERS ||
            normalizedId !in current.selectableSpaceIds
        ) {
            return
        }
        val nextIds = current.editAuthorizedSpaceIds.toMutableSet().apply {
            if (!add(normalizedId)) remove(normalizedId)
        }
        _state.value = current.copy(editAuthorizedSpaceIds = nextIds, error = null)
    }

    @MainThread
    fun setHistoryVisibility(visibility: MatrixRoomHistoryVisibility) {
        val current = editableStateOrNull() ?: return
        if (
            !current.isHistoryVisibilitySupported ||
            !current.canChangeHistoryVisibility ||
            visibility == MatrixRoomHistoryVisibility.CUSTOM
        ) {
            return
        }
        _state.value = current.copy(editHistoryVisibility = visibility, error = null)
    }

    @MainThread
    fun requestEncryptionChange(enabled: Boolean) {
        val current = editableStateOrNull() ?: return
        if (current.isEncrypted != false || !current.canEnableEncryption) return
        if (!enabled) {
            _state.value = current.copy(editIsEncrypted = false, error = null)
        } else if (!current.editIsEncrypted) {
            _state.value = current.copy(isEncryptionConfirmationVisible = true)
        }
    }

    @MainThread
    fun confirmEncryption() {
        val current = editableStateOrNull() ?: return
        if (
            !current.isEncryptionConfirmationVisible ||
            current.isEncrypted != false ||
            !current.canEnableEncryption
        ) {
            return
        }
        _state.value = current.copy(
            editIsEncrypted = true,
            editHistoryVisibility = if (
                current.editHistoryVisibility == MatrixRoomHistoryVisibility.WORLD_READABLE &&
                current.canChangeHistoryVisibility
            ) {
                MatrixRoomHistoryVisibility.INVITED
            } else {
                current.editHistoryVisibility
            },
            isEncryptionConfirmationVisible = false,
            error = null
        )
    }

    @MainThread
    fun cancelEncryptionConfirmation() {
        val current = _state.value
        if (!current.isEncryptionConfirmationVisible) return
        _state.value = current.copy(isEncryptionConfirmationVisible = false)
    }

    @MainThread
    fun setAddressLocalPart(value: String) {
        val current = editableStateOrNull() ?: return
        if (!current.canChangeAddress) return
        val next = current.copy(
            editAddressLocalPart = normalizeAliasInput(value, current.serverName),
            editIsVisibleInDirectory = if (value.isBlank()) {
                false
            } else {
                current.editIsVisibleInDirectory
            },
            error = null
        )
        publishAddressDraft(next)
    }

    @MainThread
    fun retryAddressCheck() {
        val current = editableStateOrNull() ?: return
        if (!current.hasAddressChange || !current.canChangeAddress) return
        publishAddressDraft(current.copy(error = null), debounce = false)
    }

    @MainThread
    fun setDirectoryVisibility(isVisible: Boolean) {
        val current = editableStateOrNull() ?: return
        if (
            !current.isDirectoryVisibilitySupported ||
            !current.canChangeDirectoryVisibility
        ) {
            return
        }
        if (
            isVisible &&
            (current.editAccess != RoomSecurityAccessOption.PUBLIC ||
                !current.hasUsableLocalAddress)
        ) {
            return
        }
        _state.value = current.copy(editIsVisibleInDirectory = isVisible, error = null)
    }

    @MainThread
    fun requestExit() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving) return
        if (current.hasUnsavedChanges) {
            _state.value = current.copy(isDiscardConfirmationVisible = true)
        } else {
            finish(current, didSave = false)
        }
    }

    @MainThread
    fun cancelDiscardConfirmation() {
        val current = _state.value
        if (!current.isDiscardConfirmationVisible) return
        _state.value = current.copy(isDiscardConfirmationVisible = false)
    }

    @MainThread
    fun confirmDiscard() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving) return
        finish(current, didSave = false)
    }

    @MainThread
    fun save() {
        val initial = _state.value
        val target = initial.target ?: return
        if (!initial.canSave) return
        val desired = DesiredRoomSecuritySettings.from(initial)
        val requestGeneration = beginSave()
        _state.value = initial.copy(
            isSaving = true,
            error = null,
            isDiscardConfirmationVisible = false,
            isEncryptionConfirmationVisible = false
        )

        val nextJob = scope.launch {
            var didSaveAnyField = false
            try {
                val fresh = loadSettings(target)
                if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                val preflight = reconcilePreflight(initial, fresh, desired)
                if (preflight == null) {
                    _state.value = fresh.toState(
                        target = target,
                        editSessionId = initial.editSessionId,
                        error = RoomSecurityError.REMOTE_CHANGED
                    )
                    return@launch
                }
                _state.value = preflight.copy(isSaving = true, error = null)
                if (!preflight.hasUnsavedChanges) {
                    finishAfterSave(target, initial.editSessionId, requestGeneration)
                    return@launch
                }
                if (!preflight.canSaveWhenSaving()) {
                    _state.value = preflight.copy(
                        isSaving = false,
                        error = RoomSecurityError.PERMISSION_CHANGED
                    )
                    return@launch
                }

                if (
                    _state.value.hasDirectoryChange &&
                    !_state.value.editIsVisibleInDirectory &&
                    _state.value.isVisibleInDirectory == true
                ) {
                    driver.setDirectoryVisibility(target.userId, target.roomId, false)
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markDirectorySaved(false)
                }

                if (_state.value.hasAddressChange) {
                    driver.setAddress(target.userId, target.roomId, desired.address)
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markAddressSaved(desired.address)
                }

                if (_state.value.hasAccessChange) {
                    val access = requireNotNull(desired.access)
                    driver.setJoinRule(
                        target.userId,
                        target.roomId,
                        access.toMatrixJoinRule(desired.authorizedSpaceIds)
                    )
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markAccessSaved(access, desired.authorizedSpaceIds)
                }

                if (_state.value.hasHistoryChange) {
                    val visibility = requireNotNull(desired.historyVisibility)
                    driver.setHistoryVisibility(target.userId, target.roomId, visibility)
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markHistorySaved(visibility)
                }

                if (_state.value.hasEncryptionChange) {
                    driver.enableEncryption(target.userId, target.roomId)
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markEncryptionSaved()
                }

                if (_state.value.hasDirectoryChange) {
                    val isVisible = requireNotNull(desired.directoryVisibility)
                    driver.setDirectoryVisibility(target.userId, target.roomId, isVisible)
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markDirectorySaved(isVisible)
                }

                finishAfterSave(target, initial.editSessionId, requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                onWarning("Failed to save room security settings", error)
                val fresh = try {
                    loadSettings(target)
                } catch (refreshCancellation: CancellationException) {
                    throw refreshCancellation
                } catch (refreshError: Throwable) {
                    error.addSuppressed(refreshError)
                    null
                }
                if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                if (fresh == null) {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = if (didSaveAnyField) {
                            RoomSecurityError.PARTIAL_SAVE
                        } else {
                            RoomSecurityError.SAVE
                        }
                    )
                    return@launch
                }
                val recovered = reconcileAfterWriteFailure(_state.value, fresh, desired)
                if (!recovered.hasUnsavedChanges) {
                    onWarning(
                        "Room security write reported failure but the server confirmed it",
                        error
                    )
                    finishAfterSave(target, initial.editSessionId, requestGeneration)
                    return@launch
                }
                _state.value = recovered.copy(
                    isSaving = false,
                    error = if (didSaveAnyField || recovered.didRecoverAnyField(initial)) {
                        RoomSecurityError.PARTIAL_SAVE
                    } else {
                        RoomSecurityError.SAVE
                    }
                )
            }
        }
        saveJob = nextJob
        nextJob.invokeOnCompletion {
            if (saveJob === nextJob) saveJob = null
        }
    }

    private fun observePermissions(target: RoomSecurityTarget, editSessionId: Long) {
        permissionJob = scope.launch {
            try {
                driver.observePermissions(target.userId, target.roomId).collect { permissions ->
                    val current = _state.value
                    if (current.target != target || current.editSessionId != editSessionId) {
                        return@collect
                    }
                    val permissionChanged =
                        (current.hasAccessChange && !permissions.canChangeJoinRule) ||
                            (current.hasHistoryChange &&
                                !permissions.canChangeHistoryVisibility) ||
                            (current.hasEncryptionChange && !permissions.canEnableEncryption) ||
                            (current.hasAddressChange && !permissions.canChangeAddress) ||
                            (current.hasDirectoryChange &&
                                !permissions.canChangeDirectoryVisibility)
                    _state.value = current.withPermissions(permissions).copy(
                        error = if (permissionChanged) {
                            RoomSecurityError.PERMISSION_CHANGED
                        } else {
                            current.error.takeUnless {
                                it == RoomSecurityError.PERMISSION_CHANGED
                            }
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (_state.value.target == target) {
                    onWarning("Failed to observe room security permissions", error)
                }
            }
        }
    }

    private fun load(target: RoomSecurityTarget, editSessionId: Long) {
        loadJob = scope.launch {
            try {
                val loaded = loadSettings(target)
                val current = _state.value
                if (current.target != target || current.editSessionId != editSessionId) return@launch
                _state.value = loaded.toState(target, editSessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val current = _state.value
                if (current.target != target || current.editSessionId != editSessionId) return@launch
                onWarning("Failed to load room security settings", error)
                _state.value = current.copy(isLoading = false, error = RoomSecurityError.LOAD)
            }
        }
    }

    private suspend fun loadSettings(target: RoomSecurityTarget): LoadedRoomSecuritySettings {
        val snapshot = driver.load(target.userId, target.roomId)
        val parentSpaces = try {
            driver.loadParentSpaces(target.userId, target.roomId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onWarning("Failed to load parent Spaces for room security", error)
            emptyList()
        }
        return snapshot.toLoadedSettings(parentSpaces)
    }

    private fun publishAddressDraft(state: RoomSecurityState, debounce: Boolean = true) {
        cancelAliasCheck()
        val fullAddress = state.editFullAddress
        when {
            !state.hasAddressChange -> {
                _state.value = state.copy(
                    addressAvailability = RoomSecurityAddressAvailability.UNCHANGED
                )
            }
            fullAddress == null && state.localAddress != null -> {
                _state.value = state.copy(
                    addressAvailability = RoomSecurityAddressAvailability.REMOVAL_READY
                )
            }
            fullAddress == null || !driver.isAliasValid(fullAddress) -> {
                _state.value = state.copy(
                    addressAvailability = RoomSecurityAddressAvailability.INVALID
                )
            }
            else -> {
                val target = state.target ?: return
                val sessionId = state.editSessionId
                aliasGeneration += 1L
                val requestGeneration = aliasGeneration
                _state.value = state.copy(
                    addressAvailability = RoomSecurityAddressAvailability.CHECKING
                )
                aliasJob = scope.launch {
                    if (debounce) delay(aliasCheckDebounceMillis)
                    try {
                        val availability = driver.checkAlias(
                            target.userId,
                            target.roomId,
                            fullAddress
                        )
                        if (!isCurrentAlias(target, sessionId, fullAddress, requestGeneration)) {
                            return@launch
                        }
                        _state.value = _state.value.copy(
                            addressAvailability = when (availability) {
                                MatrixRoomAliasAvailability.AVAILABLE ->
                                    RoomSecurityAddressAvailability.AVAILABLE
                                MatrixRoomAliasAvailability.OWNED_BY_ROOM ->
                                    RoomSecurityAddressAvailability.OWNED_BY_ROOM
                                MatrixRoomAliasAvailability.TAKEN ->
                                    RoomSecurityAddressAvailability.TAKEN
                            },
                            error = null
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (!isCurrentAlias(target, sessionId, fullAddress, requestGeneration)) {
                            return@launch
                        }
                        onWarning("Failed to check room address", error)
                        _state.value = _state.value.copy(
                            addressAvailability = RoomSecurityAddressAvailability.ERROR,
                            error = RoomSecurityError.ADDRESS_CHECK
                        )
                    }
                }
            }
        }
    }

    private fun reconcilePreflight(
        initial: RoomSecurityState,
        fresh: LoadedRoomSecuritySettings,
        desired: DesiredRoomSecuritySettings
    ): RoomSecurityState? {
        val accessConflict = desired.access != null &&
            fresh.accessIdentity() != initial.accessIdentity() &&
            fresh.accessIdentity() != desired.accessIdentity()
        val historyConflict = desired.historyVisibility != null &&
            fresh.historyVisibility != initial.historyVisibility &&
            fresh.historyVisibility != desired.historyVisibility
        val encryptionConflict = desired.enableEncryption &&
            fresh.isEncrypted != initial.isEncrypted && fresh.isEncrypted != true
        val addressConflict = desired.hasAddressChange &&
            fresh.localAddress != initial.localAddress && fresh.localAddress != desired.address
        val directoryConflict = desired.directoryVisibility != null &&
            fresh.isVisibleInDirectory != initial.isVisibleInDirectory &&
            fresh.isVisibleInDirectory != desired.directoryVisibility
        if (
            accessConflict || historyConflict || encryptionConflict ||
            addressConflict || directoryConflict
        ) {
            return null
        }
        return fresh.toState(
            target = requireNotNull(initial.target),
            editSessionId = initial.editSessionId
        ).withDesired(desired, initial.addressAvailability).copy(isSaving = true)
    }

    private fun reconcileAfterWriteFailure(
        current: RoomSecurityState,
        fresh: LoadedRoomSecuritySettings,
        desired: DesiredRoomSecuritySettings
    ): RoomSecurityState {
        return fresh.toState(
            target = requireNotNull(current.target),
            editSessionId = current.editSessionId
        ).withDesired(desired, current.addressAvailability)
    }

    private fun markAccessSaved(
        access: RoomSecurityAccessOption,
        authorizedSpaceIds: Set<String>
    ) {
        val savedIds = if (access == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS) {
            authorizedSpaceIds
        } else {
            emptySet()
        }
        _state.value = _state.value.copy(
            access = access,
            editAccess = access,
            authorizedSpaceIds = savedIds,
            editAuthorizedSpaceIds = savedIds
        )
    }

    private fun markHistorySaved(visibility: MatrixRoomHistoryVisibility) {
        _state.value = _state.value.copy(
            historyVisibility = visibility,
            editHistoryVisibility = visibility
        )
    }

    private fun markEncryptionSaved() {
        _state.value = _state.value.copy(isEncrypted = true, editIsEncrypted = true)
    }

    private fun markAddressSaved(address: String?) {
        _state.value = _state.value.copy(
            displayedAddress = address,
            localAddress = address,
            editAddressLocalPart = address?.aliasLocalPart().orEmpty(),
            addressAvailability = RoomSecurityAddressAvailability.UNCHANGED
        )
    }

    private fun markDirectorySaved(isVisible: Boolean) {
        _state.value = _state.value.copy(
            isVisibleInDirectory = isVisible,
            editIsVisibleInDirectory = isVisible
        )
    }

    private fun finishAfterSave(
        target: RoomSecurityTarget,
        editSessionId: Long,
        requestGeneration: Long
    ) {
        if (!isCurrent(target, editSessionId, requestGeneration)) return
        cancelAliasCheck()
        _state.value = RoomSecurityState()
        onFinished(target, true)
    }

    private fun finish(current: RoomSecurityState, didSave: Boolean) {
        val target = current.target ?: return
        cancelAll()
        _state.value = RoomSecurityState()
        onFinished(target, didSave)
    }

    private fun beginSave(): Long {
        cancelSave()
        cancelAliasCheck()
        return generation
    }

    private fun cancelAll() {
        generation += 1L
        loadJob?.cancel()
        loadJob = null
        permissionJob?.cancel()
        permissionJob = null
        saveJob?.cancel()
        saveJob = null
        cancelAliasCheck()
    }

    private fun cancelSave() {
        generation += 1L
        saveJob?.cancel()
        saveJob = null
    }

    private fun cancelAliasCheck() {
        aliasGeneration += 1L
        aliasJob?.cancel()
        aliasJob = null
    }

    private fun editableStateOrNull(): RoomSecurityState? {
        return _state.value.takeIf {
            it.editSessionId != 0L && !it.isLoading && !it.isSaving
        }
    }

    private fun isCurrent(
        target: RoomSecurityTarget,
        editSessionId: Long,
        requestGeneration: Long
    ): Boolean {
        val current = _state.value
        return generation == requestGeneration &&
            current.target == target &&
            current.editSessionId == editSessionId
    }

    private fun isCurrentAlias(
        target: RoomSecurityTarget,
        editSessionId: Long,
        fullAddress: String,
        requestGeneration: Long
    ): Boolean {
        val current = _state.value
        return aliasGeneration == requestGeneration &&
            current.target == target &&
            current.editSessionId == editSessionId &&
            current.editFullAddress == fullAddress
    }
}

internal fun createRoomSecurityStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    matrixSpaceService: MatrixSpaceService,
    onFinished: (target: RoomSecurityTarget, didSave: Boolean) -> Unit,
    onWarning: (String, Throwable) -> Unit
): RoomSecurityStore {
    return RoomSecurityStore(
        scope = scope,
        driver = RoomSecurityDriver(
            load = matrixClientService::loadRoomSecurity,
            loadParentSpaces = matrixSpaceService::joinedParentsOfChild,
            observePermissions = matrixClientService::roomSecurityPermissionUpdates,
            isAliasValid = matrixClientService::isRoomAliasValid,
            checkAlias = matrixClientService::checkRoomAliasAvailability,
            setAddress = matrixClientService::setRoomSecurityAddress,
            setJoinRule = matrixClientService::setRoomSecurityJoinRule,
            setHistoryVisibility = matrixClientService::setRoomSecurityHistoryVisibility,
            enableEncryption = matrixClientService::enableRoomSecurityEncryption,
            setDirectoryVisibility = matrixClientService::setRoomSecurityDirectoryVisibility
        ),
        onFinished = onFinished,
        onWarning = onWarning
    )
}

private data class LoadedRoomSecuritySettings(
    val access: RoomSecurityAccessOption?,
    val authorizedSpaceIds: Set<String>,
    val parentSpaces: List<RoomSecurityParentSpace>,
    val historyVisibility: MatrixRoomHistoryVisibility?,
    val isEncrypted: Boolean?,
    val displayedAddress: String?,
    val localAddress: String?,
    val serverName: String,
    val isVisibleInDirectory: Boolean?,
    val permissions: MatrixRoomSecurityPermissions
) {
    fun toState(
        target: RoomSecurityTarget,
        editSessionId: Long,
        error: RoomSecurityError? = null
    ): RoomSecurityState {
        return RoomSecurityState(
            target = target,
            access = access,
            editAccess = access,
            authorizedSpaceIds = authorizedSpaceIds,
            editAuthorizedSpaceIds = authorizedSpaceIds,
            parentSpaces = parentSpaces,
            historyVisibility = historyVisibility,
            editHistoryVisibility = historyVisibility,
            isEncrypted = isEncrypted,
            editIsEncrypted = isEncrypted == true,
            displayedAddress = displayedAddress,
            localAddress = localAddress,
            editAddressLocalPart = localAddress?.aliasLocalPart().orEmpty(),
            serverName = serverName,
            isVisibleInDirectory = isVisibleInDirectory,
            editIsVisibleInDirectory = isVisibleInDirectory == true,
            canChangeAccess = permissions.canChangeJoinRule,
            canChangeHistoryVisibility = permissions.canChangeHistoryVisibility,
            canEnableEncryption = permissions.canEnableEncryption,
            canChangeAddress = permissions.canChangeAddress,
            canChangeDirectoryVisibility = permissions.canChangeDirectoryVisibility,
            isLoading = false,
            error = error,
            editSessionId = editSessionId
        )
    }

    fun accessIdentity(): Pair<RoomSecurityAccessOption?, Set<String>> {
        return access to if (access == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS) {
            authorizedSpaceIds
        } else {
            emptySet()
        }
    }
}

private data class DesiredRoomSecuritySettings(
    val access: RoomSecurityAccessOption?,
    val authorizedSpaceIds: Set<String>,
    val historyVisibility: MatrixRoomHistoryVisibility?,
    val enableEncryption: Boolean,
    val hasAddressChange: Boolean,
    val address: String?,
    val directoryVisibility: Boolean?
) {
    fun accessIdentity(): Pair<RoomSecurityAccessOption?, Set<String>> {
        return access to if (access == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS) {
            authorizedSpaceIds
        } else {
            emptySet()
        }
    }

    companion object {
        fun from(state: RoomSecurityState): DesiredRoomSecuritySettings {
            return DesiredRoomSecuritySettings(
                access = state.editAccess.takeIf { state.hasAccessChange },
                authorizedSpaceIds = state.editAuthorizedSpaceIds,
                historyVisibility = state.editHistoryVisibility.takeIf {
                    state.hasHistoryChange
                },
                enableEncryption = state.hasEncryptionChange,
                hasAddressChange = state.hasAddressChange,
                address = state.editFullAddress,
                directoryVisibility = state.editIsVisibleInDirectory.takeIf {
                    state.hasDirectoryChange
                }
            )
        }
    }
}

private fun MatrixRoomSecuritySnapshot.toLoadedSettings(
    loadedParentSpaces: List<MatrixSpaceRoom>
): LoadedRoomSecuritySettings {
    val mappedAccess: RoomSecurityAccessOption?
    val authorizedIds: Set<String>
    when (val rule = joinRule) {
        MatrixRoomSecurityJoinRule.InviteOnly -> {
            mappedAccess = RoomSecurityAccessOption.INVITE_ONLY
            authorizedIds = emptySet()
        }
        MatrixRoomSecurityJoinRule.Public -> {
            mappedAccess = RoomSecurityAccessOption.PUBLIC
            authorizedIds = emptySet()
        }
        is MatrixRoomSecurityJoinRule.Restricted -> {
            mappedAccess = if (!rule.hasUnsupportedRules && rule.spaceIds.isNotEmpty()) {
                RoomSecurityAccessOption.PARENT_SPACE_MEMBERS
            } else {
                null
            }
            authorizedIds = rule.spaceIds
        }
        MatrixRoomSecurityJoinRule.Unsupported -> {
            mappedAccess = null
            authorizedIds = emptySet()
        }
    }
    val localAddress = aliases.firstOrNull {
        it.endsWith(":$serverName", ignoreCase = true)
    }?.withCanonicalMatrixServerName(serverName)
    return LoadedRoomSecuritySettings(
        access = mappedAccess,
        authorizedSpaceIds = authorizedIds,
        parentSpaces = loadedParentSpaces
            .asSequence()
            .filter { it.roomId != roomId }
            .distinctBy(MatrixSpaceRoom::roomId)
            .map { space ->
                RoomSecurityParentSpace(
                    roomId = space.roomId,
                    displayName = space.displayName.ifBlank { space.roomId }
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            .toList(),
        historyVisibility = historyVisibility.takeUnless {
            it == MatrixRoomHistoryVisibility.CUSTOM
        },
        isEncrypted = isEncrypted,
        displayedAddress = localAddress ?: canonicalAlias ?: alternativeAliases.firstOrNull(),
        localAddress = localAddress,
        serverName = serverName,
        isVisibleInDirectory = when (directoryVisibility) {
            MatrixRoomDirectoryVisibility.PRIVATE -> false
            MatrixRoomDirectoryVisibility.PUBLIC -> true
            MatrixRoomDirectoryVisibility.UNSUPPORTED -> null
        },
        permissions = permissions
    )
}

private fun RoomSecurityState.withPermissions(
    permissions: MatrixRoomSecurityPermissions
): RoomSecurityState {
    return copy(
        canChangeAccess = permissions.canChangeJoinRule,
        canChangeHistoryVisibility = permissions.canChangeHistoryVisibility,
        canEnableEncryption = permissions.canEnableEncryption,
        canChangeAddress = permissions.canChangeAddress,
        canChangeDirectoryVisibility = permissions.canChangeDirectoryVisibility
    )
}

private fun RoomSecurityState.accessIdentity(): Pair<RoomSecurityAccessOption?, Set<String>> {
    return access to if (access == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS) {
        authorizedSpaceIds
    } else {
        emptySet()
    }
}

private fun RoomSecurityState.withDesired(
    desired: DesiredRoomSecuritySettings,
    previousAddressAvailability: RoomSecurityAddressAvailability
): RoomSecurityState {
    return copy(
        editAccess = desired.access ?: access,
        editAuthorizedSpaceIds = if (desired.access != null) {
            desired.authorizedSpaceIds
        } else {
            authorizedSpaceIds
        },
        editHistoryVisibility = desired.historyVisibility ?: historyVisibility,
        editIsEncrypted = isEncrypted == true || desired.enableEncryption,
        editAddressLocalPart = if (desired.hasAddressChange) {
            desired.address?.aliasLocalPart().orEmpty()
        } else {
            localAddress?.aliasLocalPart().orEmpty()
        },
        editIsVisibleInDirectory = desired.directoryVisibility
            ?: (isVisibleInDirectory == true),
        addressAvailability = when {
            !desired.hasAddressChange || desired.address == localAddress ->
                RoomSecurityAddressAvailability.UNCHANGED
            desired.address == null && localAddress != null ->
                RoomSecurityAddressAvailability.REMOVAL_READY
            previousAddressAvailability == RoomSecurityAddressAvailability.OWNED_BY_ROOM ->
                RoomSecurityAddressAvailability.OWNED_BY_ROOM
            else -> previousAddressAvailability
        }
    )
}

private fun RoomSecurityAccessOption.toMatrixJoinRule(
    authorizedSpaceIds: Set<String>
): MatrixRoomSecurityJoinRule {
    return when (this) {
        RoomSecurityAccessOption.INVITE_ONLY -> MatrixRoomSecurityJoinRule.InviteOnly
        RoomSecurityAccessOption.PUBLIC -> MatrixRoomSecurityJoinRule.Public
        RoomSecurityAccessOption.PARENT_SPACE_MEMBERS ->
            MatrixRoomSecurityJoinRule.Restricted(
                spaceIds = authorizedSpaceIds,
                hasUnsupportedRules = false
            )
    }
}

private fun RoomSecurityState.canSaveWhenSaving(): Boolean {
    return copy(isSaving = false).canSave
}

private fun RoomSecurityState.didRecoverAnyField(initial: RoomSecurityState): Boolean {
    return (initial.hasAccessChange && accessIdentity() == Pair(
        initial.editAccess,
        if (initial.editAccess == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS) {
            initial.editAuthorizedSpaceIds
        } else {
            emptySet()
        }
    )) ||
        (initial.hasHistoryChange && historyVisibility == initial.editHistoryVisibility) ||
        (initial.hasEncryptionChange && isEncrypted == true) ||
        (initial.hasAddressChange && localAddress == initial.editFullAddress) ||
        (initial.hasDirectoryChange &&
            isVisibleInDirectory == initial.editIsVisibleInDirectory)
}

private fun RoomSecurityTarget.normalizedOrNull(): RoomSecurityTarget? {
    val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty) ?: return null
    return copy(
        userId = normalizedUserId,
        roomId = normalizedRoomId,
        displayName = displayName.trim()
    )
}

private fun normalizeAliasInput(value: String, serverName: String): String {
    var normalized = value.trim().lowercase(Locale.ROOT).removePrefix("#")
    val suffix = serverName.takeIf(String::isNotBlank)?.let { ":$it" }
    if (suffix != null && normalized.endsWith(suffix, ignoreCase = true)) {
        normalized = normalized.dropLast(suffix.length)
    } else {
        normalized = normalized.substringBefore(':')
    }
    return normalized
}

private fun String.aliasLocalPart(): String {
    return removePrefix("#").substringBefore(':')
}

private const val ROOM_ALIAS_CHECK_DEBOUNCE_MILLIS = 350L
