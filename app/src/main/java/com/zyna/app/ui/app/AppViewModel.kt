package com.zyna.app.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixRoomSummary
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
    val chatErrorMessage: String? = null
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

    init {
        viewModelScope.launch {
            matrixClientService.state.collect { matrixState ->
                _uiState.update { current ->
                    current.copy(
                        matrixState = matrixState,
                        route = routeForState(matrixState, current.route),
                        rooms = if (matrixState is MatrixClientState.LoggedOut) {
                            emptyList()
                        } else {
                            current.rooms
                        }
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
                chatErrorMessage = null
            )
        }
        loadTimeline(room.id)
    }

    fun closeChat() {
        _uiState.update {
            it.copy(
                route = AppRoute.Rooms,
                chatMessages = emptyList(),
                isLoadingChat = false,
                chatErrorMessage = null
            )
        }
    }

    fun refreshCurrentChat() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        loadTimeline(route.roomId)
    }

    fun logout() {
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

    private fun loadTimeline(roomId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingChat = true,
                    chatErrorMessage = null
                )
            }

            try {
                val messages = matrixClientService.roomTimelineSnapshot(roomId)
                _uiState.update {
                    if (!it.isRouteForRoom(roomId)) {
                        it
                    } else it.copy(
                        chatMessages = messages,
                        isLoadingChat = false,
                        chatErrorMessage = null
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(roomId)) {
                        it
                    } else it.copy(
                        isLoadingChat = false,
                        chatErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
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
