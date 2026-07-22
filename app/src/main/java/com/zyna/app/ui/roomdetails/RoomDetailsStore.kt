package com.zyna.app.ui.roomdetails

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomDetails
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomDetailsTarget(
    val userId: String,
    val roomId: String
)

data class RoomDetailsState(
    val target: RoomDetailsTarget? = null,
    val seed: MatrixRoomSummary? = null,
    val details: MatrixRoomDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

internal class RoomDetailsDriver(
    val observeCachedDetails: (userId: String, roomId: String) -> Flow<MatrixRoomDetails?>,
    val observeLiveDetails: (roomId: String) -> Flow<MatrixRoomDetails>,
    val cacheDetails: suspend (userId: String, details: MatrixRoomDetails) -> Unit
)

/**
 * Owns the active room-details route plus cached and live Matrix observation lifecycles.
 *
 * The target includes the session user so a late callback from an old session cannot update
 * a room with the same id after account replacement. In-memory room-list details seed the first
 * frame, the Room flow restores persisted details, and live values are written through to cache.
 */
internal class RoomDetailsStore(
    private val scope: CoroutineScope,
    private val driver: RoomDetailsDriver,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomDetailsState())
    val state: StateFlow<RoomDetailsState> = _state.asStateFlow()

    private var generation = 0L
    private var livePublishedGeneration = Long.MIN_VALUE
    private var cacheJob: Job? = null
    private var liveJob: Job? = null

    @MainThread
    fun activate(target: RoomDetailsTarget, seed: MatrixRoomSummary?) {
        val normalizedTarget = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        val compatibleSeed = seed?.takeIf { it.id == normalizedTarget.roomId }
        if (
            _state.value.target == normalizedTarget &&
            (cacheJob?.isActive == true || liveJob?.isActive == true)
        ) {
            if (_state.value.seed != compatibleSeed) {
                val seededDetails = compatibleSeed?.roomDetails
                    ?.takeIf { _state.value.details == null }
                _state.value = _state.value.copy(
                    seed = compatibleSeed,
                    details = seededDetails ?: _state.value.details,
                    isLoading = seededDetails == null && _state.value.isLoading
                )
            }
            return
        }
        startObservation(
            target = normalizedTarget,
            seed = compatibleSeed,
            retainedDetails = compatibleSeed?.roomDetails
        )
    }

    @MainThread
    fun refresh() {
        val current = _state.value
        val target = current.target ?: return
        startObservation(
            target = target,
            seed = current.seed,
            retainedDetails = current.details
        )
    }

    @MainThread
    fun deactivate() {
        if (_state.value.target == null && cacheJob == null && liveJob == null) {
            return
        }
        generation += 1
        livePublishedGeneration = Long.MIN_VALUE
        cacheJob?.cancel()
        cacheJob = null
        liveJob?.cancel()
        liveJob = null
        _state.value = RoomDetailsState()
    }

    private fun startObservation(
        target: RoomDetailsTarget,
        seed: MatrixRoomSummary?,
        retainedDetails: MatrixRoomDetails?
    ) {
        generation += 1
        val requestGeneration = generation
        livePublishedGeneration = Long.MIN_VALUE
        cacheJob?.cancel()
        liveJob?.cancel()
        _state.value = RoomDetailsState(
            target = target,
            seed = seed,
            details = retainedDetails,
            isLoading = retainedDetails == null
        )

        val nextCacheJob = scope.launch {
            try {
                driver.observeCachedDetails(target.userId, target.roomId).collect { cachedDetails ->
                    if (
                        !isCurrent(target, requestGeneration) ||
                        livePublishedGeneration == requestGeneration ||
                        cachedDetails?.roomId != target.roomId
                    ) {
                        return@collect
                    }
                    _state.value = _state.value.copy(
                        details = cachedDetails,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                onWarning("Failed to observe cached room details", error)
            }
        }
        cacheJob = nextCacheJob
        nextCacheJob.invokeOnCompletion {
            if (cacheJob === nextCacheJob) {
                cacheJob = null
            }
        }

        val nextLiveJob = scope.launch {
            try {
                driver.observeLiveDetails(target.roomId).collect { details ->
                    if (!isCurrent(target, requestGeneration) || details.roomId != target.roomId) {
                        return@collect
                    }
                    livePublishedGeneration = requestGeneration
                    _state.value = _state.value.copy(
                        details = details,
                        isLoading = false,
                        errorMessage = null
                    )
                    try {
                        driver.cacheDetails(target.userId, details)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (isCurrent(target, requestGeneration)) {
                            onWarning("Failed to cache room details", error)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                onWarning("Failed to observe live room details", error)
                if (_state.value.details == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: error.javaClass.simpleName
                    )
                } else {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
        liveJob = nextLiveJob
        nextLiveJob.invokeOnCompletion {
            if (liveJob === nextLiveJob) {
                liveJob = null
            }
        }
    }

    private fun isCurrent(target: RoomDetailsTarget, requestGeneration: Long): Boolean {
        return generation == requestGeneration && _state.value.target == target
    }

    private fun RoomDetailsTarget.normalizedOrNull(): RoomDetailsTarget? {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedRoomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        return RoomDetailsTarget(
            userId = normalizedUserId,
            roomId = normalizedRoomId
        )
    }
}

internal fun createRoomDetailsStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    onWarning: (String, Throwable) -> Unit
): RoomDetailsStore {
    return RoomDetailsStore(
        scope = scope,
        driver = RoomDetailsDriver(
            observeCachedDetails = localCacheRepository::observeRoomDetails,
            observeLiveDetails = matrixClientService::roomDetailsUpdates,
            cacheDetails = localCacheRepository::cacheRoomDetails
        ),
        onWarning = onWarning
    )
}
