package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CruiseDetectorTest {
    private fun d() = CruiseDetector()

    @Test fun `cruising starts only after the conditions held for ten seconds and ends at once`() {
        val c = d()
        assertFalse(c.update(0, 30.0, 5_000.0, 2.0, true))
        assertFalse(c.update(9_000, 30.0, 4_900.0, 2.0, true))
        assertTrue(c.update(10_000, 30.0, 4_800.0, 2.0, true))
        assertFalse(c.update(11_000, 30.0, 1_000.0, 2.0, true), "a maneuver within 1.5 km ends it")
        assertFalse(c.update(12_000, 30.0, 4_000.0, 2.0, true), "and it starts over")
    }

    @Test fun `each condition alone prevents it`() {
        fun cruise(speed: Double = 30.0, maneuver: Double? = 5_000.0, heading: Double = 2.0, steady: Boolean = true): Boolean {
            val c = d()
            c.update(0, speed, maneuver, heading, steady)
            return c.update(20_000, speed, maneuver, heading, steady)
        }
        assertTrue(cruise())
        assertTrue(cruise(maneuver = null), "no maneuver left: the way to the destination is clear")
        assertFalse(cruise(speed = 15.0), "slower than 72 km/h")
        assertFalse(cruise(maneuver = 800.0))
        assertFalse(cruise(heading = 20.0), "a bend ahead")
        assertFalse(cruise(steady = false), "estimated position or tunnel")
    }

    @Test fun `heading change looks 600 m ahead on the route`() {
        val straight = RouteGeometry(listOf(pt(0.0, 0.0), pt(0.0, 3_000.0)))
        assertEquals(0.0, CruiseDetector.headingChange(straight, 100.0), 0.5)
        // a right angle 450 m after the position
        val corner = RouteGeometry(listOf(pt(0.0, 0.0), pt(0.0, 550.0), pt(3_000.0, 550.0)))
        assertTrue(CruiseDetector.headingChange(corner, 100.0) > 60.0)
        assertEquals(0.0, CruiseDetector.headingChange(corner, 1_000.0), 0.5, "past the corner it is straight again")
    }
}
