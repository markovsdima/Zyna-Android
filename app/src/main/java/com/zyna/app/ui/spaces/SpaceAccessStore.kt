package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixRoomAliasAvailability
import com.zyna.app.data.matrix.MatrixRoomCreationAccess
import com.zyna.app.data.matrix.MatrixRoomDirectoryVisibility
import com.zyna.app.data.matrix.MatrixSpaceAccessJoinRule
import com.zyna.app.data.matrix.MatrixSpaceAccessPermissions
import com.zyna.app.data.matrix.MatrixSpaceAccessSnapshot
import com.zyna.app.data.matrix.MatrixClientService
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

data class SpaceAccessTarget(
    val userId: String,
    val spaceId: String,
    val parentSpaceId: String?,
    val displayName: String
)

enum class SpaceAccessOption {
    PRIVATE,
    PARENT_MEMBERS,
    PUBLIC
}

enum class SpaceAddressAvailability {
    UNCHANGED,
    CHECKING,
    AVAILABLE,
    OWNED_BY_SPACE,
    TAKEN,
    REMOVAL_UNSUPPORTED,
    INVALID,
    ERROR
}

enum class SpaceAccessError {
    LOAD,
    ADDRESS_CHECK,
    PERMISSION_CHANGED,
    REMOTE_CHANGED,
    SAVE,
    PARTIAL_SAVE
}

data class SpaceAccessState(
    val target: SpaceAccessTarget? = null,
    val access: SpaceAccessOption? = null,
    val editAccess: SpaceAccessOption? = null,
    val displayedAddress: String? = null,
    val localAddress: String? = null,
    val editAddressLocalPart: String = "",
    val serverName: String = "",
    val isVisibleInDirectory: Boolean? = null,
    val editIsVisibleInDirectory: Boolean = false,
    val canChangeAccess: Boolean = false,
    val canChangeAddress: Boolean = false,
    val canChangeDirectoryVisibility: Boolean = false,
    val addressAvailability: SpaceAddressAvailability = SpaceAddressAvailability.UNCHANGED,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: SpaceAccessError? = null,
    val editSessionId: Long = 0L,
    val isDiscardConfirmationVisible: Boolean = false
) {
    val isJoinRuleSupported: Boolean
        get() = access != null && editAccess != null

    val isDirectoryVisibilitySupported: Boolean
        get() = isVisibleInDirectory != null

    val editFullAddress: String?
        get() = editAddressLocalPart.takeIf(String::isNotBlank)?.let { localPart ->
            serverName.takeIf(String::isNotBlank)?.let { server -> "#$localPart:$server" }
        }

    val hasAccessChange: Boolean
        get() = isJoinRuleSupported && editAccess != access

    val hasAddressChange: Boolean
        get() = editFullAddress != localAddress

    val hasDirectoryChange: Boolean
        get() = isVisibleInDirectory?.let { it != editIsVisibleInDirectory } == true

    val hasUnsavedChanges: Boolean
        get() = editSessionId != 0L &&
            (hasAccessChange || hasAddressChange || hasDirectoryChange)

    val hasUsableAddress: Boolean
        get() = when {
            hasAddressChange -> editFullAddress != null && addressAvailability in setOf(
                SpaceAddressAvailability.AVAILABLE,
                SpaceAddressAvailability.OWNED_BY_SPACE
            )
            else -> !displayedAddress.isNullOrBlank()
        }

    val canSave: Boolean
        get() {
            if (isLoading || isSaving || !hasUnsavedChanges) return false
            if (hasAccessChange && !canChangeAccess) return false
            if (hasAddressChange) {
                if (!canChangeAddress || !hasUsableAddress) return false
            }
            if (hasDirectoryChange && !canChangeDirectoryVisibility) return false
            if (hasDirectoryChange && editIsVisibleInDirectory) {
                if (editAccess != SpaceAccessOption.PUBLIC || !hasUsableAddress) return false
            }
            return true
        }
}

