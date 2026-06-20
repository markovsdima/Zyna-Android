package com.zyna.app.ui.chat

import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.BuildConfig
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.ui.chat.render.MessageCellView
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageEditPreview
import com.zyna.app.ui.chat.render.MessageForwardPreview
import com.zyna.app.ui.chat.viewer.PhotoViewerOpenRequest
import com.zyna.app.ui.chat.render.MessageReplyPreview
import com.zyna.app.ui.chat.render.MessageRenderModel
import com.zyna.app.ui.chat.render.MessageRenderTheme
import com.zyna.app.ui.chat.render.RenderDeliveryState
import com.zyna.app.ui.glass.ChatTeleportDirection
import com.zyna.app.ui.glass.GlassComposerPreview
import com.zyna.app.ui.glass.GlassChatLayout
import com.zyna.app.ui.glass.GlassPalette
import com.zyna.app.ui.chat.viewer.PhotoViewerLayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    roomName: String,
    roomId: String,
    messages: List<MatrixChatMessage>,
    windowChangeOrigin: TimelineWindowChangeOrigin,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    canLoadNewer: Boolean,
    isAtLiveEdge: Boolean,
    scrollToLiveEdgeRequested: Boolean,
    errorMessage: String?,
    isSendingMessage: Boolean = false,
    sendErrorMessage: String? = null,
    replyTarget: MatrixReplyInfo? = null,
    editTarget: MatrixEditTarget? = null,
    forwardTarget: MatrixForwardTarget? = null,
    matrixMediaLoader: MatrixMediaLoader? = null,
    jumpTargetEventId: String? = null,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onJumpToLiveEdge: () -> Unit,
    onSendMessage: (String) -> Boolean = { false },
    onAttachPhotos: () -> Unit = {},
    onReplyToMessage: (MatrixReplyInfo) -> Unit = {},
    onReplyHeaderClicked: (String) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onEditMessage: (MatrixEditTarget) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onForwardMessage: (MatrixForwardTarget) -> Unit = {},
    onCancelForward: () -> Unit = {},
    onRetryOutgoingEnvelope: (String) -> Unit = {},
    onDiscardOutgoingEnvelope: (String) -> Unit = {},
    onRedactMessage: (String) -> Unit = {},
    onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit = {},
    onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit = { _, _, _ -> },
    onJumpTargetConsumed: (String) -> Unit = {},
    onScrollToLiveEdgeConsumed: () -> Unit = {}
) {
    val glassPalette = chatGlassPalette()
    val sendErrorColor = MaterialTheme.colorScheme.error.toArgb()
    var photoViewerRequest by remember { mutableStateOf<PhotoViewerOpenRequest?>(null) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Text("Back")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = roomName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = roomId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = onRefresh,
                            enabled = !isLoading
                        ) {
                            Text(if (isLoading) "Loading" else "Refresh")
                        }
                    }
                )
            }
        ) { innerPadding ->
            when {
                errorMessage != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> ChatMessageList(
                    messages = messages,
                    roomId = roomId,
                    windowChangeOrigin = windowChangeOrigin,
                    isLoading = isLoading,
                    isLoadingOlder = isLoadingOlder,
                    canLoadOlder = canLoadOlder,
                    canLoadNewer = canLoadNewer,
                    isAtLiveEdge = isAtLiveEdge,
                    scrollToLiveEdgeRequested = scrollToLiveEdgeRequested,
                    isSendingMessage = isSendingMessage,
                    sendErrorMessage = sendErrorMessage,
                    sendErrorColor = sendErrorColor,
                    replyTarget = replyTarget,
                    editTarget = editTarget,
                    forwardTarget = forwardTarget,
                    matrixMediaLoader = matrixMediaLoader,
                    jumpTargetEventId = jumpTargetEventId,
                    palette = glassPalette,
                    onLoadOlder = onLoadOlder,
                    onLoadNewer = onLoadNewer,
                    onJumpToLiveEdge = onJumpToLiveEdge,
                    onSendMessage = onSendMessage,
                    onAttachPhotos = onAttachPhotos,
                    onReplyToMessage = onReplyToMessage,
                    onReplyHeaderClicked = onReplyHeaderClicked,
                    onPhotoViewerRequested = { request ->
                        photoViewerRequest = request
                    },
                    onCancelReply = onCancelReply,
                    onEditMessage = onEditMessage,
                    onCancelEdit = onCancelEdit,
                    onForwardMessage = onForwardMessage,
                    onCancelForward = onCancelForward,
                    onRetryOutgoingEnvelope = onRetryOutgoingEnvelope,
                    onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope,
                    onRedactMessage = onRedactMessage,
                    onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed,
                    onVisibleReadReceiptCandidate = onVisibleReadReceiptCandidate,
                    onJumpTargetConsumed = onJumpTargetConsumed,
                    onScrollToLiveEdgeConsumed = onScrollToLiveEdgeConsumed,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
        }

        val viewerRequest = photoViewerRequest
        if (viewerRequest != null && matrixMediaLoader != null) {
            key(viewerRequest) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PhotoViewerLayer(context, matrixMediaLoader).apply {
                            onDismissed = {
                                photoViewerRequest = null
                            }
                            open(viewerRequest)
                        }
                    },
                    update = { layer ->
                        layer.onDismissed = {
                            photoViewerRequest = null
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatMessageList(
    messages: List<MatrixChatMessage>,
    roomId: String,
    windowChangeOrigin: TimelineWindowChangeOrigin,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    canLoadNewer: Boolean,
    isAtLiveEdge: Boolean,
    scrollToLiveEdgeRequested: Boolean,
    isSendingMessage: Boolean,
    sendErrorMessage: String?,
    sendErrorColor: Int,
    replyTarget: MatrixReplyInfo?,
    editTarget: MatrixEditTarget?,
    forwardTarget: MatrixForwardTarget?,
    matrixMediaLoader: MatrixMediaLoader?,
    jumpTargetEventId: String?,
    palette: GlassPalette,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onJumpToLiveEdge: () -> Unit,
    onSendMessage: (String) -> Boolean,
    onAttachPhotos: () -> Unit,
    onReplyToMessage: (MatrixReplyInfo) -> Unit,
    onReplyHeaderClicked: (String) -> Unit,
    onPhotoViewerRequested: (PhotoViewerOpenRequest) -> Unit,
    onCancelReply: () -> Unit,
    onEditMessage: (MatrixEditTarget) -> Unit,
    onCancelEdit: () -> Unit,
    onForwardMessage: (MatrixForwardTarget) -> Unit,
    onCancelForward: () -> Unit,
    onRetryOutgoingEnvelope: (String) -> Unit,
    onDiscardOutgoingEnvelope: (String) -> Unit,
    onRedactMessage: (String) -> Unit,
    onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit,
    onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit,
    onJumpTargetConsumed: (String) -> Unit,
    onScrollToLiveEdgeConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messageTheme = MessageRenderTheme(
        outgoingBubble = MaterialTheme.colorScheme.primaryContainer.toArgb(),
        outgoingText = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
        outgoingMetadata = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
        incomingBubble = MaterialTheme.colorScheme.surfaceVariant.toArgb(),
        incomingText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        incomingMetadata = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    )

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val chatLayout = GlassChatLayout(context)
            chatLayout.recyclerView.adapter = ChatMessageAdapter(
                messageTheme = messageTheme,
                matrixMediaLoader = matrixMediaLoader,
                onContextMenuPreviewRequested = chatLayout::beginMessageContextMenuGesture,
                onContextMenuRequested = chatLayout::showMessageContextMenu,
                onContextMenuGestureEvent = chatLayout::handleMessageContextGestureEvent,
                onReplyHeaderClicked = onReplyHeaderClicked,
                onPhotoViewerRequested = onPhotoViewerRequested
            )
            chatLayout.onLoadOlderMessages = onLoadOlder
            chatLayout.onLoadNewerMessages = onLoadNewer
            chatLayout.onScrollToLiveEdge = onJumpToLiveEdge
            chatLayout.onReplyToMessage = { target ->
                onReplyToMessage(target.toMatrixReplyInfo())
            }
            chatLayout.onEditMessage = { target ->
                onEditMessage(target.toMatrixEditTarget())
            }
            chatLayout.onForwardMessage = { target ->
                onForwardMessage(target.toMatrixForwardTarget())
            }
            chatLayout.onRetryOutgoingEnvelope = onRetryOutgoingEnvelope
            chatLayout.onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope
            chatLayout.onRedactMessage = onRedactMessage
            chatLayout.onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed
            chatLayout.onEvaluateVisibleReadReceiptCandidate = {
                chatLayout.evaluateVisibleReadReceiptCandidate(isAtLiveEdge) { eventId, canEstablishBaseline ->
                    onVisibleReadReceiptCandidate(roomId, eventId, canEstablishBaseline)
                }
            }
            chatLayout.inputBar.onSendMessage = onSendMessage
            chatLayout.inputBar.onAttachClicked = onAttachPhotos
            chatLayout.inputBar.onPreviewCancelled = {
                if (forwardTarget != null) onCancelForward() else onCancelReply()
            }
            chatLayout.inputBar.allowEmptySend = forwardTarget != null
            chatLayout.inputBar.setPreview(
                forwardTarget?.toComposerPreview() ?: replyTarget?.toComposerPreview()
            )
            chatLayout.inputBar.onEditCancelled = onCancelEdit
            chatLayout.inputBar.setEditDraft(editTarget?.eventId, editTarget?.body)
            chatLayout.inputBar.setEditPreview(editTarget?.toComposerPreview())
            chatLayout.setPalette(palette)
            chatLayout.setPaginationState(
                isLoadingOlder = isLoadingOlder,
                canLoadOlder = canLoadOlder &&
                    !isLoading &&
                    jumpTargetEventId == null &&
                    !scrollToLiveEdgeRequested,
                canLoadNewer = canLoadNewer &&
                    !isLoading &&
                    jumpTargetEventId == null &&
                    !scrollToLiveEdgeRequested
            )
            chatLayout.setLiveEdgeState(isAtLiveEdge)
            chatLayout.setEmptyState(messages.isEmpty(), isLoading)
            chatLayout.setComposerState(
                isSending = isSendingMessage,
                errorMessage = sendErrorMessage,
                errorColor = sendErrorColor
            )
            chatLayout
        },
        update = { chatLayout ->
            chatLayout.setPalette(palette)
            chatLayout.onLoadOlderMessages = onLoadOlder
            chatLayout.onLoadNewerMessages = onLoadNewer
            chatLayout.onScrollToLiveEdge = onJumpToLiveEdge
            chatLayout.onReplyToMessage = { target ->
                onReplyToMessage(target.toMatrixReplyInfo())
            }
            chatLayout.onEditMessage = { target ->
                onEditMessage(target.toMatrixEditTarget())
            }
            chatLayout.onForwardMessage = { target ->
                onForwardMessage(target.toMatrixForwardTarget())
            }
            chatLayout.onRetryOutgoingEnvelope = onRetryOutgoingEnvelope
            chatLayout.onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope
            chatLayout.onRedactMessage = onRedactMessage
            chatLayout.onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed
            chatLayout.onEvaluateVisibleReadReceiptCandidate = {
                chatLayout.evaluateVisibleReadReceiptCandidate(isAtLiveEdge) { eventId, canEstablishBaseline ->
                    onVisibleReadReceiptCandidate(roomId, eventId, canEstablishBaseline)
                }
            }
            chatLayout.inputBar.onSendMessage = onSendMessage
            chatLayout.inputBar.onAttachClicked = onAttachPhotos
            chatLayout.inputBar.onPreviewCancelled = {
                if (forwardTarget != null) onCancelForward() else onCancelReply()
            }
            chatLayout.inputBar.allowEmptySend = forwardTarget != null
            chatLayout.inputBar.setPreview(
                forwardTarget?.toComposerPreview() ?: replyTarget?.toComposerPreview()
            )
            chatLayout.inputBar.onEditCancelled = onCancelEdit
            chatLayout.inputBar.setEditDraft(editTarget?.eventId, editTarget?.body)
            chatLayout.inputBar.setEditPreview(editTarget?.toComposerPreview())
            chatLayout.setPaginationState(
                isLoadingOlder = isLoadingOlder,
                canLoadOlder = canLoadOlder &&
                    !isLoading &&
                    jumpTargetEventId == null &&
                    !scrollToLiveEdgeRequested,
                canLoadNewer = canLoadNewer &&
                    !isLoading &&
                    jumpTargetEventId == null &&
                    !scrollToLiveEdgeRequested
            )
            chatLayout.setLiveEdgeState(isAtLiveEdge)
            chatLayout.setEmptyState(messages.isEmpty(), isLoading)
            chatLayout.setComposerState(
                isSending = isSendingMessage,
                errorMessage = sendErrorMessage,
                errorColor = sendErrorColor
            )

            val recyclerView = chatLayout.recyclerView
            val adapter = recyclerView.adapter as ChatMessageAdapter
            adapter.onContextMenuPreviewRequested = chatLayout::beginMessageContextMenuGesture
            adapter.onContextMenuRequested = chatLayout::showMessageContextMenu
            adapter.onContextMenuGestureEvent = chatLayout::handleMessageContextGestureEvent
            adapter.onReplyHeaderClicked = onReplyHeaderClicked
            adapter.onPhotoViewerRequested = onPhotoViewerRequested
            adapter.matrixMediaLoader = matrixMediaLoader
            val displayedMessages = messages
                .asReversed()
                .withMediaGroupPresentation(
                    hasNewerBoundary = canLoadNewer,
                    hasOlderBoundary = canLoadOlder
                )
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
                windowChangeOrigin == TimelineWindowChangeOrigin.DATABASE_PAGINATION
            ) {
                recyclerView.findViewportAnchor(adapter)
            } else null
            val wasEmpty = adapter.itemCount == 0
            val themeChanged = adapter.messageTheme != messageTheme
            val jumpTargetPosition = jumpTargetEventId?.let { targetEventId ->
                displayedMessages.indexOfFirst { message ->
                    message.eventId == targetEventId || message.id == targetEventId
                }.takeIf { it != -1 }
            }
            val shouldApplyJumpTarget = jumpTargetPosition != null &&
                windowChangeOrigin == TimelineWindowChangeOrigin.JUMP
            val shouldApplyScrollToLiveEdge = scrollToLiveEdgeRequested &&
                windowChangeOrigin == TimelineWindowChangeOrigin.JUMP &&
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
            if (jumpTargetEventId != null) {
                logChatTeleport(
                    "ui update origin=$windowChangeOrigin " +
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
            if (scrollToLiveEdgeRequested) {
                logChatTeleport(
                    "live ui update origin=$windowChangeOrigin " +
                        "shouldApply=$shouldApplyScrollToLiveEdge " +
                        "oldCount=${adapter.itemCount} newCount=${displayedMessages.size} " +
                        "firstVisible=$firstVisiblePosition didBegin=$didBeginLiveEdgeTeleport"
                )
            }
            adapter.messageTheme = messageTheme
            adapter.submitList(displayedMessages) {
                if (displayedMessages.isNotEmpty()) {
                    if (shouldApplyScrollToLiveEdge) {
                        recyclerView.stopScroll()
                        chatLayout.scrollToBottom(animated = false)
                        if (didBeginLiveEdgeTeleport) {
                            recyclerView.runAfterNextPreDraw {
                                chatLayout.completeSnapshotTeleport {
                                    onScrollToLiveEdgeConsumed()
                                }
                            }
                        } else {
                            onScrollToLiveEdgeConsumed()
                        }
                    } else if (jumpTargetEventId != null && jumpTargetPosition != null && shouldApplyJumpTarget) {
                        logChatTeleport(
                            "commit scroll targetPosition=$jumpTargetPosition " +
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
                                    "preDraw complete targetPosition=$jumpTargetPosition " +
                                        "childCount=${recyclerView.childCount}"
                                )
                                chatLayout.completeSnapshotTeleport {
                                    chatLayout.highlightMessageAtAdapterPosition(jumpTargetPosition)
                                    onJumpTargetConsumed(jumpTargetEventId)
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
                                    { onJumpTargetConsumed(jumpTargetEventId) },
                                    scrollDelayMillis
                                )
                            } else {
                                layoutManager?.scrollToPositionWithOffset(
                                    jumpTargetPosition,
                                    recyclerView.jumpTargetScrollOffset()
                                )
                                chatLayout.highlightMessageAtAdapterPosition(jumpTargetPosition)
                                onJumpTargetConsumed(jumpTargetEventId)
                            }
                        }
                    } else if (
                        wasEmpty ||
                        (
                            wasAtBottom &&
                                hasNewerMessage &&
                                windowChangeOrigin != TimelineWindowChangeOrigin.DATABASE_PAGINATION
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
                chatLayout.scheduleVisibleReadReceiptCandidateEvaluation(
                    delayMillis = READ_RECEIPT_CONTENT_UPDATE_DELAY_MS
                )
            }
            if (themeChanged) {
                adapter.notifyDataSetChanged()
            }
        }
    )
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

private fun RecyclerView.jumpTargetScrollOffset(): Int {
    val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
    return paddingTop + (availableHeight * JUMP_TARGET_VIEWPORT_FRACTION).toInt()
}

@Composable
private fun chatGlassPalette(): GlassPalette {
    val scheme = MaterialTheme.colorScheme
    return GlassPalette(
        background = scheme.surface.toArgb(),
        glassTint = scheme.surface.copy(alpha = 0.18f).toArgb(),
        glassTintStrong = scheme.surfaceContainerHighest.copy(alpha = 0.24f).toArgb(),
        stroke = scheme.onSurface.copy(alpha = 0.18f).toArgb(),
        text = scheme.onSurface.toArgb(),
        hint = scheme.onSurfaceVariant.copy(alpha = 0.72f).toArgb()
    )
}

private class ChatMessageAdapter(
    var messageTheme: MessageRenderTheme,
    var matrixMediaLoader: MatrixMediaLoader?,
    var onContextMenuPreviewRequested: (MessageContextMenuRequest) -> Boolean,
    var onContextMenuRequested: (MessageContextMenuRequest) -> Boolean,
    var onContextMenuGestureEvent: (action: Int, rawX: Float, rawY: Float) -> Unit,
    var onReplyHeaderClicked: (String) -> Unit,
    var onPhotoViewerRequested: (PhotoViewerOpenRequest) -> Unit
) : ListAdapter<MatrixChatMessage, ChatMessageViewHolder>(ChatMessageDiffCallback) {
    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        return ChatMessageViewHolder(parent, matrixMediaLoader)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.stableItemId()
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        holder.bind(
            message = getItem(position).toRenderModel(),
            theme = messageTheme,
            onContextMenuPreviewRequested = onContextMenuPreviewRequested,
            onContextMenuRequested = onContextMenuRequested,
            onContextMenuGestureEvent = onContextMenuGestureEvent,
            onReplyHeaderClicked = onReplyHeaderClicked,
            onPhotoViewerRequested = onPhotoViewerRequested
        )
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
        onPhotoViewerRequested: (PhotoViewerOpenRequest) -> Unit
    ) {
        messageView.onContextMenuPreviewRequested = onContextMenuPreviewRequested
        messageView.onContextMenuRequested = onContextMenuRequested
        messageView.onContextMenuGestureEvent = onContextMenuGestureEvent
        messageView.onReplyHeaderClicked = onReplyHeaderClicked
        messageView.onPhotoViewerRequested = onPhotoViewerRequested
        messageView.bind(message, theme)
    }
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
        canDiscardOutgoingEnvelope = canDiscardOutgoingEnvelope
    )
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
        else -> MessageContent.Text(body)
    }
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
private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L

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
}
