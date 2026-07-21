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
import com.zyna.app.ui.contacts.ContactsState
import com.zyna.app.ui.contacts.DirectRoomActionIntent
import com.zyna.app.ui.contacts.DirectRoomActionRequest
import com.zyna.app.ui.contacts.DirectRoomActionState
import com.zyna.app.ui.contacts.ResolvedDirectRoomAction
import com.zyna.app.ui.contacts.createContactsStore
import com.zyna.app.ui.contacts.createDirectRoomActionCoordinator
import com.zyna.app.ui.profile.OwnProfileState
import com.zyna.app.ui.profile.UserProfileState
import com.zyna.app.ui.profile.createOwnProfileStore
import com.zyna.app.ui.profile.createUserProfileStore
import com.zyna.app.ui.rooms.RoomListState
import com.zyna.app.ui.rooms.createRoomListStore
import com.zyna.app.util.ZynaPerfLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingNativeMatrixRtcCallLaunch(
    val requestId: Long,
    val roomId: String,
    val roomName: String
)

data class LogoutConfirmationState(
    val warning: MatrixLogoutWarning?
)

data class PendingEditProfileExit(
    val destination: EditProfileExitDestination,
    val editSessionId: Long
)

data class AppUiState(
    val navState: AppNavState = AppNavState(),
    val matrixState: MatrixClientState = MatrixClientState.LoggedOut,
    val presenceByUserId: Map<String, UserPresenceStatus> = emptyMap(),
    val pendingNativeMatrixRtcCallLaunch: PendingNativeMatrixRtcCallLaunch? = null,
    val isLoggingOut: Boolean = false,
    val logoutErrorMessage: String? = null,
    val logoutConfirmation: LogoutConfirmationState? = null,
    val pendingEditProfileExit: PendingEditProfileExit? = null,
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

    val isBusy: Boolean
        get() = matrixState is MatrixClientState.LoggingIn ||
            matrixState is MatrixClientState.RestoringSession

    val errorMessage: String?
        get() = (matrixState as? MatrixClientState.Error)?.message
}

