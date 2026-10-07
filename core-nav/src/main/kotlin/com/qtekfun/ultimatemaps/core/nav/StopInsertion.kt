package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.routing.RoutePlan

/** What happened to an "add a stop to the trip in progress" request; everything but [ADDED] leaves the trip untouched. */
enum class AddStopResult { ADDED, NOT_NAVIGATING, LIMIT, DUPLICATE, SAME_AS_DESTINATION, NO_ROUTE, BUSY }

/** The result of [NavigationController.addStop]: [plan] is the route now being followed when [result] is [AddStopResult.ADDED]. */
data class AddStopOutcome(val result: AddStopResult, val plan: RoutePlan? = null)

/** Rules for adding a stop in the middle of a trip. */
object StopInsertion {
    /** Intermediate stops still ahead plus the new one may not exceed this (same limit as the route preview). */
    const val MAX_STOPS = 5

    /** Two points this close count as the same place (same value as the route preview). */
    const val SAME_PLACE_METERS = 30.0

    /**
     * Where in [remaining] (the stops still ahead, in driving order) a new stop goes: the position that adds the
     * least straight-line detour to `from -> remaining... -> destination` (cheapest insertion). A stop that lies
     * on the way costs about nothing between the right two points; one behind the user or off the road goes where
     * leaving the plan costs least. Ties keep the earlier position (the nearer stop first).
     */
    fun insertionIndex(from: LatLon, remaining: List<LatLon>, destination: LatLon, stop: LatLon): Int {
        var best = remaining.size
        var bestCost = Double.MAX_VALUE
        for (i in 0..remaining.size) {
            val before = if (i == 0) from else remaining[i - 1]
            val after = if (i == remaining.size) destination else remaining[i]
            val cost = before.distanceTo(stop) + stop.distanceTo(after) - before.distanceTo(after)
            if (cost < bestCost - 1e-6) {
                bestCost = cost
                best = i
            }
        }
        return best
    }
}
