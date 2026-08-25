package com.zyna.app.ui.roommembers

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberModerationAction
import com.zyna.app.data.matrix.MatrixRoomMemberModerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomMemberModerationTarget(
    val userId: String,
    val roomId: String,
    val memberUserId: String
)

data class PendingRoomMemberModeration(
    val action: MatrixRoomMemberModerationAction,
    val displayName: String,
    val membership: MatrixRoomMemberMembership
)

enum class RoomMemberModerationError {
    LOAD,
    SAVE,
    PERMISSION_CHANGED
}

data class RoomMemberModerationState(
    val target: RoomMemberModerationTarget? = null,
    val member: MatrixRoomMember? = null,
    val context: MatrixRoomMemberModerationContext? = null,
    val isLoading: Boolean = false,
    val pendingConfirmation: PendingRoomMemberModeration? = null,
    val savingAction: MatrixRoomMemberModerationAction? = null,
    val error: RoomMemberModerationError? = null
) {
    val isSaving: Boolean
        get() = savingAction != null

    val canSendMessage: Boolean
        get() {
            val activeTarget = target ?: return false
            return member != null && member.userId != activeTarget.userId
        }

    fun canPerform(action: MatrixRoomMemberModerationAction): Boolean {
        return !isSaving && isActionAvailable(action)
    }

    fun isActionAvailable(action: MatrixRoomMemberModerationAction): Boolean {
        return context?.canPerform(action) == true
    }
}

internal class RoomMemberModerationDriver(
    val loadContext: suspend (
        roomId: String,
        targetUserId: String
    ) -> MatrixRoomMemberModerationContext,
    val moderate: suspend (
        roomId: String,
        targetUserId: String,
        action: MatrixRoomMemberModerationAction,
        reason: String?
    ) -> Unit
)

/**
 * Owns the command side and fresh authorization state of one contextual member screen.
 *
 * The shared [RoomMembersStore] remains the list read model. This store only loads a targeted
 * Matrix snapshot and owns confirmation, mutation, cancellation and stale-result rejection.
 */
