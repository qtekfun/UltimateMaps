package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.qtekfun.ultimatemaps.core.transit.follow.ConnectionStatus
import com.qtekfun.ultimatemaps.core.transit.follow.FollowBasis
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.PlanStatus
import com.qtekfun.ultimatemaps.transit.follow.TripTestSupport.follow
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport.assertDisjointAndInside
import com.qtekfun.ultimatemaps.ui.fold.FoldFeatureInfo
import com.qtekfun.ultimatemaps.ui.fold.FoldTestSupport
import com.qtekfun.ultimatemaps.ui.fold.LocalFoldFeature
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The trip screen on a foldable in book and tabletop posture: nothing crosses the hinge. Layout assertions only. */
@RunWith(RobolectricTestRunner::class)
class TransitTripFoldTest {
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

    private val tags = listOf("trip_banner", "trip_bottom", "trip_mute", "trip_glove")

    private fun show(fold: FoldFeatureInfo?, glove: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalFoldFeature provides fold) {
                TransitTripScreen(TransitTripUi(TripTestSupport.trip(state), glove = glove), TransitTripActions(), dark = false)
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `book posture keeps the column before the hinge and the switches after it`() {
        val hinge = FoldTestSupport.vertical(420.5f, 701)
        show(hinge, glove = true)
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertNoneCrosses(rule, tags + "trip_stop", hinge)
        for (t in listOf("trip_banner", "trip_bottom", "trip_stop")) FoldTestSupport.assertBeforeVertical(rule, t, hinge)
        for (t in listOf("trip_mute", "trip_glove")) FoldTestSupport.assertAfterVertical(rule, t, hinge)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `tabletop posture puts the banner above the hinge and the stops, Stop and switches below it`() {
        val hinge = FoldTestSupport.horizontal(350.5f, 841)
        show(hinge, glove = false)
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertNoneCrosses(rule, tags + "trip_stop", hinge)
        FoldTestSupport.assertAboveHorizontal(rule, "trip_banner", hinge)
        for (t in listOf("trip_bottom", "trip_stop", "trip_mute", "trip_glove")) FoldTestSupport.assertBelowHorizontal(rule, t, hinge)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w841dp-h701dp-420dpi")
    fun `tabletop in glove mode still keeps every part in its pane`() {
        val hinge = FoldTestSupport.horizontal(350.5f, 841)
        show(hinge, glove = true)
        assertDisjointAndInside(rule, tags)
        FoldTestSupport.assertNoneCrosses(rule, tags + "trip_stop", hinge)
    }
}
