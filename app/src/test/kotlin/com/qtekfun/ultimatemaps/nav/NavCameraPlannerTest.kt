package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 3D/2D camera decisions as pure numbers: no map, no clock, no Android. */
class NavCameraPlannerTest {
    private fun input(
        speed: Double = 10.0,
        distance: Double? = null,
        type: TurnType? = null,
        mode3d: Boolean = true,
        height: Int = 2400,
        bearing: Double? = 90.0,
    ) = CameraPlanInput(speed, distance, type, mode3d, height, bearing)

    private fun plan(i: CameraPlanInput, planner: NavCameraPlanner = NavCameraPlanner()) = planner.plan(i)

    // ---------------------------------------------------------------- speed bands

    @Test fun `3D zoom is close when slow and wide when fast, never jumping the other way`() {
        val zooms = listOf(0.0, 2.0, 5.0, 10.0, 14.0, 20.0, 25.0, 30.0, 33.0, 45.0).map { plan(input(speed = it)).zoom }
        assertEquals(17.5, zooms.first())
        assertEquals(15.25, zooms.last())
        zooms.zipWithNext().forEach { (a, b) -> assertTrue(b <= a, "zoom must not grow with speed: $zooms") }
    }

    @Test fun `the speed bands of the 3D view`() {
        assertEquals(17.5, plan(input(speed = 0.0)).zoom) // standing
        assertEquals(17.25, plan(input(speed = 5.0)).zoom) // 18 km/h, a street
        assertEquals(16.5, plan(input(speed = 14.0)).zoom) // 50 km/h, town
        assertEquals(15.75, plan(input(speed = 25.0)).zoom) // 90 km/h, road
        assertEquals(15.25, plan(input(speed = 33.0)).zoom) // 120 km/h, motorway
    }

    @Test fun `zoom moves in quarter steps`() {
        for (s in 0..40) {
            val z = plan(input(speed = s * 0.9)).zoom
            assertEquals(0.0, (z * 4) % 1.0, 1e-9, "speed ${s * 0.9}: $z")
        }
    }

    @Test fun `the flat 2D view keeps the old range from 17_5 down to 15`() {
        assertEquals(17.5, plan(input(speed = 0.0, mode3d = false)).zoom)
        assertEquals(15.0, plan(input(speed = 30.0, mode3d = false)).zoom)
        assertEquals(15.0, plan(input(speed = 50.0, mode3d = false)).zoom)
    }

    @Test fun `a negative or non finite speed counts as standing`() {
        assertEquals(17.5, plan(input(speed = -3.0)).zoom)
        assertEquals(17.5, plan(input(speed = Double.NaN)).zoom)
    }

    @Test fun `tilt is 55 in town and rises to 60 at road speed, and 2D is flat`() {
        assertEquals(55.0, plan(input(speed = 0.0)).tilt)
        assertEquals(55.0, plan(input(speed = 14.0)).tilt)
        assertEquals(60.0, plan(input(speed = 25.0)).tilt)
        assertEquals(60.0, plan(input(speed = 40.0)).tilt)
        val mid = plan(input(speed = 19.5)).tilt
        assertTrue(mid > 55.0 && mid < 60.0, "$mid")
        assertEquals(0.0, plan(input(speed = 25.0, mode3d = false)).tilt)
    }

    @Test fun `the tilt never exceeds what MapLibre allows by default`() {
        for (s in 0..60) for (t in TurnType.entries) {
            val tilt = plan(input(speed = s.toDouble(), distance = 40.0, type = t)).tilt
            assertTrue(tilt in 0.0..60.0, "$tilt")
        }
    }

    // ---------------------------------------------------------------- maneuvers

    @Test fun `the camera zooms in as a turn approaches and eases back out after it`() {
        val at = { d: Double? -> plan(input(speed = 10.0, distance = d, type = TurnType.RIGHT)).zoom }
        val far = at(2000.0)
        val approaching = at(120.0)
        val onTop = at(20.0)
        assertTrue(far < approaching && approaching <= onTop, "$far $approaching $onTop")
        assertEquals(far + 0.5, onTop, 1e-9)
        // After the turn the next maneuver is far again (or none): back to the speed zoom.
        assertEquals(far, at(1500.0))
        assertEquals(far, at(null))
    }

