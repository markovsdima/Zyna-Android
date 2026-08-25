package com.zyna.app.ui.time

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineDateFormattingSnapshotTest {
    @Test
    fun format_handlesRelativeCurrentYearAndOtherYearDates() {
        val today = LocalDate.of(2026, 7, 13)
        val snapshot = snapshot(today = today)

        assertEquals("Today", snapshot.format(today))
        assertEquals("Yesterday", snapshot.format(today.minusDays(1)))
        assertEquals("January 5", snapshot.format(LocalDate.of(2026, 1, 5)))
        assertEquals("Jan 5, 2025", snapshot.format(LocalDate.of(2025, 1, 5)))
    }

    @Test
    fun dayStart_roundTripsAcrossDstBoundary() {
        val date = LocalDate.of(2026, 3, 8)
        val snapshot = snapshot(today = date, zoneId = ZoneId.of("America/New_York"))

        assertEquals(date, snapshot.localDate(snapshot.dayStartMillis(date)))
    }

    private fun snapshot(
        today: LocalDate,
        zoneId: ZoneId = ZoneId.of("UTC")
    ): TimelineDateFormattingSnapshot {
        return TimelineDateFormattingSnapshot(
            zoneId = zoneId,
            today = today,
            todayText = "Today",
            yesterdayText = "Yesterday",
            currentYearFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.US),
            otherYearFormatter = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
        )
    }
}
