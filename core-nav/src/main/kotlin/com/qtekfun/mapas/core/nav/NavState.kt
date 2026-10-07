package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.Maneuver

/** Where the follower thinks the user is relative to the route. */
enum class NavStatus {
    /** Following the route. */
    ON_ROUTE,

    /** Confirmed off the route; a reroute has not started (or has failed and is waiting to retry). */
    OFF_ROUTE,

    /** Off the route and a new route is being computed. */
    REROUTING,

    /** Reached the destination. Final: later fixes are ignored. */
    ARRIVED,

    /** No usable fix for a while (tunnel); position and progress are estimated from the last speed. */
    NO_SIGNAL,
}

/** A maneuver and how far ahead it is along the route. */
data class ManeuverInfo(val maneuver: Maneuver, val distanceMeters: Double)

/**
 * Everything the UI needs, published as an immutable snapshot. [position] is the user projected onto the route
 * (or the dead-reckoned point when [estimated]); [offRouteMeters] is the distance from the raw fix to the route.
 */
data class NavState(
    val status: NavStatus,
    val position: LatLon,
    /** Route heading at [position], degrees clockwise from north. */
    val bearingDegrees: Float,
    val traveledMeters: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val nextManeuver: ManeuverInfo?,
    /** The maneuver after [nextManeuver], for a preview. */
    val followingManeuver: ManeuverInfo?,
    val speedLimitKmh: Int?,
    val overSpeedLimit: Boolean,
    /** Lanes of [nextManeuver]; empty when unknown. */
    val lanes: List<Lane>,
    /** True when [position] comes from dead reckoning rather than from a fix. */
    val estimated: Boolean,
    val speedMps: Double,
    val offRouteMeters: Double,
    /** Starts at 0 and increases each time the route is replaced by a reroute. */
    val routeRevision: Int,
)

enum class AnnouncementKind { FAR, NEAR, NOW }

/** A voice prompt request: say [kind] for [maneuver], which is [meters] ahead when emitted. Never repeated. */
data class Announcement(val maneuver: Maneuver, val kind: AnnouncementKind, val meters: Int)
