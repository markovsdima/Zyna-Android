package com.zyna.app.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.BuildConfig
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.media.AudioPlaybackController
import com.zyna.app.data.media.AudioPlaybackSnapshot
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.media.VoiceRecorderController
import com.zyna.app.data.media.VoiceRecorderState
import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixImageInfo
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.ui.app.ChatCallBannerState
import com.zyna.app.ui.chat.render.MessageCellView
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageEditPreview
import com.zyna.app.ui.chat.render.MessageForwardPreview
import com.zyna.app.ui.chat.render.MessageReactionRenderModel
import com.zyna.app.ui.chat.render.PhotoGroupLayout
import com.zyna.app.ui.chat.render.MessageReplyPreview
import com.zyna.app.ui.chat.render.MessageRenderModel
import com.zyna.app.ui.chat.render.MessageRenderTheme
import com.zyna.app.ui.chat.render.RenderDeliveryState
import com.zyna.app.ui.chat.theme.ChatBubbleTheme
import com.zyna.app.ui.chat.theme.ChatBubbleThemes
import com.zyna.app.ui.chat.viewer.PhotoViewerLayer
import com.zyna.app.ui.chat.viewer.PhotoViewerOpenRequest
import com.zyna.app.ui.glass.ChatTeleportDirection
import com.zyna.app.ui.glass.GlassComposerPreview
import com.zyna.app.ui.glass.GlassVoiceComposerState
import com.zyna.app.ui.glass.GlassChatLayout
import com.zyna.app.ui.glass.GlassPalette
import com.zyna.app.ui.glass.RootGlassLayerCoordinator
import com.zyna.app.util.ZynaPerfLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ChatScreenViewState(
    val roomName: String,
    val roomId: String,
    val roomSubtitle: String,
    val messages: List<MatrixChatMessage>,
    val windowChangeOrigin: TimelineWindowChangeOrigin,
    val isLoading: Boolean,
    val isLoadingOlder: Boolean,
    val canLoadOlder: Boolean,
    val canLoadNewer: Boolean,
    val isAtLiveEdge: Boolean,
    val scrollToLiveEdgeRequested: Boolean,
    val errorMessage: String?,
    val isSendingMessage: Boolean,
    val sendErrorMessage: String?,
    val replyTarget: MatrixReplyInfo?,
    val editTarget: MatrixEditTarget?,
    val forwardTarget: MatrixForwardTarget?,
    val matrixMediaLoader: MatrixMediaLoader?,
    val audioPlaybackController: AudioPlaybackController?,
    val voiceRecorderController: VoiceRecorderController?,
    val jumpTargetEventId: String?,
    val callBanner: ChatCallBannerState?,
    val chatBubbleTheme: ChatBubbleTheme
)

data class ChatScreenViewActions(
    val onRefresh: () -> Unit,
    val onStartCall: () -> Unit,
    val onBack: () -> Unit,
    val onOpenRoomDetails: () -> Unit,
    val onLoadOlder: () -> Unit,
    val onLoadNewer: () -> Unit,
    val onJumpToLiveEdge: () -> Unit,
    val onSendMessage: (String) -> Boolean,
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
    val onJumpTargetConsumed: (String) -> Unit,
    val onScrollToLiveEdgeConsumed: () -> Unit
)

