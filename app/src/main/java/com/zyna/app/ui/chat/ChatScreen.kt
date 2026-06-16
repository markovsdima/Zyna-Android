package com.zyna.app.ui.chat

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
import com.zyna.app.ui.glass.GlassChatLayout
import com.zyna.app.ui.glass.GlassPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    onSendMessage: (String) -> Boolean = { false }
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
                isLoading = isLoading,
                isLoadingOlder = isLoadingOlder,
                canLoadOlder = canLoadOlder,
                isSendingMessage = isSendingMessage,
                sendErrorMessage = sendErrorMessage,
                sendErrorColor = sendErrorColor,
                palette = glassPalette,
                onLoadOlder = onLoadOlder,
                onSendMessage = onSendMessage,
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
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    isSendingMessage: Boolean,
    sendErrorMessage: String?,
    sendErrorColor: Int,
    palette: GlassPalette,
    onLoadOlder: () -> Unit,
    onSendMessage: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val colors = ChatMessageColors(
        ownBubble = MaterialTheme.colorScheme.primaryContainer.toArgb(),
        ownText = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
        ownMetadata = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
        otherBubble = MaterialTheme.colorScheme.surfaceVariant.toArgb(),
        otherText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        otherMetadata = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    )

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GlassChatLayout(context).apply {
                recyclerView.adapter = ChatMessageAdapter(colors)
                onLoadOlderMessages = onLoadOlder
                inputBar.onSendMessage = onSendMessage
                setPalette(palette)
                setPaginationState(
                    isLoadingOlder = isLoadingOlder,
                    canLoadOlder = canLoadOlder && !isLoading
                )
                setEmptyState(messages.isEmpty(), isLoading)
                setComposerState(
                    isSending = isSendingMessage,
                    errorMessage = sendErrorMessage,
                    errorColor = sendErrorColor
                )
            }
        },
        update = { chatLayout ->
            chatLayout.setPalette(palette)
            chatLayout.onLoadOlderMessages = onLoadOlder
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
            val colorsChanged = adapter.colors != colors
            adapter.colors = colors
            adapter.submitList(displayedMessages) {
                if (displayedMessages.isNotEmpty()) {
                    if (wasEmpty || (wasAtBottom && hasNewerMessage)) {
                        chatLayout.scrollToBottom(animated = false)
                    }
                }
                chatLayout.invalidateGlassContent()
                chatLayout.prefetchOlderMessagesIfNeeded()
            }
            if (colorsChanged) {
                adapter.notifyDataSetChanged()
            }
        }
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

private data class ChatMessageColors(
    val ownBubble: Int,
    val ownText: Int,
    val ownMetadata: Int,
    val otherBubble: Int,
    val otherText: Int,
    val otherMetadata: Int
)

private class ChatMessageAdapter(
    var colors: ChatMessageColors
) : ListAdapter<MatrixChatMessage, ChatMessageViewHolder>(ChatMessageDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        return ChatMessageViewHolder(parent)
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        holder.bind(getItem(position), colors)
    }
}

private class ChatMessageViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
    LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = parent.context.dpToPx(8)
        }
    }
) {
    private val row = itemView as LinearLayout
    private val maxContentWidth = parent.context.chatBubbleTextMaxWidthPx()
    private val bubble = LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            context.dpToPx(14),
            context.dpToPx(10),
            context.dpToPx(14),
            context.dpToPx(10)
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    private val senderView = TextView(parent.context).apply {
        setTypeface(typeface, Typeface.BOLD)
        textSize = 12f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = maxContentWidth
    }
    private val bodyView = TextView(parent.context).apply {
        textSize = 16f
        maxWidth = maxContentWidth
    }
    private val timeView = TextView(parent.context).apply {
        textSize = 11f
        gravity = Gravity.END
        maxWidth = maxContentWidth
    }

    init {
        bubble.addView(senderView)
        bubble.addView(bodyView)
        bubble.addView(timeView)
        row.addView(bubble)
    }

    fun bind(message: MatrixChatMessage, colors: ChatMessageColors) {
        row.gravity = if (message.isOwn) Gravity.END else Gravity.START

        val backgroundColor = if (message.isOwn) colors.ownBubble else colors.otherBubble
        val textColor = if (message.isOwn) colors.ownText else colors.otherText
        val metadataColor = if (message.isOwn) colors.ownMetadata else colors.otherMetadata
        bubble.background = GradientDrawable().apply {
            cornerRadius = itemView.context.dpToPx(18).toFloat()
            setColor(backgroundColor)
        }

        senderView.text = if (message.isOwn) "You" else message.sender
        senderView.setTextColor(metadataColor)
        bodyView.text = message.body
        bodyView.setTextColor(textColor)
        timeView.text = message.timestampMillis.formatMessageTime()
        timeView.setTextColor(metadataColor)
    }
}

private fun Long.formatMessageTime(): String {
    return MESSAGE_TIME_FORMATTER.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    )
}

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private const val NEWEST_EDGE_THRESHOLD = 1

private object ChatMessageDiffCallback : DiffUtil.ItemCallback<MatrixChatMessage>() {
    override fun areItemsTheSame(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: MatrixChatMessage, newItem: MatrixChatMessage): Boolean {
        return oldItem == newItem
    }
}

private fun android.content.Context.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
}

private fun android.content.Context.chatBubbleTextMaxWidthPx(): Int {
    val screenWidth = resources.displayMetrics.widthPixels
    val horizontalChrome = dpToPx(96)
    return (screenWidth - horizontalChrome)
        .coerceAtLeast(dpToPx(180))
        .coerceAtMost(dpToPx(520))
}
