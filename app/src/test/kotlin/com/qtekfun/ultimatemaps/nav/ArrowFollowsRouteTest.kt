package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The position arrow along turns, through the same pipeline as the app (fixes at 1 Hz, display frames at 60 Hz on a
 * virtual clock, the follow camera planned by [NavCameraPlanner] and eased by [NavPoseDriver]). At every frame it
 * measures the angle between the heading handed to the map and the route tangent at the marker, and the distance of
 * the marker from the line.
 */
class ArrowFollowsRouteTest {
    private class Rec : MapEngine {
        var heading: Float? = null
        var user: LatLon? = null
        override fun setCamera(center: LatLon, zoom: Double) = Unit
        override fun camera() = LatLon(0.0, 0.0) to 1.0
        override fun animateTo(state: CameraState, durationMillis: Int) = Unit
        override fun setUserHeading(degrees: Float?) { heading = degrees }
        override fun showUserLocation(point: LatLon?, accuracyMeters: Float?) { user = point }
        override fun close() = Unit
    }

    private class Result(val maxHeadingError: Double, val meanHeadingError: Double, val maxLateral: Double)

    private fun line(vararg xy: Double): List<LatLon> = (xy.indices step 2).map { pt(xy[it], xy[it + 1]) }

    /** Points on a circle, compass angle (0 = north, clockwise) from [fromDeg] to [toDeg]. */
    private fun arc(cx: Double, cy: Double, r: Double, fromDeg: Double, toDeg: Double, stepDeg: Double = 5.0): List<LatLon> {
        val n = (abs(toDeg - fromDeg) / stepDeg).toInt().coerceAtLeast(1)
        return (0..n).map {
            val a = Math.toRadians(fromDeg + (toDeg - fromDeg) * it / n)
            pt(cx + r * sin(a), cy + r * cos(a))
        }
    }

    private fun meters(a: LatLon, b: LatLon) =
        hypot((a.lon - b.lon) * 111_194.9266 * cos(Math.toRadians(40.0)), (a.lat - b.lat) * 111_194.9266)

    /** Distance of [p] from the polyline of [g] and the along position of the nearest point (dense sampling, test only). */
    private fun nearest(g: RouteGeometry, p: LatLon, hint: Double): Pair<Double, Double> {
        var best = Double.MAX_VALUE
        var bestA = hint
        var a = (hint - 15.0).coerceAtLeast(0.0)
        while (a <= minOf(g.totalMeters, hint + 40.0)) {
            val d = meters(g.pointAt(a), p)
            if (d < best) { best = d; bestA = a }
            a += 0.05
        }
        return best to bestA
    }

    /**
     * Error of [heading] against the route near [along]: the angle to the nearest direction the line takes within
     * [TOL_METERS] behind or ahead (a vertex has no single tangent, the turn between two samples counts as taken).
     */
    private fun headingError(g: RouteGeometry, along: Double, heading: Double): Double {
        var best = 180.0
        var prev = Double.NaN
        var d = -TOL_METERS
        while (d <= TOL_METERS + 1e-9) {
            val b = g.bearingAt((along + d).coerceIn(0.0, g.totalMeters)).toDouble()
            best = minOf(best, NavCameraPlanner.angularDistance(heading, b))
            if (!prev.isNaN()) {
                val span = NavCameraPlanner.signedDelta(prev, b)
                val to = NavCameraPlanner.signedDelta(prev, heading)
                if (if (span >= 0) to in 0.0..span else to in span..0.0) best = 0.0
            }
            prev = b
            d += 0.5
        }
        return best
    }

