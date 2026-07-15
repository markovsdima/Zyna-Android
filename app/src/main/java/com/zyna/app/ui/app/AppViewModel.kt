package com.zyna.app.ui.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.zyna.app.BuildConfig
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallService
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixOwnProfile
import com.zyna.app.data.matrix.MatrixRoomCallInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixUserProfile
import com.zyna.app.data.messaging.CaptionMode
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupInfo
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.outgoing.OutgoingOutboxService
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingVoiceDraft
import com.zyna.app.data.presence.PresenceRepository
import com.zyna.app.data.presence.UserPresenceStatus
import com.zyna.app.data.profile.ProfileAvatarDraft
import com.zyna.app.data.security.MatrixSessionSecurityAction
import com.zyna.app.data.security.MatrixLogoutWarning
import com.zyna.app.data.security.MatrixSessionSecurityState
import com.zyna.app.data.timeline.RoomTimelineWindowStore
import com.zyna.app.ui.chat.ChatReadReceiptCoordinator
import com.zyna.app.ui.chat.ChatTimelineNavigationRequest
import com.zyna.app.ui.chat.ChatTimelineNavigationResult
import com.zyna.app.ui.chat.ChatTimelinePaginationResult
import com.zyna.app.ui.chat.ChatTimelineTarget
import com.zyna.app.ui.chat.createChatTimelineStore
import com.zyna.app.util.ZynaPerfLog
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OwnProfileAvatarChange {
    KEEP,
    REPLACE,
    REMOVE
}

data class OwnProfileUiState(
    val userId: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val editDisplayName: String = "",
    val editAvatarLocalPath: String? = null,
    val editAvatarMimeType: String = "image/jpeg",
    val editAvatarChange: OwnProfileAvatarChange = OwnProfileAvatarChange.KEEP,
    val editSessionId: Long = 0L
) {
    val effectiveDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: userId

    val hasAvatar: Boolean
        get() = editAvatarChange != OwnProfileAvatarChange.REMOVE &&
            (avatarUrl?.isNotBlank() == true ||
            (editAvatarChange == OwnProfileAvatarChange.REPLACE && editAvatarLocalPath != null)
            )
}

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
    val ownProfile: OwnProfileUiState = OwnProfileUiState(),
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val presenceByUserId: Map<String, UserPresenceStatus> = emptyMap(),
    val contactsSearchQuery: String = "",
    val contactsSearchResults: List<MatrixUserProfile> = emptyList(),
    val isSearchingContacts: Boolean = false,
    val contactsSearchErrorMessage: String? = null,
    val userProfile: UserProfileUiState = UserProfileUiState(),
    val contactActionUserId: String? = null,
    val contactActionErrorMessage: String? = null,
    val callHistory: List<MatrixRtcCallHistoryItem> = emptyList(),
    val isRefreshingCallHistory: Boolean = false,
    val callHistoryErrorMessage: String? = null,
    val pendingNativeMatrixRtcCallLaunch: PendingNativeMatrixRtcCallLaunch? = null,
    val isRefreshingRooms: Boolean = false,
    val isLoggingOut: Boolean = false,
    val logoutErrorMessage: String? = null,
    val logoutConfirmation: LogoutConfirmationState? = null,
    val sessionSecurity: MatrixSessionSecurityState = MatrixSessionSecurityState(),
    val chatMessages: List<MatrixChatMessage> = emptyList(),
    val chatWindowChangeOrigin: TimelineWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
    val chatTimelineFlushSummary: TimelineFlushSummary? = null,
    val isLoadingChat: Boolean = false,
    val isLoadingOlderChatMessages: Boolean = false,
    val canLoadOlderChatMessages: Boolean = true,
    val canLoadNewerChatMessages: Boolean = false,
    val isChatAtLiveEdge: Boolean = true,
    val chatErrorMessage: String? = null,
    val isSendingChatMessage: Boolean = false,
    val chatSendErrorMessage: String? = null,
    val chatReplyTarget: MatrixReplyInfo? = null,
    val chatEditTarget: MatrixEditTarget? = null,
    val chatForwardTarget: MatrixForwardTarget? = null,
    val pendingForwardTarget: MatrixForwardTarget? = null,
    val chatJumpTargetEventId: String? = null,
    val chatScrollToLiveEdgeRequested: Boolean = false,
    val chatCallBanner: ChatCallBannerState? = null
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

data class ChatCallBannerState(
    val title: String,
    val actionLabel: String,
    val isLocalCall: Boolean,
    val remoteMembershipCount: Int = 0
)

private data class ChatMatrixRtcRingOverride(
    val eventId: String,
    val senderId: String,
    val expiresAtMillis: Long,
    val hasObservedActiveCall: Boolean
)

private data class ChatCallInfoSnapshot(
    val roomInfo: MatrixRoomCallInfo,
    val observed: MatrixRoomCallInfo,
    val ringOverride: ChatMatrixRtcRingOverride?
)

