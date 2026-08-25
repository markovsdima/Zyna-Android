package com.zyna.app.ui.spaces

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.SpaceCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceJoinContext
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.data.matrix.MatrixSpaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpaceJoinTarget(
    val userId: String,
    val parentSpaceId: String?,
    val roomId: String,
    val seed: MatrixSpaceRoom
)

enum class SpaceJoinPrimaryAction {
    OPEN,
    ACCEPT_INVITE,
    JOIN,
    KNOCK
}

enum class SpaceJoinError {
    LOAD,
    ACTION,
    ACCESS_CHANGED
}

data class SpaceJoinState(
    val target: SpaceJoinTarget? = null,
    val context: MatrixSpaceJoinContext? = null,
    val isRefreshing: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: SpaceJoinError? = null
) {
    val room: MatrixSpaceRoom?
        get() = context?.room ?: target?.seed

    val primaryAction: SpaceJoinPrimaryAction?
        get() = context?.let(::spaceJoinPrimaryAction)
}

internal class SpaceJoinDriver(
    val loadContext: suspend (
        userId: String,
        roomId: String,
        fallbackRoom: MatrixSpaceRoom
    ) -> MatrixSpaceJoinContext,
    val join: suspend (
        userId: String,
        roomId: String,
        serverNames: List<String>
    ) -> MatrixRoomSummary,
    val knock: suspend (
        userId: String,
        roomId: String,
        serverNames: List<String>
    ) -> Unit,
    val cacheJoinedRoom: suspend (userId: String, room: MatrixRoomSummary) -> Unit,
    val cacheChildMembership: suspend (
        userId: String,
        parentSpaceId: String,
        roomId: String,
        membership: MatrixSpaceMembership
    ) -> Unit
)

/**
 * Command-side owner for one Space hierarchy membership preview.
 *
 * The hierarchy store remains a cache-first read model. This store refreshes only the selected
 * room, performs a fresh preflight before a mutation, and rejects results from an old route or
 * account even when an SDK call ignores coroutine cancellation.
 */
