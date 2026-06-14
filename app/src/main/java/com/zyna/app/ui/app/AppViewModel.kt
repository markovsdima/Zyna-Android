package com.zyna.app.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class AppViewModel(
    private val matrixClientService: MatrixClientService
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var chatTimelineJob: Job? = null
    private var chatPaginationJob: Job? = null

    init {
        viewModelScope.launch {
            matrixClientService.state.collect { matrixState ->
                if (matrixState is MatrixClientState.LoggedOut || matrixState is MatrixClientState.Error) {
                    stopChatTimeline()
                }

                _uiState.update { current ->
                    val shouldClearChat = matrixState is MatrixClientState.LoggedOut ||
                        matrixState is MatrixClientState.Error

                    current.copy(
                        matrixState = matrixState,
                        route = routeForState(matrixState, current.route),
                        rooms = if (matrixState is MatrixClientState.LoggedOut) {
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

                if (matrixState is MatrixClientState.Syncing) {
                    val userId = matrixState.userId
                    if (matrixClientService.isRecoveryComplete(userId)) {
                        refreshRooms()
                    }
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
            _uiState.update { it.copy(isRefreshingRooms = true) }
            val rooms = matrixClientService.roomsSnapshot()
            _uiState.update {
                it.copy(
                    rooms = rooms,
                    isRefreshingRooms = false
                )
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
                refreshRooms()
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
        startChatTimeline(room.id, resetMessages = true)
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
        startChatTimeline(route.roomId, resetMessages = false)
    }

    fun sendChatMessage(body: String): Boolean {
        val route = _uiState.value.route as? AppRoute.Chat ?: return false
        val text = body.trim()
        if (text.isEmpty() || _uiState.value.isSendingChatMessage) {
            return false
        }

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
                matrixClientService.sendTextMessage(route.roomId, text)
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

    fun logout() {
        stopChatTimeline()
        viewModelScope.launch {
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

    private fun startChatTimeline(roomId: String, resetMessages: Boolean) {
        chatTimelineJob?.cancel()
        chatPaginationJob?.cancel()
        chatPaginationJob = null
        chatTimelineJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    chatMessages = if (resetMessages) emptyList() else it.chatMessages,
                    isLoadingChat = true,
                    isLoadingOlderChatMessages = false,
                    canLoadOlderChatMessages = true,
                    chatErrorMessage = null
                )
            }

            try {
                matrixClientService.roomTimelineMessages(roomId).collect { messages ->
                    _uiState.update {
                        if (!it.isRouteForRoom(roomId)) {
                            it
                        } else it.copy(
                            chatMessages = messages,
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
                    if (!it.isRouteForRoom(roomId)) {
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
        chatPaginationJob?.cancel()
        chatPaginationJob = null
    }

    private fun AppUiState.isRouteForRoom(roomId: String): Boolean {
        return (route as? AppRoute.Chat)?.roomId == roomId
    }
}

class AppViewModelFactory(
    private val matrixClientService: MatrixClientService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(matrixClientService) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
