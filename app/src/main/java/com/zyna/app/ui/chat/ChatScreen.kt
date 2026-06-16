package com.zyna.app.ui.chat

import android.view.ViewGroup
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
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.ui.chat.render.MessageCellView
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageRenderModel
import com.zyna.app.ui.chat.render.MessageRenderTheme
import com.zyna.app.ui.chat.render.RenderDeliveryState
import com.zyna.app.ui.glass.GlassChatLayout
import com.zyna.app.ui.glass.GlassPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    roomName: String,
    roomId: String,
    messages: List<MatrixChatMessage>,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    errorMessage: String?,
    isSendingMessage: Boolean = false,
    sendErrorMessage: String? = null,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onSendMessage: (String) -> Boolean = { false },
    onRetryOutgoingEnvelope: (String) -> Unit = {},
    onDiscardOutgoingEnvelope: (String) -> Unit = {},
    onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit = {},
    onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit = { _, _, _ -> }
) {
    val glassPalette = chatGlassPalette()
    val sendErrorColor = MaterialTheme.colorScheme.error.toArgb()
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
                isLoading = isLoading,
                isLoadingOlder = isLoadingOlder,
                canLoadOlder = canLoadOlder,
                isSendingMessage = isSendingMessage,
                sendErrorMessage = sendErrorMessage,
                sendErrorColor = sendErrorColor,
                palette = glassPalette,
                onLoadOlder = onLoadOlder,
                onSendMessage = onSendMessage,
                onRetryOutgoingEnvelope = onRetryOutgoingEnvelope,
                onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope,
                onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed,
                onVisibleReadReceiptCandidate = onVisibleReadReceiptCandidate,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
private fun ChatMessageList(
    messages: List<MatrixChatMessage>,
    roomId: String,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    isSendingMessage: Boolean,
    sendErrorMessage: String?,
    sendErrorColor: Int,
    palette: GlassPalette,
    onLoadOlder: () -> Unit,
    onSendMessage: (String) -> Boolean,
    onRetryOutgoingEnvelope: (String) -> Unit,
    onDiscardOutgoingEnvelope: (String) -> Unit,
    onDebugMarkOutgoingEnvelopeFailed: (String) -> Unit,
    onVisibleReadReceiptCandidate: (
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) -> Unit,
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
                onContextMenuPreviewRequested = chatLayout::beginMessageContextMenuGesture,
                onContextMenuRequested = chatLayout::showMessageContextMenu,
                onContextMenuGestureEvent = chatLayout::handleMessageContextGestureEvent
            )
            chatLayout.onLoadOlderMessages = onLoadOlder
            chatLayout.onRetryOutgoingEnvelope = onRetryOutgoingEnvelope
            chatLayout.onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope
            chatLayout.onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed
            chatLayout.onEvaluateVisibleReadReceiptCandidate = {
                chatLayout.evaluateVisibleReadReceiptCandidate { eventId, canEstablishBaseline ->
                    onVisibleReadReceiptCandidate(roomId, eventId, canEstablishBaseline)
                }
            }
            chatLayout.inputBar.onSendMessage = onSendMessage
            chatLayout.setPalette(palette)
            chatLayout.setPaginationState(
                isLoadingOlder = isLoadingOlder,
                canLoadOlder = canLoadOlder && !isLoading
            )
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
            chatLayout.onRetryOutgoingEnvelope = onRetryOutgoingEnvelope
            chatLayout.onDiscardOutgoingEnvelope = onDiscardOutgoingEnvelope
            chatLayout.onDebugMarkOutgoingEnvelopeFailed = onDebugMarkOutgoingEnvelopeFailed
            chatLayout.onEvaluateVisibleReadReceiptCandidate = {
                chatLayout.evaluateVisibleReadReceiptCandidate { eventId, canEstablishBaseline ->
                    onVisibleReadReceiptCandidate(roomId, eventId, canEstablishBaseline)
                }
            }
            chatLayout.inputBar.onSendMessage = onSendMessage
            chatLayout.setPaginationState(
                isLoadingOlder = isLoadingOlder,
                canLoadOlder = canLoadOlder && !isLoading
            )
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
            val displayedMessages = messages.asReversed()
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
            val wasEmpty = adapter.itemCount == 0
            val themeChanged = adapter.messageTheme != messageTheme
            adapter.messageTheme = messageTheme
            adapter.submitList(displayedMessages) {
                if (displayedMessages.isNotEmpty()) {
                    if (wasEmpty || (wasAtBottom && hasNewerMessage)) {
                        chatLayout.scrollToBottom(animated = false)
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
            message.id
        } else {
            null
        }
    }

    onCandidate(
        candidate,
        firstVisiblePosition <= NEWEST_EDGE_THRESHOLD
    )
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
    var onContextMenuPreviewRequested: (MessageContextMenuRequest) -> Boolean,
    var onContextMenuRequested: (MessageContextMenuRequest) -> Boolean,
    var onContextMenuGestureEvent: (action: Int, rawX: Float, rawY: Float) -> Unit
) : ListAdapter<MatrixChatMessage, ChatMessageViewHolder>(ChatMessageDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        return ChatMessageViewHolder(parent)
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        holder.bind(
            message = getItem(position).toRenderModel(),
            theme = messageTheme,
            onContextMenuPreviewRequested = onContextMenuPreviewRequested,
            onContextMenuRequested = onContextMenuRequested,
            onContextMenuGestureEvent = onContextMenuGestureEvent
        )
    }
}

private class ChatMessageViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
    MessageCellView(parent.context).apply {
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
        onContextMenuGestureEvent: (action: Int, rawX: Float, rawY: Float) -> Unit
    ) {
        messageView.onContextMenuPreviewRequested = onContextMenuPreviewRequested
        messageView.onContextMenuRequested = onContextMenuRequested
        messageView.onContextMenuGestureEvent = onContextMenuGestureEvent
        messageView.bind(message, theme)
    }
}

private fun MatrixChatMessage.toRenderModel(): MessageRenderModel {
    return MessageRenderModel(
        id = id,
        senderText = if (isOwn) "You" else sender,
        content = MessageContent.Text(body),
        timestampText = timestampMillis.formatMessageTime(),
        isOutgoing = isOwn,
        deliveryState = deliveryState.toRenderDeliveryState(),
        outgoingEnvelopeId = outgoingEnvelopeId,
        canRetryOutgoingEnvelope = canRetryOutgoingEnvelope,
        canDiscardOutgoingEnvelope = canDiscardOutgoingEnvelope
    )
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

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private const val NEWEST_EDGE_THRESHOLD = 1
private const val READ_RECEIPT_VISIBILITY_THRESHOLD = 0.6f
private const val READ_RECEIPT_CONTENT_UPDATE_DELAY_MS = 50L
private const val EVENT_ID_PREFIX = "$"

private fun MatrixChatMessage.isReadReceiptCandidate(): Boolean {
    return !isOwn && id.startsWith(EVENT_ID_PREFIX)
}

private object ChatMessageDiffCallback : DiffUtil.ItemCallback<MatrixChatMessage>() {
    override fun areItemsTheSame(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Boolean {
        return oldItem == newItem
    }
}
