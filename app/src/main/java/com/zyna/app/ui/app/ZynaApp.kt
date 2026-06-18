package com.zyna.app.ui.app

import androidx.compose.runtime.Composable
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.ui.auth.LoginScreen
import com.zyna.app.ui.chat.ChatScreen
import com.zyna.app.ui.rooms.RoomsScreen
import com.zyna.app.ui.security.RecoveryKeyScreen

@Composable
fun ZynaApp(
    state: AppUiState,
    onLogin: (homeserver: String, username: String, password: String) -> Unit,
    onSubmitRecoveryKey: (String) -> Unit,
    onRefreshRooms: () -> Unit,
    onOpenRoom: (MatrixRoomSummary) -> Unit,
    onRefreshChat: () -> Unit,
    onCloseChat: () -> Unit,
    onLoadOlderChatMessages: () -> Unit,
    onSendChatMessage: (String) -> Boolean,
    onReplyToMessage: (MatrixReplyInfo) -> Unit,
    onCancelReply: () -> Unit,
    onRetryOutgoingEnvelope: (String) -> Unit,
    onDiscardOutgoingEnvelope: (String) -> Unit,
    onRedactMessage: (String) -> Unit,
    onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit,
    onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit,
    onLogout: () -> Unit
) {
    when (val route = state.route) {
        AppRoute.Login -> LoginScreen(
            isBusy = state.isBusy,
            errorMessage = state.errorMessage,
            onLogin = onLogin
        )
        is AppRoute.RecoveryKey -> RecoveryKeyScreen(
            userId = route.userId,
            isRecovering = state.isRecovering,
            errorMessage = state.recoveryErrorMessage,
            onSubmit = onSubmitRecoveryKey
        )
        AppRoute.Rooms -> RoomsScreen(
            rooms = state.rooms,
            isRefreshing = state.isRefreshingRooms,
            onRefresh = onRefreshRooms,
            onOpenRoom = onOpenRoom,
            onLogout = onLogout
        )
        is AppRoute.Chat -> ChatScreen(
            roomName = route.displayName,
            roomId = route.roomId,
            messages = state.chatMessages,
            windowChangeOrigin = state.chatWindowChangeOrigin,
            isLoading = state.isLoadingChat,
            isLoadingOlder = state.isLoadingOlderChatMessages,
            canLoadOlder = state.canLoadOlderChatMessages,
            errorMessage = state.chatErrorMessage,
            isSendingMessage = state.isSendingChatMessage,
            sendErrorMessage = state.chatSendErrorMessage,
            replyTarget = state.chatReplyTarget,
            onRefresh = onRefreshChat,
            onBack = onCloseChat,
            onLoadOlder = onLoadOlderChatMessages,
            onSendMessage = onSendChatMessage,
            onReplyToMessage = onReplyToMessage,
            onCancelReply = onCancelReply,
            onRetryOutgoingEnvelope = onRetryOutgoingEnvelope,
            onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope,
            onRedactMessage = onRedactMessage,
            onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed,
            onVisibleReadReceiptCandidate = onVisibleReadReceiptCandidate
        )
    }
}
