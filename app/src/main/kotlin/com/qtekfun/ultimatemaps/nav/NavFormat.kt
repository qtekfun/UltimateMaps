package com.qtekfun.ultimatemaps.nav

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Texts of the navigation screen that are numbers: arrival time, speeds. Locale-aware, metric. */
object NavFormat {
    /** Clock time of [epochMillis] in the user's style (24 h in Spain, am/pm in English). */
    fun clockTime(epochMillis: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).also { it.timeZone = zone }.format(Date(epochMillis))

    /** Speed in km/h, rounded, from metres per second. */
    fun speedKmh(mps: Double): Int = (mps * 3.6).roundToInt().coerceAtLeast(0)

    /** Trip duration for the summary, "1 h 05 min" style without seconds. */
    fun tripDuration(millis: Long): String {
        val minutes = (millis / 60_000.0).roundToInt().coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0) "$m min" else if (m == 0) "$h h" else "$h h $m min"
    }
}
