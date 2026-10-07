package com.qtekfun.mapas.core.routing

import com.qtekfun.mapas.core.geo.LatLon

enum class RoutingProfile { CAR, FOOT, BIKE }

/**
 * How strongly bike routing prefers cycle infrastructure (cycleways, cycle lanes and tracks, roads open to bikes
 * as tagged by OpenStreetMap; see `docs/phase2/bike-cycleways.md`). Only applies to [RoutingProfile.BIKE].
 * [OFF] is the stock CoMaps behaviour, which already favours tagged cycle infrastructure.
 */
enum class BikeCycleways {
    OFF,
    PREFER,
    STRONGLY_PREFER,

    /** Non-cycle roads are excluded. Often finds no route: the engine then answers with a distinct "no cycle route" code. */
    ONLY,
}

/**
 * Road classes to avoid. They are hard exclusions in the CoMaps core: if the only way
 * needs one of them the route fails (returns null) instead of being penalised.
 * [avoidMotorways] and [avoidTolls] only apply to [RoutingProfile.CAR];
 * [avoidFerries] and [avoidUnpaved] apply to every profile. [bikeCycleways] only applies to [RoutingProfile.BIKE].
 */
data class RouteOptions(
    val avoidMotorways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val bikeCycleways: BikeCycleways = BikeCycleways.OFF,
) {
    /**
     * Compact form shared by the native core, the isolated core wire protocol and the saved navigation state. Bits 0 to
     * 3: motorways, tolls, ferries, unpaved (same order as `um::AvoidFlags`); bits 4 and 5: [bikeCycleways] ordinal.
     */
    fun toFlags(): Int =
        (if (avoidMotorways) 1 else 0) or (if (avoidTolls) 2 else 0) or (if (avoidFerries) 4 else 0) or
            (if (avoidUnpaved) 8 else 0) or (bikeCycleways.ordinal shl CYCLE_SHIFT)

    companion object {
        private const val CYCLE_SHIFT = 4

        /** Inverse of [toFlags]; unknown cycle levels decode to [BikeCycleways.OFF]. */
        fun fromFlags(f: Int): RouteOptions = RouteOptions(
            avoidMotorways = f and 1 != 0,
            avoidTolls = f and 2 != 0,
            avoidFerries = f and 4 != 0,
            avoidUnpaved = f and 8 != 0,
            bikeCycleways = BikeCycleways.entries.getOrElse((f shr CYCLE_SHIFT) and 3) { BikeCycleways.OFF },
        )
    }
}

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
