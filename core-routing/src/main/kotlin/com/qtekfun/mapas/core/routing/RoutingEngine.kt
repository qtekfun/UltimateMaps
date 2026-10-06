package com.qtekfun.mapas.core.routing

import com.qtekfun.mapas.core.geo.LatLon

enum class RoutingProfile { CAR, FOOT, BIKE }

data class RouteRequest(
    val from: LatLon,
    val to: LatLon,
    val via: List<LatLon> = emptyList(),
    val profile: RoutingProfile = RoutingProfile.CAR,
)

data class RoutePlan(val geometry: List<LatLon>, val distanceMeters: Double, val durationSeconds: Double)

/** On-device routing contract. Returns null when no route exists. */
interface RoutingEngine : AutoCloseable {
    fun route(request: RouteRequest): RoutePlan?
}
