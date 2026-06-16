package com.zyna.app.ui.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.zyna.app.BuildConfig
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.outgoing.OutgoingTextOutboxService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AppRoute {
    data object Login : AppRoute
    data class RecoveryKey(val userId: String) : AppRoute
    data object Rooms : AppRoute
    data class Chat(
        val roomId: String,
        val displayName: String
    ) : AppRoute
}

data class AppUiState(
    val route: AppRoute = AppRoute.Login,
    val matrixState: MatrixClientState = MatrixClientState.LoggedOut,
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val isRefreshingRooms: Boolean = false,
    val isRecovering: Boolean = false,
    val recoveryErrorMessage: String? = null,
    val chatMessages: List<MatrixChatMessage> = emptyList(),
    val isLoadingChat: Boolean = false,
    val isLoadingOlderChatMessages: Boolean = false,
    val canLoadOlderChatMessages: Boolean = true,
    val chatErrorMessage: String? = null,
    val isSendingChatMessage: Boolean = false,
    val chatSendErrorMessage: String? = null
) {
    val isBusy: Boolean
        get() = matrixState is MatrixClientState.LoggingIn ||
            matrixState is MatrixClientState.RestoringSession

    val errorMessage: String?
        get() = (matrixState as? MatrixClientState.Error)?.message
}

private data class VisibleReadReceiptTarget(
    val roomId: String,
    val eventId: String
)

private sealed interface PendingReadReceiptSend {
    val target: VisibleReadReceiptTarget

    data class Bootstrap(
        override val target: VisibleReadReceiptTarget
    ) : PendingReadReceiptSend

    data class Advance(
        override val target: VisibleReadReceiptTarget
    ) : PendingReadReceiptSend
}