    private fun drive(name: String, points: List<LatLon>, speed: Double, skipMeters: Double = 20.0): Result {
        val g = RouteGeometry(points)
        val rec = Rec()
        val driver = NavPoseDriver(rec).apply { setRoute(g) }
        val planner = NavCameraPlanner()
        var maxErr = 0.0
        var sumErr = 0.0
        var count = 0
        var maxLat = 0.0
        var hint = 0.0
        val endAt = ((g.totalMeters - 1.0) / speed * 1000).toLong()
        var t = 0L
        var nextFix = 0L
        var first = true
        while (t <= endAt) {
            if (t >= nextFix) {
                val along = (speed * t / 1000.0).coerceAtMost(g.totalMeters)
                val nav = navState(speedMps = speed).copy(
                    position = g.pointAt(along), traveledMeters = along, bearingDegrees = g.bearingAt(along), offRouteMeters = 0.0,
                )
                driver.onFix(nav, t)
                val plan = planner.plan(CameraPlanInput(speed, null, null, true, 2000, nav.bearingDegrees.toDouble()))
                val cam = CameraState(nav.position, plan.zoom, plan.bearing, plan.tilt, CameraPadding(0, 800, 0, 0))
                driver.retarget(cam, transition = first, current = cam)
                first = false
                nextFix += 1000
            }
            driver.frame(t)
            val u = rec.user
            val h = rec.heading
            if (u != null && h != null) {
                val (d, a) = nearest(g, u, hint)
                hint = a
                if (a >= skipMeters && a <= g.totalMeters - 5.0) {
                    val e = headingError(g, a, h.toDouble())
                    maxErr = maxOf(maxErr, e)
                    sumErr += e
                    count++
                    maxLat = maxOf(maxLat, d)
                }
            }
            t += 16
        }
        println("ARROW %-12s speed=%.0f maxHeadingErr=%.1f meanHeadingErr=%.2f maxLateral=%.2f".format(name, speed, maxErr, sumErr / count, maxLat))
        return Result(maxErr, sumErr / count, maxLat)
    }

    private fun check(r: Result) {
        assertTrue(r.maxHeadingError < MAX_HEADING_ERR, "max heading error ${r.maxHeadingError} deg")
        assertTrue(r.meanHeadingError < MAX_MEAN_ERR, "mean heading error ${r.meanHeadingError} deg")
        assertTrue(r.maxLateral < MAX_LATERAL, "lateral offset ${r.maxLateral} m")
    }

    @Test fun `right angle at 14 m per s`() = check(drive("right-angle", line(0.0, 0.0, 0.0, 300.0, 300.0, 300.0), 14.0))

    @Test fun `right angle at 25 m per s`() = check(drive("right-angle", line(0.0, 0.0, 0.0, 400.0, 400.0, 400.0), 25.0))

    @Test fun `turns across north keep the short way round the 0 and 360 wrap`() =
        check(drive("wrap", line(0.0, 0.0, 300.0, 0.0, 300.0, 200.0, 100.0, 400.0), 12.0))

    @Test fun `hairpin of a few points`() {
        val pts = line(0.0, 0.0, 0.0, 200.0) + arc(8.0, 200.0, 8.0, 270.0, 450.0, 30.0).drop(1) + line(16.0, 200.0, 16.0, 0.0).drop(1)
        check(drive("hairpin", pts, 6.0))
    }

    @Test fun `roundabout in the middle of a street`() {
        // in from the south, three quarters of the circle (radius 25 m, 5 degree steps), out to the east
        val circle = arc(0.0, 25.0, 25.0, 180.0, 450.0)
        val pts = line(0.0, -150.0, 0.0, 0.0).dropLast(1) + circle + line(25.0, 25.0, 200.0, 25.0).drop(1)
        check(drive("roundabout", pts, 8.0, skipMeters = 120.0))
    }

    @Test fun `s curve of 10 m segments`() {
        val pts = ArrayList<LatLon>()
        var x = 0.0
        var y = 0.0
        pts += pt(x, y)
        for (k in 0 until 60) {
            val a = Math.toRadians(60.0 * sin(2 * PI * k / 40.0))
            x += 10.0 * sin(a)
            y += 10.0 * cos(a)
            pts += pt(x, y)
        }
        check(drive("s-curve", pts, 12.0))
    }

    private companion object {
        const val TOL_METERS = 4.0
        const val MAX_HEADING_ERR = 8.0
        const val MAX_MEAN_ERR = 3.0
        const val MAX_LATERAL = 0.5
    }
}
