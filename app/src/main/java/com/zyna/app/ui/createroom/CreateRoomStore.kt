package com.zyna.app.ui.createroom

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixRoomCreationAccess
import com.zyna.app.data.matrix.MatrixRoomCreationKind
import com.zyna.app.data.matrix.MatrixRoomCreationRequest
import com.zyna.app.data.matrix.MatrixRoomPostingPermission
import com.zyna.app.data.matrix.MatrixSpaceService
import com.zyna.app.data.matrix.toJoinedSpaceChild
import com.zyna.app.data.matrix.toSpaceRoom
import com.zyna.app.data.profile.ProfileAvatarDraft
import com.zyna.app.ui.spaces.SpaceChildrenStore
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
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind

enum class CreateRoomMode {
    GROUP,
    STORYLINE,
    TRACK
}

enum class CreateRoomPresentation {
    GROUP,
    CHILD_CHAT,
    STORYLINE,
    TRACK
}

data class CreateRoomParent(
    val spaceId: String,
    val displayName: String
)

data class CreateRoomTarget(
    val userId: String,
    val mode: CreateRoomMode = CreateRoomMode.GROUP,
    val parent: CreateRoomParent? = null
) {
    val isChildChat: Boolean
        get() = mode == CreateRoomMode.GROUP && parent != null

    val presentation: CreateRoomPresentation
        get() = when {
            isChildChat -> CreateRoomPresentation.CHILD_CHAT
            mode == CreateRoomMode.GROUP -> CreateRoomPresentation.GROUP
            mode == CreateRoomMode.STORYLINE -> CreateRoomPresentation.STORYLINE
            else -> CreateRoomPresentation.TRACK
        }

    val serverName: String?
        get() = userId.substringAfter(':', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }

    val defaultAccess: CreateRoomAccess
        get() = if (parent == null) CreateRoomAccess.PRIVATE else CreateRoomAccess.PARENT_MEMBERS

    val supportsPostingPermissions: Boolean
        get() = mode == CreateRoomMode.GROUP
}

enum class CreateRoomAccess {
    PRIVATE,
    PARENT_MEMBERS,
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
    PARENT_PERMISSION_CHECK,
    PERMISSION_CHANGED,
    RESTRICTED_ACCESS_UNSUPPORTED,
    CREATE,
    ADD_TO_PARENT
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
    /** Set after createRoom succeeds so a link retry never creates a duplicate room. */
    val pendingCreatedRoom: MatrixRoomSummary? = null,
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
            pendingCreatedRoom != null ||
                name.isNotEmpty() ||
                topic.isNotEmpty() ||
                hasAvatar ||
                access != (target?.defaultAccess ?: CreateRoomAccess.PRIVATE) ||
                (
                    target?.supportsPostingPermissions == true &&
                        postingPermission != CreateRoomPostingPermission.ALL_MEMBERS
                    ) ||
                isAliasUserEdited
            )

    val canCreate: Boolean
        get() = editSessionId != 0L &&
            !isCreating &&
            (
                pendingCreatedRoom != null ||
                    (
                        name.isNotBlank() &&
                            (
                                access != CreateRoomAccess.PUBLIC ||
                                    aliasAvailability == CreateRoomAliasAvailability.AVAILABLE
                                )
                        )
                )

    val canRetryParentLink: Boolean
        get() = pendingCreatedRoom != null && !isCreating
}

internal class CreateRoomDriver(
    val uploadMedia: suspend (localPath: String, mimeType: String) -> String,
    val suggestAliasLocalPart: (name: String) -> String,
    val isAliasValid: (fullAlias: String) -> Boolean,
    val isAliasAvailable: suspend (fullAlias: String) -> Boolean,
    val canManageParent: suspend (userId: String, parentSpaceId: String) -> Boolean,
    val createRoom: suspend (request: MatrixRoomCreationRequest) -> MatrixRoomSummary,
    val cacheCreatedRoom: suspend (userId: String, room: MatrixRoomSummary) -> Unit,
    val awaitChildReadyForParentLink: suspend (
        userId: String,
        childRoomId: String
    ) -> Unit,
    val addChildToParent: suspend (
        userId: String,
        parentSpaceId: String,
        childRoomId: String
    ) -> Unit,
    val confirmChildAdded: suspend (
        target: CreateRoomTarget,
        room: MatrixRoomSummary
    ) -> Unit,
    val reconcileChildAdded: suspend (
        target: CreateRoomTarget,
        childRoomId: String
    ) -> Boolean?,
    val deleteDraft: (String?) -> Unit
)

