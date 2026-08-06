package com.zyna.app.ui.roomprofile

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomCapabilities
import com.zyna.app.data.matrix.MatrixRoomDetails
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.profile.ProfileAvatarDraft
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomProfileEditorTarget(
    val userId: String,
    val roomId: String
)

enum class RoomProfileAvatarChange {
    KEEP,
    REPLACE,
    REMOVE
}

enum class RoomProfileEditorError {
    AVATAR_PREPARATION,
    PERMISSION_CHANGED,
    SAVE,
    PARTIAL_SAVE
}

data class RoomProfileEditorState(
    val target: RoomProfileEditorTarget? = null,
    val kind: MatrixRoomKind = MatrixRoomKind.GROUP,
    val displayName: String = "",
    val topic: String = "",
    val avatarUrl: String? = null,
    val editDisplayName: String = "",
    val editTopic: String = "",
    val editAvatarLocalPath: String? = null,
    val editAvatarMimeType: String = DEFAULT_ROOM_AVATAR_MIME_TYPE,
    val editAvatarChange: RoomProfileAvatarChange = RoomProfileAvatarChange.KEEP,
    val canChangeName: Boolean = false,
    val canChangeTopic: Boolean = false,
    val canChangeAvatar: Boolean = false,
    val editSessionId: Long = 0L,
    val isSaving: Boolean = false,
    val error: RoomProfileEditorError? = null,
    val isDiscardConfirmationVisible: Boolean = false
) {
    val hasAvatar: Boolean
        get() = editAvatarChange != RoomProfileAvatarChange.REMOVE &&
            (avatarUrl?.isNotBlank() == true ||
                (editAvatarChange == RoomProfileAvatarChange.REPLACE &&
                    editAvatarLocalPath != null))

    val hasNameChange: Boolean
        get() = editDisplayName.trim() != displayName

    val hasTopicChange: Boolean
        get() = editTopic.trim() != topic

    val hasAvatarChange: Boolean
        get() = editAvatarChange != RoomProfileAvatarChange.KEEP

    val hasUnsavedChanges: Boolean
        get() = editSessionId != 0L && (hasNameChange || hasTopicChange || hasAvatarChange)

    val canSave: Boolean
        get() {
            if (isSaving || !hasUnsavedChanges) return false
            if (hasNameChange && (!canChangeName || editDisplayName.trim().isEmpty())) return false
            if (hasTopicChange && !canChangeTopic) return false
            if (hasAvatarChange && !canChangeAvatar) return false
            return true
        }
}

internal class RoomProfileEditorDriver(
    val loadCapabilities: suspend (roomId: String) -> MatrixRoomCapabilities,
    val setName: suspend (roomId: String, name: String) -> Unit,
    val setTopic: suspend (roomId: String, topic: String) -> Unit,
    val uploadAvatar: suspend (roomId: String, localPath: String, mimeType: String) -> Unit,
    val removeAvatar: suspend (roomId: String) -> Unit,
    val deleteDraft: (String?) -> Unit
)

/**
 * Owns one route-scoped room profile edit session.
 *
 * Matrix does not provide a transaction spanning room name, topic, and avatar state events. Saving
 * is therefore deliberately sequential: after each successful update that field becomes the new
 * baseline. If a later update fails, only the still-dirty fields remain retryable and the UI
 * reports a partial save instead of pretending the whole operation rolled back.
 */
