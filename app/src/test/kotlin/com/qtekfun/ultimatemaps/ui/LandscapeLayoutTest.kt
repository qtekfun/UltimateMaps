package com.qtekfun.ultimatemaps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    val rule = createAndroidComposeRule<ComponentActivity>()

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

    /** A stand-in for the search panel: tabs, a field, chips, then a long list (as the real one is laid out). */
    private val fakePanel: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("fake_tabs"))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(90.dp).testTag("fake_header"))
            BasicTextField(value = "madrid", onValueChange = {}, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("fake_field"))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(20) { Box(Modifier.fillMaxWidth().height(56.dp)) }
            }
        }
    }

    private var imeSeen = 0

    private fun ime(heightDp: Int) {
        // The listener Compose installs for the window insets is on its own root view, not on the decor view.
        fun find(v: android.view.View): android.view.View? {
            if (v.javaClass.name.endsWith("AndroidComposeView")) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val view = find(rule.activity.window.decorView)!!
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightDp * 3))
            .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0)
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        rule.waitForIdle()
    }

    @Test fun `with the keyboard open the field stays above it and at MEDIUM nothing is cut off`() {
        val state = MapScreenState().apply { detent = SheetDetent.FULL }
        rule.setContent {
            imeSeen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
            MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}, sheetPanel = fakePanel) {} }
        }
        rule.waitForIdle()
        // MEDIUM: the content is taller than the sheet, so it scrolls instead of being cut off.
        state.detent = SheetDetent.MEDIUM
        rule.waitForIdle()
        rule.onNodeWithTag("fake_field").performScrollTo()
        rule.waitForIdle()
        val medium = bounds(rule, "sheet")
        assertTrue(bounds(rule, "fake_field").bottom <= medium.bottom + 1f, "the field is reachable at MEDIUM")
        state.detent = SheetDetent.FULL
        rule.waitForIdle()
        ime(190)
        assertTrue(imeSeen > 0, "the simulated keyboard reached the compose window insets: $imeSeen")
        val keyboardTop = (360 - 190) * 3f
        rule.onNodeWithTag("fake_field").performScrollTo()
        rule.waitForIdle()
        val field = bounds(rule, "fake_field")
        assertTrue(field.bottom <= keyboardTop + 1f && field.top >= 0f, "field $field must be above the keyboard at $keyboardTop")
    }
}
