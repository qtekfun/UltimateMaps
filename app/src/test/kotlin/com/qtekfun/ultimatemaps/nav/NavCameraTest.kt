package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.nav.ManeuverInfo
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavCameraTest {
    private fun state(speed: Double = 10.0, bearing: Float = 90f, east: Double = 0.0, next: Double? = null) =
        navState(speedMps = speed, next = next?.let { ManeuverInfo(Maneuver(5, TurnType.RIGHT), it) }).copy(position = pt(east, 0.0), bearingDegrees = bearing)

    @Test fun `the target is the planner's plan placed at the user`() {
        val t = NavCamera().target(state(speed = 0.0), mode3d = true, screenHeightPx = 2400)
        assertEquals(17.5, t.zoom)
        assertEquals(55.0, t.tilt)
        assertEquals(90.0, t.bearing, 1e-9)
        assertEquals(state().position, t.center)
        assertEquals(NavCameraPlanner.padding(true, 2400), t.padding)
        assertTrue(t.padding.top > 0)
    }

    @Test fun `zoom is closer near a turn and wide when fast`() {
        val cam = NavCamera()
        assertTrue(cam.target(state(speed = 0.0, next = 30.0)).zoom > cam.target(state(speed = 0.0, next = 600.0)).zoom)
        assertTrue(cam.target(state(speed = 35.0)).zoom < cam.target(state(speed = 0.0)).zoom)
    }

    @Test fun `2D is flat, course up, and has less padding than 3D`() {
        val cam = NavCamera()
        val flat = cam.target(state(), mode3d = false, screenHeightPx = 2400)
        val tilted = cam.target(state(), mode3d = true, screenHeightPx = 2400)
        assertEquals(0.0, flat.tilt)
        assertEquals(90.0, flat.bearing, 1e-9)
        assertTrue(flat.padding.top < tilted.padding.top)
    }

    @Test fun `bearings wrap around north`() {
        assertEquals(10.0, NavCamera().target(state(bearing = 370f)).bearing, 1e-6)
        assertEquals(350.0, NavCamera().target(state(bearing = -10f)).bearing, 1e-6)
    }

    @Test fun `it does not move the camera more often than the interval or for tiny changes`() {
        val cam = NavCamera(minIntervalMillis = 800)
        assertNotNull(cam.next(state(), nowMillis = 0)) // the first one always goes
        assertNull(cam.next(state(east = 100.0), nowMillis = 500)) // too soon, even for a big move
        assertNull(cam.next(state(east = 3.0), nowMillis = 1_000)) // 3 m: not worth it
        assertNull(cam.next(state(bearing = 92f), nowMillis = 1_100)) // 2 degrees
        assertNotNull(cam.next(state(east = 40.0), nowMillis = 1_200)) // 40 m: yes
        assertNotNull(cam.next(state(east = 40.0, bearing = 200f), nowMillis = 2_100)) // a big turn
        assertNotNull(cam.next(state(east = 40.0, bearing = 200f, speed = 30.0), nowMillis = 3_000)) // zoom changed by more than a quarter
    }

    @Test fun `a standing car costs nothing and a forced update ignores the throttle`() {
        val cam = NavCamera()
        assertNotNull(cam.next(state(speed = 0.0), 0))
        repeat(50) { assertNull(cam.next(state(speed = 0.0), 1_000L * (it + 1))) }
        assertNotNull(cam.next(state(speed = 0.0), 51_001L, force = true))
        cam.reset()
        assertNotNull(cam.next(state(speed = 0.0), 51_002L))
    }

    @Test fun `a standing car with a noisy course keeps the last bearing and sends nothing`() {
        val cam = NavCamera()
        assertNotNull(cam.next(state(speed = 0.0, bearing = 90f), 0))
        assertEquals(90.0, cam.heading!!, 1e-9)
        for (i in 1..20) assertNull(cam.next(state(speed = 0.0, bearing = (i * 53 % 360).toFloat()), 1_000L * i))
        assertEquals(90.0, cam.heading!!, 1e-9)
    }

    @Test fun `switching between 2D and 3D goes through even inside the interval and with the car standing still`() {
        val cam = NavCamera(minIntervalMillis = 800)
        val first = cam.next(state(speed = 0.0), 0, mode3d = true, screenHeightPx = 2400)
        assertNotNull(first)
        val flat = cam.next(state(speed = 0.0), 10, mode3d = false, screenHeightPx = 2400)
        assertNotNull(flat)
        assertEquals(0.0, flat.tilt)
        assertNotEquals(first.padding, flat.padding)
        assertNotNull(cam.next(state(speed = 0.0), 20, mode3d = true, screenHeightPx = 2400))
        // Same mode again, nothing changed: quiet.
        assertNull(cam.next(state(speed = 0.0), 2_000, mode3d = true, screenHeightPx = 2400))
    }

    @Test fun `a change of padding alone is sent`() {
        val cam = NavCamera()
        assertNotNull(cam.next(state(speed = 0.0), 0, screenHeightPx = 2400))
        assertNotNull(cam.next(state(speed = 0.0), 10, screenHeightPx = 1200))
    }

    @Test fun `forget drops the heading and the throttle, reset only the throttle`() {
        val cam = NavCamera()
        cam.next(state(speed = 0.0, bearing = 200f), 0)
        cam.reset()
        assertEquals(200.0, cam.heading!!, 1e-9)
        cam.forget()
        assertNull(cam.heading)
        assertEquals(CameraPadding.NONE, cam.target(state(), screenHeightPx = 0).padding)
    }

    @Test fun `the animation is a bit shorter than the update interval and the transitions are slower`() {
        val cam = NavCamera(minIntervalMillis = 800)
        assertEquals(880, cam.animationMillis)
        assertTrue(NavCamera.TRANSITION_MILLIS > cam.animationMillis)
        assertTrue(NavCamera.LEAVE_MILLIS > cam.animationMillis)
    }

    @Test fun `bearing differences wrap around north`() {
        assertEquals(20.0, NavCamera.bearingDelta(350.0, 10.0), 1e-9)
        assertEquals(180.0, NavCamera.bearingDelta(0.0, 180.0), 1e-9)
        assertEquals(0.0, NavCamera.bearingDelta(359.9999, 359.9999), 1e-9)
    }
}
