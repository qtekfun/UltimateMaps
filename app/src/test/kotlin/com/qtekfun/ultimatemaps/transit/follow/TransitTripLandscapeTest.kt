package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.ui.test.junit4.createComposeRule
import com.qtekfun.ultimatemaps.core.transit.follow.ConnectionStatus
import com.qtekfun.ultimatemaps.core.transit.follow.FollowBasis
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.PlanStatus
import com.qtekfun.ultimatemaps.transit.follow.TripTestSupport.follow
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.bounds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** The trip screen at 800 x 360 dp with the banner, several strips, the stop list and the switches on screen at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
class TransitTripLandscapeTest {
    @get:Rule
    val rule = createComposeRule()

    private val state = follow(FollowPhase.ON_BOARD) {
        copy(
            legIndex = 1, line = TripTestSupport.metro, headsign = "Westbound", lastStopIndex = 1, nextStopIndex = 2, stopsRemaining = 3,
            nextStopName = "Charlie", alightName = "Echo", alightAt = TripTestSupport.T0 + 1170, etaAt = TripTestSupport.T0 + 1920,
            planOffsetSec = 200, plan = PlanStatus.BEHIND, planMinutes = 3,
            basis = FollowBasis.ESTIMATED, connection = ConnectionStatus.AT_RISK, connectionLine = "27",
        )
    }

    @Test fun `the banner column, the stops, the stop button and the switches do not overlap`() {
        rule.setContent { TransitTripScreen(TransitTripUi(TripTestSupport.trip(state), glove = true), TransitTripActions(), dark = false) }
        assertDisjointAndInside(rule, listOf("trip_bottom", "trip_mute", "trip_glove", "trip_banner"))
        val banner = bounds(rule, "trip_banner")
        assertTrue(banner.right <= 380 * 3f + 1f, "the banner is a column of at most 380 dp: $banner")
        assertTrue(bounds(rule, "trip_stop").bottom <= 360 * 3f + 1f)
    }
}
