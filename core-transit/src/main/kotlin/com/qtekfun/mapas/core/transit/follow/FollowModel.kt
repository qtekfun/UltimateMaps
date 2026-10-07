package com.qtekfun.mapas.core.transit.follow

import com.qtekfun.mapas.core.transit.LineInfo

/** Where the traveller is in the itinerary, as far as the follower can tell. */
enum class FollowPhase {
    /** Walking to the first boarding stop (the itinerary starts away from it). */
    BEFORE_START,

    /** At the boarding stop, the scheduled departure is ahead (or just passed). */
    WAITING,

    /** On a vehicle, at least one stop before the alighting one. */
    ON_BOARD,

    /** On a vehicle and the next stop is the alighting one. */
    ALIGHT_NEXT,

    /** Between two rides: walking to the next stop, or changing platform. */
    TRANSFER,

    /** The last walk, from the last alighting stop to the destination. */
    FINAL_WALK,

    ARRIVED,

    /** The position does not fit the plan: offer to plan again from here. */
    OFF_PLAN,
}

/** How the current position was obtained. */
enum class FollowBasis {
    /** A usable fix arrived recently. */
    GNSS,

    /** No usable fix for a while on a rail/metro/tram leg: progress comes from the timetable. Never a certainty. */
    ESTIMATED,

    /** No usable fix yet, or for a while on a leg where no estimate is made (a bus). The last known progress is shown. */
    NO_SIGNAL,
}

enum class PlanStatus { ON_PLAN, BEHIND, AHEAD }

/** Whether the next boarding can still be reached, from the position and the clock. */
enum class ConnectionStatus { OK, AT_RISK, MISSED }

/**
 * Everything the screen, the notification and the voice need, with no resources: names and absolute epoch seconds.
 * Fields that do not apply to the phase are null.
 */
data class FollowState(
    val phase: FollowPhase,
    val legIndex: Int,
    val basis: FollowBasis,
    /** The line being ridden, or the next one to board while walking or waiting. */
    val line: LineInfo? = null,
    val headsign: String? = null,
    /** Where the current walk goes (null: the destination), or the boarding stop while waiting. */
    val targetName: String? = null,
    val walkMeters: Int? = null,
    val walkSeconds: Int? = null,
    /** Scheduled departure of the next boarding and the seconds left to it (negative once passed). */
    val boardAt: Long? = null,
    val secondsToBoard: Long? = null,
    /** Stop positions of the ride in progress: last stop passed, next stop, stops left until (and including) the alighting one. */
    val lastStopIndex: Int = -1,
    val nextStopIndex: Int = -1,
    val stopsRemaining: Int = 0,
    val nextStopName: String? = null,
    val nextStopAt: Long? = null,
    val alightName: String? = null,
    val alightAt: Long? = null,
    /** Seconds behind the schedule (negative: ahead). Null when there is nothing to compare yet. */
    val planOffsetSec: Int? = null,
    val plan: PlanStatus = PlanStatus.ON_PLAN,
    /** Whole minutes of [plan] when it is not [PlanStatus.ON_PLAN]. */
    val planMinutes: Int = 0,
    val connection: ConnectionStatus? = null,
    val connectionMarginSec: Int? = null,
    /** Short name of the line whose boarding [connection] is about. */
    val connectionLine: String? = null,
    /** True while walking or riding towards the first/next boarding after a stop in the same station (no walk leg). */
    val changeHere: Boolean = false,
    /** The Re-plan button is offered: off the plan, or the next departure is out of reach. */
    val canReplan: Boolean = false,
    /** Scheduled arrival at the destination moved by the current offset. */
    val etaAt: Long = 0L,
) {
    val estimated: Boolean get() = basis == FollowBasis.ESTIMATED
}

enum class PromptKind { BOARD_NOW, GET_READY, GET_OFF_NOW, CHANGE_HERE, CONNECTION_AT_RISK, CONNECTION_MISSED, OFF_PLAN, ARRIVED }

/** Something worth saying or sounding once. [estimated]: the position behind it is a timetable estimate. */
data class FollowPrompt(
    val kind: PromptKind,
    val line: String? = null,
    val headsign: String? = null,
    val stop: String? = null,
    val minutes: Int? = null,
    val estimated: Boolean = false,
)

data class FollowUpdate(val state: FollowState, val prompts: List<FollowPrompt>)

/**
 * Thresholds of the follower. Every number is a DESIGN CHOICE (a tolerance picked to behave sensibly), not a measured
 * value; none has been tuned on a device or on real rides. They are all here so a test or a later measurement can change them.
 */
data class FollowerConfig(
    /** Walking speed for the time left on a walk: the planner's own default (1.25 m/s = 4.5 km/h). */
    val walkSpeedMps: Double = 1.25,
    /** Straight-line distance times this approximates the street walk (the planner's own default). */
    val detourFactor: Double = 1.3,
    /** A stop counts as reached within `minStopRadiusM + accuracy`, at most [maxStopRadiusM]. */
    val minStopRadiusM: Double = 50.0,
    val maxStopRadiusM: Double = 250.0,
    /** A fix with a worse accuracy than this is not used at all. */
    val unusableAccuracyM: Float = 200f,
    /** Accuracy assumed when the fix has none. */
    val defaultAccuracyM: Float = 30f,
    /** A fix belongs to a stop-to-stop segment within `corridorM + accuracy` of it (lines curve; there are no shapes yet). */
    val corridorM: Double = 150.0,
    /** While tracking, only this many segments ahead of the current one are considered (loop lines, parallel lines). */
    val lookaheadSegments: Int = 3,
    /** At or above this speed the traveller is on a vehicle, not walking (about 14 km/h; brisk walking is under 2.5 m/s). */
    val ridingSpeedMps: Float = 4f,
    /** Consecutive agreeing fixes needed to board, to skip several stops at once or to leave the plan. */
    val confirmFixes: Int = 2,
    val offPlanFixes: Int = 4,
    /** A walk is off plan when the distance to its target grows by this much over the smallest seen (street layout allows some). */
    val walkOffPlanMeters: Double = 250.0,
    /** No usable fix for this long counts as lost signal (tunnels and stations: GNSS usually comes back within seconds). */
    val signalGapSec: Int = 45,
    /** Rail legs (GTFS route types) on which progress is estimated from the timetable when the signal is lost: tram, metro, rail. */
    val estimateRouteTypes: Set<Int> = setOf(0, 1, 2),
    /** Within this many seconds of the schedule the chip says "on plan". */
    val onPlanToleranceSec: Int = 90,
    /** A boarding with less margin than this is "at risk" (the planner keeps 60 s transfer slack itself). */
    val riskMarginSec: Int = 60,
    /** A walk that will arrive more than this after the departure cannot catch it. */
    val missedGraceSec: Int = 30,
    /** Waiting at the stop: only this long after the scheduled departure is it called missed (vehicles run late). */
    val waitingLateGraceSec: Int = 120,
    /** "Board now" is said this long before the scheduled departure. */
    val boardNowSec: Int = 60,
    /** "Get off now" within this distance of the alighting stop. */
    val getOffNowMeters: Double = 120.0,
    /** Estimated progress says "get off now" this many seconds before the scheduled arrival. */
    val getOffNowEstimatedSec: Int = 20,
    /** After an estimated boarding, a fix at the boarding stop with the vehicle not moving takes the traveller back to waiting. */
    val undoEstimatedBoarding: Boolean = true,
)
