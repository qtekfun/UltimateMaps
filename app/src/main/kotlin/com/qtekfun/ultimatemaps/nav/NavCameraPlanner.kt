package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the planner knows about the moment: the numbers, no map and no clock. */
data class CameraPlanInput(
    val speedMps: Double,
    /** Distance to the next maneuver, or null when there is none (open road, or the guidance has none). */
    val distanceToManeuverMeters: Double?,
    val maneuver: TurnType?,
    /** True for the tilted 3D follow camera, false for the flat 2D one. */
    val mode3d: Boolean,
    /** Height of the map view in pixels (0 when unknown: no padding is produced). */
    val screenHeightPx: Int,
    /** The heading to use (route bearing at the user, or GNSS course), degrees clockwise from north; null or NaN when unknown. */
    val bearingDegrees: Double?,
)

/** Where the camera should be: zoom, tilt (degrees from straight down), bearing (0..360) and padding. */
data class CameraPlan(val zoom: Double, val tilt: Double, val bearing: Double, val padding: CameraPadding)

/**
 * Decides the follow camera of the navigation (pure and deterministic: the only state is the last bearing, which
 * it needs to keep it when the car stands still and to smooth it).
 *
 * - Zoom by speed ([zoomForSpeed3d]), in quarter steps so it does not jitter, then closer as a maneuver approaches
 *   (more for roundabouts, sharp turns and the arrival), so it eases back out by itself once the maneuver is behind.
 * - Tilt (3D only): 55 degrees in town, up to 60 at motorway speed (look further ahead); a few degrees flatter
 *   close to a roundabout so the whole circle is visible. 60 is the default maximum pitch of MapLibre Native.
 * - Bearing: the course, smoothed with a fixed fraction of the shortest angular difference per update, so a noisy
 *   fix does not make the map shake and 359 to 1 degrees is a 2 degree turn, not 358. Standing still (or without a
 *   heading) keeps the last bearing: a stopped receiver reports a random course.
 * - Padding: pushes the camera centre up so that the position marker sits in the lower third of the screen.
 */
class NavCameraPlanner(private val smoothing: Double = BEARING_SMOOTHING) {
    private var last: Double? = null

    /** The bearing currently shown (the smoothed course), or null before the first plan. */
    val heading: Double? get() = last

    fun plan(input: CameraPlanInput): CameraPlan {
        val speed = input.speedMps.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val d = input.distanceToManeuverMeters?.takeIf { it.isFinite() && it >= 0.0 }
        val ramp = if (d == null) 0.0 else approach(d, speed)

        var zoom = if (input.mode3d) zoomForSpeed3d(speed) else zoomForSpeed2d(speed)
        zoom += ramp * zoomBoost(input.maneuver)
        zoom = ((zoom * 4).roundToInt() / 4.0).coerceIn(ZOOM_MIN, ZOOM_MAX)

        val tilt = if (!input.mode3d) {
            0.0
        } else {
            val base = TILT_TOWN + (TILT_FAST - TILT_TOWN) * ((speed - TILT_FROM_MPS) / (TILT_TO_MPS - TILT_FROM_MPS)).coerceIn(0.0, 1.0)
            val roundabout = input.maneuver == TurnType.ROUNDABOUT_ENTER || input.maneuver == TurnType.ROUNDABOUT_LEAVE
            base - if (roundabout) ramp * ROUNDABOUT_FLATTEN else 0.0
        }
        return CameraPlan(zoom, tilt, nextBearing(speed, input.bearingDegrees), padding(input.mode3d, input.screenHeightPx))
    }

    /** Forgets the last bearing (the navigation ended): the next trip starts from its own heading. */
    fun reset() {
        last = null
    }

    private fun nextBearing(speed: Double, raw: Double?): Double {
        val course = raw?.takeIf { it.isFinite() }?.let(::normalize)
        val prev = last
        val next = when {
            prev == null -> course ?: 0.0
            course == null || speed < STOPPED_MPS -> prev
            else -> normalize(prev + smoothing * signedDelta(prev, course))
        }
        last = next
        return next
    }