internal class SpaceJoinStore(
    private val scope: CoroutineScope,
    private val driver: SpaceJoinDriver,
    private val onMembershipChanged: (SpaceJoinTarget, MatrixSpaceRoom) -> Unit,
    private val onJoined: (SpaceJoinTarget, MatrixSpaceRoom) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(SpaceJoinState())
    val state: StateFlow<SpaceJoinState> = _state.asStateFlow()

    private var routeGeneration = 0L
    private var loadGeneration = 0L
    private var actionGeneration = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    @MainThread
    fun activate(target: SpaceJoinTarget) {
        val normalized = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        val current = _state.value
        if (current.target?.sameIdentity(normalized) == true) {
            if (current.target == normalized) return
            if (current.isSubmitting) {
                // Keep the command generation stable, but retain the newest seed for a later retry.
                _state.value = current.copy(target = normalized)
                return
            }
            loadGeneration += 1
            loadJob?.cancel()
            _state.value = current.copy(
                target = normalized,
                context = MatrixSpaceJoinContext(normalized.seed),
                isRefreshing = true,
                error = null
            )
            load(normalized, routeGeneration, loadGeneration)
            return
        }

        routeGeneration += 1
        loadGeneration += 1
        actionGeneration += 1
        loadJob?.cancel()
        actionJob?.cancel()
        actionJob = null
        _state.value = SpaceJoinState(
            target = normalized,
            context = MatrixSpaceJoinContext(normalized.seed),
            isRefreshing = true
        )
        load(normalized, routeGeneration, loadGeneration)
    }

    @MainThread
    fun retry() {
        val target = _state.value.target ?: return
        if (_state.value.isSubmitting) return
        loadGeneration += 1
        loadJob?.cancel()
        _state.value = _state.value.copy(isRefreshing = true, error = null)
        load(target, routeGeneration, loadGeneration)
    }

    @MainThread
    fun performPrimaryAction() {
        val current = _state.value
        val target = current.target ?: return
        val context = current.context ?: return
        val requestedAction = current.primaryAction ?: return
        if (current.isSubmitting) return
        if (requestedAction == SpaceJoinPrimaryAction.OPEN) {
            onJoined(target, context.room.withMembership(MatrixSpaceMembership.JOINED))
            return
        }

        actionGeneration += 1
        loadGeneration += 1
        val requestActionGeneration = actionGeneration
        val requestRouteGeneration = routeGeneration
        loadJob?.cancel()
        loadJob = null
        actionJob?.cancel()
        _state.value = current.copy(isRefreshing = false, isSubmitting = true, error = null)
        actionJob = scope.launch {
            val freshContext = try {
                driver.loadContext(target.userId, target.roomId, context.room)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failActionIfCurrent(
                    target,
                    requestRouteGeneration,
                    requestActionGeneration,
                    SpaceJoinError.ACTION,
                    "Failed to authorize Space membership action",
                    error
                )
                return@launch
            }
            if (!isCurrent(target, requestRouteGeneration, requestActionGeneration)) return@launch

            val freshAction = spaceJoinPrimaryAction(freshContext)
            if (freshAction == SpaceJoinPrimaryAction.OPEN) {
                completeJoined(
                    target = target,
                    room = freshContext.room,
                    summary = freshContext.room.toJoinedRoomSummary(),
                    routeGeneration = requestRouteGeneration,
                    requestGeneration = requestActionGeneration
                )
                return@launch
            }
            if (freshAction != requestedAction) {
                _state.value = _state.value.copy(
                    context = freshContext,
                    isRefreshing = false,
                    isSubmitting = false,
                    error = SpaceJoinError.ACCESS_CHANGED
                )
                return@launch
            }

            when (requestedAction) {
                SpaceJoinPrimaryAction.JOIN,
                SpaceJoinPrimaryAction.ACCEPT_INVITE -> performJoin(
                    target,
                    freshContext,
                    requestRouteGeneration,
                    requestActionGeneration
                )
                SpaceJoinPrimaryAction.KNOCK -> performKnock(
                    target,
                    freshContext,
                    requestRouteGeneration,
                    requestActionGeneration
                )
                SpaceJoinPrimaryAction.OPEN -> Unit
            }
        }
    }

    @MainThread
    fun deactivate() {
        routeGeneration += 1
        loadGeneration += 1
        actionGeneration += 1
        loadJob?.cancel()
        loadJob = null
        actionJob?.cancel()
        actionJob = null
        _state.value = SpaceJoinState()
    }

    private fun load(target: SpaceJoinTarget, routeGeneration: Long, requestGeneration: Long) {
        loadJob = scope.launch {
            try {
                val context = driver.loadContext(target.userId, target.roomId, target.seed)
                if (!isCurrentLoad(target, routeGeneration, requestGeneration)) return@launch
                _state.value = _state.value.copy(
                    context = context,
                    isRefreshing = false,
                    error = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrentLoad(target, routeGeneration, requestGeneration)) {
                    onWarning("Failed to load Space membership preview", error)
                    _state.value = _state.value.copy(
                        isRefreshing = false,
                        error = SpaceJoinError.LOAD
                    )
                }
            }
        }
    }

    private suspend fun performJoin(
        target: SpaceJoinTarget,
        context: MatrixSpaceJoinContext,
        routeGeneration: Long,
        requestGeneration: Long
    ) {
        val summary = try {
            driver.join(target.userId, target.roomId, context.room.via)
        } catch (error: CancellationException) {
            throw error
        } catch (writeError: Throwable) {
            val reconciled = reconcileAfterWriteFailure(
                target,
                context.room,
                routeGeneration,
                requestGeneration
            )
            if (reconciled?.room?.membership == MatrixSpaceMembership.JOINED) {
                completeJoined(
                    target,
                    reconciled.room,
                    reconciled.room.toJoinedRoomSummary(),
                    routeGeneration,
                    requestGeneration
                )
                return
            }
            failActionIfCurrent(
                target,
                routeGeneration,
                requestGeneration,
                SpaceJoinError.ACTION,
                "Failed to join Space hierarchy room",
                writeError
            )
            return
        }
        completeJoined(
            target,
            context.room,
            summary,
            routeGeneration,
            requestGeneration
        )
    }

    private suspend fun completeJoined(
        target: SpaceJoinTarget,
        room: MatrixSpaceRoom,
        summary: MatrixRoomSummary,
        routeGeneration: Long,
        requestGeneration: Long
    ) {
        if (!isCurrent(target, routeGeneration, requestGeneration)) return
        val joinedRoom = room.copy(
            displayName = summary.displayName.takeIf(String::isNotBlank) ?: room.displayName,
            avatarUrl = summary.avatarUrl ?: room.avatarUrl,
            membership = MatrixSpaceMembership.JOINED
        )
        val joinedSummary = summary.copy(
            displayName = joinedRoom.displayName,
            avatarUrl = joinedRoom.avatarUrl,
            isSpace = joinedRoom.kind == MatrixSpaceRoomKind.SPACE,
            membership = MatrixSpaceMembership.JOINED
        )
        try {
            driver.cacheJoinedRoom(target.userId, joinedSummary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onWarning("Failed to cache joined Space hierarchy room", error)
        }
        if (!isCurrent(target, routeGeneration, requestGeneration)) return
        cacheHierarchyMembership(target, MatrixSpaceMembership.JOINED)
        if (!isCurrent(target, routeGeneration, requestGeneration)) return
        _state.value = _state.value.copy(
            context = MatrixSpaceJoinContext(joinedRoom),
            isRefreshing = false,
            isSubmitting = false,
            error = null
        )
        onMembershipChanged(target, joinedRoom)
        onJoined(target, joinedRoom)
    }

    private suspend fun performKnock(
        target: SpaceJoinTarget,
        context: MatrixSpaceJoinContext,
        routeGeneration: Long,
        requestGeneration: Long
    ) {
        try {
            driver.knock(target.userId, target.roomId, context.room.via)
        } catch (error: CancellationException) {
            throw error
        } catch (writeError: Throwable) {
            val reconciled = reconcileAfterWriteFailure(
                target,
                context.room,
                routeGeneration,
                requestGeneration
            )
            if (reconciled?.room?.membership == MatrixSpaceMembership.KNOCKED) {
                completeKnock(target, reconciled, routeGeneration, requestGeneration)
                return
            }
            failActionIfCurrent(
                target,
                routeGeneration,
                requestGeneration,
                SpaceJoinError.ACTION,
                "Failed to knock on Space hierarchy room",
                writeError
            )
            return
        }
        completeKnock(
            target,
            context.copy(room = context.room.withMembership(MatrixSpaceMembership.KNOCKED)),
            routeGeneration,
            requestGeneration
        )
    }

    private suspend fun completeKnock(
        target: SpaceJoinTarget,
        context: MatrixSpaceJoinContext,
        routeGeneration: Long,
        requestGeneration: Long
    ) {
        if (!isCurrent(target, routeGeneration, requestGeneration)) return
        val knockedRoom = context.room.withMembership(MatrixSpaceMembership.KNOCKED)
        cacheHierarchyMembership(target, MatrixSpaceMembership.KNOCKED)
        if (!isCurrent(target, routeGeneration, requestGeneration)) return
        _state.value = _state.value.copy(
            context = context.copy(room = knockedRoom),
            isRefreshing = false,
            isSubmitting = false,
            error = null
        )
        onMembershipChanged(target, knockedRoom)
    }

    private suspend fun cacheHierarchyMembership(
        target: SpaceJoinTarget,
        membership: MatrixSpaceMembership
    ) {
        val parentSpaceId = target.parentSpaceId ?: return
        try {
            driver.cacheChildMembership(
                target.userId,
                parentSpaceId,
                target.roomId,
                membership
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onWarning("Failed to cache Space hierarchy membership", error)
        }
    }

    private suspend fun reconcileAfterWriteFailure(
        target: SpaceJoinTarget,
        fallbackRoom: MatrixSpaceRoom,
        routeGeneration: Long,
        requestGeneration: Long
    ): MatrixSpaceJoinContext? {
        if (!isCurrent(target, routeGeneration, requestGeneration)) return null
        return try {
            driver.loadContext(target.userId, target.roomId, fallbackRoom)
                .takeIf { isCurrent(target, routeGeneration, requestGeneration) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun failActionIfCurrent(
        target: SpaceJoinTarget,
        routeGeneration: Long,
        requestGeneration: Long,
        stateError: SpaceJoinError,
        message: String,
        error: Throwable
    ) {
        if (!isCurrent(target, routeGeneration, requestGeneration)) return
        onWarning(message, error)
        _state.value = _state.value.copy(
            isRefreshing = false,
            isSubmitting = false,
            error = stateError
        )
    }

    private fun isCurrentLoad(
        target: SpaceJoinTarget,
        expectedRouteGeneration: Long,
        expectedLoadGeneration: Long
    ): Boolean {
        return _state.value.target?.sameIdentity(target) == true &&
            routeGeneration == expectedRouteGeneration &&
            loadGeneration == expectedLoadGeneration
    }

    private fun isCurrent(
        target: SpaceJoinTarget,
        expectedRouteGeneration: Long,
        expectedActionGeneration: Long
    ): Boolean {
        return _state.value.target?.sameIdentity(target) == true &&
            routeGeneration == expectedRouteGeneration &&
            actionGeneration == expectedActionGeneration
    }
}

internal fun spaceJoinPrimaryAction(
    context: MatrixSpaceJoinContext
): SpaceJoinPrimaryAction? {
    return when (context.room.membership) {
        MatrixSpaceMembership.JOINED -> SpaceJoinPrimaryAction.OPEN
        MatrixSpaceMembership.INVITED -> SpaceJoinPrimaryAction.ACCEPT_INVITE
        MatrixSpaceMembership.KNOCKED,
        MatrixSpaceMembership.BANNED -> null
        MatrixSpaceMembership.LEFT,
        MatrixSpaceMembership.UNKNOWN -> when (context.room.joinRule) {
            MatrixSpaceJoinRule.PUBLIC -> SpaceJoinPrimaryAction.JOIN
            MatrixSpaceJoinRule.KNOCK -> SpaceJoinPrimaryAction.KNOCK
            MatrixSpaceJoinRule.RESTRICTED -> {
                SpaceJoinPrimaryAction.JOIN.takeIf {
                    context.canJoinRestrictedDirectly == true ||
                        context.hasUnsupportedRestrictedAllowRules
                }
            }
            MatrixSpaceJoinRule.KNOCK_RESTRICTED -> when (
                context.canJoinRestrictedDirectly
            ) {
                true -> SpaceJoinPrimaryAction.JOIN
                false -> SpaceJoinPrimaryAction.KNOCK
                null -> null
            }
            MatrixSpaceJoinRule.INVITE,
            MatrixSpaceJoinRule.PRIVATE,
            MatrixSpaceJoinRule.CUSTOM,
            MatrixSpaceJoinRule.UNKNOWN -> null
        }
    }
}

internal fun createSpaceJoinStore(
    scope: CoroutineScope,
    matrixSpaceService: MatrixSpaceService,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    spaceCacheRepository: SpaceCacheRepository,
    onMembershipChanged: (SpaceJoinTarget, MatrixSpaceRoom) -> Unit,
    onJoined: (SpaceJoinTarget, MatrixSpaceRoom) -> Unit,
    onWarning: (String, Throwable) -> Unit
): SpaceJoinStore {
    return SpaceJoinStore(
        scope = scope,
        driver = SpaceJoinDriver(
            loadContext = matrixSpaceService::loadJoinContext,
            join = matrixClientService::joinRoomFromSpace,
            knock = matrixClientService::knockRoomFromSpace,
            cacheJoinedRoom = localCacheRepository::cacheResolvedRoomSummary,
            cacheChildMembership = spaceCacheRepository::cacheSpaceChildMembership
        ),
        onMembershipChanged = onMembershipChanged,
        onJoined = onJoined,
        onWarning = onWarning
    )
}

private fun SpaceJoinTarget.normalizedOrNull(): SpaceJoinTarget? {
    val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedParentId = parentSpaceId?.trim()?.takeIf(String::isNotEmpty)
    if (parentSpaceId != null && normalizedParentId == null) return null
    val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty) ?: return null
    if (seed.roomId != normalizedRoomId) return null
    return copy(
        userId = normalizedUserId,
        parentSpaceId = normalizedParentId,
        roomId = normalizedRoomId
    )
}

private fun SpaceJoinTarget.sameIdentity(other: SpaceJoinTarget): Boolean {
    return userId == other.userId &&
        parentSpaceId == other.parentSpaceId &&
        roomId == other.roomId
}

private fun MatrixSpaceRoom.withMembership(
    membership: MatrixSpaceMembership
): MatrixSpaceRoom = copy(membership = membership)
