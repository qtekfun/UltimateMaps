package com.qtekfun.ultimatemaps.transit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.JourneyNote
import com.qtekfun.ultimatemaps.core.transit.TransitMode
import com.qtekfun.ultimatemaps.transit.follow.InMemoryTransitTripSettings
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mode chips, their memory, the "no route with this transport" message and the walking note, on the made-up feed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitModesPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = InMemoryTransitTripSettings()
    private val nearB = LatLon(40.0101, -3.0)

    @After fun tearDown() = scope.cancel()

    private fun controller(withBus: Boolean = true): TransitController {
        val lookup = TransitLookup.Ready(TransitTestSupport.service(withBus = withBus), "Testville")
        return TransitController(
            scope, Dispatchers.Unconfined, { _, _ -> lookup }, TransitTestSupport.clockAt("2026-10-14T07:55:00"), settings = settings,
        )
    }

    private fun show(c: TransitController) = rule.setContent { MapasTheme(darkTheme = false) { TransitSection(c) } }

    private fun lines(c: TransitController) = c.state.itineraries.flatMap { it.rides }.map { it.line.shortName }.toSet()

    @Test
    fun chipsForTheModesOfTheCityAreShownAllOn() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        show(c)
        rule.onNodeWithTag("transit_mode_bus").assertIsDisplayed()
        rule.onNodeWithTag("transit_mode_metro").assertIsDisplayed()
        for (absent in listOf("tram", "train", "ferry")) rule.onNodeWithTag("transit_mode_$absent").assertDoesNotExist()
        rule.onNodeWithTag("transit_modes_reset").assertDoesNotExist()
        assertEquals(setOf("M1"), lines(c), "the metro is the faster of the two direct options")
    }

    @Test
    fun aSingleModeIndexHidesTheChips() {
        val c = controller(withBus = false)
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        show(c)
        rule.onNodeWithTag("transit_option_0").assertIsDisplayed()
        rule.onNodeWithTag("transit_modes").assertDoesNotExist()
    }

    @Test
    fun switchingMetroOffPlansAgainWithBusesOnlyRemembersItAndResetBringsItBack() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        show(c)
        rule.onNodeWithTag("transit_mode_metro").performClick()
        rule.waitForIdle()
        assertEquals(setOf("B1"), lines(c))
        assertEquals(TransitMode.FILTERABLE.toSet() - TransitMode.METRO, settings.allowedModes.value, "remembered")
        rule.onNodeWithTag("transit_modes_reset").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(TransitMode.FILTERABLE.toSet(), settings.allowedModes.value)
        assertTrue("M1" in lines(c))
        rule.onNodeWithTag("transit_modes_reset").assertDoesNotExist()
    }

    @Test
    fun theChoiceIsRestoredByTheNextController() {
        settings.setAllowedModes(setOf(TransitMode.BUS))
        val c = controller()
        assertEquals(setOf(TransitMode.BUS), c.state.modes)
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertEquals(setOf("B1"), lines(c))
    }

    @Test
    fun nothingLeftSaysSoAndKeepsTheChipsSoTheChoiceCanBeUndone() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        show(c)
        rule.onNodeWithTag("transit_mode_metro").performClick()
        rule.onNodeWithTag("transit_mode_bus").performClick()
        rule.waitForIdle()
        assertEquals(TransitError.NO_ROUTE_MODES, c.state.error)
        rule.onNodeWithTag("transit_error").assertIsDisplayed().assertTextContains("No route with the selected transport")
        rule.onNodeWithTag("transit_mode_bus").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("transit_option_0").assertIsDisplayed()
    }

    @Test
    fun walkingComesFirstWithAReasonWhenOnlyASlowBusIsLeft() {
        val c = controller()
        c.setMode(TransitMode.METRO, false)
        // A to B is a 19-minute walk (listed); the 12-minute bus saves only about 2 minutes
        c.plan(TransitTestSupport.nearA, nearB)
        show(c)
        val first = c.state.itineraries.first()
        assertTrue(first.isWalkOnly)
        assertEquals(JourneyNote.WALK_ABOUT_AS_FAST, first.note)
        rule.onNodeWithTag("transit_option_0_note", useUnmergedTree = true).assertIsDisplayed().assertTextContains("Walking is about as fast")
        // with the metro back the ride is offered next to the walk
        c.resetModes()
        rule.waitForIdle()
        assertTrue(c.state.itineraries.any { !it.isWalkOnly })
    }
}
