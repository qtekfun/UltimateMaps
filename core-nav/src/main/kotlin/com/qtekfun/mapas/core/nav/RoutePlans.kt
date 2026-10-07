package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.SpeedLimit

private fun LatLon.isUsable() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

/** True when the plan can be followed: at least one usable point (a single one becomes a zero-length route). */
fun RoutePlan.isFollowable(): Boolean = geometry.any { it.isUsable() }

/**
 * A plan the follower can never choke on: points with NaN, infinite or out-of-range coordinates are dropped
 * (the indices of maneuvers, speed limits and stops are remapped), and a plan left with fewer than two points
 * gets its single point doubled (a zero-length route that arrives at once). Repeated points are kept: they only
 * make zero-length segments, which the geometry handles. Returns `this` when nothing needs fixing.
 * A plan with no usable point at all cannot be repaired: check [isFollowable] first.
 */
fun RoutePlan.sanitized(): RoutePlan {
    val usable = geometry.count { it.isUsable() }
    if (usable == geometry.size && usable >= 2) return this
    require(usable >= 1) { "the route has no usable point" }
    val remap = IntArray(geometry.size + 1)
    val points = ArrayList<LatLon>(usable + 1)
    for (i in geometry.indices) {
        remap[i] = points.size
        if (geometry[i].isUsable()) points += geometry[i]
    }
    remap[geometry.size] = points.size
    if (points.size == 1) points += points[0]
    val last = points.size - 1
    fun map(i: Int) = if (i < 0) 0 else if (i >= geometry.size) last else remap[i].coerceAtMost(last)
    val g = guidance
    return copy(
        geometry = points,
        guidance = RouteGuidance(
            g.maneuvers.map { it.copy(geometryIndex = map(it.geometryIndex)) },
            g.speedLimits.map { SpeedLimit(map(it.startIndex), map(it.endIndex), it.kmh) },
            g.stops.map(::map),
        ),
    )
}

/**
 * Adds the intermediate stops [via] (as passed in the `RouteRequest`) to the guidance: each point is projected onto
 * the route and the geometry index nearest to the projection goes to [RouteGuidance.stops], in route order. Engines
 * that do not report stops themselves get them this way. A point farther than [maxOffRouteMeters] from the route is
 * ignored. Returns the plan unchanged when [via] is empty or the plan cannot be followed.
 */
fun RoutePlan.withStops(via: List<LatLon>, maxOffRouteMeters: Double = 300.0): RoutePlan {
    if (via.isEmpty() || !isFollowable()) return this
    val clean = sanitized()
    val geometry = RouteGeometry(clean.geometry)
    val match = RouteMatch()
    val found = ArrayList<Int>()
    var from = 0.0
    for (v in via) {
        if (!(v.lat.isFinite() && v.lon.isFinite())) continue
        geometry.search(v.lat, v.lon, from, geometry.totalMeters, Double.NaN, 0.0, match)
        if (match.distance > maxOffRouteMeters) continue
        val before = match.segment
        val nearer = if (match.along - geometry.alongOfIndex(before) <= geometry.alongOfIndex(before + 1) - match.along) before else before + 1
        val index = nearer.coerceIn(0, clean.geometry.size - 1)
        found += index
        from = geometry.alongOfIndex(index)
    }
    return clean.copy(guidance = clean.guidance.copy(stops = found))
}
