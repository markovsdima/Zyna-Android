package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixMessageContentType
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

    private fun timelineEvent(
        id: String = "event-id",
        contentType: MatrixMessageContentType
    ): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            eventId = id,
            sender = "@alice:example.org",
            body = "Event body",
            timestampMillis = 1_700_000_000_000L,
            isOwn = false,
            contentType = contentType
        )
    }
}
