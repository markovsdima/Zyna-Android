package com.zyna.app.ui.calls

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.data.calls.matrixrtc.MatrixRtcAudioOutputState
import com.zyna.app.data.calls.matrixrtc.MatrixRtcLiveKitVideoTrackReference
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallService
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallServiceException
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallParticipantsSnapshot
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallPickupState
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallServiceState
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NativeMatrixRtcCallLaunchContext(
    val roomId: String,
    val roomName: String
)

data class NativeMatrixRtcCallViewState(
    val roomName: String,
    val statusText: String,
    val isMuted: Boolean,
    val isSpeakerphoneEnabled: Boolean,
    val canToggleSpeakerphone: Boolean,
    val audioOutputLabel: String?,
    val isBusy: Boolean,
    val canToggleMicrophone: Boolean,
    val isCameraEnabled: Boolean,
    val canToggleCamera: Boolean,
    val canSwitchCamera: Boolean,
    val canEnd: Boolean,
    val isEnding: Boolean,
    val isFailed: Boolean,
    val primaryVideoTrack: MatrixRtcLiveKitVideoTrackReference?,
    val shouldMirrorPrimaryVideo: Boolean,
    val previewVideoTrack: MatrixRtcLiveKitVideoTrackReference?,
    val shouldMirrorPreviewVideo: Boolean
)

data class NativeMatrixRtcCallViewActions(
    val onToggleMicrophone: () -> Unit,
    val onToggleSpeakerphone: () -> Unit,
    val onToggleCamera: () -> Unit,
    val onSwitchCamera: () -> Unit,
    val onEndCall: () -> Unit
)

