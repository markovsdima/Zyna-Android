package com.zyna.app.ui.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageReplyPreviewPolicyTest {
    @Test
    fun sentMessage_buildsReplyTarget() {
        val target = message(content = MessageContent.Text("hello")).toReplyPreviewOrNull()

        requireNotNull(target)
        assertEquals("event-1", target.eventId)
        assertEquals("hello", target.body)
    }

    @Test
    fun redactedAndSyntheticOutgoingMessages_areNotReplyTargets() {
        assertNull(message(content = MessageContent.Redacted).toReplyPreviewOrNull())
        assertNull(
            message(
                content = MessageContent.Text("pending"),
                outgoingEnvelopeId = "envelope-1"
            ).toReplyPreviewOrNull()
        )
    }

    private fun message(
        content: MessageContent,
        outgoingEnvelopeId: String? = null
    ): MessageRenderModel {
        return MessageRenderModel(
            id = "message-1",
            eventId = "event-1",
            senderId = "@alice:zyna.app",
            senderText = "Alice",
            content = content,
            timestampText = "12:00",
            isOutgoing = false,
            deliveryState = RenderDeliveryState.SENT,
            outgoingEnvelopeId = outgoingEnvelopeId
        )
    }
}
