package com.qtekfun.ultimatemaps.route

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.ElevationProfile
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ElevationSummaryTest {
    @get:Rule
    val rule = createComposeRule()

    private val line = List(1001) { LatLon(40.0 + it * 1e-4, -3.0) }

    /** Up 100 m over the first half, down 50 m over the second. */
    private val profile = assertNotNull(
        ElevationProfile.of(line, List(1001) { if (it <= 500) 600.0 + it * 0.2 else 700.0 - (it - 500) * 0.1 }),
    )

    @Test
    fun withoutAProfileNothingIsDrawn() {
        rule.setContent { MapasTheme { ElevationSummary(null) } }
        assertEquals(0, rule.onAllNodesWithTag("route_elevation").fetchSemanticsNodes().size)
    }

    @Test
    fun showsClimbAndDescentAndTheChartOpensOnTap() {
        rule.setContent { MapasTheme { ElevationSummary(profile) } }
        rule.onNodeWithTag("route_elevation").assertExists()
        rule.onNodeWithText("↑ ${profile.roundedAscent} m\u2002↓ ${profile.roundedDescent} m", useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("route_elevation_chart").fetchSemanticsNodes().size)
        rule.onNodeWithTag("route_elevation").performClick()
        rule.onNodeWithTag("route_elevation_chart").assertExists()
        rule.onNodeWithTag("route_elevation").performClick()
        assertEquals(0, rule.onAllNodesWithTag("route_elevation_chart").fetchSemanticsNodes().size)
    }
}
