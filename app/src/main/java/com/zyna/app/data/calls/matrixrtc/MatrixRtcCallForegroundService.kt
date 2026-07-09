package com.zyna.app.data.calls.matrixrtc

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyna.app.MainActivity
import com.zyna.app.R
import com.zyna.app.ZynaApplication
import com.zyna.app.data.push.ZynaNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MatrixRtcCallForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var callService: NativeMatrixRtcCallService
    private var callStateJob: Job? = null
    private var cameraStateJob: Job? = null
    private var pendingStopJob: Job? = null
    private var roomName: String? = null
    private var currentState = NativeMatrixRtcCallServiceState.IDLE
    private var cameraEnabled = false
    private var hasObservedCall = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        callService = (application as ZynaApplication).appContainer.nativeMatrixRtcCallService
        currentState = callService.state.value
        cameraEnabled = callService.currentCameraEnabled()
        hasObservedCall = currentState != NativeMatrixRtcCallServiceState.IDLE
        ZynaNotificationChannels.ensureCreated(this)
        startObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        roomName = intent?.getStringExtra(EXTRA_ROOM_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: roomName

        startOrUpdateForeground()

        if (intent?.action == ACTION_END_CALL) {
            callService.leaveActiveCallAsync()
        }

        if (currentState == NativeMatrixRtcCallServiceState.IDLE && !hasObservedCall) {
            scheduleStop(START_WITHOUT_CALL_TIMEOUT_MS)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pendingStopJob?.cancel()
        callStateJob?.cancel()
        cameraStateJob?.cancel()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startObservers() {
        if (callStateJob == null) {
            callStateJob = serviceScope.launch {
                callService.state.collect { state ->
                    currentState = state
                    when (state) {
                        NativeMatrixRtcCallServiceState.JOINING,
                        NativeMatrixRtcCallServiceState.CONNECTED,
                        NativeMatrixRtcCallServiceState.LEAVING -> {
                            hasObservedCall = true
                            pendingStopJob?.cancel()
                            pendingStopJob = null
                            if (foregroundStarted) {
                                startOrUpdateForeground()
                            }
                        }
                        NativeMatrixRtcCallServiceState.IDLE -> {
                            val delayMillis = if (hasObservedCall) {
                                STOP_AFTER_CALL_ENDED_DELAY_MS
                            } else {
                                START_WITHOUT_CALL_TIMEOUT_MS
                            }
                            scheduleStop(delayMillis)
                        }
                    }
                }
            }
        }
        if (cameraStateJob == null) {
            cameraStateJob = serviceScope.launch {
                callService.cameraEnabled.collect { enabled ->
                    if (cameraEnabled == enabled) {
                        return@collect
                    }
                    cameraEnabled = enabled
                    if (foregroundStarted) {
                        startOrUpdateForeground()
                    }
                }
            }
        }
    }

    private fun startOrUpdateForeground() {
        val notification = callNotification()
        try {
            startForegroundCompat(notification, foregroundServiceType())
            foregroundStarted = true
        } catch (error: RuntimeException) {
            if (cameraEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.w(
                    TAG,
                    "Could not update MatrixRTC foreground service with camera type",
                    error
                )
                retryMicrophoneOnlyForeground(notification)
                return
            }
            Log.w(TAG, "Could not start MatrixRTC foreground service", error)
            stopSelf()
        }
    }

    private fun retryMicrophoneOnlyForeground(notification: Notification) {
        try {
            startForegroundCompat(
                notification = notification,
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            foregroundStarted = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start MatrixRTC foreground service", error)
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification, serviceType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cameraEnabled) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return type
    }

    private fun callNotification(): Notification {
        val title = roomName ?: getString(R.string.ongoing_call_title)
        val body = when (currentState) {
            NativeMatrixRtcCallServiceState.JOINING ->
                getString(R.string.ongoing_call_connecting)
            NativeMatrixRtcCallServiceState.CONNECTED ->
                getString(R.string.ongoing_call_active)
            NativeMatrixRtcCallServiceState.LEAVING ->
                getString(R.string.ongoing_call_ending)
            NativeMatrixRtcCallServiceState.IDLE ->
                getString(R.string.ongoing_call_connecting)
        }
        return NotificationCompat.Builder(this, ZynaNotificationChannels.ONGOING_CALLS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_calls_24)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openCallIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_call_end_24,
                getString(R.string.ongoing_call_end),
                endCallIntent()
            )
            .build()
    }

    private fun openCallIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_CALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun endCallIntent(): PendingIntent {
        val intent = Intent(this, MatrixRtcCallForegroundService::class.java).apply {
            action = ACTION_END_CALL
        }
        return PendingIntent.getService(
            this,
            REQUEST_CODE_END_CALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleStop(delayMillis: Long) {
        pendingStopJob?.cancel()
        pendingStopJob = serviceScope.launch {
            delay(delayMillis)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "MatrixRtcCallFg"
        private const val ACTION_END_CALL = "com.zyna.app.action.END_MATRIX_RTC_CALL_FROM_FOREGROUND"
        private const val EXTRA_ROOM_NAME = "com.zyna.app.extra.MATRIX_RTC_CALL_ROOM_NAME"
        private const val NOTIFICATION_ID = 3917
        private const val REQUEST_CODE_OPEN_CALL = 3918
        private const val REQUEST_CODE_END_CALL = 3919
        private const val START_WITHOUT_CALL_TIMEOUT_MS = 15_000L
        private const val STOP_AFTER_CALL_ENDED_DELAY_MS = 1_500L

        fun start(context: Context, roomName: String?) {
            val intent = Intent(context, MatrixRtcCallForegroundService::class.java).apply {
                putExtra(EXTRA_ROOM_NAME, roomName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not request MatrixRTC foreground service start", error)
            }
        }
    }
}
