package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.SpeedLimit
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val ORIGIN_LAT = 40.0
const val ORIGIN_LON = -3.0
private const val M = 111_194.9266

/** Point `east` metres east and `north` metres north of the test origin. */
fun pt(east: Double, north: Double) =
    LatLon(ORIGIN_LAT + north / M, ORIGIN_LON + east / (M * cos(Math.toRadians(ORIGIN_LAT))))

/** Metres between two test points on the local plane (good enough to build fixtures). */
fun planar(a: LatLon, b: LatLon): Double =
    hypot((b.lon - a.lon) * M * cos(Math.toRadians(ORIGIN_LAT)), (b.lat - a.lat) * M)

/** Builds a polyline with a vertex about every [step] metres, remembering the index of each corner. */
class RouteBuilder(private val step: Double = 20.0) {
    val points = ArrayList<LatLon>()
    private var east = 0.0
    private var north = 0.0

    init { points += pt(0.0, 0.0) }

    val lastIndex get() = points.size - 1

    fun lineTo(e: Double, n: Double): RouteBuilder {
        val len = hypot(e - east, n - north)
        val parts = maxOf(1, Math.ceil(len / step).toInt())
        for (i in 1..parts) points += pt(east + (e - east) * i / parts, north + (n - north) * i / parts)
        east = e
        north = n
        return this
    }

    /** Circular arc around ([cx], [cy]) from angle [fromDeg] to [toDeg] (math angles, counter-clockwise). */
    fun arc(cx: Double, cy: Double, radius: Double, fromDeg: Double, toDeg: Double): RouteBuilder {
        val arcLen = radius * Math.abs(Math.toRadians(toDeg - fromDeg))
        val parts = maxOf(4, Math.ceil(arcLen / 5.0).toInt())
        for (i in 1..parts) {
            val a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / parts)
            points += pt(cx + radius * cos(a), cy + radius * sin(a))
        }
        east = cx + radius * cos(Math.toRadians(toDeg))
        north = cy + radius * sin(Math.toRadians(toDeg))
        return this
    }

    fun plan(
        maneuvers: List<Maneuver>,
        speedLimits: List<SpeedLimit> = emptyList(),
        durationSeconds: Double = 600.0,
    ): RoutePlan {
        val length = RouteGeometry(points).totalMeters
        return RoutePlan(points.toList(), length, durationSeconds, RouteGuidance(maneuvers, speedLimits))
    }
}

fun maneuver(index: Int, type: TurnType, street: String? = null) = Maneuver(index, type, street)

/** 2 km north with a motorway exit at 1 km. */
fun straightPlan(): RoutePlan {
    val b = RouteBuilder().lineTo(0.0, 1000.0)
    val exit = b.lastIndex
    b.lineTo(0.0, 2000.0)
    return b.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(exit, TurnType.EXIT_RIGHT), maneuver(b.lastIndex, TurnType.ARRIVE)))
}

/** 1 km north, right turn, 1 km east. */
fun lPlan(): RoutePlan {
    val b = RouteBuilder().lineTo(0.0, 1000.0)
    val corner = b.lastIndex
    b.lineTo(1000.0, 1000.0)
    return b.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(corner, TurnType.RIGHT, "Calle Mayor"), maneuver(b.lastIndex, TurnType.ARRIVE)))
}

/** 400 m east, a three-quarter turn around a 25 m roundabout, 400 m north. */
fun roundaboutPlan(): RoutePlan {
    val b = RouteBuilder().lineTo(400.0, 0.0)
    val enter = b.lastIndex
    // Circle centre 25 m north of the entry point; enter at the bottom (270 deg) and leave at the left (180 deg) after 270 deg.
    b.arc(400.0, 25.0, 25.0, -90.0, 180.0)
    val leave = b.lastIndex
    b.lineTo(375.0, 425.0)
    return b.plan(
        listOf(
            maneuver(0, TurnType.DEPART),
            Maneuver(enter, TurnType.ROUNDABOUT_ENTER, roundaboutExit = 3),
            maneuver(leave, TurnType.ROUNDABOUT_LEAVE),
            maneuver(b.lastIndex, TurnType.ARRIVE),
        ),
    )
}

/** 500 m north, a U-turn with legs 30 m apart, 500 m back south. */
fun uTurnPlan(): RoutePlan {
    val b = RouteBuilder().lineTo(0.0, 500.0)
    val turn = b.lastIndex
    b.arc(15.0, 500.0, 15.0, 180.0, 0.0)
    b.lineTo(30.0, 0.0)
    return b.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(turn, TurnType.U_TURN_RIGHT), maneuver(b.lastIndex, TurnType.ARRIVE)))
}

/**
 * Passes twice over the first leg: east 400, north 400, west 400, south 400 back to the start, then east again over
 * the first leg and on to 800 m. The second pass shares the first one's road in the same direction.
 */
fun loopPlan(): RoutePlan {
    val b = RouteBuilder().lineTo(400.0, 0.0)
    val c1 = b.lastIndex
    b.lineTo(400.0, 400.0)
    val c2 = b.lastIndex
    b.lineTo(0.0, 400.0)
    val c3 = b.lastIndex
    b.lineTo(0.0, 0.0)
    val c4 = b.lastIndex
    b.lineTo(800.0, 0.0)
    return b.plan(
        listOf(
            maneuver(0, TurnType.DEPART),
            maneuver(c1, TurnType.LEFT), maneuver(c2, TurnType.LEFT), maneuver(c3, TurnType.LEFT),
            maneuver(c4, TurnType.LEFT), maneuver(b.lastIndex, TurnType.ARRIVE),
        ),
    )
}

/** Everything a run through the tracker produced. */
class Run(val states: List<NavState>, val announcements: List<Announcement>, val fixes: List<LocationFix>)

/** Feeds every fix of [fixes] to a fresh tracker, ticking the clock in between like the session does. */
fun track(plan: RoutePlan, fixes: Sequence<LocationFix>, config: NavConfig = NavConfig()): Run {
    val announcements = ArrayList<Announcement>()
    val tracker = RouteTracker(plan, config, 0) { announcements += it }
    val states = ArrayList<NavState>()
    val used = ArrayList<LocationFix>()
    for (fix in fixes) {
        tracker.onFix(fix)
        states += tracker.snapshot()
        used += fix
    }
    return Run(states, announcements, used)
}

fun fix(point: LatLon, t: Long, acc: Float = 5f, speed: Float? = 10f, bearing: Float? = null) =
    LocationFix(point, acc, bearing, speed, t)
