package com.qtekfun.ultimatemaps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The traffic button of the map: only with live traffic ready, and one tap flips it (the layer itself is tested apart). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MapTrafficButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(available: Boolean, shown: Boolean, onToggle: () -> Unit = {}) = rule.setContent {
        MapasTheme(darkTheme = false) {
            MapButtons(
                bearingDegrees = 0f, locating = false, locateDescription = "Locate", compassDescription = "North",
                onLocate = {}, onResetNorth = {},
                trafficAvailable = available, trafficShown = shown, trafficDescription = if (shown) "Hide traffic" else "Show traffic", onToggleTraffic = onToggle,
            )
        }
    }

    @Test fun withoutLiveTrafficThereIsNoButton() {
        show(available = false, shown = true)
        rule.onNodeWithTag("btn_locate").assertIsDisplayed()
        rule.onAllNodesWithTagCount("btn_traffic", 0)
    }

    @Test fun withLiveTrafficTheButtonIsThereAndATapAsksForTheToggle() {
        var taps = 0
        show(available = true, shown = true) { taps++ }
        rule.onNodeWithTag("btn_traffic").assertIsDisplayed().performClick()
        assertEquals(1, taps)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) =
        assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
}
