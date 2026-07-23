package com.zyna.app.ui.createroom

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.profile.ProfileAvatarDraft
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateRoomTarget(
    val userId: String
)

enum class CreateRoomError {
    AVATAR_PREPARATION,
    AVATAR_UPLOAD,
    CREATE
}

data class CreateRoomState(
    val target: CreateRoomTarget? = null,
    val name: String = "",
    val avatarLocalPath: String? = null,
    val avatarMimeType: String = DEFAULT_ROOM_AVATAR_MIME_TYPE,
    val uploadedAvatarUrl: String? = null,
    val editSessionId: Long = 0L,
    val isCreating: Boolean = false,
    val error: CreateRoomError? = null,
    val isDiscardConfirmationVisible: Boolean = false
) {
    val hasAvatar: Boolean
        get() = avatarLocalPath != null

    val hasUnsavedChanges: Boolean
        get() = editSessionId != 0L && (name.isNotEmpty() || hasAvatar)

    val canCreate: Boolean
        get() = editSessionId != 0L && !isCreating && name.isNotBlank()
}

internal class CreateRoomDriver(
    val uploadMedia: suspend (localPath: String, mimeType: String) -> String,
    val createPrivateGroup: suspend (name: String, avatarUrl: String?) -> MatrixRoomSummary,
    val cacheCreatedRoom: suspend (userId: String, room: MatrixRoomSummary) -> Unit,
    val deleteDraft: (String?) -> Unit
)

/**
 * Owns one route-scoped group creation session.
 *
 * Avatar media is uploaded before the room is created so the room appears atomically with its
 * final name and avatar. A successful upload is retained across create retries, avoiding duplicate
 * media uploads. Once Matrix returns a room ID, cache persistence is best-effort: navigation must
 * not pretend that an already-created room failed because a local database write did.
 */
