package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The marker maths on a virtual clock: nothing here reads real time. */
class PuckInterpolatorTest {
    private fun route(vararg points: LatLon) = RouteGeometry(points.toList())

    /** 2 km due east (90 degrees). */
    private val east = route(pt(0.0, 0.0), pt(2_000.0, 0.0))

    private fun PuckInterpolator.at() = LatLon(lat, lon)

    /** A fix at [t] that sits [along] metres on the route [g], taken from the route itself. */
    private fun PuckInterpolator.fix(g: RouteGeometry, t: Long, along: Double, speed: Double, revision: Int = 0, onRoute: Boolean = true) {
        val p = g.pointAt(along)
        onFix(t, p.lat, p.lon, along, speed, g.bearingAt(along).toDouble(), revision, onRoute)
    }

    /** Steps the clock in 16 ms frames from [from] to [until]; returns the largest per-frame move in metres. */
    private fun PuckInterpolator.run(from: Long, until: Long, step: Long = 16): Double {
        var worst = 0.0
        var prev = at()
        var t = from
        while (t < until) {
            t = minOf(t + step, until)
            advance(t)
            worst = maxOf(worst, prev.distanceTo(at()))
            prev = at()
        }
        return worst
    }

    @Test fun `at a steady speed the marker glides, no frame moves much more than speed times the frame`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 0.0, 14.0)
        var worst = 0.0
        for (sec in 1..8) {
            worst = maxOf(worst, p.run((sec - 1) * 1000L, sec * 1000L))
            p.fix(east, sec * 1000L, sec * 14.0, 14.0) // the fix lands exactly on the prediction
        }
        val perFrame = 14.0 * 0.016
        assertTrue(worst < perFrame * 1.5, "worst frame move $worst m vs $perFrame m")
    }

    @Test fun `noisy fixes do not make 1 Hz steps`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 0.0, 10.0)
        var worst = 0.0
        for (sec in 1..10) {
            worst = maxOf(worst, p.run((sec - 1) * 1000L, sec * 1000L))
            // The receiver is 1.5 m ahead on odd seconds and 1.5 m behind on even ones.
            p.fix(east, sec * 1000L, sec * 10.0 + if (sec % 2 == 0) -1.5 else 1.5, 10.0)
        }
        assertTrue(worst < 10.0 * 0.016 * 2, "a frame moved $worst m")
    }

    @Test fun `the marker ends where the fixes are`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 0.0, 10.0)
        p.run(0, 1_000)
        p.fix(east, 1_000, 10.0, 10.0)
        p.run(1_000, 2_600)
        p.fix(east, 2_600, 26.0, 0.0) // stopped at 26 m
        p.run(2_600, 5_000)
        assertEquals(0.0, p.at().distanceTo(east.pointAt(26.0)), 0.3)
    }

    @Test fun `a late fix does not send the marker on past the prediction limit`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 100.0, 20.0)
        p.run(0, 6_000) // no new fix for 6 s
        assertEquals(20.0 * 1.5, east.pointAt(100.0).distanceTo(p.at()), 1.0)
    }

    @Test fun `when the late fix arrives the marker catches up smoothly and never goes backwards`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 0.0, 15.0)
        p.run(0, 3_000)
        p.fix(east, 3_000, 45.0, 15.0) // it kept going: 22.5 m beyond where the marker stopped
        var prevLon = p.lon
        var prev = p.at()
        var worst = 0.0
        var t = 3_000L
        while (t < 4_500) {
            t += 16
            p.advance(t)
            assertTrue(p.lon >= prevLon - 1e-12, "moved backwards")
            prevLon = p.lon
            worst = maxOf(worst, prev.distanceTo(p.at()))
            prev = p.at()
        }
        // At most 3 * speed + 5 m/s: a quick catch-up, not a jump.
        assertTrue(worst <= (3 * 15.0 + 5.0) * 0.016 + 0.01, "catch-up frame $worst m")
        assertTrue(p.at().distanceTo(east.pointAt(45.0 + 15.0 * 1.5)) < 6.0)
    }

    @Test fun `a stopped car does not creep, and one predicted too far ahead slides back slowly`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 0.0, 0.0)
        val start = p.at()
        p.run(0, 5_000)
        assertEquals(0.0, start.distanceTo(p.at()), 1e-6)
        p.fix(east, 5_000, 0.0, 14.0)
        p.run(5_000, 5_990)
        val ahead = east.pointAt(0.0).distanceTo(p.at())
        p.fix(east, 6_000, 12.0, 0.0) // it stopped at 12 m
        val worst = p.run(6_000, 9_000)
        assertTrue(ahead > 10.0)
        assertTrue(worst <= 14 * 0.016 + 0.01, "frame $worst m")
        assertEquals(0.0, p.at().distanceTo(east.pointAt(12.0)), 0.5)
    }

    @Test fun `it takes a sharp corner on the polyline and never cuts it`() {
        val g = route(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 100.0)) // 90 degree left turn at 100 m
        val p = PuckInterpolator().apply { setRoute(g) }
        p.fix(g, 0, 80.0, 10.0)
        var worstOff = 0.0
        for (sec in 1..4) {
            var t = (sec - 1) * 1000L
            while (t < sec * 1000L) {
                t += 16
                p.advance(t)
                val e = p.at()
                val offLegA = abs(e.lat - pt(0.0, 0.0).lat) * 111_195.0 // distance from the eastbound leg
                val offLegB = abs(e.lon - pt(100.0, 0.0).lon) * 111_195.0 * kotlin.math.cos(Math.toRadians(40.0)) // from the northbound leg
                worstOff = maxOf(worstOff, minOf(offLegA, offLegB))
            }
            p.fix(g, sec * 1000L, 80.0 + 10.0 * sec, 10.0)
        }
        assertTrue(worstOff < 0.5, "left the line by $worstOff m")
        assertEquals(0.0, NavCameraPlanner.angularDistance(p.bearing, 0.0), 10.0) // it turned north
    }

    @Test fun `the heading turns over time, not at once`() {
        val g = route(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 100.0))
        val p = PuckInterpolator().apply { setRoute(g) }
        p.fix(g, 0, 90.0, 10.0)
        assertEquals(90.0, p.bearing, 1e-6)
        p.run(0, 1_100) // crosses the corner after 1 s
        p.fix(g, 1_100, 101.0, 10.0)
        var t = 1_100L
        var prev = p.bearing
        while (t < 2_500) {
            t += 16
            p.advance(t)
            assertTrue(NavCameraPlanner.angularDistance(p.bearing, prev) < 10.0, "snapped from $prev to ${p.bearing}")
            prev = p.bearing
        }
        assertEquals(0.0, NavCameraPlanner.angularDistance(p.bearing, 0.0), 8.0)
    }

    @Test fun `across north the heading goes the short way`() {
        assertEquals(0.0, NavCameraPlanner.angularDistance(PuckInterpolator.approachAngle(359.0, 1.0, 10_000.0, 250.0), 1.0), 0.01)
        val half = PuckInterpolator.approachAngle(359.0, 1.0, 250.0 * 0.6931, 250.0) // half way after ln 2 time constants
        assertEquals(0.0, NavCameraPlanner.angularDistance(half, 0.0), 0.05)
    }

    @Test fun `a u-turn rotates through a half turn over several frames`() {
        val g = route(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 1.0), pt(0.0, 1.0)) // out and back
        val p = PuckInterpolator().apply { setRoute(g) }
        p.fix(g, 0, 90.0, 10.0)
        var prev = p.bearing
        var worstStep = 0.0
        for (sec in 1..4) {
            var t = (sec - 1) * 1000L
            while (t < sec * 1000L) {
                t += 16
                p.advance(t)
                worstStep = maxOf(worstStep, NavCameraPlanner.angularDistance(p.bearing, prev))
                prev = p.bearing
            }
            p.fix(g, sec * 1000L, minOf(90.0 + 10.0 * sec, g.totalMeters), 10.0)
        }
        assertTrue(worstStep < 15.0, "a frame turned $worstStep degrees")
        assertEquals(270.0, p.bearing, 5.0)
    }

    @Test fun `the marker never passes the end of the route`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 1_990.0, 30.0)
        p.run(0, 5_000)
        assertTrue(east.pointAt(east.totalMeters).distanceTo(p.at()) < 0.01)
        assertTrue(p.lon <= east.pointAt(east.totalMeters).lon + 1e-12)
    }

    @Test fun `a reroute (new revision) places the marker on the new fix at once`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 100.0, 10.0)
        p.run(0, 1_000)
        val north = route(pt(500.0, 0.0), pt(500.0, 2_000.0))
        p.setRoute(north)
        p.fix(north, 1_000, 300.0, 10.0, revision = 1)
        assertEquals(0.0, p.at().distanceTo(north.pointAt(300.0)), 0.01)
    }

    @Test fun `a jump beyond the snap distance (a tunnel exit) resyncs at once`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 100.0, 10.0)
        p.run(0, 1_000)
        p.fix(east, 1_000, 900.0, 10.0)
        p.advance(1_016)
        assertTrue(p.at().distanceTo(east.pointAt(900.0)) < 1.0)
    }

    @Test fun `off the route the marker glides along the course of the fix`() {
        val p = PuckInterpolator() // no route
        val a = pt(0.0, 0.0)
        p.onFix(0, a.lat, a.lon, 0.0, 10.0, 90.0, 0, false)
        p.run(0, 1_000)
        assertEquals(10.0, a.distanceTo(p.at()), 0.8)
        assertTrue(abs(p.lat - a.lat) < 1e-7) // due east: the latitude did not change
        assertTrue(p.run(1_000, 1_400) < 10 * 0.016 * 1.5)
    }

    @Test fun `time going backwards or a long stall moves nothing wildly`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 1_000, 100.0, 20.0)
        p.advance(500)
        assertEquals(0.0, p.at().distanceTo(east.pointAt(100.0)), 1e-6)
        p.advance(1_000 + 60_000) // stalled for a minute: one bounded step
        assertTrue(east.pointAt(100.0).distanceTo(p.at()) <= 20.0 * 0.25 + 5.0 * 0.25 + 0.01)
    }

    @Test fun `reset forgets the pose`() {
        val p = PuckInterpolator().apply { setRoute(east) }
        p.fix(east, 0, 100.0, 10.0)
        p.reset()
        assertTrue(!p.hasPose)
        p.fix(east, 5_000, 700.0, 10.0)
        assertEquals(0.0, p.at().distanceTo(east.pointAt(700.0)), 0.01)
    }
}
