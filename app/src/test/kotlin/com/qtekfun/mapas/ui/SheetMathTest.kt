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
}
