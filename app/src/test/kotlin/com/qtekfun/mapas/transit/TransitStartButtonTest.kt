package com.qtekfun.mapas.transit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.mapas.core.transit.Itinerary
import com.qtekfun.mapas.ui.theme.MapasTheme
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
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Start button of the itinerary card: shown only when a follower is available and the trip has a vehicle leg. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitStartButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val started = mutableListOf<Pair<Itinerary, ZoneId>>()
    private val lookup = TransitLookup.Ready(TransitTestSupport.service(), "Testville")

    private fun controller(onStart: ((Itinerary, ZoneId) -> Boolean)?) = TransitController(
        scope, Dispatchers.Unconfined, { _, _ -> lookup }, TransitTestSupport.clockAt("2026-10-14T07:55:00"), onStartTrip = onStart,
    ).also {
        it.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        it.select(0)
    }

    @After fun tearDown() = scope.cancel()

    private fun show(c: TransitController) = rule.setContent { MapasTheme(darkTheme = false) { TransitSection(c) } }

    @Test fun theCardOffersStartAndPressingItHandsOverTheSelectedItinerary() {
        val c = controller { it, zone -> started += it to zone; true }
        show(c)
        rule.onNodeWithTag("transit_card").assertIsDisplayed()
        rule.onNodeWithTag("transit_trip_start").assertIsDisplayed().performClick()
        assertEquals(1, started.size)
        assertEquals(c.state.current, started[0].first)
        assertEquals(TransitTestSupport.zone, started[0].second)
    }

    @Test fun withoutAFollowerThereIsNoStartButtonAndTheOldTagsRemain() {
        show(controller(null))
        rule.onNodeWithTag("transit_card").assertIsDisplayed()
        rule.onNodeWithTag("transit_trip_start").assertDoesNotExist()
        rule.onNodeWithTag("transit_back").assertIsDisplayed()
        rule.onNodeWithTag("transit_card_summary").assertIsDisplayed()
        rule.onNodeWithTag("transit_leg_1").assertIsDisplayed()
    }

    @Test fun startTripReportsWhetherItStarted() {
        assertFalse(controller(null).startTrip())
        assertTrue(controller { _, _ -> true }.startTrip())
        assertFalse(controller { _, _ -> false }.startTrip())
    }
}
