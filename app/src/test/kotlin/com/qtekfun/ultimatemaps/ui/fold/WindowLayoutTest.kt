package com.qtekfun.ultimatemaps.ui.fold

import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.route.RoutePadding
import com.qtekfun.ultimatemaps.ui.fold.FoldTestSupport.DENSITY
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The arrangement rules for every posture, pure (no Compose). */
class WindowLayoutTest {
    private fun layout(w: Int, h: Int, fold: FoldFeatureInfo? = null, inset: Int = 0, rtl: Boolean = false) =
        WindowLayout.compute(w.dp, h.dp, inset.dp, fold, DENSITY, rtl)

    @Test fun `a normal phone upright and a cover display keep the portrait layout`() {
        assertEquals(Posture.PORTRAIT, layout(360, 800).posture)
        assertEquals(Posture.PORTRAIT, layout(411, 891).posture)
        assertEquals(Posture.PORTRAIT, layout(FoldTestSupport.Z_FOLD_COVER_W, FoldTestSupport.Z_FOLD_COVER_H).posture)
        assertEquals(Posture.PORTRAIT, layout(FoldTestSupport.PIXEL_FOLD_COVER_W, FoldTestSupport.PIXEL_FOLD_COVER_H).posture)
        // 6:5 cover and a 300 dp wide split-screen window.
        assertEquals(Posture.PORTRAIT, layout(360, 432).posture)
        assertEquals(Posture.PORTRAIT, layout(300, 640).posture)
    }

    @Test fun `a phone on its side keeps the phone-width column`() {
        val l = layout(800, 360)
        assertEquals(Posture.SIDE_COLUMN, l.posture)
        assertEquals(368.dp, l.panelWidth)
        assertEquals(12.dp + 368.dp, l.reserve)
    }

    @Test fun `an unfolded inner display uses a wider column that never passes 440 dp`() {
        val inner = layout(FoldTestSupport.PIXEL_FOLD_INNER_W, FoldTestSupport.PIXEL_FOLD_INNER_H)
        assertEquals(Posture.SIDE_COLUMN, inner.posture)
        assertTrue(inner.panelWidth > 380.dp && inner.panelWidth <= 440.dp, "${inner.panelWidth}")
        assertEquals(440.dp, layout(1280, 800).panelWidth)
        // The same display held upright: still a column, not a 690 dp wide bottom sheet.
        val upright = layout(FoldTestSupport.Z_FOLD_INNER_H, FoldTestSupport.Z_FOLD_INNER_W)
        assertEquals(Posture.SIDE_COLUMN, upright.posture)
        assertEquals(360.dp, upright.panelWidth)
        // A 600 dp tablet-ish window.
        assertEquals(Posture.SIDE_COLUMN, layout(600, 960).posture)
    }

    @Test fun `a flat unfolded device with a crease that does not separate is not split`() {
        val flat = FoldTestSupport.vertical(420.5f, 701, halfOpened = false, separating = false)
        assertEquals(Posture.SIDE_COLUMN, layout(841, 701, flat).posture)
    }

    @Test fun `half open with a vertical crease is a book with the column before the crease`() {
        val hinge = FoldTestSupport.vertical(420.5f, 701)
        val l = layout(841, 701, hinge)
        assertEquals(Posture.BOOK, l.posture)
        assertTrue(l.panelWidth.value <= 420.6f, "${l.panelWidth}")
        assertEquals(420.5f, l.reserve.value, 0.6f)
        assertEquals(420.5f, l.startPaneWidth.value, 0.6f)
        // With a cutout at the start side the column still ends at the crease.
        val cut = layout(841, 701, hinge, inset = 32)
        assertTrue(cut.panelWidth.value + 32 <= 420.6f)
    }

