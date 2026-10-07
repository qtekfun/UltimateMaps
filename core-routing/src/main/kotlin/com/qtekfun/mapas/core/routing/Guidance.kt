package com.qtekfun.mapas.core.routing

/** What the driver has to do at a [Maneuver]. Mirrors the turn kinds of the CoMaps core, without depending on it. */
enum class TurnType {
    DEPART, STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT,
    U_TURN_LEFT, U_TURN_RIGHT, ROUNDABOUT_ENTER, ROUNDABOUT_LEAVE, EXIT_LEFT, EXIT_RIGHT, MERGE,
    ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT,
}

/** One direction a lane allows. */
enum class LaneDirection { LEFT, SLIGHT_LEFT, SHARP_LEFT, THROUGH, RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, U_TURN, MERGE_LEFT, MERGE_RIGHT }

/** A lane at a [Maneuver]; [recommended] lanes lead into the planned route. Listed left to right. */
data class Lane(val directions: Set<LaneDirection>, val recommended: Boolean)

/**
 * An instruction anchored on the route geometry: it applies at `geometry[geometryIndex]` of the [RoutePlan].
 * [roundaboutExit] is the exit number for roundabout maneuvers; [streetName] is the road to turn onto (may be empty).
 */
data class Maneuver(
    val geometryIndex: Int,
    val type: TurnType,
    val streetName: String? = null,
    val roundaboutExit: Int? = null,
    val lanes: List<Lane> = emptyList(),
)

/** Speed limit in km/h for the geometry range `[startIndex, endIndex]`; `null` when the data has none. */
data class SpeedLimit(val startIndex: Int, val endIndex: Int, val kmh: Int?)

/**
 * Everything beyond the line itself that turn-by-turn guidance needs. Empty for a plain route preview.
 *
 * [stops] are the geometry indices of the intermediate stops (the `via` points of the request), in route order.
 * Reaching one is not arriving: the follower reports a "stop reached" event and keeps going. Added after the first
 * version with an empty default, so every existing constructor call and every engine that knows nothing about
 * stops keeps working unchanged (see `docs/phase2/robustness.md`).
 */
data class RouteGuidance(
    val maneuvers: List<Maneuver> = emptyList(),
    val speedLimits: List<SpeedLimit> = emptyList(),
    val stops: List<Int> = emptyList(),
) {
    companion object { val EMPTY = RouteGuidance() }
}
