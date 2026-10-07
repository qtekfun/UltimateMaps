package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.transit.follow.ConnectionStatus
import com.qtekfun.ultimatemaps.core.transit.follow.FollowBasis
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.PlanStatus
import com.qtekfun.ultimatemaps.transit.follow.TripTestSupport.follow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The banner, the stop list, the plan chip and the buttons of the trip screen, with fixed states (no location, no clock). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitTripScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = TransitTripActions(
        onStop = { log += "stop" }, onReplan = { log += "replan" }, onGlove = { log += "glove:$it" }, onVoice = { log += "voice:$it" },
        onResume = { log += "resume" }, onDiscard = { log += "discard" },
    )

    private fun show(ui: TransitTripUi, dark: Boolean = false) = rule.setContent { TransitTripScreen(ui, actions, dark) }

    private val onBoard = follow(FollowPhase.ON_BOARD) {
        copy(
            legIndex = 1, line = TripTestSupport.metro, headsign = "Westbound", lastStopIndex = 1, nextStopIndex = 2, stopsRemaining = 3,
            nextStopName = "Charlie", alightName = "Echo", alightAt = TripTestSupport.T0 + 1170, etaAt = TripTestSupport.T0 + 1920,
            planOffsetSec = 200, plan = PlanStatus.BEHIND, planMinutes = 3,
        )
    }

    @Test fun theBannerShowsTheInstructionTheLineChipAndTheDetail() {
        show(TransitTripUi(TripTestSupport.trip(onBoard)))
        rule.onNodeWithTag("trip_banner").assertIsDisplayed()
        rule.onNodeWithTag("trip_title").assertTextEquals("Next stop: Charlie")
        rule.onNodeWithTag("trip_detail").assertTextEquals("Get off at Echo in 3 stops")
        rule.onNodeWithTag("trip_line_chip", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun thePlanChipSaysHowFarBehindThePlanTheTripIs() {
        show(TransitTripUi(TripTestSupport.trip(onBoard)))
        rule.onNodeWithTag("trip_plan_chip").assertTextEquals("About 3 min behind plan")
    }

    @Test fun theStopListMarksPassedNextAndUpcomingStops() {
        show(TransitTripUi(TripTestSupport.trip(onBoard)))
        for (i in 0..4) rule.onNodeWithTag("trip_stop_$i").assertExists()
        rule.onNodeWithTag("trip_stop_0_name", useUnmergedTree = true).assertTextEquals("Alpha")
        rule.onNodeWithTag("trip_stop_2_name", useUnmergedTree = true).assertTextEquals("Charlie")
        val described = { i: Int -> rule.onNodeWithTag("trip_stop_$i").fetchSemanticsNode().config.toString() }
        assertTrue(described(0).contains("passed"), described(0))
        assertTrue(described(1).contains("passed"), described(1))
        assertTrue(described(2).contains("next stop"), described(2))
        assertTrue(!described(3).contains("passed") && !described(3).contains("next stop"), described(3))
    }

    @Test fun beforeBoardingTheListShowsTheComingRideWithNothingPassed() {
        val waiting = follow(FollowPhase.WAITING) {
            copy(legIndex = 1, line = TripTestSupport.metro, headsign = "Westbound", targetName = "Alpha", boardAt = TripTestSupport.T0 + 600, secondsToBoard = 180, etaAt = TripTestSupport.T0 + 1920)
        }
        show(TransitTripUi(TripTestSupport.trip(waiting)))
        rule.onNodeWithTag("trip_title").assertTextEquals("Board line L5 towards Westbound")
        rule.onNodeWithTag("trip_stop_0").assertExists()
        assertTrue(!rule.onNodeWithTag("trip_stop_0").fetchSemanticsNode().config.toString().contains("passed"))
    }

    @Test fun whenWalkingTheListShowsTheNextRide() {
        val walking = follow(FollowPhase.BEFORE_START) { copy(line = TripTestSupport.metro, targetName = "Alpha", walkMeters = 520, walkSeconds = 416, boardAt = TripTestSupport.T0 + 600) }
        show(TransitTripUi(TripTestSupport.trip(walking)))
        rule.onNodeWithTag("trip_title").assertTextEquals("Walk to Alpha")
        rule.onNodeWithTag("trip_stop_4_name", useUnmergedTree = true).assertTextEquals("Echo")
    }

    @Test fun estimatedPositionAndNoGpsAreSaidInWords() {
        show(TransitTripUi(TripTestSupport.trip(onBoard.copy(basis = FollowBasis.ESTIMATED))))
        rule.onNodeWithTag("trip_signal").assertTextEquals("No GPS: position estimated from the timetable")
    }

    @Test fun aConnectionAtRiskShowsAWarning() {
        show(TransitTripUi(TripTestSupport.trip(onBoard.copy(connection = ConnectionStatus.AT_RISK, connectionLine = "27"))))
        rule.onNodeWithTag("trip_connection").assertTextEquals("Connection to line 27 at risk")
    }

    @Test fun replanIsOfferedOnlyWhenItApplies() {
        show(TransitTripUi(TripTestSupport.trip(onBoard)))
        rule.onNodeWithTag("trip_replan").assertDoesNotExist()
    }

    @Test fun replanIsOfferedWhenOffPlanAndNeverRunsByItself() {
        show(TransitTripUi(TripTestSupport.trip(follow(FollowPhase.OFF_PLAN) { copy(canReplan = true) })))
        rule.onNodeWithTag("trip_title").assertTextEquals("You are off the plan")
        assertEquals(emptyList(), log)
        rule.onNodeWithTag("trip_replan").assertIsDisplayed().performClick()
        assertEquals(listOf("replan"), log)
    }

    @Test fun whileReplanningTheButtonGivesWayToAMessage() {
        show(TransitTripUi(TripTestSupport.trip(follow(FollowPhase.OFF_PLAN) { copy(canReplan = true) }, replanning = true)))
        rule.onNodeWithTag("trip_replan").assertDoesNotExist()
        rule.onNodeWithTag("trip_replanning").assertTextEquals("Planning a new trip…")
    }

    @Test fun aFailedReplanSaysSoAndKeepsTheButton() {
        show(TransitTripUi(TripTestSupport.trip(follow(FollowPhase.OFF_PLAN) { copy(canReplan = true) }, failed = true)))
        rule.onNodeWithTag("trip_replan_failed").assertTextContains("No new trip", substring = true)
        rule.onNodeWithTag("trip_replan").assertIsDisplayed()
    }

    @Test fun stopMuteAndGloveButtonsCallBack() {
        show(TransitTripUi(TripTestSupport.trip(onBoard), glove = false, voiceOn = true))
        rule.onNodeWithTag("trip_stop").performClick()
        rule.onNodeWithTag("trip_mute").performClick()
        rule.onNodeWithTag("trip_glove").performClick()
        assertEquals(listOf("stop", "voice:false", "glove:true"), log)
    }

    @Test fun whenMutedTheButtonOffersToUnmute() {
        show(TransitTripUi(TripTestSupport.trip(onBoard), voiceOn = false))
        rule.onNodeWithTag("trip_mute").performClick()
        assertEquals(listOf("voice:true"), log)
    }

    @Test fun afterArrivingTheStopButtonBecomesDone() {
        show(TransitTripUi(TripTestSupport.trip(follow(FollowPhase.ARRIVED) { copy(legIndex = 5) })))
        rule.onNodeWithTag("trip_title").assertTextEquals("You have arrived")
        rule.onNodeWithTag("trip_stop").assertIsDisplayed()
        rule.onNodeWithTag("trip_stop", useUnmergedTree = true).fetchSemanticsNode().config.toString().let { assertTrue(it.contains("Done"), it) }
    }

    @Test fun theArrivalTimeAndTheHonestNoteAreShown() {
        show(TransitTripUi(TripTestSupport.trip(onBoard)))
        rule.onNodeWithTag("trip_eta").assertTextContains("Arrive ", substring = true)
        rule.onNodeWithTag("trip_note").assertTextEquals("Scheduled times, no real-time data")
    }

    @Test fun gloveModeKeepsEveryButtonAtLeast56dpHigh() {
        show(TransitTripUi(TripTestSupport.trip(follow(FollowPhase.OFF_PLAN) { copy(canReplan = true) }), glove = true))
        val density = rule.density
        for (tag in listOf("trip_stop", "trip_mute", "trip_glove", "trip_replan")) {
            val h = rule.onNodeWithTag(tag).fetchSemanticsNode().size.height
            assertTrue(with(density) { h.toDp().value } >= 56f, "$tag is $h px")
        }
    }

    @Test fun theNightThemeDrawsTheSameScreen() {
        show(TransitTripUi(TripTestSupport.trip(onBoard)), dark = true)
        rule.onNodeWithTag("trip_title").assertTextEquals("Next stop: Charlie")
        rule.onNodeWithTag("trip_plan_chip").assertIsDisplayed()
    }

    @Test fun nothingIsDrawnWithoutATripOrAResumableOne() {
        show(TransitTripUi())
        rule.onNodeWithTag("trip_banner").assertDoesNotExist()
        rule.onNodeWithTag("trip_resume_card").assertDoesNotExist()
    }

    @Test fun aResumableTripOffersResumeAndDiscard() {
        show(TransitTripUi(resumable = true))
        rule.onNodeWithTag("trip_resume_card").assertIsDisplayed()
        rule.onNodeWithTag("trip_resume").performClick()
        rule.onNodeWithTag("trip_discard").performClick()
        assertEquals(listOf("resume", "discard"), log)
    }
}
