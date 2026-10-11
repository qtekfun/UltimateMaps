package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.abs

/**
 * Tells when the drive is "cruising": fast, on a straight stretch, nowhere near a maneuver, with a trustworthy position. Then the
 * arrow and the camera can be redrawn a few times a second, or once, instead of every frame (a car at 30 m/s hardly changes
 * what the driver needs to see between two redraws, and the display can drop its refresh rate). Pure: the caller gives the numbers
 * and the clock.
 *
 * Entering takes [ENTER_AFTER_MILLIS] of conditions holding (no flapping at a slip road); leaving is immediate, and the look-ahead
 * ([MIN_MANEUVER_METERS], [AHEAD_METERS]) is long enough (about 45 s at motorway speed) for the full rate to be back before the
 * driver reaches anything.
 */
class CruiseDetector {
    private var since = Long.MIN_VALUE
    var cruising = false
        private set

    /**
     * One update. [maneuverMeters]: distance to the next maneuver (null: none left). [headingChangeDegrees]: the largest change of
     * route direction within [AHEAD_METERS] ahead. [steady]: on route, a real position (not estimated, not in a tunnel).
     */
    fun update(nowMillis: Long, speedMps: Double, maneuverMeters: Double?, headingChangeDegrees: Double, steady: Boolean): Boolean {
        val ok = steady && speedMps >= MIN_SPEED_MPS && (maneuverMeters == null || maneuverMeters >= MIN_MANEUVER_METERS) &&
            headingChangeDegrees <= MAX_HEADING_CHANGE_DEGREES
        if (!ok) {
            since = Long.MIN_VALUE
            cruising = false
        } else {
            if (since == Long.MIN_VALUE) since = nowMillis
            if (nowMillis - since >= ENTER_AFTER_MILLIS) cruising = true
        }
        return cruising
    }

    fun reset() {
        since = Long.MIN_VALUE
        cruising = false
    }

    companion object {
        /** About 72 km/h. */
        const val MIN_SPEED_MPS = 20.0
        const val MIN_MANEUVER_METERS = 1_500.0
        const val MAX_HEADING_CHANGE_DEGREES = 8.0
        const val AHEAD_METERS = 600.0
        const val ENTER_AFTER_MILLIS = 10_000L

        /** The largest change of route direction (degrees) between [alongMeters] and [AHEAD_METERS] further on, sampled every 150 m. */
        fun headingChange(geometry: RouteGeometry, alongMeters: Double): Double {
            val start = geometry.bearingAt(alongMeters).toDouble()
            var worst = 0.0
            var d = 150.0
            while (d <= AHEAD_METERS) {
                val b = geometry.bearingAt((alongMeters + d).coerceAtMost(geometry.totalMeters)).toDouble()
                worst = maxOf(worst, abs(NavCameraPlanner.signedDelta(start, b)))
                d += 150.0
            }
            return worst
        }
    }
}
