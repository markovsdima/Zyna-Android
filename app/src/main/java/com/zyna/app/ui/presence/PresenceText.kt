package com.zyna.app.ui.presence

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.zyna.app.R
import com.zyna.app.data.presence.PresenceAvailability
import com.zyna.app.data.presence.UserPresenceStatus
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

object PresenceText {
    enum class LastSeenStyle {
        CHAT,
        EXPANDED
    }

    fun isOnline(status: UserPresenceStatus?): Boolean {
        return status?.availability == PresenceAvailability.ONLINE
    }

    fun label(
        context: Context,
        status: UserPresenceStatus?,
        style: LastSeenStyle = LastSeenStyle.CHAT,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        if (status == null) {
            return null
        }
        if (isOnline(status)) {
            return context.getString(R.string.presence_online)
        }
        val lastSeenAtMillis = status.lastSeenAtMillis ?: return null
        val diffMillis = (nowMillis - lastSeenAtMillis).coerceAtLeast(0L)
        if (diffMillis < DateUtils.MINUTE_IN_MILLIS) {
            return context.getString(R.string.presence_last_seen_just_now)
        }
        if (style == LastSeenStyle.CHAT && diffMillis < DateUtils.HOUR_IN_MILLIS) {
            val minutes = (diffMillis / DateUtils.MINUTE_IN_MILLIS).coerceAtLeast(1L)
            return context.quantityString(
                resId = R.plurals.presence_last_seen_minutes_ago,
                quantity = minutes
            )
        }

        val zone = ZoneId.systemDefault()
        val lastDate = Instant.ofEpochMilli(lastSeenAtMillis).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val dayDiff = ChronoUnit.DAYS.between(lastDate, nowDate)
        val timeString = formatShortTime(context, lastSeenAtMillis)

        return when (dayDiff) {
            0L -> {
                if (style == LastSeenStyle.CHAT) {
                    val hours = (diffMillis / DateUtils.HOUR_IN_MILLIS).coerceAtLeast(1L)
                    context.quantityString(
                        resId = R.plurals.presence_last_seen_hours_ago,
                        quantity = hours
                    )
                } else {
                    context.getString(R.string.presence_last_seen_today_at, timeString)
                }
            }
            1L -> context.getString(R.string.presence_last_seen_yesterday_at, timeString)
            else -> {
                val dateString = formatShortDate(
                    lastSeenAtMillis = lastSeenAtMillis,
                    includeYear = lastDate.year != nowDate.year
                )
                context.getString(R.string.presence_last_seen_date, dateString)
            }
        }
    }

    private fun formatShortTime(context: Context, lastSeenAtMillis: Long): String {
        return DateFormat.getTimeFormat(context).format(Date(lastSeenAtMillis))
    }

    private fun formatShortDate(lastSeenAtMillis: Long, includeYear: Boolean): String {
        val locale = Locale.getDefault()
        val skeleton = if (includeYear) "MMMd y" else "MMMd"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(Date(lastSeenAtMillis))
    }

    private fun Context.quantityString(
        resId: Int,
        quantity: Long
    ): String {
        val quantityInt = quantity.toInt()
        return resources.getQuantityString(resId, quantityInt, quantityInt)
    }
}
