package com.zyna.app.ui.chat.render

internal data class MessageRenderModel(
    val id: String,
    val senderText: String,
    val content: MessageContent,
    val timestampText: String,
    val isOutgoing: Boolean,
    val deliveryState: RenderDeliveryState,
    val outgoingEnvelopeId: String? = null,
    val canRetryOutgoingEnvelope: Boolean = false,
    val canDiscardOutgoingEnvelope: Boolean = false,
    val attributes: MessageRenderAttributes = MessageRenderAttributes(),
    val cluster: MessageCluster = MessageCluster()
)

internal sealed interface MessageContent {
    data class Text(val body: String) : MessageContent
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
    }
    val state = when (deliveryState) {
        RenderDeliveryState.SENT -> ""
        RenderDeliveryState.SENDING -> ", sending"
        RenderDeliveryState.FAILED -> ", failed"
    }
    val sender = senderText.takeIf { it.isNotBlank() } ?: if (isOutgoing) "You" else "Unknown sender"
    return "$sender, $body, $timestampText$state"
}