    companion object {
        const val ZOOM_MIN = 15.0
        const val ZOOM_MAX = 18.5

        /** Below this speed the course of a receiver is noise: keep the last bearing. */
        const val STOPPED_MPS = 1.0

        /** Fraction of the angular difference followed per update (updates are about a second apart). */
        const val BEARING_SMOOTHING = 0.5

        const val TILT_TOWN = 55.0
        const val TILT_FAST = 60.0
        private const val TILT_FROM_MPS = 14.0
        private const val TILT_TO_MPS = 25.0
        private const val ROUNDABOUT_FLATTEN = 12.0

        /** Where the marker sits, as a fraction of the screen height from the top: the lower third starts at 0.667. */
        const val MARKER_Y_3D = 0.72
        const val MARKER_Y_2D = 0.62

        /** Speed (m/s) and zoom pairs of the tilted view: close when slow, wide when fast (the tilt already shows further). */
        private val ZOOM_3D = listOf(0.0 to 17.5, 5.0 to 17.25, 14.0 to 16.5, 25.0 to 15.75, 33.0 to 15.25)

        /** The flat view: 17.5 standing, 15 at 30 m/s (108 km/h) or more. */
        private val ZOOM_2D = listOf(0.0 to 17.5, 30.0 to 15.0)

        fun zoomForSpeed3d(speedMps: Double) = interpolate(ZOOM_3D, speedMps)

        fun zoomForSpeed2d(speedMps: Double) = interpolate(ZOOM_2D, speedMps)

        /** How much closer the camera goes at the peak of the approach. */
        fun zoomBoost(type: TurnType?): Double = when (type) {
            TurnType.ROUNDABOUT_ENTER, TurnType.ROUNDABOUT_LEAVE -> 1.0
            TurnType.SHARP_LEFT, TurnType.SHARP_RIGHT, TurnType.U_TURN_LEFT, TurnType.U_TURN_RIGHT,
            TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT,
            -> 0.75
            TurnType.LEFT, TurnType.RIGHT, TurnType.EXIT_LEFT, TurnType.EXIT_RIGHT -> 0.5
            TurnType.SLIGHT_LEFT, TurnType.SLIGHT_RIGHT -> 0.25
            TurnType.DEPART, TurnType.STRAIGHT, TurnType.MERGE, null -> 0.0
        }

        private const val NEAR_METERS = 50.0

        /** 0 far from the maneuver, 1 at 50 m or closer; the far end grows with speed (about 8 s ahead, 150..300 m). */
        fun approach(distanceMeters: Double, speedMps: Double): Double {
            val far = (speedMps * 8.0).coerceIn(150.0, 300.0)
            return ((far - distanceMeters) / (far - NEAR_METERS)).coerceIn(0.0, 1.0)
        }

        /** The marker at its fraction of the height with no bottom padding: the centre of the remaining area is the marker. */
        fun padding(mode3d: Boolean, screenHeightPx: Int): CameraPadding {
            if (screenHeightPx <= 0) return CameraPadding.NONE
            val markerY = if (mode3d) MARKER_Y_3D else MARKER_Y_2D
            return CameraPadding(top = (screenHeightPx * (2 * markerY - 1)).roundToInt())
        }

        /** Where the marker ends up (pixels from the top) for [padding] on a screen of [screenHeightPx]. */
        fun markerY(padding: CameraPadding, screenHeightPx: Int): Double =
            padding.top + (screenHeightPx - padding.top - padding.bottom) / 2.0

        fun normalize(degrees: Double): Double = (degrees % 360 + 360) % 360

        /** The shortest signed turn from [from] to [to], in (-180, 180]. */
        fun signedDelta(from: Double, to: Double): Double {
            val d = ((to - from) % 360 + 540) % 360 - 180
            return if (d == -180.0) 180.0 else d
        }

        fun angularDistance(a: Double, b: Double): Double = abs(signedDelta(a, b))

        private fun interpolate(points: List<Pair<Double, Double>>, x: Double): Double {
            if (x <= points.first().first) return points.first().second
            if (x >= points.last().first) return points.last().second
            val i = points.indexOfLast { it.first <= x }
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
    }
}
