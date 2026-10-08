package com.qtekfun.ultimatemaps.route

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteFramingTest {
    private val route = listOf(LatLon(40.0, -3.0), LatLon(41.0, -2.0))
    private var detent = SheetDetent.MEDIUM
    private var navigating = false
    private val frames = mutableListOf<Pair<List<LatLon>, SheetDetent>>()
    private val framing = RouteFraming({ navigating }, { detent }, { p, d -> frames += p to d })

    @Test fun `showing a route frames all of it`() {
        framing.show(route)
        assertEquals(listOf(route to SheetDetent.MEDIUM), frames)
    }

    @Test fun `showing a new route or alternative frames again even after the user moved the map`() {
        framing.show(route)
        framing.onUserMovedMap()
        framing.show(route.reversed())
        assertEquals(2, frames.size)
    }

    @Test fun `sheet change refits when the user did not move the map`() {
        framing.show(route)
        detent = SheetDetent.COLLAPSED
        framing.onDetentChanged()
        assertEquals(2, frames.size)
        assertEquals(SheetDetent.COLLAPSED, frames.last().second)
    }

    @Test fun `sheet change does not fight a manual pan or zoom`() {
        framing.show(route)
        framing.onUserMovedMap()
        detent = SheetDetent.COLLAPSED
        framing.onDetentChanged()
        assertEquals(1, frames.size)
    }

    @Test fun `full sheet and an unchanged detent do not refit`() {
        framing.show(route)
        detent = SheetDetent.FULL
        framing.onDetentChanged()
        detent = SheetDetent.MEDIUM
        framing.onDetentChanged() // back at the framed detent: nothing to do
        assertEquals(1, frames.size)
    }

    @Test fun `no framing during a navigation`() {
        navigating = true
        framing.show(route)
        detent = SheetDetent.COLLAPSED
        framing.onDetentChanged()
        assertTrue(frames.isEmpty())
    }

    @Test fun `nothing happens without a route`() {
        framing.onDetentChanged()
        framing.show(emptyList())
        framing.onUserMovedMap()
        assertTrue(frames.isEmpty())
        framing.show(route)
        framing.clear()
        framing.onDetentChanged()
        assertEquals(1, frames.size)
    }

    @Test fun `a single point route is framed`() {
        framing.show(listOf(route[0]))
        assertEquals(1, frames.size)
    }
}

class RoutePaddingTest {
    @Test fun `bottom padding follows the sheet detent`() {
        val c = RoutePadding.compute(SheetDetent.COLLAPSED, 2400, 2.625f, 80)
        val m = RoutePadding.compute(SheetDetent.MEDIUM, 2400, 2.625f, 80)
        val f = RoutePadding.compute(SheetDetent.FULL, 2400, 2.625f, 80)
        assertTrue(c.bottom < m.bottom && m.bottom < f.bottom)
        assertTrue(Math.abs((2400 * 0.46f).toInt() - RoutePadding.sheetHeightPx(SheetDetent.MEDIUM, 2400, 2.625f)) <= 1)
    }

    @Test fun `top clears the status bar and the right clears the buttons`() {
        val p = RoutePadding.compute(SheetDetent.MEDIUM, 1280, 2f, 48)
        assertTrue(p.top > 48)
        assertEquals(144, p.right)
    }

    @Test fun `short screens keep at least the collapsed height`() {
        assertTrue(RoutePadding.sheetHeightPx(SheetDetent.MEDIUM, 300, 3f) >= (107 * 3f).toInt() - 1)
    }
}
