package com.zyna.app.ui.createroom

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixGroupAccess
import com.zyna.app.data.matrix.MatrixGroupCreationRequest
import com.zyna.app.data.matrix.MatrixGroupPostingPermission
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.profile.ProfileAvatarDraft
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateRoomTarget(
    val userId: String
) {
    val serverName: String?
        get() = userId.substringAfter(':', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
}

enum class CreateRoomAccess {
    PRIVATE,
    PUBLIC
}

enum class CreateRoomPostingPermission {
    ALL_MEMBERS,
    MODERATORS_ONLY
}

enum class CreateRoomAliasAvailability {
    NOT_REQUIRED,
    CHECKING,
    AVAILABLE,
    TAKEN,
    INVALID,
    ERROR
}

enum class CreateRoomError {
    AVATAR_PREPARATION,
    AVATAR_UPLOAD,
    ADDRESS_CHECK,
    CREATE
}

data class CreateRoomState(
    val target: CreateRoomTarget? = null,
    val name: String = "",
    val topic: String = "",
    val access: CreateRoomAccess = CreateRoomAccess.PRIVATE,
    val postingPermission: CreateRoomPostingPermission =
        CreateRoomPostingPermission.ALL_MEMBERS,
    val aliasLocalPart: String = "",
    val isAliasUserEdited: Boolean = false,
    val aliasAvailability: CreateRoomAliasAvailability =
        CreateRoomAliasAvailability.NOT_REQUIRED,
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

    val fullAlias: String?
        get() {
            if (access != CreateRoomAccess.PUBLIC) return null
            val serverName = target?.serverName ?: return null
            val localPart = aliasLocalPart.takeIf { it.isNotBlank() } ?: return null
            return "#$localPart:$serverName"
        }

    val hasUnsavedChanges: Boolean
        get() = editSessionId != 0L && (
            name.isNotEmpty() ||
                topic.isNotEmpty() ||
                hasAvatar ||
                access != CreateRoomAccess.PRIVATE ||
                postingPermission != CreateRoomPostingPermission.ALL_MEMBERS ||
                isAliasUserEdited
            )

    val canCreate: Boolean
        get() = editSessionId != 0L &&
            !isCreating &&
            name.isNotBlank() &&
            (
                access == CreateRoomAccess.PRIVATE ||
                    aliasAvailability == CreateRoomAliasAvailability.AVAILABLE
                )
}

internal class CreateRoomDriver(
    val uploadMedia: suspend (localPath: String, mimeType: String) -> String,
    val suggestAliasLocalPart: (name: String) -> String,
    val isAliasValid: (fullAlias: String) -> Boolean,
    val isAliasAvailable: suspend (fullAlias: String) -> Boolean,
    val createGroup: suspend (request: MatrixGroupCreationRequest) -> MatrixRoomSummary,
    val cacheCreatedRoom: suspend (userId: String, room: MatrixRoomSummary) -> Unit,
    val deleteDraft: (String?) -> Unit
)

/**
 * Owns one route-scoped group creation session.
 *
 * Alias checks are debounced and generation-guarded, so a late response cannot validate a newer
 * address or another session. The address is checked once more immediately before upload/create.
 * Avatar upload results are retained across create retries. Once Matrix returns a room ID, local
 * cache persistence is best-effort because the create mutation cannot be rolled back.
 */
internal class CreateRoomStore(
    private val scope: CoroutineScope,
    private val driver: CreateRoomDriver,
    private val onCreated: (target: CreateRoomTarget, room: MatrixRoomSummary) -> Unit,
    private val onCancelled: (target: CreateRoomTarget) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> },
    private val aliasCheckDebounceMillis: Long = ALIAS_CHECK_DEBOUNCE_MILLIS
) {
    private val _state = MutableStateFlow(CreateRoomState())
    val state: StateFlow<CreateRoomState> = _state.asStateFlow()

    private var generation = 0L
    private var aliasGeneration = 0L
    private var editSessionCounter = 0L
    private var createJob: Job? = null
    private var aliasCheckJob: Job? = null

    @MainThread
    fun begin(target: CreateRoomTarget) {
        val normalizedTarget = target.normalizedOrNull() ?: return
        cancelCreate()
        cancelAliasCheck()
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
        cancelAliasCheck()
        val draft = _state.value.avatarLocalPath
        _state.value = CreateRoomState()
        driver.deleteDraft(draft)
    }

    @MainThread
    fun setName(name: String) {
        val current = editableStateOrNull() ?: return
        val nextAlias = if (
            current.access == CreateRoomAccess.PUBLIC && !current.isAliasUserEdited
        ) {
            driver.suggestAliasLocalPart(name)
        } else {
            current.aliasLocalPart
        }
        val next = current.copy(
            name = name,
            aliasLocalPart = nextAlias,
            error = null
        )
        if (
            current.access == CreateRoomAccess.PUBLIC &&
            nextAlias != current.aliasLocalPart
        ) {
            publishDraft(next)
        } else {
            _state.value = next
        }
    }

    @MainThread
    fun setTopic(topic: String) {
        val current = editableStateOrNull() ?: return
        _state.value = current.copy(topic = topic, error = null)
    }

    @MainThread
    fun setAccess(access: CreateRoomAccess) {
        val current = editableStateOrNull() ?: return
        if (current.access == access) return
        val nextAlias = if (
            access == CreateRoomAccess.PUBLIC &&
            !current.isAliasUserEdited
        ) {
            driver.suggestAliasLocalPart(current.name)
        } else {
            current.aliasLocalPart
        }
        publishDraft(
            current.copy(
                access = access,
                aliasLocalPart = nextAlias,
                aliasAvailability = CreateRoomAliasAvailability.NOT_REQUIRED,
                error = null
            )
        )
    }

    @MainThread
    fun setPostingPermission(permission: CreateRoomPostingPermission) {
        val current = editableStateOrNull() ?: return
        _state.value = current.copy(postingPermission = permission, error = null)
    }

    @MainThread
    fun setAliasLocalPart(value: String) {
        val current = editableStateOrNull() ?: return
        if (current.access != CreateRoomAccess.PUBLIC) return
        publishDraft(
            current.copy(
                aliasLocalPart = normalizeAliasInput(value, current.target?.serverName),
                isAliasUserEdited = true,
                error = null
            )
        )
    }

    @MainThread
    fun retryAliasCheck() {
        val current = editableStateOrNull() ?: return
        if (current.access != CreateRoomAccess.PUBLIC) return
        publishDraft(current.copy(error = null), debounce = false)
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
        val current = editableStateOrNull() ?: return
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

        val alias = current.fullAlias
        val avatarPath = current.avatarLocalPath
        val requestGeneration = beginCreate()
        _state.value = current.copy(
            isCreating = true,
            error = null,
            isDiscardConfirmationVisible = false
        )

        val nextJob = scope.launch {
            var stage = CreateRoomStage.CHECK_ADDRESS
            try {
                if (current.access == CreateRoomAccess.PUBLIC) {
                    checkNotNull(alias)
                    if (!driver.isAliasValid(alias) || !driver.isAliasAvailable(alias)) {
                        if (!isCurrent(target, current.editSessionId, requestGeneration)) {
                            return@launch
                        }
                        _state.value = _state.value.copy(
                            isCreating = false,
                            aliasAvailability = CreateRoomAliasAvailability.TAKEN
                        )
                        return@launch
                    }
                }

                stage = CreateRoomStage.UPLOAD_AVATAR
                val avatarUrl = current.uploadedAvatarUrl ?: avatarPath?.let { path ->
                    val uploadedUrl = driver.uploadMedia(path, current.avatarMimeType)
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) {
                        return@launch
                    }
                    _state.value = _state.value.copy(uploadedAvatarUrl = uploadedUrl)
                    uploadedUrl
                }

                stage = CreateRoomStage.CREATE_ROOM
                val room = driver.createGroup(
                    MatrixGroupCreationRequest(
                        name = current.name.trim(),
                        topic = current.topic.trim().takeIf { it.isNotEmpty() },
                        avatarUrl = avatarUrl,
                        access = current.access.toMatrixAccess(),
                        aliasLocalPart = if (current.access == CreateRoomAccess.PUBLIC) {
                            current.aliasLocalPart
                        } else {
                            null
                        },
                        postingPermission = current.postingPermission.toMatrixPermission()
                    )
                )
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
                    aliasAvailability = if (stage == CreateRoomStage.CHECK_ADDRESS) {
                        CreateRoomAliasAvailability.ERROR
                    } else {
                        _state.value.aliasAvailability
                    },
                    error = when (stage) {
                        CreateRoomStage.CHECK_ADDRESS -> CreateRoomError.ADDRESS_CHECK
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

    private fun publishDraft(next: CreateRoomState, debounce: Boolean = true) {
        cancelAliasCheck()
        if (next.access != CreateRoomAccess.PUBLIC) {
            _state.value = next.copy(
                aliasAvailability = CreateRoomAliasAvailability.NOT_REQUIRED
            )
            return
        }

        val fullAlias = next.fullAlias
        if (fullAlias == null) {
            _state.value = next.copy(aliasAvailability = CreateRoomAliasAvailability.INVALID)
            return
        }

        val target = next.target ?: return
        val editSessionId = next.editSessionId
        val requestGeneration = aliasGeneration
        _state.value = next.copy(aliasAvailability = CreateRoomAliasAvailability.CHECKING)
        val nextJob = scope.launch {
            if (debounce) delay(aliasCheckDebounceMillis)
            val result = runCatching {
                if (!driver.isAliasValid(fullAlias)) {
                    CreateRoomAliasAvailability.INVALID
                } else if (driver.isAliasAvailable(fullAlias)) {
                    CreateRoomAliasAvailability.AVAILABLE
                } else {
                    CreateRoomAliasAvailability.TAKEN
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                onWarning("Failed to check room address", error)
                CreateRoomAliasAvailability.ERROR
            }
            if (!isCurrentAlias(target, editSessionId, fullAlias, requestGeneration)) {
                return@launch
            }
            _state.value = _state.value.copy(aliasAvailability = result)
        }
        aliasCheckJob = nextJob
        nextJob.invokeOnCompletion {
            if (aliasCheckJob === nextJob) aliasCheckJob = null
        }
    }

    private fun finishWithoutCreating(current: CreateRoomState) {
        val target = current.target ?: return
        cancelCreate()
        cancelAliasCheck()
        driver.deleteDraft(current.avatarLocalPath)
        _state.value = CreateRoomState()
        onCancelled(target)
    }

    private fun beginCreate(): Long {
        cancelCreate()
        cancelAliasCheck()
        return generation
    }

    private fun cancelCreate() {
        generation += 1
        createJob?.cancel()
        createJob = null
    }

    private fun cancelAliasCheck() {
        aliasGeneration += 1
        aliasCheckJob?.cancel()
        aliasCheckJob = null
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

    private fun isCurrentAlias(
        target: CreateRoomTarget,
        editSessionId: Long,
        fullAlias: String,
        requestGeneration: Long
    ): Boolean {
        val current = _state.value
        return aliasGeneration == requestGeneration &&
            current.target == target &&
            current.editSessionId == editSessionId &&
            current.fullAlias == fullAlias
    }

    private fun editableStateOrNull(): CreateRoomState? {
        return _state.value.takeIf { it.editSessionId != 0L && !it.isCreating }
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
            suggestAliasLocalPart = matrixClientService::suggestRoomAliasLocalPart,
            isAliasValid = matrixClientService::isRoomAliasValid,
            isAliasAvailable = matrixClientService::isRoomAliasAvailable,
            createGroup = matrixClientService::createGroup,
            cacheCreatedRoom = localCacheRepository::cacheCreatedRoomSummary,
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

private fun normalizeAliasInput(value: String, serverName: String?): String {
    var normalized = value.trim().lowercase(Locale.ROOT).removePrefix("#")
    val suffix = serverName?.let { ":$it" }
    if (suffix != null && normalized.endsWith(suffix, ignoreCase = true)) {
        normalized = normalized.dropLast(suffix.length)
    } else {
        normalized = normalized.substringBefore(':')
    }
    return normalized
}

private fun CreateRoomAccess.toMatrixAccess(): MatrixGroupAccess {
    return when (this) {
        CreateRoomAccess.PRIVATE -> MatrixGroupAccess.PRIVATE
        CreateRoomAccess.PUBLIC -> MatrixGroupAccess.PUBLIC
    }
}

private fun CreateRoomPostingPermission.toMatrixPermission(): MatrixGroupPostingPermission {
    return when (this) {
        CreateRoomPostingPermission.ALL_MEMBERS -> MatrixGroupPostingPermission.ALL_MEMBERS
        CreateRoomPostingPermission.MODERATORS_ONLY ->
            MatrixGroupPostingPermission.MODERATORS_ONLY
    }
}

private enum class CreateRoomStage {
    CHECK_ADDRESS,
    UPLOAD_AVATAR,
    CREATE_ROOM
}

private const val DEFAULT_ROOM_AVATAR_MIME_TYPE = "image/jpeg"
private const val ALIAS_CHECK_DEBOUNCE_MILLIS = 350L
