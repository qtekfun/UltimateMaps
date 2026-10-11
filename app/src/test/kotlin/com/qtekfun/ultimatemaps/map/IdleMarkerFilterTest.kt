package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleMarkerFilterTest {
    private val here = LatLon(40.0, -3.0)

    /** About [meters] north of [here]. */
    private fun north(meters: Double) = LatLon(40.0 + meters / 111_000.0, -3.0)

    @Test fun `the first position always goes out`() {
        assertTrue(IdleMarkerFilter().shouldShow(here, 0f, 0, navigating = false))
    }

    @Test fun `GPS wobble and compass noise of a phone lying still stay out`() {
        val f = IdleMarkerFilter()
        f.shouldShow(here, 90f, 0, navigating = false)
        for (i in 1..4) {
            assertFalse(f.shouldShow(north(1.5), 92f, i * 1_000L, navigating = false))
        }
    }

    @Test fun `a real move or turn goes out`() {
        val f = IdleMarkerFilter()
        f.shouldShow(here, 90f, 0, navigating = false)
        assertTrue(f.shouldShow(north(5.0), 90f, 1_000, navigating = false))
        assertTrue(f.shouldShow(north(5.0), 100f, 2_000, navigating = false))
    }

    @Test fun `the turn is measured on the circle`() {
        val f = IdleMarkerFilter()
        f.shouldShow(here, 359f, 0, navigating = false)
        assertFalse(f.shouldShow(here, 1f, 1_000, navigating = false)) // 2 degrees
        assertTrue(f.shouldShow(here, 10f, 2_000, navigating = false))
    }

    @Test fun `a refresh goes out every few seconds anyway`() {
        val f = IdleMarkerFilter(refreshMillis = 5_000)
        f.shouldShow(here, 0f, 0, navigating = false)
        assertFalse(f.shouldShow(here, 0f, 4_999, navigating = false))
        assertTrue(f.shouldShow(here, 0f, 5_000, navigating = false))
    }

    @Test fun `while navigating every change goes out`() {
        val f = IdleMarkerFilter()
        f.shouldShow(here, 0f, 0, navigating = true)
        assertTrue(f.shouldShow(north(0.2), 0.5f, 16, navigating = true))
    }

    @Test fun `after a reset the next one goes out`() {
        val f = IdleMarkerFilter()
        f.shouldShow(here, 0f, 0, navigating = false)
        f.reset()
        assertTrue(f.shouldShow(here, 0f, 10, navigating = false))
    }
}
