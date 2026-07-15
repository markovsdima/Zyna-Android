package com.zyna.app.ui.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.zyna.app.BuildConfig
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallService
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixUserProfile
import com.zyna.app.data.outgoing.OutgoingOutboxService
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingVoiceDraft
import com.zyna.app.data.presence.PresenceRepository
import com.zyna.app.data.presence.UserPresenceStatus
import com.zyna.app.data.profile.ProfileAvatarDraft
import com.zyna.app.data.security.MatrixSessionSecurityAction
import com.zyna.app.data.security.MatrixLogoutWarning
import com.zyna.app.data.security.MatrixSessionSecurityState
import com.zyna.app.ui.chat.ChatComposerSendTarget
import com.zyna.app.ui.chat.ChatComposerState
import com.zyna.app.ui.chat.ChatCallInfoState
import com.zyna.app.ui.chat.ChatCallInfoTarget
import com.zyna.app.ui.chat.ChatMessageActionRequest
import com.zyna.app.ui.chat.ChatMessageActionResult
import com.zyna.app.ui.chat.ChatMessageActionTarget
import com.zyna.app.ui.chat.ChatReadReceiptCoordinator
import com.zyna.app.ui.chat.ChatTimelineNavigationRequest
import com.zyna.app.ui.chat.ChatTimelineState
import com.zyna.app.ui.chat.ChatTimelineTarget
import com.zyna.app.ui.chat.createChatComposerStore
import com.zyna.app.ui.chat.createChatCallInfoCoordinator
import com.zyna.app.ui.chat.createChatMessageActionStore
import com.zyna.app.ui.chat.createChatTimelineStore
import com.zyna.app.ui.calls.CallHistoryState
import com.zyna.app.ui.calls.createCallHistoryStore
import com.zyna.app.ui.profile.OwnProfileState
import com.zyna.app.ui.profile.createOwnProfileStore
import com.zyna.app.util.ZynaPerfLog
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UserProfileUiState(
    val userId: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val effectiveDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: userId
}

data class PendingNativeMatrixRtcCallLaunch(
    val requestId: Long,
    val roomId: String,
    val roomName: String
)

data class LogoutConfirmationState(
    val warning: MatrixLogoutWarning?
)

data class AppUiState(
    val navState: AppNavState = AppNavState(),
    val matrixState: MatrixClientState = MatrixClientState.LoggedOut,
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val presenceByUserId: Map<String, UserPresenceStatus> = emptyMap(),
    val contactsSearchQuery: String = "",
    val contactsSearchResults: List<MatrixUserProfile> = emptyList(),
    val isSearchingContacts: Boolean = false,
    val contactsSearchErrorMessage: String? = null,
    val userProfile: UserProfileUiState = UserProfileUiState(),
    val contactActionUserId: String? = null,
    val contactActionErrorMessage: String? = null,
    val pendingNativeMatrixRtcCallLaunch: PendingNativeMatrixRtcCallLaunch? = null,
    val isRefreshingRooms: Boolean = false,
    val isLoggingOut: Boolean = false,
    val logoutErrorMessage: String? = null,
    val logoutConfirmation: LogoutConfirmationState? = null,
    val sessionSecurity: MatrixSessionSecurityState = MatrixSessionSecurityState()
) {
    val route: AppRoute
        get() = navState.top

    val navigationStack: List<AppRoute>
        get() = navState.visibleStack

    val selectedTab: AppTab
        get() = navState.selectedTab

    val activeChatRoute: AppRoute.Chat?
        get() = navState.activeChatRoute

    val activeChatDirectUserId: String?
        get() = activeChatRoute?.let { route ->
            rooms.firstOrNull { it.id == route.roomId }?.directUserId
        }

    val activeChatPresence: UserPresenceStatus?
        get() = activeChatDirectUserId?.let { userId -> presenceByUserId[userId] }

    val isBusy: Boolean
        get() = matrixState is MatrixClientState.LoggingIn ||
            matrixState is MatrixClientState.RestoringSession

    val errorMessage: String?
        get() = (matrixState as? MatrixClientState.Error)?.message

    val contacts: List<MatrixContact>
        get() = buildContacts()

    fun roomIdForContact(userId: String): String? {
        return rooms.firstOrNull { it.directUserId == userId }?.id
    }

    private fun buildContacts(): List<MatrixContact> {
        val query = contactsSearchQuery.trim()
        val directContacts = rooms
            .asSequence()
            .mapNotNull { room -> room.toContactOrNull() }
            .sortedWith(compareBy<MatrixContact> { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.userId })
            .toList()
        if (query.isBlank()) {
            return directContacts
        }

        val directMatches = directContacts.filter { it.matchesQuery(query) }
        val directByUserId = directContacts.associateBy { it.userId }
        val searchContacts = contactsSearchResults.map { profile ->
            val existing = directByUserId[profile.userId]
            MatrixContact(
                userId = profile.userId,
                displayName = profile.effectiveDisplayName,
                avatarUrl = profile.avatarUrl ?: existing?.avatarUrl,
                roomId = existing?.roomId
            )
        }

        return (directMatches + searchContacts)
            .fold(LinkedHashMap<String, MatrixContact>()) { contactsByUserId, contact ->
                val existing = contactsByUserId[contact.userId]
                contactsByUserId[contact.userId] = when {
                    existing == null -> contact
                    existing.roomId == null && contact.roomId != null -> contact
                    existing.avatarUrl.isNullOrBlank() && !contact.avatarUrl.isNullOrBlank() -> {
                        existing.copy(avatarUrl = contact.avatarUrl)
                    }
                    else -> existing
                }
                contactsByUserId
            }
            .values
            .sortedWith(compareBy<MatrixContact> { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.userId })
    }

    private fun MatrixRoomSummary.toContactOrNull(): MatrixContact? {
        val directUserId = this.directUserId?.takeIf { it.isNotBlank() } ?: return null
        return MatrixContact(
            userId = directUserId,
            displayName = displayName.takeIf { it.isNotBlank() } ?: directUserId,
            avatarUrl = avatarUrl,
            roomId = id
        )
    }

    private fun MatrixContact.matchesQuery(query: String): Boolean {
        return displayName.contains(query, ignoreCase = true) ||
            userId.contains(query, ignoreCase = true)
    }
}