internal class RoomMemberModerationStore(
    private val scope: CoroutineScope,
    private val driver: RoomMemberModerationDriver,
    private val onMembersRefreshRequested: () -> Unit,
    private val onCompleted: (
        RoomMemberModerationTarget,
        MatrixRoomMemberModerationAction
    ) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomMemberModerationState())
    val state: StateFlow<RoomMemberModerationState> = _state.asStateFlow()

    private var routeGeneration = 0L
    private var loadGeneration = 0L
    private var actionGeneration = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    @MainThread
    fun activate(target: RoomMemberModerationTarget, seed: MatrixRoomMember?) {
        val normalizedTarget = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        if (_state.value.target == normalizedTarget) {
            return
        }
        routeGeneration += 1
        loadGeneration += 1
        actionGeneration += 1
        loadJob?.cancel()
        actionJob?.cancel()
        actionJob = null
        val normalizedSeed = seed?.takeIf { it.userId == normalizedTarget.memberUserId }
        _state.value = RoomMemberModerationState(
            target = normalizedTarget,
            member = normalizedSeed,
            isLoading = true
        )
        load(normalizedTarget, routeGeneration, loadGeneration)
    }

    @MainThread
    fun retry() {
        val target = _state.value.target ?: return
        if (_state.value.isSaving) return
        loadJob?.cancel()
        loadGeneration += 1
        _state.value = _state.value.copy(isLoading = true, error = null)
        load(target, routeGeneration, loadGeneration)
    }

    @MainThread
    fun request(action: MatrixRoomMemberModerationAction) {
        val current = _state.value
        val member = current.member ?: return
        if (!current.canPerform(action)) return
        _state.value = current.copy(
            pendingConfirmation = PendingRoomMemberModeration(
                action = action,
                displayName = member.displayNameOrUserId,
                membership = member.membership
            ),
            error = null
        )
    }

    @MainThread
    fun cancelConfirmation() {
        val current = _state.value
        if (current.pendingConfirmation == null || current.isSaving) return
        _state.value = current.copy(pendingConfirmation = null)
    }

    @MainThread
    fun confirm(reason: String?) {
        val current = _state.value
        val target = current.target ?: return
        val pending = current.pendingConfirmation ?: return
        if (current.isSaving) return

        actionGeneration += 1
        val requestGeneration = actionGeneration
        val requestRouteGeneration = routeGeneration
        actionJob?.cancel()
        _state.value = current.copy(
            pendingConfirmation = null,
            savingAction = pending.action,
            error = null
        )
        val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        val nextJob = scope.launch {
            val freshContext = try {
                driver.loadContext(target.roomId, target.memberUserId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(target, requestRouteGeneration, requestGeneration)) {
                    onWarning("Failed to authorize room member moderation", error)
                    onMembersRefreshRequested()
                    _state.value = _state.value.copy(
                        isLoading = false,
                        savingAction = null,
                        error = RoomMemberModerationError.SAVE
                    )
                }
                return@launch
            }
            if (!isCurrent(target, requestRouteGeneration, requestGeneration)) {
                return@launch
            }
            if (
                !freshContext.matches(target) ||
                !freshContext.canPerform(pending.action)
            ) {
                onMembersRefreshRequested()
                val matchingContext = freshContext.takeIf { it.matches(target) }
                _state.value = _state.value.copy(
                    member = matchingContext?.member ?: _state.value.member,
                    context = matchingContext ?: _state.value.context,
                    isLoading = false,
                    savingAction = null,
                    error = RoomMemberModerationError.PERMISSION_CHANGED
                )
                return@launch
            }

            try {
                driver.moderate(
                    target.roomId,
                    target.memberUserId,
                    pending.action,
                    normalizedReason
                )
            } catch (error: CancellationException) {
                throw error
            } catch (writeError: Throwable) {
                if (!isCurrent(target, requestRouteGeneration, requestGeneration)) {
                    return@launch
                }
                onWarning("Failed to moderate room member", writeError)
                val observedContext = try {
                    driver.loadContext(target.roomId, target.memberUserId)
                        .takeIf { it.matches(target) }
                } catch (error: CancellationException) {
                    throw error
                } catch (refreshError: Throwable) {
                    onWarning(
                        "Failed to confirm room member moderation after write error",
                        refreshError
                    )
                    null
                }
                if (!isCurrent(target, requestRouteGeneration, requestGeneration)) {
                    return@launch
                }
                if (observedContext?.reflectsCompleted(pending.action) == true) {
                    finishSuccess(
                        target = target,
                        action = pending.action,
                        context = observedContext,
                        requestRouteGeneration = requestRouteGeneration,
                        requestGeneration = requestGeneration
                    )
                } else {
                    onMembersRefreshRequested()
                    _state.value = _state.value.copy(
                        member = observedContext?.member ?: _state.value.member,
                        context = observedContext ?: _state.value.context,
                        isLoading = false,
                        savingAction = null,
                        error = if (
                            observedContext != null &&
                            !observedContext.canPerform(pending.action)
                        ) {
                            RoomMemberModerationError.PERMISSION_CHANGED
                        } else {
                            RoomMemberModerationError.SAVE
                        }
                    )
                }
                return@launch
            }

            finishSuccess(
                target = target,
                action = pending.action,
                context = freshContext,
                requestRouteGeneration = requestRouteGeneration,
                requestGeneration = requestGeneration
            )
        }
        actionJob = nextJob
        nextJob.invokeOnCompletion {
            if (actionJob === nextJob) {
                actionJob = null
            }
        }
    }

    @MainThread
    fun deactivate() {
        if (_state.value.target == null && loadJob == null && actionJob == null) {
            return
        }
        routeGeneration += 1
        loadGeneration += 1
        actionGeneration += 1
        loadJob?.cancel()
        loadJob = null
        actionJob?.cancel()
        actionJob = null
        _state.value = RoomMemberModerationState()
    }

    @MainThread
    fun clearSession() {
        deactivate()
    }

    private fun load(
        target: RoomMemberModerationTarget,
        requestRouteGeneration: Long,
        requestLoadGeneration: Long
    ) {
        val nextJob = scope.launch {
            try {
                val context = driver.loadContext(target.roomId, target.memberUserId)
                if (!context.matches(target)) {
                    error("Room member moderation context does not match its target")
                }
                if (
                    routeGeneration == requestRouteGeneration &&
                    loadGeneration == requestLoadGeneration &&
                    _state.value.target == target
                ) {
                    _state.value = _state.value.copy(
                        member = context.member,
                        context = context,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    routeGeneration == requestRouteGeneration &&
                    loadGeneration == requestLoadGeneration &&
                    _state.value.target == target
                ) {
                    onWarning("Failed to load room member moderation context", error)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = RoomMemberModerationError.LOAD
                    )
                }
            }
        }
        loadJob = nextJob
        nextJob.invokeOnCompletion {
            if (loadJob === nextJob) {
                loadJob = null
            }
        }
    }

    private fun finishSuccess(
        target: RoomMemberModerationTarget,
        action: MatrixRoomMemberModerationAction,
        context: MatrixRoomMemberModerationContext,
        requestRouteGeneration: Long,
        requestGeneration: Long
    ) {
        if (!isCurrent(target, requestRouteGeneration, requestGeneration)) {
            return
        }
        val completedContext = if (context.reflectsCompleted(action)) {
            context
        } else {
            context.withCompleted(action)
        }
        _state.value = _state.value.copy(
            member = completedContext.member,
            context = completedContext,
            isLoading = false,
            savingAction = null,
            error = null
        )
        onMembersRefreshRequested()
        if (isCurrent(target, requestRouteGeneration, requestGeneration)) {
            onCompleted(target, action)
        }
    }

    private fun isCurrent(
        target: RoomMemberModerationTarget,
        requestRouteGeneration: Long,
        requestGeneration: Long
    ): Boolean {
        return routeGeneration == requestRouteGeneration &&
            actionGeneration == requestGeneration &&
            _state.value.target == target
    }

    private fun MatrixRoomMemberModerationContext.matches(
        target: RoomMemberModerationTarget
    ): Boolean {
        return roomId == target.roomId &&
            ownUserId == target.userId &&
            member.userId == target.memberUserId
    }

    private fun RoomMemberModerationTarget.normalizedOrNull(): RoomMemberModerationTarget? {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedRoomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedMemberUserId =
            memberUserId.trim().takeIf { it.isNotEmpty() } ?: return null
        return RoomMemberModerationTarget(
            userId = normalizedUserId,
            roomId = normalizedRoomId,
            memberUserId = normalizedMemberUserId
        )
    }
}

internal fun createRoomMemberModerationStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onMembersRefreshRequested: () -> Unit,
    onCompleted: (
        RoomMemberModerationTarget,
        MatrixRoomMemberModerationAction
    ) -> Unit,
    onWarning: (String, Throwable) -> Unit
): RoomMemberModerationStore {
    return RoomMemberModerationStore(
        scope = scope,
        driver = RoomMemberModerationDriver(
            loadContext = matrixClientService::loadRoomMemberModerationContext,
            moderate = matrixClientService::moderateRoomMember
        ),
        onMembersRefreshRequested = onMembersRefreshRequested,
        onCompleted = onCompleted,
        onWarning = onWarning
    )
}
