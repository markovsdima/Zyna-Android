package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.ui.time.TimelineDateFormattingSnapshot
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineItemTest {
    @Test
    fun toChatTimelineItem_keepsTextAndNoticeAsMessageRows() {
        listOf(
            MatrixMessageContentType.TEXT,
            MatrixMessageContentType.NOTICE
        ).forEach { contentType ->
            val source = timelineEvent(contentType = contentType)

            val item = source.toChatTimelineItem()

            assertTrue(
                "Expected $contentType to remain a message row",
                item is ChatTimelineItem.Message
            )
            assertSame(source, (item as ChatTimelineItem.Message).source)
        }
    }

    @Test
    fun toChatTimelineItem_classifiesSystemAndCallRows() {
        val systemSource = timelineEvent(contentType = MatrixMessageContentType.SYSTEM_EVENT)
        val callSource = timelineEvent(contentType = MatrixMessageContentType.MATRIX_RTC_CALL)

        val systemItem = systemSource.toChatTimelineItem()
        val callItem = callSource.toChatTimelineItem()

        assertTrue(systemItem is ChatTimelineItem.SystemEvent)
        assertSame(systemSource, (systemItem as ChatTimelineItem.SystemEvent).source)
        assertTrue(callItem is ChatTimelineItem.CallEvent)
        assertSame(callSource, (callItem as ChatTimelineItem.CallEvent).source)
    }

    @Test
    fun stableKeys_areNamespacedByRowKind() {
        val messageItem = timelineEvent(
            id = "shared-id",
            contentType = MatrixMessageContentType.TEXT
        ).toChatTimelineItem()
        val systemItem = timelineEvent(
            id = "shared-id",
            contentType = MatrixMessageContentType.SYSTEM_EVENT
        ).toChatTimelineItem()
        val callItem = timelineEvent(
            id = "shared-id",
            contentType = MatrixMessageContentType.MATRIX_RTC_CALL
        ).toChatTimelineItem()

        assertEquals("message:shared-id", messageItem.stableKey)
        assertEquals("system:shared-id", systemItem.stableKey)
        assertEquals("call:shared-id", callItem.stableKey)
        assertEquals(3, setOf(messageItem.stableKey, systemItem.stableKey, callItem.stableKey).size)
    }

    @Test
    fun messageOrNull_exposesOnlyMessageRows() {
        val messageSource = timelineEvent(contentType = MatrixMessageContentType.NOTICE)
        val systemSource = timelineEvent(contentType = MatrixMessageContentType.SYSTEM_EVENT)
        val callSource = timelineEvent(contentType = MatrixMessageContentType.MATRIX_RTC_CALL)

        assertSame(messageSource, messageSource.toChatTimelineItem().messageOrNull())
        assertNull(systemSource.toChatTimelineItem().messageOrNull())
        assertNull(callSource.toChatTimelineItem().messageOrNull())
    }

    @Test
    fun readReceiptEventOrNull_exposesSourceForEveryEventBackedRow() {
        val sources = listOf(
            timelineEvent(contentType = MatrixMessageContentType.TEXT),
            timelineEvent(contentType = MatrixMessageContentType.SYSTEM_EVENT),
            timelineEvent(contentType = MatrixMessageContentType.MATRIX_RTC_CALL)
        )

        sources.forEach { source ->
            assertSame(source, source.toChatTimelineItem().readReceiptEventOrNull())
        }
    }

    @Test
    fun withDateDividers_addsOneStableRowAfterEachNewestToOldestDayGroup() {
        val newerDate = LocalDate.of(2026, 7, 13)
        val olderDate = newerDate.minusDays(1)
        val formatting = dateFormatting(today = newerDate)
        val items = listOf(
            timelineEvent(id = "new-1", timestampMillis = noonMillis(newerDate)),
            timelineEvent(id = "new-2", timestampMillis = noonMillis(newerDate) - 1_000L),
            timelineEvent(id = "old-1", timestampMillis = noonMillis(olderDate))
        ).map { message -> message.toChatTimelineItem() }

        val rows = items.withDateDividers(formatting)

        assertEquals(
            listOf(
                "message:new-1",
                "message:new-2",
                "date:${newerDate.toEpochDay()}",
                "message:old-1",
                "date:${olderDate.toEpochDay()}"
            ),
            rows.map { it.stableKey }
        )
        assertEquals("Today", (rows[2] as ChatTimelineItem.DateDivider).model.title)
        assertEquals("Yesterday", (rows[4] as ChatTimelineItem.DateDivider).model.title)
        assertNull(rows[2].readReceiptEventOrNull())
        assertEquals(
            listOf(
                newerDate.toEpochDay(),
                newerDate.toEpochDay(),
                newerDate.toEpochDay(),
                olderDate.toEpochDay(),
                olderDate.toEpochDay()
            ),
            rows.dateDividersByPosition().map { model -> model?.epochDay }
        )
    }

    private fun timelineEvent(
        id: String = "event-id",
        contentType: MatrixMessageContentType = MatrixMessageContentType.TEXT,
        timestampMillis: Long = 1_700_000_000_000L
    ): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            eventId = id,
            sender = "@alice:example.org",
            body = "Event body",
            timestampMillis = timestampMillis,
            isOwn = false,
            contentType = contentType
        )
    }

    private fun dateFormatting(today: LocalDate): TimelineDateFormattingSnapshot {
        return TimelineDateFormattingSnapshot(
            zoneId = ZoneId.of("UTC"),
            today = today,
            todayText = "Today",
            yesterdayText = "Yesterday",
            currentYearFormatter = DateTimeFormatter.ofPattern("MMMM d"),
            otherYearFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        )
    }

    private fun noonMillis(date: LocalDate): Long {
        return date.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }
}
