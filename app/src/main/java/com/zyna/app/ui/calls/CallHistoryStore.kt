package com.zyna.app.ui.calls

import androidx.annotation.MainThread
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.local.LocalCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CallHistoryState(
    val calls: List<MatrixRtcCallHistoryItem> = emptyList()
)

internal class CallHistoryDriver(
    val observeCalls: (
        userId: String,
        limit: Int
    ) -> Flow<List<MatrixRtcCallHistoryItem>>,
    val refreshCalls: suspend (userId: String, limit: Int) -> Unit,
    val nowMillis: () -> Long = System::currentTimeMillis,
    val delayMillis: suspend (Long) -> Unit = { delay(it) }
)

/**
 * Owns call-history state and all work associated with one Matrix session.
 *
 * Public methods and driver callbacks are main-thread confined.
 */
internal class CallHistoryStore(
    private val scope: CoroutineScope,
    private val driver: CallHistoryDriver,
    private val historyLimit: Int = DEFAULT_CALL_HISTORY_LIMIT,
    private val expiryRefreshGraceMillis: Long = DEFAULT_EXPIRY_REFRESH_GRACE_MS,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(CallHistoryState())
    val state: StateFlow<CallHistoryState> = _state.asStateFlow()

    private var activeUserId: String? = null
    private var cacheJob: Job? = null
    private var refreshJob: Job? = null
    private var expiryRefreshJob: Job? = null

    @MainThread
    fun activate(userId: String) {
        if (userId.isBlank()) {
            deactivate(clearState = true)
            return
        }
        if (activeUserId == userId && cacheJob?.isActive == true) {
            return
        }
        if (activeUserId != null && activeUserId != userId) {
            deactivate(clearState = true)
        }

        cacheJob?.cancel()
        expiryRefreshJob?.cancel()
        activeUserId = userId
        cacheJob = scope.launch {
            driver.observeCalls(userId, historyLimit).collect { calls ->
                if (activeUserId != userId) {
                    return@collect
                }
                _state.update { it.copy(calls = calls) }
                scheduleExpiryRefresh(userId, calls)
            }
        }
        refreshProjection()
    }

    @MainThread
    fun deactivate(clearState: Boolean) {
        activeUserId = null
        cacheJob?.cancel()
        cacheJob = null
        refreshJob?.cancel()
        refreshJob = null
        expiryRefreshJob?.cancel()
        expiryRefreshJob = null
        if (clearState) {
            _state.value = CallHistoryState()
        }
    }

    private fun refreshProjection() {
        val userId = activeUserId ?: return
        refreshJob?.cancel()
        val nextRefreshJob = scope.launch {
            try {
                driver.refreshCalls(userId, historyLimit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onWarning("Failed to refresh MatrixRTC call history", error)
            }
        }
        refreshJob = nextRefreshJob
        nextRefreshJob.invokeOnCompletion {
            if (refreshJob === nextRefreshJob) {
                refreshJob = null
            }
        }
    }

    private fun scheduleExpiryRefresh(
        userId: String,
        calls: List<MatrixRtcCallHistoryItem>
    ) {
        val delayMillis = nextExpiryRefreshDelayMillis(
            calls = calls,
            nowMillis = driver.nowMillis(),
            graceMillis = expiryRefreshGraceMillis
        )

        expiryRefreshJob?.cancel()
        if (delayMillis == null) {
            expiryRefreshJob = null
            return
        }

        expiryRefreshJob = scope.launch {
            driver.delayMillis(delayMillis)
            if (activeUserId == userId) {
                refreshProjection()
            }
        }
    }
}

internal fun nextExpiryRefreshDelayMillis(
    calls: List<MatrixRtcCallHistoryItem>,
    nowMillis: Long,
    graceMillis: Long
): Long? {
    val nextExpiry = calls.asSequence()
        .filter { call ->
            call.notificationType == MatrixRtcCallNotificationType.RING &&
                call.outcome == MatrixRtcCallHistoryOutcome.STARTED
        }
        .mapNotNull { call -> call.expiresAtMillis }
        .filter { expiresAtMillis -> expiresAtMillis > nowMillis }
        .minOrNull()
        ?: return null
    return (nextExpiry - nowMillis + graceMillis).coerceAtLeast(1L)
}

internal fun createCallHistoryStore(
    scope: CoroutineScope,
    localCacheRepository: LocalCacheRepository,
    onWarning: (String, Throwable) -> Unit
): CallHistoryStore {
    return CallHistoryStore(
        scope = scope,
        driver = CallHistoryDriver(
            observeCalls = localCacheRepository::observeMatrixRtcCallHistory,
            refreshCalls = localCacheRepository::refreshMatrixRtcCallHistory
        ),
        onWarning = onWarning
    )
}

private const val DEFAULT_CALL_HISTORY_LIMIT = 100
private const val DEFAULT_EXPIRY_REFRESH_GRACE_MS = 250L
