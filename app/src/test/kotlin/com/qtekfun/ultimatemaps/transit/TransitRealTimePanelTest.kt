package com.qtekfun.ultimatemaps.transit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
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

/** The itinerary list and card with Cercanías real time on, off, and on for a train that is not in the feed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitRealTimePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeRealTime : TransitRealTime {
        override var enabled by mutableStateOf(true)
        override var version by mutableIntStateOf(0)
        var answer: LegRealTime? = null
        var refreshes = 0
        override fun forRide(ride: ItineraryLeg.Ride): LegRealTime? = answer
        override fun supports(ride: ItineraryLeg.Ride) = ride.tripId != null
        override fun refresh() { refreshes++ }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val rt = FakeRealTime()

    @After fun tearDown() = scope.cancel()

    private fun controller(keepIds: Boolean = true, realTime: TransitRealTime? = rt): TransitController {
        val lookup = TransitLookup.Ready(TransitTestSupport.service(keepIds), "Testville")
        return TransitController(
            scope, Dispatchers.Unconfined, { _, _ -> lookup }, TransitTestSupport.clockAt("2026-10-14T07:55:00"), realTime = realTime,
        ).also { it.plan(TransitTestSupport.nearA, TransitTestSupport.nearC) }
    }

    private fun show(c: TransitController) = rule.setContent { MapasTheme(darkTheme = false) { TransitSection(c) } }

    @Test fun aDelayedTrainShowsInTheListWithItsLine() {
        rt.answer = LegRealTime(delaySec = 240)
        show(controller())
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertTextEquals("M1 · Delayed 4 min")
    }

    @Test fun theCardShowsTheLabelTheStatusAndTheAlertsAndTheNoteStaysHonest() {
        rt.answer = LegRealTime(delaySec = 240, alerts = listOf("Retrasos por obras"))
        val c = controller()
        show(c)
        rule.onNodeWithTag("transit_option_0").performClick()
        rule.onNodeWithTag("transit_leg_1_rt", useUnmergedTree = true).assertTextEquals("Real time (Renfe) · Delayed 4 min")
        rule.onNodeWithTag("transit_leg_1_rt_detail_0", useUnmergedTree = true).assertTextEquals("Alert: Retrasos por obras")
        rule.onNodeWithTag("transit_note").assertTextContains("Real time (Renfe)", substring = true)
        rule.onNodeWithTag("transit_note").assertTextContains("every other leg has no real-time information", substring = true)
        rule.onNodeWithTag("transit_rt_source").assertTextContains("Renfe", substring = true)
    }

    @Test fun aCancelledTrainSaysSo() {
        rt.answer = LegRealTime(cancelled = true)
        show(controller())
        rule.onNodeWithTag("transit_option_0").performClick()
        rule.onNodeWithTag("transit_leg_1_rt", useUnmergedTree = true).assertTextEquals("Real time (Renfe) · Cancelled")
    }

    @Test fun aTrainNotInTheFeedStaysScheduledAndSaysNothingAboutRealTime() {
        rt.answer = null
        show(controller())
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("transit_option_0").performClick()
        rule.onNodeWithTag("transit_leg_1_rt", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("transit_card").assertIsDisplayed()
    }

    @Test fun withTheSwitchOffNothingAppearsAndNothingIsRequested() {
        rt.enabled = false
        rt.answer = LegRealTime(delaySec = 240)
        show(controller())
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("transit_option_0").performClick()
        rule.onNodeWithTag("transit_leg_1_rt", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("transit_note").assertTextContains("theoretical", substring = true)
        rule.onNodeWithTag("transit_rt_source").assertDoesNotExist()
        assertEquals(0, rt.refreshes)
    }

    @Test fun anIndexWithoutFeedIdsNeverMentionsRealTimeEvenWithTheSwitchOn() {
        rt.answer = LegRealTime(delaySec = 240) // the fake would answer for any ride, but none has a trip id
        show(controller(keepIds = false))
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, rt.refreshes)
        rule.onNodeWithTag("transit_note").assertTextContains("theoretical", substring = true)
        rule.onNodeWithTag("transit_rt_source").assertDoesNotExist()
    }

    @Test fun showingATripWithARenfeTrainAsksOnceForFreshData() {
        rt.answer = null
        show(controller())
        assertTrue(rt.refreshes >= 1, "refreshes=${rt.refreshes}")
    }

    @Test fun newDataRecomposesTheCard() {
        rt.answer = null
        show(controller())
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertDoesNotExist()
        rt.answer = LegRealTime(delaySec = 600)
        rt.version++
        rule.waitForIdle()
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertTextEquals("M1 · Delayed 10 min")
    }

    @Test fun withoutARealTimeSourceTheCardIsTheOldOne() {
        show(controller(realTime = null))
        rule.onNodeWithTag("transit_note").assertTextContains("theoretical", substring = true)
        rule.onNodeWithTag("transit_option_0_rt_0", useUnmergedTree = true).assertDoesNotExist()
    }
}
