package com.qtekfun.ultimatemaps.cameras

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.core.cameras.AlertBannerState
import com.qtekfun.ultimatemaps.core.cameras.AlertCategory
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.IncidentBannerMachine
import com.qtekfun.ultimatemaps.core.cameras.IncidentBannerState
import com.qtekfun.ultimatemaps.core.cameras.IncidentKind
import com.qtekfun.ultimatemaps.core.cameras.IncidentRepository
import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import com.qtekfun.ultimatemaps.core.cameras.TargetGrid
import com.qtekfun.ultimatemaps.core.cameras.TrafficIncident
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import com.qtekfun.ultimatemaps.nav.NavActions
import com.qtekfun.ultimatemaps.nav.NavPhase
import com.qtekfun.ultimatemaps.nav.NavScreen
import com.qtekfun.ultimatemaps.nav.NavUi
import com.qtekfun.ultimatemaps.nav.navState
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The temporary incident banner: texts, clock, dismissal, accessibility, glove size, place on the navigation screen. No real time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class IncidentBannerTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf<IncidentBannerState?>(null)
    private var glove by mutableStateOf(false)
    private var dismissed = 0

    private fun show() {
        rule.setContent { MapasTheme(darkTheme = false) { IncidentBannerContent(state, { dismissed++ }, glove = glove) } }
    }

    private fun description(): String? =
        rule.onNodeWithTag("incident_banner").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun banner(kind: IncidentKind = IncidentKind.CONGESTION, meters: Int = 400, millis: Long = 5_000) =
        IncidentBannerState("i", kind, meters, millis)

    @Test fun drawsNothingWithoutAnIncident() {
        show()
        rule.onNodeWithTag("incident_banner").assertDoesNotExist()
    }

    @Test fun showsTheIconTheTextTheDistanceAndTheClock() {
        state = banner()
        show()
        rule.onNodeWithTag("incident_banner").assertIsDisplayed()
        rule.onNodeWithTag("incident_banner_icon", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("incident_banner_title", useUnmergedTree = true).assertTextEquals("Slow traffic ahead")
        rule.onNodeWithTag("incident_banner_distance", useUnmergedTree = true).assertTextEquals("400 m")
        rule.onNodeWithTag("incident_banner_clock", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("incident_banner_seconds", useUnmergedTree = true).assertTextEquals("5")
    }

    @Test fun theClockCountsDownFromFiveToOne() {
        show()
        for ((millis, seconds) in listOf(5_000L to "5", 4_000L to "4", 3_000L to "3", 2_000L to "2", 1_000L to "1", 999L to "1")) {
            state = banner(millis = millis)
            rule.waitForIdle()
            rule.onNodeWithTag("incident_banner_seconds", useUnmergedTree = true).assertTextEquals(seconds)
        }
    }

    @Test fun oneSpokenDescriptionWithoutTheTickingSecondsAndADismissAction() {
        state = banner(millis = 3_000)
        show()
        assertEquals("Slow traffic ahead, in 400 m", description())
        state = banner(millis = 1_000)
        rule.waitForIdle()
        assertEquals("Slow traffic ahead, in 400 m", description())
        val node = rule.onNodeWithTag("incident_banner").fetchSemanticsNode().config
        assertNotNull(node.getOrNull(SemanticsActions.OnClick), "TalkBack can dismiss it")
        assertEquals("Dismiss", node[SemanticsActions.OnClick].label)
    }

    @Test fun tappingItDismisses() {
        state = banner()
        show()
        rule.onNodeWithTag("incident_banner").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun everyKindHasItsOwnWording() {
        show()
        val expected = mapOf(
            IncidentKind.V16 to "Stopped vehicle ahead (V16)", IncidentKind.ACCIDENT to "Accident ahead",
            IncidentKind.CLOSURE to "Road closure ahead", IncidentKind.CONGESTION to "Slow traffic ahead",
            IncidentKind.OBSTACLE to "Obstacle ahead", IncidentKind.WEATHER to "Bad weather ahead",
            IncidentKind.ROADWORKS to "Roadworks ahead",
        )
        assertEquals(IncidentKind.entries.toSet(), expected.keys)
        for ((kind, text) in expected) {
            state = banner(kind)
            rule.waitForIdle()
            rule.onNodeWithTag("incident_banner_title", useUnmergedTree = true).assertTextEquals(text)
        }
    }

    @Test fun gloveModeIsBigger() {
        state = banner()
        glove = true
        show()
        rule.onNodeWithTag("incident_banner").assertHeightIsAtLeast(72.dp)
        glove = false
        rule.waitForIdle()
        rule.onNodeWithTag("incident_banner").assertHeightIsAtLeast(52.dp)
    }

    @Test fun theHostVersionFollowsTheMachineAndTapDismissesItEarly() {
        val perMeter = 1.0 / TargetGrid.METERS_PER_DEGREE
        val route = RouteGeometry(listOf(LatLon(40.0, -3.7), LatLon(40.0 + 5_000 * perMeter, -3.7)))
        val repo = object : IncidentRepository {
            val item = TrafficIncident("x", IncidentKind.ACCIDENT, "A-1", LatLon(40.0 + 600 * perMeter, -3.7), null, null, null, null, null, null, null)
            override fun incidentsIn(bounds: LatLonBounds, kinds: Set<IncidentKind>, limit: Int) = listOf(item).filter { it.kind in kinds }
            override fun incident(id: String) = item.takeIf { it.id == id }
            override val lastUpdateMillis = MutableStateFlow<Long?>(null)
        }
        var now = 0L
        val machine = IncidentBannerMachine({ repo }, { CameraSettings(incidentsEnabled = true) }, { now })
        machine.onRoute(route)
        rule.setContent {
            MapasTheme(darkTheme = false) { CompositionLocalProvider(LocalIncidentBanner provides machine) { IncidentBanner() } }
        }
        rule.onNodeWithTag("incident_banner").assertDoesNotExist()
        machine.onProgress(0.0, 40.0, -3.7, 25.0)
        rule.waitForIdle()
        rule.onNodeWithTag("incident_banner_title", useUnmergedTree = true).assertTextEquals("Accident ahead")
        now = 2_000
        machine.tick()
        rule.waitForIdle()
        rule.onNodeWithTag("incident_banner_seconds", useUnmergedTree = true).assertTextEquals("3")
        rule.onNodeWithTag("incident_banner").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("incident_banner").assertDoesNotExist()
        assertNull(machine.state.value)
    }

    @Test fun drawsNothingWhereNoMachineIsProvided() {
        rule.setContent { MapasTheme(darkTheme = false) { IncidentBanner() } }
        rule.onNodeWithTag("incident_banner").assertDoesNotExist()
    }

    @Test fun theStringsExistInEnglishAndSpanishWithTheSameKeys() {
        fun keys(f: String) = Regex("<string name=\"([^\"]+)\"").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        val en = keys("src/main/res/values/strings_incident_banner.xml")
        val es = keys("src/main/res/values-es/strings_incident_banner.xml")
        assertEquals(en, es)
        assertTrue(en.size >= 9)
    }

    @Test fun onTheNavigationScreenItStacksBelowTheCameraChipAndBelowTheManeuverBanner() {
        val chip = MutableStateFlow<AlertBannerState?>(AlertBannerState(AlertCategory.FIXED_CAMERA, 500, 90))
        state = banner()
        val machine = machineShowing()
        rule.setContent {
            CompositionLocalProvider(LocalAlertBanner provides chip, LocalIncidentBanner provides machine) {
                NavScreen(
                    NavUi(phase = NavPhase.ON_ROUTE, nav = navState(), voiceOn = false, etaMillis = 1_700_000_000_000L),
                    NavActions(), dark = false,
                )
            }
        }
        rule.waitForIdle()
        val banner = rule.onNodeWithTag("nav_banner").fetchSemanticsNode().boundsInRoot
        val camera = rule.onNodeWithTag("camera_alert").fetchSemanticsNode().boundsInRoot
        val incident = rule.onNodeWithTag("incident_banner").fetchSemanticsNode().boundsInRoot
        assertTrue(camera.top >= banner.bottom - 1f, "camera chip below the maneuver banner")
        assertTrue(incident.top >= camera.bottom - 1f, "incident banner below the camera chip")
        rule.onNodeWithTag("nav_mute").assertIsDisplayed()
    }

    private fun machineShowing(): IncidentBannerMachine {
        val perMeter = 1.0 / TargetGrid.METERS_PER_DEGREE
        val route = RouteGeometry(listOf(LatLon(40.0, -3.7), LatLon(40.0 + 5_000 * perMeter, -3.7)))
        val item = TrafficIncident("y", IncidentKind.CONGESTION, "A-1", LatLon(40.0 + 600 * perMeter, -3.7), null, null, null, null, null, null, null)
        val repo = object : IncidentRepository {
            override fun incidentsIn(bounds: LatLonBounds, kinds: Set<IncidentKind>, limit: Int) = listOf(item)
            override fun incident(id: String) = item
            override val lastUpdateMillis = MutableStateFlow<Long?>(null)
        }
        return IncidentBannerMachine({ repo }, { CameraSettings(incidentsEnabled = true) }, { 0L }).also {
            it.onRoute(route)
            it.onProgress(0.0, 40.0, -3.7, 25.0)
        }
    }
}
