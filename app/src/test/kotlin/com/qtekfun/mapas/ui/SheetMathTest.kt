package com.qtekfun.mapas.ui

import com.qtekfun.mapas.ui.sheet.SheetDetent
import com.qtekfun.mapas.ui.sheet.SheetMath
import kotlin.test.Test
import kotlin.test.assertEquals

class SheetMathTest {
    private val c = 100f
    private val m = 500f
    private val f = 900f

    private fun settle(at: Float, v: Float) = SheetMath.settle(at, v, c, m, f)

    @Test
    fun slowDragGoesToNearest() {
        assertEquals(SheetDetent.COLLAPSED, settle(250f, 0f))
        assertEquals(SheetDetent.MEDIUM, settle(350f, 100f))
        assertEquals(SheetDetent.MEDIUM, settle(650f, -100f))
        assertEquals(SheetDetent.FULL, settle(750f, 0f))
    }

    @Test
    fun flingGoesToNextDetentInItsDirection() {
        assertEquals(SheetDetent.MEDIUM, settle(120f, 3000f))
        assertEquals(SheetDetent.FULL, settle(520f, 3000f))
        assertEquals(SheetDetent.MEDIUM, settle(880f, -3000f))
        assertEquals(SheetDetent.COLLAPSED, settle(480f, -3000f))
    }

    @Test
    fun detentHeightsAndHandleCycle() {
        assertEquals(c, SheetMath.height(SheetDetent.COLLAPSED, c, m, f))
        assertEquals(m, SheetMath.height(SheetDetent.MEDIUM, c, m, f))
        assertEquals(f, SheetMath.height(SheetDetent.FULL, c, m, f))
        assertEquals(SheetDetent.MEDIUM, SheetDetent.COLLAPSED.next())
        assertEquals(SheetDetent.FULL, SheetDetent.MEDIUM.next())
        assertEquals(SheetDetent.COLLAPSED, SheetDetent.FULL.next())
    }

    @Test
    fun slowDragLeavesTheStartingDetentAfterAQuarterOfTheGap() {
        // MEDIUM -> FULL: gap 400 px, 25 % = 100 px. Before: needed to pass the midpoint (200 px).
        fun slow(at: Float, start: SheetDetent) = SheetMath.settle(at, 0f, c, m, f, start = start)
        assertEquals(SheetDetent.MEDIUM, slow(590f, SheetDetent.MEDIUM))
        assertEquals(SheetDetent.FULL, slow(610f, SheetDetent.MEDIUM))
        assertEquals(SheetDetent.MEDIUM, slow(410f, SheetDetent.MEDIUM))
        assertEquals(SheetDetent.COLLAPSED, slow(390f, SheetDetent.MEDIUM))
        assertEquals(SheetDetent.MEDIUM, slow(450f, SheetDetent.MEDIUM))
        // COLLAPSED -> MEDIUM: gap 400.
        assertEquals(SheetDetent.COLLAPSED, slow(190f, SheetDetent.COLLAPSED))
        assertEquals(SheetDetent.MEDIUM, slow(210f, SheetDetent.COLLAPSED))
        // Without the start the old rule (nearest) applies.
        assertEquals(SheetDetent.MEDIUM, settle(610f, 0f))
    }

    @Test
    fun aDragThatGoesPastTheNextDetentGoesToTheNearestOne() {
        assertEquals(SheetDetent.FULL, SheetMath.settle(880f, 0f, c, m, f, start = SheetDetent.COLLAPSED))
    }

    @Test
    fun flingThresholdIsInclusiveOfTheBoundary() {
        assertEquals(SheetDetent.COLLAPSED, SheetMath.settle(120f, 499f, c, m, f))
        assertEquals(SheetDetent.MEDIUM, SheetMath.settle(120f, 500f, c, m, f))
        assertEquals(SheetDetent.MEDIUM, SheetMath.settle(120f, 700f, c, m, f, flingThreshold = 600f))
        assertEquals(SheetDetent.COLLAPSED, SheetMath.settle(120f, 700f, c, m, f, flingThreshold = 800f))
    }

    @Test
    fun nestedScrollGrowsBeforeTheListAndShrinksAfterIt() {
        // Finger up (dy < 0) grows, capped at max; the remainder is for the list.
        assertEquals(100f, SheetMath.growBeforeChild(500f, -100f, 900f))
        assertEquals(50f, SheetMath.growBeforeChild(850f, -100f, 900f))
        assertEquals(0f, SheetMath.growBeforeChild(900f, -100f, 900f))
        assertEquals(0f, SheetMath.growBeforeChild(500f, 100f, 900f))
        // Finger down on a list at the top shrinks, down to min.
        assertEquals(100f, SheetMath.shrinkAfterChild(900f, 100f, 100f))
        assertEquals(30f, SheetMath.shrinkAfterChild(130f, 100f, 100f))
        assertEquals(0f, SheetMath.shrinkAfterChild(100f, 100f, 100f))
        assertEquals(0f, SheetMath.shrinkAfterChild(500f, -100f, 100f))
    }

    @Test
    fun atDetentAndNeighbours() {
        assertEquals(true, SheetMath.atDetent(500f, c, m, f))
        assertEquals(false, SheetMath.atDetent(520f, c, m, f))
        assertEquals(null, SheetDetent.FULL.above())
        assertEquals(SheetDetent.FULL, SheetDetent.MEDIUM.above())
        assertEquals(null, SheetDetent.COLLAPSED.below())
        assertEquals(SheetDetent.COLLAPSED, SheetDetent.MEDIUM.below())
    }
}
