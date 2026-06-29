package com.zyna.app.ui.calls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyna.app.R
import com.zyna.app.ZynaApplication
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCall
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCallIntents
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MatrixRtcIncomingCallActivity : AppCompatActivity() {
    private var call: MatrixRtcIncomingCall? = null
    private var pendingAnswerCall: MatrixRtcIncomingCall? = null
    private var callController: NativeMatrixRtcCallController? = null
    private var answerJob: Job? = null
    private var callRenderJob: Job? = null
    private val appContainer by lazy { (application as ZynaApplication).appContainer }
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val call = pendingAnswerCall
        pendingAnswerCall = null
        if (granted && call != null) {
            startAnsweredCall(call)
        } else {
            finish()
        }
    }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            callController?.toggleCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenPresentation()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (answerJob?.isActive == true || callController != null) {
            return
        }
        val nextCall = intent?.let(MatrixRtcIncomingCallIntents::callFrom)
        if (nextCall == null || nextCall.isExpired()) {
            finish()
            return
        }
        call = nextCall
        if (intent.action == MatrixRtcIncomingCallIntents.ACTION_ANSWER) {
            answer(nextCall)
            return
        }
        render(nextCall)
    }

    private fun render(call: MatrixRtcIncomingCall) {
        val title = call.roomName?.takeIf { it.isNotBlank() } ?: call.senderName
        val subtitle = getString(R.string.incoming_call_audio_body)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(48), dp(28), dp(48))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(0xFFB8BDC7.toInt())
                textSize = 17f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.incoming_call_decline)
                setOnClickListener { decline(call) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.incoming_call_answer)
                setOnClickListener { answer(call) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        )
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(44)
            }
        )
        setContentView(root)
    }

    private fun answer(call: MatrixRtcIncomingCall) {
        if (answerJob?.isActive == true || callController != null) {
            return
        }

        appContainer.incomingCallManager.answerIncomingCall(call)
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startAnsweredCall(call)
        } else {
            pendingAnswerCall = call
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAnsweredCall(call: MatrixRtcIncomingCall) {
        renderStatus(call, "Connecting")
        answerJob?.cancel()
        answerJob = lifecycleScope.launch {
            val restored = withTimeoutOrNull(SESSION_RESTORE_TIMEOUT_MS) {
                appContainer.matrixClientService.ensureSessionRestored()
            } == true
            if (!restored) {
                Log.w(TAG, "Could not start incoming call: Matrix session is not ready")
                renderStatus(call, "Could not start call")
                delay(CALL_FAILURE_DISMISS_DELAY_MS)
                finish()
                return@launch
            }

            presentCall(call)
        }
    }

    private fun presentCall(call: MatrixRtcIncomingCall) {
        if (callController != null) {
            return
        }
        val activeRoomId = appContainer.nativeMatrixRtcCallService.currentRoomId()
        if (activeRoomId != null && activeRoomId != call.roomId) {
            finish()
            return
        }

        val launchContext = NativeMatrixRtcCallLaunchContext(
            roomId = call.roomId,
            roomName = call.roomName ?: call.senderName
        )
        val view = NativeMatrixRtcCallView(this)
        val controller = NativeMatrixRtcCallController(
            launchContext = launchContext,
            callService = appContainer.nativeMatrixRtcCallService,
            scope = lifecycleScope,
            startCallOnStart = activeRoomId == null,
            waitForPickupOnStart = false,
            onDismiss = ::finish
        )
        val actions = NativeMatrixRtcCallViewActions(
            onToggleMicrophone = controller::toggleMicrophone,
            onToggleSpeakerphone = controller::toggleSpeakerphone,
            onToggleCamera = ::toggleCameraWithPermission,
            onSwitchCamera = controller::switchCamera,
            onEndCall = controller::endCall
        )
        callController = controller
        setContentView(view)
        view.render(controller.viewState.value, actions)
        callRenderJob?.cancel()
        callRenderJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.viewState.collect { state ->
                    view.render(state, actions)
                }
            }
        }
        controller.start()
        controller.restoreServiceState()
    }

    private fun renderStatus(call: MatrixRtcIncomingCall, status: String) {
        val title = call.roomName?.takeIf { it.isNotBlank() } ?: call.senderName
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(48), dp(28), dp(48))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            TextView(this).apply {
                text = status
                setTextColor(0xFFB8BDC7.toInt())
                textSize = 17f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )
        setContentView(root)
    }

    private fun toggleCameraWithPermission() {
        val controller = callController ?: return
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

    private fun decline(call: MatrixRtcIncomingCall) {
        appContainer.incomingCallManager.declineIncomingCallAsync(call)
        finish()
    }

    private fun configureLockScreenPresentation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        answerJob?.cancel()
        answerJob = null
        callRenderJob?.cancel()
        callRenderJob = null
        callController?.close()
        callController = null
        pendingAnswerCall = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MatrixRtcIncomingCall"
        private const val SESSION_RESTORE_TIMEOUT_MS = 15_000L
        private const val CALL_FAILURE_DISMISS_DELAY_MS = 1_500L
    }
}
