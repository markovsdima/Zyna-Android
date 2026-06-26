package com.zyna.app.data.calls.matrixrtc

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.push.ZynaPushNotificationRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MatrixRtcIncomingCallManager(
    context: Context,
    private val matrixClientService: MatrixClientService,
    private val nativeMatrixRtcCallService: NativeMatrixRtcCallService
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = ZynaPushNotificationRenderer(appContext)
    private val mutex = Mutex()
    private var activeCall: MatrixRtcIncomingCall? = null
    private var timeoutJob: Job? = null
    private val wakeLock: PowerManager.WakeLock? = appContext.getSystemService<PowerManager>()
        ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${appContext.packageName}:IncomingCall")

    suspend fun showIncomingCall(call: MatrixRtcIncomingCall): Boolean {
        return mutex.withLock {
            val nowMillis = System.currentTimeMillis()
            if (call.isExpired(nowMillis)) {
                Log.d(TAG, "Incoming call ignored: expired event_id=${call.eventId}")
                return@withLock false
            }
            if (!call.isAudioCall) {
                Log.d(TAG, "Incoming call ignored: non-audio event_id=${call.eventId}")
                return@withLock false
            }
            if (nativeMatrixRtcCallService.currentRoomId() != null) {
                Log.d(TAG, "Incoming call ignored: another call is active")
                return@withLock false
            }

            activeCall?.let { previous ->
                renderer.cancelIncomingCallNotification(previous)
            }
            activeCall = null
            timeoutJob?.cancel()
            timeoutJob = null
            releaseWakeLock()
            val ringingCall = call.copy(
                expiresAtMillis = minOf(call.expiresAtMillis, nowMillis + MAX_RINGING_MILLIS)
            )
            val displayed = renderer.showIncomingCallNotification(ringingCall)
            if (!displayed) {
                return@withLock false
            }
            activeCall = ringingCall
            scheduleTimeoutLocked(ringingCall)
            acquireWakeLock(ringingCall)
            true
        }
    }

    fun answerIncomingCall(call: MatrixRtcIncomingCall) {
        scope.launch {
            clearIncomingCall(call)
        }
    }

    fun declineIncomingCallAsync(
        call: MatrixRtcIncomingCall,
        onComplete: (() -> Unit)? = null
    ) {
        scope.launch {
            try {
                declineIncomingCall(call)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private suspend fun declineIncomingCall(call: MatrixRtcIncomingCall) {
        clearIncomingCall(call)
        try {
            matrixClientService.declineMatrixRtcCall(
                roomId = call.roomId,
                notificationEventId = call.eventId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to decline incoming MatrixRTC call", error)
        }
    }

    private suspend fun clearIncomingCall(call: MatrixRtcIncomingCall) {
        mutex.withLock {
            if (activeCall?.eventId == call.eventId) {
                activeCall = null
                timeoutJob?.cancel()
                timeoutJob = null
                releaseWakeLock()
            }
            renderer.cancelIncomingCallNotification(call)
        }
    }

    private fun scheduleTimeoutLocked(call: MatrixRtcIncomingCall) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay((call.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L))
            mutex.withLock {
                if (activeCall?.eventId == call.eventId) {
                    Log.d(TAG, "Incoming call timed out event_id=${call.eventId}")
                    activeCall = null
                    renderer.cancelIncomingCallNotification(call)
                    releaseWakeLock()
                }
            }
        }
    }

    private fun acquireWakeLock(call: MatrixRtcIncomingCall) {
        val durationMillis = (call.expiresAtMillis - System.currentTimeMillis())
            .coerceIn(1L, MAX_RINGING_MILLIS)
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            lock.acquire(durationMillis)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
    }

    companion object {
        private const val TAG = "MatrixRtcIncomingCall"
        private const val MAX_RINGING_MILLIS = 45_000L
    }
}
