package com.qtekfun.ultimatemaps.transit.follow

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.PlanStatus
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
import com.qtekfun.ultimatemaps.transit.follow.TripTestSupport.follow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What the live follower says about Renfe real time: strips, the note under the arrival time, and the notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitTripRealTimeTest {
    @get:Rule
    val rule = createComposeRule()

    private val en = ApplicationProvider.getApplicationContext<Application>().resources
    private val actions = TransitTripActions({}, {}, {}, {}, {}, {})

    private val onBoard = follow(FollowPhase.ON_BOARD) {
        copy(
            legIndex = 1, line = TripTestSupport.metro, headsign = "Westbound", lastStopIndex = 1, nextStopIndex = 2, stopsRemaining = 3,
            nextStopName = "Charlie", alightName = "Echo", alightAt = TripTestSupport.T0 + 1170, etaAt = TripTestSupport.T0 + 1920 + 240,
            planOffsetSec = 240, plan = PlanStatus.BEHIND, planMinutes = 4,
        )
    }

    private fun show(state: com.qtekfun.ultimatemaps.core.transit.follow.FollowState) =
        rule.setContent { TransitTripScreen(TransitTripUi(TripTestSupport.trip(state)), actions, false) }

    @Test fun aDelayedRealTimeTrainShowsTheDelayAndThePlanChipUsesIt() {
        show(onBoard.copy(realTime = LegRealTime(delaySec = 240)))
        rule.onNodeWithTag("trip_rt_status").assertTextEquals("Real time (Renfe) · Delayed 4 min")
        rule.onNodeWithTag("trip_plan_chip").assertTextEquals("About 4 min behind plan")
        rule.onNodeWithTag("trip_note").assertTextEquals("Real time (Renfe)")
    }

    @Test fun alertsAreShownAsStrips() {
        show(onBoard.copy(realTime = LegRealTime(delaySec = 0, alerts = listOf("Obras entre Sol y Atocha"), skippedStops = listOf("Charlie"))))
        rule.onNodeWithTag("trip_rt_status").assertTextEquals("Real time (Renfe) · On time")
        rule.onNodeWithTag("trip_rt_detail_0").assertTextEquals("Does not stop at Charlie")
        rule.onNodeWithTag("trip_rt_detail_1").assertTextEquals("Alert: Obras entre Sol y Atocha")
    }

    @Test fun aCancelledTrainSaysSo() {
        show(
            follow(FollowPhase.WAITING) {
                copy(legIndex = 1, line = TripTestSupport.metro, headsign = "Westbound", boardAt = TripTestSupport.T0 + 600, secondsToBoard = 180, realTime = LegRealTime(cancelled = true), canReplan = true)
            },
        )
        rule.onNodeWithTag("trip_rt_status").assertTextEquals("Real time (Renfe) · Cancelled")
        rule.onNodeWithTag("trip_replan").assertExists()
    }

    @Test fun withoutRealTimeTheNoteStaysScheduledAndNoStripAppears() {
        show(onBoard)
        rule.onNodeWithTag("trip_rt_status").assertDoesNotExist()
        rule.onNodeWithTag("trip_note").assertTextEquals("Scheduled times, no real-time data")
    }

    @Test fun alertsWithoutTheTrainInTheFeedKeepTheScheduledNote() {
        show(onBoard.copy(realTime = LegRealTime(alerts = listOf("Aviso"))))
        rule.onNodeWithTag("trip_rt_status").assertDoesNotExist()
        rule.onNodeWithTag("trip_rt_detail_0").assertTextEquals("Alert: Aviso")
        rule.onNodeWithTag("trip_note").assertTextEquals("Scheduled times, no real-time data")
    }

    @Test fun theNotificationCarriesTheStatusAndRefreshesWhenItChanges() {
        val plain = TripTestSupport.trip(onBoard)
        val delayed = TripTestSupport.trip(onBoard.copy(realTime = LegRealTime(delaySec = 240)))
        val text = TransitTripNotificationTexts.of(en, delayed, Locale.ENGLISH).text
        assertTrue(text.contains("Delayed 4 min"), text)
        assertTrue(!TransitTripNotificationTexts.of(en, plain, Locale.ENGLISH).text.contains("Delayed"))
        assertNotEquals(TransitTripNotificationTexts.key(plain), TransitTripNotificationTexts.key(delayed))
        val cancelled = TripTestSupport.trip(onBoard.copy(realTime = LegRealTime(cancelled = true)))
        assertNotEquals(TransitTripNotificationTexts.key(delayed), TransitTripNotificationTexts.key(cancelled))
        assertEquals(TransitTripNotificationTexts.key(delayed), TransitTripNotificationTexts.key(TripTestSupport.trip(onBoard.copy(realTime = LegRealTime(delaySec = 250)))))
    }
}
