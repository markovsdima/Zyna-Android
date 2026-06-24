package com.zyna.app.ui.calls

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallService
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallServiceException
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
    val isBusy: Boolean,
    val canToggleMicrophone: Boolean,
    val canEnd: Boolean,
    val isEnding: Boolean,
    val isFailed: Boolean
)

data class NativeMatrixRtcCallViewActions(
    val onToggleMicrophone: () -> Unit,
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
            isBusy = true,
            canToggleMicrophone = false,
            canEnd = true,
            isEnding = false,
            isFailed = false
        )
    )
    val viewState: StateFlow<NativeMatrixRtcCallViewState> = _viewState.asStateFlow()

    private var serviceStateJob: Job? = null
    private var microphoneStateJob: Job? = null
    private var dismissJob: Job? = null
    private var hasStarted = false
    private var hasObservedRelevantCall = !startCallOnStart
    private var wasConnected = false
    private var isMuted = false
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

        if (startCallOnStart) {
            callService.startAudioCallAsync(
                roomId = launchContext.roomId,
                waitForPickup = false,
                onFailure = { error ->
                    if (!isEnding && !isClosed && !hasObservedRelevantCall) {
                        showFailure(error.callStartMessage())
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
                if (!isEnding && !isClosed) {
                    isMuted = !nextMuted
                    renderConnected(statusOverride = "Could not change microphone")
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
            canEnd = false,
            isEnding = true
        )
        callService.leaveActiveCallAsync(
            onFailure = {
                if (!isClosed && callService.state.value != NativeMatrixRtcCallServiceState.IDLE) {
                    showFailure("Could not end call")
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
        dismissJob?.cancel()
        dismissJob = null
    }

    private fun handleServiceState(serviceState: NativeMatrixRtcCallServiceState) {
        if (isClosed) {
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
                    renderConnected()
                }
            }
            NativeMatrixRtcCallServiceState.LEAVING -> {
                if (callService.currentRoomId() == launchContext.roomId || wasConnected || isEnding) {
                    hasObservedRelevantCall = true
                    isEnding = true
                    _viewState.value = viewState.value.copy(
                        statusText = "Ending",
                        isBusy = true,
                        canToggleMicrophone = false,
                        canEnd = false,
                        isEnding = true
                    )
                }
            }
        }
    }

    private fun renderConnected(statusOverride: String? = null) {
        _viewState.value = viewState.value.copy(
            statusText = statusOverride ?: if (isMuted) "Microphone muted" else "Connected",
            isMuted = isMuted,
            isBusy = false,
            canToggleMicrophone = true,
            canEnd = true,
            isEnding = false,
            isFailed = false
        )
    }

    private fun showEndedAndDismiss() {
        _viewState.value = viewState.value.copy(
            statusText = "Call ended",
            isBusy = false,
            canToggleMicrophone = false,
            canEnd = false,
            isEnding = false,
            isFailed = false
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
            canEnd = true,
            isEnding = false,
            isFailed = true
        )
        scheduleDismiss(delayMillis = 1_800)
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
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }
    private val endButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
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
            content,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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
            LinearLayout.LayoutParams(dp(132), dp(56)).apply {
                rightMargin = dp(14)
            }
        )
        controlsRow.addView(
            endButton,
            LinearLayout.LayoutParams(dp(132), dp(56))
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