internal class NativeMatrixRtcCallController(
    private val launchContext: NativeMatrixRtcCallLaunchContext,
    private val callService: NativeMatrixRtcCallService,
    private val scope: CoroutineScope,
    private val startCallOnStart: Boolean = true,
    private val onDismiss: () -> Unit
) : AutoCloseable {
    private val _viewState = MutableStateFlow(
        NativeMatrixRtcCallViewState(
            roomName = launchContext.roomName,
            statusText = "Connecting",
            isMuted = false,
            isSpeakerphoneEnabled = false,
            canToggleSpeakerphone = false,
            audioOutputLabel = null,
            isBusy = true,
            canToggleMicrophone = false,
            isCameraEnabled = false,
            canToggleCamera = false,
            canSwitchCamera = false,
            canEnd = true,
            isEnding = false,
            isFailed = false,
            primaryVideoTrack = null,
            shouldMirrorPrimaryVideo = false,
            previewVideoTrack = null,
            shouldMirrorPreviewVideo = false
        )
    )
    val viewState: StateFlow<NativeMatrixRtcCallViewState> = _viewState.asStateFlow()

    private var serviceStateJob: Job? = null
    private var microphoneStateJob: Job? = null
    private var cameraStateJob: Job? = null
    private var localCameraFacingFrontJob: Job? = null
    private var audioOutputStateJob: Job? = null
    private var remoteParticipantCountJob: Job? = null
    private var participantsJob: Job? = null
    private var pickupStateJob: Job? = null
    private var dismissJob: Job? = null
    private var hasStarted = false
    private var hasObservedRelevantCall = !startCallOnStart
    private var wasConnected = false
    private var isMuted = false
    private var isCameraEnabled = false
    private var isLocalCameraFacingFront = true
    private var audioOutputState = MatrixRtcAudioOutputState()
    private var remoteParticipantCount = 0
    private var participantsSnapshot = NativeMatrixRtcCallParticipantsSnapshot.empty()
    private var hasObservedRemoteParticipant = false
    private var pickupState: NativeMatrixRtcCallPickupState = NativeMatrixRtcCallPickupState.Inactive
    private var terminalStatus: String? = null
    private var isEnding = false
    private var isClosed = false

    fun start() {
        if (hasStarted || isClosed) {
            return
        }
        hasStarted = true
        serviceStateJob = scope.launch {
            callService.state.collect { serviceState ->
                handleServiceState(serviceState)
            }
        }
        microphoneStateJob = scope.launch {
            callService.microphoneEnabled.collect { enabled ->
                isMuted = !enabled
                if (viewState.value.canToggleMicrophone && !isEnding) {
                    renderConnected()
                }
            }
        }
        cameraStateJob = scope.launch {
            callService.cameraEnabled.collect { enabled ->
                isCameraEnabled = enabled
                if (viewState.value.canToggleMicrophone && !isEnding) {
                    renderConnected()
                }
            }
        }
        localCameraFacingFrontJob = scope.launch {
            callService.localCameraFacingFront.collect { facingFront ->
                isLocalCameraFacingFront = facingFront
                if (viewState.value.canToggleMicrophone && !isEnding) {
                    renderConnected()
                }
            }
        }
        audioOutputStateJob = scope.launch {
            callService.audioOutputState.collect { state ->
                audioOutputState = state
                if (viewState.value.canToggleMicrophone && !isEnding) {
                    renderConnected()
                }
            }
        }
        remoteParticipantCountJob = scope.launch {
            callService.remoteParticipantCount.collect { count ->
                remoteParticipantCount = count
                if (count > 0) {
                    hasObservedRemoteParticipant = true
                }
                if (viewState.value.canToggleMicrophone && !isEnding) {
                    renderConnected()
                }
            }
        }
        participantsJob = scope.launch {
            callService.participants.collect { snapshot ->
                if (snapshot.roomId == null || snapshot.roomId == launchContext.roomId) {
                    participantsSnapshot = snapshot
                    if (snapshot.remoteParticipantCount > 0) {
                        hasObservedRemoteParticipant = true
                    }
                    if (viewState.value.canToggleMicrophone && !isEnding) {
                        renderConnected()
                    }
                }
            }
        }
        pickupStateJob = scope.launch {
            callService.pickupState.collect { state ->
                pickupState = state
                handlePickupState(state)
            }
        }

        if (startCallOnStart) {
            callService.startAudioCallAsync(
                roomId = launchContext.roomId,
                waitForPickup = true,
                onFailure = { error ->
                    scope.launch {
                        if (!isEnding && !isClosed && !hasObservedRelevantCall) {
                            showFailure(error.callStartMessage())
                        }
                    }
                }
            )
        }
    }

    fun restoreServiceState() {
        if (isClosed) {
            return
        }
        isMuted = !callService.currentMicrophoneEnabled()
        isCameraEnabled = callService.currentCameraEnabled()
        isLocalCameraFacingFront = callService.currentLocalCameraFacingFront()
        audioOutputState = callService.currentAudioOutputState()
        remoteParticipantCount = callService.currentRemoteParticipantCount()
        participantsSnapshot = callService.currentParticipantsSnapshot()
        pickupState = callService.currentPickupState()
        if (remoteParticipantCount > 0) {
            hasObservedRemoteParticipant = true
        }
        handleServiceState(callService.state.value)
    }

    fun toggleMicrophone() {
        if (isClosed || isEnding || !viewState.value.canToggleMicrophone) {
            return
        }
        val nextMuted = !isMuted
        isMuted = nextMuted
        renderConnected()
        callService.setMicrophoneEnabledAsync(
            enabled = !nextMuted,
            onFailure = {
                scope.launch {
                    if (!isEnding && !isClosed) {
                        isMuted = !nextMuted
                        renderConnected(statusOverride = "Could not change microphone")
                    }
                }
            }
        )
    }

    fun toggleCamera() {
        if (isClosed || isEnding || !viewState.value.canToggleCamera) {
            return
        }
        val nextCameraEnabled = !isCameraEnabled
        isCameraEnabled = nextCameraEnabled
        renderConnected()
        callService.setCameraEnabledAsync(
            enabled = nextCameraEnabled,
            onFailure = {
                scope.launch {
                    if (!isEnding && !isClosed) {
                        isCameraEnabled = !nextCameraEnabled
                        renderConnected(statusOverride = "Could not change camera")
                    }
                }
            }
        )
    }

    fun switchCamera() {
        if (isClosed || isEnding || !viewState.value.canSwitchCamera) {
            return
        }
        callService.switchCameraAsync(
            onFailure = {
                scope.launch {
                    if (!isEnding && !isClosed) {
                        renderConnected(statusOverride = "Could not switch camera")
                    }
                }
            }
        )
    }

    fun toggleSpeakerphone() {
        if (isClosed || isEnding || !viewState.value.canToggleSpeakerphone) {
            return
        }
        val nextSpeakerphoneEnabled = !audioOutputState.isSpeakerphoneEnabled
        callService.setSpeakerphoneEnabledAsync(
            enabled = nextSpeakerphoneEnabled,
            onFailure = {
                scope.launch {
                    if (!isEnding && !isClosed) {
                        audioOutputState = callService.currentAudioOutputState()
                        renderConnected(statusOverride = "Could not change audio output")
                    }
                }
            }
        )
    }

    fun endCall() {
        if (isClosed || isEnding) {
            return
        }
        isEnding = true
        _viewState.value = viewState.value.copy(
            statusText = "Ending",
            isBusy = true,
            canToggleMicrophone = false,
            canToggleSpeakerphone = false,
            canToggleCamera = false,
            canSwitchCamera = false,
            canEnd = false,
            isEnding = true
        )
        callService.leaveActiveCallAsync(
            onFailure = {
                scope.launch {
                    if (!isClosed && callService.state.value != NativeMatrixRtcCallServiceState.IDLE) {
                        showFailure("Could not end call")
                    }
                }
            }
        )
    }

    override fun close() {
        isClosed = true
        serviceStateJob?.cancel()
        serviceStateJob = null
        microphoneStateJob?.cancel()
        microphoneStateJob = null
        cameraStateJob?.cancel()
        cameraStateJob = null
        localCameraFacingFrontJob?.cancel()
        localCameraFacingFrontJob = null
        audioOutputStateJob?.cancel()
        audioOutputStateJob = null
        remoteParticipantCountJob?.cancel()
        remoteParticipantCountJob = null
        participantsJob?.cancel()
        participantsJob = null
        pickupStateJob?.cancel()
        pickupStateJob = null
        dismissJob?.cancel()
        dismissJob = null
    }

    private fun handleServiceState(serviceState: NativeMatrixRtcCallServiceState) {
        if (isClosed) {
            return
        }
        if (terminalStatus != null) {
            return
        }
        when (serviceState) {
            NativeMatrixRtcCallServiceState.IDLE -> {
                when {
                    isEnding -> onDismiss()
                    wasConnected -> showEndedAndDismiss()
                    !startCallOnStart -> onDismiss()
                    hasObservedRelevantCall -> {
                        val error = callService.currentFailure()
                        showFailure(error?.callStartMessage() ?: "Could not start call")
                    }
                }
            }
            NativeMatrixRtcCallServiceState.JOINING -> {
                if (callService.currentRoomId() == launchContext.roomId) {
                    hasObservedRelevantCall = true
                    _viewState.value = viewState.value.copy(
                        statusText = "Connecting",
                        isBusy = true,
                        canToggleMicrophone = false,
                        canToggleSpeakerphone = false,
                        canToggleCamera = false,
                        canSwitchCamera = false,
                        canEnd = true,
                        isEnding = false,
                        isFailed = false
                    )
                }
            }
            NativeMatrixRtcCallServiceState.CONNECTED -> {
                if (callService.currentRoomId() == launchContext.roomId) {
                    hasObservedRelevantCall = true
                    wasConnected = true
                    isMuted = !callService.currentMicrophoneEnabled()
                    isCameraEnabled = callService.currentCameraEnabled()
                    isLocalCameraFacingFront = callService.currentLocalCameraFacingFront()
                    audioOutputState = callService.currentAudioOutputState()
                    remoteParticipantCount = callService.currentRemoteParticipantCount()
                    participantsSnapshot = callService.currentParticipantsSnapshot()
                    if (remoteParticipantCount > 0) {
                        hasObservedRemoteParticipant = true
                    }
                    renderConnected()
                }
            }
            NativeMatrixRtcCallServiceState.LEAVING -> {
                if (callService.currentRoomId() == launchContext.roomId || wasConnected || isEnding) {
                    hasObservedRelevantCall = true
                    val isLocalEnding = isEnding
                    _viewState.value = viewState.value.copy(
                        statusText = if (isLocalEnding) "Ending" else "Leaving",
                        isBusy = true,
                        canToggleMicrophone = false,
                        canToggleSpeakerphone = false,
                        canToggleCamera = false,
                        canSwitchCamera = false,
                        canEnd = false,
                        isEnding = isLocalEnding,
                        isFailed = false
                    )
                }
            }
        }
    }

    private fun renderConnected(statusOverride: String? = null) {
        val audioOutputLabel = audioOutputState.selectedDeviceLabel
        val currentPickupState = callService.currentPickupState()
        pickupState = currentPickupState
        val localVideoTrack = participantsSnapshot.localVideoTrack
        val remoteVideoTrack = participantsSnapshot.primaryRemoteVideoTrack
        val primaryVideoTrack = remoteVideoTrack ?: localVideoTrack
        val isPrimaryVideoLocal = remoteVideoTrack == null && localVideoTrack != null
        val previewVideoTrack = if (remoteVideoTrack != null) localVideoTrack else null
        _viewState.value = viewState.value.copy(
            statusText = statusOverride ?: when {
                terminalStatus != null -> terminalStatus.orEmpty()
                currentPickupState is NativeMatrixRtcCallPickupState.Ringing &&
                    isPickupStateRelevant(currentPickupState.roomId) -> "Ringing"
                remoteParticipantCount <= 0 && !hasObservedRemoteParticipant ->
                    if (startCallOnStart) "Calling" else "Connecting"
                isMuted -> "Microphone muted"
                audioOutputLabel != null -> "Connected on $audioOutputLabel"
                else -> "Connected"
            },
            isMuted = isMuted,
            isSpeakerphoneEnabled = audioOutputState.isSpeakerphoneEnabled,
            canToggleSpeakerphone = audioOutputState.canToggleSpeakerphone,
            audioOutputLabel = audioOutputLabel,
            isBusy = false,
            canToggleMicrophone = true,
            isCameraEnabled = isCameraEnabled,
            canToggleCamera = true,
            canSwitchCamera = isCameraEnabled,
            canEnd = true,
            isEnding = false,
            isFailed = false,
            primaryVideoTrack = primaryVideoTrack,
            shouldMirrorPrimaryVideo = isPrimaryVideoLocal && isLocalCameraFacingFront,
            previewVideoTrack = previewVideoTrack,
            shouldMirrorPreviewVideo = previewVideoTrack != null && isLocalCameraFacingFront
        )
    }

    private fun handlePickupState(pickupState: NativeMatrixRtcCallPickupState) {
        if (isClosed) {
            return
        }
        when (pickupState) {
            NativeMatrixRtcCallPickupState.Inactive -> Unit
            is NativeMatrixRtcCallPickupState.Ringing -> {
                if (isPickupStateRelevant(pickupState.roomId) && terminalStatus == null && !isEnding) {
                    _viewState.value = viewState.value.copy(
                        statusText = "Ringing",
                        isBusy = false,
                        canToggleMicrophone = true,
                        canToggleCamera = true,
                        canSwitchCamera = isCameraEnabled,
                        canEnd = true,
                        isEnding = false,
                        isFailed = false
                    )
                }
            }
            is NativeMatrixRtcCallPickupState.Answered -> {
                if (isPickupStateRelevant(pickupState.roomId) && terminalStatus == null && !isEnding) {
                    hasObservedRemoteParticipant = true
                    renderConnected()
                }
            }
            is NativeMatrixRtcCallPickupState.Declined -> {
                if (isPickupStateRelevant(pickupState.roomId)) {
                    showTerminalStatus("Declined", delayMillis = 1_200)
                }
            }
            is NativeMatrixRtcCallPickupState.TimedOut -> {
                if (isPickupStateRelevant(pickupState.roomId)) {
                    showTerminalStatus("No Answer", delayMillis = 1_200)
                }
            }
        }
    }

    private fun isPickupStateRelevant(roomId: String): Boolean {
        return roomId == launchContext.roomId &&
            (
                callService.currentRoomId() == roomId ||
                    hasObservedRelevantCall ||
                    wasConnected
                )
    }

    private fun showEndedAndDismiss() {
        _viewState.value = viewState.value.copy(
            statusText = "Call ended",
            isBusy = false,
            canToggleMicrophone = false,
            canToggleSpeakerphone = false,
            canToggleCamera = false,
            canSwitchCamera = false,
            canEnd = false,
            isEnding = false,
            isFailed = false,
            primaryVideoTrack = null,
            shouldMirrorPrimaryVideo = false,
            previewVideoTrack = null,
            shouldMirrorPreviewVideo = false
        )
        scheduleDismiss(delayMillis = 900)
    }

    private fun showFailure(message: String) {
        if (viewState.value.isFailed && viewState.value.statusText == message) {
            return
        }
        _viewState.value = viewState.value.copy(
            statusText = message,
            isBusy = false,
            canToggleMicrophone = false,
            canToggleSpeakerphone = false,
            canToggleCamera = false,
            canSwitchCamera = false,
            canEnd = true,
            isEnding = false,
            isFailed = true,
            primaryVideoTrack = null,
            shouldMirrorPrimaryVideo = false,
            previewVideoTrack = null,
            shouldMirrorPreviewVideo = false
        )
        scheduleDismiss(delayMillis = 1_800)
    }

    private fun showTerminalStatus(message: String, delayMillis: Long) {
        if (terminalStatus == message) {
            return
        }
        terminalStatus = message
        _viewState.value = viewState.value.copy(
            statusText = message,
            isBusy = false,
            canToggleMicrophone = false,
            canToggleSpeakerphone = false,
            canToggleCamera = false,
            canSwitchCamera = false,
            canEnd = false,
            isEnding = false,
            isFailed = false,
            primaryVideoTrack = null,
            shouldMirrorPrimaryVideo = false,
            previewVideoTrack = null,
            shouldMirrorPreviewVideo = false
        )
        scheduleDismiss(delayMillis = delayMillis)
    }

    private fun scheduleDismiss(delayMillis: Long) {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(delayMillis)
            if (!isClosed) {
                onDismiss()
            }
        }
    }

    private fun Throwable.callStartMessage(): String {
        return when (this) {
            is NativeMatrixRtcCallServiceException.AlreadyActive -> "Another call is active"
            NativeMatrixRtcCallServiceException.MissingLiveKitTransport -> "Call server is unavailable"
            else -> "Could not start call"
        }
    }
}

