package com.zyna.app.ui.chat.render

import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.matrix.MatrixImageInfo
import com.zyna.app.data.matrix.MatrixMediaGroupItem
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.ui.chat.theme.MessageBubbleGradientSpec

internal data class MessageRenderModel(
    val id: String,
    val eventId: String? = null,
    val senderId: String = "",
    val senderDisplayName: String? = null,
    val senderText: String,
    val content: MessageContent,
    val timestampText: String,
    val isOutgoing: Boolean,
    val deliveryState: RenderDeliveryState,
    val replyInfo: MessageReplyPreview? = null,
    val forwardedFrom: String? = null,
    val editInfo: MessageEditPreview? = null,
    val isEdited: Boolean = false,
    val isEditPending: Boolean = false,
    val isEditFailed: Boolean = false,
    val outgoingEnvelopeId: String? = null,
    val redactionTargetMessageId: String? = null,
    val canForward: Boolean = false,
    val canRetryOutgoingEnvelope: Boolean = false,
    val canDiscardOutgoingEnvelope: Boolean = false,
    val reactions: List<MessageReactionRenderModel> = emptyList(),
    val attributes: MessageRenderAttributes = MessageRenderAttributes(),
    val cluster: MessageCluster = MessageCluster()
)

internal data class MessageReactionRenderModel(
    val key: String,
    val count: Int,
    val isOwn: Boolean,
    val isPendingRemoval: Boolean = false
)

internal data class MessageEditPreview(
    val messageId: String,
    val eventId: String,
    val body: String
)

internal data class MessageReplyPreview(
    val eventId: String,
    val senderId: String,
    val senderText: String,
    val body: String
)

internal fun MessageRenderModel.toReplyPreviewOrNull(): MessageReplyPreview? {
    val replyEventId = eventId?.takeIf { it.isNotBlank() } ?: return null
    if (content is MessageContent.Redacted || outgoingEnvelopeId != null) {
        return null
    }
    val replyBody = when (val currentContent = content) {
        is MessageContent.Text -> currentContent.body
        is MessageContent.Image -> currentContent.caption.normalizedMessageCaption() ?: "Photo"
        is MessageContent.PhotoGroup -> currentContent.caption.normalizedMessageCaption() ?: "Photo group"
        is MessageContent.Voice -> "Voice message"
        MessageContent.Redacted -> return null
    }.takeIf { it.isNotBlank() } ?: return null
    return MessageReplyPreview(
        eventId = replyEventId,
        senderId = senderId,
        senderText = senderText,
        body = replyBody
    )
}

internal data class MessageForwardPreview(
    val body: String,
    val forwardedFrom: String?,
    val caption: String? = null,
    val imageItems: List<MatrixForwardImageItem> = emptyList(),
    val captionPlacement: CaptionPlacement = CaptionPlacement.BOTTOM,
    val layoutOverride: MediaGroupLayoutOverride? = null
)

internal sealed interface MessageContent {
    data class Text(val body: String) : MessageContent
    data class Image(
        val imageInfo: MatrixImageInfo,
        val caption: String?,
        val captionPlacement: CaptionPlacement = CaptionPlacement.BOTTOM
    ) : MessageContent
    data class PhotoGroup(
        val items: List<MatrixMediaGroupItem>,
        val totalHint: Int,
        val caption: String?,
        val captionPlacement: CaptionPlacement,
        val layoutOverride: MediaGroupLayoutOverride?
    ) : MessageContent
    data class Voice(val audioInfo: MatrixAudioInfo) : MessageContent
    data object Redacted : MessageContent
}

internal enum class RenderDeliveryState {
    SENT,
    SENDING,
    FAILED
}

internal data class MessageRenderAttributes(
    val bubbleColor: Int? = null
)

internal data class MessageCluster(
    val isFirstInCluster: Boolean = true,
    val isLastInCluster: Boolean = true
)

internal data class MessageRenderTheme(
    val outgoingBubble: Int,
    val outgoingText: Int,
    val outgoingMetadata: Int,
    val incomingBubble: Int,
    val incomingText: Int,
    val incomingMetadata: Int,
    val outgoingBubbleGradient: MessageBubbleGradientSpec? = null,
    val systemEventBackground: Int = incomingBubble,
    val systemEventText: Int = incomingMetadata
) {
    fun bubbleColor(message: MessageRenderModel): Int {
        return message.attributes.bubbleColor
            ?: if (message.isOutgoing) outgoingBubble else incomingBubble
    }

    fun bubbleGradient(message: MessageRenderModel): MessageBubbleGradientSpec? {
        return outgoingBubbleGradient
            ?.takeIf { message.isOutgoing && message.attributes.bubbleColor == null }
    }

    fun textColor(message: MessageRenderModel): Int {
        return if (message.isOutgoing || message.attributes.bubbleColor != null) {
            outgoingText
        } else {
            incomingText
        }
    }

    fun metadataColor(message: MessageRenderModel): Int {
        return if (message.isOutgoing || message.attributes.bubbleColor != null) {
            outgoingMetadata
        } else {
            incomingMetadata
        }
    }
}

internal enum class MessageHitTarget {
    BUBBLE,
    OUTSIDE
}

internal fun MessageRenderModel.accessibilityText(): String {
    val body = when (content) {
        is MessageContent.Text -> content.body
        is MessageContent.Image -> content.caption.normalizedMessageCaption() ?: "Photo"
        is MessageContent.PhotoGroup -> content.caption.normalizedMessageCaption() ?: "Photo group"
        is MessageContent.Voice -> content.audioInfo.accessibilityText()
        MessageContent.Redacted -> REDACTED_MESSAGE_TEXT
    }
    val reply = replyInfo?.let { ", in reply to ${it.senderText}: ${it.body}" }.orEmpty()
    val forward = forwardedFrom?.let { ", forwarded from $it" }.orEmpty()
    val state = when (deliveryState) {
        RenderDeliveryState.SENT -> when {
            isEditPending -> ", editing"
            isEditFailed -> ", edit failed"
            isEdited -> ", edited"
            else -> ""
        }
        RenderDeliveryState.SENDING -> ", sending"
        RenderDeliveryState.FAILED -> ", failed"
    }
    val sender = senderText.takeIf { it.isNotBlank() } ?: if (isOutgoing) "You" else "Unknown sender"
    val reactionCount = reactions.sumOf { it.count }
        .takeIf { it > 0 }
        ?.let { ", $it reactions" }
        .orEmpty()
    return "$sender$reply$forward, $body, $timestampText$state$reactionCount"
}

internal val MessageRenderModel.isRedacted: Boolean
    get() = content is MessageContent.Redacted

internal const val REDACTED_MESSAGE_TEXT = "Deleted message"

private fun MatrixAudioInfo.accessibilityText(): String {
    val label = if (isVoice) "Voice message" else "Audio"
    val duration = durationMillis
        ?.takeIf { it > 0L }
        ?.let { ", ${it.formatAudioDuration()}" }
        .orEmpty()
    val captionText = caption.normalizedMessageCaption()
        ?.let { ": $it" }
        .orEmpty()
    return "$label$duration$captionText"
}

private fun Long.formatAudioDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
