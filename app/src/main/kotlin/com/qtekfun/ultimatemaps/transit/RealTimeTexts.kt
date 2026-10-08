package com.qtekfun.ultimatemaps.transit

import android.content.res.Resources
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Words for Renfe's real-time data ("Delayed 4 min", "Cancelled", "Alert: ..."), shared by the itinerary cards, the live
 * follower, the notification and the tests. Alert texts are shown as Renfe wrote them (Spanish).
 */
object RealTimeTexts {
    enum class Level { ON_TIME, LATE, CANCELLED }

    class Status(val text: String, val level: Level)

    /** Alerts longer than this are cut (a notification or a card is no place for a paragraph). */
    const val MAX_ALERT_CHARS = 200

    /** Under a minute either way counts as on time. */
    private const val ON_TIME_BAND_SEC = 60

    /** The train's own status, or null when the train was not found in the feed (nothing is said then, never "on time"). */
    fun status(res: Resources, rt: LegRealTime?): Status? {
        if (rt == null) return null
        if (rt.cancelled) return Status(res.getString(R.string.transit_rt_cancelled), Level.CANCELLED)
        val delay = rt.delaySec ?: return null
        val minutes = (abs(delay) / 60.0).roundToInt().coerceAtLeast(1)
        return when {
            delay >= ON_TIME_BAND_SEC -> Status(res.getQuantityString(R.plurals.transit_rt_delayed, minutes, minutes), Level.LATE)
            delay <= -ON_TIME_BAND_SEC -> Status(res.getQuantityString(R.plurals.transit_rt_early, minutes, minutes), Level.ON_TIME)
            else -> Status(res.getString(R.string.transit_rt_on_time), Level.ON_TIME)
        }
    }

    /** "Does not stop at X" and "Alert: ..." lines, in that order. */
    fun details(res: Resources, rt: LegRealTime?): List<String> {
        if (rt == null) return emptyList()
        val skips = rt.skippedStops.map { res.getString(R.string.transit_rt_skips, it) }
        val alerts = rt.alerts.map { res.getString(R.string.transit_rt_alert, it.shorten()) }
        return skips + alerts
    }

    /** "Real time (Renfe)" when [rt] knows the train, else the honest scheduled-times note [scheduledRes]. */
    fun note(res: Resources, rt: LegRealTime?, scheduledRes: Int): String =
        if (rt?.trainFound == true) res.getString(R.string.transit_rt_label) else res.getString(scheduledRes)

    private fun String.shorten(): String = if (length <= MAX_ALERT_CHARS) this else take(MAX_ALERT_CHARS - 1).trimEnd() + "…"
}
