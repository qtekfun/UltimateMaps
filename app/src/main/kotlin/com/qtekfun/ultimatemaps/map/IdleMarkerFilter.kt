package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.map.CompassMath
import kotlin.math.abs

/**
 * Decides whether the map's position marker has changed enough to be handed to the map library again. Every hand-over of the
 * marker's GeoJSON source makes the library parse it and place every label of the map again (a few percent of a core a second on
 * a mid-range phone, see docs/battery.md), so a phone lying still, whose GPS wobbles by a few metres and whose compass by a few
 * degrees, must not cause one a second. While navigating ([shouldShow]'s `navigating`) every change goes through: the marker is the
 * thing in motion.
 *
 * Pure: the caller passes the clock.
 */
class IdleMarkerFilter(
    private val minMoveMeters: Double = 3.0,
    private val minTurnDegrees: Float = 4f,
    private val refreshMillis: Long = 5_000L,
) {
    private var shown: LatLon? = null
    private var shownHeading = 0f
    private var shownAt = 0L

    /** True when [point] and [heading] should go out now; it then counts as shown. */
    fun shouldShow(point: LatLon, heading: Float, nowMillis: Long, navigating: Boolean): Boolean {
        val last = shown
        val quiet = last != null && !navigating && nowMillis - shownAt in 0 until refreshMillis &&
            last.distanceTo(point) < minMoveMeters && abs(CompassMath.delta(shownHeading, heading)) < minTurnDegrees
        if (quiet) return false
        shown = point
        shownHeading = heading
        shownAt = nowMillis
        return true
    }

    /** Forget what was shown (the map's source was recreated or cleared): the next one always goes out. */
    fun reset() {
        shown = null
    }
}
