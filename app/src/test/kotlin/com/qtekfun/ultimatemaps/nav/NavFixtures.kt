package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.ManeuverInfo
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.SpeedLimit
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.math.cos
import kotlin.math.hypot

private const val M = 111_194.9266

/** A point [east] metres east and [north] metres north of (40, -3), on the local plane. */
fun pt(east: Double, north: Double) = LatLon(40.0 + north / M, -3.0 + east / (M * cos(Math.toRadians(40.0))))

/**
 * 1 km north, a right turn onto "Calle de Alcalá" with two lanes (the right one recommended), 500 m east, a slight
 * left onto "Gran Vía", 500 m more, arrival. The whole route has a 50 km/h limit. A point every 20 m.
 */
fun cityPlan(): RoutePlan {
    val pts = ArrayList<LatLon>()
    var e = 0.0
    var n = 0.0
    pts += pt(0.0, 0.0)
    fun lineTo(e2: Double, n2: Double) {
        val parts = maxOf(1, Math.ceil(hypot(e2 - e, n2 - n) / 20.0).toInt())
        for (i in 1..parts) pts += pt(e + (e2 - e) * i / parts, n + (n2 - n) * i / parts)
        e = e2
        n = n2
    }
    lineTo(0.0, 1000.0)
    val corner = pts.lastIndex
    lineTo(500.0, 1000.0)
    val second = pts.lastIndex
    lineTo(1000.0, 1000.0)
    val lanes = listOf(
        Lane(setOf(LaneDirection.LEFT, LaneDirection.THROUGH), recommended = false),
        Lane(setOf(LaneDirection.RIGHT), recommended = true),
    )
    val guidance = RouteGuidance(
        maneuvers = listOf(
            Maneuver(0, TurnType.DEPART),
            Maneuver(corner, TurnType.RIGHT, "Calle de Alcalá", lanes = lanes),
            Maneuver(second, TurnType.SLIGHT_LEFT, "Gran Vía"),
            Maneuver(pts.lastIndex, TurnType.ARRIVE),
        ),
        speedLimits = listOf(SpeedLimit(0, pts.lastIndex, 50)),
    )
    var length = 0.0
    for (i in 1 until pts.size) length += hypot((pts[i].lon - pts[i - 1].lon) * M * cos(Math.toRadians(40.0)), (pts[i].lat - pts[i - 1].lat) * M)
    return RoutePlan(pts, length, length / 13.9, guidance)
}

/** Index of the geometry point closest to [along] metres, for the plans of this file (a point every 20 m). */
fun cityIndexAt(along: Double): Int = (along / 20.0).toInt()

/** A hand-made snapshot for the screen tests. */
fun navState(
    status: NavStatus = NavStatus.ON_ROUTE,
    next: ManeuverInfo? = ManeuverInfo(Maneuver(5, TurnType.RIGHT, "Calle de Alcalá"), 300.0),
    following: ManeuverInfo? = null,
    lanes: List<Lane> = emptyList(),
    speedLimitKmh: Int? = null,
    speedMps: Double = 10.0,
    over: Boolean = false,
    remainingMeters: Double = 12_400.0,
    remainingSeconds: Double = 1_100.0,
) = NavState(
    status = status, position = LatLon(40.0, -3.0), bearingDegrees = 90f, traveledMeters = 100.0,
    remainingMeters = remainingMeters, remainingSeconds = remainingSeconds, nextManeuver = next, followingManeuver = following,
    speedLimitKmh = speedLimitKmh, overSpeedLimit = over, lanes = lanes, estimated = status == NavStatus.NO_SIGNAL,
    speedMps = speedMps, offRouteMeters = 0.0, routeRevision = 0,
)