internal class RoomProfileEditorStore(
    private val scope: CoroutineScope,
    private val driver: RoomProfileEditorDriver,
    private val onEditFinished: (target: RoomProfileEditorTarget, didSave: Boolean) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomProfileEditorState())
    val state: StateFlow<RoomProfileEditorState> = _state.asStateFlow()

    private var generation = 0L
    private var editSessionCounter = 0L
    private var saveJob: Job? = null

    @MainThread
    fun beginEdit(target: RoomProfileEditorTarget, details: MatrixRoomDetails) {
        val normalizedTarget = target.normalizedOrNull() ?: return
        if (details.roomId != normalizedTarget.roomId || details.kind == MatrixRoomKind.DIRECT) {
            return
        }
        cancelSave()
        val previousDraft = _state.value.editAvatarLocalPath
        editSessionCounter += 1
        _state.value = RoomProfileEditorState(
            target = normalizedTarget,
            kind = details.kind,
            displayName = details.displayName.trim(),
            topic = details.topic?.trim().orEmpty(),
            avatarUrl = details.avatarUrl?.takeIf { it.isNotBlank() },
            editDisplayName = details.displayName.trim(),
            editTopic = details.topic?.trim().orEmpty(),
            canChangeName = details.capabilities.canChangeName == true,
            canChangeTopic = details.capabilities.canChangeTopic == true,
            canChangeAvatar = details.capabilities.canChangeAvatar == true,
            editSessionId = editSessionCounter
        )
        driver.deleteDraft(previousDraft)
    }

    @MainThread
    fun deactivate() {
        cancelSave()
        val draft = _state.value.editAvatarLocalPath
        _state.value = RoomProfileEditorState()
        driver.deleteDraft(draft)
    }

    @MainThread
    fun setDisplayNameDraft(displayName: String) {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving || !current.canChangeName) return
        _state.value = current.copy(
            editDisplayName = displayName,
            error = null
        )
    }

    @MainThread
    fun setTopicDraft(topic: String) {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving || !current.canChangeTopic) return
        _state.value = current.copy(
            editTopic = topic,
            error = null
        )
    }

    @MainThread
    fun setAvatarDraft(draft: ProfileAvatarDraft, target: RoomProfileEditorTarget, editSessionId: Long) {
        val current = _state.value
        if (
            current.target != target ||
            current.editSessionId == 0L ||
            current.editSessionId != editSessionId ||
            current.isSaving ||
            !current.canChangeAvatar
        ) {
            driver.deleteDraft(draft.localPath)
            return
        }
        _state.value = current.copy(
            editAvatarLocalPath = draft.localPath,
            editAvatarMimeType = draft.mimeType,
            editAvatarChange = RoomProfileAvatarChange.REPLACE,
            error = null
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
    fun setAvatarPreparationError(target: RoomProfileEditorTarget, editSessionId: Long) {
        val current = _state.value
        if (
            current.target != target ||
            current.editSessionId != editSessionId ||
            current.isSaving
        ) return
        _state.value = current.copy(error = RoomProfileEditorError.AVATAR_PREPARATION)
    }

    @MainThread
    fun removeAvatarDraft() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving || !current.canChangeAvatar) return
        val nextChange = if (current.avatarUrl.isNullOrBlank()) {
            RoomProfileAvatarChange.KEEP
        } else {
            RoomProfileAvatarChange.REMOVE
        }
        _state.value = current.copy(
            editAvatarLocalPath = null,
            editAvatarMimeType = DEFAULT_ROOM_AVATAR_MIME_TYPE,
            editAvatarChange = nextChange,
            error = null
        )
        driver.deleteDraft(current.editAvatarLocalPath)
    }

    @MainThread
    fun requestExit() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isSaving) return
        if (current.hasUnsavedChanges) {
            _state.value = current.copy(isDiscardConfirmationVisible = true)
        } else {
            finishWithoutSaving(current)
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
        if (current.isSaving || current.editSessionId == 0L) return
        finishWithoutSaving(current)
    }

    @MainThread
    fun save() {
        val current = _state.value
        val target = current.target ?: return
        if (!current.canSave) return

        val nameChanged = current.hasNameChange
        val topicChanged = current.hasTopicChange
        val avatarChange = current.editAvatarChange
        val nextName = current.editDisplayName.trim()
        val nextTopic = current.editTopic.trim()
        val avatarPath = current.editAvatarLocalPath
        val avatarMimeType = current.editAvatarMimeType
        val requestGeneration = beginSave()
        _state.value = current.copy(
            isSaving = true,
            error = null,
            isDiscardConfirmationVisible = false
        )

        val nextJob = scope.launch {
            var didSaveAnyField = false
            try {
                val capabilities = driver.loadCapabilities(target.roomId)
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                val canChangeName = capabilities.canChangeName == true
                val canChangeTopic = capabilities.canChangeTopic == true
                val canChangeAvatar = capabilities.canChangeAvatar == true
                if ((nameChanged && !canChangeName) ||
                    (topicChanged && !canChangeTopic) ||
                    (avatarChange != RoomProfileAvatarChange.KEEP && !canChangeAvatar)
                ) {
                    _state.value = _state.value.copy(
                        canChangeName = canChangeName,
                        canChangeTopic = canChangeTopic,
                        canChangeAvatar = canChangeAvatar,
                        isSaving = false,
                        error = RoomProfileEditorError.PERMISSION_CHANGED
                    )
                    return@launch
                }

                if (nameChanged) {
                    driver.setName(target.roomId, nextName)
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    _state.value = _state.value.copy(
                        displayName = nextName,
                        editDisplayName = nextName
                    )
                }

                if (topicChanged) {
                    driver.setTopic(target.roomId, nextTopic)
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                    didSaveAnyField = true
                    _state.value = _state.value.copy(
                        topic = nextTopic,
                        editTopic = nextTopic
                    )
                }

                when (avatarChange) {
                    RoomProfileAvatarChange.KEEP -> Unit
                    RoomProfileAvatarChange.REPLACE -> driver.uploadAvatar(
                        target.roomId,
                        avatarPath ?: error("Avatar file is not available"),
                        avatarMimeType
                    )
                    RoomProfileAvatarChange.REMOVE -> driver.removeAvatar(target.roomId)
                }
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch

                driver.deleteDraft(avatarPath)
                _state.value = RoomProfileEditorState()
                onEditFinished(target, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                onWarning("Failed to save room profile", error)
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = if (didSaveAnyField) {
                        RoomProfileEditorError.PARTIAL_SAVE
                    } else {
                        RoomProfileEditorError.SAVE
                    }
                )
            }
        }
        saveJob = nextJob
        nextJob.invokeOnCompletion {
            if (saveJob === nextJob) saveJob = null
        }
    }

    private fun finishWithoutSaving(current: RoomProfileEditorState) {
        val target = current.target ?: return
        cancelSave()
        driver.deleteDraft(current.editAvatarLocalPath)
        _state.value = RoomProfileEditorState()
        onEditFinished(target, false)
    }

    private fun beginSave(): Long {
        cancelSave()
        return generation
    }

    private fun cancelSave() {
        generation += 1
        saveJob?.cancel()
        saveJob = null
    }

    private fun isCurrent(
        target: RoomProfileEditorTarget,
        editSessionId: Long,
        requestGeneration: Long
    ): Boolean {
        val current = _state.value
        return generation == requestGeneration &&
            current.target == target &&
            current.editSessionId == editSessionId
    }

    private fun RoomProfileEditorTarget.normalizedOrNull(): RoomProfileEditorTarget? {
        val userId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val roomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        return RoomProfileEditorTarget(userId = userId, roomId = roomId)
    }
}

internal fun createRoomProfileEditorStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onEditFinished: (target: RoomProfileEditorTarget, didSave: Boolean) -> Unit,
    onWarning: (String, Throwable) -> Unit
): RoomProfileEditorStore {
    return RoomProfileEditorStore(
        scope = scope,
        driver = RoomProfileEditorDriver(
            loadCapabilities = matrixClientService::loadRoomCapabilities,
            setName = matrixClientService::setRoomName,
            setTopic = matrixClientService::setRoomTopic,
            uploadAvatar = matrixClientService::uploadRoomAvatar,
            removeAvatar = matrixClientService::removeRoomAvatar,
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

private const val DEFAULT_ROOM_AVATAR_MIME_TYPE = "image/jpeg"