internal class CreateRoomStore(
    private val scope: CoroutineScope,
    private val driver: CreateRoomDriver,
    private val onCreated: (target: CreateRoomTarget, room: MatrixRoomSummary) -> Unit,
    private val onCancelled: (target: CreateRoomTarget) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(CreateRoomState())
    val state: StateFlow<CreateRoomState> = _state.asStateFlow()

    private var generation = 0L
    private var editSessionCounter = 0L
    private var createJob: Job? = null

    @MainThread
    fun begin(target: CreateRoomTarget) {
        val normalizedTarget = target.normalizedOrNull() ?: return
        cancelCreate()
        val previousDraft = _state.value.avatarLocalPath
        editSessionCounter += 1
        _state.value = CreateRoomState(
            target = normalizedTarget,
            editSessionId = editSessionCounter
        )
        driver.deleteDraft(previousDraft)
    }

    @MainThread
    fun deactivate() {
        cancelCreate()
        val draft = _state.value.avatarLocalPath
        _state.value = CreateRoomState()
        driver.deleteDraft(draft)
    }

    @MainThread
    fun setName(name: String) {
        val current = _state.value
        if (current.editSessionId == 0L || current.isCreating) return
        _state.value = current.copy(name = name, error = null)
    }

    @MainThread
    fun setAvatarDraft(
        draft: ProfileAvatarDraft,
        target: CreateRoomTarget,
        editSessionId: Long
    ) {
        val current = _state.value
        if (
            current.target != target ||
            current.editSessionId == 0L ||
            current.editSessionId != editSessionId ||
            current.isCreating
        ) {
            driver.deleteDraft(draft.localPath)
            return
        }
        _state.value = current.copy(
            avatarLocalPath = draft.localPath,
            avatarMimeType = draft.mimeType,
            uploadedAvatarUrl = null,
            error = null
        )
        if (current.avatarLocalPath != draft.localPath) {
            driver.deleteDraft(current.avatarLocalPath)
        }
    }

    @MainThread
    fun discardAvatarDraft(draft: ProfileAvatarDraft) {
        driver.deleteDraft(draft.localPath)
    }

    @MainThread
    fun setAvatarPreparationError(target: CreateRoomTarget, editSessionId: Long) {
        val current = _state.value
        if (
            current.target != target ||
            current.editSessionId != editSessionId ||
            current.isCreating
        ) return
        _state.value = current.copy(error = CreateRoomError.AVATAR_PREPARATION)
    }

    @MainThread
    fun removeAvatar() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isCreating) return
        _state.value = current.copy(
            avatarLocalPath = null,
            avatarMimeType = DEFAULT_ROOM_AVATAR_MIME_TYPE,
            uploadedAvatarUrl = null,
            error = null
        )
        driver.deleteDraft(current.avatarLocalPath)
    }

    @MainThread
    fun requestExit() {
        val current = _state.value
        if (current.editSessionId == 0L || current.isCreating) return
        if (current.hasUnsavedChanges) {
            _state.value = current.copy(isDiscardConfirmationVisible = true)
        } else {
            finishWithoutCreating(current)
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
        if (current.editSessionId == 0L || current.isCreating) return
        finishWithoutCreating(current)
    }

    @MainThread
    fun create() {
        val current = _state.value
        val target = current.target ?: return
        if (!current.canCreate) return

        val normalizedName = current.name.trim()
        val avatarPath = current.avatarLocalPath
        val avatarMimeType = current.avatarMimeType
        val requestGeneration = beginCreate()
        _state.value = current.copy(
            isCreating = true,
            error = null,
            isDiscardConfirmationVisible = false
        )

        val nextJob = scope.launch {
            var stage = CreateRoomStage.UPLOAD_AVATAR
            try {
                val avatarUrl = current.uploadedAvatarUrl ?: avatarPath?.let { path ->
                    val uploadedUrl = driver.uploadMedia(path, avatarMimeType)
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) {
                        return@launch
                    }
                    _state.value = _state.value.copy(uploadedAvatarUrl = uploadedUrl)
                    uploadedUrl
                }

                stage = CreateRoomStage.CREATE_ROOM
                val room = driver.createPrivateGroup(normalizedName, avatarUrl)
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch

                runCatching { driver.cacheCreatedRoom(target.userId, room) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        onWarning("Failed to cache newly created room", error)
                    }
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch

                driver.deleteDraft(avatarPath)
                _state.value = CreateRoomState()
                onCreated(target, room)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                onWarning("Failed to create group", error)
                _state.value = _state.value.copy(
                    isCreating = false,
                    error = when (stage) {
                        CreateRoomStage.UPLOAD_AVATAR -> CreateRoomError.AVATAR_UPLOAD
                        CreateRoomStage.CREATE_ROOM -> CreateRoomError.CREATE
                    }
                )
            }
        }
        createJob = nextJob
        nextJob.invokeOnCompletion {
            if (createJob === nextJob) createJob = null
        }
    }

    private fun finishWithoutCreating(current: CreateRoomState) {
        val target = current.target ?: return
        cancelCreate()
        driver.deleteDraft(current.avatarLocalPath)
        _state.value = CreateRoomState()
        onCancelled(target)
    }

    private fun beginCreate(): Long {
        cancelCreate()
        return generation
    }

    private fun cancelCreate() {
        generation += 1
        createJob?.cancel()
        createJob = null
    }

    private fun isCurrent(
        target: CreateRoomTarget,
        editSessionId: Long,
        requestGeneration: Long
    ): Boolean {
        val current = _state.value
        return generation == requestGeneration &&
            current.target == target &&
            current.editSessionId == editSessionId
    }

    private fun CreateRoomTarget.normalizedOrNull(): CreateRoomTarget? {
        val userId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        return CreateRoomTarget(userId)
    }
}

internal fun createCreateRoomStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    onCreated: (target: CreateRoomTarget, room: MatrixRoomSummary) -> Unit,
    onCancelled: (target: CreateRoomTarget) -> Unit,
    onWarning: (String, Throwable) -> Unit
): CreateRoomStore {
    return CreateRoomStore(
        scope = scope,
        driver = CreateRoomDriver(
            uploadMedia = matrixClientService::uploadMedia,
            createPrivateGroup = matrixClientService::createPrivateGroup,
            cacheCreatedRoom = localCacheRepository::cacheRoomSummary,
            deleteDraft = { path ->
                path?.takeIf { it.isNotBlank() }?.let { localPath ->
                    runCatching { File(localPath).delete() }
                }
            }
        ),
        onCreated = onCreated,
        onCancelled = onCancelled,
        onWarning = onWarning
    )
}

private enum class CreateRoomStage {
    UPLOAD_AVATAR,
    CREATE_ROOM
}

private const val DEFAULT_ROOM_AVATAR_MIME_TYPE = "image/jpeg"
