package com.qtekfun.ultimatemaps.nav

import androidx.compose.ui.test.junit4.createComposeRule
import com.qtekfun.ultimatemaps.core.nav.ManeuverInfo
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.bounds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The navigation screen drawn at 800 x 360 dp (a phone on its side) with everything that can be on screen at once:
 * the banner with the second maneuver and lanes, the speed and its limit, every button, the simulation bar. Nothing
 * may overlap and the banner is a column, not the full width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
class NavScreenLandscapeTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = NavActions()

    private val busyNav = navState(
        next = ManeuverInfo(Maneuver(5, TurnType.RIGHT, "Calle de Alcalá"), 300.0),
        following = ManeuverInfo(Maneuver(9, TurnType.SLIGHT_LEFT, "Gran Vía"), 800.0),
        lanes = listOf(
            Lane(setOf(LaneDirection.LEFT, LaneDirection.THROUGH), recommended = false),
            Lane(setOf(LaneDirection.RIGHT), recommended = true),
        ),
        speedLimitKmh = 50,
    )

    private fun busy(glove: Boolean) = NavUi(
        phase = NavPhase.ON_ROUTE, nav = busyNav, simulated = true, following = false, glove = glove, etaMillis = 1_700_000_000_000L,
        cameraAlertsOn = true,
    )

    private val tags = listOf(
        "nav_banner", "nav_sim_bar", "nav_bottom", "nav_speed", "nav_limit", "nav_recenter", "nav_mute", "nav_camera_mute",
        "nav_view_toggle", "nav_overview", "nav_glove",
    )

    @Test fun `every part of the driving screen has its own place at 800 x 360 dp`() {
        rule.setContent { NavScreen(busy(glove = false), actions, dark = false) }
        assertDisjointAndInside(rule, tags)
        val banner = bounds(rule, "nav_banner")
        assertTrue(banner.right <= 380 * 3f + 1f, "the banner is a column of at most 380 dp: $banner")
    }

    @Test fun `glove mode with its bigger buttons still fits`() {
        rule.setContent { NavScreen(busy(glove = true), actions, dark = false) }
        assertDisjointAndInside(rule, tags)
    }

    @Test fun `the stop button and the arrival time stay reachable inside the column`() {
        rule.setContent { NavScreen(busy(glove = true), actions, dark = false) }
        assertDisjointAndInside(rule, listOf("nav_banner", "nav_eta", "nav_stop", "nav_attribution"))
        val bottom = bounds(rule, "nav_bottom")
        assertTrue(bottom.bottom <= 360 * 3f + 1f)
    }

    @Test fun `the arrival summary keeps a phone width and is not stretched across the screen`() {
        val summary = NavSummary(distanceMeters = 12_400.0, durationMillis = 19 * 60_000L, stopsReached = 1, simulated = true)
        rule.setContent {
            NavScreen(NavUi(phase = NavPhase.ARRIVED, nav = navState(next = null), summary = summary), actions, dark = false)
        }
        assertDisjointAndInside(rule, listOf("nav_summary"))
        assertTrue(bounds(rule, "nav_summary").width <= 480 * 3f + 1f)
    }

    @Test fun `the resume offer is not stretched either`() {
        rule.setContent { NavScreen(NavUi(resumable = true), actions, dark = false) }
        assertDisjointAndInside(rule, listOf("nav_resume_card"))
        assertTrue(bounds(rule, "nav_resume_card").width <= 480 * 3f + 1f)
    }
}
