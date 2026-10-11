package com.qtekfun.ultimatemaps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.bounds
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rules (pure) and the map screen drawn at 800 x 360 dp: the sheet is a column and nothing piles up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
class LandscapeLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @Test fun `landscape is wider than tall and the panel is a phone-width column`() {
        assertTrue(LandscapeLayout.isLandscape(800.dp, 360.dp))
        assertTrue(!LandscapeLayout.isLandscape(411.dp, 891.dp))
        assertEquals(380.dp, LandscapeLayout.panelWidth(1000.dp))
        assertEquals(800.dp * LandscapeLayout.MAX_PANEL_SHARE, LandscapeLayout.panelWidth(800.dp))
        assertEquals(1104, LandscapeLayout.panelWidthPx(2400, 3f))
        assertEquals(1140, LandscapeLayout.panelWidthPx(3000, 3f))
        assertTrue(LandscapeLayout.isLandscapePx(2400, 1080))
        assertTrue(!LandscapeLayout.isLandscapePx(1080, 2400))
    }

    private fun show(state: MapScreenState) {
        rule.setContent { MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} } }
    }

    @Test fun `the sheet is a column at the start side in every detent and the controls sit beside it`() {
        val state = MapScreenState().apply { cameraKnown = true; tilt = 40f }
        show(state)
        val density = 3f
        for (d in SheetDetent.entries) {
            state.detent = d
            rule.waitForIdle()
            val sheet = bounds(rule, "sheet")
            assertTrue(sheet.left <= 1f, "the column starts at the left edge: $sheet")
            assertTrue(sheet.width <= 380 * density + 1f, "at most 380 dp wide: ${sheet.width / density} dp")
            assertTrue(sheet.height <= 360 * density + 1f)
            val controls = listOf("attribution", "btn_locate", "btn_compass", "btn_settings")
            for (tag in controls) {
                rule.onNodeWithTag(tag).assertIsDisplayed()
                assertTrue(!LandscapeTestSupport.overlaps(sheet, bounds(rule, tag)), "$tag overlaps the sheet in $d")
            }
            assertDisjointAndInside(rule, controls)
        }
    }

    @Test fun `the full sheet reaches the top and the medium one is a part of the height`() {
        val state = MapScreenState()
        show(state)
        state.detent = SheetDetent.FULL
        rule.waitForIdle()
        assertTrue(bounds(rule, "sheet").top < 40f * 3f, "full: ${bounds(rule, "sheet")}")
        state.detent = SheetDetent.MEDIUM
        rule.waitForIdle()
        val h = bounds(rule, "sheet").height
        assertTrue(h > 150f * 3f && h < 300f * 3f, "medium height $h")
    }
}
