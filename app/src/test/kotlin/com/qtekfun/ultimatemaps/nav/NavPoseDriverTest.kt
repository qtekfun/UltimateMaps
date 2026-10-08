package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Marker and camera pushes on a virtual clock, with a recording engine. */
class NavPoseDriverTest {
    private class Rec : MapEngine {
        val cams = mutableListOf<CameraState>()
        val users = mutableListOf<LatLon?>()

        /** The marker as it was when each camera was pushed. */
        val userAtCam = mutableListOf<LatLon?>()
        override fun setCamera(center: LatLon, zoom: Double) = Unit
        override fun camera() = LatLon(0.0, 0.0) to 1.0
        override fun animateTo(state: CameraState, durationMillis: Int) {
            assertEquals(0, durationMillis)
            cams += state
            userAtCam += users.lastOrNull()
        }
        override fun showUserLocation(point: LatLon?, accuracyMeters: Float?) { users += point }
        override fun close() = Unit
    }

    private val east = RouteGeometry(listOf(pt(0.0, 0.0), pt(5_000.0, 0.0)))
    private val engine = Rec()
    private val driver = NavPoseDriver(engine).apply { setRoute(east) }
    private val follow = CameraState(pt(0.0, 0.0), 17.0, 90.0, 55.0, CameraPadding(0, 1000, 0, 0))

    private fun fix(t: Long, along: Double, speed: Double = 10.0) {
        val nav = navState(speedMps = speed).copy(position = east.pointAt(along), traveledMeters = along, bearingDegrees = 90f, offRouteMeters = 0.0)
        driver.onFix(nav, t)
    }

    @Test fun `the camera centre is the marker pushed in the same frame`() {
        fix(0, 0.0)
        driver.retarget(follow, transition = false, current = follow)
        var t = 0L
        repeat(180) {
            driver.frame(t)
            t += 16
            if (t % 1000L < 16) fix(t, t / 100.0)
        }
        assertTrue(engine.cams.size > 100)
        for (i in engine.cams.indices) {
            assertEquals(0.0, engine.cams[i].center.distanceTo(engine.userAtCam[i]!!), 0.01)
        }
    }

    @Test fun `a transition eases the camera from where it is in about 1_2 s with no jump`() {
        fix(0, 500.0)
        val far = follow.copy(center = pt(0.0, 0.0), zoom = 14.0, tilt = 0.0, bearing = 0.0, padding = CameraPadding.NONE)
        driver.retarget(follow.copy(center = east.pointAt(500.0)), transition = true, current = far)
        var t = 0L
        repeat(110) { driver.frame(t); t += 16 }
        val zooms = engine.cams.map { it.zoom }
        assertTrue(zooms.zipWithNext().all { (a, b) -> abs(b - a) < 0.2 }, "a zoom step was too big")
        assertTrue(zooms.first() < 14.5, "starts near the old zoom")
        assertEquals(17.0, zooms.last(), 0.1)
        assertEquals(55.0, engine.cams.last().tilt, 1.0)
        // The centre starts at the old camera (500 m away) and ends on the marker, in steps of a few metres.
        assertTrue(engine.cams.first().center.distanceTo(far.center) < 100.0)
        assertEquals(0.0, engine.cams.last().center.distanceTo(engine.users.last()!!), 5.0)
    }

    @Test fun `released, only the marker moves and the camera is left alone`() {
        fix(0, 0.0)
        driver.release()
        var t = 0L
        repeat(60) { driver.frame(t); t += 16 }
        assertTrue(engine.cams.isEmpty())
        assertTrue(engine.users.size > 10)
    }

    @Test fun `a standing car pushes nothing once settled and asks for no more frames`() {
        fix(0, 0.0, speed = 0.0)
        driver.retarget(follow.copy(center = east.pointAt(0.0)), transition = false, current = follow.copy(center = east.pointAt(0.0)))
        var t = 0L
        repeat(120) { driver.frame(t); t += 16 }
        val users = engine.users.size
        val cams = engine.cams.size
        repeat(60) { driver.frame(t); t += 16 }
        assertEquals(users, engine.users.size)
        assertEquals(cams, engine.cams.size)
        assertFalse(driver.needsFrames(t))
    }

    @Test fun `the push rate is capped by the minimum interval (battery saver)`() {
        fix(0, 0.0)
        driver.release()
        var t = 0L
        repeat(120) { driver.frame(t, PowerSavePolicy.SAVER_MIN_INTERVAL_MILLIS); t += 8 } // a 120 Hz display, 960 ms
        assertTrue(engine.users.size <= 31, "pushes ${engine.users.size}")
        assertTrue(engine.users.size >= 20, "pushes ${engine.users.size}")
    }

    @Test fun `a car off the route uses the free mode along its course`() {
        val nav = navState(speedMps = 10.0).copy(position = pt(0.0, 50.0), offRouteMeters = 80.0, bearingDegrees = 0f)
        driver.onFix(nav, 0)
        driver.release()
        var t = 0L
        repeat(60) { driver.frame(t); t += 16 }
        assertTrue(engine.users.last()!!.lat > nav.position.lat) // went north along the course
    }
}
