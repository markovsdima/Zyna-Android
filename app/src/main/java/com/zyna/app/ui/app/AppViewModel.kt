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
import com.zyna.app.data.local.SpaceCacheRepository
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberModerationAction
import com.zyna.app.data.matrix.MatrixRoomPermission
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.MatrixSpaceService
import com.zyna.app.data.matrix.MatrixUserProfile
import com.zyna.app.data.matrix.toSpaceRoom
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
import com.zyna.app.ui.chat.ChatRouteRevealCoordinator
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
import com.zyna.app.ui.createroom.CreateRoomAccess
import com.zyna.app.ui.createroom.CreateRoomMode
import com.zyna.app.ui.createroom.CreateRoomParent
import com.zyna.app.ui.createroom.CreateRoomPostingPermission
import com.zyna.app.ui.createroom.CreateRoomState
import com.zyna.app.ui.createroom.CreateRoomTarget
import com.zyna.app.ui.createroom.createCreateRoomStore
import com.zyna.app.ui.invitemembers.InviteMembersState
import com.zyna.app.ui.invitemembers.InviteMembersActivationMode
import com.zyna.app.ui.invitemembers.InviteMembersTarget
import com.zyna.app.ui.invitemembers.createInviteMembersStore
import com.zyna.app.ui.profile.OwnProfileState
import com.zyna.app.ui.profile.UserProfileState
import com.zyna.app.ui.profile.createOwnProfileStore
import com.zyna.app.ui.profile.createUserProfileStore
import com.zyna.app.ui.roomdetails.RoomDetailsState
import com.zyna.app.ui.roomdetails.RoomDetailsTarget
import com.zyna.app.ui.roomdetails.RoomLeaveState
import com.zyna.app.ui.roomdetails.RoomLeaveTarget
import com.zyna.app.ui.roomdetails.createRoomDetailsStore
import com.zyna.app.ui.roomdetails.createRoomLeaveStore
import com.zyna.app.ui.roommembers.RoomMembersState
import com.zyna.app.ui.roommembers.RoomMembersTarget
import com.zyna.app.ui.roommembers.RoomMemberModerationState
import com.zyna.app.ui.roommembers.RoomMemberModerationTarget
import com.zyna.app.ui.roommembers.createRoomMemberModerationStore
import com.zyna.app.ui.roommembers.createRoomMembersStore
import com.zyna.app.ui.roompermissions.RoomPermissionAudience
import com.zyna.app.ui.roompermissions.RoomPermissionsState
import com.zyna.app.ui.roompermissions.RoomPermissionsTarget
import com.zyna.app.ui.roompermissions.createRoomPermissionsStore
import com.zyna.app.ui.roomroles.RoomAssignableRole
import com.zyna.app.ui.roomroles.RoomRoleCapabilities
import com.zyna.app.ui.roomroles.RoomRoleManagementState
import com.zyna.app.ui.roomroles.RoomRoleManagementTarget
import com.zyna.app.ui.roomroles.createRoomRoleManagementStore
import com.zyna.app.ui.roomprofile.RoomProfileEditorState
import com.zyna.app.ui.roomprofile.RoomProfileEditorTarget
import com.zyna.app.ui.roomprofile.createRoomProfileEditorStore
import com.zyna.app.ui.rooms.RoomListState
import com.zyna.app.ui.rooms.createRoomListStore
import com.zyna.app.ui.spaces.SpaceAddRoomsState
import com.zyna.app.ui.spaces.SpaceAddRoomsTarget
import com.zyna.app.ui.spaces.SpaceChildrenState
import com.zyna.app.ui.spaces.SpaceJoinError
import com.zyna.app.ui.spaces.SpaceJoinState
import com.zyna.app.ui.spaces.SpaceJoinTarget
import com.zyna.app.ui.spaces.SpaceLeaveState
import com.zyna.app.ui.spaces.SpaceLeaveTarget
import com.zyna.app.ui.spaces.SpaceTarget
import com.zyna.app.ui.spaces.SpaceRootsState
import com.zyna.app.ui.spaces.createSpaceChildrenStore
import com.zyna.app.ui.spaces.createSpaceAddRoomsStore
import com.zyna.app.ui.spaces.createSpaceJoinStore
import com.zyna.app.ui.spaces.createSpaceLeaveStore
import com.zyna.app.ui.spaces.createSpaceRootsStore
import com.zyna.app.util.ZynaPerfLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

private data class RoomDetailsRouteInput(
    val target: RoomDetailsTarget,
    val seed: MatrixRoomSummary?
)

private data class RoomMembersRouteInput(
    val target: RoomMembersTarget,
    val expectedJoinedCount: Long?
)

private data class RoomMemberModerationRouteInput(
    val target: RoomMemberModerationTarget,
    val seed: MatrixRoomMember?
)

