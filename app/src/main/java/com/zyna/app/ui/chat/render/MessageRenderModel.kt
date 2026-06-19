package com.zyna.app.ui.chat.render

import com.zyna.app.data.matrix.MatrixImageInfo

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
    val attributes: MessageRenderAttributes = MessageRenderAttributes(),
    val cluster: MessageCluster = MessageCluster()
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

internal data class MessageForwardPreview(
    val body: String,
    val forwardedFrom: String?
)

internal sealed interface MessageContent {
    data class Text(val body: String) : MessageContent
    data class Image(
        val imageInfo: MatrixImageInfo,
        val caption: String?
    ) : MessageContent
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
    val incomingMetadata: Int
) {
    fun bubbleColor(message: MessageRenderModel): Int {
        return message.attributes.bubbleColor
            ?: if (message.isOutgoing) outgoingBubble else incomingBubble
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
        is MessageContent.Image -> content.caption ?: "Photo"
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
    return "$sender$reply$forward, $body, $timestampText$state"
}

internal val MessageRenderModel.isRedacted: Boolean
    get() = content is MessageContent.Redacted

internal const val REDACTED_MESSAGE_TEXT = "Deleted message"