    @Test fun `a book in a right-to-left language puts the column on the right pane`() {
        val hinge = FoldTestSupport.vertical(300f, 701)
        val l = layout(841, 701, hinge, rtl = true)
        assertEquals(Posture.BOOK, l.posture)
        // The start pane is the one from the right edge to the crease.
        assertEquals(841f - hinge.right / DENSITY, l.startPaneWidth.value, 0.6f)
    }

    @Test fun `a hinge that hides part of the screen keeps the overlays after it`() {
        val duo = FoldTestSupport.vertical(540f, 720, widthDp = 34f, halfOpened = false, separating = true, occluding = true)
        val l = layout(1114, 720, duo)
        assertEquals(Posture.BOOK, l.posture)
        assertEquals(574f, l.reserve.value, 0.6f)
        assertTrue(l.panelWidth.value <= 540f)
    }

    @Test fun `half open with a horizontal crease is tabletop with a top pane and a bottom pane`() {
        val hinge = FoldTestSupport.horizontal(350.5f, 841)
        val l = layout(841, 701, hinge)
        assertEquals(Posture.TABLETOP, l.posture)
        assertEquals(350.5f, l.topPaneHeight.value, 0.6f)
        assertEquals(350.5f, l.bottomPaneTop.value, 0.6f)
        assertEquals(350.5f, l.bottomPaneHeight.value, 0.6f)
        assertEquals(720.dp, l.panelWidth)
        assertEquals(0.dp, l.reserve)
    }

    @Test fun `a crease that leaves a pane too small falls back to the plain rules`() {
        assertEquals(Posture.SIDE_COLUMN, layout(841, 701, FoldTestSupport.vertical(200f, 701)).posture)
        assertEquals(Posture.PORTRAIT, layout(360, 800, FoldTestSupport.horizontal(100f, 360)).posture)
    }

    @Test fun `a crease outside the window is ignored`() {
        assertEquals(Posture.PORTRAIT, layout(411, 797, FoldTestSupport.vertical(900f, 797)).posture)
    }

    @Test fun `a split-screen window of 300 dp keeps a usable column and never exceeds the window`() {
        val l = layout(300, 200)
        assertEquals(Posture.SIDE_COLUMN, l.posture)
        assertTrue(l.panelWidth >= 240.dp && l.panelWidth <= 300.dp)
        val tiny = layout(180, 120)
        assertTrue(tiny.panelWidth <= 180.dp)
    }

    @Test fun `route framing leaves the side column, the crease side or the bottom half free`() {
        val side = RoutePadding.computeFor(layout(800, 360), SheetDetent.MEDIUM, 1080, 3f, 72)
        assertTrue(side.left > 0 && side.bottom < 200)
        val book = layout(841, 701, FoldTestSupport.vertical(420.5f, 701))
        val bookPad = RoutePadding.computeFor(book, SheetDetent.FULL, 1840, DENSITY, 80)
        assertTrue(bookPad.left >= (420.5f * DENSITY).toInt(), "${bookPad.left}")
        val table = layout(841, 701, FoldTestSupport.horizontal(350.5f, 841))
        val tablePad = RoutePadding.computeFor(table, SheetDetent.FULL, 1840, DENSITY, 80)
        assertTrue(tablePad.bottom >= (350.5f * DENSITY).toInt(), "${tablePad.bottom}")
        val portrait = RoutePadding.computeFor(layout(411, 797), SheetDetent.MEDIUM, 2092, DENSITY, 80)
        assertEquals(RoutePadding.compute(SheetDetent.MEDIUM, 2092, DENSITY, 80), portrait)
    }

    @Test fun `the camera covered padding mirrors in a right-to-left layout`() {
        val ltr = layout(841, 701, FoldTestSupport.vertical(420.5f, 701)).coveredPadding(DENSITY)
        val rtl = layout(841, 701, FoldTestSupport.vertical(420.5f, 701), rtl = true).coveredPadding(DENSITY)
        assertTrue(ltr.left > 0 && ltr.right == 0)
        assertTrue(rtl.right > 0 && rtl.left == 0)
    }
}