private data class InviteMembersRouteInput(
    val target: InviteMembersTarget,
    val seedCanInviteMembers: Boolean,
    val mode: InviteMembersActivationMode
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
    private val matrixSpaceService: MatrixSpaceService,
    private val localCacheRepository: LocalCacheRepository,
    private val spaceCacheRepository: SpaceCacheRepository,
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
    private val spaceRootsStore = createSpaceRootsStore(
        scope = viewModelScope,
        matrixSpaceService = matrixSpaceService,
        cacheRepository = spaceCacheRepository,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val spaceRootsState: StateFlow<SpaceRootsState> = spaceRootsStore.state
    private val spaceChildrenStore = createSpaceChildrenStore(
        scope = viewModelScope,
        matrixSpaceService = matrixSpaceService,
        matrixClientService = matrixClientService,
        cacheRepository = spaceCacheRepository,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val spaceChildrenState: StateFlow<SpaceChildrenState> = spaceChildrenStore.state
    private val spaceAddRoomsStore = createSpaceAddRoomsStore(
        scope = viewModelScope,
        roomListStore = roomListStore,
        spaceChildrenStore = spaceChildrenStore,
        matrixClientService = matrixClientService,
        matrixSpaceService = matrixSpaceService,
        onAdded = ::handleSpaceRoomsAdded,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val spaceAddRoomsState: StateFlow<SpaceAddRoomsState> = spaceAddRoomsStore.state
    private val spaceJoinStore = createSpaceJoinStore(
        scope = viewModelScope,
        matrixSpaceService = matrixSpaceService,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        spaceCacheRepository = spaceCacheRepository,
        onMembershipChanged = ::handleSpaceMembershipChanged,
        onJoined = ::handleSpaceJoined,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val spaceJoinState: StateFlow<SpaceJoinState> = spaceJoinStore.state
    private val spaceLeaveStore = createSpaceLeaveStore(
        scope = viewModelScope,
        matrixSpaceService = matrixSpaceService,
        onLeft = ::handleSpaceLeft,
        onPartiallyLeft = ::handleSpaceRoomsPartiallyLeft,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val spaceLeaveState: StateFlow<SpaceLeaveState> = spaceLeaveStore.state
    private val createRoomStore = createCreateRoomStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        matrixSpaceService = matrixSpaceService,
        localCacheRepository = localCacheRepository,
        spaceChildrenStore = spaceChildrenStore,
        onCreated = ::handleRoomCreated,
        onCancelled = ::handleCreateRoomCancelled,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val createRoomState: StateFlow<CreateRoomState> = createRoomStore.state
    private val roomDetailsStore = createRoomDetailsStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomDetailsState: StateFlow<RoomDetailsState> = roomDetailsStore.state
    private val roomLeaveStore = createRoomLeaveStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onLeft = ::handleRoomLeft,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomLeaveState: StateFlow<RoomLeaveState> = roomLeaveStore.state
    private val roomProfileEditorStore = createRoomProfileEditorStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onEditFinished = ::handleRoomProfileEditFinished,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomProfileEditorState: StateFlow<RoomProfileEditorState> = roomProfileEditorStore.state
    private val roomMembersStore = createRoomMembersStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomMembersState: StateFlow<RoomMembersState> = roomMembersStore.state
    private val roomMemberModerationStore = createRoomMemberModerationStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onMembersRefreshRequested = roomMembersStore::retry,
        onCompleted = ::handleRoomMemberModerationCompleted,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomMemberModerationState: StateFlow<RoomMemberModerationState> =
        roomMemberModerationStore.state
    private val roomPermissionsStore = createRoomPermissionsStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomPermissionsState: StateFlow<RoomPermissionsState> = roomPermissionsStore.state
    private val roomRoleManagementStore = createRoomRoleManagementStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onMembersRefreshRequested = {
            roomMembersStore.retry()
        },
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val roomRoleManagementState: StateFlow<RoomRoleManagementState> =
        roomRoleManagementStore.state
    private val inviteMembersStore = createInviteMembersStore(
        scope = viewModelScope,
        matrixClientService = matrixClientService,
        onAllInvitesSent = ::handleAllInvitesSent,
        onWarning = { message, error -> Log.w(TAG, message, error) }
    )
    val inviteMembersState: StateFlow<InviteMembersState> = inviteMembersStore.state
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
    private val chatRouteRevealCoordinator = ChatRouteRevealCoordinator(viewModelScope)
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
            observeRoomDetailsRouteInputs()
        }

        viewModelScope.launch {
            observeRoomLeaveRouteInputs()
        }

        viewModelScope.launch {
            observeSpaceRouteInputs()
        }

        viewModelScope.launch {
            observeSpaceJoinRouteInputs()
        }

        viewModelScope.launch {
            observeSpaceLeaveRouteInputs()
        }

        viewModelScope.launch {
            observeSpaceAddRoomsRouteInputs()
        }

        viewModelScope.launch {
            observeRoomProfileEditorOwner()
        }

        viewModelScope.launch {
            observeCreateRoomOwner()
        }

        viewModelScope.launch {
            observeRoomMembersRouteInputs()
        }

        viewModelScope.launch {
            observeRoomMemberModerationRouteInputs()
        }

        viewModelScope.launch {
            observeRoomPermissionsRouteInputs()
        }

        viewModelScope.launch {
            observeRoomRoleManagementRouteInputs()
        }

        viewModelScope.launch {
            roomMembersStore.state.collect { members ->
                roomRoleManagementStore.reconcileMembers(members.joinedMembers)
            }
        }

        viewModelScope.launch {
            observeInviteMembersRouteInputs()
        }

        viewModelScope.launch {
            observeRoomMembersPrefetchInputs()
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
                val restoringUserId = (matrixState as? MatrixClientState.RestoringSession)?.userId
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
                val publishMatrixState = {
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
                            pendingNativeMatrixRtcCallLaunch =
                                if (shouldClearSessionData || shouldClearChat) {
                                    null
                                } else {
                                    current.pendingNativeMatrixRtcCallLaunch
                                },
                            isLoggingOut = if (shouldClearSessionData) {
                                false
                            } else {
                                current.isLoggingOut
                            },
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
                }
                val publishedBeforeTeardown = nextUserId == null
                if (publishedBeforeTeardown) {
                    // Terminal/login states have no cache-backed destination to gate. Reveal
                    // them before awaiting cancellation of potentially blocking SDK operations.
                    publishMatrixState()
                }
                if (
                    nextUserId == null ||
                    didChangeUser ||
                    matrixState is MatrixClientState.Error
                ) {
                    roomListStore.deactivate()
                    spaceRootsStore.deactivate()
                    spaceChildrenStore.deactivate()
                    spaceAddRoomsStore.deactivate()
                    spaceJoinStore.deactivate()
                    spaceLeaveStore.deactivate()
                    matrixSpaceService.deactivate()
                    roomDetailsStore.deactivate()
                    roomLeaveStore.deactivate()
                    roomProfileEditorStore.deactivate()
                    createRoomStore.deactivate()
                    roomMembersStore.clearSession()
                    roomMemberModerationStore.clearSession()
                    roomPermissionsStore.clearSession()
                    roomRoleManagementStore.clearSession()
                    inviteMembersStore.clearSession()
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

                if (nextUserId == null || didChangeUser || matrixState is MatrixClientState.Error) {
                    contactsStore.clear()
                }
                val cachedRoomListUserId = nextUserId ?: restoringUserId
                if (cachedRoomListUserId != null) {
                    roomListStore.activate(cachedRoomListUserId)
                }
                if (matrixClientService.state.value != matrixState) {
                    return@collect
                }
                if (restoringUserId == null && !publishedBeforeTeardown) {
                    // Room has now published its first cache result, so an active session cannot
                    // reveal an empty Chats screen before its durable data is available.
                    publishMatrixState()
                }

                if (nextUserId != null) {
                    callHistoryStore.activate(nextUserId)
                    ownProfileStore.activate(nextUserId)
                    if (previousUserId != nextUserId) {
                        try {
                            spaceCacheRepository.warmSession(nextUserId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Log.w(TAG, "Failed to warm Spaces cache", error)
                        }
                    }
                    spaceRootsStore.activate(nextUserId)
                }

                if (matrixState is MatrixClientState.Syncing) {
                    val userId = matrixState.userId
                    if (
                        _uiState.value.sessionSecurity.userId == userId &&
                        _uiState.value.sessionSecurity.gateComplete
                    ) {
                        roomListStore.enableReactiveSynchronization(userId)
                        enableSpacesLive(userId)
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
                        enableSpacesLive(userId)
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
        if (room.kind == MatrixRoomKind.SPACE) {
            val space = room.toSpaceRoom()
            if (space.membership == MatrixSpaceMembership.INVITED) {
                openSpaceJoinPreview(space, parentSpaceId = null)
            } else {
                openSpace(
                    space.copy(membership = MatrixSpaceMembership.JOINED),
                    parentSpaceId = null
                )
            }
            return
        }
        openRoom(room, forwardTarget = null)
    }

    fun openSpaceChild(room: MatrixSpaceRoom) {
        val current = _uiState.value
        val route = current.route as? AppRoute.Space ?: return
        val userId = current.matrixState.userIdOrNull() ?: return
        val currentChild = spaceChildrenStore.state.value.childForOpen(
            userId = userId,
            spaceId = route.spaceId,
            parentSpaceId = route.parentSpaceId,
            childRoomId = room.roomId
        ) ?: return
        if (!currentChild.isJoined) {
            openSpaceJoinPreview(currentChild, parentSpaceId = route.spaceId)
            return
        }
        if (currentChild.kind == MatrixSpaceRoomKind.SPACE) {
            openSpace(currentChild, parentSpaceId = route.spaceId)
        } else {
            openRoom(
                room = currentChild.toJoinedRoomSummary(),
                forwardTarget = null,
                preserveSpaceContext = true
            )
        }
    }

    fun loadMoreSpaceChildren() {
        spaceChildrenStore.loadMore()
    }

    fun updateVisibleRooms(ownerId: String, roomIds: List<String>) {
        roomListStore.updateVisibleRooms(ownerId, roomIds)
    }

    fun clearVisibleRooms(ownerId: String) {
        roomListStore.clearVisibleRooms(ownerId)
    }

    fun retryRoomListSynchronization() {
        roomListStore.retrySynchronization()
    }

    fun retrySpaceChildren() {
        spaceChildrenStore.retry()
    }

    fun openSpaceAddRooms() {
        val current = _uiState.value
        val route = current.route as? AppRoute.Space ?: return
        val userId = current.matrixState.userIdOrNull() ?: return
        val children = spaceChildrenStore.state.value
        if (
            children.target?.userId != userId ||
            children.target.spaceId != route.spaceId ||
            !children.management.canManage
        ) {
            return
        }
        val nextNavState = current.navState.openSpaceAddRooms()
        if (nextNavState == current.navState) return
        if (!_uiState.compareAndSet(current, current.withNavigationState(nextNavState))) return
        spaceAddRoomsStore.activate(
            SpaceAddRoomsTarget(
                userId = userId,
                spaceId = route.spaceId,
                parentSpaceId = route.parentSpaceId,
                displayName = route.displayName
            )
        )
    }

    fun setSpaceAddRoomsSearchQuery(query: String) {
        spaceAddRoomsStore.setSearchQuery(query)
    }

    fun toggleSpaceRoomToAdd(roomId: String) {
        spaceAddRoomsStore.toggleRoom(roomId)
    }

    fun saveSpaceRoomsToAdd() {
        if (roomListStore.state.value.isLoadingFullCoverage) return
        spaceAddRoomsStore.save()
    }

    fun enterSpaceManagement() {
        spaceChildrenStore.enterManagement()
    }

    fun exitSpaceManagement() {
        spaceChildrenStore.exitManagement()
    }

    fun toggleManagedSpaceRoom(roomId: String) {
        spaceChildrenStore.toggleManagedRoom(roomId)
    }

    fun toggleAllManagedSpaceRooms() {
        spaceChildrenStore.toggleAllManagedRooms()
    }

    fun requestManagedSpaceRoomsRemoval() {
        spaceChildrenStore.requestSelectedRoomsRemoval()
    }

    fun confirmManagedSpaceRoomsRemoval() {
        spaceChildrenStore.confirmSelectedRoomsRemoval()
    }

    fun cancelManagedSpaceRoomsRemoval() {
        spaceChildrenStore.cancelSelectedRoomsRemoval()
    }

    fun performSpaceJoinAction() {
        spaceJoinStore.performPrimaryAction()
    }

    fun retrySpaceJoinPreview() {
        spaceJoinStore.retry()
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
        initialEventId: String? = null,
        preserveSpaceContext: Boolean = false,
        onOpened: (() -> Unit)? = null
    ) {
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        directRoomActionCoordinator.cancel()
        val isReplacingUserProfile = _uiState.value.route is AppRoute.UserProfile
        val sourceNavigationState = _uiState.value.navState
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

        val timelineTarget = ChatTimelineTarget(userId = userId, roomId = room.id)
        var didRevealChat = false
        fun revealChatIfOwned(): Boolean {
            if (didRevealChat) {
                return _uiState.value.isRouteForRoom(userId, room.id)
            }
            val current = _uiState.value
            if (
                current.matrixState.userIdOrNull() != userId ||
                current.navState != sourceNavigationState
            ) {
                return false
            }
            chatComposerStore.enterRoom(
                target = ChatComposerSendTarget(userId = userId, roomId = room.id),
                forwardTarget = forwardTarget
            )
            _uiState.update { state ->
                if (
                    state.matrixState.userIdOrNull() != userId ||
                    state.navState != sourceNavigationState
                ) {
                    state
                } else {
                    state.enterChatLoadingState(
                        userId = userId,
                        room = room,
                        preserveSpaceContext = preserveSpaceContext
                    )
                }
            }
            if (!_uiState.value.isRouteForRoom(userId, room.id)) {
                chatComposerStore.deactivateRoom()
                return false
            }
            didRevealChat = true
            if (isReplacingUserProfile) {
                userProfileStore.clear()
            }
            chatCallInfoCoordinator.activate(
                ChatCallInfoTarget(userId = userId, roomId = room.id)
            )
            return true
        }
        val revealRequestId = chatRouteRevealCoordinator.begin(
            reveal = ::revealChatIfOwned,
            onAbandoned = chatTimelineStore::deactivate
        )
        chatTimelineStore.open(
            target = timelineTarget,
            onActivated = onActivated@{
                if (!chatRouteRevealCoordinator.ready(revealRequestId)) return@onActivated
                initialEventId?.let(::jumpToChatEvent)
                onOpened?.invoke()
                ZynaPerfLog.mark {
                    "openRoom.cacheReadyAndRouted roomId=${room.id} " +
                        "messages=${chatTimelineStore.state.value.messages.size}"
                }
            }
        )
        ZynaPerfLog.mark {
            "openRoom.bootstrapScheduled roomId=${room.id}"
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
        if (spaceAddRoomsStore.state.value.isSaving) return
        val spaceManagement = spaceChildrenStore.state.value.management
        if (spaceManagement.isRemoving) return
        if (spaceManagement.isManaging) {
            spaceChildrenStore.exitManagement()
        }
        if (requestEditProfileExit(EditProfileExitDestination.Tab(tab))) {
            return
        }
        selectTabImmediately(tab)
    }

    private fun selectTabImmediately(tab: AppTab) {
        val state = _uiState.value
        val previousContactActionOwner = state.route.directRoomActionOwnerKey()
        val nextNavState = state.navState.selectTab(tab)
        if (!_uiState.compareAndSet(state, state.withNavigationState(nextNavState))) return
        prepareSpaceRoute(nextNavState.activeSpaceRoute, state.matrixState.userIdOrNull())
        if (tab == AppTab.PROFILE) {
            _uiState.value.matrixState.userIdOrNull()?.let(ownProfileStore::activate)
        }
        if (_uiState.value.route.directRoomActionOwnerKey() != previousContactActionOwner) {
            directRoomActionCoordinator.cancel()
        }
    }

    fun navigateBack(): Boolean {
        val route = _uiState.value.route
        if (route is AppRoute.SpaceAddRooms && spaceAddRoomsStore.state.value.isSaving) {
            return true
        }
        if (route is AppRoute.Space && spaceChildrenStore.state.value.management.isManaging) {
            if (!spaceChildrenStore.state.value.management.isRemoving) {
                spaceChildrenStore.exitManagement()
            }
            return true
        }
        if (route is AppRoute.SpaceLeave && spaceLeaveStore.state.value.isLeaving) {
            return true
        }
        if (route is AppRoute.RoomDetails && roomLeaveStore.state.value.isBusy) {
            return true
        }
        if (route is AppRoute.RoomPermissions && roomPermissionsStore.state.value.isSaving) {
            return true
        }
        if (
            route is AppRoute.RoomRoleManagement &&
            roomRoleManagementStore.state.value.isSaving
        ) {
            return true
        }
        if (
            route is AppRoute.RoomMemberDetails &&
            roomMemberModerationStore.state.value.isSaving
        ) {
            return true
        }
        if (
            (route is AppRoute.InviteRoomMembers ||
                route is AppRoute.InviteCreatedRoomMembers) &&
            inviteMembersStore.state.value.isSending
        ) {
            return true
        }
        if (route is AppRoute.InviteCreatedRoomMembers) {
            finishCreatedRoomInvites(route)
            return true
        }
        if (
            route == AppRoute.EditProfile &&
            requestEditProfileExit(EditProfileExitDestination.Back)
        ) {
            return true
        }
        if (route is AppRoute.EditRoomProfile) {
            roomProfileEditorStore.requestExit()
            return true
        }
        if (route == AppRoute.CreateRoom) {
            createRoomStore.requestExit()
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
        if (didNavigate && route is AppRoute.Space) {
            val current = _uiState.value
            prepareSpaceRoute(
                route = current.navState.activeSpaceRoute,
                userId = current.matrixState.userIdOrNull()
            )
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

    private fun openSpace(room: MatrixSpaceRoom, parentSpaceId: String?) {
        val current = _uiState.value
        val userId = current.matrixState.userIdOrNull() ?: return
        val nextNavState = current.navState.openSpace(
            spaceId = room.roomId,
            parentSpaceId = parentSpaceId,
            displayName = room.displayName,
            avatarUrl = room.avatarUrl,
            topic = room.topic
        )
        if (nextNavState == current.navState) return
        if (!_uiState.compareAndSet(current, current.withNavigationState(nextNavState))) return
        directRoomActionCoordinator.cancel()
        stopChatTimeline()
        chatComposerStore.deactivateRoom()
        // Seed immediately after the navigation CAS. The route observer repeats
        // the same idempotent activation, so either scheduling order is safe.
        spaceChildrenStore.activate(
            SpaceTarget(
                userId = userId,
                spaceId = room.roomId,
                parentSpaceId = parentSpaceId,
                seed = room
            )
        )
    }

    fun openCreateRoom() {
        openCreateRoom(CreateRoomMode.GROUP)
    }

    fun openCreateStoryline() {
        openCreateRoom(CreateRoomMode.STORYLINE)
    }

    fun openCreateSpaceChat() {
        val parent = currentManagedSpaceParent(requireRootStoryline = false) ?: return
        openCreateRoom(mode = CreateRoomMode.GROUP, parent = parent)
    }

    fun openCreateTrack() {
        val parent = currentManagedSpaceParent(requireRootStoryline = true) ?: return
        openCreateRoom(mode = CreateRoomMode.TRACK, parent = parent)
    }

    private fun currentManagedSpaceParent(
        requireRootStoryline: Boolean
    ): CreateRoomParent? {
        val current = _uiState.value
        val route = current.route as? AppRoute.Space ?: return null
        if (requireRootStoryline && route.parentSpaceId != null) return null
        val userId = current.matrixState.userIdOrNull() ?: return null
        val children = spaceChildrenStore.state.value
        val target = children.target ?: return null
        if (
            target.userId != userId ||
            target.spaceId != route.spaceId ||
            target.parentSpaceId != route.parentSpaceId ||
            !children.management.canManage
        ) {
            return null
        }
        return CreateRoomParent(
            spaceId = route.spaceId,
            displayName = route.displayName
        )
    }

    private fun openCreateRoom(
        mode: CreateRoomMode,
        parent: CreateRoomParent? = null
    ) {
        val current = _uiState.value
        val userId = current.matrixState.userIdOrNull() ?: return
        val nextNavigation = current.navState.openCreateRoom()
        if (nextNavigation == current.navState) return
        val didBegin = createRoomStore.begin(
            CreateRoomTarget(userId = userId, mode = mode, parent = parent)
        )
        if (!didBegin) return
        _uiState.update { state -> state.withNavigationState(nextNavigation) }
    }

    fun setCreateRoomName(name: String) {
        createRoomStore.setName(name)
    }

    fun setCreateRoomTopic(topic: String) {
        createRoomStore.setTopic(topic)
    }

    fun setCreateRoomAccess(access: CreateRoomAccess) {
        createRoomStore.setAccess(access)
    }

    fun setCreateRoomPostingPermission(permission: CreateRoomPostingPermission) {
        createRoomStore.setPostingPermission(permission)
    }

    fun setCreateRoomAlias(alias: String) {
        createRoomStore.setAliasLocalPart(alias)
    }

    fun retryCreateRoomAliasCheck() {
        createRoomStore.retryAliasCheck()
    }

    fun setCreateRoomAvatarDraft(
        draft: ProfileAvatarDraft,
        target: CreateRoomTarget,
        editSessionId: Long
    ) {
        val current = _uiState.value
        if (
            current.route != AppRoute.CreateRoom ||
            current.matrixState.userIdOrNull() != target.userId
        ) {
            createRoomStore.discardAvatarDraft(draft)
            return
        }
        createRoomStore.setAvatarDraft(draft, target, editSessionId)
    }

    fun setCreateRoomAvatarPreparationError(
        target: CreateRoomTarget,
        editSessionId: Long
    ) {
        val current = _uiState.value
        if (
            current.route != AppRoute.CreateRoom ||
            current.matrixState.userIdOrNull() != target.userId
        ) return
        createRoomStore.setAvatarPreparationError(target, editSessionId)
    }

    fun removeCreateRoomAvatar() {
        createRoomStore.removeAvatar()
    }

    fun createRoom() {
        createRoomStore.create()
    }

    fun confirmCreateRoomDiscard() {
        createRoomStore.confirmDiscard()
    }

    fun cancelCreateRoomDiscard() {
        createRoomStore.cancelDiscardConfirmation()
    }

    fun refreshRoomDetails() {
        roomDetailsStore.refresh()
    }

    fun requestRoomLeave() {
        val current = _uiState.value
        val route = current.route as? AppRoute.RoomDetails ?: return
        val userId = current.matrixState.userIdOrNull() ?: return
        val details = roomDetailsStore.state.value
            .takeIf { it.target == RoomDetailsTarget(userId, route.roomId) }
            ?.details
            ?: roomListStore.state.value.roomForId(route.roomId)?.roomDetails
        val kind = details?.kind
            ?: roomListStore.state.value.roomForId(route.roomId)?.kind
            ?: return
        if (kind == MatrixRoomKind.SPACE) {
            _uiState.update { state ->
                state.withNavigationState(state.navState.openSpaceLeave())
            }
            return
        }
        roomLeaveStore.activate(
            RoomLeaveTarget(
                userId = userId,
                roomId = route.roomId,
                displayName = details?.displayName
                    ?: roomListStore.state.value.roomForId(route.roomId)?.displayName
                    ?: route.roomId,
                kind = kind,
                access = details?.access
            )
        )
        roomLeaveStore.request()
    }

    fun confirmRoomLeave() {
        roomLeaveStore.confirm()
    }

    fun cancelRoomLeave() {
        roomLeaveStore.cancelConfirmation()
    }

    fun toggleSpaceLeaveRoom(roomId: String) {
        spaceLeaveStore.toggle(roomId)
    }

    fun toggleAllSpaceLeaveRooms() {
        spaceLeaveStore.toggleAll()
    }

    fun leaveSpace() {
        spaceLeaveStore.leave()
    }

    fun retrySpaceLeave() {
        spaceLeaveStore.retry()
    }

    fun resolveSpaceLeaveOwnership() {
        val route = _uiState.value.route as? AppRoute.SpaceLeave ?: return
        val leave = spaceLeaveStore.state.value
        if (leave.target?.spaceId != route.spaceId || !leave.needsOwnerChange) return
        _uiState.update { current ->
            val detailsState = current.navState.popActiveStack() ?: return@update current
            val next = if (leave.areCreatorsPrivileged) {
                detailsState.openRoomMembers()
            } else {
                detailsState.openRoomPermissions()
            }
            current.withNavigationState(next)
        }
    }

    fun openEditRoomProfile() {
        val current = _uiState.value
        val route = current.route as? AppRoute.RoomDetails ?: return
        val userId = current.matrixState.userIdOrNull() ?: return
        val target = RoomDetailsTarget(userId, route.roomId)
        val details = roomDetailsStore.state.value
            .takeIf { it.target == target }
            ?.details
            ?: roomListStore.state.value.roomForId(route.roomId)?.roomDetails
            ?: return
        if (
            details.kind == MatrixRoomKind.DIRECT ||
            (details.capabilities.canChangeName != true &&
                details.capabilities.canChangeAvatar != true)
        ) {
            return
        }
        roomProfileEditorStore.beginEdit(
            target = RoomProfileEditorTarget(userId = userId, roomId = route.roomId),
            details = details
        )
        _uiState.update { state ->
            state.withNavigationState(state.navState.openEditRoomProfile())
        }
    }

    fun setRoomProfileDisplayNameDraft(displayName: String) {
        roomProfileEditorStore.setDisplayNameDraft(displayName)
    }

    fun setRoomProfileAvatarDraft(
        draft: ProfileAvatarDraft,
        target: RoomProfileEditorTarget,
        editSessionId: Long
    ) {
        val route = _uiState.value.route as? AppRoute.EditRoomProfile
        if (route?.roomId != target.roomId) {
            roomProfileEditorStore.discardAvatarDraft(draft)
            return
        }
        roomProfileEditorStore.setAvatarDraft(draft, target, editSessionId)
    }

    fun setRoomProfileAvatarPreparationError(
        target: RoomProfileEditorTarget,
        editSessionId: Long
    ) {
        val route = _uiState.value.route as? AppRoute.EditRoomProfile ?: return
        if (route.roomId != target.roomId) return
        roomProfileEditorStore.setAvatarPreparationError(target, editSessionId)
    }

    fun removeRoomProfileAvatarDraft() {
        roomProfileEditorStore.removeAvatarDraft()
    }

    fun saveRoomProfile() {
        roomProfileEditorStore.save()
    }

    fun confirmRoomProfileDiscard() {
        roomProfileEditorStore.confirmDiscard()
    }

    fun cancelRoomProfileDiscard() {
        roomProfileEditorStore.cancelDiscardConfirmation()
    }

    fun openRoomMembers() {
        val current = _uiState.value
        val routeRoomId = when (val route = current.route) {
            is AppRoute.RoomDetails -> route.roomId
            else -> return
        }
        val sessionUserId = current.matrixState.userIdOrNull() ?: return
        val detailsKind = roomDetailsStore.state.value
            .takeIf { state ->
                state.target == RoomDetailsTarget(
                    userId = sessionUserId,
                    roomId = routeRoomId
                )
            }
            ?.details
            ?.kind
        val roomKind = detailsKind
            ?: roomListStore.state.value.roomForId(routeRoomId)?.kind
            ?: return
        if (roomKind == MatrixRoomKind.DIRECT) {
            return
        }
        _uiState.update { current ->
            current.withNavigationState(current.navState.openRoomMembers())
        }
    }

    fun openRoomPermissions() {
        val current = _uiState.value
        val route = current.route as? AppRoute.RoomDetails ?: return
        val sessionUserId = current.matrixState.userIdOrNull() ?: return
        val kind = roomDetailsStore.state.value
            .takeIf {
                it.target == RoomDetailsTarget(sessionUserId, route.roomId)
            }
            ?.details
            ?.kind
            ?: roomListStore.state.value.roomForId(route.roomId)?.kind
            ?: return
        if (kind == MatrixRoomKind.DIRECT) return
        _uiState.update { state ->
            state.withNavigationState(state.navState.openRoomPermissions())
        }
    }

    fun retryRoomPermissions() {
        roomPermissionsStore.retry()
    }

    fun setRoomPermission(
        permission: MatrixRoomPermission,
        audience: RoomPermissionAudience
    ) {
        roomPermissionsStore.setPermission(permission, audience)
    }

    fun openRoomRoleManagement() {
        val current = _uiState.value
        val route = current.route as? AppRoute.RoomPermissions ?: return
        if (roomPermissionsStore.state.value.isSaving) return
        current.matrixState.userIdOrNull() ?: return
        _uiState.update { state ->
            state.withNavigationState(state.navState.openRoomRoleManagement())
        }
    }

    fun setRoomMemberRole(
        member: MatrixRoomMember,
        role: RoomAssignableRole
    ) {
        roomRoleManagementStore.requestRoleChange(member, role)
    }

    fun confirmRoomMemberRoleChange() {
        roomRoleManagementStore.confirmPendingChange()
    }

    fun cancelRoomMemberRoleChange() {
        roomRoleManagementStore.cancelPendingChange()
    }

    fun retryRoomMembers() {
        roomMembersStore.retry()
    }

    fun setRoomMembersSearchQuery(query: String) {
        roomMembersStore.setSearchQuery(query)
    }

    fun openRoomMemberDetails(member: MatrixRoomMember) {
        val current = _uiState.value
        val route = current.route as? AppRoute.RoomMembers ?: return
        val sessionUserId = current.matrixState.userIdOrNull() ?: return
        val members = roomMembersStore.state.value
        if (
            members.target != RoomMembersTarget(sessionUserId, route.roomId) ||
            members.allVisibleMembers().none { it.userId == member.userId }
        ) {
            return
        }
        val nextNavigation = current.navState.openRoomMemberDetails(member.userId)
        if (nextNavigation == current.navState) return
        directRoomActionCoordinator.cancel()
        roomMemberModerationStore.activate(
            target = RoomMemberModerationTarget(
                userId = sessionUserId,
                roomId = route.roomId,
                memberUserId = member.userId
            ),
            seed = member
        )
        _uiState.update { state ->
            val latestRoute = state.route as? AppRoute.RoomMembers
            if (
                state.matrixState.userIdOrNull() != sessionUserId ||
                latestRoute?.roomId != route.roomId
            ) {
                state
            } else {
                state.withNavigationState(
                    state.navState.openRoomMemberDetails(member.userId)
                )
            }
        }
    }

    fun retryRoomMemberModeration() {
        roomMemberModerationStore.retry()
    }

    fun requestRoomMemberModeration(action: MatrixRoomMemberModerationAction) {
        roomMemberModerationStore.request(action)
    }

    fun confirmRoomMemberModeration(reason: String?) {
        roomMemberModerationStore.confirm(reason)
    }

    fun cancelRoomMemberModeration() {
        roomMemberModerationStore.cancelConfirmation()
    }

    fun openRoomMemberChat() {
        val state = roomMemberModerationStore.state.value
        state.target ?: return
        val member = state.member ?: return
        if (!state.canSendMessage) return
        openContactChat(
            MatrixContact(
                userId = member.userId,
                displayName = member.displayNameOrUserId,
                avatarUrl = member.avatarUrl,
                roomId = roomListStore.state.value.roomIdForDirectUser(member.userId)
            )
        )
    }

    fun openInviteRoomMembers() {
        val current = _uiState.value
        val routeRoomId = when (val route = current.route) {
            is AppRoute.RoomDetails -> route.roomId
            is AppRoute.RoomMembers -> route.roomId
            else -> return
        }
        val sessionUserId = current.matrixState.userIdOrNull() ?: return
        val target = RoomDetailsTarget(sessionUserId, routeRoomId)
        val liveDetails = roomDetailsStore.state.value
            .takeIf { state -> state.target == target }
            ?.details
        val details = liveDetails
            ?: roomListStore.state.value.roomForId(routeRoomId)?.roomDetails
            ?: return
        if (
            details.kind == MatrixRoomKind.DIRECT ||
            details.capabilities.canInviteMembers != true
        ) {
            return
        }
        _uiState.update { state ->
            state.withNavigationState(state.navState.openInviteRoomMembers())
        }
    }

    fun retryInviteMembersPreparation() {
        inviteMembersStore.retryPreparation()
    }

    fun setInviteMembersSearchQuery(query: String) {
        inviteMembersStore.setSearchQuery(query)
    }

    fun retryInviteMembersSearch() {
        inviteMembersStore.retrySearch()
    }

    fun toggleInviteMemberSelection(profile: MatrixUserProfile) {
        inviteMembersStore.toggleSelection(profile)
    }

    fun sendRoomMemberInvites() {
        inviteMembersStore.sendInvites()
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
        openRoom(
            room = result.room,
            forwardTarget = null,
            onOpened = {
                if (result.request.intent == DirectRoomActionIntent.START_CALL) {
                    _uiState.update { current ->
                        if (
                            current.matrixState.userIdOrNull() != result.request.sessionUserId ||
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
            }
        )
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
        openRoom(
            room = room,
            forwardTarget = null,
            onOpened = {
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
        )
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
        runLogoutCleanup("clear Spaces cache") { spaceCacheRepository.clearAll() }
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
            is MatrixClientState.RestoringSession -> currentNavState
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
        chatRouteRevealCoordinator.cancel()
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

            val profileUserId = when (val route = state.route) {
                is AppRoute.UserProfile -> route.userId
                is AppRoute.RoomMemberDetails -> route.userId
                else -> null
            }?.takeIf { it.isNotBlank() }
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

    private suspend fun observeRoomDetailsRouteInputs() {
        combine(_uiState, roomListStore.state) { state, roomList ->
            val route = state.navState.activeRoomDetailsRoute
            val userId = state.matrixState.userIdOrNull()
            if (route == null || userId == null) {
                null
            } else {
                RoomDetailsRouteInput(
                    target = RoomDetailsTarget(userId = userId, roomId = route.roomId),
                    seed = roomList.roomForId(route.roomId)
                )
            }
        }.collect { input ->
            if (input == null) {
                roomDetailsStore.deactivate()
            } else {
                roomDetailsStore.activate(input.target, input.seed)
            }
        }
    }

    private suspend fun observeRoomLeaveRouteInputs() {
        combine(_uiState, roomDetailsStore.state, roomListStore.state) {
                state,
                detailsState,
                roomList ->
            val route = state.navState.activeRoomDetailsRoute ?: return@combine null
            val userId = state.matrixState.userIdOrNull() ?: return@combine null
            val details = detailsState
                .takeIf { it.target == RoomDetailsTarget(userId, route.roomId) }
                ?.details
            val seed = roomList.roomForId(route.roomId)
            val kind = details?.kind ?: seed?.kind ?: return@combine null
            RoomLeaveTarget(
                userId = userId,
                roomId = route.roomId,
                displayName = details?.displayName ?: seed?.displayName ?: route.roomId,
                kind = kind,
                access = details?.access
            )
        }
            .distinctUntilChanged()
            .collect { target ->
                if (target == null) {
                    roomLeaveStore.deactivate()
                } else {
                    roomLeaveStore.activate(target)
                }
            }
    }

    private suspend fun observeSpaceRouteInputs() {
        _uiState
            .map { state ->
                val userId = state.matrixState.userIdOrNull() ?: return@map null
                val route = state.navState.activeSpaceRoute ?: return@map null
                route.toSpaceTarget(userId)
            }
            .distinctUntilChanged()
            .collect { target ->
                if (target == null) {
                    spaceChildrenStore.deactivate()
                } else {
                    spaceChildrenStore.activate(target)
                }
            }
    }

    private suspend fun observeSpaceJoinRouteInputs() {
        combine(_uiState, spaceChildrenStore.state) { state, children ->
            val userId = state.matrixState.userIdOrNull() ?: return@combine null
            val route = state.navState.activeSpaceJoinPreviewRoute ?: return@combine null
            val currentChild = route.parentSpaceId?.let { parentSpaceId ->
                val activeParent = state.navState.activeSpaceRoute
                    ?.takeIf { it.spaceId == parentSpaceId }
                    ?: return@combine null
                children.takeIf { childState ->
                    childState.target?.userId == userId &&
                        childState.target.spaceId == activeParent.spaceId &&
                        childState.target.parentSpaceId == activeParent.parentSpaceId
                }?.roomForId(route.roomId)
            }
            SpaceJoinTarget(
                userId = userId,
                parentSpaceId = route.parentSpaceId,
                roomId = route.roomId,
                seed = currentChild ?: route.toSpaceRoomSeed()
            )
        }
            .distinctUntilChanged()
            .collect { target ->
                if (target == null) {
                    spaceJoinStore.deactivate()
                } else {
                    spaceJoinStore.activate(target)
                }
            }
    }

    private suspend fun observeSpaceLeaveRouteInputs() {
        _uiState
            .map { state ->
                val route = state.navState.activeSpaceLeaveRoute ?: return@map null
                val userId = state.matrixState.userIdOrNull() ?: return@map null
                SpaceLeaveTarget(
                    userId = userId,
                    spaceId = route.spaceId,
                    parentSpaceId = route.parentSpaceId,
                    displayName = route.displayName
                )
            }
            .distinctUntilChanged()
            .collect { target ->
                if (target == null) {
                    spaceLeaveStore.deactivate()
                } else {
                    spaceLeaveStore.activate(target)
                }
            }
    }

    private suspend fun observeSpaceAddRoomsRouteInputs() {
        _uiState
            .map { state ->
                val route = state.navState.activeSpaceAddRoomsRoute ?: return@map null
                val userId = state.matrixState.userIdOrNull() ?: return@map null
                SpaceAddRoomsTarget(
                    userId = userId,
                    spaceId = route.spaceId,
                    parentSpaceId = route.parentSpaceId,
                    displayName = route.displayName
                )
            }
            .distinctUntilChanged()
            .collect { target ->
                if (target == null) {
                    spaceAddRoomsStore.deactivate()
                    roomListStore.clearFullCoverage(SPACE_ADD_ROOMS_COVERAGE_OWNER)
                } else {
                    roomListStore.requestFullCoverage(SPACE_ADD_ROOMS_COVERAGE_OWNER)
                    spaceAddRoomsStore.activate(target)
                }
            }
    }

    private fun handleSpaceRoomsAdded(
        target: SpaceAddRoomsTarget,
        roomIds: Set<String>
    ) {
        if (roomIds.isEmpty()) return
        _uiState.update { state ->
            val route = state.route as? AppRoute.SpaceAddRooms ?: return@update state
            if (
                state.matrixState.userIdOrNull() != target.userId ||
                route.spaceId != target.spaceId ||
                route.parentSpaceId != target.parentSpaceId
            ) {
                state
            } else {
                val next = state.navState.popActiveStack() ?: return@update state
                state.withNavigationState(next)
            }
        }
    }

    private fun handleRoomLeft(target: RoomLeaveTarget) {
        val current = _uiState.value
        if (current.matrixState.userIdOrNull() != target.userId) return
        roomListStore.confirmRoomsLeft(target.userId, setOf(target.roomId))
        if (current.activeChatRoute?.roomId == target.roomId) {
            stopChatTimeline()
            chatComposerStore.clearAll()
        }
        _uiState.update { state ->
            if (state.matrixState.userIdOrNull() != target.userId) state else {
                state.withNavigationState(state.navState.closeLeftRoom(target.roomId))
            }
        }
    }

    private fun handleSpaceLeft(target: SpaceLeaveTarget, roomIds: Set<String>) {
        val current = _uiState.value
        if (current.matrixState.userIdOrNull() != target.userId) return
        confirmSpaceRoomsLeft(target, roomIds)
        if (target.parentSpaceId != null) {
            current.navState.chatsStack
                .filterIsInstance<AppRoute.Space>()
                .lastOrNull { route -> route.spaceId == target.parentSpaceId }
                ?.let { parentRoute ->
                    // The leave screen owns the child Space store. Switch the read model to its
                    // parent before applying the overlay so the returned screen never flashes the
                    // successfully left child Space as joined.
                    spaceChildrenStore.activate(parentRoute.toSpaceTarget(target.userId))
                }
            spaceChildrenStore.confirmChildMembership(
                userId = target.userId,
                spaceId = target.parentSpaceId,
                roomId = target.spaceId,
                membership = MatrixSpaceMembership.LEFT
            )
            viewModelScope.launch {
                try {
                    spaceCacheRepository.cacheSpaceChildMembership(
                        target.userId,
                        target.parentSpaceId,
                        target.spaceId,
                        MatrixSpaceMembership.LEFT
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(TAG, "Failed to cache left nested Space membership", error)
                }
            }
        }
        stopChatTimeline()
        chatComposerStore.clearAll()
        _uiState.update { state ->
            if (state.matrixState.userIdOrNull() != target.userId) state else {
                state.withNavigationState(state.navState.closeLeftRoom(target.spaceId))
            }
        }
    }

    private fun handleSpaceRoomsPartiallyLeft(
        target: SpaceLeaveTarget,
        roomIds: Set<String>
    ) {
        val current = _uiState.value
        if (current.matrixState.userIdOrNull() != target.userId) return
        confirmSpaceRoomsLeft(target, roomIds)
    }

    private fun confirmSpaceRoomsLeft(target: SpaceLeaveTarget, roomIds: Set<String>) {
        if (roomIds.isEmpty()) return
        roomListStore.confirmRoomsLeft(target.userId, roomIds)

        val visibleRootIds = spaceRootsStore.state.value.spaces
            .mapTo(HashSet()) { it.roomId }
        val leftRootIds = roomIds.filterTo(linkedSetOf(), visibleRootIds::contains)
        if (target.parentSpaceId == null && target.spaceId in roomIds) {
            leftRootIds += target.spaceId
        }
        leftRootIds.forEach { roomId ->
            spaceRootsStore.confirmLeftRoot(target.userId, roomId)
        }

        val children = spaceChildrenStore.state.value
        val directChildIds = if (
            target.spaceId !in roomIds &&
            children.target?.userId == target.userId &&
            children.target.spaceId == target.spaceId
        ) {
            roomIds.filterTo(linkedSetOf()) { children.roomForId(it) != null }
        } else {
            emptySet()
        }
        directChildIds.forEach { roomId ->
            spaceChildrenStore.confirmChildMembership(
                userId = target.userId,
                spaceId = target.spaceId,
                roomId = roomId,
                membership = MatrixSpaceMembership.LEFT
            )
            viewModelScope.launch {
                try {
                    spaceCacheRepository.cacheSpaceChildMembership(
                        target.userId,
                        target.spaceId,
                        roomId,
                        MatrixSpaceMembership.LEFT
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(TAG, "Failed to cache left child Space membership", error)
                }
            }
        }
    }

    private fun handleSpaceMembershipChanged(
        target: SpaceJoinTarget,
        room: MatrixSpaceRoom
    ) {
        val parentSpaceId = target.parentSpaceId
        if (parentSpaceId == null) {
            if (room.kind == MatrixSpaceRoomKind.SPACE && room.isJoined) {
                spaceRootsStore.confirmJoinedRoot(target.userId, room)
            }
            return
        }
        spaceChildrenStore.confirmChildMembership(
            userId = target.userId,
            spaceId = parentSpaceId,
            roomId = target.roomId,
            membership = room.membership
        )
    }

    private fun handleSpaceJoined(target: SpaceJoinTarget, room: MatrixSpaceRoom) {
        val current = _uiState.value
        val route = current.navState.activeSpaceJoinPreviewRoute ?: return
        if (
            current.matrixState.userIdOrNull() != target.userId ||
            route.roomId != target.roomId ||
            route.parentSpaceId != target.parentSpaceId
        ) {
            return
        }
        if (room.kind == MatrixSpaceRoomKind.SPACE) {
            openSpace(room, parentSpaceId = target.parentSpaceId)
        } else {
            openRoom(
                room = room.toJoinedRoomSummary(),
                forwardTarget = null,
                preserveSpaceContext = true
            )
        }
    }

    private fun openSpaceJoinPreview(room: MatrixSpaceRoom, parentSpaceId: String?) {
        val current = _uiState.value
        val userId = current.matrixState.userIdOrNull() ?: return
        val nextNavState = current.navState.openSpaceJoinPreview(
            roomId = room.roomId,
            parentSpaceId = parentSpaceId,
            displayName = room.displayName,
            avatarUrl = room.avatarUrl,
            topic = room.topic,
            isSpace = room.kind == MatrixSpaceRoomKind.SPACE,
            membership = room.membership,
            joinRule = room.joinRule
        )
        if (nextNavState == current.navState) return
        if (!_uiState.compareAndSet(current, current.withNavigationState(nextNavState))) return
        spaceJoinStore.activate(
            SpaceJoinTarget(
                userId = userId,
                parentSpaceId = parentSpaceId,
                roomId = room.roomId,
                seed = room
            )
        )
    }

    private fun prepareSpaceRoute(route: AppRoute.Space?, userId: String?) {
        if (route == null || userId == null) return
        spaceChildrenStore.activate(route.toSpaceTarget(userId))
    }

    private suspend fun enableSpacesLive(userId: String) {
        try {
            matrixSpaceService.activate(userId)
            if (_uiState.value.matrixState.userIdOrNull() == userId) {
                spaceRootsStore.enableLive(userId)
                val activeSpace = _uiState.value.navState.activeSpaceRoute
                if (
                    activeSpace != null &&
                    spaceChildrenStore.state.value.target?.spaceId == activeSpace.spaceId &&
                    spaceChildrenStore.state.value.error != null
                ) {
                    spaceChildrenStore.retry()
                }
                if (
                    spaceJoinStore.state.value.target?.userId == userId &&
                    spaceJoinStore.state.value.error == SpaceJoinError.LOAD
                ) {
                    spaceJoinStore.retry()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (_uiState.value.matrixState.userIdOrNull() == userId) {
                Log.w(TAG, "Failed to activate Matrix Spaces", error)
            }
        }
    }

    private suspend fun observeRoomProfileEditorOwner() {
        _uiState.collect { state ->
            val editor = roomProfileEditorStore.state.value
            val target = editor.target ?: return@collect
            val route = state.route as? AppRoute.EditRoomProfile
            if (
                route?.roomId != target.roomId ||
                state.matrixState.userIdOrNull() != target.userId
            ) {
                roomProfileEditorStore.deactivate()
            }
        }
    }

    private suspend fun observeCreateRoomOwner() {
        _uiState.collect { state ->
            val target = createRoomStore.state.value.target ?: return@collect
            if (
                state.route != AppRoute.CreateRoom ||
                state.matrixState.userIdOrNull() != target.userId
            ) {
                createRoomStore.deactivate()
            }
        }
    }

    private fun handleRoomCreated(
        target: CreateRoomTarget,
        room: MatrixRoomSummary,
        access: CreateRoomAccess
    ) {
        val current = _uiState.value
        if (
            current.route != AppRoute.CreateRoom ||
            current.matrixState.userIdOrNull() != target.userId
        ) {
            return
        }
        when (target.mode) {
            CreateRoomMode.GROUP -> {
                if (target.parent == null || access == CreateRoomAccess.PRIVATE) {
                    _uiState.update { state ->
                        state.withNavigationState(
                            state.navState.openCreatedRoomInvites(
                                roomId = room.id,
                                displayName = room.displayName,
                                avatarUrl = room.avatarUrl,
                                preserveSpaceContext = target.parent != null
                            )
                        )
                    }
                } else {
                    openRoom(
                        room = room,
                        forwardTarget = null,
                        preserveSpaceContext = true
                    )
                }
            }
            CreateRoomMode.STORYLINE -> {
                val space = room.toSpaceRoom()
                spaceRootsStore.confirmJoinedRoot(target.userId, space)
                openSpace(space, parentSpaceId = null)
            }
            CreateRoomMode.TRACK -> {
                val space = room.toSpaceRoom()
                openSpace(space, parentSpaceId = requireNotNull(target.parent).spaceId)
            }
        }
    }

    private fun handleCreateRoomCancelled(target: CreateRoomTarget) {
        val current = _uiState.value
        if (
            current.route != AppRoute.CreateRoom ||
            current.matrixState.userIdOrNull() != target.userId
        ) {
            return
        }
        _uiState.update { state ->
            state.navState.popActiveStack()
                ?.let(state::withNavigationState)
                ?: state
        }
    }

    private fun handleRoomProfileEditFinished(
        target: RoomProfileEditorTarget,
        didSave: Boolean
    ) {
        val current = _uiState.value
        val route = current.route as? AppRoute.EditRoomProfile ?: return
        if (
            route.roomId != target.roomId ||
            current.matrixState.userIdOrNull() != target.userId
        ) {
            return
        }
        _uiState.update { state ->
            state.navState.popActiveStack()
                ?.let(state::withNavigationState)
                ?: state
        }
        if (didSave) {
            roomDetailsStore.refresh()
        }
    }

    private suspend fun observeInviteMembersRouteInputs() {
        combine(
            _uiState,
            roomListStore.state,
            roomDetailsStore.state
        ) { state, roomList, roomDetails ->
            val userId = state.matrixState.userIdOrNull()
            if (userId == null) {
                return@combine null
            }
            when (val route = state.route) {
                is AppRoute.InviteCreatedRoomMembers -> InviteMembersRouteInput(
                    target = InviteMembersTarget(userId, route.roomId),
                    seedCanInviteMembers = true,
                    mode = InviteMembersActivationMode.NEWLY_CREATED_ROOM
                )
                is AppRoute.InviteRoomMembers -> {
                    val target = RoomDetailsTarget(userId, route.roomId)
                    val details = roomDetails
                        .takeIf { it.target == target }
                        ?.details
                        ?: roomList.roomForId(route.roomId)?.roomDetails
                    if (details == null || details.kind == MatrixRoomKind.DIRECT) {
                        null
                    } else {
                        InviteMembersRouteInput(
                            target = InviteMembersTarget(userId, route.roomId),
                            seedCanInviteMembers =
                                details.capabilities.canInviteMembers == true,
                            mode = InviteMembersActivationMode.EXISTING_ROOM
                        )
                    }
                }
                else -> null
            }
        }.distinctUntilChanged().collect { input ->
            if (input == null) {
                inviteMembersStore.deactivate()
            } else {
                inviteMembersStore.activate(
                    target = input.target,
                    seedCanInviteMembers = input.seedCanInviteMembers,
                    mode = input.mode
                )
            }
        }
    }

    private fun handleAllInvitesSent(target: InviteMembersTarget) {
        val current = _uiState.value
        when (val route = current.route) {
            is AppRoute.InviteCreatedRoomMembers -> {
                if (
                    route.roomId == target.roomId &&
                    current.matrixState.userIdOrNull() == target.userId
                ) {
                    finishCreatedRoomInvites(route)
                }
            }
            is AppRoute.InviteRoomMembers -> {
                if (
                    route.roomId != target.roomId ||
                    current.matrixState.userIdOrNull() != target.userId
                ) {
                    return
                }
                _uiState.update { state ->
                    state.navState.popActiveStack()
                        ?.let(state::withNavigationState)
                        ?: state
                }
            }
            else -> Unit
        }
    }

    private fun finishCreatedRoomInvites(route: AppRoute.InviteCreatedRoomMembers) {
        val current = _uiState.value
        if (current.route != route) return
        openRoom(
            MatrixRoomSummary(
                id = route.roomId,
                displayName = route.displayName,
                avatarUrl = route.avatarUrl
            ),
            forwardTarget = null,
            preserveSpaceContext = route.preserveSpaceContext
        )
    }

    private suspend fun observeRoomMembersRouteInputs() {
        combine(_uiState, roomListStore.state) { state, roomList ->
            val roomId = when (val route = state.route) {
                is AppRoute.RoomMembers -> route.roomId
                is AppRoute.RoomMemberDetails -> route.roomId
                is AppRoute.RoomRoleManagement -> route.roomId
                else -> null
            }
            val userId = state.matrixState.userIdOrNull()
            if (roomId == null || userId == null) {
                null
            } else {
                RoomMembersRouteInput(
                    target = RoomMembersTarget(userId = userId, roomId = roomId),
                    expectedJoinedCount = roomList.roomForId(roomId)
                        ?.roomDetails
                        ?.joinedMemberCount
                )
            }
        }.collect { input ->
            if (input == null) {
                roomMembersStore.deactivate()
            } else {
                roomMembersStore.activate(
                    target = input.target,
                    expectedJoinedCount = input.expectedJoinedCount
                )
            }
        }
    }

    private suspend fun observeRoomMemberModerationRouteInputs() {
        combine(_uiState, roomMembersStore.state) { state, members ->
            val route = state.route as? AppRoute.RoomMemberDetails
            val userId = state.matrixState.userIdOrNull()
            if (route == null || userId == null) {
                null
            } else {
                RoomMemberModerationRouteInput(
                    target = RoomMemberModerationTarget(
                        userId = userId,
                        roomId = route.roomId,
                        memberUserId = route.userId
                    ),
                    seed = members
                        .takeIf {
                            it.target == RoomMembersTarget(userId, route.roomId)
                        }
                        ?.memberForId(route.userId)
                )
            }
        }.distinctUntilChanged().collect { input ->
            if (input == null) {
                roomMemberModerationStore.deactivate()
            } else {
                roomMemberModerationStore.activate(input.target, input.seed)
            }
        }
    }

    private fun handleRoomMemberModerationCompleted(
        target: RoomMemberModerationTarget,
        action: MatrixRoomMemberModerationAction
    ) {
        val current = _uiState.value
        val route = current.route as? AppRoute.RoomMemberDetails ?: return
        if (
            current.matrixState.userIdOrNull() != target.userId ||
            route.roomId != target.roomId ||
            route.userId != target.memberUserId
        ) {
            return
        }
        roomMembersStore.applyConfirmedModeration(
            roomId = target.roomId,
            userId = target.memberUserId,
            action = action
        )
        _uiState.update { state ->
            state.navState.popActiveStack()
                ?.let(state::withNavigationState)
                ?: state
        }
    }

    private suspend fun observeRoomPermissionsRouteInputs() {
        _uiState.collect { state ->
            val route = state.navState.activeRoomPermissionsRoute
            val userId = state.matrixState.userIdOrNull()
            if (route == null || userId == null) {
                roomPermissionsStore.deactivate()
            } else {
                roomPermissionsStore.activate(
                    RoomPermissionsTarget(userId = userId, roomId = route.roomId)
                )
            }
        }
    }

    private suspend fun observeRoomRoleManagementRouteInputs() {
        combine(_uiState, roomPermissionsStore.state) { state, permissions ->
            val route = state.route as? AppRoute.RoomRoleManagement
            val userId = state.matrixState.userIdOrNull()
            if (route == null || userId == null) {
                null
            } else {
                Pair(
                    RoomRoleManagementTarget(userId = userId, roomId = route.roomId),
                    permissions.permissions
                        ?.takeIf { snapshot -> snapshot.roomId == route.roomId }
                        ?.let { snapshot ->
                            RoomRoleCapabilities(
                                canEdit = snapshot.canEdit,
                                ownPowerLevel = snapshot.ownPowerLevel
                            )
                        }
                )
            }
        }.collect { input ->
            if (input == null) {
                roomRoleManagementStore.deactivate()
            } else {
                roomRoleManagementStore.activate(
                    target = input.first,
                    capabilities = input.second
                )
            }
        }
    }

    private suspend fun observeRoomMembersPrefetchInputs() {
        combine(
            _uiState,
            roomListStore.state,
            roomDetailsStore.state
        ) { state, roomList, roomDetails ->
            val route = state.route as? AppRoute.RoomDetails
            val userId = state.matrixState.userIdOrNull()
            if (route == null || userId == null) {
                return@combine null
            }

            val seed = roomList.roomForId(route.roomId)
            val liveDetails = roomDetails
                .takeIf {
                    it.target == RoomDetailsTarget(userId = userId, roomId = route.roomId)
                }
                ?.details
            val kind = liveDetails?.kind ?: seed?.kind
            if (kind == null || kind == MatrixRoomKind.DIRECT) {
                null
            } else {
                RoomMembersRouteInput(
                    target = RoomMembersTarget(userId = userId, roomId = route.roomId),
                    expectedJoinedCount = liveDetails?.joinedMemberCount
                        ?: seed?.roomDetails?.joinedMemberCount
                )
            }
        }.collect { input ->
            if (input == null) {
                roomMembersStore.stopPrefetch()
            } else {
                roomMembersStore.prefetch(
                    target = input.target,
                    expectedJoinedCount = input.expectedJoinedCount
                )
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
        preserveSpaceContext: Boolean = false
    ): AppUiState {
        if (matrixState.userIdOrNull() != userId) {
            return this
        }
        return withNavigationState(
            if (preserveSpaceContext) {
                navState.openChatFromSpace(
                    roomId = room.id,
                    displayName = room.displayName
                )
            } else {
                navState.openChat(
                    roomId = room.id,
                    displayName = room.displayName
                )
            }
        )
    }

    private fun AppRoute.directRoomActionOwnerKey(): String? {
        return when (this) {
            AppRoute.Contacts -> "contacts"
            is AppRoute.UserProfile -> "user-profile:$userId"
            is AppRoute.RoomMemberDetails -> "room-member:$roomId:$userId"
            else -> null
        }
    }

    private fun AppRoute.perfName(): String {
        return when (this) {
            AppRoute.Calls -> "Calls"
            AppRoute.ChatThemeSettings -> "ChatThemeSettings"
            is AppRoute.UserProfile -> "UserProfile(${userId.shortLogId()})"
            AppRoute.Contacts -> "Contacts"
            AppRoute.CreateRoom -> "CreateRoom"
            AppRoute.ForwardPicker -> "ForwardPicker"
            AppRoute.Login -> "Login"
            AppRoute.EditProfile -> "EditProfile"
            is AppRoute.EditRoomProfile -> "EditRoomProfile(${roomId.shortLogId()})"
            AppRoute.Profile -> "Profile"
            is AppRoute.RecoveryKey -> "RecoveryKey"
            is AppRoute.SessionSecurity -> "SessionSecurity"
            is AppRoute.RoomDetails -> "RoomDetails(${roomId.shortLogId()})"
            is AppRoute.RoomMembers -> "RoomMembers(${roomId.shortLogId()})"
            is AppRoute.RoomMemberDetails ->
                "RoomMemberDetails(${roomId.shortLogId()},${userId.shortLogId()})"
            is AppRoute.RoomPermissions -> "RoomPermissions(${roomId.shortLogId()})"
            is AppRoute.RoomRoleManagement ->
                "RoomRoleManagement(${roomId.shortLogId()})"
            is AppRoute.InviteRoomMembers -> "InviteRoomMembers(${roomId.shortLogId()})"
            is AppRoute.InviteCreatedRoomMembers ->
                "InviteCreatedRoomMembers(${roomId.shortLogId()})"
            is AppRoute.Space ->
                "Space(${spaceId.shortLogId()},parent=${parentSpaceId?.shortLogId()})"
            is AppRoute.SpaceJoinPreview ->
                "SpaceJoinPreview(${roomId.shortLogId()},parent=${parentSpaceId?.shortLogId()})"
            is AppRoute.SpaceLeave ->
                "SpaceLeave(${spaceId.shortLogId()},parent=${parentSpaceId?.shortLogId()})"
            is AppRoute.SpaceAddRooms ->
                "SpaceAddRooms(${spaceId.shortLogId()},parent=${parentSpaceId?.shortLogId()})"
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
            is MatrixClientState.RestoringSession -> null
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
        const val SPACE_ADD_ROOMS_COVERAGE_OWNER = "space-add-rooms"
    }
}

private fun RoomMembersState.allVisibleMembers(): Sequence<MatrixRoomMember> {
    return sequenceOf(
        invitedMembers.asSequence(),
        joinedMembers.asSequence(),
        bannedMembers.asSequence()
    ).flatten()
}

private fun RoomMembersState.memberForId(userId: String): MatrixRoomMember? {
    return allVisibleMembers().firstOrNull { member -> member.userId == userId }
}

private fun String.shortLogId(): String {
    return takeLast(10)
}

private fun AppRoute.Space.toSpaceTarget(userId: String): SpaceTarget {
    return SpaceTarget(
        userId = userId,
        spaceId = spaceId,
        parentSpaceId = parentSpaceId,
        seed = MatrixSpaceRoom(
            roomId = spaceId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            topic = topic,
            kind = MatrixSpaceRoomKind.SPACE,
            membership = MatrixSpaceMembership.JOINED,
            joinedMemberCount = 0L,
            childrenCount = 0L,
            canonicalAlias = null,
            joinRule = MatrixSpaceJoinRule.UNKNOWN,
            worldReadable = null,
            guestCanJoin = false,
            isDirect = false,
            isDm = false,
            via = emptyList()
        )
    )
}

class AppViewModelFactory(
    private val matrixClientService: MatrixClientService,
    private val matrixSpaceService: MatrixSpaceService,
    private val localCacheRepository: LocalCacheRepository,
    private val spaceCacheRepository: SpaceCacheRepository,
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
                matrixSpaceService = matrixSpaceService,
                localCacheRepository = localCacheRepository,
                spaceCacheRepository = spaceCacheRepository,
                outgoingOutboxService = outgoingOutboxService,
                matrixMediaLoader = matrixMediaLoader,
                presenceRepository = presenceRepository,
                nativeMatrixRtcCallService = nativeMatrixRtcCallService
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