/**
 * Owns one route-scoped Matrix room or Space creation session.
 *
 * Alias checks are debounced and generation-guarded, so a late response cannot validate a newer
 * address or another session. The address is checked once more immediately before upload/create.
 * Avatar upload results are retained across create retries. Once Matrix returns a room ID, local
 * cache persistence is best-effort because the create mutation cannot be rolled back. Child
 * creation also retains the created room while its parent link is unresolved, so retrying can only
 * repeat the m.space.child write and can never create a duplicate.
 */
internal class CreateRoomStore(
    private val scope: CoroutineScope,
    private val driver: CreateRoomDriver,
    private val onCreated: (
        target: CreateRoomTarget,
        room: MatrixRoomSummary,
        access: CreateRoomAccess
    ) -> Unit,
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
    fun begin(target: CreateRoomTarget): Boolean {
        val normalizedTarget = target.normalizedOrNull()
        if (normalizedTarget == null) {
            onWarning(
                "Rejected invalid Matrix room creation target",
                IllegalArgumentException(target.validationFailureDescription())
            )
            return false
        }
        cancelCreate()
        cancelAliasCheck()
        val previousDraft = _state.value.avatarLocalPath
        editSessionCounter += 1
        _state.value = CreateRoomState(
            target = normalizedTarget,
            access = normalizedTarget.defaultAccess,
            editSessionId = editSessionCounter
        )
        driver.deleteDraft(previousDraft)
        return true
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
        if (access == CreateRoomAccess.PARENT_MEMBERS && current.target?.parent == null) return
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
        if (current.target?.supportsPostingPermissions != true) return
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
            current.isCreating ||
            current.pendingCreatedRoom != null
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
            current.isCreating ||
            current.pendingCreatedRoom != null
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
                var room = current.pendingCreatedRoom
                if (room == null) {
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

                    if (target.parent != null) {
                        stage = CreateRoomStage.CHECK_PARENT_PERMISSION
                        if (!checkParentPermission(
                                target,
                                current.editSessionId,
                                requestGeneration
                            )
                        ) {
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
                    room = driver.createRoom(
                        MatrixRoomCreationRequest(
                            name = current.name.trim(),
                            topic = current.topic.trim().takeIf { it.isNotEmpty() },
                            avatarUrl = avatarUrl,
                            kind = target.mode.toMatrixKind(),
                            access = current.access.toMatrixAccess(target.parent),
                            aliasLocalPart = if (current.access == CreateRoomAccess.PUBLIC) {
                                current.aliasLocalPart
                            } else {
                                null
                            },
                            postingPermission = current.postingPermission.toMatrixPermission()
                        )
                    )
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                    _state.value = _state.value.copy(pendingCreatedRoom = room)

                    runCatching { driver.cacheCreatedRoom(target.userId, room) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            onWarning("Failed to cache newly created room", error)
                        }
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                }

                val createdRoom = requireNotNull(room)
                val parent = target.parent
                if (parent != null) {
                    stage = CreateRoomStage.WAIT_FOR_CHILD_ROOM
                    driver.awaitChildReadyForParentLink(target.userId, createdRoom.id)
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) {
                        return@launch
                    }
                    stage = CreateRoomStage.CHECK_PARENT_PERMISSION
                    if (!checkParentPermission(target, current.editSessionId, requestGeneration)) {
                        return@launch
                    }
                    stage = CreateRoomStage.ADD_TO_PARENT
                    attachCreatedRoom(target, createdRoom)
                    if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                }

                driver.deleteDraft(avatarPath)
                _state.value = CreateRoomState()
                onCreated(target, createdRoom, current.access)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, current.editSessionId, requestGeneration)) return@launch
                onWarning(stage.warningMessage, error)
                _state.value = _state.value.copy(
                    isCreating = false,
                    aliasAvailability = if (stage == CreateRoomStage.CHECK_ADDRESS) {
                        CreateRoomAliasAvailability.ERROR
                    } else {
                        _state.value.aliasAvailability
                    },
                    error = when (stage) {
                        CreateRoomStage.CHECK_ADDRESS -> CreateRoomError.ADDRESS_CHECK
                        CreateRoomStage.CHECK_PARENT_PERMISSION ->
                            CreateRoomError.PARENT_PERMISSION_CHECK
                        CreateRoomStage.UPLOAD_AVATAR -> CreateRoomError.AVATAR_UPLOAD
                        CreateRoomStage.CREATE_ROOM -> if (
                            current.access == CreateRoomAccess.PARENT_MEMBERS &&
                            error.isUnsupportedRoomVersion()
                        ) {
                            CreateRoomError.RESTRICTED_ACCESS_UNSUPPORTED
                        } else {
                            CreateRoomError.CREATE
                        }
                        CreateRoomStage.WAIT_FOR_CHILD_ROOM -> CreateRoomError.ADD_TO_PARENT
                        CreateRoomStage.ADD_TO_PARENT -> CreateRoomError.ADD_TO_PARENT
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
        return _state.value.takeIf {
            it.editSessionId != 0L && !it.isCreating && it.pendingCreatedRoom == null
        }
    }

    private suspend fun checkParentPermission(
        target: CreateRoomTarget,
        editSessionId: Long,
        requestGeneration: Long
    ): Boolean {
        val parent = target.parent ?: return true
        val canManage = driver.canManageParent(target.userId, parent.spaceId)
        if (!isCurrent(target, editSessionId, requestGeneration)) return false
        if (canManage) return true
        _state.value = _state.value.copy(
            isCreating = false,
            error = CreateRoomError.PERMISSION_CHANGED
        )
        return false
    }

    private suspend fun attachCreatedRoom(
        target: CreateRoomTarget,
        room: MatrixRoomSummary
    ) {
        val parent = requireNotNull(target.parent)
        try {
            driver.addChildToParent(target.userId, parent.spaceId, room.id)
        } catch (error: CancellationException) {
            throw error
        } catch (writeError: Throwable) {
            val wasApplied = try {
                driver.reconcileChildAdded(target, room.id) == true
            } catch (error: CancellationException) {
                throw error
            } catch (refreshError: Throwable) {
                writeError.addSuppressed(refreshError)
                false
            }
            if (!wasApplied) throw writeError
            onWarning("Space child write reported failure but hierarchy confirmed it", writeError)
        }
        driver.confirmChildAdded(target, room)
    }

    private fun CreateRoomTarget.normalizedOrNull(): CreateRoomTarget? {
        val userId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedParent = parent?.let { value ->
            val spaceId = value.spaceId.trim().takeIf(String::isNotEmpty) ?: return null
            value.copy(spaceId = spaceId, displayName = value.displayName.trim())
        }
        when (mode) {
            CreateRoomMode.GROUP -> Unit
            CreateRoomMode.STORYLINE -> if (normalizedParent != null) return null
            CreateRoomMode.TRACK -> if (normalizedParent == null) return null
        }
        return copy(userId = userId, parent = normalizedParent)
    }

    private fun CreateRoomTarget.validationFailureDescription(): String {
        return "Invalid creation target: mode=$mode, " +
            "userIdPresent=${userId.isNotBlank()}, " +
            "parentPresent=${parent != null}, " +
            "parentIdPresent=${parent?.spaceId?.isNotBlank() == true}"
    }
}

internal fun createCreateRoomStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    matrixSpaceService: MatrixSpaceService,
    localCacheRepository: LocalCacheRepository,
    spaceChildrenStore: SpaceChildrenStore,
    onCreated: (
        target: CreateRoomTarget,
        room: MatrixRoomSummary,
        access: CreateRoomAccess
    ) -> Unit,
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
            canManageParent = { _, parentSpaceId ->
                matrixClientService.canManageSpaceChildren(parentSpaceId)
            },
            createRoom = matrixClientService::createRoom,
            cacheCreatedRoom = localCacheRepository::cacheCreatedRoomSummary,
            awaitChildReadyForParentLink =
                matrixClientService::awaitRoomReadyForSpaceRelationship,
            addChildToParent = matrixSpaceService::addChildToSpace,
            confirmChildAdded = { target, room ->
                val child = if (room.isSpace) room.toSpaceRoom() else room.toJoinedSpaceChild()
                spaceChildrenStore.confirmChildrenAdded(
                    userId = target.userId,
                    spaceId = requireNotNull(target.parent).spaceId,
                    rooms = listOf(child)
                )
            },
            reconcileChildAdded = { target, childRoomId ->
                spaceChildrenStore.refreshAfterChildMutation(
                    userId = target.userId,
                    spaceId = requireNotNull(target.parent).spaceId,
                    roomIdsToFind = setOf(childRoomId)
                )?.contains(childRoomId)
            },
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

private fun CreateRoomMode.toMatrixKind(): MatrixRoomCreationKind {
    return when (this) {
        CreateRoomMode.GROUP -> MatrixRoomCreationKind.ROOM
        CreateRoomMode.STORYLINE,
        CreateRoomMode.TRACK -> MatrixRoomCreationKind.SPACE
    }
}

private fun CreateRoomAccess.toMatrixAccess(
    parent: CreateRoomParent?
): MatrixRoomCreationAccess {
    return when (this) {
        CreateRoomAccess.PRIVATE -> MatrixRoomCreationAccess.Private
        CreateRoomAccess.PUBLIC -> MatrixRoomCreationAccess.Public
        CreateRoomAccess.PARENT_MEMBERS -> MatrixRoomCreationAccess.Restricted(
            parentSpaceId = requireNotNull(parent).spaceId
        )
    }
}

private fun CreateRoomPostingPermission.toMatrixPermission(): MatrixRoomPostingPermission {
    return when (this) {
        CreateRoomPostingPermission.ALL_MEMBERS -> MatrixRoomPostingPermission.ALL_MEMBERS
        CreateRoomPostingPermission.MODERATORS_ONLY ->
            MatrixRoomPostingPermission.MODERATORS_ONLY
    }
}

private fun Throwable.isUnsupportedRoomVersion(): Boolean {
    val kind = (this as? ClientException.MatrixApi)?.kind ?: return false
    return kind is ErrorKind.IncompatibleRoomVersion || kind == ErrorKind.UnsupportedRoomVersion
}

private enum class CreateRoomStage {
    CHECK_ADDRESS,
    CHECK_PARENT_PERMISSION,
    UPLOAD_AVATAR,
    CREATE_ROOM,
    WAIT_FOR_CHILD_ROOM,
    ADD_TO_PARENT;

    val warningMessage: String
        get() = when (this) {
            CHECK_ADDRESS -> "Failed to check Matrix room address"
            CHECK_PARENT_PERMISSION -> "Failed to check Space child management permission"
            UPLOAD_AVATAR -> "Failed to upload Matrix room avatar"
            CREATE_ROOM -> "Failed to create Matrix room"
            WAIT_FOR_CHILD_ROOM -> "Created room did not become ready for its parent Space"
            ADD_TO_PARENT -> "Failed to add newly created room to its parent Space"
        }
}

private const val DEFAULT_ROOM_AVATAR_MIME_TYPE = "image/jpeg"
private const val ALIAS_CHECK_DEBOUNCE_MILLIS = 350L
