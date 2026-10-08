package com.qtekfun.ultimatemaps.weather

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Times in the warning texts: "18:00" for today, "Fri 18:00" for another day, always in the device time zone and locale. */
object WeatherTimes {
    fun text(millis: Long, nowMillis: Long, zone: ZoneId, locale: Locale): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
        if (at.toLocalDate() == today) return time
        return DateTimeFormatter.ofPattern("EEE", locale).format(at) + " " + time
    }
}
