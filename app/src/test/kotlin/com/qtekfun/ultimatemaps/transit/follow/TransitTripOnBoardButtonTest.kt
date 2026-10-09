package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.ui.test.assertTextEquals
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

/** The "I'm on board" answer: shown while waiting or walking, flips to "I'm not on board" aboard, and reports what was tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitTripOnBoardButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private val answers = mutableListOf<Boolean>()

    private fun show(phase: FollowPhase) = rule.setContent {
        TransitTripScreen(
            TransitTripUi(TripTestSupport.trip(TripTestSupport.follow(phase) { copy(legIndex = 1, line = TripTestSupport.metro, nextStopIndex = 2, stopsRemaining = 3) })),
            TransitTripActions(onBoard = { answers.add(it) }), false,
        )
    }

    @Test fun whileWaitingTheButtonSaysIAmOnBoard() {
        show(FollowPhase.WAITING)
        rule.onNodeWithTag("trip_on_board").assertTextEquals("I'm on board")
        rule.onNodeWithTag("trip_on_board").performClick()
        assertEquals(listOf(true), answers)
    }

    @Test fun aboardTheButtonSaysIAmNotOnBoard() {
        show(FollowPhase.ON_BOARD)
        rule.onNodeWithTag("trip_on_board").assertTextEquals("I'm not on board")
        rule.onNodeWithTag("trip_on_board").performClick()
        assertEquals(listOf(false), answers)
    }
}