internal class ChatScreenView(
    context: Context,
    rootGlassOwnerKey: String,
    rootGlassCoordinator: RootGlassLayerCoordinator,
    private val rootOverlayHost: FrameLayout?
) : FrameLayout(context) {
    private val initStart = ZynaPerfLog.start()
    private val density = resources.displayMetrics.density
    private var isDarkTheme = resources.configuration.isNightMode()
    private var chatBubbleTheme = ChatBubbleThemes.fallback
    private var nativeColors = nativeChatColors(isDarkTheme, chatBubbleTheme)
    private var palette = nativeColors.palette
    private var messageTheme = nativeColors.messageTheme
    private var sendErrorColor = nativeColors.sendError
    private var statusTopInset = 0
    private var photoViewerLayer: PhotoViewerLayer? = null
    private var currentRoomId: String? = null
    private var currentAudioPlaybackController: AudioPlaybackController? = null
    private var audioPlaybackListenerHandle: AutoCloseable? = null
    private var currentVoiceRecorderController: VoiceRecorderController? = null
    private var voiceRecorderListenerHandle: AutoCloseable? = null
    private var latestAudioPlaybackSnapshot = AudioPlaybackSnapshot()
    private var latestVoiceRecorderState: VoiceRecorderState = VoiceRecorderState.Idle

    fun canReuseForRoom(roomId: String): Boolean {
        return currentRoomId == null || currentRoomId == roomId
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(palette.background)
    }
    private val backButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Back"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(nativeColors.actionText)
        isClickable = true
        isFocusable = true
    }
    private val titleColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(nativeColors.titleText)
    }
    private val subtitleText = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 12f
        setTextColor(nativeColors.subtitleText)
    }
    private val refreshButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(nativeColors.actionText)
        isClickable = true
        isFocusable = true
    }
    private val callButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Call"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(nativeColors.actionText)
        isClickable = true
        isFocusable = true
    }
    private val activeCallBanner = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        isClickable = true
        isFocusable = true
    }
    private val activeCallAccent = View(context)
    private val activeCallTitle = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val activeCallAction = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val contentFrame = FrameLayout(context)
    private val chatLayoutStart = ZynaPerfLog.start()
    private val chatLayout = GlassChatLayout(
        context = context,
        rootGlassOwnerKey = rootGlassOwnerKey,
        rootGlassCoordinator = rootGlassCoordinator
    ).also {
        ZynaPerfLog.end(chatLayoutStart, "chatView.createGlassChatLayout")
    }
    private val errorView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        includeFontPadding = true
        setTextColor(sendErrorColor)
        visibility = View.GONE
    }

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(palette.background)

        addView(
            root,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(NATIVE_TOP_BAR_HEIGHT_DP)
            )
        )
        topBar.addView(
            backButton,
            LinearLayout.LayoutParams(
                dp(72),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        topBar.addView(
            titleColumn,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        titleColumn.addView(
            titleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        titleColumn.addView(
            subtitleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        topBar.addView(
            callButton,
            LinearLayout.LayoutParams(
                dp(72),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        topBar.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                dp(96),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activeCallBanner.setPadding(dp(16), 0, dp(16), 0)
        activeCallBanner.addView(
            activeCallAccent,
            LinearLayout.LayoutParams(
                dp(10),
                dp(10)
            )
        )
        activeCallBanner.addView(
            activeCallTitle,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )
        activeCallBanner.addView(
            activeCallAction,
            LinearLayout.LayoutParams(
                dp(80),
                dp(34)
            )
        )
        root.addView(
            activeCallBanner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ACTIVE_CALL_BANNER_HEIGHT_DP)
            )
        )
        root.addView(
            contentFrame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val adapterStart = ZynaPerfLog.start()
        chatLayout.recyclerView.adapter = ChatMessageAdapter(
            messageTheme = messageTheme,
            matrixMediaLoader = null,
            onContextMenuPreviewRequested = chatLayout::beginMessageContextMenuGesture,
            onContextMenuRequested = chatLayout::showMessageContextMenu,
            onContextMenuGestureEvent = chatLayout::handleMessageContextGestureEvent,
            onReplyHeaderClicked = {},
            onToggleReaction = { _, _ -> },
            onPhotoViewerRequested = {},
            onVoicePlaybackRequested = { _, _ -> }
        )
        chatLayout.recyclerView.addOnScrollListener(MediaPrefetchScrollListener)
        ZynaPerfLog.end(adapterStart, "chatView.initAdapter")
        contentFrame.addView(
            chatLayout,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            errorView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nextTopInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            if (statusTopInset != nextTopInset) {
                statusTopInset = nextTopInset
                topBar.updatePadding(top = statusTopInset)
                val params = topBar.layoutParams as LinearLayout.LayoutParams
                params.height = dp(NATIVE_TOP_BAR_HEIGHT_DP) + statusTopInset
                topBar.layoutParams = params
            }
            insets
        }
        applyNativeColors()
        ZynaPerfLog.end(initStart, "chatView.init.done")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        removePhotoViewer()
        audioPlaybackListenerHandle?.close()
        audioPlaybackListenerHandle = null
        currentAudioPlaybackController = null
        voiceRecorderListenerHandle?.close()
        voiceRecorderListenerHandle = null
        currentVoiceRecorderController = null
        super.onDetachedFromWindow()
    }

    fun handleBack(): Boolean {
        val layer = photoViewerLayer ?: return false
        layer.close(animated = true)
        return true
    }

    fun canStartNavigationBackGesture(): Boolean {
        return photoViewerLayer == null && chatLayout.canStartNavigationBackGesture()
    }

    fun render(state: ChatScreenViewState, actions: ChatScreenViewActions) {
        updateNativeThemeIfNeeded(state.chatBubbleTheme)
        val renderStart = ZynaPerfLog.start()
        ZynaPerfLog.mark {
            "chatView.render.begin roomId=${state.roomId} messages=${state.messages.size} " +
                "origin=${state.windowChangeOrigin} loading=${state.isLoading} " +
                "jump=${state.jumpTargetEventId != null} liveReq=${state.scrollToLiveEdgeRequested}"
        }
        titleText.text = state.roomName
        subtitleText.text = state.roomSubtitle
        backButton.setOnClickListener { actions.onBack() }
        titleColumn.contentDescription = "${state.roomName}. ${state.roomSubtitle}"
        titleColumn.setOnClickListener { actions.onOpenRoomDetails() }
        callButton.setOnClickListener { actions.onStartCall() }
        renderActiveCallBanner(state.callBanner, actions)
        refreshButton.text = if (state.isLoading) "Loading" else "Refresh"
        refreshButton.isEnabled = !state.isLoading
        refreshButton.alpha = if (state.isLoading) 0.54f else 1f
        refreshButton.setOnClickListener { actions.onRefresh() }

        val errorMessage = state.errorMessage
        if (errorMessage != null) {
            chatLayout.visibility = View.GONE
            errorView.text = errorMessage
            errorView.visibility = View.VISIBLE
            ZynaPerfLog.end(
                renderStart,
                "chatView.render.error"
            ) {
                "roomId=${state.roomId} message=${errorMessage.take(80)}"
            }
            return
        }

        errorView.visibility = View.GONE
        chatLayout.visibility = View.VISIBLE
        renderChatLayout(state, actions)
        ZynaPerfLog.end(
            renderStart,
            "chatView.render.done"
        ) {
            "roomId=${state.roomId} messages=${state.messages.size}"
        }
    }

    private fun renderActiveCallBanner(
        banner: ChatCallBannerState?,
        actions: ChatScreenViewActions
    ) {
        if (banner == null) {
            activeCallBanner.visibility = View.GONE
            activeCallBanner.setOnClickListener(null)
            activeCallAction.setOnClickListener(null)
            return
        }

        activeCallTitle.text = banner.title
        activeCallAction.text = banner.actionLabel
        activeCallBanner.visibility = View.VISIBLE
        activeCallBanner.contentDescription = "${banner.title}. ${banner.actionLabel}"
        activeCallBanner.setOnClickListener { actions.onStartCall() }
        activeCallAction.setOnClickListener { actions.onStartCall() }
    }

    private fun renderChatLayout(
        state: ChatScreenViewState,
        actions: ChatScreenViewActions
    ) {
        val layoutRenderStart = ZynaPerfLog.start()
        chatLayout.setPalette(palette)
        chatLayout.onLoadOlderMessages = actions.onLoadOlder
        chatLayout.onLoadNewerMessages = actions.onLoadNewer
        chatLayout.onScrollToLiveEdge = actions.onJumpToLiveEdge
        chatLayout.onReplyToMessage = { target ->
            actions.onReplyToMessage(target.toMatrixReplyInfo())
        }
        chatLayout.onEditMessage = { target ->
            actions.onEditMessage(target.toMatrixEditTarget())
        }
        chatLayout.onForwardMessage = { target ->
            actions.onForwardMessage(target.toMatrixForwardTarget())
        }
        chatLayout.onRetryOutgoingEnvelope = actions.onRetryOutgoingEnvelope
        chatLayout.onDiscardOutgoingEnvelope = actions.onDiscardOutgoingEnvelope
        chatLayout.onRedactMessage = actions.onRedactMessage
        chatLayout.onRedactMessages = actions.onRedactMessages
        chatLayout.onToggleReaction = actions.onToggleReaction
        chatLayout.onDebugMarkOutgoingEnvelopeFailed = actions.onDebugMarkOutgoingEnvelopeFailed
        chatLayout.onEvaluateVisibleReadReceiptCandidate = {
            chatLayout.evaluateVisibleReadReceiptCandidate(state.isAtLiveEdge) { eventId, canEstablishBaseline ->
                actions.onVisibleReadReceiptCandidate(state.roomId, eventId, canEstablishBaseline)
            }
        }
        chatLayout.inputBar.onSendMessage = actions.onSendMessage
        chatLayout.inputBar.onAttachClicked = actions.onAttachPhotos
        chatLayout.inputBar.onVoiceRecordClicked = actions.onStartVoiceRecording
        chatLayout.inputBar.onVoiceStopClicked = actions.onStopVoiceRecording
        chatLayout.inputBar.onVoiceCancelClicked = actions.onCancelVoiceRecording
        chatLayout.inputBar.onVoiceFinishForSendClicked = actions.onFinishVoiceRecordingForSend
        chatLayout.inputBar.onVoiceSendClicked = actions.onSendVoiceRecording
        chatLayout.inputBar.onVoicePreviewPlaybackClicked = actions.onToggleVoicePreviewPlayback
        chatLayout.inputBar.onPreviewCancelled = {
            if (state.forwardTarget != null) actions.onCancelForward() else actions.onCancelReply()
        }
        chatLayout.inputBar.allowEmptySend = state.forwardTarget != null
        chatLayout.inputBar.setPreview(
            state.forwardTarget?.toComposerPreview() ?: state.replyTarget?.toComposerPreview()
        )
        chatLayout.inputBar.onEditCancelled = actions.onCancelEdit
        chatLayout.inputBar.setEditDraft(state.editTarget?.eventId, state.editTarget?.body)
        chatLayout.inputBar.setEditPreview(state.editTarget?.toComposerPreview())
        chatLayout.setPaginationState(
            isLoadingOlder = state.isLoadingOlder,
            canLoadOlder = state.canLoadOlder &&
                !state.isLoading &&
                state.jumpTargetEventId == null &&
                !state.scrollToLiveEdgeRequested,
            canLoadNewer = state.canLoadNewer &&
                !state.isLoading &&
                state.jumpTargetEventId == null &&
                !state.scrollToLiveEdgeRequested
        )
        chatLayout.setLiveEdgeState(state.isAtLiveEdge)

        val recyclerView = chatLayout.recyclerView
        val adapter = recyclerView.adapter as ChatMessageAdapter
        val shouldKeepCurrentMessagesForLoading = state.isLoading &&
            state.messages.isEmpty() &&
            currentRoomId == state.roomId &&
            adapter.itemCount > 0
        chatLayout.setEmptyState(
            isEmpty = state.messages.isEmpty() && !shouldKeepCurrentMessagesForLoading,
            isLoading = state.isLoading
        )
        chatLayout.setComposerState(
            isSending = state.isSendingMessage,
            errorMessage = state.sendErrorMessage,
            errorColor = sendErrorColor
        )
        adapter.onContextMenuPreviewRequested = chatLayout::beginMessageContextMenuGesture
        adapter.onContextMenuRequested = chatLayout::showMessageContextMenu
        adapter.onContextMenuGestureEvent = chatLayout::handleMessageContextGestureEvent
        adapter.onReplyHeaderClicked = actions.onReplyHeaderClicked
        adapter.onToggleReaction = actions.onToggleReaction
        adapter.onPhotoViewerRequested = { request ->
            openPhotoViewer(request, state.matrixMediaLoader)
        }
        adapter.onVoicePlaybackRequested = { messageId, audioInfo ->
            state.audioPlaybackController?.toggle(messageId, audioInfo)
        }
        adapter.matrixMediaLoader = state.matrixMediaLoader
        bindAudioPlaybackController(state.audioPlaybackController)
        bindVoiceRecorderController(state.voiceRecorderController)
        val presentationStart = ZynaPerfLog.start()
        val displayedMessages = state.messages
            .asReversed()
            .withMediaGroupPresentation(
                hasNewerBoundary = state.canLoadNewer,
                hasOlderBoundary = state.canLoadOlder
            )
        ZynaPerfLog.end(
            presentationStart,
            "chatView.mediaPresentation"
        ) {
            "roomId=${state.roomId} input=${state.messages.size} displayed=${displayedMessages.size}"
        }
        val previousNewestMessageId = adapter.currentList.firstOrNull()?.id
        val nextNewestMessageId = displayedMessages.firstOrNull()?.id
        val hasNewerMessage = previousNewestMessageId != null &&
            nextNewestMessageId != null &&
            previousNewestMessageId != nextNewestMessageId
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition()
            ?: RecyclerView.NO_POSITION
        val wasAtBottom = firstVisiblePosition != RecyclerView.NO_POSITION &&
            firstVisiblePosition <= NEWEST_EDGE_THRESHOLD
        val viewportAnchor = if (
            !wasAtBottom &&
            layoutManager != null &&
            recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
            state.windowChangeOrigin == TimelineWindowChangeOrigin.DATABASE_PAGINATION
        ) {
            recyclerView.findViewportAnchor(adapter)
        } else null
        val wasEmpty = adapter.itemCount == 0
        val themeChanged = adapter.messageTheme != messageTheme
        val jumpTargetPosition = state.jumpTargetEventId?.let { targetEventId ->
            displayedMessages.indexOfFirst { message ->
                message.eventId == targetEventId || message.id == targetEventId
            }.takeIf { it != -1 }
        }
        val shouldApplyJumpTarget = jumpTargetPosition != null &&
            state.windowChangeOrigin == TimelineWindowChangeOrigin.JUMP
        val shouldApplyScrollToLiveEdge = state.scrollToLiveEdgeRequested &&
            state.windowChangeOrigin == TimelineWindowChangeOrigin.JUMP &&
            displayedMessages.isNotEmpty()
        val visibleCenterPosition = layoutManager?.visibleCenterAdapterPosition()
        val jumpDistance = jumpTargetPosition?.let { targetPosition ->
            abs(targetPosition - (visibleCenterPosition ?: targetPosition))
        } ?: 0
        val isSameWindowJump = adapter.currentList.isSameMessageWindow(displayedMessages)
        val shouldTeleportJump = shouldApplyJumpTarget &&
            (!isSameWindowJump || jumpDistance > LOCAL_JUMP_SMOOTH_SCROLL_MAX_DISTANCE)
        val shouldSmoothLocalJump = shouldApplyJumpTarget &&
            isSameWindowJump &&
            !shouldTeleportJump
        val teleportDirection = if (
            jumpTargetPosition != null &&
            shouldTeleportJump &&
            !wasEmpty
        ) {
            inferTeleportDirection(
                adapter = adapter,
                layoutManager = layoutManager,
                displayedMessages = displayedMessages,
                targetPosition = jumpTargetPosition
            )
        } else null
        val didBeginTeleport = teleportDirection?.let(chatLayout::beginSnapshotTeleport) == true
        val didBeginLiveEdgeTeleport = if (
            shouldApplyScrollToLiveEdge &&
            !wasEmpty &&
            !didBeginTeleport
        ) {
            chatLayout.beginSnapshotTeleport(ChatTeleportDirection.TO_NEWER)
        } else {
            false
        }
        if (state.jumpTargetEventId != null) {
            logChatTeleport(
                "native ui update origin=${state.windowChangeOrigin} " +
                    "targetFound=${jumpTargetPosition != null} " +
                    "targetPosition=$jumpTargetPosition " +
                    "shouldApply=$shouldApplyJumpTarget " +
                    "sameWindow=$isSameWindowJump distance=$jumpDistance " +
                    "teleport=$shouldTeleportJump smooth=$shouldSmoothLocalJump " +
                    "oldCount=${adapter.itemCount} newCount=${displayedMessages.size} " +
                    "firstVisible=$firstVisiblePosition wasEmpty=$wasEmpty " +
                    "direction=$teleportDirection didBegin=$didBeginTeleport"
            )
        }
        if (state.scrollToLiveEdgeRequested) {
            logChatTeleport(
                "native live ui update origin=${state.windowChangeOrigin} " +
                    "shouldApply=$shouldApplyScrollToLiveEdge " +
                    "oldCount=${adapter.itemCount} newCount=${displayedMessages.size} " +
                    "firstVisible=$firstVisiblePosition didBegin=$didBeginLiveEdgeTeleport"
            )
        }
        currentRoomId = state.roomId
        if (shouldKeepCurrentMessagesForLoading) {
            ZynaPerfLog.mark {
                "chatView.keepCurrentListForLoading roomId=${state.roomId} current=${adapter.itemCount}"
            }
            ZynaPerfLog.end(
                layoutRenderStart,
                "chatView.renderChatLayout.done"
            ) {
                "roomId=${state.roomId} displayed=${adapter.itemCount} kept=true"
            }
            return
        }
        adapter.messageTheme = messageTheme
        val submitStart = ZynaPerfLog.start()
        adapter.submitList(displayedMessages) {
            ZynaPerfLog.end(
                submitStart,
                "chatView.submitList.commit"
            ) {
                "roomId=${state.roomId} displayed=${displayedMessages.size} " +
                    "wasEmpty=$wasEmpty children=${recyclerView.childCount}"
            }
            if (displayedMessages.isNotEmpty()) {
                if (shouldApplyScrollToLiveEdge) {
                    recyclerView.stopScroll()
                    chatLayout.scrollToBottom(animated = false)
                    if (didBeginLiveEdgeTeleport) {
                        recyclerView.runAfterNextPreDraw {
                            chatLayout.completeSnapshotTeleport {
                                actions.onScrollToLiveEdgeConsumed()
                            }
                        }
                    } else {
                        actions.onScrollToLiveEdgeConsumed()
                    }
                } else if (
                    state.jumpTargetEventId != null &&
                    jumpTargetPosition != null &&
                    shouldApplyJumpTarget
                ) {
                    logChatTeleport(
                        "native commit scroll targetPosition=$jumpTargetPosition " +
                            "didBegin=$didBeginTeleport childCount=${recyclerView.childCount}"
                    )
                    recyclerView.stopScroll()
                    if (didBeginTeleport) {
                        layoutManager?.scrollToPositionWithOffset(
                            jumpTargetPosition,
                            recyclerView.jumpTargetScrollOffset()
                        )
                        recyclerView.runAfterNextPreDraw {
                            logChatTeleport(
                                "native preDraw complete targetPosition=$jumpTargetPosition " +
                                    "childCount=${recyclerView.childCount}"
                            )
                            chatLayout.completeSnapshotTeleport {
                                chatLayout.highlightMessageAtAdapterPosition(jumpTargetPosition)
                                actions.onJumpTargetConsumed(state.jumpTargetEventId)
                            }
                        }
                    } else {
                        if (shouldSmoothLocalJump) {
                            val scrollDelayMillis = jumpDistance.localJumpScrollDelayMillis()
                            recyclerView.smoothScrollToPosition(jumpTargetPosition)
                            chatLayout.highlightMessageAtAdapterPosition(
                                position = jumpTargetPosition,
                                delayMillis = scrollDelayMillis + LOCAL_JUMP_HIGHLIGHT_DELAY_MS
                            )
                            recyclerView.postDelayed(
                                { actions.onJumpTargetConsumed(state.jumpTargetEventId) },
                                scrollDelayMillis
                            )
                        } else {
                            layoutManager?.scrollToPositionWithOffset(
                                jumpTargetPosition,
                                recyclerView.jumpTargetScrollOffset()
                            )
                            chatLayout.highlightMessageAtAdapterPosition(jumpTargetPosition)
                            actions.onJumpTargetConsumed(state.jumpTargetEventId)
                        }
                    }
                } else if (
                    wasEmpty ||
                    (
                        wasAtBottom &&
                            hasNewerMessage &&
                            state.windowChangeOrigin != TimelineWindowChangeOrigin.DATABASE_PAGINATION
                        )
                ) {
                    chatLayout.scrollToBottom(animated = false)
                } else if (viewportAnchor != null) {
                    val anchorPosition = displayedMessages.indexOfFirst { it.id == viewportAnchor.messageId }
                    if (anchorPosition != -1) {
                        layoutManager?.scrollToPositionWithOffset(
                            anchorPosition,
                            viewportAnchor.top
                        )
                    }
                }
            }
            chatLayout.invalidateGlassContent()
            chatLayout.prefetchOlderMessagesIfNeeded()
            recyclerView.post {
                recyclerView.prefetchMediaAroundVisibleWindow()
            }
            chatLayout.scheduleVisibleReadReceiptCandidateEvaluation(
                delayMillis = READ_RECEIPT_CONTENT_UPDATE_DELAY_MS
            )
        }
        if (themeChanged) {
            val notifyStart = ZynaPerfLog.start()
            adapter.notifyDataSetChanged()
            ZynaPerfLog.end(notifyStart, "chatView.notifyDataSetChanged") {
                "roomId=${state.roomId}"
            }
        }
        ZynaPerfLog.end(
            submitStart,
            "chatView.submitList.call"
        ) {
            "roomId=${state.roomId} displayed=${displayedMessages.size}"
        }
        ZynaPerfLog.end(
            layoutRenderStart,
            "chatView.renderChatLayout.done"
        ) {
            "roomId=${state.roomId} displayed=${displayedMessages.size}"
        }
    }

    private fun openPhotoViewer(request: PhotoViewerOpenRequest, imageLoader: MatrixMediaLoader?) {
        imageLoader ?: return
        val start = ZynaPerfLog.start()
        removePhotoViewer()
        val layer = PhotoViewerLayer(context, imageLoader).apply {
            onDismissed = {
                removePhotoViewer()
            }
            open(request)
        }
        photoViewerLayer = layer
        val host = rootOverlayHost ?: this
        host.addView(
            layer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        layer.bringToFront()
        ZynaPerfLog.end(
            start,
            "chatView.openPhotoViewer"
        ) {
            "messageId=${request.messageId} items=${request.items.size}"
        }
    }

    private fun removePhotoViewer() {
        val layer = photoViewerLayer ?: return
        photoViewerLayer = null
        (layer.parent as? ViewGroup)?.removeView(layer)
    }

    private fun bindAudioPlaybackController(controller: AudioPlaybackController?) {
        if (currentAudioPlaybackController === controller) {
            return
        }
        audioPlaybackListenerHandle?.close()
        audioPlaybackListenerHandle = null
        currentAudioPlaybackController = controller
        val adapter = chatLayout.recyclerView.adapter as? ChatMessageAdapter
        if (controller == null) {
            latestAudioPlaybackSnapshot = AudioPlaybackSnapshot()
            adapter?.setAudioPlaybackSnapshot(AudioPlaybackSnapshot())
            updateInputBarVoiceState()
            return
        }
        audioPlaybackListenerHandle = controller.addListener { snapshot ->
            latestAudioPlaybackSnapshot = snapshot
            (chatLayout.recyclerView.adapter as? ChatMessageAdapter)
                ?.setAudioPlaybackSnapshot(snapshot)
            updateInputBarVoiceState()
        }
    }

    private fun bindVoiceRecorderController(controller: VoiceRecorderController?) {
        if (currentVoiceRecorderController === controller) {
            return
        }
        voiceRecorderListenerHandle?.close()
        voiceRecorderListenerHandle = null
        currentVoiceRecorderController = controller
        if (controller == null) {
            latestVoiceRecorderState = VoiceRecorderState.Idle
            updateInputBarVoiceState()
            return
        }
        voiceRecorderListenerHandle = controller.addListener { state ->
            latestVoiceRecorderState = state
            updateInputBarVoiceState()
        }
    }

    private fun updateInputBarVoiceState() {
        chatLayout.inputBar.setVoiceComposerState(
            latestVoiceRecorderState.toGlassVoiceState(latestAudioPlaybackSnapshot)
        )
    }

    private fun updateNativeThemeIfNeeded(nextBubbleTheme: ChatBubbleTheme) {
        val nextDarkTheme = resources.configuration.isNightMode()
        if (nextDarkTheme == isDarkTheme && nextBubbleTheme == chatBubbleTheme) {
            return
        }
        isDarkTheme = nextDarkTheme
        chatBubbleTheme = nextBubbleTheme
        nativeColors = nativeChatColors(nextDarkTheme, nextBubbleTheme)
        palette = nativeColors.palette
        messageTheme = nativeColors.messageTheme
        sendErrorColor = nativeColors.sendError
        applyNativeColors()
    }

    private fun applyNativeColors() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(nativeColors.actionText)
        callButton.setTextColor(nativeColors.actionText)
        titleText.setTextColor(nativeColors.titleText)
        subtitleText.setTextColor(nativeColors.subtitleText)
        refreshButton.setTextColor(nativeColors.actionText)
        activeCallBanner.setBackgroundColor(nativeColors.callBannerBackground)
        activeCallAccent.background = roundedDrawable(
            color = nativeColors.callBannerAccent,
            radiusPx = dp(5)
        )
        activeCallTitle.setTextColor(nativeColors.callBannerText)
        activeCallAction.setTextColor(nativeColors.callBannerActionText)
        activeCallAction.background = roundedDrawable(
            color = nativeColors.callBannerActionBackground,
            radiusPx = dp(17)
        )
        errorView.setTextColor(sendErrorColor)
        chatLayout.setPalette(palette)
    }

    private fun roundedDrawable(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }
}

private fun GlassChatLayout.evaluateVisibleReadReceiptCandidate(
    isAtLiveEdge: Boolean,
    onCandidate: (eventId: String?, canEstablishBaseline: Boolean) -> Unit
) {
    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
    val adapter = recyclerView.adapter as? ChatMessageAdapter
    if (layoutManager == null || adapter == null || adapter.itemCount == 0) {
        onCandidate(null, false)
        return
    }

    val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
    if (
        firstVisiblePosition == RecyclerView.NO_POSITION ||
        lastVisiblePosition == RecyclerView.NO_POSITION
    ) {
        onCandidate(null, false)
        return
    }

    val viewportTop = recyclerView.paddingTop
    val viewportBottom = (recyclerView.height - recyclerView.paddingBottom)
        .coerceAtLeast(viewportTop)
    val viewportHeight = viewportBottom - viewportTop
    if (viewportHeight <= 0) {
        onCandidate(null, false)
        return
    }

    val candidate = (firstVisiblePosition..lastVisiblePosition).firstNotNullOfOrNull { position ->
        val message = adapter.currentList.getOrNull(position)
            ?.takeIf { it.isReadReceiptCandidate() }
            ?: return@firstNotNullOfOrNull null
        val child = layoutManager.findViewByPosition(position)
            ?: return@firstNotNullOfOrNull null
        val visibleTop = max(child.top, viewportTop)
        val visibleBottom = min(child.bottom, viewportBottom)
        val visibleHeight = visibleBottom - visibleTop
        if (visibleHeight <= 0) {
            return@firstNotNullOfOrNull null
        }

        val maxRelevantVisibleHeight = min(child.height, viewportHeight)
        if (
            maxRelevantVisibleHeight > 0 &&
            visibleHeight.toFloat() / maxRelevantVisibleHeight >= READ_RECEIPT_VISIBILITY_THRESHOLD
        ) {
            message.eventId
        } else {
            null
        }
    }

    onCandidate(
        candidate,
        isAtLiveEdge && firstVisiblePosition <= NEWEST_EDGE_THRESHOLD
    )
}

private data class ViewportAnchor(
    val messageId: String,
    val top: Int
)

private fun inferTeleportDirection(
    adapter: ChatMessageAdapter,
    layoutManager: LinearLayoutManager?,
    displayedMessages: List<MatrixChatMessage>,
    targetPosition: Int
): ChatTeleportDirection {
    val target = displayedMessages.getOrNull(targetPosition)
        ?: return ChatTeleportDirection.TO_OLDER
    val referencePosition = layoutManager?.visibleCenterAdapterPosition()
    val reference = referencePosition?.let { adapter.currentList.getOrNull(it) }
    if (reference != null && reference.timestampMillis != target.timestampMillis) {
        return if (target.timestampMillis < reference.timestampMillis) {
            ChatTeleportDirection.TO_OLDER
        } else {
            ChatTeleportDirection.TO_NEWER
        }
    }

    return if (referencePosition != null && targetPosition < referencePosition) {
        ChatTeleportDirection.TO_NEWER
    } else {
        ChatTeleportDirection.TO_OLDER
    }
}

private fun LinearLayoutManager.visibleCenterAdapterPosition(): Int? {
    val firstVisible = findFirstVisibleItemPosition()
    val lastVisible = findLastVisibleItemPosition()
    if (
        firstVisible == RecyclerView.NO_POSITION ||
        lastVisible == RecyclerView.NO_POSITION ||
        firstVisible > lastVisible
    ) {
        return null
    }
    return firstVisible + (lastVisible - firstVisible) / 2
}

private fun List<MatrixChatMessage>.isSameMessageWindow(other: List<MatrixChatMessage>): Boolean {
    return size == other.size &&
        firstOrNull()?.id == other.firstOrNull()?.id &&
        lastOrNull()?.id == other.lastOrNull()?.id
}

private fun Int.localJumpScrollDelayMillis(): Long {
    return (LOCAL_JUMP_SCROLL_BASE_DELAY_MS + this * LOCAL_JUMP_SCROLL_PER_ITEM_DELAY_MS)
        .coerceAtMost(LOCAL_JUMP_SCROLL_MAX_DELAY_MS)
}

private fun RecyclerView.runAfterNextPreDraw(action: () -> Unit) {
    val observer = viewTreeObserver
    observer.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val currentObserver = viewTreeObserver
                if (currentObserver.isAlive) {
                    currentObserver.removeOnPreDrawListener(this)
                } else {
                    observer.removeOnPreDrawListener(this)
                }
                action()
                return true
            }
        }
    )
    invalidate()
}

private object MediaPrefetchScrollListener : RecyclerView.OnScrollListener() {
    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        recyclerView.prefetchMediaAroundVisibleWindow()
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            recyclerView.prefetchMediaAroundVisibleWindow()
        }
    }
}

