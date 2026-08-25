package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.ui.time.TimelineDateFormattingSnapshot
import java.time.LocalDate

/**
 * Reuses semantic rows and local-day calculations across chat state emissions.
 *
 * Loading/composer changes usually hit the whole-result cache. Pagination still walks the source
 * window for grouping correctness, but only newly added or changed messages are presented again.
 */
internal class ChatTimelinePresentationCache(
    private val presentItem: (MatrixChatMessage, String?) -> ChatTimelineItem
) {
    private data class CachedItem(
        val source: MatrixChatMessage,
        val item: ChatTimelineItem,
        val epochDay: Long
    )

    private val itemCache = object : LinkedHashMap<String, CachedItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedItem>?): Boolean {
            return size > MAX_CACHED_ITEMS
        }
    }
    private val dividerCache = mutableMapOf<Long, ChatTimelineItem.DateDivider>()

    private var lastSourceMessages: List<MatrixChatMessage>? = null
    private var lastCurrentUserId: String? = null
    private var lastHasNewerBoundary = false
    private var lastHasOlderBoundary = false
    private var lastFormatting: TimelineDateFormattingSnapshot? = null
    private var lastResult: List<ChatTimelineItem>? = null

    var lastWholeResultHit: Boolean = false
        private set
    var lastReusedItemCount: Int = 0
        private set
    var lastRebuiltItemCount: Int = 0
        private set

    fun present(
        sourceMessages: List<MatrixChatMessage>,
        currentUserId: String?,
        hasNewerBoundary: Boolean,
        hasOlderBoundary: Boolean,
        formatting: TimelineDateFormattingSnapshot
    ): List<ChatTimelineItem> {
        if (
            currentUserId == lastCurrentUserId &&
            hasNewerBoundary == lastHasNewerBoundary &&
            hasOlderBoundary == lastHasOlderBoundary &&
            formatting === lastFormatting &&
            sourceMessages.sameContentsAs(lastSourceMessages)
        ) {
            lastWholeResultHit = true
            lastReusedItemCount = 0
            lastRebuiltItemCount = 0
            return checkNotNull(lastResult)
        }

        lastWholeResultHit = false
        lastReusedItemCount = 0
        lastRebuiltItemCount = 0

        if (currentUserId != lastCurrentUserId || formatting !== lastFormatting) {
            itemCache.clear()
        }
        if (formatting !== lastFormatting) {
            dividerCache.clear()
        }

        val groupedMessages = sourceMessages
            .asReversed()
            .withMediaGroupPresentation(
                hasNewerBoundary = hasNewerBoundary,
                hasOlderBoundary = hasOlderBoundary
            )
        val result = ArrayList<ChatTimelineItem>(groupedMessages.size + 16)
        var pendingItem: ChatTimelineItem? = null
        var pendingEpochDay = 0L

        for (message in groupedMessages) {
            val cached = itemCache[message.id]
            val presented = if (cached?.source == message) {
                lastReusedItemCount += 1
                cached
            } else {
                lastRebuiltItemCount += 1
                val item = presentItem(message, currentUserId)
                CachedItem(
                    source = message,
                    item = item,
                    epochDay = formatting.localDate(item.timestampMillis).toEpochDay()
                ).also { next -> itemCache[message.id] = next }
            }

            val previousItem = pendingItem
            if (previousItem != null) {
                result += previousItem
                if (pendingEpochDay != presented.epochDay) {
                    result += dividerFor(pendingEpochDay, formatting)
                }
            }
            pendingItem = presented.item
            pendingEpochDay = presented.epochDay
        }

        pendingItem?.let { lastItem ->
            result += lastItem
            result += dividerFor(pendingEpochDay, formatting)
        }

        lastSourceMessages = sourceMessages
        lastCurrentUserId = currentUserId
        lastHasNewerBoundary = hasNewerBoundary
        lastHasOlderBoundary = hasOlderBoundary
        lastFormatting = formatting
        lastResult = result
        return result
    }

    fun clear() {
        itemCache.clear()
        dividerCache.clear()
        lastSourceMessages = null
        lastFormatting = null
        lastResult = null
        lastWholeResultHit = false
        lastReusedItemCount = 0
        lastRebuiltItemCount = 0
    }

    private fun dividerFor(
        epochDay: Long,
        formatting: TimelineDateFormattingSnapshot
    ): ChatTimelineItem.DateDivider {
        return dividerCache.getOrPut(epochDay) {
            val date = LocalDate.ofEpochDay(epochDay)
            ChatTimelineItem.DateDivider(
                TimelineDateDividerModel(
                    epochDay = epochDay,
                    dayStartMillis = formatting.dayStartMillis(date),
                    title = formatting.format(date)
                )
            )
        }
    }

    private fun List<MatrixChatMessage>.sameContentsAs(
        other: List<MatrixChatMessage>?
    ): Boolean {
        if (this === other) return true
        if (other == null || size != other.size) return false
        for (index in indices) {
            if (this[index] != other[index]) return false
        }
        return true
    }

    private companion object {
        const val MAX_CACHED_ITEMS = 2_000
    }
}