internal class SpaceAccessDriver(
    val load: suspend (userId: String, spaceId: String) -> MatrixSpaceAccessSnapshot,
    val observePermissions: (
        userId: String,
        spaceId: String
    ) -> Flow<MatrixSpaceAccessPermissions>,
    val isAliasValid: (String) -> Boolean,
    val checkAlias: suspend (
        userId: String,
        roomId: String,
        alias: String
    ) -> MatrixRoomAliasAvailability,
    val setAddress: suspend (userId: String, spaceId: String, alias: String) -> Unit,
    val setJoinRule: suspend (
        userId: String,
        spaceId: String,
        access: MatrixRoomCreationAccess
    ) -> Unit,
    val setDirectoryVisibility: suspend (
        userId: String,
        spaceId: String,
        isVisible: Boolean
    ) -> Unit
)

/**
 * Owns one route-scoped Space access edit session.
 *
 * Matrix has no transaction spanning join rules, aliases, and room-directory visibility. Writes
 * are therefore ordered for safety and each confirmed field becomes a new retry baseline. A
 * failed write is followed by a fresh read so server-applied results are never reported as lost.
 */
internal class SpaceAccessStore(
    private val scope: CoroutineScope,
    private val driver: SpaceAccessDriver,
    private val onFinished: (target: SpaceAccessTarget, didSave: Boolean) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> },
    private val aliasCheckDebounceMillis: Long = SPACE_ALIAS_CHECK_DEBOUNCE_MILLIS
) {
    private val _state = MutableStateFlow(SpaceAccessState())
    val state: StateFlow<SpaceAccessState> = _state.asStateFlow()

    private var generation = 0L
    private var aliasGeneration = 0L
    private var editSessionCounter = 0L
    private var loadJob: Job? = null
    private var permissionJob: Job? = null
    private var saveJob: Job? = null
    private var aliasJob: Job? = null

    @MainThread
    fun activate(target: SpaceAccessTarget) {
        val normalized = target.normalizedOrNull() ?: return
        if (_state.value.target == normalized && _state.value.editSessionId != 0L) return
        cancelAll()
        editSessionCounter += 1
        val sessionId = editSessionCounter
        _state.value = SpaceAccessState(
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
        _state.value = SpaceAccessState()
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
    fun setAccess(access: SpaceAccessOption) {
        val current = editableStateOrNull() ?: return
        if (!current.isJoinRuleSupported || !current.canChangeAccess) return
        if (access == SpaceAccessOption.PARENT_MEMBERS && current.target?.parentSpaceId == null) {
            return
        }
        _state.value = current.copy(
            editAccess = access,
            editIsVisibleInDirectory = if (access == SpaceAccessOption.PUBLIC) {
                current.editIsVisibleInDirectory
            } else {
                false
            },
            error = null
        )
    }

    @MainThread
    fun setAddressLocalPart(value: String) {
        val current = editableStateOrNull() ?: return
        if (!current.canChangeAddress) return
        val normalized = normalizeAliasInput(value, current.serverName)
        val next = current.copy(
            editAddressLocalPart = normalized,
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
        if (isVisible && current.editAccess != SpaceAccessOption.PUBLIC) return
        _state.value = current.copy(
            editIsVisibleInDirectory = isVisible,
            error = null
        )
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
        val desiredAccess = initial.editAccess.takeIf { initial.hasAccessChange }
        val desiredAddress = initial.editFullAddress.takeIf { initial.hasAddressChange }
        val desiredDirectoryVisibility = initial.editIsVisibleInDirectory.takeIf {
            initial.hasDirectoryChange
        }
        val requestGeneration = beginSave()
        _state.value = initial.copy(
            isSaving = true,
            error = null,
            isDiscardConfirmationVisible = false
        )

        val nextJob = scope.launch {
            var didSaveAnyField = false
            try {
                val freshSnapshot = driver.load(target.userId, target.spaceId)
                if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                val fresh = freshSnapshot.toLoadedSettings(target)
                val preflight = reconcilePreflight(
                    initial = initial,
                    fresh = fresh,
                    desiredAccess = desiredAccess,
                    desiredAddress = desiredAddress,
                    desiredDirectoryVisibility = desiredDirectoryVisibility
                )
                if (preflight == null) {
                    _state.value = fresh.toState(
                        target = target,
                        editSessionId = initial.editSessionId,
                        error = SpaceAccessError.REMOTE_CHANGED
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
                        error = SpaceAccessError.PERMISSION_CHANGED
                    )
                    return@launch
                }

                if (
                    preflight.hasDirectoryChange &&
                    preflight.editIsVisibleInDirectory.not() &&
                    preflight.isVisibleInDirectory == true
                ) {
                    driver.setDirectoryVisibility(target.userId, target.spaceId, false)
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markDirectorySaved(false)
                }

                if (_state.value.hasAddressChange) {
                    driver.setAddress(
                        target.userId,
                        target.spaceId,
                        desiredAddress ?: error("Space address is unavailable")
                    )
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markAddressSaved(desiredAddress)
                }

                if (_state.value.hasAccessChange) {
                    val accessToSave = requireNotNull(desiredAccess) {
                        "Space join rule is unavailable"
                    }
                    driver.setJoinRule(
                        target.userId,
                        target.spaceId,
                        accessToSave.toMatrixAccess(target.parentSpaceId)
                    )
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markAccessSaved(accessToSave)
                }

                if (_state.value.hasDirectoryChange) {
                    val visibilityToSave = requireNotNull(desiredDirectoryVisibility) {
                        "Space directory visibility is unavailable"
                    }
                    driver.setDirectoryVisibility(
                        target.userId,
                        target.spaceId,
                        visibilityToSave
                    )
                    if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    markDirectorySaved(visibilityToSave)
                }

                finishAfterSave(target, initial.editSessionId, requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                onWarning("Failed to save Space access", error)
                val reconciled = try {
                    driver.load(target.userId, target.spaceId).toLoadedSettings(target)
                } catch (refreshCancellation: CancellationException) {
                    throw refreshCancellation
                } catch (refreshError: Throwable) {
                    error.addSuppressed(refreshError)
                    null
                }
                if (!isCurrent(target, initial.editSessionId, requestGeneration)) return@launch
                if (reconciled != null) {
                    val recovered = reconcileAfterWriteFailure(
                        current = _state.value,
                        fresh = reconciled,
                        desiredAccess = desiredAccess,
                        desiredAddress = desiredAddress,
                        desiredDirectoryVisibility = desiredDirectoryVisibility
                    )
                    if (!recovered.hasUnsavedChanges) {
                        onWarning(
                            "Space access write reported failure but the server confirmed it",
                            error
                        )
                        finishAfterSave(target, initial.editSessionId, requestGeneration)
                        return@launch
                    }
                    _state.value = recovered.copy(
                        isSaving = false,
                        error = if (didSaveAnyField || recovered.didRecoverAnyField(initial)) {
                            SpaceAccessError.PARTIAL_SAVE
                        } else {
                            SpaceAccessError.SAVE
                        }
                    )
                } else {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = if (didSaveAnyField) {
                            SpaceAccessError.PARTIAL_SAVE
                        } else {
                            SpaceAccessError.SAVE
                        }
                    )
                }
            }
        }
        saveJob = nextJob
        nextJob.invokeOnCompletion {
            if (saveJob === nextJob) saveJob = null
        }
    }

    private fun observePermissions(target: SpaceAccessTarget, editSessionId: Long) {
        permissionJob = scope.launch {
            try {
                driver.observePermissions(target.userId, target.spaceId).collect { permissions ->
                    val current = _state.value
                    if (current.target != target || current.editSessionId != editSessionId) {
                        return@collect
                    }
                    val permissionChanged =
                        (current.hasAccessChange && !permissions.canChangeJoinRule) ||
                            (current.hasAddressChange && !permissions.canChangeAddress) ||
                            (current.hasDirectoryChange &&
                                !permissions.canChangeDirectoryVisibility)
                    _state.value = current.copy(
                        canChangeAccess = permissions.canChangeJoinRule,
                        canChangeAddress = permissions.canChangeAddress,
                        canChangeDirectoryVisibility =
                            permissions.canChangeDirectoryVisibility,
                        error = if (permissionChanged) {
                            SpaceAccessError.PERMISSION_CHANGED
                        } else {
                            current.error.takeUnless {
                                it == SpaceAccessError.PERMISSION_CHANGED
                            }
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (_state.value.target == target) {
                    onWarning("Failed to observe Space access permissions", error)
                }
            }
        }
    }

    private fun load(target: SpaceAccessTarget, editSessionId: Long) {
        loadJob = scope.launch {
            try {
                val loaded = driver.load(target.userId, target.spaceId).toLoadedSettings(target)
                val current = _state.value
                if (current.target != target || current.editSessionId != editSessionId) return@launch
                _state.value = loaded.toState(target, editSessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val current = _state.value
                if (current.target != target || current.editSessionId != editSessionId) return@launch
                onWarning("Failed to load Space access", error)
                _state.value = current.copy(
                    isLoading = false,
                    error = SpaceAccessError.LOAD
                )
            }
        }
    }

    private fun publishAddressDraft(state: SpaceAccessState, debounce: Boolean = true) {
        cancelAliasCheck()
        val fullAddress = state.editFullAddress
        when {
            !state.hasAddressChange -> {
                _state.value = state.copy(
                    addressAvailability = SpaceAddressAvailability.UNCHANGED
                )
            }
            state.editAddressLocalPart.isBlank() && state.localAddress != null -> {
                _state.value = state.copy(
                    addressAvailability = SpaceAddressAvailability.REMOVAL_UNSUPPORTED
                )
            }
            fullAddress == null || !driver.isAliasValid(fullAddress) -> {
                _state.value = state.copy(
                    addressAvailability = SpaceAddressAvailability.INVALID
                )
            }
            fullAddress == state.localAddress -> {
                _state.value = state.copy(
                    addressAvailability = SpaceAddressAvailability.UNCHANGED
                )
            }
            else -> {
                val target = state.target ?: return
                val sessionId = state.editSessionId
                aliasGeneration += 1
                val requestGeneration = aliasGeneration
                _state.value = state.copy(
                    addressAvailability = SpaceAddressAvailability.CHECKING
                )
                aliasJob = scope.launch {
                    if (debounce) delay(aliasCheckDebounceMillis)
                    try {
                        val availability = driver.checkAlias(
                            target.userId,
                            target.spaceId,
                            fullAddress
                        )
                        if (!isCurrentAlias(target, sessionId, fullAddress, requestGeneration)) {
                            return@launch
                        }
                        _state.value = _state.value.copy(
                            addressAvailability = when (availability) {
                                MatrixRoomAliasAvailability.AVAILABLE ->
                                    SpaceAddressAvailability.AVAILABLE
                                MatrixRoomAliasAvailability.OWNED_BY_ROOM ->
                                    SpaceAddressAvailability.OWNED_BY_SPACE
                                MatrixRoomAliasAvailability.TAKEN ->
                                    SpaceAddressAvailability.TAKEN
                            },
                            error = null
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (!isCurrentAlias(target, sessionId, fullAddress, requestGeneration)) {
                            return@launch
                        }
                        onWarning("Failed to check Space address", error)
                        _state.value = _state.value.copy(
                            addressAvailability = SpaceAddressAvailability.ERROR,
                            error = SpaceAccessError.ADDRESS_CHECK
                        )
                    }
                }
            }
        }
    }

    private fun reconcilePreflight(
        initial: SpaceAccessState,
        fresh: LoadedSpaceAccessSettings,
        desiredAccess: SpaceAccessOption?,
        desiredAddress: String?,
        desiredDirectoryVisibility: Boolean?
    ): SpaceAccessState? {
        val accessConflict = desiredAccess != null &&
            fresh.access != initial.access && fresh.access != desiredAccess
        val addressConflict = desiredAddress != null &&
            fresh.localAddress != initial.localAddress && fresh.localAddress != desiredAddress
        val directoryConflict = desiredDirectoryVisibility != null &&
            fresh.isVisibleInDirectory != initial.isVisibleInDirectory &&
            fresh.isVisibleInDirectory != desiredDirectoryVisibility
        if (accessConflict || addressConflict || directoryConflict) return null

        return fresh.toState(
            target = requireNotNull(initial.target),
            editSessionId = initial.editSessionId
        ).copy(
            editAccess = desiredAccess ?: fresh.access,
            editAddressLocalPart = desiredAddress?.aliasLocalPart()
                ?: fresh.localAddress?.aliasLocalPart().orEmpty(),
            editIsVisibleInDirectory = desiredDirectoryVisibility
                ?: (fresh.isVisibleInDirectory == true),
            addressAvailability = when {
                desiredAddress == null || desiredAddress == fresh.localAddress ->
                    SpaceAddressAvailability.UNCHANGED
                initial.addressAvailability == SpaceAddressAvailability.OWNED_BY_SPACE ->
                    SpaceAddressAvailability.OWNED_BY_SPACE
                else -> initial.addressAvailability
            },
            isSaving = true
        )
    }

    private fun reconcileAfterWriteFailure(
        current: SpaceAccessState,
        fresh: LoadedSpaceAccessSettings,
        desiredAccess: SpaceAccessOption?,
        desiredAddress: String?,
        desiredDirectoryVisibility: Boolean?
    ): SpaceAccessState {
        return fresh.toState(
            target = requireNotNull(current.target),
            editSessionId = current.editSessionId
        ).copy(
            editAccess = desiredAccess ?: fresh.access,
            editAddressLocalPart = desiredAddress?.aliasLocalPart()
                ?: fresh.localAddress?.aliasLocalPart().orEmpty(),
            editIsVisibleInDirectory = desiredDirectoryVisibility
                ?: (fresh.isVisibleInDirectory == true),
            addressAvailability = when {
                desiredAddress == null || desiredAddress == fresh.localAddress ->
                    SpaceAddressAvailability.UNCHANGED
                current.addressAvailability == SpaceAddressAvailability.OWNED_BY_SPACE ->
                    SpaceAddressAvailability.OWNED_BY_SPACE
                else -> current.addressAvailability
            }
        )
    }

    private fun markAccessSaved(access: SpaceAccessOption) {
        _state.value = _state.value.copy(access = access, editAccess = access)
    }

    private fun markAddressSaved(address: String) {
        _state.value = _state.value.copy(
            displayedAddress = address,
            localAddress = address,
            editAddressLocalPart = address.aliasLocalPart(),
            addressAvailability = SpaceAddressAvailability.UNCHANGED
        )
    }

    private fun markDirectorySaved(isVisible: Boolean) {
        _state.value = _state.value.copy(
            isVisibleInDirectory = isVisible,
            editIsVisibleInDirectory = isVisible
        )
    }

    private fun finishAfterSave(
        target: SpaceAccessTarget,
        editSessionId: Long,
        requestGeneration: Long
    ) {
        if (!isCurrent(target, editSessionId, requestGeneration)) return
        cancelAliasCheck()
        _state.value = SpaceAccessState()
        onFinished(target, true)
    }

    private fun finish(current: SpaceAccessState, didSave: Boolean) {
        val target = current.target ?: return
        cancelAll()
        _state.value = SpaceAccessState()
        onFinished(target, didSave)
    }

    private fun beginSave(): Long {
        cancelSave()
        cancelAliasCheck()
        return generation
    }

    private fun cancelAll() {
        generation += 1
        loadJob?.cancel()
        loadJob = null
        permissionJob?.cancel()
        permissionJob = null
        saveJob?.cancel()
        saveJob = null
        cancelAliasCheck()
    }

    private fun cancelSave() {
        generation += 1
        saveJob?.cancel()
        saveJob = null
    }

    private fun cancelAliasCheck() {
        aliasGeneration += 1
        aliasJob?.cancel()
        aliasJob = null
    }

    private fun editableStateOrNull(): SpaceAccessState? {
        return _state.value.takeIf {
            it.editSessionId != 0L && !it.isLoading && !it.isSaving
        }
    }

    private fun isCurrent(
        target: SpaceAccessTarget,
        editSessionId: Long,
        requestGeneration: Long
    ): Boolean {
        val current = _state.value
        return generation == requestGeneration &&
            current.target == target &&
            current.editSessionId == editSessionId
    }

    private fun isCurrentAlias(
        target: SpaceAccessTarget,
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

internal fun createSpaceAccessStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onFinished: (target: SpaceAccessTarget, didSave: Boolean) -> Unit,
    onWarning: (String, Throwable) -> Unit
): SpaceAccessStore {
    return SpaceAccessStore(
        scope = scope,
        driver = SpaceAccessDriver(
            load = matrixClientService::loadSpaceAccess,
            observePermissions = matrixClientService::spaceAccessPermissionUpdates,
            isAliasValid = matrixClientService::isRoomAliasValid,
            checkAlias = matrixClientService::checkRoomAliasAvailability,
            setAddress = matrixClientService::setSpaceAddress,
            setJoinRule = matrixClientService::setSpaceJoinRule,
            setDirectoryVisibility = matrixClientService::setSpaceDirectoryVisibility
        ),
        onFinished = onFinished,
        onWarning = onWarning
    )
}

private data class LoadedSpaceAccessSettings(
    val access: SpaceAccessOption?,
    val displayedAddress: String?,
    val localAddress: String?,
    val serverName: String,
    val isVisibleInDirectory: Boolean?,
    val permissions: MatrixSpaceAccessPermissions
) {
    fun toState(
        target: SpaceAccessTarget,
        editSessionId: Long,
        error: SpaceAccessError? = null
    ): SpaceAccessState {
        return SpaceAccessState(
            target = target,
            access = access,
            editAccess = access,
            displayedAddress = displayedAddress,
            localAddress = localAddress,
            editAddressLocalPart = localAddress?.aliasLocalPart().orEmpty(),
            serverName = serverName,
            isVisibleInDirectory = isVisibleInDirectory,
            editIsVisibleInDirectory = isVisibleInDirectory == true,
            canChangeAccess = permissions.canChangeJoinRule,
            canChangeAddress = permissions.canChangeAddress,
            canChangeDirectoryVisibility = permissions.canChangeDirectoryVisibility,
            isLoading = false,
            error = error,
            editSessionId = editSessionId
        )
    }
}

private fun MatrixSpaceAccessSnapshot.toLoadedSettings(
    target: SpaceAccessTarget
): LoadedSpaceAccessSettings {
    val access = when (val rule = joinRule) {
        MatrixSpaceAccessJoinRule.InviteOnly -> SpaceAccessOption.PRIVATE
        MatrixSpaceAccessJoinRule.Public -> SpaceAccessOption.PUBLIC
        is MatrixSpaceAccessJoinRule.Restricted -> {
            val parentSpaceId = target.parentSpaceId
            if (
                parentSpaceId != null &&
                !rule.hasUnsupportedRules &&
                rule.roomIds == setOf(parentSpaceId)
            ) {
                SpaceAccessOption.PARENT_MEMBERS
            } else {
                null
            }
        }
        MatrixSpaceAccessJoinRule.Unsupported -> null
    }
    val localAddress = aliases.firstOrNull {
        it.endsWith(":$serverName", ignoreCase = true)
    }
    return LoadedSpaceAccessSettings(
        access = access,
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

private fun SpaceAccessOption.toMatrixAccess(parentSpaceId: String?): MatrixRoomCreationAccess {
    return when (this) {
        SpaceAccessOption.PRIVATE -> MatrixRoomCreationAccess.Private
        SpaceAccessOption.PUBLIC -> MatrixRoomCreationAccess.Public
        SpaceAccessOption.PARENT_MEMBERS -> MatrixRoomCreationAccess.Restricted(
            parentSpaceId = requireNotNull(parentSpaceId) {
                "Parent Space is required for parent-members access"
            }
        )
    }
}

private fun SpaceAccessState.canSaveWhenSaving(): Boolean {
    return copy(isSaving = false).canSave
}

private fun SpaceAccessState.didRecoverAnyField(initial: SpaceAccessState): Boolean {
    return (initial.hasAccessChange && access == initial.editAccess) ||
        (initial.hasAddressChange && localAddress == initial.editFullAddress) ||
        (initial.hasDirectoryChange &&
            isVisibleInDirectory == initial.editIsVisibleInDirectory)
}

private fun SpaceAccessTarget.normalizedOrNull(): SpaceAccessTarget? {
    val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedSpaceId = spaceId.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedParentSpaceId = parentSpaceId?.trim()?.takeIf(String::isNotEmpty)
    if (parentSpaceId != null && normalizedParentSpaceId == null) return null
    if (normalizedParentSpaceId == normalizedSpaceId) return null
    return copy(
        userId = normalizedUserId,
        spaceId = normalizedSpaceId,
        parentSpaceId = normalizedParentSpaceId,
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

private const val SPACE_ALIAS_CHECK_DEBOUNCE_MILLIS = 350L
