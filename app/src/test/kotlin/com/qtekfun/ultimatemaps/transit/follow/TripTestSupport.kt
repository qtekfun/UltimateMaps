package com.qtekfun.ultimatemaps.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.FollowState
import com.qtekfun.ultimatemaps.core.transit.follow.FollowBasis
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripState

/** A made-up two-ride trip (no real data): metro L5 (A B C D E) then bus 27 (F G H), with walks around them. */
object TripTestSupport {
    const val T0 = 1_800_000_000L
    const val ZONE = "Europe/Madrid"

    val metro = LineInfo("L5", "Line five", 0xFF00AA00.toInt(), 0xFFFFFFFF.toInt(), 1)
    val bus = LineInfo("27", "Route 27", 0xFF1565C0.toInt(), 0xFFFFFFFF.toInt(), 3)
    private val lat = doubleArrayOf(40.0036, 40.0126, 40.0216, 40.0306, 40.0396)
    private val arrive = longArrayOf(600, 720, 870, 1020, 1170)
    private val depart = longArrayOf(600, 750, 900, 1050, 1170)
    val metroStops = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo").mapIndexed { i, n ->
        ItineraryStop(n, LatLon(lat[i], -3.0), T0 + arrive[i], T0 + depart[i])
    }
    val busStops = listOf(
        ItineraryStop("Foxtrot", LatLon(40.0396, -3.002), T0 + 1500, T0 + 1500),
        ItineraryStop("Golf", LatLon(40.0486, -3.002), T0 + 1620, T0 + 1650),
        ItineraryStop("Hotel", LatLon(40.0576, -3.002), T0 + 1800, T0 + 1800),
    )

    fun itinerary() = Itinerary(
        listOf(
            ItineraryLeg.Walk(null, "Alpha", LatLon(40.0, -3.0), metroStops[0].point, 520, T0, T0 + 420),
            ItineraryLeg.Ride(metro, "Westbound", metroStops),
            ItineraryLeg.Walk("Echo", "Foxtrot", metroStops[4].point, busStops[0].point, 220, T0 + 1170, T0 + 1350),
            ItineraryLeg.Ride(bus, "Hospital", busStops),
            ItineraryLeg.Walk("Hotel", null, busStops[2].point, LatLon(40.0586, -3.002), 130, T0 + 1800, T0 + 1920),
        ),
    )

    fun follow(phase: FollowPhase, block: FollowState.() -> FollowState = { this }): FollowState =
        FollowState(phase, legIndex = 0, basis = FollowBasis.GNSS).block()

    fun trip(state: FollowState, replanning: Boolean = false, failed: Boolean = false) =
        TransitTripState(itinerary(), state, ZONE, replanning, failed)
}
