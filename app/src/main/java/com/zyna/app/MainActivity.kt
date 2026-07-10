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
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.zyna.app.data.profile.ProfileAvatarPreprocessor
import com.zyna.app.data.media.VoiceRecorderState
import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.app.AppViewModel
import com.zyna.app.ui.app.AppViewModelFactory
import com.zyna.app.ui.calls.NativeMatrixRtcCallController
import com.zyna.app.ui.calls.NativeMatrixRtcCallLaunchContext
import com.zyna.app.ui.calls.NativeMatrixRtcCallView
import com.zyna.app.ui.calls.NativeMatrixRtcCallViewActions
import com.zyna.app.ui.glass.GlassInputBarView
import com.zyna.app.ui.navigation.ZynaAppActions
import com.zyna.app.ui.navigation.ZynaRootHostView
import com.zyna.app.ui.navigation.ZynaRootPreferences
import com.zyna.app.ui.photo.PhotoMessageEditor
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
    private enum class RecordAudioPermissionRequest {
        VOICE_RECORDING,
        NATIVE_MATRIX_RTC_CALL
    }

    private enum class RootOverlayOwner {
        PHOTO_EDITOR,
        NATIVE_MATRIX_RTC_CALL
    }

    private data class ProfileAvatarPickRequest(
        val editSessionId: Long,
        val generation: Long
    )

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
    private var profileAvatarPickGeneration: Long = 0L
    private var rootOverlayOwner: RootOverlayOwner? = null
    private var nativeMatrixRtcCallController: NativeMatrixRtcCallController? = null
    private var nativeMatrixRtcCallRenderJob: Job? = null
    private val notificationPermissionPreferences by lazy {
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferMaxRefreshRate()
        OutgoingOutboxDebugHooks.handleIntent(this, intent)

        appViewModel = ViewModelProvider(
            this,
            AppViewModelFactory(
                matrixClientService = appContainer.matrixClientService,
                localCacheRepository = appContainer.localCacheRepository,
                outgoingOutboxService = appContainer.outgoingOutboxService,
                matrixMediaLoader = appContainer.matrixMediaLoader,
                nativeMatrixRtcCallService = appContainer.nativeMatrixRtcCallService
            )
        )[AppViewModel::class.java]
        cleanupProfileAvatarTempFiles(
            excludedPath = appViewModel.uiState.value.ownProfile.editAvatarLocalPath
        )

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
            handlePickedProfileAvatar(uri, request)
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

        val actions = ZynaAppActions(
            matrixMediaLoader = appContainer.matrixMediaLoader,
            audioPlaybackController = appContainer.audioPlaybackController,
            voiceRecorderController = appContainer.voiceRecorderController,
            onLogin = appViewModel::login,
            onSubmitRecoveryKey = appViewModel::submitRecoveryKey,
            onSelectTab = appViewModel::selectTab,
            onNavigateBack = appViewModel::navigateBack,
            onRefreshRooms = appViewModel::refreshRooms,
            onOpenRoom = appViewModel::openRoom,
            onContactsSearchQueryChanged = appViewModel::setContactsSearchQuery,
            onOpenUserProfile = appViewModel::openUserProfile,
            onOpenContactChat = appViewModel::openContactChat,
            onCallContact = appViewModel::callContact,
            onOpenUserProfileChat = appViewModel::openUserProfileChat,
            onCallUserProfile = appViewModel::callUserProfile,
            onRefreshUserProfile = appViewModel::refreshUserProfile,
            onOpenCallHistoryRoom = appViewModel::openCallHistoryRoom,
            onCallHistoryItem = appViewModel::callHistoryItem,
            onRefreshCallHistory = appViewModel::refreshCallHistory,
            onConsumePendingNativeMatrixRtcCallLaunch =
                appViewModel::consumePendingNativeMatrixRtcCallLaunch,
            onForwardRoomSelected = appViewModel::selectForwardRoom,
            onCancelForwardPicker = appViewModel::cancelForwardPicker,
            onRefreshChat = appViewModel::refreshCurrentChat,
            onStartNativeMatrixRtcCall = ::startNativeMatrixRtcCallWithPermission,
            onCloseChat = appViewModel::closeChat,
            onOpenRoomDetails = appViewModel::openRoomDetails,
            onLoadOlderChatMessages = appViewModel::loadOlderChatMessages,
            onLoadNewerChatMessages = appViewModel::loadNewerChatMessages,
            onJumpToChatLiveEdge = appViewModel::jumpToChatLiveEdge,
            onSendChatMessage = appViewModel::sendChatMessage,
            onAttachPhotos = ::launchPhotoPicker,
            onStartVoiceRecording = ::startVoiceRecordingWithPermission,
            onStopVoiceRecording = ::stopVoiceRecordingToPreview,
            onCancelVoiceRecording = ::cancelVoiceRecording,
            onFinishVoiceRecordingForSend = ::finishVoiceRecordingForSend,
            onSendVoiceRecording = ::sendVoiceRecording,
            onToggleVoicePreviewPlayback = ::toggleVoicePreviewPlayback,
            onReplyToMessage = appViewModel::setChatReplyTarget,
            onReplyHeaderClicked = appViewModel::jumpToChatEvent,
            onCancelReply = appViewModel::clearChatReplyTarget,
            onEditMessage = appViewModel::setChatEditTarget,
            onCancelEdit = appViewModel::clearChatEditTarget,
            onForwardMessage = appViewModel::startForwardMessage,
            onCancelForward = appViewModel::clearChatForwardTarget,
            onToggleReaction = appViewModel::toggleReaction,
            onRetryOutgoingEnvelope = appViewModel::retryOutgoingEnvelope,
            onDiscardOutgoingEnvelope = appViewModel::discardOutgoingEnvelope,
            onRedactMessage = appViewModel::redactMessage,
            onRedactMessages = appViewModel::redactMessages,
            onDebugMarkOutgoingEnvelopeFailed = appViewModel::debugMarkOutgoingEnvelopeFailed,
            onVisibleReadReceiptCandidate = appViewModel::updateVisibleReadReceiptCandidate,
            onChatJumpTargetConsumed = appViewModel::clearChatJumpTarget,
            onChatScrollToLiveEdgeConsumed = appViewModel::clearChatScrollToLiveEdgeRequest,
            onOpenProfileSettings = appViewModel::openProfileSettings,
            onOpenEditProfile = appViewModel::openEditProfile,
            onRefreshOwnProfile = appViewModel::refreshOwnProfile,
            onOwnProfileDisplayNameChanged = appViewModel::setOwnProfileDisplayNameDraft,
            onPickOwnProfileAvatar = ::launchProfileAvatarPicker,
            onRemoveOwnProfileAvatar = ::removeOwnProfileAvatarDraft,
            onSaveOwnProfile = appViewModel::saveOwnProfile,
            onOpenChatThemeSettings = appViewModel::openChatThemeSettings,
            onSelectChatBubbleTheme = appContainer.chatBubbleThemeStore::setSelectedTheme,
            onSelectAppThemeMode = appContainer.appThemeStore::setSelectedMode,
            onLogout = {
                dismissNativeMatrixRtcCall()
                pendingNativeMatrixRtcCall = null
                pendingRecordAudioPermissionRequest = null
                appContainer.nativeMatrixRtcCallService.leaveActiveCallAsync()
                appContainer.audioPlaybackController.stop()
                sendVoiceAfterFinish = false
                appContainer.voiceRecorderController.clear()
                appContainer.matrixAudioMediaLoader.clear()
                appViewModel.logout()
            }
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    nativeMatrixRtcCallController?.let { controller ->
                        controller.endCall()
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    appViewModel.uiState,
                    appContainer.chatBubbleThemeStore.selectedTheme,
                    appContainer.appThemeStore.selectedMode
                ) { state, chatBubbleTheme, appThemeMode ->
                    state to ZynaRootPreferences(
                        chatBubbleTheme = chatBubbleTheme,
                        appThemeMode = appThemeMode
                    )
                }.collect { (state, preferences) ->
                    val collectStart = ZynaPerfLog.start()
                    ZynaPerfLog.mark {
                        "activity.uiState.collect route=${state.route.perfName()} " +
                            "messages=${state.chatMessages.size} loading=${state.isLoadingChat}"
                    }
                    if (hasRenderedState) {
                        cancelVoiceComposerIfRouteChanged(latestState, state)
                    }
                    latestState = state
                    hasRenderedState = true
                    rootHost.render(state, actions, preferences)
                    startPendingNativeMatrixRtcCallIfNeeded(state, actions)
                    requestNotificationPermissionIfNeeded(state)
                    restoreNativeMatrixRtcCallOverlayIfNeeded(state)
                    renderPhotoEditor()
                    ZynaPerfLog.end(
                        collectStart,
                        "activity.uiState.rendered"
                    ) {
                        "route=${state.route.perfName()} messages=${state.chatMessages.size}"
                    }
                }
            }
        }
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
        profileAvatarPickGeneration += 1
        pendingProfileAvatarPickRequest = ProfileAvatarPickRequest(
            editSessionId = editSessionId,
            generation = profileAvatarPickGeneration
        )
        profileAvatarPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun removeOwnProfileAvatarDraft() {
        profileAvatarPickGeneration += 1
        appViewModel.removeOwnProfileAvatarDraft()
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
        actions: ZynaAppActions
    ) {
        val pendingLaunch = state.pendingNativeMatrixRtcCallLaunch ?: return
        val activeChatRoute = state.activeChatRoute ?: return
        if (activeChatRoute.roomId != pendingLaunch.roomId) {
            return
        }

        actions.onConsumePendingNativeMatrixRtcCallLaunch(pendingLaunch.requestId)
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

    private fun handlePickedProfileAvatar(uri: Uri?, request: ProfileAvatarPickRequest?) {
        if (uri == null || request == null) {
            return
        }
        lifecycleScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    ProfileAvatarPreprocessor.process(
                        contentResolver = contentResolver,
                        uri = uri,
                        outputDir = File(filesDir, PROFILE_AVATAR_DIRECTORY)
                    )
                }
                if (request.generation != profileAvatarPickGeneration) {
                    runCatching { File(draft.localPath).delete() }
                    return@launch
                }
                appViewModel.setOwnProfileAvatarDraft(
                    draft = draft,
                    editSessionId = request.editSessionId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (request.generation != profileAvatarPickGeneration) {
                    return@launch
                }
                appViewModel.setOwnProfileEditError(
                    message = error.message ?: error.javaClass.simpleName,
                    editSessionId = request.editSessionId
                )
            }
        }
    }

    private fun cleanupProfileAvatarTempFiles(excludedPath: String?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(filesDir, PROFILE_AVATAR_DIRECTORY)
                    .listFiles()
                    ?.forEach { file ->
                        if (file.absolutePath != excludedPath) {
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
    }

    override fun onResume() {
        super.onResume()
        preferMaxRefreshRate()
    }

    override fun onStart() {
        super.onStart()
        appViewModel.setChatCallInfoObserverEnabled(true)
    }

    override fun onStop() {
        appViewModel.setChatCallInfoObserverEnabled(false)
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
private const val PROFILE_AVATAR_DIRECTORY = "profile_avatars"

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
        AppRoute.Profile -> "Profile"
        is AppRoute.RecoveryKey -> "RecoveryKey"
        is AppRoute.RoomDetails -> "RoomDetails(${roomId.takeLast(10)})"
        AppRoute.Rooms -> "Rooms"
        AppRoute.Settings -> "Settings"
        is AppRoute.Chat -> "Chat(${roomId.takeLast(10)})"
    }
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