class AppViewModel(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingTextOutboxService: OutgoingTextOutboxService
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var chatTimelineJob: Job? = null
    private var chatPaginationJob: Job? = null
    private var chatCacheJob: Job? = null
    private var roomCacheJob: Job? = null
    private var roomListLiveJob: Job? = null
    private var roomCacheUserId: String? = null
    private var roomListLiveUserId: String? = null
    private var readReceiptJob: Job? = null
    private var readReceiptBaselineTarget: VisibleReadReceiptTarget? = null
    private var pendingReadReceiptSend: PendingReadReceiptSend? = null

    init {
        outgoingTextOutboxService.start(viewModelScope)

        viewModelScope.launch {
            matrixClientService.state.collect { matrixState ->
                val previousUserId = _uiState.value.matrixState.userIdOrNull()
                val nextUserId = matrixState.userIdOrNull()
                val didChangeUser = previousUserId != null &&
                    nextUserId != null &&
                    previousUserId != nextUserId
                if (
                    nextUserId == null ||
                    didChangeUser ||
                    matrixState is MatrixClientState.Error
                ) {
                    stopChatTimeline()
                }
                if (nextUserId == null || didChangeUser || matrixState is MatrixClientState.Error) {
                    stopRoomCache()
                    stopRoomListLiveRefresh()
                }

                _uiState.update { current ->
                    val shouldClearSessionData = nextUserId == null || didChangeUser
                    val shouldClearChat = shouldClearSessionData ||
                        matrixState is MatrixClientState.Error

                    current.copy(
                        matrixState = matrixState,
                        route = routeForState(
                            matrixState,
                            if (didChangeUser) AppRoute.Login else current.route
                        ),
                        rooms = if (shouldClearSessionData) {
                            emptyList()
                        } else {
                            current.rooms
                        },
                        chatMessages = if (shouldClearChat) emptyList() else current.chatMessages,
                        isLoadingChat = if (shouldClearChat) false else current.isLoadingChat,
                        isLoadingOlderChatMessages = if (shouldClearChat) {
                            false
                        } else {
                            current.isLoadingOlderChatMessages
                        },
                        canLoadOlderChatMessages = if (shouldClearChat) {
                            true
                        } else {
                            current.canLoadOlderChatMessages
                        },
                        chatErrorMessage = if (shouldClearChat) null else current.chatErrorMessage,
                        isSendingChatMessage = if (shouldClearChat) false else current.isSendingChatMessage,
                        chatSendErrorMessage = if (shouldClearChat) null else current.chatSendErrorMessage
                    )
                }

                if (nextUserId != null) {
                    startRoomCache(nextUserId)
                }

                if (matrixState is MatrixClientState.Syncing) {
                    val userId = matrixState.userId
                    if (matrixClientService.isRecoveryComplete(userId)) {
                        startRoomListLiveRefresh(userId)
                        refreshRooms()
                    }
                }
            }
        }

        viewModelScope.launch {
            outgoingTextOutboxService.sendFailures.collect { failure ->
                _uiState.update {
                    if (!it.isRouteForRoom(failure.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = failure.message)
                }
            }
        }

        viewModelScope.launch {
            matrixClientService.restoreSessionIfAvailable()
        }
    }

    fun login(homeserver: String, username: String, password: String) {
        viewModelScope.launch {
            matrixClientService.login(
                homeserver = homeserver,
                username = username,
                password = password
            )
        }
    }

    fun refreshRooms() {
        viewModelScope.launch {
            refreshRoomsNow()
        }
    }

    private suspend fun refreshRoomsNow() {
        _uiState.update { it.copy(isRefreshingRooms = true) }
        try {
            val userId = _uiState.value.matrixState.userIdOrNull() ?: return
            val rooms = matrixClientService.roomsSnapshot()
            localCacheRepository.cacheRoomsSnapshot(userId, rooms)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to refresh rooms", error)
        } finally {
            _uiState.update {
                it.copy(isRefreshingRooms = false)
            }
        }
    }

    fun submitRecoveryKey(recoveryKey: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRecovering = true,
                    recoveryErrorMessage = null
                )
            }

            try {
                matrixClientService.recoverWithRecoveryKey(recoveryKey)
                _uiState.update {
                    it.copy(
                        route = AppRoute.Rooms,
                        isRecovering = false,
                        recoveryErrorMessage = null
                    )
                }
                val userId = matrixClientService.state.value.userIdOrNull() ?: return@launch
                startRoomListLiveRefresh(userId)
                refreshRoomsNow()
                outgoingTextOutboxService.kick(reason = "recovery-complete")
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isRecovering = false,
                        recoveryErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun openRoom(room: MatrixRoomSummary) {
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        resetReadReceiptTracking()
        _uiState.update {
            it.copy(
                route = AppRoute.Chat(
                    roomId = room.id,
                    displayName = room.displayName
                ),
                chatMessages = emptyList(),
                isLoadingChat = true,
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                chatErrorMessage = null,
                isSendingChatMessage = false,
                chatSendErrorMessage = null
            )
        }
        startChatTimeline(userId, room.id, resetMessages = true)
    }

    fun closeChat() {
        stopChatTimeline()
        _uiState.update {
            it.copy(
                route = AppRoute.Rooms,
                chatMessages = emptyList(),
                isLoadingChat = false,
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                chatErrorMessage = null,
                isSendingChatMessage = false,
                chatSendErrorMessage = null
            )
        }
    }

    fun refreshCurrentChat() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        startChatTimeline(userId, route.roomId, resetMessages = false)
    }

    fun sendChatMessage(body: String): Boolean {
        val route = _uiState.value.route as? AppRoute.Chat ?: return false
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return false
        val text = body.trim()
        if (text.isEmpty() || _uiState.value.isSendingChatMessage) {
            return false
        }
        val envelopeId = "text:${UUID.randomUUID()}"
        val transactionId = matrixClientService.prepareTransactionId()

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(
                isSendingChatMessage = true,
                chatSendErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                localCacheRepository.createOutgoingTextEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId,
                    transactionId = transactionId,
                    body = text
                )
                outgoingTextOutboxService.kick(
                    reason = "new-envelope",
                    envelopeId = envelopeId
                )
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return true
    }

    fun retryOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                val didRetry = localCacheRepository.retryFailedOutgoingTextEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
                if (!didRetry) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
                outgoingTextOutboxService.kick(
                    reason = "manual-retry",
                    envelopeId = envelopeId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun discardOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                val didDiscard = localCacheRepository.discardFailedOutgoingTextEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
                if (!didDiscard) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun debugMarkOutgoingEnvelopeFailed(envelopeId: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                localCacheRepository.debugMarkOutgoingTextEnvelopeFailed(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun loadOlderChatMessages() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val state = _uiState.value
        if (
            state.isLoadingChat ||
            state.isLoadingOlderChatMessages ||
            !state.canLoadOlderChatMessages ||
            chatPaginationJob?.isActive == true
        ) {
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(isLoadingOlderChatMessages = true)
        }

        chatPaginationJob = viewModelScope.launch {
            try {
                val hasReachedStart = matrixClientService.paginateRoomTimelineBackwards(route.roomId)
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadOlderChatMessages = !hasReachedStart
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(isLoadingOlderChatMessages = false)
                }
            }
        }
    }

    fun updateVisibleReadReceiptCandidate(
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        if (route.roomId != roomId) {
            return
        }
        if (eventId.isNullOrBlank()) {
            readReceiptJob?.cancel()
            readReceiptJob = null
            pendingReadReceiptSend = null
            return
        }

        val target = VisibleReadReceiptTarget(
            roomId = roomId,
            eventId = eventId
        )

        if (readReceiptBaselineTarget == null) {
            if (!canEstablishBaseline) {
                return
            }
            val pendingBootstrap = PendingReadReceiptSend.Bootstrap(target)
            if (pendingReadReceiptSend == pendingBootstrap) {
                return
            }

            scheduleReadReceiptSend(
                target = target,
                pending = pendingBootstrap
            )
            return
        }

        if (!shouldAdvanceReadReceipt(target = target)) {
            return
        }

        val pendingAdvance = PendingReadReceiptSend.Advance(target)
        if (pendingReadReceiptSend == pendingAdvance) {
            return
        }

        scheduleReadReceiptSend(
            target = target,
            pending = pendingAdvance
        )
    }

    fun logout() {
        stopChatTimeline()
        stopRoomCache()
        stopRoomListLiveRefresh()
        viewModelScope.launch {
            localCacheRepository.clearAll()
            matrixClientService.logout()
        }
    }

    private fun routeForState(
        state: MatrixClientState,
        currentRoute: AppRoute
    ): AppRoute {
        return when (state) {
            MatrixClientState.LoggedOut,
            is MatrixClientState.Error -> AppRoute.Login
            is MatrixClientState.LoggedIn -> {
                val userId = state.userId

                if (!matrixClientService.isRecoveryComplete(userId)) {
                    AppRoute.RecoveryKey(userId)
                } else if (currentRoute is AppRoute.Login || currentRoute is AppRoute.RecoveryKey) {
                    AppRoute.Rooms
                } else {
                    currentRoute
                }
            }
            is MatrixClientState.Syncing -> {
                val userId = state.userId

                if (!matrixClientService.isRecoveryComplete(userId)) {
                    AppRoute.RecoveryKey(userId)
                } else if (currentRoute is AppRoute.Login || currentRoute is AppRoute.RecoveryKey) {
                    AppRoute.Rooms
                } else {
                    currentRoute
                }
            }
            MatrixClientState.LoggingIn,
            MatrixClientState.RestoringSession -> currentRoute
        }
    }

    private fun startChatTimeline(userId: String, roomId: String, resetMessages: Boolean) {
        chatTimelineJob?.cancel()
        chatPaginationJob?.cancel()
        chatPaginationJob = null
        chatCacheJob?.cancel()
        chatCacheJob = null
        _uiState.update {
            it.copy(
                chatMessages = if (resetMessages) emptyList() else it.chatMessages,
                isLoadingChat = true,
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                chatErrorMessage = null
            )
        }
        chatCacheJob = viewModelScope.launch {
            localCacheRepository.observeRoomTimeline(userId, roomId).collect { messages ->
                _uiState.update {
                    if (!it.isRouteForRoom(userId, roomId)) {
                        it
                    } else it.copy(chatMessages = messages)
                }
            }
        }
        chatTimelineJob = viewModelScope.launch {
            try {
                matrixClientService.roomTimelineMessages(roomId).collect { messages ->
                    if (messages.isNotEmpty()) {
                        localCacheRepository.cacheRoomTimelineMessages(userId, roomId, messages)
                    }
                    _uiState.update {
                        if (!it.isRouteForRoom(userId, roomId)) {
                            it
                        } else it.copy(
                            isLoadingChat = false,
                            isLoadingOlderChatMessages = false,
                            chatErrorMessage = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, roomId)) {
                        it
                    } else it.copy(
                        isLoadingChat = false,
                        isLoadingOlderChatMessages = false,
                        chatErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun stopChatTimeline() {
        chatTimelineJob?.cancel()
        chatTimelineJob = null
        chatCacheJob?.cancel()
        chatCacheJob = null
        chatPaginationJob?.cancel()
        chatPaginationJob = null
        resetReadReceiptTracking()
    }

    private fun scheduleReadReceiptSend(
        target: VisibleReadReceiptTarget,
        pending: PendingReadReceiptSend
    ) {
        readReceiptJob?.cancel()
        pendingReadReceiptSend = pending
        readReceiptJob = viewModelScope.launch {
            delay(READ_RECEIPT_SEND_DELAY_MS)

            val didSend = try {
                matrixClientService.sendReadReceipt(
                    roomId = target.roomId,
                    eventId = target.eventId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to send read receipt", error)
                false
            }

            finishReadReceiptSend(pending, didSend = didSend)
        }
    }

    private fun finishReadReceiptSend(
        pending: PendingReadReceiptSend,
        didSend: Boolean
    ) {
        if (pendingReadReceiptSend == pending) {
            pendingReadReceiptSend = null
        }

        if (!didSend) {
            return
        }

        when (pending) {
            is PendingReadReceiptSend.Bootstrap -> {
                establishReadReceiptBaseline(target = pending.target)
            }
            is PendingReadReceiptSend.Advance -> {
                establishReadReceiptBaseline(target = pending.target)
            }
        }
    }

    private fun shouldAdvanceReadReceipt(target: VisibleReadReceiptTarget): Boolean {
        val baselineTarget = readReceiptBaselineTarget
        if (baselineTarget != null && !isReadReceiptTargetNewer(target, reference = baselineTarget)) {
            return false
        }

        val pendingTarget = pendingReadReceiptSend?.target
        if (pendingTarget != null && !isReadReceiptTargetNewer(target, reference = pendingTarget)) {
            return false
        }

        return true
    }

    private fun isReadReceiptTargetNewer(
        target: VisibleReadReceiptTarget,
        reference: VisibleReadReceiptTarget
    ): Boolean {
        if (target.roomId != reference.roomId || target.eventId == reference.eventId) {
            return false
        }

        val targetIndex = messageIndex(eventId = target.eventId)
        val referenceIndex = messageIndex(eventId = reference.eventId)

        return when {
            targetIndex != null && referenceIndex != null -> targetIndex > referenceIndex
            targetIndex != null && referenceIndex == null -> true
            else -> false
        }
    }

    private fun messageIndex(eventId: String): Int? {
        return _uiState.value.chatMessages.indexOfFirst { it.eventId == eventId }
            .takeIf { it >= 0 }
    }

    private fun establishReadReceiptBaseline(target: VisibleReadReceiptTarget) {
        val currentBaseline = readReceiptBaselineTarget
        if (currentBaseline != null && !isReadReceiptTargetNewer(target, reference = currentBaseline)) {
            return
        }

        readReceiptBaselineTarget = target

        val pendingTarget = pendingReadReceiptSend?.target
        if (pendingTarget != null && !isReadReceiptTargetNewer(pendingTarget, reference = target)) {
            readReceiptJob?.cancel()
            readReceiptJob = null
            pendingReadReceiptSend = null
        }
    }

    private fun resetReadReceiptTracking() {
        readReceiptJob?.cancel()
        readReceiptJob = null
        readReceiptBaselineTarget = null
        pendingReadReceiptSend = null
    }

    private fun startRoomCache(userId: String) {
        if (roomCacheUserId == userId && roomCacheJob?.isActive == true) {
            return
        }

        roomCacheJob?.cancel()
        roomCacheUserId = userId
        roomCacheJob = viewModelScope.launch {
            localCacheRepository.observeRooms(userId).collect { rooms ->
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(rooms = rooms)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun stopRoomCache() {
        roomCacheJob?.cancel()
        roomCacheJob = null
        roomCacheUserId = null
    }

    private fun startRoomListLiveRefresh(userId: String) {
        if (roomListLiveUserId == userId && roomListLiveJob?.isActive == true) {
            return
        }

        roomListLiveJob?.cancel()
        roomListLiveUserId = userId
        roomListLiveJob = viewModelScope.launch {
            try {
                matrixClientService.roomListChangeSignals().collectLatest {
                    if (matrixClientService.isRecoveryComplete(userId)) {
                        refreshRoomsNow()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to observe room list state", error)
            }
        }
    }

    private fun stopRoomListLiveRefresh() {
        roomListLiveJob?.cancel()
        roomListLiveJob = null
        roomListLiveUserId = null
    }

    private fun AppUiState.isRouteForRoom(roomId: String): Boolean {
        return (route as? AppRoute.Chat)?.roomId == roomId
    }

    private fun AppUiState.isRouteForRoom(userId: String, roomId: String): Boolean {
        return matrixState.userIdOrNull() == userId && isRouteForRoom(roomId)
    }

    private fun MatrixClientState.userIdOrNull(): String? {
        return when (this) {
            is MatrixClientState.LoggedIn -> userId
            is MatrixClientState.Syncing -> userId
            MatrixClientState.LoggedOut,
            is MatrixClientState.Error,
            MatrixClientState.LoggingIn,
            MatrixClientState.RestoringSession -> null
        }
    }

    private companion object {
        const val TAG = "AppViewModel"
        const val READ_RECEIPT_SEND_DELAY_MS = 250L
    }
}

class AppViewModelFactory(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingTextOutboxService: OutgoingTextOutboxService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(
                matrixClientService = matrixClientService,
                localCacheRepository = localCacheRepository,
                outgoingTextOutboxService = outgoingTextOutboxService
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