internal class NativeMatrixRtcCallView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var topInset = 0
    private var bottomInset = 0

    private val primaryVideoView = NativeMatrixRtcLiveKitVideoView(context)
    private val previewVideoView = NativeMatrixRtcLiveKitVideoView(context).apply {
        elevation = dp(10).toFloat()
        background = roundedRect(
            color = Color.BLACK,
            strokeColor = Color.argb(96, 255, 255, 255)
        )
    }
    private val videoTopOverlay = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = roundedRect(
            color = Color.argb(112, 0, 0, 0),
            strokeColor = Color.argb(28, 255, 255, 255)
        )
        visibility = View.GONE
    }
    private val videoRoomNameText = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
    }
    private val videoStatusText = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 13f
        setTextColor(Color.argb(210, 255, 255, 255))
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), 0, dp(24), 0)
    }
    private val avatarText = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = oval(color = Color.rgb(36, 85, 78))
    }
    private val roomNameText = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
    }
    private val statusText = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        textSize = 15f
        setTextColor(Color.argb(210, 255, 255, 255))
    }
    private val controlsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val microphoneButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }
    private val speakerButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }
    private val cameraButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }
    private val switchCameraButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }
    private val endButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.rgb(13, 17, 20))

        addView(
            primaryVideoView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            content,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            videoTopOverlay,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        )
        videoTopOverlay.addView(
            videoRoomNameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        videoTopOverlay.addView(
            videoStatusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
        )
        addView(
            previewVideoView,
            LayoutParams(
                dp(116),
                dp(154),
                Gravity.TOP or Gravity.RIGHT
            ).apply {
                rightMargin = dp(18)
            }
        )
        content.addView(
            Space(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        content.addView(
            avatarText,
            LinearLayout.LayoutParams(dp(116), dp(116))
        )
        content.addView(
            roomNameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(28)
            }
        )
        content.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
        content.addView(
            Space(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        content.addView(
            controlsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
            ).apply {
                bottomMargin = dp(12)
            }
        )
        controlsRow.addView(
            microphoneButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                rightMargin = dp(6)
            }
        )
        controlsRow.addView(
            speakerButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                rightMargin = dp(6)
            }
        )
        controlsRow.addView(
            cameraButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                rightMargin = dp(6)
            }
        )
        controlsRow.addView(
            switchCameraButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                rightMargin = dp(6)
            }
        )
        controlsRow.addView(
            endButton,
            LinearLayout.LayoutParams(0, dp(56), 1f)
        )

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = systemInsets.top
            bottomInset = systemInsets.bottom
            updateContentInsets()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    fun render(state: NativeMatrixRtcCallViewState, actions: NativeMatrixRtcCallViewActions) {
        val hasPrimaryVideo = state.primaryVideoTrack != null
        primaryVideoView.setVideoTrack(
            track = state.primaryVideoTrack,
            mirror = state.shouldMirrorPrimaryVideo
        )
        previewVideoView.setVideoTrack(
            track = state.previewVideoTrack,
            mirror = state.shouldMirrorPreviewVideo
        )
        videoTopOverlay.visibility = if (hasPrimaryVideo) View.VISIBLE else View.GONE
        videoRoomNameText.text = state.roomName
        videoStatusText.text = state.statusText
        avatarText.visibility = if (hasPrimaryVideo) View.GONE else View.VISIBLE
        roomNameText.visibility = if (hasPrimaryVideo) View.GONE else View.VISIBLE
        statusText.visibility = if (hasPrimaryVideo) View.GONE else View.VISIBLE

        roomNameText.text = state.roomName
        avatarText.text = state.roomName.avatarInitial()
        statusText.text = state.statusText

        microphoneButton.text = if (state.isMuted) "Unmute" else "Mute"
        microphoneButton.isEnabled = state.canToggleMicrophone && !state.isBusy
        microphoneButton.alpha = if (microphoneButton.isEnabled) 1f else 0.42f
        microphoneButton.background = roundedRect(
            color = if (state.isMuted) Color.rgb(58, 76, 82) else Color.rgb(45, 93, 82),
            strokeColor = Color.argb(48, 255, 255, 255)
        )
        microphoneButton.setOnClickListener { actions.onToggleMicrophone() }

        speakerButton.text = if (state.isSpeakerphoneEnabled) "Phone" else "Speaker"
        speakerButton.isEnabled = state.canToggleSpeakerphone && !state.isBusy
        speakerButton.alpha = if (speakerButton.isEnabled) 1f else 0.42f
        speakerButton.background = roundedRect(
            color = if (state.isSpeakerphoneEnabled) Color.rgb(45, 93, 82) else Color.rgb(58, 76, 82),
            strokeColor = Color.argb(48, 255, 255, 255)
        )
        speakerButton.setOnClickListener { actions.onToggleSpeakerphone() }

        cameraButton.text = if (state.isCameraEnabled) "Stop" else "Video"
        cameraButton.isEnabled = state.canToggleCamera && !state.isBusy
        cameraButton.alpha = if (cameraButton.isEnabled) 1f else 0.42f
        cameraButton.background = roundedRect(
            color = if (state.isCameraEnabled) Color.rgb(45, 93, 82) else Color.rgb(58, 76, 82),
            strokeColor = Color.argb(48, 255, 255, 255)
        )
        cameraButton.setOnClickListener { actions.onToggleCamera() }

        switchCameraButton.text = "Flip"
        switchCameraButton.isEnabled = state.canSwitchCamera && !state.isBusy
        switchCameraButton.alpha = if (switchCameraButton.isEnabled) 1f else 0.42f
        switchCameraButton.background = roundedRect(
            color = Color.rgb(58, 76, 82),
            strokeColor = Color.argb(48, 255, 255, 255)
        )
        switchCameraButton.setOnClickListener { actions.onSwitchCamera() }

        endButton.text = when {
            state.isFailed -> "Close"
            state.isEnding -> "Ending"
            else -> "End"
        }
        endButton.isEnabled = state.canEnd
        endButton.alpha = if (state.canEnd) 1f else 0.54f
        endButton.background = roundedRect(
            color = Color.rgb(192, 43, 43),
            strokeColor = Color.argb(58, 255, 255, 255)
        )
        endButton.setOnClickListener { actions.onEndCall() }
    }

    private fun updateContentInsets() {
        content.updatePadding(
            top = topInset + dp(24),
            bottom = bottomInset + dp(24)
        )
        (videoTopOverlay.layoutParams as? LayoutParams)?.let { params ->
            params.topMargin = topInset + dp(12)
            videoTopOverlay.layoutParams = params
        }
        (previewVideoView.layoutParams as? LayoutParams)?.let { params ->
            params.topMargin = topInset + dp(92)
            previewVideoView.layoutParams = params
        }
    }

    private fun roundedRect(color: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(color)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun oval(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.argb(40, 255, 255, 255))
        }
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }
}

private fun String.avatarInitial(): String {
    val firstLetter = trim().firstOrNull { it.isLetterOrDigit() }
    return firstLetter?.uppercaseChar()?.toString() ?: "Z"
}
