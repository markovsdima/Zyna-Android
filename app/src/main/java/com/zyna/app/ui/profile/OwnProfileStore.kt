package com.zyna.app.ui.profile

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixOwnProfile
import com.zyna.app.data.profile.ProfileAvatarDraft
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OwnProfileAvatarChange {
    KEEP,
    REPLACE,
    REMOVE
}

data class OwnProfileState(
    val userId: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val editDisplayName: String = "",
    val editAvatarLocalPath: String? = null,
    val editAvatarMimeType: String = DEFAULT_PROFILE_AVATAR_MIME_TYPE,
    val editAvatarChange: OwnProfileAvatarChange = OwnProfileAvatarChange.KEEP,
    val editSessionId: Long = 0L
) {
    val effectiveDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: userId

    val hasAvatar: Boolean
        get() = editAvatarChange != OwnProfileAvatarChange.REMOVE &&
            (avatarUrl?.isNotBlank() == true ||
                (editAvatarChange == OwnProfileAvatarChange.REPLACE &&
                    editAvatarLocalPath != null))

    val hasUnsavedChanges: Boolean
        get() = editSessionId != 0L &&
            (editDisplayName != displayName.orEmpty() ||
                editAvatarChange != OwnProfileAvatarChange.KEEP)
}

internal class OwnProfileDriver(
    val loadProfile: suspend () -> MatrixOwnProfile,
    val setDisplayName: suspend (String) -> Unit,
    val uploadAvatar: suspend (localPath: String, mimeType: String) -> Unit,
    val removeAvatar: suspend () -> Unit,
    val deleteDraft: (String?) -> Unit
)

/**
 * Owns profile loading, editing, saving, and avatar-draft cleanup for one session.
 *
 * Public methods and driver callbacks are main-thread confined. Navigation remains
 * outside the store and is notified only after an edit is successfully finished.
 */
