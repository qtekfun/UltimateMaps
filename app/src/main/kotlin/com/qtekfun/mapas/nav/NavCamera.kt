package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.geo.distanceTo
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.nav.NavState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The camera policy of the navigation: where the camera should be for a snapshot, and, to spare the battery and the
 * GPU, WHEN it is worth moving it. The map is never asked to move more often than [minIntervalMillis], nor for a
 * change smaller than a few metres, degrees or quarter zoom levels (a standing car costs nothing).
 *
 * - Heading: the route's bearing at the user (smooth, unlike the noisy GNSS bearing), the map "heads up".
 * - Zoom by speed: close (17.5) when slow, wide (15) at 30 m/s or more, in quarter steps so it does not jitter;
 *   half a level closer within [turnZoomMeters] of the next maneuver.
 * - Tilt: [TILT_DEGREES], a gentle 3D view.
 *
 * Pure and deterministic (the caller passes the time); not thread-safe, used from the main thread.
 */
class NavCamera(
    private val minIntervalMillis: Long = 800L,
    private val minMoveMeters: Double = 6.0,
    private val minBearingDegrees: Double = 4.0,
    private val minZoomDelta: Double = 0.25,
    private val turnZoomMeters: Double = 120.0,
) {
    private var last: CameraState? = null
    private var lastAt = Long.MIN_VALUE

    /** Where the camera should be for [state] (no throttling). */
    fun target(state: NavState): CameraState {
        var zoom = ZOOM_NEAR - (ZOOM_NEAR - ZOOM_FAR) * (state.speedMps / FAST_MPS).coerceIn(0.0, 1.0)
        val next = state.nextManeuver
        if (next != null && next.distanceMeters in 0.0..turnZoomMeters) zoom += 0.5
        zoom = ((zoom * 4).roundToInt() / 4.0).coerceIn(ZOOM_FAR, ZOOM_MAX)
        return CameraState(state.position, zoom, (state.bearingDegrees.toDouble() % 360 + 360) % 360, TILT_DEGREES)
    }

    /**
     * The camera to animate to now, or null when the last one sent is still good enough (or too recent).
     * [force] skips the throttle (the user pressed "recenter").
     */
    fun next(state: NavState, nowMillis: Long, force: Boolean = false): CameraState? {
        val t = target(state)
        val prev = last
        if (!force && prev != null) {
            if (nowMillis - lastAt < minIntervalMillis) return null
            val moved = prev.center.distanceTo(t.center)
            if (moved < minMoveMeters && bearingDelta(prev.bearing, t.bearing) < minBearingDegrees && abs(prev.zoom - t.zoom) < minZoomDelta) return null
        }
        last = t
        lastAt = nowMillis
        return t
    }

    /** Forget what was sent (the user moved the map by hand, or navigation restarted). */
    fun reset() {
        last = null
        lastAt = Long.MIN_VALUE
    }

    /** How long the animation to the next camera should take: a bit less than the update interval, so it flows. */
    val animationMillis: Int get() = (minIntervalMillis * 1.1).toInt()

    companion object {
        const val ZOOM_NEAR = 17.5
        const val ZOOM_FAR = 15.0
        const val ZOOM_MAX = 18.5
        const val FAST_MPS = 30.0
        const val TILT_DEGREES = 45.0

        fun bearingDelta(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }
}
