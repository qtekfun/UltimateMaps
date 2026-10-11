package com.qtekfun.ultimatemaps.nav

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.qtekfun.ultimatemaps.cameras.LocalAlertBanner
import com.qtekfun.ultimatemaps.core.cameras.AlertBannerState
import com.qtekfun.ultimatemaps.core.cameras.AlertCategory
import com.qtekfun.ultimatemaps.core.nav.ManeuverInfo
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.bounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The speed-limit sign, the over-limit warning and the camera alert strip on the landscape navigation screen (800 x 360 dp),
 * in every state: none may overlap the banner, the ETA panel, the speed readout or the buttons, and all stay in the window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
class NavSpeedAlertsLandscapeTest {
    @get:Rule
    val rule = createComposeRule()

    private val alert = MutableStateFlow<AlertBannerState?>(null)

    private val lanes = listOf(
        Lane(setOf(LaneDirection.LEFT, LaneDirection.THROUGH), recommended = false),
        Lane(setOf(LaneDirection.RIGHT), recommended = true),
    )
    private val then = ManeuverInfo(Maneuver(9, TurnType.SLIGHT_LEFT, "Gran Vía"), 800.0)

    private fun ui(glove: Boolean = false, over: Boolean = false, limit: Int? = 50, busy: Boolean = false) = NavUi(
        phase = NavPhase.ON_ROUTE,
        nav = navState(
            speedLimitKmh = limit, over = over, speedMps = if (over) 20.0 else 10.0,
            following = if (busy) then else null, lanes = if (busy) lanes else emptyList(),
        ),
        glove = glove, etaMillis = 1_700_000_000_000L, following = !busy, simulated = busy, cameraAlertsOn = busy,
    )

    private fun show(ui: NavUi) {
        rule.setContent { CompositionLocalProvider(LocalAlertBanner provides alert) { NavScreen(ui, NavActions(), dark = false) } }
    }

    private val base = listOf("nav_banner", "nav_bottom", "nav_mute", "nav_view_toggle", "nav_glove", "nav_overview")

    @Test fun `the limit sign alone sits beside the column`() {
        show(ui())
        assertDisjointAndInside(rule, base + listOf("nav_limit", "nav_speed"))
    }

    @Test fun `the over-limit warning stays inside the speed readout and clear of everything`() {
        show(ui(over = true))
        assertDisjointAndInside(rule, base + listOf("nav_limit", "nav_speed_over"))
        // The warning is part of the speed readout: inside it, not beside it.
        assertDisjointAndInside(rule, listOf("nav_over_limit"))
        val readout = bounds(rule, "nav_speed_over")
        val warning = bounds(rule, "nav_over_limit")
        assertTrue(warning.left >= readout.left - 1f && warning.right <= readout.right + 1f && warning.bottom <= readout.bottom + 1f)
    }

    @Test fun `limit, camera alert and every control at once`() {
        alert.value = AlertBannerState(AlertCategory.FIXED_CAMERA, 500, 50)
        show(ui(over = true, busy = true))
        assertDisjointAndInside(
            rule,
            base + listOf("camera_alert", "nav_limit", "nav_speed_over", "nav_sim_bar", "nav_recenter", "nav_camera_mute"),
        )
    }

    @Test fun `glove mode with the over-limit warning and a camera alert`() {
        alert.value = AlertBannerState(AlertCategory.FIXED_CAMERA, 500, 50)
        show(ui(glove = true, over = true, busy = true))
        assertDisjointAndInside(
            rule,
            base + listOf("camera_alert", "nav_limit", "nav_speed_over", "nav_sim_bar", "nav_recenter", "nav_camera_mute"),
        )
    }
}
