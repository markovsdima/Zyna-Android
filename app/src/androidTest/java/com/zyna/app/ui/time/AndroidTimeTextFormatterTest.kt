package com.zyna.app.ui.time

import android.text.format.DateFormat as AndroidDateFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTimeTextFormatterTest {
    @Test
    fun format_matchesPlatformLocaleAndClockPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timestampMillis = 1_718_023_860_000L
        val expected = AndroidDateFormat.getTimeFormat(context).format(Date(timestampMillis))

        val actual = AndroidTimeTextFormatter(context).format(timestampMillis)

        assertEquals(expected, actual)
    }

    @Test
    fun format_reusesFormatterAndRebuildsWhenClockOrTimezoneChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var environment = TimeFormatEnvironment(
            localeTags = "en-US",
            uses24HourClock = false,
            timeZoneId = "UTC"
        )
        var formatterCreations = 0
        val formatter = AndroidTimeTextFormatter(
            context = context,
            environmentProvider = { environment },
            formatterFactory = { currentEnvironment ->
                formatterCreations += 1
                SimpleDateFormat(
                    if (currentEnvironment.uses24HourClock) "HH:mm" else "h:mm a",
                    Locale.US
                ).apply {
                    timeZone = TimeZone.getTimeZone(currentEnvironment.timeZoneId)
                }
            }
        )

        assertEquals("12:00 AM", formatter.format(0L))
        assertEquals("12:00 AM", formatter.format(0L))
        assertEquals(1, formatterCreations)

        environment = environment.copy(uses24HourClock = true)
        assertEquals("00:00", formatter.format(0L))
        assertEquals(2, formatterCreations)

        environment = environment.copy(timeZoneId = "GMT+02:00")
        assertEquals("02:00", formatter.format(0L))
        assertEquals(3, formatterCreations)
    }
}