class AppViewModel(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingOutboxService: OutgoingOutboxService,
    private val matrixMediaLoader: MatrixMediaLoader,
    private val presenceRepository: PresenceRepository,
    private val nativeMatrixRtcCallService: NativeMatrixRtcCallService
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val roomRefreshCoordinator = CoalescingRoomRefreshCoordinator(viewModelScope) { userId ->
        performRoomRefresh(userId)
    }
    private val chatReadReceiptCoordinator = ChatReadReceiptCoordinator(
        scope = viewModelScope,
        messageIndex = { eventId ->
            _uiState.value.chatMessages.indexOfFirst { it.eventId == eventId }
                .takeIf { it >= 0 }
        },
        sendReadReceipt = { roomId, eventId ->
            matrixClientService.sendReadReceipt(roomId, eventId)
        },
        onSendFailure = { error ->
            Log.w(TAG, "Failed to send read receipt", error)
        }
    )
    private val chatTimelineStore = createChatTimelineStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        onWindowUpdate = { target, update, isAtLiveEdge ->
            applyChatTimelineWindowUpdate(target, update, isAtLiveEdge)
        },
        onTimelineSettled = ::settleChatTimeline,
        onTimelineError = ::failChatTimeline,
        onPaginationResult = ::applyChatTimelinePaginationResult,
        onPaginationError = { target, _ ->
            clearChatTimelinePaginationLoading(target)
        },
        onNavigationResult = ::applyChatTimelineNavigationResult,
        onNavigationError = ::failChatTimelineNavigation,
        onNavigationTrace = ::logTeleport
    )
    private var visibleRoomRefreshRequestCount = 0
    private var openRoomJob: Job? = null
    private var roomCacheJob: Job? = null
    private var roomListLiveJob: Job? = null
    private var callHistoryCacheJob: Job? = null
    private var callHistoryRefreshJob: Job? = null
    private var callHistoryExpiryRefreshJob: Job? = null
    private var roomCacheUserId: String? = null
    private var roomListLiveUserId: String? = null
    private var callHistoryCacheUserId: String? = null
    private var ownProfileLoadJob: Job? = null
    private var ownProfileUserId: String? = null
    private var ownProfileLoadGeneration = 0L
    private var ownProfileEditSessionCounter = 0L
    private var contactsSearchJob: Job? = null
    private var contactsSearchGeneration = 0L
    private var userProfileLoadJob: Job? = null
    private var userProfileLoadGeneration = 0L
    private var contactActionJob: Job? = null
    private var contactActionGeneration = 0L
    private var pendingNativeMatrixRtcCallLaunchCounter = 0L
    private val externalRouteCoordinator = ExternalRouteCoordinator()
    private var chatCallInfoJob: Job? = null
    private var chatCallInfoUserId: String? = null
    private var chatCallInfoRoomId: String? = null
    private var chatCallInfoObserverEnabled: Boolean = false

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
            uiState.collect(::consumePendingExternalRouteIfReady)
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
                    stopCallHistoryCache()
                    stopRoomListLiveRefresh()
                    stopOwnProfileLoad(clearState = true)
                    stopContactJobs(clearState = true)
                }

                _uiState.update { current ->
                    val shouldClearSessionData = nextUserId == null || didChangeUser
                    val shouldClearChat = shouldClearSessionData ||
                        matrixState is MatrixClientState.Error

                    val nextNavState = navStateForState(
                        matrixState,
                        if (didChangeUser) AppNavState() else current.navState,
                        current.sessionSecurity
                    )

                    current.copy(
                        matrixState = matrixState,
                        navState = nextNavState,
                        ownProfile = when {
                            shouldClearSessionData -> OwnProfileUiState()
                            current.ownProfile.userId.isBlank() -> {
                                current.ownProfile.copy(userId = nextUserId.orEmpty())
                            }
                            else -> current.ownProfile
                        },
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
                        callHistory = if (shouldClearSessionData) {
                            emptyList()
                        } else {
                            current.callHistory
                        },
                        isRefreshingCallHistory = if (shouldClearSessionData) {
                            false
                        } else {
                            current.isRefreshingCallHistory
                        },
                        callHistoryErrorMessage = if (shouldClearSessionData) {
                            null
                        } else {
                            current.callHistoryErrorMessage
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
                        },
                        chatMessages = if (shouldClearChat) emptyList() else current.chatMessages,
                        chatWindowChangeOrigin = if (shouldClearChat) {
                            TimelineWindowChangeOrigin.INITIAL_LOAD
                        } else {
                            current.chatWindowChangeOrigin
                        },
                        chatTimelineFlushSummary = if (shouldClearChat) {
                            null
                        } else {
                            current.chatTimelineFlushSummary
                        },
                        isLoadingChat = if (shouldClearChat) false else current.isLoadingChat,
                        isLoadingOlderChatMessages = if (shouldClearChat) {
                            false
                        } else {
                            current.isLoadingOlderChatMessages
                        },
                        canLoadOlderChatMessages = if (shouldClearChat) {
                            true
                        } else {
                            current.canLoadOlderChatMessages
                        },
                        canLoadNewerChatMessages = if (shouldClearChat) {
                            false
                        } else {
                            current.canLoadNewerChatMessages
                        },
                        isChatAtLiveEdge = if (shouldClearChat) true else current.isChatAtLiveEdge,
                        chatErrorMessage = if (shouldClearChat) null else current.chatErrorMessage,
                        isSendingChatMessage = if (shouldClearChat) false else current.isSendingChatMessage,
                        chatSendErrorMessage = if (shouldClearChat) null else current.chatSendErrorMessage,
                        chatReplyTarget = if (shouldClearChat) null else current.chatReplyTarget,
                        chatEditTarget = if (shouldClearChat) null else current.chatEditTarget,
                        chatForwardTarget = if (shouldClearChat) null else current.chatForwardTarget,
                        pendingForwardTarget = if (shouldClearSessionData) {
                            null
                        } else {
                            current.pendingForwardTarget
                        },
                        chatJumpTargetEventId = if (shouldClearChat) {
                            null
                        } else {
                            current.chatJumpTargetEventId
                        },
                        chatScrollToLiveEdgeRequested = if (shouldClearChat) {
                            false
                        } else {
                            current.chatScrollToLiveEdgeRequested
                        },
                        chatCallBanner = if (shouldClearChat) null else current.chatCallBanner
                    )
                }

                if (nextUserId != null) {
                    startRoomCache(nextUserId)
                    startCallHistoryCache(nextUserId)
                    startOwnProfileLoad(nextUserId)
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
            outgoingOutboxService.sendFailures.collect { failure ->
                _uiState.update {
                    if (!it.isRouteForRoom(failure.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = failure.message)
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
                "route=${_uiState.value.route.perfName()} currentMessages=${_uiState.value.chatMessages.size}"
        }
        stopChatTimeline()
        ZynaPerfLog.end(
            requestStart,
            "openRoom.stopPrevious"
        ) {
            "roomId=${room.id}"
        }

        val storeStart = ZynaPerfLog.start()
        val timelineStore = RoomTimelineWindowStore(
            userId = userId,
            roomId = room.id,
            localCacheRepository = localCacheRepository
        )
        ZynaPerfLog.end(storeStart, "openRoom.createStore") { "roomId=${room.id}" }

        val routeUpdateStart = ZynaPerfLog.start()
        _uiState.update {
            it.enterChatLoadingState(
                userId = userId,
                room = room,
                forwardTarget = forwardTarget
            )
        }
        startChatCallInfoObserver(userId, room.id)
        ZynaPerfLog.end(routeUpdateStart, "openRoom.routeUpdate") {
            "roomId=${room.id}"
        }

        openRoomJob = viewModelScope.launch {
            val jobStart = ZynaPerfLog.start()
            ZynaPerfLog.mark { "openRoom.job.start roomId=${room.id}" }
            val snapshotStart = ZynaPerfLog.start()
            val initialMessages = try {
                timelineStore.initialMessagesSnapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to load initial chat window from cache", error)
                emptyList()
            }
            ZynaPerfLog.end(
                snapshotStart,
                "openRoom.initialSnapshot"
            ) {
                "roomId=${room.id} count=${initialMessages.size}"
            }

            val stateUpdateStart = ZynaPerfLog.start()
            _uiState.update {
                it.applyInitialChatSnapshot(
                    userId = userId,
                    roomId = room.id,
                    initialMessages = initialMessages
                )
            }
            ZynaPerfLog.end(
                stateUpdateStart,
                "openRoom.stateUpdate"
            ) {
                "roomId=${room.id} count=${initialMessages.size}"
            }

            if (_uiState.value.isRouteForRoom(userId, room.id)) {
                val startTimelineStart = ZynaPerfLog.start()
                startChatTimeline(
                    userId = userId,
                    roomId = room.id,
                    resetMessages = false,
                    timelineStore = timelineStore
                )
                initialEventId?.let(::jumpToChatEvent)
                ZynaPerfLog.end(
                    startTimelineStart,
                    "openRoom.startTimeline"
                ) {
                    "roomId=${room.id}"
                }
            }
            ZynaPerfLog.end(
                jobStart,
                "openRoom.job.done"
            ) {
                "roomId=${room.id} count=${initialMessages.size}"
            }
        }.also { job ->
            job.invokeOnCompletion {
                ZynaPerfLog.mark {
                    "openRoom.job.complete roomId=${room.id} " +
                        "cancelled=${job.isCancelled}"
                }
                if (openRoomJob == job) {
                    openRoomJob = null
                }
            }
        }
    }

    fun closeChat() {
        stopChatTimeline()
        _uiState.update {
            it.copy(
                navState = it.navState.closeChat(),
                chatMessages = emptyList(),
                chatWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
                chatTimelineFlushSummary = null,
                isLoadingChat = false,
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                canLoadNewerChatMessages = false,
                isChatAtLiveEdge = true,
                chatErrorMessage = null,
                isSendingChatMessage = false,
                chatSendErrorMessage = null,
                chatReplyTarget = null,
                chatEditTarget = null,
                chatForwardTarget = null,
                pendingForwardTarget = null,
                chatJumpTargetEventId = null,
                chatScrollToLiveEdgeRequested = false,
                chatCallBanner = null,
                pendingNativeMatrixRtcCallLaunch = null
            )
        }
    }

    fun selectTab(tab: AppTab) {
        val state = _uiState.value
        if (state.route == AppRoute.EditProfile && state.ownProfile.isSaving) {
            return
        }
        var draftPathToDelete: String? = null
        _uiState.update { current ->
            val isClosingEditProfile = current.route == AppRoute.EditProfile
            val nextProfile = if (isClosingEditProfile) {
                draftPathToDelete = current.ownProfile.editAvatarLocalPath
                current.ownProfile.clearedEditState()
            } else {
                current.ownProfile
            }
            val nextNavState = if (isClosingEditProfile) {
                current.navState.closeEditProfile()
            } else {
                current.navState
            }
            current.copy(
                navState = nextNavState.selectTab(tab),
                ownProfile = nextProfile
            )
        }
        deleteProfileAvatarDraft(draftPathToDelete)
        if (tab == AppTab.PROFILE) {
            _uiState.value.matrixState.userIdOrNull()?.let(::startOwnProfileLoad)
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
                if (_uiState.value.ownProfile.isSaving) {
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
        ownProfileLoadJob?.cancel()
        ownProfileLoadJob = null
        ownProfileLoadGeneration += 1
        ownProfileUserId = null
        val previousDraftPath = _uiState.value.ownProfile.editAvatarLocalPath
        val editSessionId = nextOwnProfileEditSessionId()
        _uiState.update { current ->
            val profile = current.ownProfile
            current.copy(
                navState = current.navState.openEditProfile(),
                ownProfile = profile.copy(
                    editDisplayName = profile.displayName.orEmpty(),
                    editAvatarLocalPath = null,
                    editAvatarMimeType = "image/jpeg",
                    editAvatarChange = OwnProfileAvatarChange.KEEP,
                    editSessionId = editSessionId,
                    isLoading = false,
                    errorMessage = null
                )
            )
        }
        deleteProfileAvatarDraft(previousDraftPath)
    }

    fun openChatThemeSettings() {
        _uiState.update { current ->
            current.copy(navState = current.navState.openChatThemeSettings())
        }
    }

    fun refreshOwnProfile() {
        _uiState.value.matrixState.userIdOrNull()?.let { userId ->
            startOwnProfileLoad(userId = userId, force = true)
        }
    }

    fun setOwnProfileDisplayNameDraft(displayName: String) {
        _uiState.update { current ->
            current.copy(
                ownProfile = current.ownProfile.copy(
                    editDisplayName = displayName,
                    errorMessage = null
                )
            )
        }
    }

    fun setOwnProfileAvatarDraft(draft: ProfileAvatarDraft, editSessionId: Long) {
        val state = _uiState.value
        if (
            state.route != AppRoute.EditProfile ||
            state.ownProfile.editSessionId != editSessionId ||
            state.ownProfile.isSaving
        ) {
            deleteProfileAvatarDraft(draft.localPath)
            return
        }
        val previousPath = _uiState.value.ownProfile.editAvatarLocalPath
        _uiState.update { current ->
            current.copy(
                ownProfile = current.ownProfile.copy(
                    editAvatarLocalPath = draft.localPath,
                    editAvatarMimeType = draft.mimeType,
                    editAvatarChange = OwnProfileAvatarChange.REPLACE,
                    errorMessage = null
                )
            )
        }
        if (previousPath != draft.localPath) {
            deleteProfileAvatarDraft(previousPath)
        }
    }

    fun setOwnProfileEditError(message: String, editSessionId: Long) {
        val state = _uiState.value
        if (
            state.route != AppRoute.EditProfile ||
            state.ownProfile.editSessionId != editSessionId ||
            state.ownProfile.isSaving
        ) {
            return
        }
        _uiState.update { current ->
            current.copy(
                ownProfile = current.ownProfile.copy(
                    isSaving = false,
                    errorMessage = message
                )
            )
        }
    }

    fun removeOwnProfileAvatarDraft() {
        val previousPath = _uiState.value.ownProfile.editAvatarLocalPath
        _uiState.update { current ->
            val nextChange = if (current.ownProfile.avatarUrl.isNullOrBlank()) {
                OwnProfileAvatarChange.KEEP
            } else {
                OwnProfileAvatarChange.REMOVE
            }
            current.copy(
                ownProfile = current.ownProfile.copy(
                    editAvatarLocalPath = null,
                    editAvatarMimeType = "image/jpeg",
                    editAvatarChange = nextChange,
                    errorMessage = null
                )
            )
        }
        deleteProfileAvatarDraft(previousPath)
    }

    fun cancelOwnProfileEdit() {
        val previousPath = _uiState.value.ownProfile.editAvatarLocalPath
        _uiState.update { current ->
            val profile = current.ownProfile
            current.copy(
                ownProfile = profile.clearedEditState()
            )
        }
        deleteProfileAvatarDraft(previousPath)
    }

    fun saveOwnProfile() {
        val state = _uiState.value
        state.matrixState.userIdOrNull() ?: return
        val profile = state.ownProfile
        if (profile.isSaving) {
            return
        }
        val nextDisplayName = profile.editDisplayName.trim()
        val currentDisplayName = profile.displayName.orEmpty()
        val didChangeName = nextDisplayName != currentDisplayName
        val avatarChange = profile.editAvatarChange
        val avatarPath = profile.editAvatarLocalPath
        val avatarMimeType = profile.editAvatarMimeType
        val didChangeAvatar = avatarChange != OwnProfileAvatarChange.KEEP
        if (!didChangeName && !didChangeAvatar) {
            cancelOwnProfileEdit()
            popActiveStack()
            return
        }

        ownProfileLoadJob?.cancel()
        ownProfileLoadJob = null
        ownProfileLoadGeneration += 1
        ownProfileUserId = null
        _uiState.update { current ->
            current.copy(
                ownProfile = current.ownProfile.copy(
                    isSaving = true,
                    errorMessage = null
                )
            )
        }

        viewModelScope.launch {
            try {
                if (didChangeName) {
                    matrixClientService.setOwnDisplayName(nextDisplayName)
                }
                when (avatarChange) {
                    OwnProfileAvatarChange.KEEP -> Unit
                    OwnProfileAvatarChange.REPLACE -> {
                        val localPath = avatarPath
                            ?: error("Avatar file is not available")
                        matrixClientService.uploadOwnAvatar(
                            localPath = localPath,
                            mimeType = avatarMimeType
                        )
                    }
                    OwnProfileAvatarChange.REMOVE -> {
                        matrixClientService.removeOwnAvatar()
                    }
                }
                val refreshed = matrixClientService.loadOwnProfile()
                deleteProfileAvatarDraft(avatarPath)
                ownProfileUserId = refreshed.userId
                _uiState.update { current ->
                    current.copy(
                        navState = current.navState.closeEditProfile(),
                        ownProfile = refreshed.toUiState()
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to save own profile", error)
                _uiState.update { current ->
                    current.copy(
                        ownProfile = current.ownProfile.copy(
                            isSaving = false,
                            errorMessage = error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
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
                    callHistoryErrorMessage = null,
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

    private fun startOwnProfileLoad(userId: String, force: Boolean = false) {
        if (!force && _uiState.value.route == AppRoute.EditProfile) {
            return
        }
        if (!force && ownProfileUserId == userId && _uiState.value.ownProfile.errorMessage == null) {
            return
        }
        if (ownProfileLoadJob?.isActive == true && ownProfileUserId == userId) {
            return
        }
        ownProfileLoadJob?.cancel()
        ownProfileUserId = userId
        ownProfileLoadGeneration += 1
        val loadGeneration = ownProfileLoadGeneration
        _uiState.update { current ->
            current.copy(
                ownProfile = current.ownProfile.copy(
                    userId = userId,
                    isLoading = true,
                    errorMessage = null
                )
            )
        }
        val loadJob = viewModelScope.launch {
            try {
                val profile = matrixClientService.loadOwnProfile()
                if (ownProfileLoadGeneration != loadGeneration) {
                    return@launch
                }
                _uiState.update { current ->
                    if (ownProfileLoadGeneration != loadGeneration) {
                        current
                    } else {
                        current.copy(ownProfile = profile.toUiState())
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (ownProfileLoadGeneration != loadGeneration) {
                    return@launch
                }
                Log.w(TAG, "Failed to load own profile", error)
                _uiState.update { current ->
                    if (ownProfileLoadGeneration != loadGeneration) {
                        current
                    } else {
                        current.copy(
                            ownProfile = current.ownProfile.copy(
                                userId = userId,
                                isLoading = false,
                                errorMessage = error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
            }
        }
        ownProfileLoadJob = loadJob
        loadJob.invokeOnCompletion {
            if (ownProfileLoadJob === loadJob) {
                ownProfileLoadJob = null
            }
        }
    }

    private fun stopOwnProfileLoad(clearState: Boolean) {
        ownProfileLoadJob?.cancel()
        ownProfileLoadJob = null
        ownProfileLoadGeneration += 1
        ownProfileUserId = null
        if (clearState) {
            val draftPath = _uiState.value.ownProfile.editAvatarLocalPath
            _uiState.update { it.copy(ownProfile = OwnProfileUiState()) }
            deleteProfileAvatarDraft(draftPath)
        }
    }

    private fun MatrixOwnProfile.toUiState(): OwnProfileUiState {
        return OwnProfileUiState(
            userId = userId,
            displayName = displayName?.takeIf { it.isNotBlank() },
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            isLoading = false,
            isSaving = false,
            errorMessage = null,
            editDisplayName = displayName.orEmpty()
        )
    }

    private fun OwnProfileUiState.clearedEditState(): OwnProfileUiState {
        return copy(
            editDisplayName = displayName.orEmpty(),
            editAvatarLocalPath = null,
            editAvatarMimeType = "image/jpeg",
            editAvatarChange = OwnProfileAvatarChange.KEEP,
            editSessionId = 0L,
            isSaving = false,
            errorMessage = null
        )
    }

    private fun nextOwnProfileEditSessionId(): Long {
        ownProfileEditSessionCounter += 1
        return ownProfileEditSessionCounter
    }

    private fun deleteProfileAvatarDraft(path: String?) {
        path?.takeIf { it.isNotBlank() }?.let { localPath ->
            runCatching { File(localPath).delete() }
        }
    }

    fun setChatCallInfoObserverEnabled(enabled: Boolean) {
        if (chatCallInfoObserverEnabled == enabled) {
            return
        }
        logChatCall("chatCallInfoObserver enabled=$enabled")
        chatCallInfoObserverEnabled = enabled
        if (enabled) {
            startChatCallInfoObserverJobForTarget()
        } else {
            pauseChatCallInfoObserver()
        }
    }

    fun sendChatMessage(body: String): Boolean {
        val route = _uiState.value.activeChatRoute ?: return false
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return false
        val state = _uiState.value
        val forwardTarget = state.chatForwardTarget
        val isForwardingMedia = forwardTarget?.imageItems?.isNotEmpty() == true
        val text = if (isForwardingMedia) {
            forwardTarget?.body?.trim().orEmpty().ifBlank { "Photo" }
        } else {
            forwardTarget?.body?.trim() ?: body.trim()
        }
        if (text.isEmpty() || state.isSendingChatMessage) {
            return false
        }
        val replyInfo = if (forwardTarget == null) state.chatReplyTarget else null
        val editTarget = if (forwardTarget == null) state.chatEditTarget else null
        val envelopeId = "text:${UUID.randomUUID()}"
        val transactionId = matrixClientService.prepareTransactionId()

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(
                isSendingChatMessage = true,
                chatSendErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                if (editTarget != null) {
                    val didPrepare = localCacheRepository.prepareOutgoingTextEdit(
                        userId = userId,
                        roomId = route.roomId,
                        targetMessage = MatrixChatMessage(
                            id = editTarget.messageId,
                            eventId = editTarget.eventId,
                            sender = userId,
                            body = editTarget.body,
                            timestampMillis = 0L,
                            isOwn = true
                        ),
                        body = text,
                        transactionId = transactionId
                    )
                    if (didPrepare) {
                        outgoingOutboxService.kick(reason = "new-edit")
                    }
                } else if (isForwardingMedia) {
                    val mediaForwardTarget = forwardTarget ?: return@launch
                    val items = mediaForwardTarget.imageItems
                    val groupId = "forwarded-photo-group:${UUID.randomUUID()}"
                    val shouldWriteMediaGroup = items.size > 1 ||
                        mediaForwardTarget.captionPlacement != CaptionPlacement.BOTTOM ||
                        mediaForwardTarget.layoutOverride != null
                    items.forEachIndexed { index, item ->
                        val envelopeId = "image:${UUID.randomUUID()}"
                        val transactionId = matrixClientService.prepareTransactionId()
                        val attributes = ZynaMessageAttributes(
                            forwardedFrom = mediaForwardTarget.forwardedFrom,
                            mediaGroup = if (shouldWriteMediaGroup) {
                                MediaGroupInfo(
                                    id = groupId,
                                    index = index,
                                    total = items.size,
                                    captionMode = CaptionMode.REPLICATED,
                                    captionPlacement = mediaForwardTarget.captionPlacement,
                                    layoutOverride = mediaForwardTarget.layoutOverride
                                        .takeIf { items.size > 1 }
                                )
                            } else {
                                null
                            }
                        )
                        localCacheRepository.createOutgoingForwardedImageEnvelope(
                            userId = userId,
                            roomId = route.roomId,
                            envelopeId = envelopeId,
                            transactionId = transactionId,
                            image = item,
                            caption = mediaForwardTarget.caption,
                            zynaAttributes = attributes
                        )
                    }
                    outgoingOutboxService.kick(reason = "new-forwarded-images")
                } else {
                    localCacheRepository.createOutgoingTextEnvelope(
                        userId = userId,
                        roomId = route.roomId,
                        envelopeId = envelopeId,
                        transactionId = transactionId,
                        body = text,
                        replyInfo = replyInfo,
                        forwardedFrom = forwardTarget?.forwardedFrom
                    )
                    outgoingOutboxService.kick(
                        reason = "new-envelope",
                        envelopeId = envelopeId
                    )
                }
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = null,
                        chatReplyTarget = null,
                        chatEditTarget = null,
                        chatForwardTarget = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return true
    }

    fun sendPhotoMessages(draft: OutgoingPhotoDraft): Boolean {
        val route = _uiState.value.activeChatRoute ?: return false
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return false
        val items = draft.items.filter { it.localPath.isNotBlank() }
        if (items.isEmpty() || _uiState.value.isSendingChatMessage) {
            return false
        }

        val groupId = "photo-group:${UUID.randomUUID()}"
        val shouldWriteMediaGroup = items.size > 1 ||
            draft.captionPlacement != CaptionPlacement.BOTTOM ||
            draft.layoutOverride != null

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(
                isSendingChatMessage = true,
                chatSendErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                items.forEachIndexed { index, item ->
                    val envelopeId = "image:${UUID.randomUUID()}"
                    val transactionId = matrixClientService.prepareTransactionId()
                    val attributes = if (shouldWriteMediaGroup) {
                        ZynaMessageAttributes(
                            mediaGroup = MediaGroupInfo(
                                id = groupId,
                                index = index,
                                total = items.size,
                                captionMode = CaptionMode.REPLICATED,
                                captionPlacement = draft.captionPlacement,
                                layoutOverride = draft.layoutOverride.takeIf { items.size > 1 }
                            )
                        )
                    } else {
                        ZynaMessageAttributes()
                    }
                    localCacheRepository.createOutgoingImageEnvelope(
                        userId = userId,
                        roomId = route.roomId,
                        envelopeId = envelopeId,
                        transactionId = transactionId,
                        localPath = item.localPath,
                        mimeType = item.mimeType,
                        width = item.width,
                        height = item.height,
                        sizeBytes = item.sizeBytes,
                        thumbnailLocalPath = item.thumbnailLocalPath,
                        thumbnailMimeType = item.thumbnailMimeType,
                        thumbnailWidth = item.thumbnailWidth,
                        thumbnailHeight = item.thumbnailHeight,
                        thumbnailSizeBytes = item.thumbnailSizeBytes,
                        blurhash = item.blurhash,
                        caption = draft.caption,
                        zynaAttributes = attributes
                    )
                }
                outgoingOutboxService.kick(reason = "new-images")
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return true
    }

    fun sendVoiceMessage(
        draft: OutgoingVoiceDraft,
        onEnqueued: () -> Unit = {}
    ): Boolean {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return false
        val userId = state.matrixState.userIdOrNull() ?: return false
        if (draft.localPath.isBlank() || state.isSendingChatMessage) {
            return false
        }
        val replyInfo = state.chatReplyTarget

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(
                isSendingChatMessage = true,
                chatSendErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val envelopeId = "voice:${UUID.randomUUID()}"
                val transactionId = matrixClientService.prepareTransactionId()
                localCacheRepository.createOutgoingVoiceEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId,
                    transactionId = transactionId,
                    localPath = draft.localPath,
                    mimeType = draft.mimeType,
                    sizeBytes = draft.sizeBytes,
                    durationMillis = draft.durationMillis,
                    waveform = draft.waveform,
                    replyInfo = replyInfo
                )
                outgoingOutboxService.kick(
                    reason = "new-voice",
                    envelopeId = envelopeId
                )
                onEnqueued()
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = null,
                        chatReplyTarget = null,
                        chatEditTarget = null,
                        chatForwardTarget = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return true
    }

    fun toggleReaction(messageId: String, reactionKey: String) {
        val state = _uiState.value
        val route = state.activeChatRoute ?: return
        val userId = state.matrixState.userIdOrNull() ?: return
        val key = reactionKey.takeIf { it.isNotBlank() } ?: return
        val message = state.chatMessages.firstOrNull { it.id == messageId } ?: return
        val targetEventId = message.eventId?.takeIf { it.isNotBlank() } ?: return
        if (
            message.contentType == MatrixMessageContentType.REDACTED ||
            message.outgoingEnvelopeId != null
        ) {
            return
        }
        val ownReaction = message.reactions.firstOrNull {
            it.key == key && it.isOwn
        }
        val shouldRemove = ownReaction != null && !ownReaction.isPendingRemoval

        viewModelScope.launch {
            try {
                if (shouldRemove) {
                    val transactionId = matrixClientService.prepareTransactionId()
                    var reactionId = localCacheRepository.prepareOutgoingReactionRemoval(
                        userId = userId,
                        roomId = route.roomId,
                        targetEventId = targetEventId,
                        reactionKey = key,
                        reactionEventId = null,
                        transactionId = transactionId
                    )
                    if (reactionId == null) {
                        val reactionEventId = matrixClientService.findOwnReactionEventId(
                            roomId = route.roomId,
                            targetEventId = targetEventId,
                            reactionKey = key,
                            userId = userId
                        )
                        if (reactionEventId != null) {
                            reactionId = localCacheRepository.prepareOutgoingReactionRemoval(
                                userId = userId,
                                roomId = route.roomId,
                                targetEventId = targetEventId,
                                reactionKey = key,
                                reactionEventId = reactionEventId,
                                transactionId = transactionId
                            )
                        }
                    }
                    if (reactionId != null) {
                        outgoingOutboxService.kick(
                            reason = "new-reaction-removal",
                            envelopeId = reactionId
                        )
                    }
                } else {
                    val transactionId = matrixClientService.prepareTransactionId()
                    val reactionId = localCacheRepository.prepareOutgoingReactionAdd(
                        userId = userId,
                        roomId = route.roomId,
                        targetEventId = targetEventId,
                        reactionKey = key,
                        transactionId = transactionId
                    )
                    if (reactionId != null) {
                        outgoingOutboxService.kick(
                            reason = "new-reaction",
                            envelopeId = reactionId
                        )
                    }
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun setChatReplyTarget(replyInfo: MatrixReplyInfo) {
        val route = _uiState.value.activeChatRoute ?: return
        if (replyInfo.eventId.isBlank()) {
            return
        }
        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else {
                it.copy(
                    chatReplyTarget = replyInfo,
                    chatEditTarget = null,
                    chatForwardTarget = null
                )
            }
        }
    }

    fun setChatEditTarget(editTarget: MatrixEditTarget) {
        val route = _uiState.value.activeChatRoute ?: return
        if (editTarget.eventId.isBlank() || editTarget.body.isBlank()) {
            return
        }
        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else {
                it.copy(
                    chatReplyTarget = null,
                    chatEditTarget = editTarget,
                    chatForwardTarget = null
                )
            }
        }
    }

    fun startForwardMessage(target: MatrixForwardTarget) {
        if (target.body.isBlank() && target.imageItems.isEmpty()) {
            return
        }
        _uiState.update { current ->
            current.copy(
                navState = current.navState.openForwardPicker(),
                pendingForwardTarget = target,
                chatReplyTarget = null,
                chatEditTarget = null,
                chatForwardTarget = null
            )
        }
    }

    fun cancelForwardPicker() {
        _uiState.update { current ->
            current.copy(
                navState = current.navState.closeForwardPicker(),
                pendingForwardTarget = null,
            )
        }
    }

    fun selectForwardRoom(room: MatrixRoomSummary) {
        val target = _uiState.value.pendingForwardTarget ?: return
        openRoom(room, forwardTarget = target)
    }

    fun clearChatForwardTarget() {
        _uiState.update {
            if (it.chatForwardTarget == null) {
                it
            } else {
                it.copy(chatForwardTarget = null)
            }
        }
    }

    fun clearChatReplyTarget() {
        _uiState.update {
            if (it.chatReplyTarget == null) {
                it
            } else {
                it.copy(chatReplyTarget = null)
            }
        }
    }

    fun clearChatEditTarget() {
        _uiState.update {
            if (it.chatEditTarget == null) {
                it
            } else {
                it.copy(chatEditTarget = null)
            }
        }
    }

    fun retryOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.activeChatRoute ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                val didRetry = localCacheRepository.retryFailedOutgoingMessageEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
                if (!didRetry) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
                outgoingOutboxService.kick(
                    reason = "manual-retry",
                    envelopeId = envelopeId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun discardOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.activeChatRoute ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                val didDiscard = localCacheRepository.discardFailedOutgoingMessageEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
                if (!didDiscard) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun redactMessage(messageId: String) {
        redactMessages(listOf(messageId))
    }

    fun redactMessages(messageIds: List<String>) {
        val route = _uiState.value.activeChatRoute ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        val distinctMessageIds = messageIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (distinctMessageIds.isEmpty()) {
            return
        }
        val targetMessagesById = _uiState.value.chatMessages.associateBy { it.id }
        val targetMessages = distinctMessageIds
            .mapNotNull { targetMessagesById[it] }
            .filter { targetMessage ->
                targetMessage.isOwn &&
                    targetMessage.eventId != null &&
                    targetMessage.contentType != MatrixMessageContentType.REDACTED &&
                    targetMessage.deliveryState == MatrixMessageDeliveryState.SENT
            }
        if (targetMessages.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                val createdEnvelopeIds = mutableListOf<String>()
                for (targetMessage in targetMessages) {
                    val envelopeId = "redaction:${UUID.randomUUID()}"
                    val transactionId = matrixClientService.prepareTransactionId()
                    val didCreate = localCacheRepository.createOutgoingRedactionEnvelope(
                        userId = userId,
                        roomId = route.roomId,
                        envelopeId = envelopeId,
                        transactionId = transactionId,
                        targetMessage = targetMessage
                    )
                    if (didCreate) {
                        createdEnvelopeIds += envelopeId
                    }
                }
                if (createdEnvelopeIds.isEmpty()) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
                outgoingOutboxService.kick(
                    reason = if (createdEnvelopeIds.size == 1) "new-redaction" else "new-redactions",
                    envelopeId = createdEnvelopeIds.singleOrNull()
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
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
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun loadOlderChatMessages() {
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        if (
            state.isLoadingChat ||
            state.isLoadingOlderChatMessages ||
            !state.canLoadOlderChatMessages ||
            chatTimelineStore.hasActiveWindowOperation()
        ) {
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(isLoadingOlderChatMessages = true)
        }

        if (!chatTimelineStore.loadOlder(target)) {
            clearChatTimelinePaginationLoading(target)
        }
    }

    fun loadNewerChatMessages() {
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        if (
            state.isLoadingChat ||
            state.isLoadingOlderChatMessages ||
            !state.canLoadNewerChatMessages ||
            chatTimelineStore.hasActiveWindowOperation()
        ) {
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(isLoadingOlderChatMessages = true)
        }

        if (!chatTimelineStore.loadNewer(target)) {
            clearChatTimelinePaginationLoading(target)
        }
    }

    fun jumpToChatEvent(eventId: String) {
        val normalizedEventId = eventId.takeIf { it.isNotBlank() } ?: return
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        if (chatTimelineStore.hasActiveWindowOperation()) {
            logTeleport("jump cancels activeWindowOperation target=${normalizedEventId.shortLogId()}")
            chatTimelineStore.cancelWindowOperation()
        }
        logTeleport(
            "jump request target=${normalizedEventId.shortLogId()} " +
                "messages=${state.chatMessages.size} canOlder=${state.canLoadOlderChatMessages} " +
                "canNewer=${state.canLoadNewerChatMessages} live=${state.isChatAtLiveEdge}"
        )

        val isTargetInCurrentWindow = state.chatMessages.any { message ->
            message.eventId == normalizedEventId || message.id == normalizedEventId
        }
        if (isTargetInCurrentWindow) {
            logTeleport("jump local target=${normalizedEventId.shortLogId()}")
            _uiState.update {
                if (!it.isRouteForRoom(userId, route.roomId)) {
                    it
                } else it.copy(
                    chatWindowChangeOrigin = TimelineWindowChangeOrigin.JUMP,
                    chatTimelineFlushSummary = null,
                    isLoadingOlderChatMessages = false,
                    chatSendErrorMessage = null,
                    chatJumpTargetEventId = normalizedEventId
                )
            }
            chatReadReceiptCoordinator.reset()
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = true,
                chatSendErrorMessage = null,
                chatJumpTargetEventId = normalizedEventId
            )
        }
        chatReadReceiptCoordinator.reset()

        if (!chatTimelineStore.jumpToEvent(target, normalizedEventId)) {
            logTeleport("jump abort unavailable target=${normalizedEventId.shortLogId()}")
            clearChatTimelineEventJump(target, normalizedEventId)
        }
    }

    fun clearChatJumpTarget(eventId: String) {
        _uiState.update {
            if (it.chatJumpTargetEventId == eventId) {
                it.copy(chatJumpTargetEventId = null)
            } else {
                it
            }
        }
    }

    fun jumpToChatLiveEdge() {
        val route = _uiState.value.activeChatRoute ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        val target = ChatTimelineTarget(userId = userId, roomId = route.roomId)
        if (chatTimelineStore.hasActiveWindowOperation()) {
            logTeleport("live cancels activeWindowOperation")
            chatTimelineStore.cancelWindowOperation()
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = true,
                chatSendErrorMessage = null,
                chatJumpTargetEventId = null,
                chatScrollToLiveEdgeRequested = true
            )
        }
        chatReadReceiptCoordinator.reset()

        if (!chatTimelineStore.jumpToLiveEdge(target)) {
            logTeleport("live abort unavailable")
            clearChatTimelineLiveEdgeJump(target)
        }
    }

    fun clearChatScrollToLiveEdgeRequest() {
        _uiState.update {
            if (it.chatScrollToLiveEdgeRequested) {
                it.copy(chatScrollToLiveEdgeRequested = false)
            } else {
                it
            }
        }
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

    private fun startChatTimeline(
        userId: String,
        roomId: String,
        resetMessages: Boolean,
        timelineStore: RoomTimelineWindowStore = RoomTimelineWindowStore(
            userId = userId,
            roomId = roomId,
            localCacheRepository = localCacheRepository
        )
    ) {
        chatTimelineStore.deactivate()
        ZynaPerfLog.mark {
            "startChatTimeline.begin roomId=$roomId reset=$resetMessages " +
                "currentMessages=${_uiState.value.chatMessages.size}"
        }
        val initialStateStart = ZynaPerfLog.start()
        _uiState.update {
            val nextMessages = if (resetMessages) emptyList() else it.chatMessages
            it.copy(
                chatMessages = nextMessages,
                chatWindowChangeOrigin = if (resetMessages) {
                    TimelineWindowChangeOrigin.INITIAL_LOAD
                } else {
                    it.chatWindowChangeOrigin
                },
                chatTimelineFlushSummary = if (resetMessages) null else it.chatTimelineFlushSummary,
                isLoadingChat = nextMessages.isEmpty(),
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                canLoadNewerChatMessages = if (resetMessages) false else it.canLoadNewerChatMessages,
                isChatAtLiveEdge = if (resetMessages) true else it.isChatAtLiveEdge,
                chatErrorMessage = null,
                chatJumpTargetEventId = if (resetMessages) null else it.chatJumpTargetEventId,
                chatScrollToLiveEdgeRequested = if (resetMessages) {
                    false
                } else {
                    it.chatScrollToLiveEdgeRequested
                }
            )
        }
        ZynaPerfLog.end(
            initialStateStart,
            "startChatTimeline.initialState"
        ) {
            "roomId=$roomId reset=$resetMessages messages=${_uiState.value.chatMessages.size}"
        }
        chatTimelineStore.activate(
            target = ChatTimelineTarget(userId = userId, roomId = roomId),
            windowStore = timelineStore
        )
    }

    private fun applyChatTimelineWindowUpdate(
        target: ChatTimelineTarget,
        update: TimelineWindowUpdate<MatrixChatMessage>,
        isAtLiveEdge: Boolean
    ) {
        val start = ZynaPerfLog.start()
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else it.copy(
                chatMessages = update.messages,
                chatWindowChangeOrigin = update.origin,
                chatTimelineFlushSummary = update.flushSummary,
                isLoadingChat = if (update.messages.isNotEmpty()) false else it.isLoadingChat,
                canLoadNewerChatMessages = update.hasNewerInDb,
                isChatAtLiveEdge = isAtLiveEdge
            )
        }
        ZynaPerfLog.end(
            start,
            "chatCache.collect.stateUpdate"
        ) {
            "roomId=${target.roomId} origin=${update.origin} count=${update.messages.size} " +
                "older=${update.hasOlderInDb} newer=${update.hasNewerInDb}"
        }
    }

    private fun settleChatTimeline(
        target: ChatTimelineTarget,
        messageCount: Int
    ) {
        val start = ZynaPerfLog.start()
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else it.copy(
                isLoadingChat = false,
                isLoadingOlderChatMessages = false,
                chatErrorMessage = null
            )
        }
        ZynaPerfLog.end(
            start,
            "chatTimeline.stateUpdate"
        ) {
            "roomId=${target.roomId} count=$messageCount"
        }
    }

    private fun failChatTimeline(
        target: ChatTimelineTarget,
        error: Throwable
    ) {
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else it.copy(
                isLoadingChat = false,
                isLoadingOlderChatMessages = false,
                chatErrorMessage = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun applyChatTimelinePaginationResult(
        target: ChatTimelineTarget,
        result: ChatTimelinePaginationResult
    ) {
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = result.canLoadOlder
                    ?: it.canLoadOlderChatMessages,
                canLoadNewerChatMessages = result.canLoadNewer
                    ?: it.canLoadNewerChatMessages,
                isChatAtLiveEdge = result.isAtLiveEdge ?: it.isChatAtLiveEdge
            )
        }
    }

    private fun clearChatTimelinePaginationLoading(target: ChatTimelineTarget) {
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else {
                it.copy(isLoadingOlderChatMessages = false)
            }
        }
    }

    private fun applyChatTimelineNavigationResult(
        target: ChatTimelineTarget,
        result: ChatTimelineNavigationResult
    ) {
        when (result) {
            is ChatTimelineNavigationResult.EventJump -> {
                _uiState.update {
                    if (!it.isRouteForRoom(target.userId, target.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadOlderChatMessages = result.canLoadOlder
                            ?: it.canLoadOlderChatMessages,
                        canLoadNewerChatMessages = result.canLoadNewer
                            ?: it.canLoadNewerChatMessages,
                        isChatAtLiveEdge = result.isAtLiveEdge,
                        chatJumpTargetEventId = if (result.didJump) {
                            it.chatJumpTargetEventId
                        } else if (it.chatJumpTargetEventId == result.eventId) {
                            null
                        } else {
                            it.chatJumpTargetEventId
                        }
                    )
                }
            }

            is ChatTimelineNavigationResult.LiveEdge -> {
                _uiState.update {
                    if (!it.isRouteForRoom(target.userId, target.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadOlderChatMessages = result.canLoadOlder,
                        canLoadNewerChatMessages = result.canLoadNewer,
                        isChatAtLiveEdge = result.isAtLiveEdge,
                        chatScrollToLiveEdgeRequested = result.didJump
                    )
                }
            }
        }
    }

    private fun failChatTimelineNavigation(
        target: ChatTimelineTarget,
        request: ChatTimelineNavigationRequest,
        error: Throwable
    ) {
        when (request) {
            is ChatTimelineNavigationRequest.EventJump -> {
                Log.w(TAG, "Failed to jump to chat event", error)
                clearChatTimelineEventJump(target, request.eventId)
            }

            ChatTimelineNavigationRequest.LiveEdge -> {
                Log.w(TAG, "Failed to jump to live edge", error)
                clearChatTimelineLiveEdgeJump(target)
            }
        }
    }

    private fun clearChatTimelineEventJump(
        target: ChatTimelineTarget,
        eventId: String
    ) {
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = false,
                chatJumpTargetEventId = if (it.chatJumpTargetEventId == eventId) {
                    null
                } else {
                    it.chatJumpTargetEventId
                }
            )
        }
    }

    private fun clearChatTimelineLiveEdgeJump(target: ChatTimelineTarget) {
        _uiState.update {
            if (!it.isRouteForRoom(target.userId, target.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = false,
                chatScrollToLiveEdgeRequested = false
            )
        }
    }

    private fun stopChatTimeline() {
        openRoomJob?.cancel()
        openRoomJob = null
        chatTimelineStore.deactivate()
        clearChatCallInfoObserver()
        chatReadReceiptCoordinator.reset()
    }

    private fun startChatCallInfoObserver(userId: String, roomId: String) {
        if (
            chatCallInfoUserId == userId &&
            chatCallInfoRoomId == roomId &&
            chatCallInfoJob?.isActive == true
        ) {
            return
        }

        pauseChatCallInfoObserver()
        chatCallInfoUserId = userId
        chatCallInfoRoomId = roomId
        logChatCall("chatCallInfoObserver target roomId=$roomId")
        startChatCallInfoObserverJobForTarget()
    }

    private fun startChatCallInfoObserverJobForTarget() {
        if (!chatCallInfoObserverEnabled || chatCallInfoJob?.isActive == true) {
            return
        }
        val userId = chatCallInfoUserId ?: return
        val roomId = chatCallInfoRoomId ?: return
        logChatCall("chatCallInfoObserver start roomId=$roomId")
        chatCallInfoJob = viewModelScope.launch {
            val ringOverride = MutableStateFlow<ChatMatrixRtcRingOverride?>(null)
            val membershipFallback = MutableStateFlow<MatrixRoomCallInfo?>(null)
            var lastObservedCallInfo: MatrixRoomCallInfo? = null
            var lastRoomInfoHasCall = false
            var hasObservedRoomInfoActiveCall = false
            var membershipFallbackRefreshJob: Job? = null
            var membershipFallbackValidationJob: Job? = null
            var ringExpiryJob: Job? = null
            var ringValidationJob: Job? = null
            var ringValidationEventId: String? = null
            var refreshMembershipFallback: ((String) -> Unit)? = null

            fun scheduleMembershipFallbackValidation() {
                if (membershipFallbackValidationJob?.isActive == true) {
                    return
                }
                membershipFallbackValidationJob = launch {
                    delay(MEMBERSHIP_FALLBACK_VALIDATION_DELAY_MS)
                    membershipFallbackValidationJob = null
                    refreshMembershipFallback?.invoke("activeFallbackValidation")
                }
            }

            fun applyMembershipFallbackSnapshot(
                fallback: MatrixRoomCallInfo?,
                reason: String
            ) {
                if (lastRoomInfoHasCall || hasObservedRoomInfoActiveCall) {
                    membershipFallback.value = null
                    return
                }

                membershipFallback.value = fallback
                if (fallback?.hasRoomCall == true && fallback.isAudioCall) {
                    logChatCall(
                        "chatCallMembershipFallback active roomId=$roomId " +
                            "participants=${fallback.activeParticipantCount} reason=$reason"
                    )
                    scheduleMembershipFallbackValidation()
                }
            }

            refreshMembershipFallback = refresh@ { reason ->
                if (membershipFallbackRefreshJob?.isActive == true) {
                    return@refresh
                }
                membershipFallbackRefreshJob = launch {
                    val fallback = try {
                        matrixClientService.loadRoomCallInfo(roomId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(
                            TAG,
                            "Failed loading MatrixRTC membership fallback roomId=$roomId reason=$reason",
                            error
                        )
                        null
                    }

                    membershipFallbackRefreshJob = null
                    applyMembershipFallbackSnapshot(fallback, reason)
                }
            }

            fun handleRoomInfoForMembershipFallback(callInfo: MatrixRoomCallInfo) {
                lastRoomInfoHasCall = callInfo.hasRoomCall
                if (callInfo.hasRoomCall) {
                    hasObservedRoomInfoActiveCall = true
                    membershipFallback.value = null
                    membershipFallbackRefreshJob?.cancel()
                    membershipFallbackRefreshJob = null
                    membershipFallbackValidationJob?.cancel()
                    membershipFallbackValidationJob = null
                    return
                }

                if (hasObservedRoomInfoActiveCall) {
                    membershipFallback.value = null
                    membershipFallbackRefreshJob?.cancel()
                    membershipFallbackRefreshJob = null
                    membershipFallbackValidationJob?.cancel()
                    membershipFallbackValidationJob = null
                    return
                }

                if (membershipFallback.value == null) {
                    refreshMembershipFallback?.invoke("roomInfoInactive")
                }
            }

            fun clearRingOverride(reason: String, eventId: String) {
                if (ringOverride.value?.eventId != eventId) {
                    return
                }
                ringExpiryJob?.cancel()
                ringExpiryJob = null
                ringValidationJob?.cancel()
                ringValidationJob = null
                ringValidationEventId = null
                ringOverride.value = null
                logChatCall("chatCallRingOverride cleared roomId=$roomId eventId=$eventId reason=$reason")
            }

            fun scheduleRingOverrideExpiry(override: ChatMatrixRtcRingOverride) {
                ringExpiryJob?.cancel()
                ringExpiryJob = launch {
                    delay(maxOf(0L, override.expiresAtMillis - System.currentTimeMillis()))
                    clearRingOverride(reason = "expired", eventId = override.eventId)
                }
            }

            fun scheduleRingOverrideValidation(
                eventId: String,
                senderId: String,
                reason: String,
                delayMillis: Long
            ) {
                if (ringValidationJob?.isActive == true && ringValidationEventId == eventId) {
                    return
                }
                ringValidationJob?.cancel()
                ringValidationEventId = eventId
                ringValidationJob = launch {
                    delay(delayMillis)
                    val hasActiveMembership = try {
                        matrixClientService.hasActiveMatrixRtcMembership(
                            roomId = roomId,
                            senderId = senderId
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(
                            TAG,
                            "Failed validating MatrixRTC ring membership roomId=$roomId " +
                                "eventId=$eventId reason=$reason",
                            error
                        )
                        null
                    }

                    if (ringOverride.value?.eventId != eventId) {
                        if (ringValidationEventId == eventId) {
                            ringValidationJob = null
                            ringValidationEventId = null
                        }
                        return@launch
                    }

                    ringValidationJob = null
                    ringValidationEventId = null
                    when (hasActiveMembership) {
                        true -> {
                            ringOverride.update { current ->
                                if (current?.eventId == eventId) {
                                    current.copy(hasObservedActiveCall = true)
                                } else {
                                    current
                                }
                            }
                            logChatCall(
                                "chatCallRingOverride validated roomId=$roomId eventId=$eventId reason=$reason"
                            )
                        }
                        false -> clearRingOverride(reason = reason, eventId = eventId)
                        null -> Unit
                    }
                }
            }

            fun handleRingOverrideSideEffects(
                observed: MatrixRoomCallInfo,
                override: ChatMatrixRtcRingOverride?
            ) {
                lastObservedCallInfo = observed
                val currentOverride = override ?: return
                if (currentOverride.expiresAtMillis <= System.currentTimeMillis()) {
                    clearRingOverride(reason = "expired", eventId = currentOverride.eventId)
                    return
                }

                if (observed.hasRoomCall) {
                    if (!currentOverride.hasObservedActiveCall) {
                        ringValidationJob?.cancel()
                        ringValidationJob = null
                        ringValidationEventId = null
                        ringOverride.value = currentOverride.copy(hasObservedActiveCall = true)
                        logChatCall(
                            "chatCallRingOverride observedActive roomId=$roomId " +
                                "eventId=${currentOverride.eventId}"
                        )
                    }
                    return
                }

                if (currentOverride.hasObservedActiveCall) {
                    clearRingOverride(reason = "roomCallEnded", eventId = currentOverride.eventId)
                }
            }

            fun mergeMembershipFallback(
                roomInfo: MatrixRoomCallInfo,
                fallback: MatrixRoomCallInfo?
            ): MatrixRoomCallInfo {
                if (roomInfo.hasRoomCall || fallback == null || !fallback.hasRoomCall || !fallback.isAudioCall) {
                    return roomInfo
                }
                return roomInfo.copy(
                    hasRoomCall = true,
                    activeParticipantUserIds = fallback.activeParticipantUserIds,
                    isAudioCall = true
                )
            }

            fun effectiveCallInfo(
                observed: MatrixRoomCallInfo,
                override: ChatMatrixRtcRingOverride?
            ): MatrixRoomCallInfo {
                val currentOverride = override ?: return observed
                if (
                    currentOverride.expiresAtMillis <= System.currentTimeMillis() ||
                    observed.hasRoomCall
                ) {
                    return observed
                }

                val participantUserIds = observed.activeParticipantUserIds
                    .takeIf { it.isNotEmpty() }
                    ?: listOf(currentOverride.senderId)
                return observed.copy(
                    hasRoomCall = true,
                    activeParticipantUserIds = participantUserIds,
                    isAudioCall = true
                )
            }

            val notificationJob = launch {
                matrixClientService.incomingMatrixRtcCallNotifications.collect { notification ->
                    handleIncomingMatrixRtcCallNotification(
                        notification = notification,
                        roomId = roomId,
                        ringOverride = ringOverride,
                        lastObservedCallInfo = lastObservedCallInfo,
                        scheduleExpiry = ::scheduleRingOverrideExpiry,
                        scheduleValidation = ::scheduleRingOverrideValidation
                    )
                }
            }

            try {
                combine(
                    matrixClientService.roomCallInfoUpdates(roomId)
                        .onEach { callInfo ->
                            handleRoomInfoForMembershipFallback(callInfo)
                        },
                    nativeMatrixRtcCallService.state,
                    ringOverride,
                    membershipFallback
                ) { callInfo, _, override, fallback ->
                    ChatCallInfoSnapshot(
                        roomInfo = callInfo,
                        observed = mergeMembershipFallback(callInfo, fallback),
                        ringOverride = override
                    )
                }
                    .collect { snapshot ->
                        handleRingOverrideSideEffects(
                            observed = snapshot.roomInfo,
                            override = snapshot.ringOverride
                        )
                        val callInfo = effectiveCallInfo(
                            observed = snapshot.observed,
                            override = snapshot.ringOverride
                        )
                        updateChatCallBanner(userId, roomId, callInfo)
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to observe MatrixRTC room call info", error)
                _uiState.update {
                    if (it.isRouteForRoom(userId, roomId)) {
                        it.copy(chatCallBanner = null)
                    } else {
                        it
                    }
                }
            } finally {
                notificationJob.cancel()
                membershipFallbackRefreshJob?.cancel()
                membershipFallbackValidationJob?.cancel()
                ringExpiryJob?.cancel()
                ringValidationJob?.cancel()
                ringValidationEventId = null
            }
        }
    }

    private fun handleIncomingMatrixRtcCallNotification(
        notification: MatrixIncomingRtcCallNotification,
        roomId: String,
        ringOverride: MutableStateFlow<ChatMatrixRtcRingOverride?>,
        lastObservedCallInfo: MatrixRoomCallInfo?,
        scheduleExpiry: (ChatMatrixRtcRingOverride) -> Unit,
        scheduleValidation: (eventId: String, senderId: String, reason: String, delayMillis: Long) -> Unit
    ) {
        if (notification.roomId != roomId || !notification.isAudioCall) {
            return
        }
        if (notification.expiresAtMillis <= System.currentTimeMillis()) {
            return
        }

        val override = ChatMatrixRtcRingOverride(
            eventId = notification.eventId,
            senderId = notification.senderId,
            expiresAtMillis = notification.expiresAtMillis,
            hasObservedActiveCall = lastObservedCallInfo?.hasRoomCall == true
        )
        ringOverride.value = override
        scheduleExpiry(override)
        if (!override.hasObservedActiveCall) {
            scheduleValidation(
                notification.eventId,
                notification.senderId,
                "membershipNotObserved",
                RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS
            )
        }
        logChatCall(
            "chatCallRingOverride received roomId=$roomId eventId=${notification.eventId} " +
                "sender=${notification.senderId} kind=${notification.kind} " +
                "observed=${override.hasObservedActiveCall}"
        )
    }

    private fun pauseChatCallInfoObserver() {
        if (chatCallInfoJob != null) {
            logChatCall("chatCallInfoObserver pause")
        }
        chatCallInfoJob?.cancel()
        chatCallInfoJob = null
    }

    private fun clearChatCallInfoObserver() {
        pauseChatCallInfoObserver()
        chatCallInfoUserId = null
        chatCallInfoRoomId = null
        _uiState.update { it.copy(chatCallBanner = null) }
    }

    private fun updateChatCallBanner(
        userId: String,
        roomId: String,
        callInfo: MatrixRoomCallInfo
    ) {
        val localCallRoomId = nativeMatrixRtcCallService.currentRoomId()
        val banner = when {
            localCallRoomId == roomId -> ChatCallBannerState(
                title = "Call in progress",
                actionLabel = "Return",
                isLocalCall = true,
                remoteMembershipCount = callInfo.activeParticipantCount
            )
            callInfo.hasRoomCall && callInfo.isAudioCall -> ChatCallBannerState(
                title = "Call in progress",
                actionLabel = "Join",
                isLocalCall = false,
                remoteMembershipCount = callInfo.activeParticipantCount
            )
            else -> null
        }
        logChatCall(
            "chatCallBanner roomId=$roomId localCallRoomId=$localCallRoomId " +
                "hasRoomCall=${callInfo.hasRoomCall} isAudioCall=${callInfo.isAudioCall} " +
                "participants=${callInfo.activeParticipantCount} " +
                "banner=${banner?.actionLabel ?: "null"}"
        )
        _uiState.update {
            if (!it.isRouteForRoom(userId, roomId)) {
                it
            } else {
                it.copy(chatCallBanner = banner)
            }
        }
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

    private fun startCallHistoryCache(userId: String) {
        if (callHistoryCacheUserId == userId && callHistoryCacheJob?.isActive == true) {
            return
        }

        callHistoryCacheJob?.cancel()
        callHistoryCacheUserId = userId
        callHistoryCacheJob = viewModelScope.launch {
            localCacheRepository.observeMatrixRtcCallHistory(
                userId = userId,
                limit = CALL_HISTORY_LIMIT
            ).collect { calls ->
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(callHistory = calls)
                    } else {
                        it
                    }
                }
                scheduleCallHistoryExpiryRefresh(userId, calls)
            }
        }
        startCallHistoryRefresh(userId = userId, showRefreshing = false)
    }

    private fun stopCallHistoryCache() {
        callHistoryCacheJob?.cancel()
        callHistoryCacheJob = null
        callHistoryRefreshJob?.cancel()
        callHistoryRefreshJob = null
        callHistoryExpiryRefreshJob?.cancel()
        callHistoryExpiryRefreshJob = null
        callHistoryCacheUserId = null
        _uiState.update {
            it.copy(isRefreshingCallHistory = false)
        }
    }

    private fun startCallHistoryRefresh(userId: String, showRefreshing: Boolean) {
        callHistoryRefreshJob?.cancel()
        val refreshJob = viewModelScope.launch {
            if (showRefreshing) {
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(
                            isRefreshingCallHistory = true,
                            callHistoryErrorMessage = null
                        )
                    } else {
                        it
                    }
                }
            }
            try {
                localCacheRepository.refreshMatrixRtcCallHistory(
                    userId = userId,
                    limit = CALL_HISTORY_LIMIT
                )
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(
                            isRefreshingCallHistory = false,
                            callHistoryErrorMessage = null
                        )
                    } else {
                        it
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to refresh MatrixRTC call history", error)
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(
                            isRefreshingCallHistory = false,
                            callHistoryErrorMessage = if (showRefreshing) {
                                error.message ?: error.javaClass.simpleName
                            } else {
                                it.callHistoryErrorMessage
                            }
                        )
                    } else {
                        it
                    }
                }
            }
        }
        callHistoryRefreshJob = refreshJob
        refreshJob.invokeOnCompletion {
            if (callHistoryRefreshJob === refreshJob) {
                callHistoryRefreshJob = null
            }
        }
    }

    private fun scheduleCallHistoryExpiryRefresh(
        userId: String,
        calls: List<MatrixRtcCallHistoryItem>
    ) {
        val now = System.currentTimeMillis()
        val nextExpiry = calls.asSequence()
            .filter { call ->
                call.notificationType == MatrixRtcCallNotificationType.RING &&
                    call.outcome == MatrixRtcCallHistoryOutcome.STARTED
            }
            .mapNotNull { call -> call.expiresAtMillis }
            .filter { expiresAtMillis -> expiresAtMillis > now }
            .minOrNull()

        callHistoryExpiryRefreshJob?.cancel()
        if (nextExpiry == null) {
            callHistoryExpiryRefreshJob = null
            return
        }

        callHistoryExpiryRefreshJob = viewModelScope.launch {
            delay((nextExpiry - now + CALL_HISTORY_EXPIRY_REFRESH_GRACE_MS).coerceAtLeast(1L))
            if (_uiState.value.matrixState.userIdOrNull() == userId) {
                startCallHistoryRefresh(userId = userId, showRefreshing = false)
            }
        }
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
        room: MatrixRoomSummary,
        forwardTarget: MatrixForwardTarget?
    ): AppUiState {
        if (matrixState.userIdOrNull() != userId) {
            return this
        }
        return copy(
            navState = navState.openChat(
                roomId = room.id,
                displayName = room.displayName
            ),
            chatMessages = emptyList(),
            chatWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
            chatTimelineFlushSummary = null,
            isLoadingChat = true,
            isLoadingOlderChatMessages = false,
            canLoadOlderChatMessages = false,
            canLoadNewerChatMessages = false,
            isChatAtLiveEdge = true,
            chatErrorMessage = null,
            isSendingChatMessage = false,
            chatSendErrorMessage = null,
            chatReplyTarget = null,
            chatEditTarget = null,
            chatForwardTarget = forwardTarget,
            pendingForwardTarget = null,
            chatJumpTargetEventId = null,
            chatScrollToLiveEdgeRequested = false,
            chatCallBanner = null
        )
    }

    private fun AppUiState.applyInitialChatSnapshot(
        userId: String,
        roomId: String,
        initialMessages: List<MatrixChatMessage>
    ): AppUiState {
        if (!isRouteForRoom(userId, roomId)) {
            return this
        }
        return copy(
            chatMessages = initialMessages,
            chatWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
            chatTimelineFlushSummary = null,
            isLoadingChat = initialMessages.isEmpty(),
            isLoadingOlderChatMessages = false,
            canLoadOlderChatMessages = true,
            canLoadNewerChatMessages = false,
            isChatAtLiveEdge = true,
            chatErrorMessage = null
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
        const val MEMBERSHIP_FALLBACK_VALIDATION_DELAY_MS = 10_000L
        const val RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS = 2_500L
        const val CALL_HISTORY_LIMIT = 100
        const val CALL_HISTORY_EXPIRY_REFRESH_GRACE_MS = 250L
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
