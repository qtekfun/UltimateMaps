package com.qtekfun.mapas.core.routing

import com.qtekfun.mapas.core.geo.LatLon

enum class RoutingProfile { CAR, FOOT, BIKE }

/**
 * Road classes to avoid. They are hard exclusions in the CoMaps core: if the only way
 * needs one of them the route fails (returns null) instead of being penalised.
 * [avoidMotorways] and [avoidTolls] only apply to [RoutingProfile.CAR];
 * [avoidFerries] and [avoidUnpaved] apply to every profile.
 */
data class RouteOptions(
    val avoidMotorways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
)

data class RouteRequest(
    val from: LatLon,
    val to: LatLon,
    val via: List<LatLon> = emptyList(),
    val profile: RoutingProfile = RoutingProfile.CAR,
    val options: RouteOptions = RouteOptions(),
)

data class RoutePlan(
    val geometry: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Maneuvers, lanes and speed limits for turn-by-turn; [RouteGuidance.EMPTY] when the engine gives none. */
    val guidance: RouteGuidance = RouteGuidance.EMPTY,
)

/** On-device routing contract. Returns null when no route exists. */
interface RoutingEngine : AutoCloseable {
    fun route(request: RouteRequest): RoutePlan?
}