    @Test fun `the closer the turn the closer the zoom, step by step`() {
        val zooms = listOf(400.0, 250.0, 180.0, 120.0, 80.0, 50.0, 10.0).map { plan(input(speed = 10.0, distance = it, type = TurnType.LEFT)).zoom }
        zooms.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "$zooms") }
        assertTrue(zooms.last() > zooms.first())
    }

    @Test fun `a roundabout zooms in more than a turn and flattens the tilt to show the whole circle`() {
        val turn = plan(input(speed = 10.0, distance = 40.0, type = TurnType.RIGHT))
        val round = plan(input(speed = 10.0, distance = 40.0, type = TurnType.ROUNDABOUT_ENTER))
        assertTrue(round.zoom > turn.zoom)
        assertTrue(round.tilt < turn.tilt)
        assertEquals(43.0, round.tilt, 1e-9)
        assertEquals(55.0, plan(input(speed = 10.0, distance = 900.0, type = TurnType.ROUNDABOUT_ENTER)).tilt)
        assertEquals(round.zoom, plan(input(speed = 10.0, distance = 40.0, type = TurnType.ROUNDABOUT_LEAVE)).zoom)
    }

    @Test fun `going straight, merging and departing add nothing`() {
        val base = plan(input(speed = 10.0, distance = null)).zoom
        for (t in listOf(TurnType.STRAIGHT, TurnType.MERGE, TurnType.DEPART)) assertEquals(base, plan(input(speed = 10.0, distance = 10.0, type = t)).zoom, "$t")
    }

    @Test fun `sharp turns, u turns and the arrival come in closer than a normal turn`() {
        val normal = plan(input(speed = 0.0, distance = 20.0, type = TurnType.RIGHT)).zoom
        for (t in listOf(TurnType.SHARP_LEFT, TurnType.U_TURN_RIGHT, TurnType.ARRIVE, TurnType.ARRIVE_LEFT)) {
            assertTrue(plan(input(speed = 0.0, distance = 20.0, type = t)).zoom > normal, "$t")
        }
        assertTrue(plan(input(speed = 0.0, distance = 20.0, type = TurnType.SLIGHT_RIGHT)).zoom < normal)
    }

    @Test fun `at speed the approach starts earlier so the driver sees the turn in time`() {
        val slow = NavCameraPlanner.approach(200.0, speedMps = 5.0)
        val fast = NavCameraPlanner.approach(200.0, speedMps = 30.0)
        assertEquals(0.0, slow)
        assertTrue(fast > 0.0)
        assertEquals(1.0, NavCameraPlanner.approach(50.0, 10.0))
        assertEquals(0.0, NavCameraPlanner.approach(300.0, 40.0))
    }

    @Test fun `the zoom stays within the limits whatever the maneuver`() {
        for (s in 0..45) for (t in TurnType.entries) for (d in listOf(0.0, 30.0, 100.0, 5000.0)) for (m in listOf(true, false)) {
            val z = plan(input(speed = s.toDouble(), distance = d, type = t, mode3d = m)).zoom
            assertTrue(z in NavCameraPlanner.ZOOM_MIN..NavCameraPlanner.ZOOM_MAX, "$z")
        }
    }

    @Test fun `a negative or missing distance is ignored`() {
        val base = plan(input(speed = 10.0, distance = null, type = TurnType.RIGHT)).zoom
        assertEquals(base, plan(input(speed = 10.0, distance = -5.0, type = TurnType.RIGHT)).zoom)
        assertEquals(base, plan(input(speed = 10.0, distance = Double.NaN, type = TurnType.RIGHT)).zoom)
    }

    // ---------------------------------------------------------------- bearing

    @Test fun `the first bearing is the course, normalised`() {
        assertEquals(10.0, plan(input(bearing = 370.0)).bearing, 1e-9)
        assertEquals(350.0, plan(input(bearing = -10.0)).bearing, 1e-9)
    }

    @Test fun `standing still keeps the last bearing whatever the receiver says`() {
        val p = NavCameraPlanner()
        assertEquals(90.0, p.plan(input(speed = 10.0, bearing = 90.0)).bearing)
        assertEquals(90.0, p.plan(input(speed = 0.0, bearing = 270.0)).bearing)
        assertEquals(90.0, p.plan(input(speed = 0.5, bearing = 0.0)).bearing)
        assertEquals(90.0, p.heading)
        // Moving again: it follows the new course.
        assertTrue(p.plan(input(speed = 8.0, bearing = 100.0)).bearing > 90.0)
    }

    @Test fun `without a heading the last bearing is kept, and with none yet it is north`() {
        val p = NavCameraPlanner()
        assertEquals(0.0, p.plan(input(bearing = null)).bearing)
        val q = NavCameraPlanner()
        q.plan(input(bearing = 200.0))
        assertEquals(200.0, q.plan(input(bearing = null)).bearing)
        assertEquals(200.0, q.plan(input(bearing = Double.NaN)).bearing)
    }

    @Test fun `a standing start uses the first heading it is given`() {
        assertEquals(135.0, plan(input(speed = 0.0, bearing = 135.0)).bearing, 1e-9)
    }

    @Test fun `a jump of the course is smoothed over several updates and converges`() {
        val p = NavCameraPlanner()
        p.plan(input(bearing = 0.0))
        val seq = (1..8).map { p.plan(input(bearing = 90.0)).bearing }
        assertEquals(45.0, seq[0], 1e-9)
        assertEquals(67.5, seq[1], 1e-9)
        seq.zipWithNext().forEach { (a, b) -> assertTrue(b > a && b <= 90.0) }
        assertEquals(90.0, seq.last(), 0.5)
    }

    @Test fun `one noisy fix does not swing the map`() {
        val p = NavCameraPlanner()
        p.plan(input(bearing = 90.0))
        val noisy = p.plan(input(bearing = 270.0)).bearing // a 180 degree glitch
        assertEquals(180.0, noisy, 1e-9) // only half of the way
        val back = p.plan(input(bearing = 90.0)).bearing
        assertEquals(135.0, back, 1e-9) // and it comes back, not further away
    }

    @Test fun `wrap around north goes the short way, 359 to 1 is a 2 degree turn`() {
        val p = NavCameraPlanner()
        assertEquals(359.0, p.plan(input(bearing = 359.0)).bearing, 1e-9)
        val next = p.plan(input(bearing = 1.0)).bearing
        assertEquals(0.0, next, 1e-9) // halfway: 359 + 1
        val after = p.plan(input(bearing = 1.0)).bearing
        assertEquals(0.5, after, 1e-9)
        // And the other way round.
        val q = NavCameraPlanner()
        q.plan(input(bearing = 2.0))
        assertEquals(0.0, q.plan(input(bearing = 358.0)).bearing, 1e-9)
    }

    @Test fun `the bearing is always within 0 to 360`() {
        val p = NavCameraPlanner()
        var b = 0.0
        repeat(400) {
            b += 37.0
            val r = p.plan(input(bearing = b)).bearing
            assertTrue(r >= 0.0 && r < 360.0, "$r")
        }
    }

    @Test fun `reset forgets the bearing for the next trip`() {
        val p = NavCameraPlanner()
        p.plan(input(bearing = 200.0))
        p.reset()
        assertEquals(null, p.heading)
        assertEquals(10.0, p.plan(input(speed = 0.0, bearing = 10.0)).bearing)
    }

    @Test fun `signedDelta and angularDistance`() {
        assertEquals(2.0, NavCameraPlanner.signedDelta(359.0, 1.0), 1e-9)
        assertEquals(-2.0, NavCameraPlanner.signedDelta(1.0, 359.0), 1e-9)
        assertEquals(180.0, NavCameraPlanner.signedDelta(0.0, 180.0), 1e-9)
        assertEquals(180.0, NavCameraPlanner.signedDelta(180.0, 0.0), 1e-9)
        assertEquals(0.0, NavCameraPlanner.angularDistance(720.0, 0.0), 1e-9)
        assertEquals(170.0, NavCameraPlanner.angularDistance(-90.0, 100.0), 1e-9)
    }

    // ---------------------------------------------------------------- padding / marker position

    @Test fun `in 3D the marker sits in the lower third of the screen and nothing is cut at the bottom`() {
        for (h in listOf(1280, 1920, 2400, 2992)) {
            val pad = plan(input(height = h)).padding
            assertEquals(0, pad.bottom)
            assertEquals(0, pad.left + pad.right)
            val y = NavCameraPlanner.markerY(pad, h)
            assertTrue(y >= h * 2.0 / 3.0, "marker at $y of $h")
            assertTrue(y <= h * 0.85, "marker above the bottom panel: $y of $h")
            assertEquals(h * NavCameraPlanner.MARKER_Y_3D, y, 1.0)
        }
    }

    @Test fun `in 2D the marker is lower than centre but higher than in 3D`() {
        val h = 2400
        val y2 = NavCameraPlanner.markerY(plan(input(height = h, mode3d = false)).padding, h)
        val y3 = NavCameraPlanner.markerY(plan(input(height = h)).padding, h)
        assertTrue(y2 > h / 2.0 && y2 < y3, "$y2 $y3")
    }

    @Test fun `the road ahead keeps room under the banner`() {
        // The banner takes roughly the top 20 %: the marker, and the road up to it, are well below that.
        val h = 2400
        val y = NavCameraPlanner.markerY(plan(input(height = h)).padding, h)
        assertTrue(y - h * 0.20 > h * 0.4)
    }

    @Test fun `an unknown screen height gives no padding`() {
        assertEquals(CameraPadding.NONE, plan(input(height = 0)).padding)
        assertEquals(CameraPadding.NONE, plan(input(height = -5)).padding)
    }

    @Test fun `the same inputs give the same plan`() {
        assertEquals(plan(input(speed = 12.0, distance = 90.0, type = TurnType.LEFT)), plan(input(speed = 12.0, distance = 90.0, type = TurnType.LEFT)))
    }
}
