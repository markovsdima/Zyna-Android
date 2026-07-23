package com.zyna.app.ui.invitemembers

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixUserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InviteMembersTarget(
    val userId: String,
    val roomId: String
)

data class InviteMemberCandidate(
    val profile: MatrixUserProfile,
    val membership: MatrixRoomMemberMembership?
)

data class InviteMembersState(
    val target: InviteMembersTarget? = null,
    val searchQuery: String = "",
    val selectedMembers: List<InviteMemberCandidate> = emptyList(),
    val searchResults: List<InviteMemberCandidate> = emptyList(),
    val canInviteMembers: Boolean = false,
    val isPreparing: Boolean = false,
    val isSearching: Boolean = false,
    val isSending: Boolean = false,
    val preparationErrorMessage: String? = null,
    val permissionErrorMessage: String? = null,
    val searchErrorMessage: String? = null,
    val sendErrorMessage: String? = null,
    val failedInviteCount: Int = 0,
    val permissionDenied: Boolean = false
) {
    val canSubmit: Boolean
        get() = canInviteMembers &&
            !isPreparing &&
            !isSending &&
            selectedMembers.isNotEmpty()
}

internal enum class InviteMembersSource {
    CACHE,
    SERVER
}

internal enum class InviteMembersActivationMode {
    EXISTING_ROOM,
    NEWLY_CREATED_ROOM
}

internal class InviteMembersDriver(
    val canInviteMembers: suspend (roomId: String) -> Boolean,
    val loadMembers: suspend (
        roomId: String,
        source: InviteMembersSource
    ) -> List<MatrixRoomMember>,
    val searchUsers: suspend (searchTerm: String, limit: Int) -> List<MatrixUserProfile>,
    val inviteUser: suspend (roomId: String, userId: String) -> Unit,
    val delayMillis: suspend (Long) -> Unit = { duration -> delay(duration) }
)

/**
 * Owns the complete invite-members workflow for one room and session.
 *
 * Room membership is loaded before sending so joined and already-invited users cannot be
 * selected. Search and send operations use independent generations: dependencies that swallow
 * cancellation still cannot publish into a replacement route or session. Permission is seeded
 * for a stable first frame and checked again immediately before send. Existing rooms refresh
 * permission and membership on activation; a room just created by this client starts from the
 * complete known snapshot containing only its creator, avoiding a redundant loading frame.
 */
