package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The trip screen offers "Recenter" only after the user moved the map, like the car navigation does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitTripRecenterButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private var taps = 0

    private fun show(moved: Boolean) = rule.setContent {
        TransitTripScreen(
            TransitTripUi(TripTestSupport.trip(TripTestSupport.follow(FollowPhase.WAITING)), mapMoved = moved),
            TransitTripActions(onRecenter = { taps++ }), false,
        )
    }

    @Test fun hiddenWhileTheMapIsFramedByTheApp() {
        show(moved = false)
        rule.onAllNodesWithTag("trip_recenter").assertCountEquals(0)
    }

    @Test fun shownAfterAGestureAndReportsTheTap() {
        show(moved = true)
        rule.onAllNodesWithTag("trip_recenter").assertCountEquals(1)
        rule.onNodeWithTag("trip_recenter").performClick()
        assertEquals(1, taps)
    }
}
