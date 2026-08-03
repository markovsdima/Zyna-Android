package com.zyna.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyna.app.data.outgoing.OutgoingImagePreprocessor
import com.zyna.app.data.outgoing.OutgoingMediaStorage
import com.zyna.app.data.outgoing.OutgoingOutboxDebugHooks
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingPhotoDraftItem
import com.zyna.app.data.media.VoiceRecorderState
import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.app.AppViewModel
import com.zyna.app.ui.app.AppViewModelFactory
import com.zyna.app.ui.app.ExternalRouteDeliveryTracker
import com.zyna.app.ui.app.ExternalRouteIntents
import com.zyna.app.ui.avatar.AvatarCropTarget
import com.zyna.app.ui.calls.CallHistoryState
import com.zyna.app.ui.calls.NativeMatrixRtcCallController
import com.zyna.app.ui.calls.NativeMatrixRtcCallLaunchContext
import com.zyna.app.ui.calls.NativeMatrixRtcCallView
import com.zyna.app.ui.calls.NativeMatrixRtcCallViewActions
import com.zyna.app.ui.chat.ChatFeatureState
import com.zyna.app.ui.contacts.ContactsFeatureState
import com.zyna.app.ui.glass.GlassInputBarView
import com.zyna.app.ui.navigation.AppActions
import com.zyna.app.ui.navigation.CallsFeatureActions
import com.zyna.app.ui.navigation.ChatComposerActions
import com.zyna.app.ui.navigation.ChatFeatureActions
import com.zyna.app.ui.navigation.ChatMessageActions
import com.zyna.app.ui.navigation.ChatNavigationActions
import com.zyna.app.ui.navigation.ChatTimelineActions
import com.zyna.app.ui.navigation.ContactsFeatureActions
import com.zyna.app.ui.navigation.CreateRoomActions
import com.zyna.app.ui.navigation.InviteMembersFeatureActions
import com.zyna.app.ui.navigation.NavigationActions
import com.zyna.app.ui.navigation.OwnProfileActions
import com.zyna.app.ui.navigation.ProfileFeatureActions
import com.zyna.app.ui.navigation.RoomsFeatureActions
import com.zyna.app.ui.navigation.RoomDetailsFeatureActions
import com.zyna.app.ui.navigation.RoomMemberModerationActions
import com.zyna.app.ui.navigation.RoomMembersFeatureActions
import com.zyna.app.ui.navigation.RoomPermissionsFeatureActions
import com.zyna.app.ui.navigation.RoomRolesFeatureActions
import com.zyna.app.ui.navigation.RoomProfileEditorActions
import com.zyna.app.ui.navigation.SettingsFeatureActions
import com.zyna.app.ui.navigation.UserProfileActions
import com.zyna.app.ui.navigation.ZynaRenderDependencies
import com.zyna.app.ui.navigation.ZynaRootActions
import com.zyna.app.ui.navigation.ZynaRootHostView
import com.zyna.app.ui.navigation.ZynaRootPreferences
import com.zyna.app.ui.navigation.SpacesFeatureActions
import com.zyna.app.ui.photo.PhotoMessageEditor
import com.zyna.app.ui.profile.ProfileAvatarCropCoordinator
import com.zyna.app.ui.profile.ProfileAvatarCropError
import com.zyna.app.ui.profile.ProfileAvatarCropEditor
import com.zyna.app.ui.profile.ProfileAvatarCropState
import com.zyna.app.ui.profile.ProfileAvatarPickRequest
import com.zyna.app.ui.profile.createProfileAvatarCropDriver
import com.zyna.app.ui.profile.ProfileFeatureState
import com.zyna.app.ui.roomdetails.RoomFeatureState
import com.zyna.app.ui.roomprofile.RoomProfileEditorTarget
import com.zyna.app.ui.rooms.RoomListState
import com.zyna.app.ui.spaces.SpaceFeatureState
import com.zyna.app.ui.theme.ZynaAndroidTheme
import com.zyna.app.util.ZynaPerfLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private data class AppFeatureInput(
        val state: AppUiState,
        val roomList: RoomListState,
        val room: RoomFeatureState,
        val spaces: SpaceFeatureState
    )

    private data class RootFeatureInput(
        val state: AppUiState,
        val roomList: RoomListState,
        val room: RoomFeatureState,
        val spaces: SpaceFeatureState,
        val contacts: ContactsFeatureState,
        val profile: ProfileFeatureState,
        val callHistory: CallHistoryState,
        val chat: ChatFeatureState
    )

    private data class RootRenderInput(
        val state: AppUiState,
        val roomList: RoomListState,
        val room: RoomFeatureState,
        val spaces: SpaceFeatureState,
        val contacts: ContactsFeatureState,
        val profile: ProfileFeatureState,
        val callHistory: CallHistoryState,
        val chat: ChatFeatureState,
        val preferences: ZynaRootPreferences
    )

    private enum class RecordAudioPermissionRequest {
        VOICE_RECORDING,
        NATIVE_MATRIX_RTC_CALL
    }

    private enum class RootOverlayOwner {
        PHOTO_EDITOR,
        PROFILE_AVATAR_CROP,
        NATIVE_MATRIX_RTC_CALL
    }

    private val appContainer by lazy { (application as ZynaApplication).appContainer }
    private lateinit var appViewModel: AppViewModel
    private lateinit var rootHost: ZynaRootHostView
    private lateinit var photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var profileAvatarPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private var latestState: AppUiState = AppUiState()
    private var photoEditorItems: List<OutgoingPhotoDraftItem> = emptyList()
    private var isPreparingPhotos: Boolean = false
    private var photoEditorError: String? = null
    private var photoEditorView: ComposeView? = null
    private var hasRenderedState: Boolean = false
    private var voiceRecorderAutoSendHandle: AutoCloseable? = null
    private var sendVoiceAfterFinish: Boolean = false
    private var pendingRecordAudioPermissionRequest: RecordAudioPermissionRequest? = null
    private var pendingNativeMatrixRtcCall: NativeMatrixRtcCallLaunchContext? = null
    private var pendingProfileAvatarPickRequest: ProfileAvatarPickRequest? = null
    private lateinit var profileAvatarCropCoordinator: ProfileAvatarCropCoordinator
    private var profileAvatarCropView: ComposeView? = null
    private var profileAvatarCropRenderJob: Job? = null
    private var rootOverlayOwner: RootOverlayOwner? = null
    private var nativeMatrixRtcCallController: NativeMatrixRtcCallController? = null
    private var nativeMatrixRtcCallRenderJob: Job? = null
    private var externalRouteDeliveryTracker = ExternalRouteDeliveryTracker()
    private val notificationPermissionPreferences by lazy {
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalRouteDeliveryTracker = ExternalRouteDeliveryTracker(
            savedInstanceState
                ?.getStringArrayList(STATE_DELIVERED_EXTERNAL_COMMAND_IDS)
                .orEmpty()
        )
        enableEdgeToEdge()
        preferMaxRefreshRate()
        OutgoingOutboxDebugHooks.handleIntent(this, intent)

        appViewModel = ViewModelProvider(
            this,
            AppViewModelFactory(
                matrixClientService = appContainer.matrixClientService,
                matrixSpaceService = appContainer.matrixSpaceService,
                localCacheRepository = appContainer.localCacheRepository,
                spaceCacheRepository = appContainer.spaceCacheRepository,
                outgoingOutboxService = appContainer.outgoingOutboxService,
                matrixMediaLoader = appContainer.matrixMediaLoader,
                presenceRepository = appContainer.presenceRepository,
                nativeMatrixRtcCallService = appContainer.nativeMatrixRtcCallService
            )
        )[AppViewModel::class.java]
        profileAvatarCropCoordinator = ProfileAvatarCropCoordinator(
            scope = lifecycleScope,
            driver = createProfileAvatarCropDriver(
                contentResolver = contentResolver,
                sourceDirectory = File(filesDir, PROFILE_AVATAR_SOURCE_DIRECTORY),
                outputDirectory = File(filesDir, PROFILE_AVATAR_DIRECTORY)
            ),
            canDeliver = ::canDeliverAvatarCropTarget,
            onDraftReady = { draft, target ->
                when (target) {
                    is AvatarCropTarget.OwnProfile -> appViewModel.setOwnProfileAvatarDraft(
                        draft,
                        target.editSessionId
                    )
                    is AvatarCropTarget.RoomProfile -> appViewModel.setRoomProfileAvatarDraft(
                        draft = draft,
                        target = target.toRoomProfileEditorTarget(),
                        editSessionId = target.editSessionId
                    )
                    is AvatarCropTarget.CreateRoom -> appViewModel.setCreateRoomAvatarDraft(
                        draft = draft,
                        target = target.target,
                        editSessionId = target.editSessionId
                    )
                }
            },
            onPreparationError = { target ->
                when (target) {
                    is AvatarCropTarget.OwnProfile -> appViewModel.setOwnProfileEditError(
                        message = getString(R.string.profile_avatar_crop_error),
                        editSessionId = target.editSessionId
                    )
                    is AvatarCropTarget.RoomProfile ->
                        appViewModel.setRoomProfileAvatarPreparationError(
                            target = target.toRoomProfileEditorTarget(),
                            editSessionId = target.editSessionId
                        )
                    is AvatarCropTarget.CreateRoom ->
                        appViewModel.setCreateRoomAvatarPreparationError(
                            target = target.target,
                            editSessionId = target.editSessionId
                        )
                }
            },
            onSessionWillClose = ::disposeProfileAvatarCropOverlay
        )
        handleExternalRouteIntent(intent)
        cleanupProfileAvatarTempFiles(
            excludedPaths = setOfNotNull(
                appViewModel.ownProfileState.value.editAvatarLocalPath,
                appViewModel.roomProfileEditorState.value.editAvatarLocalPath,
                appViewModel.createRoomState.value.avatarLocalPath
            )
        )
        profileAvatarCropCoordinator.cleanupOrphanSources()

        photoPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(10)
        ) { uris ->
            handlePickedPhotos(uris)
        }
        profileAvatarPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            val request = pendingProfileAvatarPickRequest
            pendingProfileAvatarPickRequest = null
            profileAvatarCropCoordinator.handlePickerResult(uri, request)
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // The notification renderer checks permission before posting.
        }
        recordAudioPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val pendingRequest = pendingRecordAudioPermissionRequest
            val pendingCall = pendingNativeMatrixRtcCall
            pendingRecordAudioPermissionRequest = null
            pendingNativeMatrixRtcCall = null

            when (pendingRequest) {
                RecordAudioPermissionRequest.VOICE_RECORDING -> {
                    if (granted) {
                        startVoiceRecording()
                    } else {
                        appContainer.voiceRecorderController.cancelRecording()
                    }
                }
                RecordAudioPermissionRequest.NATIVE_MATRIX_RTC_CALL -> {
                    if (granted && pendingCall != null) {
                        presentNativeMatrixRtcCall(pendingCall)
                    }
                }
                null -> {
                    if (!granted) {
                        appContainer.voiceRecorderController.cancelRecording()
                    }
                }
            }
        }
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                nativeMatrixRtcCallController?.toggleCamera()
            }
        }

        rootHost = ZynaRootHostView(this)
        setContentView(rootHost)
        voiceRecorderAutoSendHandle = appContainer.voiceRecorderController.addListener(
            ::handleVoiceRecorderAutoSendState
        )

        val actions = createRootActions()
        val renderDependencies = createRenderDependencies()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    if (
                        nativeMatrixRtcCallController == null &&
                        profileAvatarCropCoordinator.state.value.session == null
                    ) {
                        rootHost.handleSystemBackStarted()
                    }
                }

                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    rootHost.handleSystemBackProgressed(backEvent.progress)
                }

                override fun handleOnBackCancelled() {
                    rootHost.handleSystemBackCancelled()
                }

                override fun handleOnBackPressed() {
                    nativeMatrixRtcCallController?.let { controller ->
                        controller.endCall()
                        return
                    }
                    profileAvatarCropCoordinator.state.value.session?.let {
                        if (!profileAvatarCropCoordinator.state.value.isProcessing) {
                            profileAvatarCropCoordinator.dismiss()
                        }
                        return
                    }
                    if (rootHost.handleSystemBackPressed()) {
                        return
                    }
                    if (rootHost.handleBack()) {
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        profileAvatarCropRenderJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileAvatarCropCoordinator.state.collect { state ->
                    renderProfileAvatarCrop(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    combine(
                        combine(
                            appViewModel.uiState,
                            appViewModel.roomListState,
                            combine(
                                combine(
                                    appViewModel.roomDetailsState,
                                    appViewModel.roomProfileEditorState,
                                    appViewModel.createRoomState,
                                    appViewModel.roomMembersState,
                                    appViewModel.inviteMembersState
                                ) {
                                        roomDetails,
                                        roomProfileEditor,
                                        createRoom,
                                        roomMembers,
                                        inviteMembers
                                    ->
                                    RoomFeatureState(
                                        details = roomDetails,
                                        profileEditor = roomProfileEditor,
                                        createRoom = createRoom,
                                        members = roomMembers,
                                        inviteMembers = inviteMembers
                                    )
                                },
                                appViewModel.roomMemberModerationState,
                                appViewModel.roomPermissionsState,
                                appViewModel.roomRoleManagementState,
                                appViewModel.roomLeaveState
                            ) { room, memberModeration, permissions, roles, leave ->
                                room.copy(
                                    memberModeration = memberModeration,
                                    permissions = permissions,
                                    roles = roles,
                                    leave = leave
                                )
                            },
                            combine(
                                appViewModel.spaceRootsState,
                                appViewModel.spaceChildrenState,
                                appViewModel.spaceJoinState,
                                appViewModel.spaceLeaveState,
                                appViewModel.spaceAddRoomsState
                            ) { roots, children, join, leave, addRooms ->
                                SpaceFeatureState(
                                    roots = roots,
                                    children = children,
                                    join = join,
                                    leave = leave,
                                    addRooms = addRooms
                                )
                            }
                        ) { state, roomList, room, spaces ->
                            AppFeatureInput(
                                state = state,
                                roomList = roomList,
                                room = room,
                                spaces = spaces
                            )
                        },
                        combine(
                            appViewModel.contactsState,
                            appViewModel.directRoomActionState
                        ) { directory, directRoomAction ->
                            ContactsFeatureState(
                                directory = directory,
                                directRoomAction = directRoomAction
                            )
                        },
                        combine(
                            appViewModel.ownProfileState,
                            appViewModel.userProfileState
                        ) { own, user ->
                            ProfileFeatureState(own = own, user = user)
                        },
                        appViewModel.callHistoryState,
                        combine(
                            appViewModel.chatComposerState,
                            appViewModel.chatTimelineState,
                            appViewModel.chatCallInfoState
                        ) { composer, timeline, callInfo ->
                            ChatFeatureState(
                                composer = composer,
                                timeline = timeline,
                                callInfo = callInfo
                            )
                        }
                    ) { app, contacts, profile, callHistory, chat ->
                        RootFeatureInput(
                            state = app.state,
                            roomList = app.roomList,
                            room = app.room,
                            spaces = app.spaces,
                            contacts = contacts,
                            profile = profile,
                            callHistory = callHistory,
                            chat = chat
                        )
                    },
                    appContainer.chatBubbleThemeStore.selectedTheme,
                    appContainer.appThemeStore.selectedMode,
                    appContainer.presenceSettingsStore.selectedProvider
                ) { feature, chatBubbleTheme, appThemeMode, presenceProvider ->
                    RootRenderInput(
                        state = feature.state,
                        roomList = feature.roomList,
                        room = feature.room,
                        spaces = feature.spaces,
                        contacts = feature.contacts,
                        profile = feature.profile,
                        callHistory = feature.callHistory,
                        chat = feature.chat,
                        preferences = ZynaRootPreferences(
                            chatBubbleTheme = chatBubbleTheme,
                            appThemeMode = appThemeMode,
                            presenceProvider = presenceProvider
                        )
                    )
                }.collect { input ->
                    val state = input.state
                    val collectStart = ZynaPerfLog.start()
                    ZynaPerfLog.mark {
                        "activity.uiState.collect route=${state.route.perfName()} " +
                            "messages=${input.chat.timeline.messages.size} " +
                            "loading=${input.chat.timeline.isLoading}"
                    }
                    if (hasRenderedState) {
                        cancelVoiceComposerIfRouteChanged(latestState, state)
                    }
                    latestState = state
                    hasRenderedState = true
                    rootHost.render(
                        state = state,
                        roomList = input.roomList,
                        room = input.room,
                        spaces = input.spaces,
                        contacts = input.contacts,
                        profile = input.profile,
                        callHistory = input.callHistory,
                        chat = input.chat,
                        actions = actions,
                        dependencies = renderDependencies,
                        preferences = input.preferences
                    )
                    startPendingNativeMatrixRtcCallIfNeeded(state, actions)
                    requestNotificationPermissionIfNeeded(state)
                    restoreNativeMatrixRtcCallOverlayIfNeeded(state)
                    // Coordinator state drives the crop contents; the root render also checks
                    // the app route so an active crop is dismissed when EditProfile closes.
                    renderProfileAvatarCrop()
                    renderPhotoEditor()
                    ZynaPerfLog.end(
                        collectStart,
                        "activity.uiState.rendered"
                    ) {
                        "route=${state.route.perfName()} " +
                            "messages=${input.chat.timeline.messages.size}"
                    }
                }
            }
        }
    }

    private fun createRootActions(): ZynaRootActions {
        return ZynaRootActions(
            app = AppActions(
                onLogin = appViewModel::login,
                onSessionSecurityAction = appViewModel::handleSessionSecurityAction
            ),
            navigation = NavigationActions(
                onSelectTab = appViewModel::selectTab,
                onNavigateBack = appViewModel::navigateBack
            ),
            contacts = ContactsFeatureActions(
                onSearchQueryChanged = appViewModel::setContactsSearchQuery,
                onOpenChat = appViewModel::openContactChat,
                onCall = appViewModel::callContact
            ),
            calls = CallsFeatureActions(
                onOpenHistoryRoom = appViewModel::openCallHistoryRoom,
                onCallHistoryItem = appViewModel::callHistoryItem,
                onConsumePendingLaunch = appViewModel::consumePendingNativeMatrixRtcCallLaunch,
                onStart = ::startNativeMatrixRtcCallWithPermission
            ),
            rooms = RoomsFeatureActions(
                onOpenRoom = appViewModel::openRoom,
                onCreateRoom = appViewModel::openCreateRoom,
                onCreateStoryline = appViewModel::openCreateStoryline,
                onForwardRoomSelected = appViewModel::selectForwardRoom,
                onVisibleRoomsChanged = appViewModel::updateVisibleRooms,
                onVisibleRoomsInactive = appViewModel::clearVisibleRooms,
                onRetrySynchronization = appViewModel::retryRoomListSynchronization
            ),
            spaces = SpacesFeatureActions(
                onOpenRoom = appViewModel::openSpaceChild,
                onLoadMore = appViewModel::loadMoreSpaceChildren,
                onRetry = appViewModel::retrySpaceChildren,
                onOpenDetails = appViewModel::openRoomDetails,
                onOpenAddRooms = appViewModel::openSpaceAddRooms,
                onSetAddRoomsSearchQuery = appViewModel::setSpaceAddRoomsSearchQuery,
                onToggleAddRoom = appViewModel::toggleSpaceRoomToAdd,
                onSaveAddedRooms = appViewModel::saveSpaceRoomsToAdd,
                onEnterManagement = appViewModel::enterSpaceManagement,
                onExitManagement = appViewModel::exitSpaceManagement,
                onToggleManagedRoom = appViewModel::toggleManagedSpaceRoom,
                onToggleAllManagedRooms = appViewModel::toggleAllManagedSpaceRooms,
                onRequestManagedRoomsRemoval = appViewModel::requestManagedSpaceRoomsRemoval,
                onConfirmManagedRoomsRemoval = appViewModel::confirmManagedSpaceRoomsRemoval,
                onCancelManagedRoomsRemoval = appViewModel::cancelManagedSpaceRoomsRemoval,
                onPerformJoinAction = appViewModel::performSpaceJoinAction,
                onRetryJoinPreview = appViewModel::retrySpaceJoinPreview,
                onToggleLeaveRoom = appViewModel::toggleSpaceLeaveRoom,
                onToggleAllLeaveRooms = appViewModel::toggleAllSpaceLeaveRooms,
                onLeaveSpace = appViewModel::leaveSpace,
                onRetrySpaceLeave = appViewModel::retrySpaceLeave,
                onResolveSpaceOwnership = appViewModel::resolveSpaceLeaveOwnership
            ),
            createRoom = CreateRoomActions(
                onNameChanged = appViewModel::setCreateRoomName,
                onTopicChanged = appViewModel::setCreateRoomTopic,
                onAccessChanged = appViewModel::setCreateRoomAccess,
                onPostingPermissionChanged = appViewModel::setCreateRoomPostingPermission,
                onAliasChanged = appViewModel::setCreateRoomAlias,
                onRetryAliasCheck = appViewModel::retryCreateRoomAliasCheck,
                onPickAvatar = ::launchCreateRoomAvatarPicker,
                onRemoveAvatar = ::removeCreateRoomAvatar,
                onCreate = appViewModel::createRoom,
                onConfirmDiscard = appViewModel::confirmCreateRoomDiscard,
                onCancelDiscard = appViewModel::cancelCreateRoomDiscard
            ),
            roomDetails = RoomDetailsFeatureActions(
                onRefresh = appViewModel::refreshRoomDetails,
                onOpenProfileEditor = appViewModel::openEditRoomProfile,
                onOpenMembers = appViewModel::openRoomMembers,
                onOpenInviteMembers = appViewModel::openInviteRoomMembers,
                onOpenPermissions = appViewModel::openRoomPermissions,
                onRequestLeave = appViewModel::requestRoomLeave,
                onConfirmLeave = appViewModel::confirmRoomLeave,
                onCancelLeave = appViewModel::cancelRoomLeave
            ),
            roomPermissions = RoomPermissionsFeatureActions(
                onRetry = appViewModel::retryRoomPermissions,
                onOpenRoleManagement = appViewModel::openRoomRoleManagement,
                onSetPermission = appViewModel::setRoomPermission
            ),
            roomRoles = RoomRolesFeatureActions(
                onRetryMembers = appViewModel::retryRoomMembers,
                onSearchQueryChanged = appViewModel::setRoomMembersSearchQuery,
                onSetRole = appViewModel::setRoomMemberRole,
                onConfirmRoleChange = appViewModel::confirmRoomMemberRoleChange,
                onCancelRoleChange = appViewModel::cancelRoomMemberRoleChange
            ),
            roomProfileEditor = RoomProfileEditorActions(
                onDisplayNameChanged = appViewModel::setRoomProfileDisplayNameDraft,
                onPickAvatar = ::launchRoomProfileAvatarPicker,
                onRemoveAvatar = ::removeRoomProfileAvatarDraft,
                onSave = appViewModel::saveRoomProfile,
                onConfirmDiscard = appViewModel::confirmRoomProfileDiscard,
                onCancelDiscard = appViewModel::cancelRoomProfileDiscard
            ),
            roomMembers = RoomMembersFeatureActions(
                onRetry = appViewModel::retryRoomMembers,
                onSearchQueryChanged = appViewModel::setRoomMembersSearchQuery,
                onOpenInviteMembers = appViewModel::openInviteRoomMembers,
                onOpenMember = appViewModel::openRoomMemberDetails
            ),
            roomMemberModeration = RoomMemberModerationActions(
                onRetry = appViewModel::retryRoomMemberModeration,
                onMessage = appViewModel::openRoomMemberChat,
                onRequest = appViewModel::requestRoomMemberModeration,
                onConfirm = appViewModel::confirmRoomMemberModeration,
                onCancel = appViewModel::cancelRoomMemberModeration
            ),
            inviteMembers = InviteMembersFeatureActions(
                onRetryPreparation = appViewModel::retryInviteMembersPreparation,
                onRetrySearch = appViewModel::retryInviteMembersSearch,
                onSearchQueryChanged = appViewModel::setInviteMembersSearchQuery,
                onToggleSelection = appViewModel::toggleInviteMemberSelection,
                onSend = appViewModel::sendRoomMemberInvites
            ),
            chat = ChatFeatureActions(
                navigation = ChatNavigationActions(
                    onClose = appViewModel::closeChat,
                    onOpenRoomDetails = appViewModel::openRoomDetails
                ),
                timeline = ChatTimelineActions(
                    onLoadOlder = appViewModel::loadOlderChatMessages,
                    onLoadNewer = appViewModel::loadNewerChatMessages,
                    onJumpToLiveEdge = appViewModel::jumpToChatLiveEdge,
                    onReplyHeaderClicked = appViewModel::jumpToChatEvent,
                    onVisibleReadReceiptCandidate =
                        appViewModel::updateVisibleReadReceiptCandidate,
                    onJumpTargetConsumed = appViewModel::clearChatJumpTarget,
                    onScrollToLiveEdgeConsumed =
                        appViewModel::clearChatScrollToLiveEdgeRequest
                ),
                composer = ChatComposerActions(
                    onSendMessage = appViewModel::sendChatMessage,
                    onAttachPhotos = ::launchPhotoPicker,
                    onStartVoiceRecording = ::startVoiceRecordingWithPermission,
                    onStopVoiceRecording = ::stopVoiceRecordingToPreview,
                    onCancelVoiceRecording = ::cancelVoiceRecording,
                    onFinishVoiceRecordingForSend = ::finishVoiceRecordingForSend,
                    onSendVoiceRecording = ::sendVoiceRecording,
                    onToggleVoicePreviewPlayback = ::toggleVoicePreviewPlayback,
                    onReplyToMessage = appViewModel::setChatReplyTarget,
                    onCancelReply = appViewModel::clearChatReplyTarget,
                    onEditMessage = appViewModel::setChatEditTarget,
                    onCancelEdit = appViewModel::clearChatEditTarget,
                    onForwardMessage = appViewModel::startForwardMessage,
                    onCancelForward = appViewModel::clearChatForwardTarget
                ),
                messages = ChatMessageActions(
                    onToggleReaction = appViewModel::toggleReaction,
                    onRetryOutgoingEnvelope = appViewModel::retryOutgoingEnvelope,
                    onDiscardOutgoingEnvelope = appViewModel::discardOutgoingEnvelope,
                    onRedactMessage = appViewModel::redactMessage,
                    onRedactMessages = appViewModel::redactMessages,
                    onDebugMarkOutgoingEnvelopeFailed =
                        appViewModel::debugMarkOutgoingEnvelopeFailed
                )
            ),
            profile = ProfileFeatureActions(
                user = UserProfileActions(
                    onOpen = appViewModel::openUserProfile,
                    onOpenChat = appViewModel::openUserProfileChat,
                    onCall = appViewModel::callUserProfile,
                    onRefresh = appViewModel::refreshUserProfile
                ),
                own = OwnProfileActions(
                    onOpenSettings = appViewModel::openProfileSettings,
                    onOpenEdit = appViewModel::openEditProfile,
                    onRefresh = appViewModel::refreshOwnProfile,
                    onDisplayNameChanged = appViewModel::setOwnProfileDisplayNameDraft,
                    onPickAvatar = ::launchProfileAvatarPicker,
                    onRemoveAvatar = ::removeOwnProfileAvatarDraft,
                    onSave = appViewModel::saveOwnProfile,
                    onConfirmEditExit = appViewModel::confirmEditProfileExit,
                    onCancelEditExit = appViewModel::cancelEditProfileExit
                )
            ),
            settings = SettingsFeatureActions(
                onOpenChatTheme = appViewModel::openChatThemeSettings,
                onOpenSessionSecurity = appViewModel::openSessionSecurity,
                onSelectChatBubbleTheme = appContainer.chatBubbleThemeStore::setSelectedTheme,
                onSelectAppThemeMode = appContainer.appThemeStore::setSelectedMode,
                onSelectPresenceProvider =
                    appContainer.presenceSettingsStore::setSelectedProvider,
                onLogoutRequested = appViewModel::requestLogout,
                onLogoutCancelled = appViewModel::cancelLogout,
                onLogoutConfirmed = ::confirmLogout
            )
        )
    }

    private fun createRenderDependencies(): ZynaRenderDependencies {
        return ZynaRenderDependencies(
            matrixMediaLoader = appContainer.matrixMediaLoader,
            audioPlaybackController = appContainer.audioPlaybackController,
            voiceRecorderController = appContainer.voiceRecorderController
        )
    }

    private fun confirmLogout() {
        appViewModel.confirmLogout()
        dismissNativeMatrixRtcCall()
        pendingNativeMatrixRtcCall = null
        pendingRecordAudioPermissionRequest = null
        appContainer.nativeMatrixRtcCallService.leaveActiveCallAsync()
        appContainer.audioPlaybackController.stop()
        sendVoiceAfterFinish = false
        appContainer.voiceRecorderController.clear()
        appContainer.matrixAudioMediaLoader.clear()
    }

    private fun cancelVoiceComposerIfRouteChanged(previous: AppUiState, next: AppUiState) {
        val previousChat = previous.activeChatRoute
        val nextChat = next.activeChatRoute
        if (previousChat?.roomId == nextChat?.roomId) {
            return
        }
        cancelVoiceRecording()
    }

    private fun launchPhotoPicker() {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchProfileAvatarPicker(editSessionId: Long) {
        pendingProfileAvatarPickRequest = profileAvatarCropCoordinator.beginPick(
            AvatarCropTarget.OwnProfile(editSessionId)
        )
        profileAvatarPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchRoomProfileAvatarPicker(editSessionId: Long) {
        val editor = appViewModel.roomProfileEditorState.value
        val target = editor.target ?: return
        if (editor.editSessionId != editSessionId) return
        pendingProfileAvatarPickRequest = profileAvatarCropCoordinator.beginPick(
            AvatarCropTarget.RoomProfile(
                userId = target.userId,
                roomId = target.roomId,
                editSessionId = editSessionId
            )
        )
        profileAvatarPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchCreateRoomAvatarPicker(editSessionId: Long) {
        val creation = appViewModel.createRoomState.value
        val target = creation.target ?: return
        if (creation.editSessionId != editSessionId) return
        pendingProfileAvatarPickRequest = profileAvatarCropCoordinator.beginPick(
            AvatarCropTarget.CreateRoom(
                target = target,
                editSessionId = editSessionId
            )
        )
        profileAvatarPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun removeOwnProfileAvatarDraft() {
        profileAvatarCropCoordinator.dismiss()
        appViewModel.removeOwnProfileAvatarDraft()
    }

    private fun removeRoomProfileAvatarDraft() {
        profileAvatarCropCoordinator.dismiss()
        appViewModel.removeRoomProfileAvatarDraft()
    }

    private fun removeCreateRoomAvatar() {
        profileAvatarCropCoordinator.dismiss()
        appViewModel.removeCreateRoomAvatar()
    }

    private fun canDeliverAvatarCropTarget(target: AvatarCropTarget): Boolean {
        return when (target) {
            is AvatarCropTarget.OwnProfile ->
                appViewModel.uiState.value.route == AppRoute.EditProfile &&
                    appViewModel.ownProfileState.value.editSessionId == target.editSessionId
            is AvatarCropTarget.RoomProfile -> {
                val editor = appViewModel.roomProfileEditorState.value
                val route = appViewModel.uiState.value.route as? AppRoute.EditRoomProfile
                route?.roomId == target.roomId &&
                    editor.target == target.toRoomProfileEditorTarget() &&
                    editor.editSessionId == target.editSessionId
            }
            is AvatarCropTarget.CreateRoom -> {
                val creation = appViewModel.createRoomState.value
                appViewModel.uiState.value.route == AppRoute.CreateRoom &&
                    creation.target == target.target &&
                    creation.editSessionId == target.editSessionId
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded(state: AppUiState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (state.route is AppRoute.Login || state.route is AppRoute.RecoveryKey) {
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (notificationPermissionPreferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) {
            return
        }
        notificationPermissionPreferences.edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            .apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startPendingNativeMatrixRtcCallIfNeeded(
        state: AppUiState,
        actions: ZynaRootActions
    ) {
        val pendingLaunch = state.pendingNativeMatrixRtcCallLaunch ?: return
        val activeChatRoute = state.activeChatRoute ?: return
        if (activeChatRoute.roomId != pendingLaunch.roomId) {
            return
        }

        actions.calls.onConsumePendingLaunch(pendingLaunch.requestId)
        startNativeMatrixRtcCallWithPermission(
            roomId = pendingLaunch.roomId,
            roomName = pendingLaunch.roomName
        )
    }

    private fun startNativeMatrixRtcCallWithPermission(roomId: String, roomName: String) {
        val chatRoute = latestState.activeChatRoute ?: return
        if (chatRoute.roomId != roomId || nativeMatrixRtcCallController != null) {
            return
        }
        val activeRoomId = appContainer.nativeMatrixRtcCallService.currentRoomId()
        val launchContext = NativeMatrixRtcCallLaunchContext(
            roomId = roomId,
            roomName = roomName.ifBlank { chatRoute.displayName }
        )
        if (activeRoomId != null) {
            if (activeRoomId == roomId) {
                presentNativeMatrixRtcCall(
                    launchContext = launchContext,
                    startCall = false
                )
            }
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            presentNativeMatrixRtcCall(launchContext)
        } else {
            pendingRecordAudioPermissionRequest = RecordAudioPermissionRequest.NATIVE_MATRIX_RTC_CALL
            pendingNativeMatrixRtcCall = launchContext
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun presentNativeMatrixRtcCall(
        launchContext: NativeMatrixRtcCallLaunchContext,
        startCall: Boolean = true
    ) {
        val chatRoute = latestState.activeChatRoute ?: return
        if (chatRoute.roomId != launchContext.roomId || nativeMatrixRtcCallController != null) {
            return
        }
        if (startCall && appContainer.nativeMatrixRtcCallService.currentRoomId() != null) {
            return
        }

        cancelVoiceRecording()
        val view = NativeMatrixRtcCallView(this)
        val controller = NativeMatrixRtcCallController(
            context = this,
            launchContext = launchContext,
            callService = appContainer.nativeMatrixRtcCallService,
            scope = lifecycleScope,
            startCallOnStart = startCall,
            onDismiss = ::dismissNativeMatrixRtcCall
        )
        val actions = NativeMatrixRtcCallViewActions(
            onToggleMicrophone = controller::toggleMicrophone,
            onToggleSpeakerphone = controller::toggleSpeakerphone,
            onToggleCamera = ::toggleNativeMatrixRtcCameraWithPermission,
            onSwitchCamera = controller::switchCamera,
            onEndCall = controller::endCall
        )

        nativeMatrixRtcCallController = controller
        rootOverlayOwner = RootOverlayOwner.NATIVE_MATRIX_RTC_CALL
        rootHost.showOverlay(view)
        view.render(controller.viewState.value, actions)
        nativeMatrixRtcCallRenderJob?.cancel()
        nativeMatrixRtcCallRenderJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.viewState.collect { state ->
                    view.render(state, actions)
                }
            }
        }
        controller.start()
        controller.restoreServiceState()
    }

    private fun toggleNativeMatrixRtcCameraWithPermission() {
        val controller = nativeMatrixRtcCallController ?: return
        if (
            controller.viewState.value.isCameraEnabled ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            controller.toggleCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun restoreNativeMatrixRtcCallOverlayIfNeeded(state: AppUiState) {
        if (nativeMatrixRtcCallController != null) {
            return
        }
        val activeRoomId = appContainer.nativeMatrixRtcCallService.currentRoomId() ?: return
        val chatRoute = state.activeChatRoute ?: return
        if (chatRoute.roomId != activeRoomId) {
            return
        }

        presentNativeMatrixRtcCall(
            launchContext = NativeMatrixRtcCallLaunchContext(
                roomId = activeRoomId,
                roomName = chatRoute.displayName
            ),
            startCall = false
        )
    }

    private fun dismissNativeMatrixRtcCall() {
        nativeMatrixRtcCallRenderJob?.cancel()
        nativeMatrixRtcCallRenderJob = null
        nativeMatrixRtcCallController?.close()
        nativeMatrixRtcCallController = null
        if (rootOverlayOwner == RootOverlayOwner.NATIVE_MATRIX_RTC_CALL) {
            rootOverlayOwner = null
            rootHost.showOverlay(null)
            renderPhotoEditor()
        }
    }

    private fun startVoiceRecordingWithPermission(): Boolean {
        if (latestState.route !is AppRoute.Chat) {
            return false
        }
        if (
            nativeMatrixRtcCallController != null ||
            appContainer.nativeMatrixRtcCallService.currentRoomId() != null
        ) {
            return false
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return startVoiceRecording()
        } else {
            pendingRecordAudioPermissionRequest = RecordAudioPermissionRequest.VOICE_RECORDING
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
    }

    private fun startVoiceRecording(): Boolean {
        if (latestState.route !is AppRoute.Chat) {
            return false
        }
        if (
            nativeMatrixRtcCallController != null ||
            appContainer.nativeMatrixRtcCallService.currentRoomId() != null
        ) {
            return false
        }
        sendVoiceAfterFinish = false
        appContainer.audioPlaybackController.stop()
        appContainer.voiceRecorderController.startRecording()
        return true
    }

    private fun stopVoiceRecordingToPreview() {
        sendVoiceAfterFinish = false
        appContainer.voiceRecorderController.stopRecording()
    }

    private fun cancelVoiceRecording() {
        sendVoiceAfterFinish = false
        appContainer.audioPlaybackController.stop()
        appContainer.voiceRecorderController.cancelRecording()
    }

    private fun finishVoiceRecordingForSend(): Boolean {
        if (latestState.route !is AppRoute.Chat) {
            return false
        }
        val controller = appContainer.voiceRecorderController
        if (controller.stateSnapshot() !is VoiceRecorderState.Recording) {
            return false
        }
        sendVoiceAfterFinish = true
        controller.stopRecording()
        return true
    }

    private fun handleVoiceRecorderAutoSendState(state: VoiceRecorderState) {
        if (!sendVoiceAfterFinish) {
            return
        }
        when (state) {
            is VoiceRecorderState.Finished -> {
                sendVoiceAfterFinish = false
                rootHost.post {
                    sendVoiceRecording()
                }
            }
            VoiceRecorderState.Idle,
            is VoiceRecorderState.Error -> {
                sendVoiceAfterFinish = false
            }
            is VoiceRecorderState.Recording -> Unit
        }
    }

    private fun sendVoiceRecording(): Boolean {
        val controller = appContainer.voiceRecorderController
        val finished = controller.stateSnapshot()
            as? VoiceRecorderState.Finished
            ?: return false
        val localPath = finished.localPath
        val didSend = appViewModel.sendVoiceMessage(finished.toDraft()) {
            val currentFinished = controller.stateSnapshot() as? VoiceRecorderState.Finished
            if (currentFinished?.localPath == localPath) {
                appContainer.audioPlaybackController.stop()
                controller.consumeFinished()
            }
        }
        if (didSend) {
            appContainer.audioPlaybackController.stop()
        }
        return didSend
    }

    private fun stopActiveVoiceRecordingToPreviewForBackground() {
        val controller = appContainer.voiceRecorderController
        if (controller.stateSnapshot() !is VoiceRecorderState.Recording) {
            return
        }
        sendVoiceAfterFinish = false
        controller.stopRecording()
    }

    private fun toggleVoicePreviewPlayback() {
        val finished = appContainer.voiceRecorderController.stateSnapshot()
            as? VoiceRecorderState.Finished
            ?: return
        val sourceJson = "local:${finished.localPath}"
        appContainer.audioPlaybackController.toggle(
            messageId = "voice-preview:${finished.localPath}",
            audioInfo = MatrixAudioInfo(
                sourceJson = sourceJson,
                filename = File(finished.localPath).name,
                caption = null,
                mimeType = finished.mimeType,
                sizeBytes = finished.sizeBytes,
                durationMillis = finished.durationMillis,
                waveform = finished.waveform,
                isVoice = true,
                localPath = finished.localPath
            )
        )
    }

    private fun handlePickedPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }

        isPreparingPhotos = true
        photoEditorError = null
        renderPhotoEditor()
        lifecycleScope.launch {
            try {
                deletePhotoItems(photoEditorItems)
                photoEditorItems = prepareOutgoingPhotoItems(uris)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                photoEditorError = error.message ?: error.javaClass.simpleName
            } finally {
                isPreparingPhotos = false
                renderPhotoEditor()
            }
        }
    }

    private fun renderPhotoEditor() {
        val shouldShowEditor = photoEditorItems.isNotEmpty() && latestState.route is AppRoute.Chat
        if (!shouldShowEditor) {
            if (rootOverlayOwner == RootOverlayOwner.PHOTO_EDITOR) {
                rootOverlayOwner = null
                rootHost.showOverlay(null)
            }
            photoEditorView = null
            return
        }
        if (rootOverlayOwner != null && rootOverlayOwner != RootOverlayOwner.PHOTO_EDITOR) {
            return
        }

        val editorView = photoEditorView ?: ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            photoEditorView = this
        }

        editorView.setContent {
            ZynaAndroidTheme {
                PhotoMessageEditor(
                    items = photoEditorItems,
                    isSending = isPreparingPhotos,
                    errorMessage = photoEditorError,
                    onDismiss = {
                        if (!isPreparingPhotos) {
                            deletePhotoItems(photoEditorItems)
                            photoEditorItems = emptyList()
                            photoEditorError = null
                            renderPhotoEditor()
                        }
                    },
                    onDiscardItem = { item ->
                        deletePhotoItems(listOf(item))
                    },
                    onSend = { result ->
                        if (isPreparingPhotos) {
                            return@PhotoMessageEditor
                        }
                        isPreparingPhotos = true
                        photoEditorError = null
                        renderPhotoEditor()
                        lifecycleScope.launch {
                            var processedItems: List<OutgoingPhotoDraftItem> = emptyList()
                            try {
                                processedItems = processOutgoingPhotoItems(result.items)
                                val draft = OutgoingPhotoDraft(
                                    items = processedItems,
                                    caption = result.caption,
                                    captionPlacement = result.captionPlacement,
                                    layoutOverride = result.layoutOverride
                                )
                                val didSend = appViewModel.sendPhotoMessages(draft)
                                if (didSend) {
                                    deletePhotoItems(result.items)
                                    photoEditorItems = emptyList()
                                    photoEditorError = null
                                } else {
                                    deletePhotoItems(processedItems)
                                    photoEditorError = "Could not send photos"
                                }
                            } catch (error: CancellationException) {
                                deletePhotoItems(processedItems)
                                throw error
                            } catch (error: Throwable) {
                                deletePhotoItems(processedItems)
                                photoEditorError = error.message ?: error.javaClass.simpleName
                            } finally {
                                isPreparingPhotos = false
                                renderPhotoEditor()
                            }
                        }
                    }
                )
            }
        }
        rootOverlayOwner = RootOverlayOwner.PHOTO_EDITOR
        rootHost.showOverlay(editorView)
    }

    private fun renderProfileAvatarCrop(
        state: ProfileAvatarCropState = profileAvatarCropCoordinator.state.value
    ) {
        if (state.session == null) {
            disposeProfileAvatarCropOverlay()
            return
        }
        if (!canDeliverAvatarCropTarget(state.session.request.target)) {
            profileAvatarCropCoordinator.dismiss()
            return
        }
        if (
            rootOverlayOwner != null &&
            rootOverlayOwner != RootOverlayOwner.PROFILE_AVATAR_CROP
        ) {
            return
        }

        val editorView = profileAvatarCropView ?: ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ZynaAndroidTheme(darkTheme = true, dynamicColor = false) {
                    val cropState by profileAvatarCropCoordinator.state.collectAsState()
                    cropState.session?.let { activeSession ->
                        ProfileAvatarCropEditor(
                            bitmap = activeSession.previewBitmap,
                            isProcessing = cropState.isProcessing,
                            errorMessage = cropState.error
                                ?.takeIf { it == ProfileAvatarCropError.EXPORT }
                                ?.let { getString(R.string.profile_avatar_crop_error) },
                            onDismiss = profileAvatarCropCoordinator::dismiss,
                            onConfirm = profileAvatarCropCoordinator::confirm
                        )
                    }
                }
            }
            profileAvatarCropView = this
        }
        rootOverlayOwner = RootOverlayOwner.PROFILE_AVATAR_CROP
        rootHost.showOverlay(editorView)
    }

    private fun disposeProfileAvatarCropOverlay() {
        profileAvatarCropView?.disposeComposition()
        if (rootOverlayOwner == RootOverlayOwner.PROFILE_AVATAR_CROP) {
            rootOverlayOwner = null
            rootHost.showOverlay(null)
        }
        profileAvatarCropView = null
    }

    private fun cleanupProfileAvatarTempFiles(excludedPaths: Set<String>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(filesDir, PROFILE_AVATAR_DIRECTORY)
                    .listFiles()
                    ?.forEach { file ->
                        if (file.absolutePath !in excludedPaths) {
                            runCatching { file.delete() }
                        }
                    }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OutgoingOutboxDebugHooks.handleIntent(this, intent)
        handleExternalRouteIntent(intent)
    }

    private fun handleExternalRouteIntent(intent: Intent?) {
        val command = ExternalRouteIntents.consume(intent) ?: return
        if (!externalRouteDeliveryTracker.markForDelivery(command.id)) {
            return
        }
        appViewModel.handleExternalRoute(command)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_DELIVERED_EXTERNAL_COMMAND_IDS,
            externalRouteDeliveryTracker.snapshot()
        )
    }

    override fun onResume() {
        super.onResume()
        preferMaxRefreshRate()
    }

    override fun onStart() {
        super.onStart()
        appViewModel.setAppForeground(true)
        appViewModel.setChatCallInfoObserverEnabled(true)
    }

    override fun onStop() {
        appViewModel.setChatCallInfoObserverEnabled(false)
        appViewModel.setAppForeground(false)
        stopActiveVoiceRecordingToPreviewForBackground()
        super.onStop()
    }

    override fun onDestroy() {
        voiceRecorderAutoSendHandle?.close()
        voiceRecorderAutoSendHandle = null
        sendVoiceAfterFinish = false
        pendingNativeMatrixRtcCall = null
        pendingRecordAudioPermissionRequest = null
        nativeMatrixRtcCallRenderJob?.cancel()
        nativeMatrixRtcCallRenderJob = null
        nativeMatrixRtcCallController?.close()
        nativeMatrixRtcCallController = null
        profileAvatarCropRenderJob?.cancel()
        profileAvatarCropRenderJob = null
        profileAvatarCropCoordinator.close()
        if (isFinishing) {
            appContainer.voiceRecorderController.clear()
            appContainer.nativeMatrixRtcCallService.leaveActiveCallAsync()
        }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            hideKeyboardIfTapOutsideInput(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun hideKeyboardIfTapOutsideInput(event: MotionEvent) {
        val focusedView = currentFocus as? EditText ?: return
        val touchRoot = focusedView.findAncestor<GlassInputBarView>() ?: focusedView
        if (event.isInsideView(touchRoot)) {
            return
        }

        focusedView.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(focusedView.windowToken, 0)
    }

    private suspend fun prepareOutgoingPhotoItems(
        uris: List<Uri>
    ): List<OutgoingPhotoDraftItem> = withContext(Dispatchers.IO) {
        val outputDir = File(filesDir, OutgoingMediaStorage.DIRECTORY_NAME).apply {
            mkdirs()
        }
        val copiedFiles = mutableListOf<File>()
        try {
            uris.map { uri ->
                val mimeType = contentResolver.getType(uri)
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                val outputFile = File(
                    outputDir,
                    "${System.currentTimeMillis()}-${UUID.randomUUID()}.${mimeType.fileExtension()}"
                )
                contentResolver.openInputStream(uri)?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: error("Could not open selected image")
                copiedFiles += outputFile
                val dimensions = outputFile.imageDimensions()
                OutgoingPhotoDraftItem(
                    localPath = outputFile.absolutePath,
                    mimeType = mimeType,
                    width = dimensions.first,
                    height = dimensions.second,
                    sizeBytes = outputFile.length(),
                    thumbnailLocalPath = null,
                    thumbnailMimeType = null,
                    thumbnailWidth = null,
                    thumbnailHeight = null,
                    thumbnailSizeBytes = null,
                    blurhash = null
                )
            }
        } catch (error: Throwable) {
            copiedFiles.forEach { file -> file.delete() }
            throw error
        }
    }

    private suspend fun processOutgoingPhotoItems(
        items: List<OutgoingPhotoDraftItem>
    ): List<OutgoingPhotoDraftItem> = withContext(Dispatchers.IO) {
        val outputDir = File(filesDir, OutgoingMediaStorage.DIRECTORY_NAME).apply {
            mkdirs()
        }
        val processedItems = mutableListOf<OutgoingPhotoDraftItem>()
        try {
            items.map { item ->
                OutgoingImagePreprocessor.processFile(
                    file = File(item.localPath),
                    outputDir = outputDir
                ).also { processed ->
                    processedItems += processed
                }
            }
        } catch (error: Throwable) {
            deletePhotoItems(processedItems)
            throw error
        }
    }

    private fun deletePhotoItems(items: List<OutgoingPhotoDraftItem>) {
        items.flatMap { item -> item.localFiles() }
            .forEach { file -> file.delete() }
    }

    private fun String.fileExtension(): String {
        val normalized = substringBefore(';').lowercase()
        return MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(normalized)
            ?: when (normalized) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
    }

    private fun File.imageDimensions(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var sourceWidth = 0
            var sourceHeight = 0
            runCatching {
                val source = ImageDecoder.createSource(this)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    sourceWidth = info.size.width
                    sourceHeight = info.size.height
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(maxOf(info.size.width, info.size.height, 1))
                }
            }
            if (sourceWidth > 0 && sourceHeight > 0) {
                return Pair(sourceWidth, sourceHeight)
            }
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(absolutePath, options)
        return Pair(
            options.outWidth.takeIf { it > 0 } ?: 1,
            options.outHeight.takeIf { it > 0 } ?: 1
        )
    }

    @Suppress("DEPRECATION")
    private fun preferMaxRefreshRate() {
        val display = windowManager.defaultDisplay
        val currentMode = display.mode
        val preferredMode = display.supportedModes
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: display.supportedModes.maxByOrNull { it.refreshRate }
            ?: return
        val maxRefreshRate = preferredMode.refreshRate

        val attributes = window.attributes
        if (
            attributes.preferredDisplayModeId != preferredMode.modeId ||
            attributes.preferredRefreshRate != maxRefreshRate
        ) {
            attributes.preferredDisplayModeId = preferredMode.modeId
            attributes.preferredRefreshRate = maxRefreshRate
            window.attributes = attributes
        }
    }
}

private const val NOTIFICATION_PERMISSION_PREFERENCES = "zyna_notification_permission"
private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
private const val STATE_DELIVERED_EXTERNAL_COMMAND_IDS =
    "zyna.state.deliveredExternalCommandIds"
private const val PROFILE_AVATAR_DIRECTORY = "profile_avatars"
private const val PROFILE_AVATAR_SOURCE_DIRECTORY = "profile_avatar_sources"

private fun MotionEvent.isInsideView(view: View): Boolean {
    val bounds = Rect()
    return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX.toInt(), rawY.toInt())
}

private fun AppRoute.perfName(): String {
    return when (this) {
        AppRoute.Calls -> "Calls"
        AppRoute.ChatThemeSettings -> "ChatThemeSettings"
        is AppRoute.UserProfile -> "UserProfile(${userId.takeLast(10)})"
        AppRoute.Contacts -> "Contacts"
        AppRoute.ForwardPicker -> "ForwardPicker"
        AppRoute.Login -> "Login"
        AppRoute.EditProfile -> "EditProfile"
        is AppRoute.EditRoomProfile -> "EditRoomProfile(${roomId.takeLast(10)})"
        AppRoute.Profile -> "Profile"
        is AppRoute.RecoveryKey -> "RecoveryKey"
        is AppRoute.SessionSecurity -> "SessionSecurity"
        is AppRoute.RoomDetails -> "RoomDetails(${roomId.takeLast(10)})"
        is AppRoute.RoomMembers -> "RoomMembers(${roomId.takeLast(10)})"
        is AppRoute.RoomMemberDetails ->
            "RoomMemberDetails(${roomId.takeLast(10)},${userId.takeLast(10)})"
        is AppRoute.RoomPermissions -> "RoomPermissions(${roomId.takeLast(10)})"
        is AppRoute.RoomRoleManagement -> "RoomRoleManagement(${roomId.takeLast(10)})"
        is AppRoute.InviteRoomMembers -> "InviteRoomMembers(${roomId.takeLast(10)})"
        is AppRoute.InviteCreatedRoomMembers ->
            "InviteCreatedRoomMembers(${roomId.takeLast(10)})"
        is AppRoute.Space ->
            "Space(${spaceId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
        is AppRoute.SpaceJoinPreview ->
            "SpaceJoinPreview(${roomId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
        is AppRoute.SpaceLeave ->
            "SpaceLeave(${spaceId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
        is AppRoute.SpaceAddRooms ->
            "SpaceAddRooms(${spaceId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
        AppRoute.CreateRoom -> "CreateRoom"
        AppRoute.Rooms -> "Rooms"
        AppRoute.Settings -> "Settings"
        is AppRoute.Chat -> "Chat(${roomId.takeLast(10)})"
    }
}

private fun AvatarCropTarget.RoomProfile.toRoomProfileEditorTarget(): RoomProfileEditorTarget {
    return RoomProfileEditorTarget(userId = userId, roomId = roomId)
}

private fun OutgoingPhotoDraftItem.localFiles(): List<File> {
    return listOfNotNull(
        localPath.takeIf { it.isNotBlank() },
        thumbnailLocalPath?.takeIf { it.isNotBlank() }
    ).distinct().map(::File)
}

private inline fun <reified T : View> View.findAncestor(): T? {
    var current: View? = this
    while (current != null) {
        if (current is T) {
            return current
        }
        current = current.parent as? View
    }
    return null
}
