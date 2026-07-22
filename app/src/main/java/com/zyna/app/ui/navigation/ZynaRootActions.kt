package com.zyna.app.ui.navigation

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixUserProfile
import com.zyna.app.data.media.AudioPlaybackController
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.media.VoiceRecorderController
import com.zyna.app.data.presence.PresenceProviderMode
import com.zyna.app.data.security.MatrixSessionSecurityAction
import com.zyna.app.ui.app.AppTab
import com.zyna.app.ui.chat.theme.ChatBubbleTheme
import com.zyna.app.ui.theme.AppThemeMode

data class ZynaRootActions(
    val app: AppActions,
    val navigation: NavigationActions,
    val contacts: ContactsFeatureActions,
    val calls: CallsFeatureActions,
    val rooms: RoomsFeatureActions,
    val roomDetails: RoomDetailsFeatureActions,
    val roomProfileEditor: RoomProfileEditorActions,
    val roomMembers: RoomMembersFeatureActions,
    val inviteMembers: InviteMembersFeatureActions,
    val chat: ChatFeatureActions,
    val profile: ProfileFeatureActions,
    val settings: SettingsFeatureActions
)

data class AppActions(
    val onLogin: (homeserver: String, username: String, password: String) -> Unit,
    val onSessionSecurityAction: (MatrixSessionSecurityAction) -> Unit
)

data class NavigationActions(
    val onSelectTab: (AppTab) -> Unit,
    val onNavigateBack: () -> Boolean
)

data class ContactsFeatureActions(
    val onSearchQueryChanged: (String) -> Unit,
    val onOpenChat: (MatrixContact) -> Unit,
    val onCall: (MatrixContact) -> Unit
)

data class CallsFeatureActions(
    val onOpenHistoryRoom: (MatrixRtcCallHistoryItem) -> Unit,
    val onCallHistoryItem: (MatrixRtcCallHistoryItem) -> Unit,
    val onConsumePendingLaunch: (Long) -> Unit,
    val onStart: (roomId: String, roomName: String) -> Unit
)

data class RoomsFeatureActions(
    val onOpenRoom: (MatrixRoomSummary) -> Unit,
    val onForwardRoomSelected: (MatrixRoomSummary) -> Unit
)

data class RoomDetailsFeatureActions(
    val onRefresh: () -> Unit,
    val onOpenProfileEditor: () -> Unit,
    val onOpenMembers: () -> Unit,
    val onOpenInviteMembers: () -> Unit
)

data class RoomProfileEditorActions(
    val onDisplayNameChanged: (String) -> Unit,
    val onPickAvatar: (Long) -> Unit,
    val onRemoveAvatar: () -> Unit,
    val onSave: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onCancelDiscard: () -> Unit
)

data class RoomMembersFeatureActions(
    val onRetry: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onOpenInviteMembers: () -> Unit
)

data class InviteMembersFeatureActions(
    val onRetryPreparation: () -> Unit,
    val onRetrySearch: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onToggleSelection: (MatrixUserProfile) -> Unit,
    val onSend: () -> Unit
)

data class ChatFeatureActions(
    val navigation: ChatNavigationActions,
    val timeline: ChatTimelineActions,
    val composer: ChatComposerActions,
    val messages: ChatMessageActions
)

data class ChatNavigationActions(
    val onClose: () -> Unit,
    val onOpenRoomDetails: () -> Unit
)

data class ChatTimelineActions(
    val onLoadOlder: () -> Unit,
    val onLoadNewer: () -> Unit,
    val onJumpToLiveEdge: () -> Unit,
    val onReplyHeaderClicked: (String) -> Unit,
    val onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit,
    val onJumpTargetConsumed: (String) -> Unit,
    val onScrollToLiveEdgeConsumed: () -> Unit
)

data class ChatComposerActions(
    val onSendMessage: (String) -> Boolean,
    val onAttachPhotos: () -> Unit,
    val onStartVoiceRecording: () -> Boolean,
    val onStopVoiceRecording: () -> Unit,
    val onCancelVoiceRecording: () -> Unit,
    val onFinishVoiceRecordingForSend: () -> Boolean,
    val onSendVoiceRecording: () -> Boolean,
    val onToggleVoicePreviewPlayback: () -> Unit,
    val onReplyToMessage: (MatrixReplyInfo) -> Unit,
    val onCancelReply: () -> Unit,
    val onEditMessage: (MatrixEditTarget) -> Unit,
    val onCancelEdit: () -> Unit,
    val onForwardMessage: (MatrixForwardTarget) -> Unit,
    val onCancelForward: () -> Unit
)

data class ChatMessageActions(
    val onToggleReaction: (messageId: String, reactionKey: String) -> Unit,
    val onRetryOutgoingEnvelope: (String) -> Unit,
    val onDiscardOutgoingEnvelope: (String) -> Unit,
    val onRedactMessage: (String) -> Unit,
    val onRedactMessages: (List<String>) -> Unit,
    val onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit
)

data class ProfileFeatureActions(
    val user: UserProfileActions,
    val own: OwnProfileActions
)

data class UserProfileActions(
    val onOpen: (userId: String, displayName: String?, avatarUrl: String?) -> Unit,
    val onOpenChat: () -> Unit,
    val onCall: () -> Unit,
    val onRefresh: () -> Unit
)

data class OwnProfileActions(
    val onOpenSettings: () -> Unit,
    val onOpenEdit: () -> Unit,
    val onRefresh: () -> Unit,
    val onDisplayNameChanged: (String) -> Unit,
    val onPickAvatar: (Long) -> Unit,
    val onRemoveAvatar: () -> Unit,
    val onSave: () -> Unit,
    val onConfirmEditExit: () -> Unit,
    val onCancelEditExit: () -> Unit
)

data class SettingsFeatureActions(
    val onOpenChatTheme: () -> Unit,
    val onOpenSessionSecurity: () -> Unit,
    val onSelectChatBubbleTheme: (String) -> Unit,
    val onSelectAppThemeMode: (AppThemeMode) -> Unit,
    val onSelectPresenceProvider: (PresenceProviderMode) -> Unit,
    val onLogoutRequested: () -> Unit,
    val onLogoutConfirmed: () -> Unit,
    val onLogoutCancelled: () -> Unit
)

data class ZynaRenderDependencies(
    val matrixMediaLoader: MatrixMediaLoader?,
    val audioPlaybackController: AudioPlaybackController?,
    val voiceRecorderController: VoiceRecorderController?
)

data class ZynaRootPreferences(
    val chatBubbleTheme: ChatBubbleTheme,
    val appThemeMode: AppThemeMode,
    val presenceProvider: PresenceProviderMode
)
