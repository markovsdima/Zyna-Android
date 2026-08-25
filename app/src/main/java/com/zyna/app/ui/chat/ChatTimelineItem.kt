package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.ui.chat.render.MatrixTimelineEventPresenter
import com.zyna.app.ui.chat.render.SystemEventRenderKind
import com.zyna.app.ui.chat.render.SystemEventRenderModel
import com.zyna.app.ui.time.TimelineDateFormattingSnapshot

internal data class TimelineDateDividerModel(
    val epochDay: Long,
    val dayStartMillis: Long,
    val title: String
) {
    val stableKey: String
        get() = "date:$epochDay"
}

/**
 * Presentation-level rows shown by the chat RecyclerView.
 *
 * Event-backed rows keep their source available for ordering, jumps and semantic presentation.
 * Synthetic date rows carry a stable local-day model. Every kind exposes a namespaced [stableKey]
 * so different rows cannot accidentally share a RecyclerView stable id.
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

    data class DateDivider(
        val model: TimelineDateDividerModel
    ) : ChatTimelineItem {
        override val id: String
            get() = model.stableKey
        override val eventId: String? = null
        override val timestampMillis: Long
            get() = model.dayStartMillis
        override val stableKey: String
            get() = model.stableKey
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

/** Adds one stable divider after every newest-to-oldest day group. */
internal fun List<ChatTimelineItem>.withDateDividers(
    formatting: TimelineDateFormattingSnapshot
): List<ChatTimelineItem> {
    if (isEmpty()) return emptyList()

    val result = ArrayList<ChatTimelineItem>(size + minOf(size, 16))
    var date = formatting.localDate(first().timestampMillis)
    for (index in indices) {
        val item = this[index]
        result += item

        val olderDate = getOrNull(index + 1)
            ?.let { olderItem -> formatting.localDate(olderItem.timestampMillis) }
        if (olderDate == date) {
            continue
        }

        val epochDay = date.toEpochDay()
        result += ChatTimelineItem.DateDivider(
            TimelineDateDividerModel(
                epochDay = epochDay,
                dayStartMillis = formatting.dayStartMillis(date),
                title = formatting.format(date)
            )
        )
        if (olderDate != null) {
            date = olderDate
        }
    }
    return result
}

/** Associates every newest-to-oldest event row with the divider ending its day group. */
internal fun List<ChatTimelineItem>.dateDividersByPosition(): List<TimelineDateDividerModel?> {
    val result = MutableList<TimelineDateDividerModel?>(size) { null }
    var currentDivider: TimelineDateDividerModel? = null
    for (position in indices.reversed()) {
        val item = this[position]
        if (item is ChatTimelineItem.DateDivider) {
            currentDivider = item.model
        }
        result[position] = currentDivider
    }
    return result
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
        is ChatTimelineItem.DateDivider -> null
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
