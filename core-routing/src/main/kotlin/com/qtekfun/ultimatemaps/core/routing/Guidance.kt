package com.qtekfun.ultimatemaps.core.routing

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
 * The three exit fields come from the OSM tags of the ramp (`junction:ref`, `destination:ref`, `destination`) and are
 * null when the map does not have them (never invented): [exitRef] is the junction/exit number ("23", "12A"),
 * [towardRef] the road(s) the ramp leads to ("A-2", "M-40;A-2") and [towardName] the signposted place(s)
 * ("Alcalá de Henares; Torrejón"). See [ExitSign] for how they are shown and spoken.
 */
data class Maneuver(
    val geometryIndex: Int,
    val type: TurnType,
    val streetName: String? = null,
    val roundaboutExit: Int? = null,
    val lanes: List<Lane> = emptyList(),
    val exitRef: String? = null,
    val towardRef: String? = null,
    val towardName: String? = null,
)

/** Speed limit in km/h for the geometry range `[startIndex, endIndex]`; `null` when the data has none. */
data class SpeedLimit(val startIndex: Int, val endIndex: Int, val kmh: Int?)

/** A stretch of the route that lies in a tunnel (the road is tagged `tunnel=*`), as geometry point indices `[startIndex, endIndex]`. */
data class TunnelRange(val startIndex: Int, val endIndex: Int)

/**
 * Everything beyond the line itself that turn-by-turn guidance needs. Empty for a plain route preview.
 *
 * [stops] are the geometry indices of the intermediate stops (the `via` points of the request), in route order.
 * Reaching one is not arriving: the follower reports a "stop reached" event and keeps going. Added after the first
 * version with an empty default, so every existing constructor call and every engine that knows nothing about
 * stops keeps working unchanged (see `docs/phase2/robustness.md`).
 * [tunnels] was added the same way: the stretches the engine knows to be tunnels, in route order; empty when the engine
 * reports none (then nothing is known, not "there are no tunnels").
 */
data class RouteGuidance(
    val maneuvers: List<Maneuver> = emptyList(),
    val speedLimits: List<SpeedLimit> = emptyList(),
    val stops: List<Int> = emptyList(),
    val tunnels: List<TunnelRange> = emptyList(),
) {
    companion object { val EMPTY = RouteGuidance() }
}