private fun RecyclerView.prefetchMediaAroundVisibleWindow() {
    val adapter = adapter as? ChatMessageAdapter ?: return
    val layoutManager = layoutManager as? LinearLayoutManager ?: return
    val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
    adapter.prefetchMediaAround(
        firstVisiblePosition = firstVisiblePosition,
        lastVisiblePosition = lastVisiblePosition,
        viewportWidthPx = (width - paddingLeft - paddingRight).coerceAtLeast(1),
        density = resources.displayMetrics.density
    )
}

private fun RecyclerView.findViewportAnchor(adapter: ChatMessageAdapter): ViewportAnchor? {
    val layoutManager = layoutManager ?: return null
    val viewportTop = paddingTop
    var bestPosition = RecyclerView.NO_POSITION
    var bestTop = Int.MAX_VALUE
    var bestDistance = Int.MAX_VALUE

    for (index in 0 until childCount) {
        val child = getChildAt(index) ?: continue
        val position = layoutManager.getPosition(child)
        if (position == RecyclerView.NO_POSITION) {
            continue
        }
        val visibleTop = max(child.top, viewportTop)
        val distance = abs(visibleTop - viewportTop)
        if (distance < bestDistance || (distance == bestDistance && visibleTop < bestTop)) {
            bestDistance = distance
            bestPosition = position
            bestTop = child.top
        }
    }

    if (bestPosition == RecyclerView.NO_POSITION) {
        return null
    }

    val messageId = adapter.currentList.getOrNull(bestPosition)?.id ?: return null
    return ViewportAnchor(
        messageId = messageId,
        top = bestTop
    )
}

