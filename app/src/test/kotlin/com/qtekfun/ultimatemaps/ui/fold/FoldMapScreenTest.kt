package com.qtekfun.ultimatemaps.ui.fold

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.bounds
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.rootBounds
import com.qtekfun.ultimatemaps.ui.MapScreen
import com.qtekfun.ultimatemaps.ui.MapScreenState
import com.qtekfun.ultimatemaps.ui.fold.FoldTestSupport.DENSITY
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The map screen (sheet, buttons, attribution, scale bar) drawn at the sizes of real foldables with fake folds. Layout
 * assertions only: nothing here was seen on a screen. Each test sets its own window size (420 dpi, density 2.625).
 */
@RunWith(RobolectricTestRunner::class)
class FoldMapScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val controls = listOf("attribution", "map_scale", "btn_locate", "btn_compass", "btn_settings")

    private fun show(state: MapScreenState, fold: FoldFeatureInfo?) {
        rule.setContent {
            CompositionLocalProvider(LocalFoldFeature provides fold) {
                MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} }
            }
        }
    }

    private fun state() = MapScreenState().apply { cameraKnown = true; tilt = 40f }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold unfolded flat is a column of at most 440 dp with the controls beside it`() {
        val s = state()
        show(s, FoldTestSupport.vertical(420.5f, 701, halfOpened = false, separating = false))
        for (d in SheetDetent.entries) {
            s.detent = d
            rule.waitForIdle()
            val sheet = bounds(rule, "sheet")
            assertTrue(sheet.left <= 1f)
            assertTrue(sheet.width in 380 * DENSITY..440 * DENSITY + 1f, "width ${sheet.width / DENSITY} dp")
            assertDisjointAndInside(rule, controls)
            for (t in controls) assertTrue(!com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.overlaps(sheet, bounds(rule, t)), "$t overlaps the sheet in $d")
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold in book posture keeps the column before the hinge and the map controls after it`() {
        val hinge = FoldTestSupport.vertical(420.5f, 701)
        val s = state()
        show(s, hinge)
        for (d in SheetDetent.entries) {
            s.detent = d
            rule.waitForIdle()
            FoldTestSupport.assertBeforeVertical(rule, "sheet", hinge)
            FoldTestSupport.assertNoneCrosses(rule, listOf("sheet") + controls, hinge)
            // Overlays start in the map pane, not in the strip between the column and the crease.
            for (t in listOf("attribution", "map_scale", "btn_locate", "btn_compass", "btn_settings")) FoldTestSupport.assertAfterVertical(rule, t, hinge)
            assertDisjointAndInside(rule, controls)
        }
        // The column is a pane: it fills up to the crease on the book (at most 480 dp).
        assertTrue(bounds(rule, "sheet").width > 380 * DENSITY)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w829dp-h690dp-420dpi")
    fun `galaxy z fold in book posture`() {
        val hinge = FoldTestSupport.vertical(414.5f, 690)
        val s = state()
        show(s, hinge)
        rule.waitForIdle()
        FoldTestSupport.assertBeforeVertical(rule, "sheet", hinge)
        FoldTestSupport.assertNoneCrosses(rule, listOf("sheet") + controls, hinge)
        assertDisjointAndInside(rule, controls)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1114dp-h720dp-420dpi")
    fun `a dual-screen hinge that hides 34 dp is never drawn over`() {
        val hinge = FoldTestSupport.vertical(540f, 720, widthDp = 34f, halfOpened = false, separating = true, occluding = true)
        val s = state()
        show(s, hinge)
        rule.waitForIdle()
        FoldTestSupport.assertBeforeVertical(rule, "sheet", hinge)
        FoldTestSupport.assertNoneCrosses(rule, listOf("sheet") + controls, hinge)
        for (t in controls) FoldTestSupport.assertAfterVertical(rule, t, hinge)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold in tabletop keeps the map on the top half and the sheet on the bottom half`() {
        val hinge = FoldTestSupport.horizontal(350.5f, 841)
        val s = state()
        show(s, hinge)
        for (d in SheetDetent.entries) {
            s.detent = d
            rule.waitForIdle()
            FoldTestSupport.assertBelowHorizontal(rule, "sheet", hinge)
            FoldTestSupport.assertNoneCrosses(rule, listOf("sheet") + controls, hinge)
            for (t in controls) FoldTestSupport.assertAboveHorizontal(rule, t, hinge)
            assertDisjointAndInside(rule, controls)
            val sheet = bounds(rule, "sheet")
            assertTrue(sheet.width <= 720 * DENSITY + 1f, "readable width: ${sheet.width / DENSITY} dp")
            assertTrue(sheet.bottom <= rootBounds(rule).bottom + 1f)
        }
        s.detent = SheetDetent.FULL
        rule.waitForIdle()
        // FULL is the whole bottom pane.
        assertEquals(hinge.bottom.toFloat(), bounds(rule, "sheet").top, 2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h797dp-420dpi")
    fun `the pixel fold cover display is the ordinary portrait layout`() {
        val s = state()
        show(s, null)
        s.detent = SheetDetent.MEDIUM
        rule.waitForIdle()
        val sheet = bounds(rule, "sheet")
        assertEquals(rootBounds(rule).width, sheet.width, 1f)
        assertTrue(sheet.bottom >= rootBounds(rule).bottom - 1f)
        assertDisjointAndInside(rule, controls)
        rule.onNodeWithTag("btn_settings").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w344dp-h882dp-420dpi")
    fun `a narrow 344 dp cover display keeps the portrait layout and the controls fit`() {
        val s = state()
        show(s, null)
        rule.waitForIdle()
        assertEquals(rootBounds(rule).width, bounds(rule, "sheet").width, 1f)
        assertDisjointAndInside(rule, controls)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w600dp-h960dp-420dpi")
    fun `a 600 dp tablet-ish window gets the column instead of a stretched bottom sheet`() {
        val s = state()
        show(s, null)
        rule.waitForIdle()
        val sheet = bounds(rule, "sheet")
        assertTrue(sheet.width <= 440 * DENSITY + 1f && sheet.left <= 1f, "$sheet")
        assertDisjointAndInside(rule, controls)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w300dp-h640dp-420dpi")
    fun `a 300 dp wide split-screen window does not crash and nothing leaves it`() {
        val s = state()
        show(s, null)
        rule.waitForIdle()
        assertDisjointAndInside(rule, listOf("sheet"))
        assertTrue(bounds(rule, "sheet").width <= 300 * DENSITY + 1f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w300dp-h200dp-420dpi")
    fun `a 300 x 200 dp window does not crash and the column stays inside it`() {
        val s = state()
        show(s, null)
        rule.waitForIdle()
        assertDisjointAndInside(rule, listOf("sheet"))
        assertTrue(bounds(rule, "sheet").width <= 300 * DENSITY + 1f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `folding while the sheet is open keeps the detent and the posture follows the fold`() {
        val s = state().apply { detent = SheetDetent.FULL }
        val fold = androidx.compose.runtime.mutableStateOf<FoldFeatureInfo?>(null)
        rule.setContent {
            CompositionLocalProvider(LocalFoldFeature provides fold.value) {
                MapasTheme(darkTheme = false) { MapScreen(s, onLocate = {}, onResetNorth = {}) {} }
            }
        }
        rule.waitForIdle()
        assertEquals(Posture.SIDE_COLUMN, s.windowLayout.posture)
        val hinge = FoldTestSupport.horizontal(350.5f, 841)
        rule.runOnUiThread { fold.value = hinge }
        rule.waitForIdle()
        assertEquals(Posture.TABLETOP, s.windowLayout.posture)
        assertEquals(SheetDetent.FULL, s.detent)
        FoldTestSupport.assertBelowHorizontal(rule, "sheet", hinge)
        rule.runOnUiThread { fold.value = null }
        rule.waitForIdle()
        assertEquals(Posture.SIDE_COLUMN, s.windowLayout.posture)
        assertEquals(SheetDetent.FULL, s.detent)
    }
}
