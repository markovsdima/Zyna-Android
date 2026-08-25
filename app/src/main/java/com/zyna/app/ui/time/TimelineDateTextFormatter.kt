package com.zyna.app.ui.time

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import com.zyna.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Immutable date environment captured once for a timeline presentation pass. */
internal data class TimelineDateFormattingSnapshot(
    val zoneId: ZoneId,
    val today: LocalDate,
    val todayText: String,
    val yesterdayText: String,
    val currentYearFormatter: DateTimeFormatter,
    val otherYearFormatter: DateTimeFormatter
) {
    fun localDate(timestampMillis: Long): LocalDate {
        return Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
    }

    fun dayStartMillis(date: LocalDate): Long {
        return date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    fun format(date: LocalDate): String {
        return when (date) {
            today -> todayText
            today.minusDays(1) -> yesterdayText
            else -> if (date.year == today.year) {
                currentYearFormatter.format(date)
            } else {
                otherYearFormatter.format(date)
            }
        }
    }
}

/** Builds locale- and timezone-aware snapshots without doing formatter work in the scroll path. */
internal class AndroidTimelineDateTextFormatter(
    private val context: Context
) {
    private data class Environment(
        val localeTag: String,
        val timeZoneId: String,
        val todayEpochDay: Long
    )

    private val lock = Any()
    private var cachedEnvironment: Environment? = null
    private var cachedSnapshot: TimelineDateFormattingSnapshot? = null

    fun snapshot(nowMillis: Long = System.currentTimeMillis()): TimelineDateFormattingSnapshot {
        return synchronized(lock) {
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val zoneId = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            val environment = Environment(
                localeTag = locale.toLanguageTag(),
                timeZoneId = zoneId.id,
                todayEpochDay = today.toEpochDay()
            )
            if (environment == cachedEnvironment && cachedSnapshot != null) {
                return@synchronized checkNotNull(cachedSnapshot)
            }

            val currentYearPattern = AndroidDateFormat.getBestDateTimePattern(locale, "dMMMM")
            TimelineDateFormattingSnapshot(
                zoneId = zoneId,
                today = today,
                todayText = context.getString(R.string.timeline_date_today),
                yesterdayText = context.getString(R.string.timeline_date_yesterday),
                currentYearFormatter = DateTimeFormatter.ofPattern(currentYearPattern, locale),
                otherYearFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
            ).also { snapshot ->
                cachedEnvironment = environment
                cachedSnapshot = snapshot
            }
        }
    }
}
