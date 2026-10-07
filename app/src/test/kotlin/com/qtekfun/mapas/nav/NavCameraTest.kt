package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.nav.ManeuverInfo
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavCameraTest {
    private fun state(speed: Double = 10.0, bearing: Float = 90f, east: Double = 0.0, next: Double? = null) =
        navState(speedMps = speed, next = next?.let { ManeuverInfo(Maneuver(5, TurnType.RIGHT), it) }).copy(position = pt(east, 0.0), bearingDegrees = bearing)

    @Test fun `zoom goes from close when slow to wide when fast, in quarter steps, and a bit closer near a turn`() {
        val cam = NavCamera()
        val slow = cam.target(state(speed = 0.0)).zoom
        val fast = cam.target(state(speed = 35.0)).zoom
        assertEquals(NavCamera.ZOOM_NEAR, slow)
        assertEquals(NavCamera.ZOOM_FAR, fast)
        val mid = cam.target(state(speed = 15.0)).zoom
        assertTrue(mid > fast && mid < slow)
        assertEquals(0.0, (mid * 4) % 1.0, 1e-9)
        assertEquals(slow + 0.5, cam.target(state(speed = 0.0, next = 80.0)).zoom, 1e-9)
        assertEquals(slow, cam.target(state(speed = 0.0, next = 600.0)).zoom, 1e-9)
    }

    @Test fun `the map heads up with the route bearing and a fixed tilt`() {
        val t = NavCamera().target(state(bearing = 370f))
        assertEquals(10.0, t.bearing, 1e-6)
        assertEquals(NavCamera.TILT_DEGREES, t.tilt)
        assertEquals(350.0, NavCamera().target(state(bearing = -10f)).bearing, 1e-6)
    }

    @Test fun `it does not move the camera more often than the interval or for tiny changes`() {
        val cam = NavCamera(minIntervalMillis = 800)
        assertNotNull(cam.next(state(), nowMillis = 0)) // the first one always goes
        assertNull(cam.next(state(east = 100.0), nowMillis = 500)) // too soon, even for a big move
        assertNull(cam.next(state(east = 3.0), nowMillis = 1_000)) // 3 m: not worth it
        assertNull(cam.next(state(bearing = 92f), nowMillis = 1_100)) // 2 degrees
        assertNotNull(cam.next(state(east = 40.0), nowMillis = 1_200)) // 40 m: yes
        assertNotNull(cam.next(state(east = 40.0, bearing = 120f), nowMillis = 2_100)) // 30 degrees
        assertNotNull(cam.next(state(east = 40.0, bearing = 120f, speed = 30.0), nowMillis = 3_000)) // zoom changed by more than a quarter
    }

    @Test fun `a standing car costs nothing and a forced update ignores the throttle`() {
        val cam = NavCamera()
        assertNotNull(cam.next(state(speed = 0.0), 0))
        repeat(50) { assertNull(cam.next(state(speed = 0.0), 1_000L * (it + 1))) }
        assertNotNull(cam.next(state(speed = 0.0), 51_001L, force = true))
        cam.reset()
        assertNotNull(cam.next(state(speed = 0.0), 51_002L))
    }

    @Test fun `bearing differences wrap around north`() {
        assertEquals(20.0, NavCamera.bearingDelta(350.0, 10.0), 1e-9)
        assertEquals(180.0, NavCamera.bearingDelta(0.0, 180.0), 1e-9)
        assertEquals(0.0, NavCamera.bearingDelta(359.9999, 359.9999), 1e-9)
    }
}
