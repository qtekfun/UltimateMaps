package com.qtekfun.mapas.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.core.nav.ManeuverInfo
import com.qtekfun.mapas.core.nav.NavProblem
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.LaneDirection
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.TurnType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The navigation screen drawn from hand-made snapshots: texts, icons, lanes, speed, the six phases, glove mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var ui by mutableStateOf(NavUi())
    private var dark by mutableStateOf(false)
    private val calls = mutableListOf<String>()

    private val actions = NavActions(
        onStop = { calls += "stop" }, onRecenter = { calls += "recenter" }, onGlove = { calls += "glove:$it" },
        onView3d = { calls += "view3d:$it" }, onVoice = { calls += "voice:$it" }, onOverview = { calls += "overview" },
        onFaster = { calls += "faster" }, onSlower = { calls += "slower" }, onExit = { calls += "exit" },
        onResume = { calls += "resume" }, onDiscard = { calls += "discard" },
    )

    private fun show(initial: NavUi) {
        ui = initial
        rule.setContent { NavScreen(ui, actions, dark) }
    }

    private fun driving(
        phase: NavPhase = NavPhase.ON_ROUTE,
        nav: com.qtekfun.mapas.core.nav.NavState = navState(),
        simulated: Boolean = false,
        following: Boolean = true,
        glove: Boolean = false,
        problem: NavProblem? = null,
    ) = NavUi(phase = phase, nav = nav, simulated = simulated, following = following, glove = glove, problem = problem, etaMillis = 1_700_000_000_000L)

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test fun `the banner shows the distance, the turn with its street and the arrival and remaining trip`() {
        show(driving())
        rule.onNodeWithTag("nav_banner").assertIsDisplayed()
        rule.onNodeWithTag("nav_turn_icon").assertIsDisplayed()
        assertEquals("300 m", text("nav_distance"))
        assertEquals("Turn right onto Calle de Alcalá", text("nav_instruction"))
        assertEquals("12.4 km · 18 min", text("nav_remaining"))
        assertTrue(text("nav_eta").startsWith("Arrive "), text("nav_eta"))
        rule.onNodeWithTag("nav_then").assertDoesNotExist()
        rule.onNodeWithTag("nav_attribution").assertIsDisplayed() // ODbL stays visible while navigating
    }

    @Test @Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
    fun `in Spanish the instruction reads like a person says it`() {
        show(driving(nav = navState(next = ManeuverInfo(Maneuver(5, TurnType.LEFT, "Calle de Alcalá"), 450.0))))
        assertEquals("Gira a la izquierda en Calle de Alcalá", text("nav_instruction"))
        assertEquals("450 m", text("nav_distance"))
        assertTrue(text("nav_eta").startsWith("Llegada "), text("nav_eta"))
    }

    @Test fun `the second maneuver is shown small under the first`() {
        val then = ManeuverInfo(Maneuver(9, TurnType.SLIGHT_LEFT, "Gran Vía"), 800.0)
        show(driving(nav = navState(following = then)))
        rule.onNodeWithTag("nav_then").assertIsDisplayed()
        assertEquals("Bear left onto Gran Vía", text("nav_then_text"))
    }

    @Test fun `every kind of turn draws an icon and has an instruction`() {
        show(driving())
        for (type in TurnType.entries) {
            ui = driving(nav = navState(next = ManeuverInfo(Maneuver(5, type, "Calle Mayor"), 120.0)))
            rule.waitForIdle()
            rule.onNodeWithTag("nav_turn_icon").assertIsDisplayed()
            assertTrue(text("nav_instruction").isNotBlank(), type.name)
        }
    }

    @Test fun `a roundabout says which exit to take`() {
        show(driving(nav = navState(next = ManeuverInfo(Maneuver(5, TurnType.ROUNDABOUT_ENTER, roundaboutExit = 3), 80.0))))
        assertEquals("At the roundabout, take exit 3", text("nav_instruction"))
    }

    @Test fun `with no guidance it still says to follow the route`() {
        show(driving(nav = navState(next = null)))
        assertEquals("Follow the route", text("nav_instruction"))
    }

    @Test fun `lanes are drawn with the recommended one marked, and absent when unknown`() {
        val lanes = listOf(
            Lane(setOf(LaneDirection.LEFT, LaneDirection.THROUGH), recommended = false),
            Lane(setOf(LaneDirection.THROUGH), recommended = false),
            Lane(setOf(LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT), recommended = true),
        )
        show(driving(nav = navState(lanes = lanes)))
        rule.onNodeWithTag("nav_lanes").assertIsDisplayed()
        rule.onNodeWithTag("nav_lane_0").assertIsDisplayed()
        rule.onNodeWithTag("nav_lane_1").assertIsDisplayed()
        rule.onNodeWithTag("nav_lane_2_recommended").assertIsDisplayed()
        ui = driving(nav = navState(lanes = emptyList()))
        rule.waitForIdle()
        rule.onNodeWithTag("nav_lanes").assertDoesNotExist()
    }

    @Test fun `the speed limit sign and the current speed appear, and exceeding the limit is warned in words and colour`() {
        show(driving(nav = navState(speedLimitKmh = 50, speedMps = 10.0)))
        rule.onNodeWithTag("nav_limit").assertIsDisplayed()
        rule.onNodeWithTag("nav_speed").assertIsDisplayed() // 36 km/h under a limit of 50
        rule.onNodeWithTag("nav_over_limit").assertDoesNotExist()

        ui = driving(nav = navState(speedLimitKmh = 50, speedMps = 18.0, over = true))
        rule.waitForIdle()
        rule.onNodeWithTag("nav_speed_over").assertIsDisplayed()
        rule.onNodeWithTag("nav_over_limit").assertIsDisplayed()

        ui = driving(nav = navState(speedLimitKmh = null, speedMps = 18.0)) // bike or on foot: no limits at all
        rule.waitForIdle()
        rule.onNodeWithTag("nav_limit").assertDoesNotExist()
        rule.onNodeWithTag("nav_speed").assertIsDisplayed()
    }

    @Test fun `each situation has its own clear message`() {
        show(driving())
        rule.onNodeWithTag("nav_status").assertDoesNotExist() // on route: nothing to add

        fun check(phase: NavPhase, status: NavStatus, expected: String, problem: NavProblem? = null) {
            ui = driving(phase = phase, nav = navState(status = status), problem = problem)
            rule.waitForIdle()
            assertEquals(expected, text("nav_status"), phase.name)
        }
        check(NavPhase.OFF_ROUTE, NavStatus.OFF_ROUTE, "You left the route")
        check(NavPhase.REROUTING, NavStatus.REROUTING, "Recalculating the route…")
        check(NavPhase.NO_SIGNAL, NavStatus.NO_SIGNAL, "GPS signal lost. Estimated position")
        check(NavPhase.STOP_REACHED, NavStatus.ON_ROUTE, "Stop reached. Continue to the destination")
        check(NavPhase.ON_ROUTE, NavStatus.ON_ROUTE, "Location permission was removed. Allow it to keep navigating.", NavProblem.LOCATION_PERMISSION)
        check(NavPhase.ON_ROUTE, NavStatus.ON_ROUTE, "Location is off. Turn it on to keep navigating.", NavProblem.LOCATION_DISABLED)
    }

    @Test fun `the arrival shows a summary and Done leaves`() {
        val summary = NavSummary(distanceMeters = 12_400.0, durationMillis = 19 * 60_000L, stopsReached = 1, simulated = false)
        show(NavUi(phase = NavPhase.ARRIVED, nav = navState(status = NavStatus.ARRIVED, next = null), summary = summary))
        rule.onNodeWithTag("nav_summary").assertIsDisplayed()
        assertEquals("You have arrived", text("nav_summary_title"))
        assertEquals("12.4 km in 19 min", text("nav_summary_body"))
        rule.onNodeWithTag("nav_banner").assertDoesNotExist()
        rule.onNodeWithTag("nav_summary_simulated").assertDoesNotExist()
        rule.onNodeWithTag("nav_exit").performClick()
        assertEquals(listOf("exit"), calls)

        ui = NavUi(phase = NavPhase.ARRIVED, nav = navState(status = NavStatus.ARRIVED, next = null), summary = summary.copy(simulated = true))
        rule.waitForIdle()
        rule.onNodeWithTag("nav_summary_simulated").assertIsDisplayed()
    }

    @Test fun `Stop and Recenter work, and Recenter only shows once the user moved the map`() {
        show(driving(following = true))
        rule.onNodeWithTag("nav_recenter").assertDoesNotExist()
        rule.onNodeWithTag("nav_stop").performClick()
        ui = driving(following = false)
        rule.waitForIdle()
        rule.onNodeWithTag("nav_recenter").performClick()
        assertEquals(listOf("stop", "recenter"), calls)
    }

    @Test fun `the simulation controls exist only while simulating`() {
        show(driving(simulated = false))
        rule.onNodeWithTag("nav_sim_bar").assertDoesNotExist()
        ui = driving(simulated = true).copy(simulationSpeedKmh = 90)
        rule.waitForIdle()
        assertEquals("90 km/h", text("nav_sim_speed"))
        rule.onNodeWithTag("nav_sim_faster").performClick()
        rule.onNodeWithTag("nav_sim_slower").performClick()
        assertEquals(listOf("faster", "slower"), calls)
    }

    @Test fun `an interrupted trip offers to resume or discard`() {
        show(NavUi(resumable = true))
        rule.onNodeWithTag("nav_resume_card").assertIsDisplayed()
        rule.onNodeWithTag("nav_resume").performClick()
        rule.onNodeWithTag("nav_discard").performClick()
        assertEquals(listOf("resume", "discard"), calls)
        ui = NavUi(resumable = false)
        rule.waitForIdle()
        rule.onNodeWithTag("nav_resume_card").assertDoesNotExist()
    }

    @Test fun `glove mode makes every button at least 56 dp and normal mode at least 48 dp`() {
        val tags = listOf("nav_stop", "nav_glove", "nav_recenter")
        show(driving(following = false, glove = false))
        for (t in tags) {
            rule.onNodeWithTag(t).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        ui = driving(following = false, glove = true, simulated = true)
        rule.waitForIdle()
        for (t in tags + listOf("nav_sim_faster", "nav_sim_slower")) {
            rule.onNodeWithTag(t).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
        }
        rule.onNodeWithTag("nav_glove").performClick()
        assertEquals(listOf("glove:false"), calls) // the toggle asks for the opposite of the current mode
    }

    @Test fun `the 2D 3D toggle shows the current mode, describes it, and asks for the opposite`() {
        show(driving().copy(view3d = true))
        assertEquals("3D", text("nav_view_toggle"))
        rule.onNodeWithTag("nav_view_toggle").assertContentDescriptionEquals("3D view on. Switch to the flat 2D view")
        rule.onNodeWithTag("nav_view_toggle").performClick()
        ui = driving().copy(view3d = false)
        rule.waitForIdle()
        assertEquals("2D", text("nav_view_toggle"))
        rule.onNodeWithTag("nav_view_toggle").assertContentDescriptionEquals("Flat 2D view on. Switch to the 3D view")
        rule.onNodeWithTag("nav_view_toggle").performClick()
        assertEquals(listOf("view3d:false", "view3d:true"), calls)
    }

    @Test fun `the mute button shows the state, describes it, and asks for the opposite`() {
        show(driving().copy(voiceOn = true))
        assertEquals("Mute", text("nav_mute"))
        rule.onNodeWithTag("nav_mute").assertContentDescriptionEquals("Voice on. Tap to mute the spoken guidance")
        rule.onNodeWithTag("nav_mute").performClick()
        ui = driving().copy(voiceOn = false)
        rule.waitForIdle()
        assertEquals("Unmute", text("nav_mute"))
        rule.onNodeWithTag("nav_mute").assertContentDescriptionEquals("Voice muted. Tap to turn the spoken guidance back on")
        rule.onNodeWithTag("nav_mute").performClick()
        assertEquals(listOf("voice:false", "voice:true"), calls)
    }

    @Test fun `the 2D 3D toggle and the route overview button are at least 56 dp in glove mode`() {
        show(driving(glove = true))
        for (t in listOf("nav_view_toggle", "nav_overview")) rule.onNodeWithTag(t).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
        ui = driving(glove = false)
        rule.waitForIdle()
        for (t in listOf("nav_view_toggle", "nav_overview")) rule.onNodeWithTag(t).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test fun `the overview button asks for the overview and is hidden while it is shown`() {
        show(driving())
        rule.onNodeWithTag("nav_overview").assertContentDescriptionEquals("Show the whole remaining route for a moment")
        rule.onNodeWithTag("nav_overview").performClick()
        assertEquals(listOf("overview"), calls)
        ui = driving(following = false).copy(overview = true)
        rule.waitForIdle()
        rule.onNodeWithTag("nav_overview").assertDoesNotExist()
        rule.onNodeWithTag("nav_recenter").assertIsDisplayed()
    }

    @Test @Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
    fun `the toggle is described in Spanish`() {
        show(driving().copy(view3d = true))
        rule.onNodeWithTag("nav_view_toggle").assertContentDescriptionEquals("Vista 3D activada. Cambiar a la vista plana 2D")
    }

    @Test fun `night and day themes both draw the screen`() {
        show(driving(nav = navState(speedLimitKmh = 50)))
        dark = true
        rule.waitForIdle()
        rule.onNodeWithTag("nav_banner").assertIsDisplayed()
        rule.onNodeWithTag("nav_limit").assertIsDisplayed()
    }

    @Test fun `the screen draws nothing while there is no navigation and nothing to resume`() {
        show(NavUi())
        rule.onNodeWithTag("nav_banner").assertDoesNotExist()
        rule.onNodeWithTag("nav_summary").assertDoesNotExist()
        rule.onNodeWithTag("nav_resume_card").assertDoesNotExist()
    }

    @Test fun `the arrival time is shown in the clock style of the locale`() {
        val millis = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("22:13", NavFormat.clockTime(millis, Locale("es", "ES"), utc))
        assertEquals("10:13 PM", NavFormat.clockTime(millis, Locale.US, utc).replace(' ', ' '))
        assertEquals("1 h 05 min".replace(" 05", " 5"), NavFormat.tripDuration(65 * 60_000L))
    }
}