internal class OwnProfileStore(
    private val scope: CoroutineScope,
    private val driver: OwnProfileDriver,
    private val onEditFinished: () -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(OwnProfileState())
    val state: StateFlow<OwnProfileState> = _state.asStateFlow()

    private var activeUserId: String? = null
    private var loadedUserId: String? = null
    private var operationJob: Job? = null
    private var operationGeneration = 0L
    private var editSessionCounter = 0L

    @MainThread
    fun activate(userId: String) {
        if (userId.isBlank()) {
            deactivate()
            return
        }
        if (activeUserId != null && activeUserId != userId) {
            deactivate()
        }
        activeUserId = userId
        if (_state.value.editSessionId != 0L) {
            return
        }
        load(userId = userId, force = false)
    }

    @MainThread
    fun refresh() {
        activeUserId?.let { userId -> load(userId = userId, force = true) }
    }

    @MainThread
    fun deactivate() {
        activeUserId = null
        loadedUserId = null
        cancelOperation()
        val draftPath = _state.value.editAvatarLocalPath
        _state.value = OwnProfileState()
        driver.deleteDraft(draftPath)
    }

    @MainThread
    fun beginEdit() {
        cancelOperation()
        loadedUserId = null
        val previousDraftPath = _state.value.editAvatarLocalPath
        editSessionCounter += 1
        _state.value = _state.value.copy(
            editDisplayName = _state.value.displayName.orEmpty(),
            editAvatarLocalPath = null,
            editAvatarMimeType = DEFAULT_PROFILE_AVATAR_MIME_TYPE,
            editAvatarChange = OwnProfileAvatarChange.KEEP,
            editSessionId = editSessionCounter,
            isLoading = false,
            isSaving = false,
            errorMessage = null
        )
        driver.deleteDraft(previousDraftPath)
    }

    @MainThread
    fun setDisplayNameDraft(displayName: String) {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving) {
            return
        }
        _state.value = current.copy(
            editDisplayName = displayName,
            errorMessage = null
        )
    }

    @MainThread
    fun setAvatarDraft(draft: ProfileAvatarDraft, editSessionId: Long) {
        val current = _state.value
        if (
            current.editSessionId == 0L ||
            current.editSessionId != editSessionId ||
            current.isSaving
        ) {
            driver.deleteDraft(draft.localPath)
            return
        }
        _state.value = current.copy(
            editAvatarLocalPath = draft.localPath,
            editAvatarMimeType = draft.mimeType,
            editAvatarChange = OwnProfileAvatarChange.REPLACE,
            errorMessage = null
        )
        if (current.editAvatarLocalPath != draft.localPath) {
            driver.deleteDraft(current.editAvatarLocalPath)
        }
    }

    @MainThread
    fun discardAvatarDraft(draft: ProfileAvatarDraft) {
        driver.deleteDraft(draft.localPath)
    }

    @MainThread
    fun setEditError(message: String, editSessionId: Long) {
        val current = _state.value
        if (
            current.editSessionId == 0L ||
            current.editSessionId != editSessionId ||
            current.isSaving
        ) {
            return
        }
        _state.value = current.copy(isSaving = false, errorMessage = message)
    }

    @MainThread
    fun removeAvatarDraft() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving) {
            return
        }
        val nextChange = if (current.avatarUrl.isNullOrBlank()) {
            OwnProfileAvatarChange.KEEP
        } else {
            OwnProfileAvatarChange.REMOVE
        }
        _state.value = current.copy(
            editAvatarLocalPath = null,
            editAvatarMimeType = DEFAULT_PROFILE_AVATAR_MIME_TYPE,
            editAvatarChange = nextChange,
            errorMessage = null
        )
        driver.deleteDraft(current.editAvatarLocalPath)
    }

    @MainThread
    fun cancelEdit() {
        val current = _state.value
        if (current.isSaving) {
            return
        }
        _state.value = current.clearedEditState()
        driver.deleteDraft(current.editAvatarLocalPath)
    }

    @MainThread
    fun save() {
        val userId = activeUserId ?: return
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving) {
            return
        }
        val nextDisplayName = current.editDisplayName.trim()
        val didChangeName = nextDisplayName != current.displayName.orEmpty()
        val avatarChange = current.editAvatarChange
        val avatarPath = current.editAvatarLocalPath
        val avatarMimeType = current.editAvatarMimeType
        val didChangeAvatar = avatarChange != OwnProfileAvatarChange.KEEP
        if (!didChangeName && !didChangeAvatar) {
            cancelEdit()
            onEditFinished()
            return
        }

        val generation = beginOperation()
        loadedUserId = null
        _state.value = current.copy(isSaving = true, errorMessage = null)
        val nextJob = scope.launch {
            try {
                if (didChangeName) {
                    driver.setDisplayName(nextDisplayName)
                }
                when (avatarChange) {
                    OwnProfileAvatarChange.KEEP -> Unit
                    OwnProfileAvatarChange.REPLACE -> {
                        driver.uploadAvatar(
                            avatarPath ?: error("Avatar file is not available"),
                            avatarMimeType
                        )
                    }
                    OwnProfileAvatarChange.REMOVE -> driver.removeAvatar()
                }
                val refreshed = driver.loadProfile()
                if (!isCurrentOperation(userId, generation)) {
                    return@launch
                }
                driver.deleteDraft(avatarPath)
                loadedUserId = refreshed.userId
                _state.value = refreshed.toState()
                onEditFinished()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentOperation(userId, generation)) {
                    return@launch
                }
                onWarning("Failed to save own profile", error)
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = error.message ?: error.javaClass.simpleName
                )
            }
        }
        operationJob = nextJob
        nextJob.invokeOnCompletion {
            if (operationJob === nextJob) {
                operationJob = null
            }
        }
    }

    private fun load(userId: String, force: Boolean) {
        if (!force && loadedUserId == userId && _state.value.errorMessage == null) {
            return
        }
        if (operationJob?.isActive == true && loadedUserId == userId) {
            return
        }
        val generation = beginOperation()
        loadedUserId = userId
        _state.value = _state.value.copy(
            userId = userId,
            isLoading = true,
            isSaving = false,
            errorMessage = null
        )
        val nextJob = scope.launch {
            try {
                val profile = driver.loadProfile()
                if (!isCurrentOperation(userId, generation)) {
                    return@launch
                }
                loadedUserId = profile.userId
                _state.value = profile.toState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentOperation(userId, generation)) {
                    return@launch
                }
                onWarning("Failed to load own profile", error)
                _state.value = _state.value.copy(
                    userId = userId,
                    isLoading = false,
                    isSaving = false,
                    errorMessage = error.message ?: error.javaClass.simpleName
                )
            }
        }
        operationJob = nextJob
        nextJob.invokeOnCompletion {
            if (operationJob === nextJob) {
                operationJob = null
            }
        }
    }

    private fun beginOperation(): Long {
        cancelOperation()
        return operationGeneration
    }

    private fun cancelOperation() {
        operationGeneration += 1
        operationJob?.cancel()
        operationJob = null
    }

    private fun isCurrentOperation(userId: String, generation: Long): Boolean {
        return activeUserId == userId && operationGeneration == generation
    }
}

internal fun createOwnProfileStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onEditFinished: () -> Unit,
    onWarning: (String, Throwable) -> Unit
): OwnProfileStore {
    return OwnProfileStore(
        scope = scope,
        driver = OwnProfileDriver(
            loadProfile = matrixClientService::loadOwnProfile,
            setDisplayName = matrixClientService::setOwnDisplayName,
            uploadAvatar = matrixClientService::uploadOwnAvatar,
            removeAvatar = matrixClientService::removeOwnAvatar,
            deleteDraft = { path ->
                path?.takeIf { it.isNotBlank() }?.let { localPath ->
                    runCatching { File(localPath).delete() }
                }
            }
        ),
        onEditFinished = onEditFinished,
        onWarning = onWarning
    )
}

private fun MatrixOwnProfile.toState(): OwnProfileState {
    return OwnProfileState(
        userId = userId,
        displayName = displayName?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
        isLoading = false,
        isSaving = false,
        errorMessage = null,
        editDisplayName = displayName.orEmpty()
    )
}

private fun OwnProfileState.clearedEditState(): OwnProfileState {
    return copy(
        editDisplayName = displayName.orEmpty(),
        editAvatarLocalPath = null,
        editAvatarMimeType = DEFAULT_PROFILE_AVATAR_MIME_TYPE,
        editAvatarChange = OwnProfileAvatarChange.KEEP,
        editSessionId = 0L,
        isSaving = false,
        errorMessage = null
    )
}

private const val DEFAULT_PROFILE_AVATAR_MIME_TYPE = "image/jpeg"