internal class InviteMembersStore(
    private val scope: CoroutineScope,
    private val driver: InviteMembersDriver,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val searchDebounceMillis: Long = INVITE_MEMBERS_SEARCH_DEBOUNCE_MILLIS,
    private val onAllInvitesSent: (InviteMembersTarget) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(InviteMembersState())
    val state: StateFlow<InviteMembersState> = _state.asStateFlow()

    private var routeGeneration = 0L
    private var searchGeneration = 0L
    private var sendGeneration = 0L
    private var permissionJob: Job? = null
    private var membersJob: Job? = null
    private var searchJob: Job? = null
    private var sendJob: Job? = null
    private var hasMemberSnapshot = false
    private var activationMode = InviteMembersActivationMode.EXISTING_ROOM
    private var membershipByUserId = emptyMap<String, MatrixRoomMemberMembership>()
    private var rawSearchResults = emptyList<MatrixUserProfile>()
    private val selectedByUserId = LinkedHashMap<String, MatrixUserProfile>()

    @MainThread
    fun activate(
        target: InviteMembersTarget,
        seedCanInviteMembers: Boolean,
        mode: InviteMembersActivationMode = InviteMembersActivationMode.EXISTING_ROOM
    ) {
        val normalizedTarget = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        if (_state.value.target == normalizedTarget && activationMode == mode) {
            if (_state.value.canInviteMembers != seedCanInviteMembers) {
                _state.value = _state.value.copy(
                    canInviteMembers = seedCanInviteMembers,
                    permissionDenied = !seedCanInviteMembers,
                    permissionErrorMessage = null
                )
            }
            return
        }

        deactivate()
        routeGeneration += 1
        val requestGeneration = routeGeneration
        activationMode = mode
        val isNewlyCreatedRoom = mode == InviteMembersActivationMode.NEWLY_CREATED_ROOM
        if (isNewlyCreatedRoom) {
            hasMemberSnapshot = true
            membershipByUserId = mapOf(
                normalizedTarget.userId to MatrixRoomMemberMembership.JOINED
            )
        }
        _state.value = InviteMembersState(
            target = normalizedTarget,
            canInviteMembers = seedCanInviteMembers,
            isPreparing = !isNewlyCreatedRoom
        )
        if (!isNewlyCreatedRoom) {
            startPermissionRefresh(normalizedTarget, requestGeneration)
            startMembersLoad(normalizedTarget, requestGeneration)
        }
    }

    @MainThread
    fun retryPreparation() {
        if (activationMode == InviteMembersActivationMode.NEWLY_CREATED_ROOM) return
        val target = _state.value.target ?: return
        val requestGeneration = routeGeneration
        _state.value = _state.value.copy(
            isPreparing = !hasMemberSnapshot,
            preparationErrorMessage = null,
            permissionErrorMessage = null,
            sendErrorMessage = null,
            permissionDenied = false
        )
        startPermissionRefresh(target, requestGeneration)
        startMembersLoad(target, requestGeneration)
    }

    @MainThread
    fun setSearchQuery(query: String) {
        if (_state.value.searchQuery == query || _state.value.isSending) {
            return
        }
        cancelSearch()
        rawSearchResults = emptyList()
        _state.value = _state.value.copy(
            searchQuery = query,
            searchResults = emptyList(),
            isSearching = false,
            searchErrorMessage = null,
            sendErrorMessage = null,
            failedInviteCount = 0
        )
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < INVITE_MEMBERS_MIN_SEARCH_LENGTH) {
            return
        }

        startSearch(query = query, normalizedQuery = normalizedQuery)
    }

    @MainThread
    fun retrySearch() {
        val current = _state.value
        val query = current.searchQuery
        val normalizedQuery = query.trim()
        if (
            current.searchErrorMessage == null ||
            current.isSending ||
            normalizedQuery.length < INVITE_MEMBERS_MIN_SEARCH_LENGTH
        ) {
            return
        }
        cancelSearch()
        rawSearchResults = emptyList()
        _state.value = current.copy(
            searchResults = emptyList(),
            isSearching = false,
            searchErrorMessage = null
        )
        startSearch(query = query, normalizedQuery = normalizedQuery)
    }

    private fun startSearch(query: String, normalizedQuery: String) {
        searchGeneration += 1
        val requestGeneration = searchGeneration
        val target = _state.value.target ?: return
        val routeRequestGeneration = routeGeneration
        _state.value = _state.value.copy(isSearching = true)
        val nextJob = scope.launch {
            try {
                driver.delayMillis(searchDebounceMillis)
                val results = driver.searchUsers(
                    normalizedQuery,
                    INVITE_MEMBERS_SEARCH_LIMIT
                )
                if (
                    !isCurrent(target, routeRequestGeneration) ||
                    searchGeneration != requestGeneration ||
                    _state.value.searchQuery != query
                ) {
                    return@launch
                }
                rawSearchResults = results.distinctBy { profile -> profile.userId }
                publishSearchResults()
                _state.value = _state.value.copy(
                    isSearching = false,
                    searchErrorMessage = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    isCurrent(target, routeRequestGeneration) &&
                    searchGeneration == requestGeneration
                ) {
                    onWarning("Failed to search users for room invitation", error)
                    _state.value = _state.value.copy(
                        isSearching = false,
                        searchErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
        searchJob = nextJob
        nextJob.invokeOnCompletion {
            if (searchJob === nextJob) {
                searchJob = null
            }
        }
    }

    @MainThread
    fun toggleSelection(profile: MatrixUserProfile) {
        if (_state.value.isSending || membershipByUserId.containsKey(profile.userId)) {
            return
        }
        if (selectedByUserId.remove(profile.userId) == null) {
            selectedByUserId[profile.userId] = profile
        }
        publishSelection()
        publishSearchResults()
    }

    @MainThread
    fun sendInvites() {
        val target = _state.value.target ?: return
        if (!_state.value.canSubmit || !hasMemberSnapshot) {
            return
        }
        val requestedProfiles = selectedByUserId.values.toList()
        if (requestedProfiles.isEmpty()) {
            return
        }

        cancelSearch()
        sendGeneration += 1
        val requestGeneration = sendGeneration
        val routeRequestGeneration = routeGeneration
        _state.value = _state.value.copy(
            isSending = true,
            isSearching = false,
            sendErrorMessage = null,
            failedInviteCount = 0,
            permissionDenied = false
        )
        val nextJob = scope.launch {
            val canInvite = try {
                driver.canInviteMembers(target.roomId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isSendCurrent(target, routeRequestGeneration, requestGeneration)) {
                    onWarning("Failed to recheck room invite permission", error)
                    _state.value = _state.value.copy(
                        isSending = false,
                        sendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
                return@launch
            }
            if (!isSendCurrent(target, routeRequestGeneration, requestGeneration)) {
                return@launch
            }
            if (!canInvite) {
                _state.value = _state.value.copy(
                    canInviteMembers = false,
                    isSending = false,
                    permissionDenied = true
                )
                return@launch
            }

            val succeeded = ArrayList<MatrixUserProfile>(requestedProfiles.size)
            val failed = ArrayList<MatrixUserProfile>()
            requestedProfiles.forEach { profile ->
                currentCoroutineContext().ensureActive()
                if (!isSendCurrent(target, routeRequestGeneration, requestGeneration)) {
                    return@launch
                }
                try {
                    driver.inviteUser(target.roomId, profile.userId)
                    succeeded += profile
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failed += profile
                    onWarning("Failed to invite ${profile.userId} to room", error)
                }
            }
            if (!isSendCurrent(target, routeRequestGeneration, requestGeneration)) {
                return@launch
            }

            val updatedMemberships = withContext(workerDispatcher) {
                membershipByUserId.toMutableMap().apply {
                    succeeded.forEach { profile ->
                        this[profile.userId] = MatrixRoomMemberMembership.INVITED
                    }
                }
            }
            if (!isSendCurrent(target, routeRequestGeneration, requestGeneration)) {
                return@launch
            }
            membershipByUserId = updatedMemberships
            succeeded.forEach { profile -> selectedByUserId.remove(profile.userId) }
            publishSelection()
            publishSearchResults()
            _state.value = _state.value.copy(
                isSending = false,
                failedInviteCount = failed.size
            )
            if (failed.isEmpty()) {
                onAllInvitesSent(target)
            }
        }
        sendJob = nextJob
        nextJob.invokeOnCompletion {
            if (sendJob === nextJob) {
                sendJob = null
            }
        }
    }

    @MainThread
    fun deactivate() {
        routeGeneration += 1
        permissionJob?.cancel()
        permissionJob = null
        membersJob?.cancel()
        membersJob = null
        cancelSearch()
        sendGeneration += 1
        sendJob?.cancel()
        sendJob = null
        hasMemberSnapshot = false
        activationMode = InviteMembersActivationMode.EXISTING_ROOM
        membershipByUserId = emptyMap()
        rawSearchResults = emptyList()
        selectedByUserId.clear()
        _state.value = InviteMembersState()
    }

    @MainThread
    fun clearSession() = deactivate()

    private fun startPermissionRefresh(
        target: InviteMembersTarget,
        requestGeneration: Long
    ) {
        permissionJob?.cancel()
        val nextJob = scope.launch {
            try {
                val canInvite = driver.canInviteMembers(target.roomId)
                if (isCurrent(target, requestGeneration)) {
                    _state.value = _state.value.copy(
                        canInviteMembers = canInvite,
                        permissionDenied = !canInvite,
                        permissionErrorMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(target, requestGeneration)) {
                    onWarning("Failed to load room invite permission", error)
                    _state.value = _state.value.copy(
                        permissionErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
        permissionJob = nextJob
        nextJob.invokeOnCompletion {
            if (permissionJob === nextJob) {
                permissionJob = null
            }
        }
    }

    private fun startMembersLoad(
        target: InviteMembersTarget,
        requestGeneration: Long
    ) {
        membersJob?.cancel()
        val nextJob = scope.launch {
            var publishedSnapshot = hasMemberSnapshot
            try {
                val cachedMemberships = loadMembershipSnapshot(
                    target.roomId,
                    InviteMembersSource.CACHE
                )
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                applyMemberships(cachedMemberships, finishedPreparing = false)
                publishedSnapshot = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(target, requestGeneration)) {
                    onWarning("Failed to load cached members for invitation", error)
                }
            }

            try {
                val currentMemberships = loadMembershipSnapshot(
                    target.roomId,
                    InviteMembersSource.SERVER
                )
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                applyMemberships(currentMemberships, finishedPreparing = true)
                _state.value = _state.value.copy(
                    isPreparing = false,
                    preparationErrorMessage = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                onWarning("Failed to load current members for invitation", error)
                _state.value = _state.value.copy(
                    isPreparing = false,
                    preparationErrorMessage = if (publishedSnapshot) {
                        null
                    } else {
                        error.message ?: error.javaClass.simpleName
                    }
                )
            }
        }
        membersJob = nextJob
        nextJob.invokeOnCompletion {
            if (membersJob === nextJob) {
                membersJob = null
            }
        }
    }

    private suspend fun loadMembershipSnapshot(
        roomId: String,
        source: InviteMembersSource
    ): Map<String, MatrixRoomMemberMembership> {
        val members = driver.loadMembers(roomId, source)
        return withContext(workerDispatcher) {
            val coroutineContext = currentCoroutineContext()
            buildMap(members.size) {
                members.forEachIndexed { index, member ->
                    if (index and MEMBERSHIP_CANCELLATION_CHECK_MASK == 0) {
                        coroutineContext.ensureActive()
                    }
                    put(member.userId, member.membership)
                }
            }
        }
    }

    private fun applyMemberships(
        memberships: Map<String, MatrixRoomMemberMembership>,
        finishedPreparing: Boolean
    ) {
        hasMemberSnapshot = true
        membershipByUserId = memberships
        val selectedIterator = selectedByUserId.keys.iterator()
        while (selectedIterator.hasNext()) {
            if (membershipByUserId.containsKey(selectedIterator.next())) {
                selectedIterator.remove()
            }
        }
        publishSelection()
        publishSearchResults()
        _state.value = _state.value.copy(
            isPreparing = !finishedPreparing,
            preparationErrorMessage = null
        )
    }

    private fun publishSelection() {
        _state.value = _state.value.copy(
            selectedMembers = selectedByUserId.values.map { profile ->
                InviteMemberCandidate(profile = profile, membership = null)
            }
        )
    }

    private fun publishSearchResults() {
        _state.value = _state.value.copy(
            searchResults = rawSearchResults
                .filterNot { profile -> selectedByUserId.containsKey(profile.userId) }
                .map { profile ->
                    InviteMemberCandidate(
                        profile = profile,
                        membership = membershipByUserId[profile.userId]
                    )
                }
        )
    }

    private fun cancelSearch() {
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
    }

    private fun isCurrent(target: InviteMembersTarget, requestGeneration: Long): Boolean {
        return routeGeneration == requestGeneration && _state.value.target == target
    }

    private fun isSendCurrent(
        target: InviteMembersTarget,
        routeRequestGeneration: Long,
        requestGeneration: Long
    ): Boolean {
        return isCurrent(target, routeRequestGeneration) && sendGeneration == requestGeneration
    }

    private fun InviteMembersTarget.normalizedOrNull(): InviteMembersTarget? {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedRoomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        return InviteMembersTarget(normalizedUserId, normalizedRoomId)
    }
}

internal fun createInviteMembersStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onAllInvitesSent: (InviteMembersTarget) -> Unit,
    onWarning: (String, Throwable) -> Unit
): InviteMembersStore {
    return InviteMembersStore(
        scope = scope,
        driver = InviteMembersDriver(
            canInviteMembers = matrixClientService::canInviteRoomMembers,
            loadMembers = { roomId, source ->
                matrixClientService.loadRoomMembers(
                    roomId = roomId,
                    useCachedSnapshot = source == InviteMembersSource.CACHE
                )
            },
            searchUsers = matrixClientService::searchUsers,
            inviteUser = matrixClientService::inviteRoomMember
        ),
        onAllInvitesSent = onAllInvitesSent,
        onWarning = onWarning
    )
}

internal const val INVITE_MEMBERS_MIN_SEARCH_LENGTH = 3
private const val INVITE_MEMBERS_SEARCH_LIMIT = 20
private const val INVITE_MEMBERS_SEARCH_DEBOUNCE_MILLIS = 250L
private const val MEMBERSHIP_CANCELLATION_CHECK_MASK = 127
