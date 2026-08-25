package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.ui.time.TimelineDateFormattingSnapshot
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatTimelinePresentationCacheTest {
    @Test
    fun equalWindow_reusesWholePresentedList() {
        var presentationCount = 0
        val cache = cache { presentationCount += 1 }
        val messages = listOf(message("older", 1_000), message("newer", 2_000))
        val formatting = formatting()

        val first = cache.present(messages, null, false, false, formatting)
        val second = cache.present(messages.map { it.copy() }, null, false, false, formatting)

        assertSame(first, second)
        assertEquals(2, presentationCount)
    }

    @Test
    fun olderPage_reusesExistingRowsAndPresentsOnlyNewMessage() {
        var presentationCount = 0
        val cache = cache { presentationCount += 1 }
        val formatting = formatting()
        val initial = listOf(message("old", 2_000), message("new", 3_000))
        val first = cache.present(initial, null, false, true, formatting)
        val firstNewRow = first.filterNot { it is ChatTimelineItem.DateDivider }
            .first { it.id == "new" }

        val expanded = listOf(message("oldest", 1_000)) + initial.map { it.copy() }
        val second = cache.present(expanded, null, false, false, formatting)
        val secondNewRow = second.filterNot { it is ChatTimelineItem.DateDivider }
            .first { it.id == "new" }

        assertNotSame(first, second)
        assertSame(firstNewRow, secondNewRow)
        assertEquals(3, presentationCount)
    }

    @Test
    fun changedMessage_rebuildsOnlyChangedRow() {
        var presentationCount = 0
        val cache = cache { presentationCount += 1 }
        val formatting = formatting()
        val initial = listOf(message("old", 1_000), message("new", 2_000))
        cache.present(initial, null, false, false, formatting)

        cache.present(
            sourceMessages = listOf(initial[0], initial[1].copy(body = "updated")),
            currentUserId = null,
            hasNewerBoundary = false,
            hasOlderBoundary = false,
            formatting = formatting
        )

        assertEquals(3, presentationCount)
    }

    @Test
    fun dayBoundary_insertsStableDividerAfterEachNewestToOldestGroup() {
        val cache = cache {}
        val formatting = formatting(today = LocalDate.of(2026, 7, 13))
        val newer = message(
            id = "new",
            timestampMillis = LocalDate.of(2026, 7, 13)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli()
        )
        val older = message(
            id = "old",
            timestampMillis = LocalDate.of(2026, 7, 12)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli()
        )

        val result = cache.present(
            sourceMessages = listOf(older, newer),
            currentUserId = null,
            hasNewerBoundary = false,
            hasOlderBoundary = false,
            formatting = formatting
        )

        assertEquals(
            listOf("message:new", "date:20647", "message:old", "date:20646"),
            result.map(ChatTimelineItem::stableKey)
        )
    }

    private fun cache(onPresent: () -> Unit): ChatTimelinePresentationCache {
        return ChatTimelinePresentationCache { message, _ ->
            onPresent()
            message.toChatTimelineItem()
        }
    }

    private fun message(id: String, timestampMillis: Long): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            eventId = id,
            sender = "@alice:example.org",
            body = id,
            timestampMillis = timestampMillis,
            isOwn = false
        )
    }

    private fun formatting(
        today: LocalDate = LocalDate.of(2026, 7, 13)
    ): TimelineDateFormattingSnapshot {
        return TimelineDateFormattingSnapshot(
            zoneId = ZoneId.of("UTC"),
            today = today,
            todayText = "Today",
            yesterdayText = "Yesterday",
            currentYearFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.US),
            otherYearFormatter = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
        )
    }
}
