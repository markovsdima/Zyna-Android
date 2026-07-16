package com.zyna.app.ui.navigation

import com.zyna.app.data.media.AudioPlaybackController
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.media.VoiceRecorderController
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.presence.PresenceProviderMode
import com.zyna.app.data.security.MatrixSessionSecurityAction
import com.zyna.app.ui.app.AppTab
import com.zyna.app.ui.chat.theme.ChatBubbleTheme
import com.zyna.app.ui.theme.AppThemeMode

data class ZynaAppActions(
    val matrixMediaLoader: MatrixMediaLoader?,
    val audioPlaybackController: AudioPlaybackController?,
    val voiceRecorderController: VoiceRecorderController?,
    val onLogin: (homeserver: String, username: String, password: String) -> Unit,
    val onSessionSecurityAction: (MatrixSessionSecurityAction) -> Unit,
    val onSelectTab: (AppTab) -> Unit,
    val onNavigateBack: () -> Boolean,
    val onOpenRoom: (MatrixRoomSummary) -> Unit,
    val onContactsSearchQueryChanged: (String) -> Unit,
    val onOpenUserProfile: (userId: String, displayName: String?, avatarUrl: String?) -> Unit,
    val onOpenContactChat: (MatrixContact) -> Unit,
    val onCallContact: (MatrixContact) -> Unit,
    val onOpenUserProfileChat: () -> Unit,
    val onCallUserProfile: () -> Unit,
    val onRefreshUserProfile: () -> Unit,
    val onOpenCallHistoryRoom: (MatrixRtcCallHistoryItem) -> Unit,
    val onCallHistoryItem: (MatrixRtcCallHistoryItem) -> Unit,
    val onConsumePendingNativeMatrixRtcCallLaunch: (Long) -> Unit,
    val onForwardRoomSelected: (MatrixRoomSummary) -> Unit,
    val onCancelForwardPicker: () -> Unit,
    val onStartNativeMatrixRtcCall: (roomId: String, roomName: String) -> Unit,
    val onCloseChat: () -> Unit,
    val onOpenRoomDetails: () -> Unit,
    val onLoadOlderChatMessages: () -> Unit,
    val onLoadNewerChatMessages: () -> Unit,
    val onJumpToChatLiveEdge: () -> Unit,
    val onSendChatMessage: (String) -> Boolean,
    val onAttachPhotos: () -> Unit,
    val onStartVoiceRecording: () -> Boolean,
    val onStopVoiceRecording: () -> Unit,
    val onCancelVoiceRecording: () -> Unit,
    val onFinishVoiceRecordingForSend: () -> Boolean,
    val onSendVoiceRecording: () -> Boolean,
    val onToggleVoicePreviewPlayback: () -> Unit,
    val onReplyToMessage: (MatrixReplyInfo) -> Unit,
    val onReplyHeaderClicked: (String) -> Unit,
    val onCancelReply: () -> Unit,
    val onEditMessage: (MatrixEditTarget) -> Unit,
    val onCancelEdit: () -> Unit,
    val onForwardMessage: (MatrixForwardTarget) -> Unit,
    val onCancelForward: () -> Unit,
    val onToggleReaction: (messageId: String, reactionKey: String) -> Unit,
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
    val onOpenProfileSettings: () -> Unit,
    val onOpenEditProfile: () -> Unit,
    val onRefreshOwnProfile: () -> Unit,
    val onOwnProfileDisplayNameChanged: (String) -> Unit,
    val onPickOwnProfileAvatar: (Long) -> Unit,
    val onRemoveOwnProfileAvatar: () -> Unit,
    val onSaveOwnProfile: () -> Unit,
    val onOpenChatThemeSettings: () -> Unit,
    val onOpenSessionSecurity: () -> Unit,
    val onSelectChatBubbleTheme: (String) -> Unit,
    val onSelectAppThemeMode: (AppThemeMode) -> Unit,
    val onSelectPresenceProvider: (PresenceProviderMode) -> Unit,
    val onLogoutRequested: () -> Unit,
    val onLogoutConfirmed: () -> Unit,
    val onLogoutCancelled: () -> Unit
)

data class ZynaRootPreferences(
    val chatBubbleTheme: ChatBubbleTheme,
    val appThemeMode: AppThemeMode,
    val presenceProvider: PresenceProviderMode
)