private data class MediaPrefetchTarget(
    val widthPx: Int,
    val heightPx: Int
)

private data class MediaPrefetchRequest(
    val imageInfo: MatrixImageInfo,
    val targetWidthPx: Int,
    val targetHeightPx: Int
)

private fun mediaPrefetchTarget(viewportWidthPx: Int, density: Float): MediaPrefetchTarget {
    val horizontalChrome = MEDIA_PREFETCH_HORIZONTAL_CHROME_DP.dpToPx(density)
    val maxMediaWidth = MEDIA_PREFETCH_MAX_WIDTH_DP.dpToPx(density)
    val maxMediaHeight = MEDIA_PREFETCH_MAX_HEIGHT_DP.dpToPx(density)
    val width = (viewportWidthPx - horizontalChrome)
        .coerceAtMost(maxMediaWidth)
        .coerceAtLeast(1)
    return MediaPrefetchTarget(
        widthPx = width,
        heightPx = maxMediaHeight.coerceAtLeast(1)
    )
}

private fun RecyclerView.jumpTargetScrollOffset(): Int {
    val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
    return paddingTop + (availableHeight * JUMP_TARGET_VIEWPORT_FRACTION).toInt()
}

private data class NativeChatColors(
    val palette: GlassPalette,
    val messageTheme: MessageRenderTheme,
    val actionText: Int,
    val titleText: Int,
    val subtitleText: Int,
    val sendError: Int,
    val callBannerBackground: Int,
    val callBannerAccent: Int,
    val callBannerText: Int,
    val callBannerActionBackground: Int,
    val callBannerActionText: Int
)

