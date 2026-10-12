package com.qtekfun.ultimatemaps.nav

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.qtekfun.ultimatemaps.core.nav.ManeuverInfo
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.bounds
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.rootBounds
import com.qtekfun.ultimatemaps.ui.fold.FoldFeatureInfo
import com.qtekfun.ultimatemaps.ui.fold.FoldTestSupport
import com.qtekfun.ultimatemaps.ui.fold.FoldTestSupport.DENSITY
import com.qtekfun.ultimatemaps.ui.fold.LocalFoldFeature
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The navigation screen on a foldable, with everything on screen at once, in the postures: unfolded flat, book (column on one
 * side of the vertical hinge) and tabletop (banner and speed above the horizontal hinge, panel and buttons below it). Layout
 * assertions only: nothing here was seen on a screen.
 */
@RunWith(RobolectricTestRunner::class)
class NavScreenFoldTest {
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

    private fun show(fold: FoldFeatureInfo?, ui: NavUi) {
        rule.setContent { CompositionLocalProvider(LocalFoldFeature provides fold) { NavScreen(ui, actions, dark = false) } }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `unfolded flat uses a wide column and every part has its own place`() {
        show(FoldTestSupport.vertical(420.5f, 701, halfOpened = false, separating = false), busy(glove = false))
        assertDisjointAndInside(rule, tags)
        val banner = bounds(rule, "nav_banner")
        val root = rootBounds(rule)
        assertTrue(banner.right <= 440 * DENSITY + 1f && banner.right > 0.4f * root.width, "banner $banner in $root")
    }

    private fun book(widthDp: Int, heightDp: Int, hingeX: Float, glove: Boolean) {
        val hinge = FoldTestSupport.vertical(hingeX, heightDp)
        show(hinge, busy(glove))
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertNoneCrosses(rule, tags, hinge)
        // The column (banner, panel) is before the crease; the speed and the buttons are on the map side.
        for (t in listOf("nav_banner", "nav_bottom", "nav_sim_bar")) FoldTestSupport.assertBeforeVertical(rule, t, hinge)
        for (t in listOf("nav_speed", "nav_limit", "nav_recenter", "nav_mute", "nav_camera_mute", "nav_view_toggle", "nav_overview", "nav_glove")) {
            FoldTestSupport.assertAfterVertical(rule, t, hinge)
        }
        assertTrue(bounds(rule, "nav_bottom").bottom <= heightDp * DENSITY + 1f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold book posture`() = book(841, 701, 420.5f, glove = false)

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold book posture in glove mode`() = book(841, 701, 420.5f, glove = true)

    @Test
    @Config(sdk = [34], qualifiers = "w829dp-h690dp-420dpi")
    fun `galaxy z fold book posture`() = book(829, 690, 414.5f, glove = false)

    private fun tabletop(widthDp: Int, heightDp: Int, hingeY: Float, glove: Boolean) {
        val hinge = FoldTestSupport.horizontal(hingeY, widthDp)
        show(hinge, busy(glove))
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertNoneCrosses(rule, tags, hinge)
        // The maneuver and the speed stay readable on the top half; the arrival panel and the buttons are on the bottom half.
        for (t in listOf("nav_banner", "nav_speed", "nav_limit")) FoldTestSupport.assertAboveHorizontal(rule, t, hinge)
        for (t in listOf("nav_bottom", "nav_sim_bar", "nav_stop", "nav_recenter", "nav_mute", "nav_camera_mute", "nav_view_toggle", "nav_overview", "nav_glove")) {
            FoldTestSupport.assertBelowHorizontal(rule, t, hinge)
        }
        assertTrue(bounds(rule, "nav_bottom").bottom <= rootBounds(rule).bottom + 1f)
        assertTrue(bounds(rule, "nav_banner").width <= 720 * DENSITY + 1f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold tabletop posture`() = tabletop(841, 701, 350.5f, glove = false)

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `pixel fold tabletop posture in glove mode`() = tabletop(841, 701, 350.5f, glove = true)

    @Test
    @Config(sdk = [34], qualifiers = "w829dp-h690dp-420dpi")
    fun `galaxy z fold tabletop posture`() = tabletop(829, 690, 345f, glove = false)

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h797dp-420dpi")
    fun `the cover display keeps the portrait layout`() {
        show(null, busy(glove = false))
        // Full-width banner at the top, the panel at the bottom: the portrait arrangement.
        assertTrue(bounds(rule, "nav_banner").width >= 0.9f * rootBounds(rule).width, "banner ${bounds(rule, "nav_banner")} in ${rootBounds(rule)}")
        assertDisjointAndInside(rule, listOf("nav_banner", "nav_bottom", "nav_speed", "nav_recenter", "nav_mute"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `the arrival summary and the resume offer stay in the column pane of a book`() {
        val hinge = FoldTestSupport.vertical(420.5f, 701)
        val summary = NavSummary(distanceMeters = 12_400.0, durationMillis = 19 * 60_000L, stopsReached = 1, simulated = true)
        show(hinge, NavUi(phase = NavPhase.ARRIVED, nav = navState(next = null), summary = summary))
        FoldTestSupport.assertBeforeVertical(rule, "nav_summary", hinge)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `the resume offer stays in the column pane of a book`() {
        val hinge = FoldTestSupport.vertical(420.5f, 701)
        show(hinge, NavUi(resumable = true))
        FoldTestSupport.assertBeforeVertical(rule, "nav_resume_card", hinge)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `the arrival summary and the resume offer stay in the bottom and top pane of a tabletop`() {
        val hinge = FoldTestSupport.horizontal(350.5f, 841)
        val summary = NavSummary(distanceMeters = 12_400.0, durationMillis = 19 * 60_000L, stopsReached = 1, simulated = true)
        show(hinge, NavUi(phase = NavPhase.ARRIVED, nav = navState(next = null), summary = summary))
        FoldTestSupport.assertBelowHorizontal(rule, "nav_summary", hinge)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `folding and unfolding while navigating keeps every control and moves it to its pane`() {
        val fold = androidx.compose.runtime.mutableStateOf<FoldFeatureInfo?>(null)
        val ui = busy(glove = false)
        rule.setContent { CompositionLocalProvider(LocalFoldFeature provides fold.value) { NavScreen(ui, actions, dark = false) } }
        rule.waitForIdle()
        assertDisjointAndInside(rule, tags)
        val tabletop = FoldTestSupport.horizontal(350.5f, 841)
        rule.runOnUiThread { fold.value = tabletop }
        rule.waitForIdle()
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertBelowHorizontal(rule, "nav_stop", tabletop)
        val book = FoldTestSupport.vertical(420.5f, 701)
        rule.runOnUiThread { fold.value = book }
        rule.waitForIdle()
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertBeforeVertical(rule, "nav_stop", book)
        rule.runOnUiThread { fold.value = null }
        rule.waitForIdle()
        assertDisjointAndInside(rule, tags)
    }
}
