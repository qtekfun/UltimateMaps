package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.nav.NavState
import kotlin.math.abs

/**
 * The camera policy of the navigation: where the camera should be for a snapshot ([NavCameraPlanner] decides the
 * zoom, tilt, bearing and padding) and, to spare the battery and the GPU, WHEN it is worth moving it. The map is
 * never asked to move more often than [minIntervalMillis], nor for a change smaller than a few metres, degrees or
 * quarter zoom levels (a standing car costs nothing). A switch between flat and tilted (or a change of padding)
 * goes through at once.
 *
 * Stateful (throttle and bearing smoothing; the caller passes the time); not thread-safe, used from the main thread.
 */
class NavCamera(
    private val minIntervalMillis: Long = 800L,
    private val minMoveMeters: Double = 6.0,
    private val minBearingDegrees: Double = 4.0,
    private val minZoomDelta: Double = 0.25,
    private val planner: NavCameraPlanner = NavCameraPlanner(),
) {
    private var last: CameraState? = null
    private var lastAt = Long.MIN_VALUE

    /** The smoothed heading, for the arrow of the position marker; null before the first plan. */
    val heading: Double? get() = planner.heading

    /** Where the camera should be for [state] (no throttling). Feeds the bearing smoothing: call it once per snapshot. */
    fun target(state: NavState, mode3d: Boolean = true, screenHeightPx: Int = 0): CameraState {
        val next = state.nextManeuver
        val plan = planner.plan(
            CameraPlanInput(
                speedMps = state.speedMps,
                distanceToManeuverMeters = next?.distanceMeters,
                maneuver = next?.maneuver?.type,
                mode3d = mode3d,
                screenHeightPx = screenHeightPx,
                bearingDegrees = state.bearingDegrees.toDouble(),
            ),
        )
        return CameraState(state.position, plan.zoom, plan.bearing, plan.tilt, plan.padding)
    }

    /**
     * The camera to animate to now, or null when the last one sent is still good enough (or too recent).
     * [force] skips the throttle (the user pressed "recenter").
     */
    fun next(state: NavState, nowMillis: Long, force: Boolean = false, mode3d: Boolean = true, screenHeightPx: Int = 0): CameraState? {
        val t = target(state, mode3d, screenHeightPx)
        val prev = last
        if (!force && prev != null) {
            val modeChanged = (prev.tilt == 0.0) != (t.tilt == 0.0) || prev.padding != t.padding
            if (!modeChanged) {
                if (nowMillis - lastAt < minIntervalMillis) return null
                val moved = prev.center.distanceTo(t.center)
                if (moved < minMoveMeters && bearingDelta(prev.bearing, t.bearing) < minBearingDegrees &&
                    abs(prev.zoom - t.zoom) < minZoomDelta && abs(prev.tilt - t.tilt) < MIN_TILT_DELTA
                ) {
                    return null
                }
            }
        }
        last = t
        lastAt = nowMillis
        return t
    }

    /** Forget what was sent (the user moved the map by hand, or navigation restarted); the bearing smoothing stays. */
    fun reset() {
        last = null
        lastAt = Long.MIN_VALUE
    }

    /** [reset] and also forget the heading: the navigation ended. */
    fun forget() {
        reset()
        planner.reset()
    }

    /** How long the animation to the next camera should take: a bit less than the update interval, so it flows. */
    val animationMillis: Int get() = (minIntervalMillis * 1.1).toInt()

    companion object {
        /** Easing into the tilt (navigation starts, the user recenters, 2D/3D is switched). */
        const val TRANSITION_MILLIS = 1_200

        /** Easing back to the flat north-up view when navigation stops. */
        const val LEAVE_MILLIS = 900

        const val MIN_TILT_DELTA = 3.0

        fun bearingDelta(a: Double, b: Double): Double = NavCameraPlanner.angularDistance(a, b)
    }
}