private fun nativeChatColors(
    isDarkTheme: Boolean,
    outgoingBubbleTheme: ChatBubbleTheme
): NativeChatColors {
    return if (isDarkTheme) {
        NativeChatColors(
            palette = GlassPalette(
                background = Color.rgb(18, 18, 22),
                glassTint = Color.argb(58, 48, 47, 56),
                glassTintStrong = Color.argb(82, 62, 60, 70),
                stroke = Color.argb(74, 255, 255, 255),
                text = Color.rgb(232, 225, 229),
                hint = Color.argb(184, 202, 196, 208)
            ),
            messageTheme = MessageRenderTheme(
                outgoingBubble = outgoingBubbleTheme.actionAccentColor,
                outgoingText = Color.WHITE,
                outgoingMetadata = Color.argb(222, 255, 255, 255),
                incomingBubble = Color.rgb(49, 48, 56),
                incomingText = Color.rgb(232, 225, 229),
                incomingMetadata = Color.rgb(202, 196, 208),
                outgoingBubbleGradient = outgoingBubbleTheme.outgoingGradient
            ),
            actionText = Color.rgb(208, 188, 255),
            titleText = Color.rgb(232, 225, 229),
            subtitleText = Color.rgb(202, 196, 208),
            sendError = Color.rgb(255, 180, 171),
            callBannerBackground = Color.rgb(29, 34, 30),
            callBannerAccent = Color.rgb(88, 191, 107),
            callBannerText = Color.rgb(232, 245, 234),
            callBannerActionBackground = Color.rgb(52, 168, 83),
            callBannerActionText = Color.WHITE
        )
    } else {
        NativeChatColors(
            palette = GlassPalette(
                background = Color.WHITE,
                glassTint = Color.argb(48, 255, 255, 255),
                glassTintStrong = Color.argb(61, 255, 255, 255),
                stroke = Color.argb(46, 0, 0, 0),
                text = Color.rgb(29, 27, 32),
                hint = Color.argb(153, 73, 69, 79)
            ),
            messageTheme = MessageRenderTheme(
                outgoingBubble = outgoingBubbleTheme.actionAccentColor,
                outgoingText = Color.WHITE,
                outgoingMetadata = Color.argb(222, 255, 255, 255),
                incomingBubble = Color.rgb(231, 224, 236),
                incomingText = Color.rgb(29, 27, 32),
                incomingMetadata = Color.rgb(73, 69, 79),
                outgoingBubbleGradient = outgoingBubbleTheme.outgoingGradient
            ),
            actionText = Color.rgb(33, 0, 93),
            titleText = Color.rgb(29, 27, 32),
            subtitleText = Color.rgb(73, 69, 79),
            sendError = Color.rgb(186, 26, 26),
            callBannerBackground = Color.rgb(240, 248, 241),
            callBannerAccent = Color.rgb(42, 145, 64),
            callBannerText = Color.rgb(21, 45, 27),
            callBannerActionBackground = Color.rgb(42, 145, 64),
            callBannerActionText = Color.WHITE
        )
    }
}

