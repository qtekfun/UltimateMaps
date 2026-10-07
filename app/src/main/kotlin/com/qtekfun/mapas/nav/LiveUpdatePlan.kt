package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.nav.NavProblem
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.voice.DistanceUnits
import java.util.Locale
import kotlin.math.roundToInt

/** The short words of the chip for the states without a distance (localized by the caller). */
data class ChipWords(val reroute: String, val noSignal: String)

/**
 * What the promoted ("Live Update") notification shows: the text of the status-bar chip and the trip progress.
 * Never contains a position, only a distance or a short word.
 */
data class LiveUpdatePlan(val chipText: String, val progressPercent: Int)

/** Pure decisions for the Android 16+ status-bar chip, unit-tested without Android. */
object LiveUpdatePolicy {
    /** First API level with `Notification.ProgressStyle` and `setShortCriticalText` (Android 16). */
    const val MIN_SDK = 36

    /**
     * The plan to promote with, or null when the notification must stay a normal one: the user turned the chip off,
     * the device is older than Android 16, there is no state yet, or the trip is over (a final "arrived" is normal).
     * The chip text is never blank when a plan exists.
     */
    fun plan(
        enabled: Boolean,
        sdkInt: Int,
        state: NavState?,
        problem: NavProblem?,
        units: DistanceUnits,
        locale: Locale,
        words: ChipWords,
    ): LiveUpdatePlan? {
        if (!enabled || sdkInt < MIN_SDK || state == null || state.status == NavStatus.ARRIVED) return null
        val text = when {
            problem != null -> words.noSignal
            state.status == NavStatus.NO_SIGNAL -> words.noSignal
            state.status == NavStatus.OFF_ROUTE || state.status == NavStatus.REROUTING -> words.reroute
            else -> compactDistance((state.nextManeuver?.distanceMeters ?: state.remainingMeters).coerceAtLeast(0.0), units, locale)
        }
        val total = state.traveledMeters + state.remainingMeters
        val percent = if (total > 0.0) (state.traveledMeters / total * 100.0).roundToInt().coerceIn(0, 100) else 0
        return LiveUpdatePlan(text.ifBlank { words.noSignal }, percent)
    }

    /**
     * Distance in at most 7 characters (the suggested maximum of a chip): "160 m", "1,2 km", "35 km", "500 ft",
     * "0.3 mi". Metres and feet are rounded to 10; kilometres and miles get one decimal below 10 and none from 10.
     */
    fun compactDistance(meters: Double, units: DistanceUnits, locale: Locale): String {
        val m = meters.coerceAtLeast(0.0)
        return when (units) {
            DistanceUnits.METRIC -> when {
                m < 1000 -> "${(m / 10).roundToInt() * 10} m"
                m < 9_950 -> String.format(locale, "%.1f km", m / 1000)
                else -> String.format(locale, "%d km", (m / 1000).roundToInt())
            }
            DistanceUnits.IMPERIAL -> {
                val feet = m * FEET_PER_METER
                val miles = m / METERS_PER_MILE
                when {
                    feet < 1000 -> "${(feet / 10).roundToInt() * 10} ft"
                    miles < 9.95 -> String.format(locale, "%.1f mi", miles)
                    else -> String.format(locale, "%d mi", miles.roundToInt())
                }
            }
        }
    }

    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_MILE = 1609.344
}

/**
 * Skips a notification update that would show exactly what is already shown (same title, text, chip and progress),
 * so the chip is only redrawn when its text changes. A forced update (arrival) always goes through.
 */
class NotificationDedupe {
    private var last: String? = null

    fun shouldPost(title: String, text: String, plan: LiveUpdatePlan?, force: Boolean = false): Boolean {
        val signature = "$title\u0000$text\u0000${plan?.chipText}\u0000${plan?.progressPercent}"
        if (!force && signature == last) return false
        last = signature
        return true
    }
}
