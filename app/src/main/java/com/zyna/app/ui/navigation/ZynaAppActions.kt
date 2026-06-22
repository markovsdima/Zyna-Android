package com.zyna.app.ui.navigation

import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary

data class ZynaAppActions(
    val matrixMediaLoader: MatrixMediaLoader?,
    val onLogin: (homeserver: String, username: String, password: String) -> Unit,
    val onSubmitRecoveryKey: (String) -> Unit,
    val onRefreshRooms: () -> Unit,
    val onOpenRoom: (MatrixRoomSummary) -> Unit,
    val onForwardRoomSelected: (MatrixRoomSummary) -> Unit,
    val onCancelForwardPicker: () -> Unit,
    val onRefreshChat: () -> Unit,
    val onCloseChat: () -> Unit,
    val onLoadOlderChatMessages: () -> Unit,
    val onLoadNewerChatMessages: () -> Unit,
    val onJumpToChatLiveEdge: () -> Unit,
    val onSendChatMessage: (String) -> Boolean,
    val onAttachPhotos: () -> Unit,
    val onReplyToMessage: (MatrixReplyInfo) -> Unit,
    val onReplyHeaderClicked: (String) -> Unit,
    val onCancelReply: () -> Unit,
    val onEditMessage: (MatrixEditTarget) -> Unit,
    val onCancelEdit: () -> Unit,
    val onForwardMessage: (MatrixForwardTarget) -> Unit,
    val onCancelForward: () -> Unit,
    val onRetryOutgoingEnvelope: (String) -> Unit,
    val onDiscardOutgoingEnvelope: (String) -> Unit,
    val onRedactMessage: (String) -> Unit,
    val onRedactMessages: (List<String>) -> Unit,
    val onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit,
    val onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit,
    val onChatJumpTargetConsumed: (String) -> Unit,
    val onChatScrollToLiveEdgeConsumed: () -> Unit,
    val onLogout: () -> Unit
)
