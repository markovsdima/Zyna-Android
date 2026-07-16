package com.zyna.app.ui.contacts

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DirectRoomActionIntent {
    OPEN_CHAT,
    START_CALL
}

data class DirectRoomActionState(
    val activeUserId: String? = null,
    val activeIntent: DirectRoomActionIntent? = null,
    val errorMessage: String? = null
)

internal data class DirectRoomActionRequest(
    val sessionUserId: String,
    val ownerKey: String,
    val contact: MatrixContact,
    val intent: DirectRoomActionIntent
)

internal data class ResolvedDirectRoomAction(
    val request: DirectRoomActionRequest,
    val room: MatrixRoomSummary
)

internal class DirectRoomActionDriver(
    val resolveRoom: suspend (
        contact: MatrixContact,
        rooms: List<MatrixRoomSummary>
    ) -> MatrixRoomSummary,
    val cacheRoom: suspend (sessionUserId: String, room: MatrixRoomSummary) -> Unit
)

/**
 * Resolves a direct room for one route-owned contact action at a time.
 *
 * Request identity includes the action intent, so changing from message to call
 * replaces the pending request. Cache writes are best-effort and never block a
 * successfully resolved room from being delivered. Public methods and driver
 * callbacks are main-thread confined.
 */
internal class DirectRoomActionCoordinator(
    private val scope: CoroutineScope,
    private val driver: DirectRoomActionDriver,
    private val canDeliver: (DirectRoomActionRequest) -> Boolean,
    private val onResolved: (ResolvedDirectRoomAction) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(DirectRoomActionState())
    val state: StateFlow<DirectRoomActionState> = _state.asStateFlow()

    private var activeRequest: DirectRoomActionRequest? = null
    private var actionJob: Job? = null
    private var actionGeneration = 0L

    @MainThread
    fun submit(
        sessionUserId: String,
        ownerKey: String,
        contact: MatrixContact,
        rooms: List<MatrixRoomSummary>,
        intent: DirectRoomActionIntent
    ) {
        val normalizedSessionUserId = sessionUserId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedOwnerKey = ownerKey.takeIf { it.isNotBlank() } ?: return
        val normalizedContactUserId = contact.userId.trim().takeIf { it.isNotEmpty() } ?: return
        val request = DirectRoomActionRequest(
            sessionUserId = normalizedSessionUserId,
            ownerKey = normalizedOwnerKey,
            contact = contact.copy(userId = normalizedContactUserId),
            intent = intent
        )
        if (activeRequest?.sameIdentityAs(request) == true && actionJob?.isActive == true) {
            return
        }

        actionGeneration += 1
        val generation = actionGeneration
        actionJob?.cancel()
        activeRequest = request
        _state.value = DirectRoomActionState(
            activeUserId = normalizedContactUserId,
            activeIntent = intent
        )

        val roomsSnapshot = rooms.toList()
        val nextJob = scope.launch {
            try {
                val room = driver.resolveRoom(request.contact, roomsSnapshot)
                if (!isCurrent(request, generation)) {
                    return@launch
                }
                if (!canDeliver(request)) {
                    finishCurrent(request, generation)
                    return@launch
                }

                try {
                    driver.cacheRoom(request.sessionUserId, room)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (isCurrent(request, generation) && canDeliver(request)) {
                        onWarning("Failed to cache resolved direct room", error)
                    }
                }

                if (!isCurrent(request, generation)) {
                    return@launch
                }
                if (!canDeliver(request)) {
                    finishCurrent(request, generation)
                    return@launch
                }

                finishCurrent(request, generation)
                onResolved(ResolvedDirectRoomAction(request = request, room = room))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(request, generation)) {
                    return@launch
                }
                if (!canDeliver(request)) {
                    finishCurrent(request, generation)
                    return@launch
                }
                onWarning("Failed to resolve direct room", error)
                activeRequest = null
                _state.value = DirectRoomActionState(
                    errorMessage = error.message ?: error.javaClass.simpleName
                )
            }
        }
        actionJob = nextJob
        nextJob.invokeOnCompletion {
            if (actionJob === nextJob) {
                actionJob = null
            }
        }
    }

    @MainThread
    fun cancel() {
        actionGeneration += 1
        activeRequest = null
        actionJob?.cancel()
        actionJob = null
        _state.value = DirectRoomActionState()
    }

    private fun finishCurrent(request: DirectRoomActionRequest, generation: Long) {
        if (!isCurrent(request, generation)) {
            return
        }
        activeRequest = null
        actionJob = null
        _state.value = DirectRoomActionState()
    }

    private fun isCurrent(request: DirectRoomActionRequest, generation: Long): Boolean {
        return actionGeneration == generation && activeRequest === request
    }

    private fun DirectRoomActionRequest.sameIdentityAs(
        other: DirectRoomActionRequest
    ): Boolean {
        return sessionUserId == other.sessionUserId &&
            ownerKey == other.ownerKey &&
            contact.userId == other.contact.userId &&
            intent == other.intent
    }
}

internal fun createDirectRoomActionCoordinator(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    canDeliver: (DirectRoomActionRequest) -> Boolean,
    onResolved: (ResolvedDirectRoomAction) -> Unit,
    onWarning: (String, Throwable) -> Unit
): DirectRoomActionCoordinator {
    return DirectRoomActionCoordinator(
        scope = scope,
        driver = DirectRoomActionDriver(
            resolveRoom = { contact, rooms ->
                val existingRoom = contact.roomId
                    ?.let { roomId -> rooms.firstOrNull { it.id == roomId } }
                when {
                    existingRoom != null -> existingRoom
                    !contact.roomId.isNullOrBlank() -> MatrixRoomSummary(
                        id = contact.roomId,
                        displayName = contact.displayName,
                        avatarUrl = contact.avatarUrl,
                        directUserId = contact.userId
                    )
                    else -> matrixClientService.resolveDirectRoom(
                        userId = contact.userId,
                        fallbackDisplayName = contact.displayName,
                        fallbackAvatarUrl = contact.avatarUrl
                    )
                }
            },
            cacheRoom = localCacheRepository::cacheRoomSummary
        ),
        canDeliver = canDeliver,
        onResolved = onResolved,
        onWarning = onWarning
    )
}
