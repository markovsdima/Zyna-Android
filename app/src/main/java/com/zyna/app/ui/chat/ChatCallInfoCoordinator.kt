package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixRoomCallInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ChatCallInfoTarget(
    val userId: String,
    val roomId: String
)

data class ChatCallInfoState(
    val roomId: String? = null,
    val banner: ChatCallBannerState? = null
)

/**
 * Owns the room-scoped banner state and observation lifecycle for chat call info.
 * Pausing preserves the last banner while clearing drops both the target and state.
 *
 * All methods are main-thread confined. The observer session itself is
 * supplied separately so its flow reconciliation can be tested independently.
 */
internal class ChatCallInfoCoordinator(
    private val scope: CoroutineScope,
    private val observeTarget: suspend (
        ChatCallInfoTarget,
        (MatrixRoomCallInfo) -> Unit,
        (Throwable) -> Unit
    ) -> Unit,
    private val projectBanner: (
        ChatCallInfoTarget,
        MatrixRoomCallInfo
    ) -> ChatCallBannerState?,
    private val onObservationError: (ChatCallInfoTarget, Throwable) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(ChatCallInfoState())
    val state: StateFlow<ChatCallInfoState> = _state.asStateFlow()

    private var observerJob: Job? = null
    private var activeObservationToken: Any? = null
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

        val didChangeTarget = this.target != target
        pause()
        this.target = target
        if (didChangeTarget || _state.value.roomId != target.roomId) {
            _state.value = ChatCallInfoState(roomId = target.roomId)
        }
        onLog("chatCallInfoObserver target roomId=${target.roomId}")
        startTargetIfNeeded()
    }

    @MainThread
    fun pause() {
        if (observerJob != null) {
            onLog("chatCallInfoObserver pause")
        }
        activeObservationToken = null
        observerJob?.cancel()
        observerJob = null
    }

    @MainThread
    fun clear() {
        pause()
        target = null
        _state.value = ChatCallInfoState()
    }

    @MainThread
    private fun startTargetIfNeeded() {
        if (!isEnabled || observerJob?.isActive == true) {
            return
        }
        val currentTarget = target ?: return
        val observationToken = Any()
        activeObservationToken = observationToken
        onLog("chatCallInfoObserver start roomId=${currentTarget.roomId}")
        observerJob = scope.launch {
            try {
                observeTarget(
                    currentTarget,
                    { callInfo -> applyCallInfo(observationToken, currentTarget, callInfo) },
                    { error -> failObservation(observationToken, currentTarget, error) }
                )
            } finally {
                if (activeObservationToken === observationToken) {
                    activeObservationToken = null
                }
            }
        }
    }

    private fun applyCallInfo(
        observationToken: Any,
        observedTarget: ChatCallInfoTarget,
        callInfo: MatrixRoomCallInfo
    ) {
        if (activeObservationToken !== observationToken || target != observedTarget) {
            return
        }
        _state.value = ChatCallInfoState(
            roomId = observedTarget.roomId,
            banner = projectBanner(observedTarget, callInfo)
        )
    }

    private fun failObservation(
        observationToken: Any,
        observedTarget: ChatCallInfoTarget,
        error: Throwable
    ) {
        onObservationError(observedTarget, error)
        if (activeObservationToken === observationToken && target == observedTarget) {
            _state.value = _state.value.copy(banner = null)
        }
    }
}