private fun Configuration.isNightMode(): Boolean {
    return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

private class ChatMessageAdapter(
    var messageTheme: MessageRenderTheme,
    matrixMediaLoader: MatrixMediaLoader?,
    var onContextMenuPreviewRequested: (MessageContextMenuRequest) -> Boolean,
    var onContextMenuRequested: (MessageContextMenuRequest) -> Boolean,
    var onContextMenuGestureEvent: (action: Int, rawX: Float, rawY: Float) -> Unit,
    var onReplyHeaderClicked: (String) -> Unit,
    var onToggleReaction: (messageId: String, reactionKey: String) -> Unit,
    var onPhotoViewerRequested: (PhotoViewerOpenRequest) -> Unit,
    var onVoicePlaybackRequested: (messageId: String, audioInfo: MatrixAudioInfo) -> Unit
) : ListAdapter<MatrixChatMessage, ChatMessageViewHolder>(ChatMessageDiffCallback) {
    private var lastMediaPrefetchWindowSignature: String? = null
    private var lastMediaPrefetchSignature: String? = null
    private var audioPlaybackSnapshot = AudioPlaybackSnapshot()
    var matrixMediaLoader: MatrixMediaLoader? = matrixMediaLoader
        set(value) {
            if (field !== value) {
                resetMediaPrefetchSignature()
            }
            field = value
        }

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        val start = ZynaPerfLog.start()
        return ChatMessageViewHolder(parent, matrixMediaLoader).also {
            ZynaPerfLog.endIfSlow(
                start,
                "chatAdapter.createViewHolder.slow",
                thresholdMs = 4.0
            )
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.stableItemId()
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        val start = ZynaPerfLog.start()
        holder.bind(
            message = getItem(position).toRenderModel(),
            theme = messageTheme,
            onContextMenuPreviewRequested = onContextMenuPreviewRequested,
            onContextMenuRequested = onContextMenuRequested,
            onContextMenuGestureEvent = onContextMenuGestureEvent,
            onReplyHeaderClicked = onReplyHeaderClicked,
            onToggleReaction = onToggleReaction,
            onPhotoViewerRequested = onPhotoViewerRequested,
            onVoicePlaybackRequested = onVoicePlaybackRequested,
            audioPlaybackSnapshot = audioPlaybackSnapshot
        )
        ZynaPerfLog.endIfSlow(
            start,
            "chatAdapter.bind.slow",
            thresholdMs = 4.0
        ) {
            "position=$position id=${getItem(position).id}"
        }
    }

    override fun onBindViewHolder(
        holder: ChatMessageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.all { it === AudioPlaybackPayload }) {
            holder.updateAudioPlaybackSnapshot(audioPlaybackSnapshot)
            return
        }
        if (payloads.isNotEmpty() && payloads.all { it === ReactionPayload }) {
            holder.updateReactions(getItem(position).toReactionRenderModels())
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onCurrentListChanged(
        previousList: List<MatrixChatMessage>,
        currentList: List<MatrixChatMessage>
    ) {
        resetMediaPrefetchSignature()
    }

    fun prefetchMediaAround(
        firstVisiblePosition: Int,
        lastVisiblePosition: Int,
        viewportWidthPx: Int,
        density: Float
    ) {
        val loader = matrixMediaLoader ?: return
        if (
            currentList.isEmpty() ||
            firstVisiblePosition == RecyclerView.NO_POSITION ||
            lastVisiblePosition == RecyclerView.NO_POSITION
        ) {
            return
        }

        val start = (min(firstVisiblePosition, lastVisiblePosition) - MEDIA_PREFETCH_ITEM_MARGIN)
            .coerceAtLeast(0)
        val end = (max(firstVisiblePosition, lastVisiblePosition) + MEDIA_PREFETCH_ITEM_MARGIN)
            .coerceAtMost(currentList.lastIndex)
        if (start > end) {
            return
        }

        val target = mediaPrefetchTarget(viewportWidthPx, density)
        val windowSignature = "$start:$end:${target.widthPx}x${target.heightPx}"
        if (windowSignature == lastMediaPrefetchWindowSignature) {
            return
        }

        val requests = (start..end)
            .flatMap { position ->
                currentList.getOrNull(position)
                    ?.prefetchImageRequests(target = target, density = density)
                    .orEmpty()
            }
        val signature = "$windowSignature:${requests.mediaSignature()}"
        lastMediaPrefetchWindowSignature = windowSignature
        if (signature == lastMediaPrefetchSignature) {
            return
        }
        lastMediaPrefetchSignature = signature
        requests.forEach { request ->
            loader.prefetchImage(
                imageInfo = request.imageInfo,
                targetWidthPx = request.targetWidthPx,
                targetHeightPx = request.targetHeightPx
            )
        }
    }

    private fun resetMediaPrefetchSignature() {
        lastMediaPrefetchWindowSignature = null
        lastMediaPrefetchSignature = null
    }

    fun setAudioPlaybackSnapshot(snapshot: AudioPlaybackSnapshot) {
        val previous = audioPlaybackSnapshot
        if (previous == snapshot) {
            return
        }
        audioPlaybackSnapshot = snapshot
        listOfNotNull(previous.messageId, snapshot.messageId)
            .distinct()
            .forEach { messageId ->
                val position = currentList.indexOfFirst { it.id == messageId }
                if (position != -1) {
                    notifyItemChanged(position, AudioPlaybackPayload)
                }
            }
    }
}

private class ChatMessageViewHolder(
    parent: ViewGroup,
    matrixMediaLoader: MatrixMediaLoader?
) : RecyclerView.ViewHolder(
    MessageCellView(parent.context, matrixMediaLoader).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
) {
    private val messageView = itemView as MessageCellView

    fun bind(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        onContextMenuPreviewRequested: (MessageContextMenuRequest) -> Boolean,
        onContextMenuRequested: (MessageContextMenuRequest) -> Boolean,
        onContextMenuGestureEvent: (action: Int, rawX: Float, rawY: Float) -> Unit,
        onReplyHeaderClicked: (String) -> Unit,
        onToggleReaction: (messageId: String, reactionKey: String) -> Unit,
        onPhotoViewerRequested: (PhotoViewerOpenRequest) -> Unit,
        onVoicePlaybackRequested: (messageId: String, audioInfo: MatrixAudioInfo) -> Unit,
        audioPlaybackSnapshot: AudioPlaybackSnapshot
    ) {
        messageView.onContextMenuPreviewRequested = onContextMenuPreviewRequested
        messageView.onContextMenuRequested = onContextMenuRequested
        messageView.onContextMenuGestureEvent = onContextMenuGestureEvent
        messageView.onReplyHeaderClicked = onReplyHeaderClicked
        messageView.onReactionClicked = { reactionKey -> onToggleReaction(message.id, reactionKey) }
        messageView.onPhotoViewerRequested = onPhotoViewerRequested
        messageView.onVoicePlaybackRequested = onVoicePlaybackRequested
        messageView.setAudioPlaybackSnapshot(audioPlaybackSnapshot)
        messageView.bind(message, theme)
    }

    fun updateAudioPlaybackSnapshot(snapshot: AudioPlaybackSnapshot) {
        messageView.setAudioPlaybackSnapshot(snapshot)
    }

    fun updateReactions(reactions: List<MessageReactionRenderModel>) {
        messageView.updateReactions(reactions)
    }
}

private object AudioPlaybackPayload
private object ReactionPayload

private fun VoiceRecorderState.toGlassVoiceState(
    playbackSnapshot: AudioPlaybackSnapshot
): GlassVoiceComposerState {
    return when (this) {
        VoiceRecorderState.Idle -> GlassVoiceComposerState.Idle
        is VoiceRecorderState.Recording -> GlassVoiceComposerState.Recording(
            durationMillis = durationMillis.toComposerDisplayDurationMillis(),
            waveform = waveform
        )
        is VoiceRecorderState.Finished -> {
            val sourceJson = "local:$localPath"
            val isActive = playbackSnapshot.sourceJson == sourceJson
            GlassVoiceComposerState.Preview(
                durationMillis = durationMillis.toComposerDisplayDurationMillis(),
                waveform = waveform,
                isLoading = isActive && playbackSnapshot.isLoading,
                isPlaying = isActive && playbackSnapshot.isPlaying
            )
        }
        is VoiceRecorderState.Error -> GlassVoiceComposerState.Error(message)
    }
}

private fun Long.toComposerDisplayDurationMillis(): Long {
    return (this / 1000L).coerceAtLeast(0L) * 1000L
}

private fun MatrixChatMessage.toRenderModel(): MessageRenderModel {
    return MessageRenderModel(
        id = id,
        eventId = eventId,
        senderId = sender,
        senderDisplayName = senderDisplayName,
        senderText = if (isOwn) {
            "You"
        } else {
            senderDisplayName?.takeIf { it.isNotBlank() } ?: sender
        },
        content = renderContent(),
        timestampText = timestampMillis.formatMessageTime(),
        isOutgoing = isOwn,
        deliveryState = deliveryState.toRenderDeliveryState(),
        replyInfo = replyInfo?.toRenderReplyPreview(),
        forwardedFrom = forwardedFrom,
        editInfo = editPreviewOrNull(),
        isEdited = isEdited,
        isEditPending = isEditPending,
        isEditFailed = isEditFailed,
        outgoingEnvelopeId = outgoingEnvelopeId,
        redactionTargetMessageId = redactionTargetMessageId(),
        canForward = canForwardMessage(),
        canRetryOutgoingEnvelope = canRetryOutgoingEnvelope,
        canDiscardOutgoingEnvelope = canDiscardOutgoingEnvelope,
        reactions = toReactionRenderModels()
    )
}

private fun MatrixChatMessage.toReactionRenderModels(): List<MessageReactionRenderModel> {
    return reactions.map { reaction ->
        MessageReactionRenderModel(
            key = reaction.key,
            count = reaction.count,
            isOwn = reaction.isOwn,
            isPendingRemoval = reaction.isPendingRemoval
        )
    }
}

private fun MatrixChatMessage.renderContent(): MessageContent {
    val presentation = mediaGroupPresentation
    if (presentation?.rendersCompositeBubble == true && presentation.items.isNotEmpty()) {
        return MessageContent.PhotoGroup(
            items = presentation.items,
            totalHint = presentation.totalHint,
            caption = presentation.caption,
            captionPlacement = presentation.captionPlacement,
            layoutOverride = presentation.layoutOverride
        )
    }

    return when (contentType) {
        MatrixMessageContentType.REDACTED -> MessageContent.Redacted
        MatrixMessageContentType.IMAGE -> imageInfo
            ?.let {
                MessageContent.Image(
                    imageInfo = it,
                    caption = if (presentation?.suppressIndividualCaption == true) null else it.caption,
                    captionPlacement = zynaAttributes.mediaGroup?.captionPlacement ?: CaptionPlacement.BOTTOM
                )
            }
            ?: MessageContent.Text(body.ifBlank { "Photo" })
        MatrixMessageContentType.AUDIO -> audioInfo
            ?.let { MessageContent.Voice(it) }
            ?: MessageContent.Text(body.ifBlank { "Audio" })
        else -> MessageContent.Text(body)
    }
}

private fun MatrixChatMessage.prefetchImageRequests(
    target: MediaPrefetchTarget,
    density: Float
): List<MediaPrefetchRequest> {
    val presentation = mediaGroupPresentation
    if (presentation?.rendersCompositeBubble == true && presentation.items.isNotEmpty()) {
        val visibleItems = presentation.items
            .take(PhotoGroupLayout.visibleItemCount(presentation.items.size))
        if (visibleItems.isEmpty()) {
            return emptyList()
        }

        val mediaWidth = target.widthPx.coerceAtLeast(1)
        val mediaHeight = photoGroupPrefetchHeight(
            width = mediaWidth,
            itemCount = presentation.items.size,
            primaryAspectRatio = visibleItems.firstOrNull()?.imageInfo?.widthToHeightAspectRatio(),
            density = density
        )
        val frames = PhotoGroupLayout.frames(
            bounds = RectF(0f, 0f, mediaWidth.toFloat(), mediaHeight.toFloat()),
            itemCount = presentation.items.size,
            layoutOverride = presentation.layoutOverride,
            spacingPx = MEDIA_PREFETCH_TILE_SPACING_DP.dpToPx(density)
        )

        return visibleItems.mapIndexedNotNull { index, item ->
            val frame = frames.getOrNull(index) ?: return@mapIndexedNotNull null
            MediaPrefetchRequest(
                imageInfo = item.imageInfo,
                targetWidthPx = frame.width().roundToInt().coerceAtLeast(1),
                targetHeightPx = frame.height().roundToInt().coerceAtLeast(1)
            )
        }
    }

    return if (contentType == MatrixMessageContentType.IMAGE) {
        imageInfo?.let { listOf(it.singleImagePrefetchRequest(target, density)) }.orEmpty()
    } else {
        emptyList()
    }
}

private fun MatrixImageInfo.singleImagePrefetchRequest(
    target: MediaPrefetchTarget,
    density: Float
): MediaPrefetchRequest {
    val width = target.widthPx.coerceAtLeast(1)
    val minHeight = MEDIA_PREFETCH_MIN_HEIGHT_DP.dpToPx(density)
    val height = (width * heightToWidthRatio())
        .roundToInt()
        .coerceIn(min(minHeight, target.heightPx), target.heightPx)
    return MediaPrefetchRequest(
        imageInfo = this,
        targetWidthPx = width,
        targetHeightPx = height.coerceAtLeast(1)
    )
}

private fun photoGroupPrefetchHeight(
    width: Int,
    itemCount: Int,
    primaryAspectRatio: Float?,
    density: Float
): Int {
    val resolvedCount = max(1, itemCount)
    val rawHeight = when (resolvedCount) {
        1 -> if (primaryAspectRatio != null && primaryAspectRatio > 0f) {
            width / primaryAspectRatio
        } else {
            width * 0.78f
        }
        2 -> width * 0.74f
        3 -> width * 0.82f
        else -> width.toFloat()
    }
    val minHeight = MEDIA_PREFETCH_MIN_HEIGHT_DP.dpToPx(density)
    val maxHeight = MEDIA_PREFETCH_MAX_HEIGHT_DP.dpToPx(density)
    return rawHeight.roundToInt().coerceIn(min(minHeight, maxHeight), maxHeight)
}

private fun List<MediaPrefetchRequest>.mediaSignature(): Int {
    return fold(1) { hash, request ->
        val imageHash = request.imageInfo.prefetchIdentity().hashCode()
        var nextHash = 31 * hash + imageHash
        nextHash = 31 * nextHash + request.targetWidthPx
        nextHash = 31 * nextHash + request.targetHeightPx
        nextHash
    }
}

private fun MatrixImageInfo.prefetchIdentity(): String {
    return localPath ?: thumbnailSourceJson ?: sourceJson
}

private fun MatrixImageInfo.widthToHeightAspectRatio(): Float? {
    val width = width?.takeIf { it > 0 } ?: return null
    val height = height?.takeIf { it > 0 } ?: return null
    return width.toFloat() / height.toFloat()
}

private fun MatrixImageInfo.heightToWidthRatio(): Float {
    val sourceWidth = width?.takeIf { it > 0 } ?: 4
    val sourceHeight = height?.takeIf { it > 0 } ?: 3
    return (sourceHeight.toFloat() / sourceWidth.toFloat()).coerceIn(0.45f, 2.1f)
}

private fun MatrixChatMessage.editPreviewOrNull(): MessageEditPreview? {
    val eventId = eventId?.takeIf { it.isNotBlank() } ?: return null
    if (
        !isOwn ||
        outgoingEnvelopeId != null ||
        contentType != MatrixMessageContentType.TEXT ||
        deliveryState != MatrixMessageDeliveryState.SENT ||
        isEditPending
    ) {
        return null
    }
    val body = body.takeIf { it.isNotBlank() } ?: return null
    return MessageEditPreview(
        messageId = id,
        eventId = eventId,
        body = body
    )
}

private fun MessageEditPreview.toMatrixEditTarget(): MatrixEditTarget {
    return MatrixEditTarget(
        messageId = messageId,
        eventId = eventId,
        body = body
    )
}

private fun MatrixReplyInfo.toRenderReplyPreview(): MessageReplyPreview {
    val displaySender = senderDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: senderId.takeIf { it.isNotBlank() }
        ?: "Unknown"
    return MessageReplyPreview(
        eventId = eventId,
        senderId = senderId,
        senderText = displaySender,
        body = body.ifBlank { "Message" }
    )
}

private fun MessageReplyPreview.toMatrixReplyInfo(): MatrixReplyInfo {
    return MatrixReplyInfo(
        eventId = eventId,
        senderId = senderId,
        senderDisplayName = senderText.takeIf { it.isNotBlank() && it != senderId },
        body = body
    )
}

private fun MessageForwardPreview.toMatrixForwardTarget(): MatrixForwardTarget {
    return MatrixForwardTarget(
        body = body,
        forwardedFrom = forwardedFrom,
        caption = caption,
        imageItems = imageItems,
        captionPlacement = captionPlacement,
        layoutOverride = layoutOverride
    )
}

private fun MatrixReplyInfo.toComposerPreview(): GlassComposerPreview {
    val sender = senderDisplayName
        ?.takeIf { it.isNotBlank() }
        ?: senderId.takeIf { it.isNotBlank() }
        ?: "Unknown"
    return GlassComposerPreview(
        title = sender,
        body = body.ifBlank { "Message" }
    )
}

private fun MatrixForwardTarget.toComposerPreview(): GlassComposerPreview {
    val title = forwardedFrom
        ?.takeIf { it.isNotBlank() }
        ?.let { "Forwarded from $it" }
        ?: "Forward message"
    return GlassComposerPreview(
        title = title,
        body = body.ifBlank { "Message" }
    )
}

private fun MatrixEditTarget.toComposerPreview(): GlassComposerPreview {
    return GlassComposerPreview(
        title = "Edit message",
        body = body.ifBlank { "Message" }
    )
}

private fun MatrixChatMessage.redactionTargetMessageId(): String? {
    return if (
        isOwn &&
            eventId != null &&
            outgoingEnvelopeId == null &&
            contentType != MatrixMessageContentType.REDACTED &&
            deliveryState == MatrixMessageDeliveryState.SENT
    ) {
        id
    } else {
        null
    }
}

private fun MatrixChatMessage.canForwardMessage(): Boolean {
    if (
        eventId == null ||
        outgoingEnvelopeId != null ||
        deliveryState != MatrixMessageDeliveryState.SENT
    ) {
        return false
    }
    return when (contentType) {
        MatrixMessageContentType.TEXT -> body.isNotBlank()
        MatrixMessageContentType.IMAGE -> imageInfo?.sourceJson?.isNotBlank() == true
        else -> false
    }
}

private fun MatrixMessageDeliveryState.toRenderDeliveryState(): RenderDeliveryState {
    return when (this) {
        MatrixMessageDeliveryState.SENT -> RenderDeliveryState.SENT
        MatrixMessageDeliveryState.SENDING -> RenderDeliveryState.SENDING
        MatrixMessageDeliveryState.FAILED -> RenderDeliveryState.FAILED
    }
}

private fun Long.formatMessageTime(): String {
    return MESSAGE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    )
}

private fun logChatTeleport(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d(CHAT_TELEPORT_TAG, message)
    }
}

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private const val CHAT_TELEPORT_TAG = "ZynaChatTeleport"
private const val NEWEST_EDGE_THRESHOLD = 1
private const val JUMP_TARGET_VIEWPORT_FRACTION = 0.42f
private const val LOCAL_JUMP_SMOOTH_SCROLL_MAX_DISTANCE = 28
private const val LOCAL_JUMP_SCROLL_BASE_DELAY_MS = 320L
private const val LOCAL_JUMP_SCROLL_PER_ITEM_DELAY_MS = 24L
private const val LOCAL_JUMP_SCROLL_MAX_DELAY_MS = 1_400L
private const val LOCAL_JUMP_HIGHLIGHT_DELAY_MS = 80L
private const val READ_RECEIPT_VISIBILITY_THRESHOLD = 0.6f
private const val READ_RECEIPT_CONTENT_UPDATE_DELAY_MS = 50L
private const val MEDIA_PREFETCH_ITEM_MARGIN = 8
private const val MEDIA_PREFETCH_HORIZONTAL_CHROME_DP = 96
private const val MEDIA_PREFETCH_MAX_WIDTH_DP = 320
private const val MEDIA_PREFETCH_MIN_HEIGHT_DP = 128
private const val MEDIA_PREFETCH_MAX_HEIGHT_DP = 390
private const val MEDIA_PREFETCH_TILE_SPACING_DP = 2
private const val NATIVE_TOP_BAR_HEIGHT_DP = 64
private const val ACTIVE_CALL_BANNER_HEIGHT_DP = 48
private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L

private fun Int.dpToPx(density: Float): Int {
    return (this * density).roundToInt()
}

private fun MatrixChatMessage.isReadReceiptCandidate(): Boolean {
    return !isOwn && eventId != null && contentType != MatrixMessageContentType.REDACTED
}

private fun String.stableItemId(): Long {
    var hash = FNV_64_OFFSET_BASIS
    forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= FNV_64_PRIME
    }
    return hash
}

private object ChatMessageDiffCallback : DiffUtil.ItemCallback<MatrixChatMessage>() {
    override fun areItemsTheSame(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Any? {
        return if (
            oldItem.reactions != newItem.reactions &&
            oldItem.copy(reactions = newItem.reactions) == newItem
        ) {
            ReactionPayload
        } else {
            null
        }
    }
}
