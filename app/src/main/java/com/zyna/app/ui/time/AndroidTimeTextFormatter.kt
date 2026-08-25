package com.zyna.app.ui.time

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/** Formats a timeline timestamp using the device's locale and 12/24-hour preference. */
internal fun interface TimeTextFormatter {
    fun format(timestampMillis: Long): String
}

/**
 * Reuses the relatively expensive platform formatter while following runtime locale, clock and
 * timezone changes. [DateFormat] is mutable and not thread-safe, so cache access and formatting
 * deliberately share the same lock.
 */
internal class AndroidTimeTextFormatter(
    private val context: Context,
    private val environmentProvider: () -> TimeFormatEnvironment = {
        context.currentTimeFormatEnvironment()
    },
    private val formatterFactory: (TimeFormatEnvironment) -> DateFormat = { environment ->
        AndroidDateFormat.getTimeFormat(context).apply {
            timeZone = TimeZone.getTimeZone(environment.timeZoneId)
        }
    }
) : TimeTextFormatter {
    private val lock = Any()
    private var cachedEnvironment: TimeFormatEnvironment? = null
    private var cachedFormatter: DateFormat? = null

    override fun format(timestampMillis: Long): String {
        return synchronized(lock) {
            val environment = environmentProvider()
            val formatter = if (
                cachedEnvironment == environment &&
                cachedFormatter != null
            ) {
                checkNotNull(cachedFormatter)
            } else {
                formatterFactory(environment).also { nextFormatter ->
                    cachedEnvironment = environment
                    cachedFormatter = nextFormatter
                }
            }
            formatter.format(Date(timestampMillis))
        }
    }
}

internal data class TimeFormatEnvironment(
    val localeTags: String,
    val uses24HourClock: Boolean,
    val timeZoneId: String
)

private fun Context.currentTimeFormatEnvironment(): TimeFormatEnvironment {
    return TimeFormatEnvironment(
        localeTags = resources.configuration.locales.toLanguageTags(),
        uses24HourClock = AndroidDateFormat.is24HourFormat(this),
        timeZoneId = TimeZone.getDefault().id
    )
}