internal fun AppUiState.withNavigationState(nextNavState: AppNavState): AppUiState {
    val keepsEditProfileOwner =
        route == AppRoute.EditProfile && nextNavState.top == AppRoute.EditProfile
    return copy(
        navState = nextNavState,
        pendingEditProfileExit = pendingEditProfileExit.takeIf { keepsEditProfileOwner }
    )
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
    private val contactsStore = createContactsStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val contactsState: StateFlow<ContactsState> = contactsStore.state
    private val directRoomActionCoordinator = createDirectRoomActionCoordinator(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        canDeliver = ::canDeliverDirectRoomAction,
        onResolved = ::handleResolvedDirectRoomAction,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val directRoomActionState: StateFlow<DirectRoomActionState> =
        directRoomActionCoordinator.state
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
                current.withNavigationState(current.navState.closeEditProfile())
            }
        },
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val ownProfileState: StateFlow<OwnProfileState> = ownProfileStore.state
    private val userProfileStore = createUserProfileStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val userProfileState: StateFlow<UserProfileState> = userProfileStore.state
    private val roomListStore = createRoomListStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomListState: StateFlow<RoomListState> = roomListStore.state
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
            combine(_uiState, roomListStore.state) { state, roomList ->
                state to roomList
            }.collect { (state, roomList) ->
                consumePendingExternalRouteIfReady(state, roomList)
            }
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
                    roomListStore.deactivate()
                }
                if (
                    nextUserId == null ||
                    didChangeUser ||
                    matrixState is MatrixClientState.Error
                ) {
                    stopChatTimeline()
                }
                if (nextUserId == null || didChangeUser || matrixState is MatrixClientState.Error) {
                    callHistoryStore.deactivate(clearState = shouldClearSessionData)
                    ownProfileStore.deactivate()
                    userProfileStore.clear()
                    directRoomActionCoordinator.cancel()
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
                        presenceByUserId = if (shouldClearSessionData) {
                            emptyMap()
                        } else {
                            current.presenceByUserId
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
                    ).withNavigationState(nextNavState)
                }

                if (nextUserId == null || didChangeUser || matrixState is MatrixClientState.Error) {
                    contactsStore.clear()
                }

                if (nextUserId != null) {
                    roomListStore.activate(nextUserId)
                    callHistoryStore.activate(nextUserId)
                    ownProfileStore.activate(nextUserId)
                }

                if (matrixState is MatrixClientState.Syncing) {
                    val userId = matrixState.userId
                    if (
                        _uiState.value.sessionSecurity.userId == userId &&
                        _uiState.value.sessionSecurity.gateComplete
                    ) {
                        roomListStore.enableReactiveSynchronization(userId)
                    }
                }
            }
        }

        viewModelScope.launch {
            matrixClientService.sessionSecurityState.collect { sessionSecurity ->
                val matrixState = _uiState.value.matrixState
                val userId = matrixState.userIdOrNull()
                val previousSecurity = _uiState.value.sessionSecurity
                val previousContactActionOwner =
                    _uiState.value.route.directRoomActionOwnerKey()
                val becameGateComplete =
                    !previousSecurity.gateComplete && sessionSecurity.gateComplete
                val receivedNewVerificationRequest =
                    sessionSecurity.incomingRequest != null &&
                        sessionSecurity.incomingRequest.flowId !=
                        previousSecurity.incomingRequest?.flowId

                _uiState.update { current ->
                    val securityMatchesSession = userId != null && sessionSecurity.userId == userId
                    val nextNavState = if (securityMatchesSession) {
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
                    current.copy(sessionSecurity = sessionSecurity)
                        .withNavigationState(nextNavState)
                }
                if (
                    _uiState.value.route.directRoomActionOwnerKey() !=
                    previousContactActionOwner
                ) {
                    directRoomActionCoordinator.cancel()
                }

                if (userId != null && sessionSecurity.userId == userId) {
                    presenceRepository.setSessionContext(
                        userId = userId,
                        allowed = sessionSecurity.gateComplete
                    )
                    if (becameGateComplete && matrixState is MatrixClientState.Syncing) {
                        roomListStore.enableReactiveSynchronization(userId)
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

    fun setContactsSearchQuery(query: String) {
        contactsStore.setSearchQuery(query)
    }

    fun openUserProfile(
        userId: String,
        displayName: String? = null,
        avatarUrl: String? = null
    ) {
        val targetUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return
        val currentNavState = _uiState.value.navState
        val nextNavState = currentNavState.openUserProfile(targetUserId)
        if (nextNavState == currentNavState) {
            return
        }
        directRoomActionCoordinator.cancel()
        userProfileStore.open(
            userId = targetUserId,
            seedDisplayName = displayName,
            seedAvatarUrl = avatarUrl
        )
        _uiState.update { current ->
            val latestNavState = current.navState.openUserProfile(targetUserId)
            if (latestNavState == current.navState) {
                return@update current
            }
            current.withNavigationState(latestNavState)
        }
    }

    fun refreshUserProfile() {
        userProfileStore.refresh()
    }

    fun openContactChat(contact: MatrixContact) {
        submitDirectRoomAction(contact, DirectRoomActionIntent.OPEN_CHAT)
    }

    fun callContact(contact: MatrixContact) {
        submitDirectRoomAction(contact, DirectRoomActionIntent.START_CALL)
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
        directRoomActionCoordinator.cancel()
        matrixClientService.handleSessionSecurityAction(MatrixSessionSecurityAction.Manage)
        _uiState.update { current ->
            current.withNavigationState(current.navState.openSessionSecurity(userId))
        }
    }

    fun openRoom(room: MatrixRoomSummary) {
        openRoom(room, forwardTarget = null)
    }

    fun handleExternalRoute(command: ExternalRouteCommand) {
        if (externalRouteCoordinator.accept(command)) {
            consumePendingExternalRouteIfReady(
                state = _uiState.value,
                roomList = roomListStore.state.value
            )
        }
    }

    private fun consumePendingExternalRouteIfReady(
        state: AppUiState,
        roomList: RoomListState
    ) {
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
            availableRoomIds = roomList.rooms.asSequence().map { it.id }.toSet()
        ) ?: return

        // External navigation never acknowledges messages. Read receipts remain
        // driven exclusively by updateVisibleReadReceiptCandidate after rendering.
        when (val route = command.route) {
            is ExternalRoute.OpenRoom -> {
                if (state.activeChatRoute?.roomId == route.roomId) {
                    route.eventId?.let(::jumpToChatEvent)
                    return
                }
                val room = roomList.roomForId(route.roomId) ?: return
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
        directRoomActionCoordinator.cancel()
        val isReplacingUserProfile = _uiState.value.route is AppRoute.UserProfile
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
            if (isReplacingUserProfile) {
                userProfileStore.clear()
            }
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
            it.withNavigationState(it.navState.closeChat())
                .copy(pendingNativeMatrixRtcCallLaunch = null)
        }
    }

    fun selectTab(tab: AppTab) {
        if (requestEditProfileExit(EditProfileExitDestination.Tab(tab))) {
            return
        }
        selectTabImmediately(tab)
    }

    private fun selectTabImmediately(tab: AppTab) {
        val state = _uiState.value
        val previousContactActionOwner = state.route.directRoomActionOwnerKey()
        _uiState.update { current ->
            current.withNavigationState(current.navState.selectTab(tab))
        }
        if (tab == AppTab.PROFILE) {
            _uiState.value.matrixState.userIdOrNull()?.let(ownProfileStore::activate)
        }
        if (_uiState.value.route.directRoomActionOwnerKey() != previousContactActionOwner) {
            directRoomActionCoordinator.cancel()
        }
    }

    fun navigateBack(): Boolean {
        val route = _uiState.value.route
        if (
            route == AppRoute.EditProfile &&
            requestEditProfileExit(EditProfileExitDestination.Back)
        ) {
            return true
        }
        val previousContactActionOwner = route.directRoomActionOwnerKey()
        val didNavigate = when (route) {
            is AppRoute.Chat -> {
                closeChat()
                true
            }
            AppRoute.ForwardPicker -> {
                cancelForwardPicker()
                true
            }
            AppRoute.EditProfile -> false
            is AppRoute.UserProfile -> {
                val didNavigate = popActiveStack()
                userProfileStore.clear()
                didNavigate
            }
            else -> {
                popActiveStack()
            }
        }
        if (_uiState.value.route.directRoomActionOwnerKey() != previousContactActionOwner) {
            directRoomActionCoordinator.cancel()
        }
        return didNavigate
    }

    fun confirmEditProfileExit() {
        val pending = _uiState.value.pendingEditProfileExit ?: return
        completeEditProfileExit(
            destination = pending.destination,
            expectedEditSessionId = pending.editSessionId
        )
    }

    fun cancelEditProfileExit() {
        _uiState.update { current ->
            if (current.pendingEditProfileExit == null) {
                current
            } else {
                current.copy(pendingEditProfileExit = null)
            }
        }
    }

    private fun requestEditProfileExit(destination: EditProfileExitDestination): Boolean {
        val state = _uiState.value
        val profile = ownProfileStore.state.value
        return when (
            editProfileExitDecision(
                route = state.route,
                isSaving = profile.isSaving,
                hasUnsavedChanges = profile.hasUnsavedChanges
            )
        ) {
            EditProfileExitDecision.NOT_APPLICABLE -> false
            EditProfileExitDecision.BLOCKED_WHILE_SAVING -> true
            EditProfileExitDecision.REQUEST_CONFIRMATION -> {
                _uiState.update { current ->
                    if (current.route == AppRoute.EditProfile) {
                        current.copy(
                            pendingEditProfileExit = PendingEditProfileExit(
                                destination = destination,
                                editSessionId = profile.editSessionId
                            )
                        )
                    } else {
                        current
                    }
                }
                true
            }
            EditProfileExitDecision.EXIT -> {
                completeEditProfileExit(
                    destination = destination,
                    expectedEditSessionId = profile.editSessionId
                )
                true
            }
        }
    }

    private fun completeEditProfileExit(
        destination: EditProfileExitDestination,
        expectedEditSessionId: Long
    ) {
        val state = _uiState.value
        val profile = ownProfileStore.state.value
        if (
            state.route != AppRoute.EditProfile ||
            profile.isSaving ||
            profile.editSessionId != expectedEditSessionId
        ) {
            cancelEditProfileExit()
            return
        }
        val previousContactActionOwner = state.route.directRoomActionOwnerKey()
        ownProfileStore.cancelEdit()
        _uiState.update { current ->
            if (current.route != AppRoute.EditProfile) {
                current.copy(pendingEditProfileExit = null)
            } else {
                current.withNavigationState(current.navState.exitEditProfile(destination))
            }
        }
        val targetTab = (destination as? EditProfileExitDestination.Tab)?.tab
        if (targetTab == AppTab.PROFILE) {
            _uiState.value.matrixState.userIdOrNull()?.let(ownProfileStore::activate)
        }
        if (_uiState.value.route.directRoomActionOwnerKey() != previousContactActionOwner) {
            directRoomActionCoordinator.cancel()
        }
    }

    fun openRoomDetails() {
        _uiState.update { current ->
            current.withNavigationState(current.navState.openRoomDetails())
        }
    }

    fun openProfileSettings() {
        _uiState.update { current ->
            current.withNavigationState(current.navState.openProfileSettings())
        }
    }

    fun openEditProfile() {
        ownProfileStore.beginEdit()
        _uiState.update { current ->
            current.withNavigationState(current.navState.openEditProfile())
                .copy(pendingEditProfileExit = null)
        }
    }

    fun openChatThemeSettings() {
        _uiState.update { current ->
            current.withNavigationState(current.navState.openChatThemeSettings())
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

    fun saveOwnProfile() {
        _uiState.value.matrixState.userIdOrNull() ?: return
        cancelEditProfileExit()
        ownProfileStore.save()
    }

    private fun submitDirectRoomAction(
        contact: MatrixContact,
        intent: DirectRoomActionIntent
    ) {
        val state = _uiState.value
        val sessionUserId = state.matrixState.userIdOrNull() ?: return
        val ownerKey = state.route.directRoomActionOwnerKey() ?: return
        directRoomActionCoordinator.submit(
            sessionUserId = sessionUserId,
            ownerKey = ownerKey,
            contact = contact,
            rooms = roomListStore.state.value.rooms,
            intent = intent
        )
    }

    private fun canDeliverDirectRoomAction(request: DirectRoomActionRequest): Boolean {
        val state = _uiState.value
        return state.matrixState.userIdOrNull() == request.sessionUserId &&
            state.route.directRoomActionOwnerKey() == request.ownerKey
    }

    private fun handleResolvedDirectRoomAction(result: ResolvedDirectRoomAction) {
        if (!canDeliverDirectRoomAction(result.request)) {
            return
        }
        openRoom(result.room)
        if (result.request.intent != DirectRoomActionIntent.START_CALL) {
            return
        }
        _uiState.update { current ->
            if (current.matrixState.userIdOrNull() != result.request.sessionUserId ||
                current.activeChatRoute?.roomId != result.room.id
            ) {
                current
            } else {
                current.copy(
                    pendingNativeMatrixRtcCallLaunch = PendingNativeMatrixRtcCallLaunch(
                        requestId = nextPendingNativeMatrixRtcCallLaunchId(),
                        roomId = result.room.id,
                        roomName = result.room.displayName
                    )
                )
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
        return roomListStore.state.value.roomForId(item.roomId)
            ?: MatrixRoomSummary(
                id = item.roomId,
                displayName = item.title,
                avatarUrl = item.roomAvatarUrl
            )
    }

    private fun contactFromUserProfile(): MatrixContact? {
        val profile = userProfileStore.state.value
        val userId = profile.userId.takeIf { it.isNotBlank() } ?: return null
        return MatrixContact(
            userId = userId,
            displayName = profile.effectiveDisplayName,
            avatarUrl = profile.avatarUrl,
            roomId = roomListStore.state.value.roomIdForDirectUser(userId)
        )
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
                current.withNavigationState(nextNavState)
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
            current.withNavigationState(current.navState.openForwardPicker())
        }
    }

    fun cancelForwardPicker() {
        chatComposerStore.cancelForwardPicker()
        _uiState.update { current ->
            current.withNavigationState(current.navState.closeForwardPicker())
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
        runLogoutCleanup("deactivate room list") {
            roomListStore.deactivate()
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

    private suspend fun observePresenceRegistrationInputs() {
        var lastRooms: List<MatrixRoomSummary>? = null
        var lastRoomUserIds: Set<String> = emptySet()
        var lastChatRoomId: String? = null
        var lastChatUserIds: Set<String> = emptySet()
        var lastProfileUserId: String? = null
        var lastProfileUserIds: Set<String> = emptySet()

        combine(_uiState, roomListStore.state) { state, roomList ->
            state to roomList.rooms
        }.collect { (state, rooms) ->
            val roomsChanged = lastRooms !== rooms
            if (roomsChanged) {
                lastRooms = rooms
                val nextRoomUserIds = rooms.directPresenceUserIds()
                if (nextRoomUserIds != lastRoomUserIds) {
                    lastRoomUserIds = nextRoomUserIds
                    presenceRepository.register(PRESENCE_TAG_ROOMS, nextRoomUserIds)
                }
            }

            val activeChatRoomId = state.activeChatRoute?.roomId
            if (roomsChanged || activeChatRoomId != lastChatRoomId) {
                lastChatRoomId = activeChatRoomId
                val nextChatUserIds = activeChatRoomId
                    ?.let { roomId -> rooms.directPresenceUserIdForRoom(roomId) }
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
        return withNavigationState(
            navState.openChat(
                roomId = room.id,
                displayName = room.displayName
            )
        )
    }

    private fun AppRoute.directRoomActionOwnerKey(): String? {
        return when (this) {
            AppRoute.Contacts -> "contacts"
            is AppRoute.UserProfile -> "user-profile:$userId"
            else -> null
        }
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
