package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.ui.chat.render.MatrixTimelineEventPresenter
import com.zyna.app.ui.chat.render.SystemEventRenderKind
import com.zyna.app.ui.chat.render.SystemEventRenderModel

/**
 * Presentation-level rows shown by the chat RecyclerView.
 *
 * A row deliberately keeps the source event available for ordering, jumps and future semantic
 * presentation, while exposing a namespaced [stableKey] so different row kinds cannot accidentally
 * share a RecyclerView stable id.
 */
internal sealed interface ChatTimelineItem {
    val id: String
    val eventId: String?
    val timestampMillis: Long
    val stableKey: String

    data class Message(
        val source: MatrixChatMessage
    ) : ChatTimelineItem {
        override val id: String
            get() = source.id
        override val eventId: String?
            get() = source.eventId
        override val timestampMillis: Long
            get() = source.timestampMillis
        override val stableKey: String
            get() = "message:${source.id}"
    }

    data class SystemEvent(
        val source: MatrixChatMessage,
        val renderModel: SystemEventRenderModel = source.fallbackSystemEventRenderModel(
            kind = SystemEventRenderKind.SYSTEM_EVENT
        )
    ) : ChatTimelineItem {
        override val id: String
            get() = source.id
        override val eventId: String?
            get() = source.eventId
        override val timestampMillis: Long
            get() = source.timestampMillis
        override val stableKey: String
            get() = "system:${source.id}"
    }

    data class CallEvent(
        val source: MatrixChatMessage,
        val renderModel: SystemEventRenderModel = source.fallbackSystemEventRenderModel(
            kind = SystemEventRenderKind.CALL_EVENT
        )
    ) : ChatTimelineItem {
        override val id: String
            get() = source.id
        override val eventId: String?
            get() = source.eventId
        override val timestampMillis: Long
            get() = source.timestampMillis
        override val stableKey: String
            get() = "call:${source.id}"
    }
}

internal fun MatrixChatMessage.toChatTimelineItem(): ChatTimelineItem {
    return when (contentType) {
        MatrixMessageContentType.SYSTEM_EVENT -> ChatTimelineItem.SystemEvent(this)
        MatrixMessageContentType.MATRIX_RTC_CALL -> ChatTimelineItem.CallEvent(this)
        else -> ChatTimelineItem.Message(this)
    }
}

internal fun MatrixChatMessage.toChatTimelineItem(
    presenter: MatrixTimelineEventPresenter,
    currentUserId: String?
): ChatTimelineItem {
    return when (contentType) {
        MatrixMessageContentType.SYSTEM_EVENT -> ChatTimelineItem.SystemEvent(
            source = this,
            renderModel = presenter.present(this, currentUserId)
        )
        MatrixMessageContentType.MATRIX_RTC_CALL -> ChatTimelineItem.CallEvent(
            source = this,
            renderModel = presenter.present(this, currentUserId)
        )
        else -> ChatTimelineItem.Message(this)
    }
}

/** Returns a source message only for rows allowed to enter message-specific UI paths. */
internal fun ChatTimelineItem.messageOrNull(): MatrixChatMessage? {
    return (this as? ChatTimelineItem.Message)?.source
}

/** Matrix event backing rows that are valid read-receipt anchors (date rows will return null). */
internal fun ChatTimelineItem.readReceiptEventOrNull(): MatrixChatMessage? {
    return when (this) {
        is ChatTimelineItem.Message -> source
        is ChatTimelineItem.SystemEvent -> source
        is ChatTimelineItem.CallEvent -> source
    }
}

private fun MatrixChatMessage.fallbackSystemEventRenderModel(
    kind: SystemEventRenderKind
): SystemEventRenderModel {
    return SystemEventRenderModel(
        text = body,
        accessibilityText = body,
        kind = kind
    )
}