class AppViewModel(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingOutboxService: OutgoingOutboxService,
    private val matrixMediaLoader: MatrixMediaLoader,
    private val presenceRepository: PresenceRepository,
    private val nativeMatrixRtcCallService: NativeMatrixRtcCallService
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    private val chatComposerStore = createChatComposerStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        outgoingOutboxService = outgoingOutboxService
    )
    private val chatMessageActionStore = createChatMessageActionStore(
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        outgoingOutboxService = outgoingOutboxService
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val chatComposerState: StateFlow<ChatComposerState> = chatComposerStore.state
    private val callHistoryStore = createCallHistoryStore(
        scope = viewModelScope,
        localCacheRepository = localCacheRepository,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val callHistoryState: StateFlow<CallHistoryState> = callHistoryStore.state
    private val ownProfileStore = createOwnProfileStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onEditFinished = {
            _uiState.update { current ->
                current.copy(navState = current.navState.closeEditProfile())
            }
        },
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val ownProfileState: StateFlow<OwnProfileState> = ownProfileStore.state
    private val roomRefreshCoordinator = CoalescingRoomRefreshCoordinator(viewModelScope) { userId ->
        performRoomRefresh(userId)
    }
    private val chatTimelineStore = createChatTimelineStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        onWindowUpdate = ::logChatTimelineWindowUpdate,
        onTimelineSettled = ::logChatTimelineSettled,
        onInitialSnapshotError = { _, error ->
            Log.w(TAG, "Failed to load initial chat window from cache", error)
        },
        onNavigationError = ::failChatTimelineNavigation,
        onNavigationTrace = ::logTeleport
    )
    val chatTimelineState: StateFlow<ChatTimelineState> = chatTimelineStore.state
    private val chatReadReceiptCoordinator = ChatReadReceiptCoordinator(
        scope = viewModelScope,
        messageIndex = { eventId ->
            chatTimelineStore.state.value.messages.indexOfFirst { it.eventId == eventId }
                .takeIf { it >= 0 }
        },
        sendReadReceipt = { roomId, eventId ->
            matrixClientService.sendReadReceipt(roomId, eventId)
        },
        onSendFailure = { error ->
            Log.w(TAG, "Failed to send read receipt", error)
        }
    )
    private val chatCallInfoCoordinator = createChatCallInfoCoordinator(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        nativeMatrixRtcCallService = nativeMatrixRtcCallService,
        onObservationError = { _, error ->
            Log.w(TAG, "Failed to observe MatrixRTC room call info", error)
        },
        onWarning = { message, error -> Log.w(TAG, message, error) },
        onLog = ::logChatCall
    )
    val chatCallInfoState: StateFlow<ChatCallInfoState> = chatCallInfoCoordinator.state
    private var visibleRoomRefreshRequestCount = 0
    private var roomCacheJob: Job? = null
    private var roomListLiveJob: Job? = null
    private var roomCacheUserId: String? = null
    private var roomListLiveUserId: String? = null
    private var contactsSearchJob: Job? = null
    private var contactsSearchGeneration = 0L
    private var userProfileLoadJob: Job? = null
    private var userProfileLoadGeneration = 0L
    private var contactActionJob: Job? = null
    private var contactActionGeneration = 0L
    private var pendingNativeMatrixRtcCallLaunchCounter = 0L
    private val externalRouteCoordinator = ExternalRouteCoordinator()

    init {
        presenceRepository.start(viewModelScope)

        viewModelScope.launch {
            runCatching { localCacheRepository.cleanupOrphanOutgoingMediaFiles() }
        }

        viewModelScope.launch {
            presenceRepository.statuses.collect { statuses ->
                _uiState.update { current ->
                    current.copy(presenceByUserId = statuses)
                }
            }
        }

        viewModelScope.launch {
            observePresenceRegistrationInputs()
        }

        viewModelScope.launch {
            _uiState.collect(::consumePendingExternalRouteIfReady)
        }

        viewModelScope.launch {
            matrixClientService.state.collect { matrixState ->
                val previousUserId = _uiState.value.matrixState.userIdOrNull()
                val nextUserId = matrixState.userIdOrNull()
                presenceRepository.setSessionContext(
                    userId = nextUserId,
                    allowed = nextUserId != null &&
                        matrixState !is MatrixClientState.Error &&
                        _uiState.value.sessionSecurity.userId == nextUserId &&
                        _uiState.value.sessionSecurity.gateComplete
                )
                val didChangeUser = previousUserId != null &&
                    nextUserId != null &&
                    previousUserId != nextUserId
                val shouldClearSessionData = nextUserId == null || didChangeUser
                val shouldClearChat = shouldClearSessionData ||
                    matrixState is MatrixClientState.Error
                if (
                    nextUserId == null ||
                    didChangeUser ||
                    matrixState is MatrixClientState.Error
                ) {
                    roomRefreshCoordinator.deactivateSession()
                }
                if (nextUserId != null && matrixState !is MatrixClientState.Error) {
                    roomRefreshCoordinator.activateSession(nextUserId)
                }
                if (
                    nextUserId == null ||
                    didChangeUser ||
                    matrixState is MatrixClientState.Error
                ) {
                    stopChatTimeline()
                }
                if (nextUserId == null || didChangeUser || matrixState is MatrixClientState.Error) {
                    stopRoomCache()
                    callHistoryStore.deactivate(clearState = shouldClearSessionData)
                    stopRoomListLiveRefresh()
                    ownProfileStore.deactivate()
                    stopContactJobs(clearState = true)
                }

                when {
                    shouldClearSessionData -> chatComposerStore.clearAll()
                    shouldClearChat -> chatComposerStore.deactivateRoom()
                }

                _uiState.update { current ->
                    val nextNavState = navStateForState(
                        matrixState,
                        if (didChangeUser) AppNavState() else current.navState,
                        current.sessionSecurity
                    )

                    current.copy(
                        matrixState = matrixState,
                        navState = nextNavState,
                        rooms = if (shouldClearSessionData) {
                            emptyList()
                        } else {
                            current.rooms
                        },
                        presenceByUserId = if (shouldClearSessionData) {
                            emptyMap()
                        } else {
                            current.presenceByUserId
                        },
                        contactsSearchQuery = if (shouldClearSessionData) {
                            ""
                        } else {
                            current.contactsSearchQuery
                        },
                        contactsSearchResults = if (shouldClearSessionData) {
                            emptyList()
                        } else {
                            current.contactsSearchResults
                        },
                        isSearchingContacts = if (shouldClearSessionData) {
                            false
                        } else {
                            current.isSearchingContacts
                        },
                        contactsSearchErrorMessage = if (shouldClearSessionData) {
                            null
                        } else {
                            current.contactsSearchErrorMessage
                        },
                        userProfile = if (shouldClearSessionData) {
                            UserProfileUiState()
                        } else {
                            current.userProfile
                        },
                        contactActionUserId = if (shouldClearSessionData) {
                            null
                        } else {
                            current.contactActionUserId
                        },
                        contactActionErrorMessage = if (shouldClearSessionData) {
                            null
                        } else {
                            current.contactActionErrorMessage
                        },
                        pendingNativeMatrixRtcCallLaunch = if (shouldClearSessionData || shouldClearChat) {
                            null
                        } else {
                            current.pendingNativeMatrixRtcCallLaunch
                        },
                        isLoggingOut = if (shouldClearSessionData) false else current.isLoggingOut,
                        logoutErrorMessage = if (shouldClearSessionData) {
                            null
                        } else {
                            current.logoutErrorMessage
                        },
                        logoutConfirmation = if (shouldClearSessionData) {
                            null
                        } else {
                            current.logoutConfirmation
                        }
                    )
                }

                if (nextUserId != null) {
                    startRoomCache(nextUserId)
                    callHistoryStore.activate(nextUserId)
                    ownProfileStore.activate(nextUserId)
                }

                if (matrixState is MatrixClientState.Syncing) {
                    val userId = matrixState.userId
                    if (
                        _uiState.value.sessionSecurity.userId == userId &&
                        _uiState.value.sessionSecurity.gateComplete
                    ) {
                        startRoomListLiveRefresh(userId)
                        launchRoomRefresh(showRefreshing = true)
                    }
                }
            }
        }

        viewModelScope.launch {
            matrixClientService.sessionSecurityState.collect { sessionSecurity ->
                val matrixState = _uiState.value.matrixState
                val userId = matrixState.userIdOrNull()
                val previousSecurity = _uiState.value.sessionSecurity
                val becameGateComplete =
                    !previousSecurity.gateComplete && sessionSecurity.gateComplete
                val receivedNewVerificationRequest =
                    sessionSecurity.incomingRequest != null &&
                        sessionSecurity.incomingRequest.flowId !=
                        previousSecurity.incomingRequest?.flowId

                _uiState.update { current ->
                    val securityMatchesSession = userId != null && sessionSecurity.userId == userId
                    current.copy(
                        sessionSecurity = sessionSecurity,
                        navState = if (securityMatchesSession) {
                            val routedState = current.navState.routeForClientState(
                                shouldShowLogin = false,
                                recoveryUserId = userId.takeUnless { sessionSecurity.gateComplete }
                            )
                            if (
                                sessionSecurity.gateComplete &&
                                receivedNewVerificationRequest &&
                                routedState.mode == AppNavMode.Main
                            ) {
                                routedState.openSessionSecurity(userId)
                            } else {
                                routedState
                            }
                        } else {
                            current.navState
                        }
                    )
                }

                if (userId != null && sessionSecurity.userId == userId) {
                    presenceRepository.setSessionContext(
                        userId = userId,
                        allowed = sessionSecurity.gateComplete
                    )
                    if (becameGateComplete && matrixState is MatrixClientState.Syncing) {
                        startRoomListLiveRefresh(userId)
                        launchRoomRefresh(showRefreshing = true)
                    }
                }
            }
        }

        viewModelScope.launch {
            matrixClientService.incomingMatrixRtcCallNotifications.collect { notification ->
                val userId = matrixClientService.state.value.userIdOrNull() ?: return@collect
                try {
                    localCacheRepository.cacheIncomingMatrixRtcCallNotification(
                        userId = userId,
                        notification = notification
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(TAG, "Failed to cache incoming MatrixRTC call notification", error)
                }
            }
        }

        viewModelScope.launch {
            matrixClientService.restoreSessionIfAvailable()
        }
    }

    fun login(homeserver: String, username: String, password: String) {
        viewModelScope.launch {
            matrixClientService.login(
                homeserver = homeserver,
                username = username,
                password = password
            )
        }
    }

    fun setAppForeground(isForeground: Boolean) {
        presenceRepository.setForeground(isForeground)
    }

    private fun launchRoomRefresh(showRefreshing: Boolean) {
        viewModelScope.launch {
            awaitRoomRefresh(showRefreshing = showRefreshing)
        }
    }

    private suspend fun awaitRoomRefresh(showRefreshing: Boolean) {
        if (showRefreshing) {
            beginVisibleRoomRefresh()
        }
        try {
            roomRefreshCoordinator.refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to refresh rooms", error)
        } finally {
            if (showRefreshing) {
                endVisibleRoomRefresh()
            }
        }
    }

    private suspend fun performRoomRefresh(userId: String) {
        val rooms = matrixClientService.roomsSnapshot()
        if (_uiState.value.matrixState.userIdOrNull() != userId) {
            return
        }
        localCacheRepository.cacheRoomsSnapshot(userId, rooms)
    }

    private fun beginVisibleRoomRefresh() {
        visibleRoomRefreshRequestCount += 1
        if (visibleRoomRefreshRequestCount == 1) {
            _uiState.update { it.copy(isRefreshingRooms = true) }
        }
    }

    private fun endVisibleRoomRefresh() {
        visibleRoomRefreshRequestCount = (visibleRoomRefreshRequestCount - 1).coerceAtLeast(0)
        if (visibleRoomRefreshRequestCount == 0) {
            _uiState.update { it.copy(isRefreshingRooms = false) }
        }
    }

    fun setContactsSearchQuery(query: String) {
        if (_uiState.value.contactsSearchQuery == query) {
            return
        }

        contactsSearchJob?.cancel()
        contactsSearchGeneration += 1
        val generation = contactsSearchGeneration
        val trimmed = query.trim()
        val shouldSearchServer = trimmed.length >= CONTACTS_SEARCH_MIN_LENGTH

        _uiState.update { current ->
            current.copy(
                contactsSearchQuery = query,
                contactsSearchResults = emptyList(),
                isSearchingContacts = shouldSearchServer,
                contactsSearchErrorMessage = null
            )
        }

        if (!shouldSearchServer) {
            return
        }

        contactsSearchJob = viewModelScope.launch {
            delay(CONTACTS_SEARCH_DEBOUNCE_MS)
            try {
                val results = matrixClientService.searchUsers(
                    searchTerm = trimmed,
                    limit = CONTACTS_SEARCH_LIMIT
                )
                _uiState.update { current ->
                    if (contactsSearchGeneration != generation ||
                        current.contactsSearchQuery.trim() != trimmed
                    ) {
                        current
                    } else {
                        current.copy(
                            contactsSearchResults = results,
                            isSearchingContacts = false,
                            contactsSearchErrorMessage = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to search contacts", error)
                _uiState.update { current ->
                    if (contactsSearchGeneration != generation ||
                        current.contactsSearchQuery.trim() != trimmed
                    ) {
                        current
                    } else {
                        current.copy(
                            contactsSearchResults = emptyList(),
                            isSearchingContacts = false,
                            contactsSearchErrorMessage = error.message
                                ?: error.javaClass.simpleName
                        )
                    }
                }
            }
        }
    }

    fun openUserProfile(contact: MatrixContact) {
        val userId = contact.userId.takeIf { it.isNotBlank() } ?: return
        _uiState.update { current ->
            current.copy(
                navState = current.navState.openUserProfile(userId),
                contactActionErrorMessage = null
            )
        }
        startUserProfileLoad(
            userId = userId,
            seedDisplayName = contact.displayName,
            seedAvatarUrl = contact.avatarUrl,
            force = true
        )
    }

    fun refreshUserProfile() {
        val profile = _uiState.value.userProfile
        val userId = profile.userId.takeIf { it.isNotBlank() } ?: return
        startUserProfileLoad(
            userId = userId,
            seedDisplayName = profile.displayName,
            seedAvatarUrl = profile.avatarUrl,
            force = true
        )
    }

    fun openContactChat(contact: MatrixContact) {
        resolveContactRoom(contact, startCall = false)
    }

    fun callContact(contact: MatrixContact) {
        resolveContactRoom(contact, startCall = true)
    }

    fun openUserProfileChat() {
        contactFromUserProfile()?.let { openContactChat(it) }
    }

    fun callUserProfile() {
        contactFromUserProfile()?.let { callContact(it) }
    }

    fun openCallHistoryRoom(item: MatrixRtcCallHistoryItem) {
        openCallHistoryRoom(item, startCall = false)
    }

    fun callHistoryItem(item: MatrixRtcCallHistoryItem) {
        openCallHistoryRoom(item, startCall = true)
    }

    fun consumePendingNativeMatrixRtcCallLaunch(requestId: Long) {
        _uiState.update { current ->
            if (current.pendingNativeMatrixRtcCallLaunch?.requestId == requestId) {
                current.copy(pendingNativeMatrixRtcCallLaunch = null)
            } else {
                current
            }
        }
    }

    fun handleSessionSecurityAction(action: MatrixSessionSecurityAction) {
        matrixClientService.handleSessionSecurityAction(action)
        if (
            (action == MatrixSessionSecurityAction.Continue ||
                action == MatrixSessionSecurityAction.Skip) &&
            _uiState.value.route is AppRoute.SessionSecurity
        ) {
            popActiveStack()
        }
    }

    fun openSessionSecurity() {
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        matrixClientService.handleSessionSecurityAction(MatrixSessionSecurityAction.Manage)
        _uiState.update { current ->
            current.copy(navState = current.navState.openSessionSecurity(userId))
        }
    }

    fun openRoom(room: MatrixRoomSummary) {
        openRoom(room, forwardTarget = null)
    }

    fun handleExternalRoute(command: ExternalRouteCommand) {
        if (externalRouteCoordinator.accept(command)) {
            consumePendingExternalRouteIfReady(_uiState.value)
        }
    }

    private fun consumePendingExternalRouteIfReady(state: AppUiState) {
        if (!externalRouteCoordinator.hasPendingCommand()) {
            return
        }
        val userId = state.matrixState.userIdOrNull()
        val canOpenRooms = userId != null &&
            state.navState.mode == AppNavMode.Main &&
            state.sessionSecurity.userId == userId &&
            state.sessionSecurity.gateComplete
        val command = externalRouteCoordinator.takeIfReady(
            canOpenRooms = canOpenRooms,
            availableRoomIds = state.rooms.asSequence().map { it.id }.toSet()
        ) ?: return

        // External navigation never acknowledges messages. Read receipts remain
        // driven exclusively by updateVisibleReadReceiptCandidate after rendering.
        when (val route = command.route) {
            is ExternalRoute.OpenRoom -> {
                if (state.activeChatRoute?.roomId == route.roomId) {
                    route.eventId?.let(::jumpToChatEvent)
                    return
                }
                val room = state.rooms.firstOrNull { it.id == route.roomId } ?: return
                openRoom(
                    room = room,
                    forwardTarget = null,
                    initialEventId = route.eventId
                )
            }
        }
    }

    private fun openRoom(
        room: MatrixRoomSummary,
        forwardTarget: MatrixForwardTarget?,
        initialEventId: String? = null
    ) {
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        val requestStart = ZynaPerfLog.start()
        ZynaPerfLog.mark {
            "openRoom.request roomId=${room.id} name=${room.displayName} " +
                "route=${_uiState.value.route.perfName()} " +
                "currentMessages=${chatTimelineStore.state.value.messages.size}"
        }
        stopChatTimeline()
        ZynaPerfLog.end(
            requestStart,
            "openRoom.stopPrevious"
        ) {
            "roomId=${room.id}"
        }

        val routeUpdateStart = ZynaPerfLog.start()
        val timelineTarget = ChatTimelineTarget(userId = userId, roomId = room.id)
        if (_uiState.value.matrixState.userIdOrNull() == userId) {
            chatComposerStore.enterRoom(
                target = ChatComposerSendTarget(userId = userId, roomId = room.id),
                forwardTarget = forwardTarget
            )
        }
        _uiState.update {
            if (it.matrixState.userIdOrNull() != userId) {
                it
            } else {
                it.enterChatLoadingState(
                    userId = userId,
                    room = room
                )
            }
        }
        if (_uiState.value.isRouteForRoom(userId, room.id)) {
            chatTimelineStore.open(timelineTarget) {
                initialEventId?.let(::jumpToChatEvent)
            }
            chatCallInfoCoordinator.activate(
                ChatCallInfoTarget(userId = userId, roomId = room.id)
            )
        }
        ZynaPerfLog.end(routeUpdateStart, "openRoom.routeUpdate") {
            "roomId=${room.id}"
        }
    }

    fun closeChat() {
        stopChatTimeline()
        chatComposerStore.clearAll()
        _uiState.update {
            it.copy(
                navState = it.navState.closeChat(),
                pendingNativeMatrixRtcCallLaunch = null
            )
        }
    }

    fun selectTab(tab: AppTab) {
        val state = _uiState.value
        val isClosingEditProfile = state.route == AppRoute.EditProfile
        if (isClosingEditProfile && ownProfileStore.state.value.isSaving) {
            return
        }
        if (isClosingEditProfile) {
            ownProfileStore.cancelEdit()
        }
        _uiState.update { current ->
            val nextNavState = if (isClosingEditProfile) {
                current.navState.closeEditProfile()
            } else {
                current.navState
            }
            current.copy(navState = nextNavState.selectTab(tab))
        }
        if (tab == AppTab.PROFILE) {
            _uiState.value.matrixState.userIdOrNull()?.let(ownProfileStore::activate)
        }
    }

    fun navigateBack(): Boolean {
        val route = _uiState.value.route
        return when (route) {
            is AppRoute.Chat -> {
                closeChat()
                true
            }
            AppRoute.ForwardPicker -> {
                cancelForwardPicker()
                true
            }
            AppRoute.EditProfile -> {
                if (ownProfileStore.state.value.isSaving) {
                    return true
                }
                cancelOwnProfileEdit()
                popActiveStack()
            }
            else -> {
                popActiveStack()
            }
        }
    }

    fun openRoomDetails() {
        _uiState.update { current ->
            current.copy(navState = current.navState.openRoomDetails())
        }
    }

    fun openProfileSettings() {
        _uiState.update { current ->
            current.copy(navState = current.navState.openProfileSettings())
        }
    }

    fun openEditProfile() {
        ownProfileStore.beginEdit()
        _uiState.update { current ->
            current.copy(navState = current.navState.openEditProfile())
        }
    }

    fun openChatThemeSettings() {
        _uiState.update { current ->
            current.copy(navState = current.navState.openChatThemeSettings())
        }
    }

    fun refreshOwnProfile() {
        ownProfileStore.refresh()
    }

    fun setOwnProfileDisplayNameDraft(displayName: String) {
        ownProfileStore.setDisplayNameDraft(displayName)
    }

    fun setOwnProfileAvatarDraft(draft: ProfileAvatarDraft, editSessionId: Long) {
        if (_uiState.value.route != AppRoute.EditProfile) {
            ownProfileStore.discardAvatarDraft(draft)
            return
        }
        ownProfileStore.setAvatarDraft(draft, editSessionId)
    }

    fun setOwnProfileEditError(message: String, editSessionId: Long) {
        if (_uiState.value.route != AppRoute.EditProfile) {
            return
        }
        ownProfileStore.setEditError(message, editSessionId)
    }

    fun removeOwnProfileAvatarDraft() {
        ownProfileStore.removeAvatarDraft()
    }

    fun cancelOwnProfileEdit() {
        ownProfileStore.cancelEdit()
    }

    fun saveOwnProfile() {
        _uiState.value.matrixState.userIdOrNull() ?: return
        ownProfileStore.save()
    }

    private fun startUserProfileLoad(
        userId: String,
        seedDisplayName: String?,
        seedAvatarUrl: String?,
        force: Boolean = false
    ) {
        if (!force &&
            _uiState.value.userProfile.userId == userId &&
            _uiState.value.userProfile.errorMessage == null
        ) {
            return
        }

        userProfileLoadJob?.cancel()
        userProfileLoadGeneration += 1
        val generation = userProfileLoadGeneration
        _uiState.update { current ->
            current.copy(
                userProfile = UserProfileUiState(
                    userId = userId,
                    displayName = seedDisplayName?.takeIf { it.isNotBlank() },
                    avatarUrl = seedAvatarUrl?.takeIf { it.isNotBlank() },
                    isLoading = true,
                    errorMessage = null
                )
            )
        }

        val loadJob = viewModelScope.launch {
            try {
                val profile = matrixClientService.loadUserProfile(userId)
                _uiState.update { current ->
                    if (userProfileLoadGeneration != generation ||
                        current.userProfile.userId != userId
                    ) {
                        current
                    } else {
                        current.copy(
                            userProfile = UserProfileUiState(
                                userId = profile.userId,
                                displayName = profile.displayName,
                                avatarUrl = profile.avatarUrl,
                                isLoading = false,
                                errorMessage = null
                            )
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to load user profile", error)
                _uiState.update { current ->
                    if (userProfileLoadGeneration != generation ||
                        current.userProfile.userId != userId
                    ) {
                        current
                    } else {
                        current.copy(
                            userProfile = current.userProfile.copy(
                                isLoading = false,
                                errorMessage = error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
            }
        }
        userProfileLoadJob = loadJob
        loadJob.invokeOnCompletion {
            if (userProfileLoadJob === loadJob) {
                userProfileLoadJob = null
            }
        }
    }

    private fun resolveContactRoom(contact: MatrixContact, startCall: Boolean) {
        val userId = contact.userId.takeIf { it.isNotBlank() } ?: return
        val activeUserId = _uiState.value.matrixState.userIdOrNull() ?: return
        if (contactActionJob?.isActive == true && _uiState.value.contactActionUserId == userId) {
            return
        }

        contactActionJob?.cancel()
        contactActionGeneration += 1
        val generation = contactActionGeneration
        _uiState.update { current ->
            current.copy(
                contactActionUserId = userId,
                contactActionErrorMessage = null
            )
        }

        val actionJob = viewModelScope.launch {
            try {
                val room = resolvedContactRoom(contact)
                if (contactActionGeneration != generation) {
                    return@launch
                }

                cacheResolvedDirectRoom(activeUserId, room)
                if (contactActionGeneration != generation) {
                    return@launch
                }

                openRoom(room)

                _uiState.update { current ->
                    if (contactActionGeneration != generation) {
                        current
                    } else {
                        current.copy(
                            contactActionUserId = null,
                            contactActionErrorMessage = null,
                            pendingNativeMatrixRtcCallLaunch = if (startCall) {
                                PendingNativeMatrixRtcCallLaunch(
                                    requestId = nextPendingNativeMatrixRtcCallLaunchId(),
                                    roomId = room.id,
                                    roomName = room.displayName
                                )
                            } else {
                                current.pendingNativeMatrixRtcCallLaunch
                            }
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to resolve contact room", error)
                _uiState.update { current ->
                    if (contactActionGeneration != generation) {
                        current
                    } else {
                        current.copy(
                            contactActionUserId = null,
                            contactActionErrorMessage = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
            }
        }
        contactActionJob = actionJob
        actionJob.invokeOnCompletion {
            if (contactActionJob === actionJob) {
                contactActionJob = null
            }
        }
    }

    private fun openCallHistoryRoom(
        item: MatrixRtcCallHistoryItem,
        startCall: Boolean
    ) {
        if (item.roomId.isBlank()) {
            return
        }
        val activeUserId = _uiState.value.matrixState.userIdOrNull() ?: return
        val room = callHistoryRoomSummary(item)
        openRoom(room)

        _uiState.update { current ->
            if (current.matrixState.userIdOrNull() != activeUserId) {
                current
            } else {
                current.copy(
                    pendingNativeMatrixRtcCallLaunch = if (startCall) {
                        PendingNativeMatrixRtcCallLaunch(
                            requestId = nextPendingNativeMatrixRtcCallLaunchId(),
                            roomId = room.id,
                            roomName = room.displayName
                        )
                    } else {
                        current.pendingNativeMatrixRtcCallLaunch
                    }
                )
            }
        }
    }

    private fun callHistoryRoomSummary(item: MatrixRtcCallHistoryItem): MatrixRoomSummary {
        return _uiState.value.rooms.firstOrNull { it.id == item.roomId }
            ?: MatrixRoomSummary(
                id = item.roomId,
                displayName = item.title,
                avatarUrl = item.roomAvatarUrl
            )
    }

    private suspend fun resolvedContactRoom(contact: MatrixContact): MatrixRoomSummary {
        val existingRoom = contact.roomId
            ?.let { roomId -> _uiState.value.rooms.firstOrNull { it.id == roomId } }
        if (existingRoom != null) {
            return existingRoom
        }

        if (!contact.roomId.isNullOrBlank()) {
            return MatrixRoomSummary(
                id = contact.roomId,
                displayName = contact.displayName,
                avatarUrl = contact.avatarUrl,
                directUserId = contact.userId
            )
        }

        return matrixClientService.resolveDirectRoom(
            userId = contact.userId,
            fallbackDisplayName = contact.displayName,
            fallbackAvatarUrl = contact.avatarUrl
        )
    }

    private suspend fun cacheResolvedDirectRoom(userId: String, room: MatrixRoomSummary) {
        localCacheRepository.cacheRoomSummary(userId, room)
    }

    private fun contactFromUserProfile(): MatrixContact? {
        val profile = _uiState.value.userProfile
        val userId = profile.userId.takeIf { it.isNotBlank() } ?: return null
        return MatrixContact(
            userId = userId,
            displayName = profile.effectiveDisplayName,
            avatarUrl = profile.avatarUrl,
            roomId = _uiState.value.roomIdForContact(userId)
        )
    }

    private fun stopContactJobs(clearState: Boolean) {
        contactsSearchJob?.cancel()
        contactsSearchJob = null
        contactsSearchGeneration += 1
        userProfileLoadJob?.cancel()
        userProfileLoadJob = null
        userProfileLoadGeneration += 1
        contactActionJob?.cancel()
        contactActionJob = null
        contactActionGeneration += 1
        if (clearState) {
            _uiState.update {
                it.copy(
                    contactsSearchQuery = "",
                    contactsSearchResults = emptyList(),
                    isSearchingContacts = false,
                    contactsSearchErrorMessage = null,
                    userProfile = UserProfileUiState(),
                    contactActionUserId = null,
                    contactActionErrorMessage = null,
                    pendingNativeMatrixRtcCallLaunch = null
                )
            }
        }
    }

    private fun nextPendingNativeMatrixRtcCallLaunchId(): Long {
        pendingNativeMatrixRtcCallLaunchCounter += 1
        return pendingNativeMatrixRtcCallLaunchCounter
    }

    private fun popActiveStack(): Boolean {
        var didNavigate = false
        _uiState.update { current ->
            val nextNavState = current.navState.popActiveStack()
            if (nextNavState == null) {
                current
            } else {
                didNavigate = true
                current.copy(navState = nextNavState)
            }
        }
        return didNavigate
    }

    fun setChatCallInfoObserverEnabled(enabled: Boolean) {
        chatCallInfoCoordinator.setEnabled(enabled)
    }

    fun sendChatMessage(body: String): Boolean {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return false
        val userId = state.matrixState.userIdOrNull() ?: return false
        return chatComposerStore.sendText(
            target = ChatComposerSendTarget(userId = userId, roomId = route.roomId),
            body = body
        )
    }

    fun sendPhotoMessages(draft: OutgoingPhotoDraft): Boolean {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return false
        val userId = state.matrixState.userIdOrNull() ?: return false
        return chatComposerStore.sendPhotos(
            target = ChatComposerSendTarget(userId = userId, roomId = route.roomId),
            draft = draft
        )
    }

    fun sendVoiceMessage(
        draft: OutgoingVoiceDraft,
        onEnqueued: () -> Unit = {}
    ): Boolean {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return false
        val userId = state.matrixState.userIdOrNull() ?: return false
        return chatComposerStore.sendVoice(
            target = ChatComposerSendTarget(userId = userId, roomId = route.roomId),
            draft = draft,
            onEnqueued = onEnqueued
        )
    }

    fun toggleReaction(messageId: String, reactionKey: String) {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return
        val userId = state.matrixState.userIdOrNull() ?: return
        val message = chatTimelineStore.state.value.messages
            .firstOrNull { it.id == messageId }
            ?: return
        val request = chatMessageActionStore.createReactionRequest(
            target = ChatMessageActionTarget(userId, route.roomId),
            message = message,
            reactionKey = reactionKey
        ) ?: return
        launchChatMessageAction(request)
    }

    fun setChatReplyTarget(replyInfo: MatrixReplyInfo) {
        _uiState.value.activeChatRoute ?: return
        chatComposerStore.selectReply(replyInfo)
    }

    fun setChatEditTarget(editTarget: MatrixEditTarget) {
        _uiState.value.activeChatRoute ?: return
        chatComposerStore.selectEdit(editTarget)
    }

    fun startForwardMessage(target: MatrixForwardTarget) {
        chatComposerStore.startForwardPicker(target) ?: return
        _uiState.update { current ->
            current.copy(
                navState = current.navState.openForwardPicker()
            )
        }
    }

    fun cancelForwardPicker() {
        chatComposerStore.cancelForwardPicker()
        _uiState.update { current ->
            current.copy(
                navState = current.navState.closeForwardPicker()
            )
        }
    }

    fun selectForwardRoom(room: MatrixRoomSummary) {
        val target = chatComposerStore.state.value.pendingForwardTarget ?: return
        openRoom(room, forwardTarget = target)
    }

    fun clearChatForwardTarget() {
        if (chatComposerStore.state.value.forwardTarget != null) {
            chatComposerStore.clearForward()
        }
    }

    fun clearChatReplyTarget() {
        if (chatComposerStore.state.value.replyTarget != null) {
            chatComposerStore.clearReply()
        }
    }

    fun clearChatEditTarget() {
        if (chatComposerStore.state.value.editTarget != null) {
            chatComposerStore.clearEdit()
        }
    }

    fun retryOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.activeChatRoute ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        launchChatMessageAction(
            ChatMessageActionRequest.RetryOutgoing(
                target = ChatMessageActionTarget(userId, route.roomId),
                envelopeId = envelopeId
            )
        )
    }

    fun discardOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.activeChatRoute ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        launchChatMessageAction(
            ChatMessageActionRequest.DiscardOutgoing(
                target = ChatMessageActionTarget(userId, route.roomId),
                envelopeId = envelopeId
            )
        )
    }

    private fun launchChatMessageAction(request: ChatMessageActionRequest) {
        viewModelScope.launch {
            try {
                when (chatMessageActionStore.execute(request)) {
                    ChatMessageActionResult.COMPLETED -> Unit
                    ChatMessageActionResult.NOT_APPLIED -> return@launch
                }
                chatComposerStore.clearError(
                    ChatComposerSendTarget(
                        userId = request.target.userId,
                        roomId = request.target.roomId
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                chatComposerStore.reportError(
                    target = ChatComposerSendTarget(
                        userId = request.target.userId,
                        roomId = request.target.roomId
                    ),
                    message = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    fun redactMessage(messageId: String) {
        redactMessages(listOf(messageId))
    }

    fun redactMessages(messageIds: List<String>) {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return
        val userId = state.matrixState.userIdOrNull() ?: return
        val request = chatMessageActionStore.createRedactionRequest(
            target = ChatMessageActionTarget(userId, route.roomId),
            messageIds = messageIds,
            availableMessages = chatTimelineStore.state.value.messages
        ) ?: return
        launchChatMessageAction(request)
    }

    fun debugMarkOutgoingEnvelopeFailed(envelopeId: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val route = _uiState.value.activeChatRoute ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                localCacheRepository.debugMarkOutgoingMessageEnvelopeFailed(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                chatComposerStore.reportError(
                    target = ChatComposerSendTarget(userId = userId, roomId = route.roomId),
                    message = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    fun loadOlderChatMessages() {
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        val timeline = chatTimelineStore.state.value
        if (
            timeline.isLoading ||
            timeline.isLoadingWindowOperation ||
            !timeline.canLoadOlder ||
            chatTimelineStore.hasActiveWindowOperation()
        ) {
            return
        }
        chatTimelineStore.loadOlder(target)
    }

    fun loadNewerChatMessages() {
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        val timeline = chatTimelineStore.state.value
        if (
            timeline.isLoading ||
            timeline.isLoadingWindowOperation ||
            !timeline.canLoadNewer ||
            chatTimelineStore.hasActiveWindowOperation()
        ) {
            return
        }
        chatTimelineStore.loadNewer(target)
    }

    fun jumpToChatEvent(eventId: String) {
        val normalizedEventId = eventId.takeIf { it.isNotBlank() } ?: return
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        val timeline = chatTimelineStore.state.value
        if (chatTimelineStore.hasActiveWindowOperation()) {
            logTeleport("jump cancels activeWindowOperation target=${normalizedEventId.shortLogId()}")
        }
        logTeleport(
            "jump request target=${normalizedEventId.shortLogId()} " +
                "messages=${timeline.messages.size} canOlder=${timeline.canLoadOlder} " +
                "canNewer=${timeline.canLoadNewer} live=${timeline.isAtLiveEdge}"
        )

        val isTargetInCurrentWindow = timeline.messages.any { message ->
            message.eventId == normalizedEventId || message.id == normalizedEventId
        }
        if (isTargetInCurrentWindow) {
            logTeleport("jump local target=${normalizedEventId.shortLogId()}")
        }

        if (chatTimelineStore.jumpToEvent(target, normalizedEventId)) {
            chatComposerStore.clearError(
                ChatComposerSendTarget(userId = userId, roomId = route.roomId)
            )
            chatReadReceiptCoordinator.reset()
        } else {
            logTeleport("jump abort unavailable target=${normalizedEventId.shortLogId()}")
        }
    }

    fun clearChatJumpTarget(eventId: String) {
        chatTimelineStore.consumeJumpTarget(eventId)
    }

    fun jumpToChatLiveEdge() {
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        if (chatTimelineStore.hasActiveWindowOperation()) {
            logTeleport("live cancels activeWindowOperation")
        }

        if (chatTimelineStore.jumpToLiveEdge(target)) {
            chatComposerStore.clearError(
                ChatComposerSendTarget(userId = userId, roomId = route.roomId)
            )
            chatReadReceiptCoordinator.reset()
        } else {
            logTeleport("live abort unavailable")
        }
    }

    fun clearChatScrollToLiveEdgeRequest() {
        chatTimelineStore.consumeScrollToLiveEdgeRequest()
    }

    fun updateVisibleReadReceiptCandidate(
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) {
        chatReadReceiptCoordinator.updateVisibleCandidate(
            activeRoomId = _uiState.value.activeChatRoute?.roomId,
            roomId = roomId,
            eventId = eventId,
            canEstablishBaseline = canEstablishBaseline
        )
    }

    fun requestLogout() {
        val current = _uiState.value
        if (current.isLoggingOut || current.logoutConfirmation != null) return
        val requestedUserId = current.matrixState.userIdOrNull() ?: return
        _uiState.update {
            it.copy(
                isLoggingOut = true,
                logoutErrorMessage = null
            )
        }
        viewModelScope.launch {
            val warning = runCatching { matrixClientService.prepareForLogout() }
                .onFailure { Log.w(TAG, "Failed to check key backup before logout", it) }
                .getOrDefault(MatrixLogoutWarning.BACKUP_NOT_READY)
            _uiState.update { latest ->
                if (latest.matrixState.userIdOrNull() == requestedUserId) {
                    latest.copy(
                        isLoggingOut = false,
                        logoutConfirmation = LogoutConfirmationState(warning)
                    )
                } else {
                    latest.copy(isLoggingOut = false, logoutConfirmation = null)
                }
            }
        }
    }

    fun cancelLogout() {
        if (_uiState.value.isLoggingOut) return
        _uiState.update { it.copy(logoutConfirmation = null) }
    }

    fun confirmLogout() {
        val current = _uiState.value
        if (current.isLoggingOut || current.logoutConfirmation == null) return
        _uiState.update {
            it.copy(
                isLoggingOut = true,
                logoutConfirmation = null,
                logoutErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                matrixClientService.logout()
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoggingOut = false,
                        logoutErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(isLoggingOut = false, logoutErrorMessage = null)
            }
            cleanupAfterLogout()
        }
    }

    private suspend fun cleanupAfterLogout() {
        runLogoutCleanup("stop chat timeline") { stopChatTimeline() }
        runLogoutCleanup("stop room cache") { stopRoomCache() }
        runLogoutCleanup("stop room refresh") { stopRoomListLiveRefresh() }
        runLogoutCleanup("deactivate room refresh") {
            roomRefreshCoordinator.deactivateSession()
        }
        runLogoutCleanup("clear local cache") { localCacheRepository.clearAll() }
        runLogoutCleanup("clear media cache") {
            withContext(Dispatchers.IO) {
                matrixMediaLoader.clear()
            }
        }
    }

    private suspend fun runLogoutCleanup(
        operationName: String,
        operation: suspend () -> Unit
    ) {
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to $operationName after logout", error)
        }
    }

    private fun navStateForState(
        state: MatrixClientState,
        currentNavState: AppNavState,
        sessionSecurity: MatrixSessionSecurityState
    ): AppNavState {
        return when (state) {
            MatrixClientState.LoggedOut,
            is MatrixClientState.Error -> currentNavState.routeForClientState(
                shouldShowLogin = true,
                recoveryUserId = null
            )
            is MatrixClientState.LoggedIn -> {
                val userId = state.userId

                currentNavState.routeForClientState(
                    shouldShowLogin = false,
                    recoveryUserId = userId.takeUnless {
                        sessionSecurity.userId == it && sessionSecurity.gateComplete
                    }
                )
            }
            is MatrixClientState.Syncing -> {
                val userId = state.userId

                currentNavState.routeForClientState(
                    shouldShowLogin = false,
                    recoveryUserId = userId.takeUnless {
                        sessionSecurity.userId == it && sessionSecurity.gateComplete
                    }
                )
            }
            MatrixClientState.LoggingIn,
            MatrixClientState.RestoringSession -> currentNavState
        }
    }

    private fun logChatTimelineWindowUpdate(
        target: ChatTimelineTarget,
        update: TimelineWindowUpdate<MatrixChatMessage>,
        isAtLiveEdge: Boolean
    ) {
        ZynaPerfLog.mark {
            "chatCache.collect.stateUpdate roomId=${target.roomId} origin=${update.origin} " +
                "count=${update.messages.size} older=${update.hasOlderInDb} " +
                "newer=${update.hasNewerInDb} live=$isAtLiveEdge"
        }
    }

    private fun logChatTimelineSettled(
        target: ChatTimelineTarget,
        messageCount: Int
    ) {
        ZynaPerfLog.mark {
            "chatTimeline.stateUpdate roomId=${target.roomId} count=$messageCount"
        }
    }

    private fun failChatTimelineNavigation(
        target: ChatTimelineTarget,
        request: ChatTimelineNavigationRequest,
        error: Throwable
    ) {
        when (request) {
            is ChatTimelineNavigationRequest.EventJump -> {
                Log.w(TAG, "Failed to jump to chat event in ${target.roomId}", error)
            }

            ChatTimelineNavigationRequest.LiveEdge -> {
                Log.w(TAG, "Failed to jump to live edge in ${target.roomId}", error)
            }
        }
    }

    private fun stopChatTimeline() {
        chatTimelineStore.deactivate()
        chatCallInfoCoordinator.clear()
        chatReadReceiptCoordinator.reset()
    }

    private fun startRoomCache(userId: String) {
        if (roomCacheUserId == userId && roomCacheJob?.isActive == true) {
            return
        }

        roomCacheJob?.cancel()
        roomCacheUserId = userId
        roomCacheJob = viewModelScope.launch {
            localCacheRepository.observeRooms(userId).collect { rooms ->
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(rooms = rooms)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun stopRoomCache() {
        roomCacheJob?.cancel()
        roomCacheJob = null
        roomCacheUserId = null
    }

    private fun startRoomListLiveRefresh(userId: String) {
        if (roomListLiveUserId == userId && roomListLiveJob?.isActive == true) {
            return
        }

        roomListLiveJob?.cancel()
        roomListLiveUserId = userId
        roomListLiveJob = viewModelScope.launch {
            try {
                matrixClientService.roomListChangeSignals().collect {
                    val security = _uiState.value.sessionSecurity
                    if (security.userId == userId && security.gateComplete) {
                        awaitRoomRefresh(showRefreshing = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to observe room list state", error)
            }
        }
    }

    private fun stopRoomListLiveRefresh() {
        roomListLiveJob?.cancel()
        roomListLiveJob = null
        roomListLiveUserId = null
    }

    private suspend fun observePresenceRegistrationInputs() {
        var lastRooms: List<MatrixRoomSummary>? = null
        var lastRoomUserIds: Set<String> = emptySet()
        var lastChatRoomId: String? = null
        var lastChatUserIds: Set<String> = emptySet()
        var lastProfileUserId: String? = null
        var lastProfileUserIds: Set<String> = emptySet()

        _uiState.collect { state ->
            val roomsChanged = lastRooms !== state.rooms
            if (roomsChanged) {
                lastRooms = state.rooms
                val nextRoomUserIds = state.rooms.directPresenceUserIds()
                if (nextRoomUserIds != lastRoomUserIds) {
                    lastRoomUserIds = nextRoomUserIds
                    presenceRepository.register(PRESENCE_TAG_ROOMS, nextRoomUserIds)
                }
            }

            val activeChatRoomId = state.activeChatRoute?.roomId
            if (roomsChanged || activeChatRoomId != lastChatRoomId) {
                lastChatRoomId = activeChatRoomId
                val nextChatUserIds = activeChatRoomId
                    ?.let { roomId -> state.rooms.directPresenceUserIdForRoom(roomId) }
                    ?.let(::setOf)
                    ?: emptySet()
                if (nextChatUserIds != lastChatUserIds) {
                    lastChatUserIds = nextChatUserIds
                    presenceRepository.register(PRESENCE_TAG_CHAT, nextChatUserIds)
                }
            }

            val profileUserId = (state.route as? AppRoute.UserProfile)
                ?.userId
                ?.takeIf { it.isNotBlank() }
            if (profileUserId != lastProfileUserId) {
                lastProfileUserId = profileUserId
                val nextProfileUserIds = profileUserId?.let(::setOf) ?: emptySet()
                if (nextProfileUserIds != lastProfileUserIds) {
                    lastProfileUserIds = nextProfileUserIds
                    presenceRepository.register(PRESENCE_TAG_PROFILE, nextProfileUserIds)
                }
            }
        }
    }

    private fun List<MatrixRoomSummary>.directPresenceUserIds(): Set<String> {
        return asSequence()
            .mapNotNull { room -> room.directUserId?.takeIf { it.isNotBlank() } }
            .toSet()
    }

    private fun List<MatrixRoomSummary>.directPresenceUserIdForRoom(roomId: String): String? {
        return firstOrNull { it.id == roomId }
            ?.directUserId
            ?.takeIf { it.isNotBlank() }
    }

    private fun AppUiState.isRouteForRoom(roomId: String): Boolean {
        return activeChatRoute?.roomId == roomId
    }

    private fun AppUiState.isRouteForRoom(userId: String, roomId: String): Boolean {
        return matrixState.userIdOrNull() == userId && isRouteForRoom(roomId)
    }

    private fun AppUiState.enterChatLoadingState(
        userId: String,
        room: MatrixRoomSummary
    ): AppUiState {
        if (matrixState.userIdOrNull() != userId) {
            return this
        }
        return copy(
            navState = navState.openChat(
                roomId = room.id,
                displayName = room.displayName
            )
        )
    }

    private fun AppRoute.perfName(): String {
        return when (this) {
            AppRoute.Calls -> "Calls"
            AppRoute.ChatThemeSettings -> "ChatThemeSettings"
            is AppRoute.UserProfile -> "UserProfile(${userId.shortLogId()})"
            AppRoute.Contacts -> "Contacts"
            AppRoute.ForwardPicker -> "ForwardPicker"
            AppRoute.Login -> "Login"
            AppRoute.EditProfile -> "EditProfile"
            AppRoute.Profile -> "Profile"
            is AppRoute.RecoveryKey -> "RecoveryKey"
            is AppRoute.SessionSecurity -> "SessionSecurity"
            is AppRoute.RoomDetails -> "RoomDetails(${roomId.shortLogId()})"
            AppRoute.Rooms -> "Rooms"
            AppRoute.Settings -> "Settings"
            is AppRoute.Chat -> "Chat(${roomId.shortLogId()})"
        }
    }

    private fun MatrixClientState.userIdOrNull(): String? {
        return when (this) {
            is MatrixClientState.LoggedIn -> userId
            is MatrixClientState.Syncing -> userId
            MatrixClientState.LoggedOut,
            is MatrixClientState.Error,
            MatrixClientState.LoggingIn,
            MatrixClientState.RestoringSession -> null
        }
    }

    private fun logTeleport(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TELEPORT_LOG_TAG, message)
        }
    }

    private fun logChatCall(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    private companion object {
        const val TAG = "AppViewModel"
        const val TELEPORT_LOG_TAG = "ZynaChatTeleport"
        const val CONTACTS_SEARCH_MIN_LENGTH = 2
        const val CONTACTS_SEARCH_DEBOUNCE_MS = 250L
        const val CONTACTS_SEARCH_LIMIT = 30
        const val PRESENCE_TAG_ROOMS = "rooms"
        const val PRESENCE_TAG_CHAT = "chat"
        const val PRESENCE_TAG_PROFILE = "profile"
    }
}

private fun String.shortLogId(): String {
    return takeLast(10)
}

class AppViewModelFactory(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingOutboxService: OutgoingOutboxService,
    private val matrixMediaLoader: MatrixMediaLoader,
    private val presenceRepository: PresenceRepository,
    private val nativeMatrixRtcCallService: NativeMatrixRtcCallService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(
                matrixClientService = matrixClientService,
                localCacheRepository = localCacheRepository,
                outgoingOutboxService = outgoingOutboxService,
                matrixMediaLoader = matrixMediaLoader,
                presenceRepository = presenceRepository,
                nativeMatrixRtcCallService = nativeMatrixRtcCallService
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
