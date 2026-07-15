package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ChatCallInfoTarget(
    val userId: String,
    val roomId: String
)

/**
 * Owns the enabled/target/job lifecycle for chat call info observation.
 *
 * All methods are main-thread confined. The observer session itself is
 * supplied separately so its flow reconciliation can be tested independently.
 */
internal class ChatCallInfoCoordinator(
    private val scope: CoroutineScope,
    private val observeTarget: suspend (ChatCallInfoTarget) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private var observerJob: Job? = null
    private var target: ChatCallInfoTarget? = null
    private var isEnabled: Boolean = false

    @MainThread
    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) {
            return
        }
        onLog("chatCallInfoObserver enabled=$enabled")
        isEnabled = enabled
        if (enabled) {
            startTargetIfNeeded()
        } else {
            pause()
        }
    }

    @MainThread
    fun activate(target: ChatCallInfoTarget) {
        if (this.target == target && observerJob?.isActive == true) {
            return
        }

        pause()
        this.target = target
        onLog("chatCallInfoObserver target roomId=${target.roomId}")
        startTargetIfNeeded()
    }

    @MainThread
    fun pause() {
        if (observerJob != null) {
            onLog("chatCallInfoObserver pause")
        }
        observerJob?.cancel()
        observerJob = null
    }

    @MainThread
    fun clear() {
        pause()
        target = null
    }

    @MainThread
    private fun startTargetIfNeeded() {
        if (!isEnabled || observerJob?.isActive == true) {
            return
        }
        val currentTarget = target ?: return
        onLog("chatCallInfoObserver start roomId=${currentTarget.roomId}")
        observerJob = scope.launch {
            observeTarget(currentTarget)
        }
    }
}
