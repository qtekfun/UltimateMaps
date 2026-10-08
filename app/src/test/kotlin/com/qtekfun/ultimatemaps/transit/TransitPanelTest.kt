package com.qtekfun.ultimatemaps.transit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.route.RoutePanel
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
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
import java.io.File
import java.time.LocalDateTime

/**
 * Compose tests of the transit mode inside the real [RoutePanel]. The transit planner runs on the caller
 * (Unconfined) with an injected clock, so the state is final before the first composition: no waiting on other threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transitScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val service = TransitTestSupport.service()
    private val lookup = TransitLookup.Ready(service, "Testville")
    private val transit = TransitController(
        transitScope, Dispatchers.Unconfined, { _, _ -> lookup }, TransitTestSupport.clockAt("2026-10-14T07:55:00"),
    )

    private val route = RoutePreviewController(
        scope, Dispatchers.IO,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                private val outcome = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1)), 1_000.0, 100.0))
                override fun routeDetailed(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) = outcome
                override fun route(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) = outcome.plan
                override fun close() = Unit
            }
        },
        userLocation = { TransitTestSupport.nearA }, showRoute = {}, clearRoute = {}, clock = { 0L },
        log = { _, _, _ -> },
        transit = transit,
    )

    @After fun tearDown() {
        scope.cancel()
        transitScope.cancel()
    }

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) { RoutePanel(route, onUseLocation = {}, originSearch = {}) }
    }

    /** Opens the transit mode for a trip before composing, so the first composition reads the final state. */
    private fun startTransit() {
        route.setTransitMode(true)
        route.start(PlaceInfo("Charlie Town", TransitTestSupport.nearC))
    }

    @Test
    fun fourthProfileSitsNextToTheOthersAndTheOldTagsStillWork() {
        route.start(PlaceInfo("Charlie Town", TransitTestSupport.nearC))
        show()
        for (tag in listOf("profile_car", "profile_foot", "profile_bike", "profile_transit")) rule.onNodeWithTag(tag).assertIsDisplayed()
        rule.onNodeWithTag("profile_car").assertIsSelected()
        rule.onNodeWithTag("profile_transit").performClick()
        rule.onNodeWithTag("profile_transit").assertIsSelected()
        rule.onNodeWithTag("transit_section").assertIsDisplayed()
        rule.onNodeWithTag("transit_option_0").assertIsDisplayed()
        rule.onNodeWithTag("profile_foot").performClick()
        rule.onNodeWithTag("profile_foot").assertIsSelected()
        rule.onNodeWithTag("profile_transit").assertIsNotSelected()
        rule.onNodeWithTag("transit_section").assertDoesNotExist()
        org.junit.Assert.assertEquals(RoutingProfile.FOOT, route.state.profile)
    }

    @Test
    fun withoutTransitSupportThereAreOnlyThreeProfiles() {
        val plain = RoutePreviewController(
            scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps() = null },
            backend = { _, _ -> error("not used") }, userLocation = { null }, showRoute = {}, clearRoute = {}, clock = { 0L }, log = { _, _, _ -> },
        )
        rule.setContent { MapasTheme(darkTheme = false) { RoutePanel(plain, onUseLocation = {}, originSearch = {}) } }
        rule.onNodeWithTag("profile_car").assertIsDisplayed()
        rule.onNodeWithTag("profile_transit").assertDoesNotExist()
    }

    @Test
    fun listsOptionsWithLineChipsTimesTransfersAndWalking() {
        startTransit()
        show()
        val it = transit.state.itineraries.first()
        val zone = transit.state.zone
        rule.onNodeWithTag("transit_option_0").assertIsDisplayed()
        rule.onNodeWithTag("transit_option_0_chip_0", useUnmergedTree = true).onChildren().filterToOne(hasText("M1")).assertTextEquals("M1")
        rule.onNodeWithTag("transit_option_0_facts", useUnmergedTree = true).assertTextContains("Direct", substring = true)
        rule.onNodeWithTag("transit_option_0_facts", useUnmergedTree = true).assertTextContains("walking", substring = true)
        rule.onNodeWithTag("transit_option_0_duration", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("transit_option_0").assertExists()
        org.junit.Assert.assertTrue(it.rides.single().line.color == 0xFF0000FF.toInt())
        rule.onNodeWithText("${TransitFormat.time(it.departAt, zone)} – ${TransitFormat.time(it.arriveAt, zone)}", useUnmergedTree = true).assertExists()
        org.junit.Assert.assertTrue(transit.state.itineraries.size in 1..3)
    }

    @Test
    fun tappingAnOptionShowsTheItineraryCardWithEveryLeg() {
        startTransit()
        show()
        rule.onNodeWithTag("transit_option_0").performClick()
        rule.onNodeWithTag("transit_card").assertIsDisplayed()
        // legs: walk to the boarding stop, the ride, walk to the destination
        rule.onNodeWithTag("transit_leg_0_text", useUnmergedTree = true).assertTextContains("Walk", substring = true)
        rule.onNodeWithTag("transit_leg_0_text", useUnmergedTree = true).assertTextContains("Alpha Square", substring = true)
        rule.onNodeWithTag("transit_leg_1_chip", useUnmergedTree = true).onChildren().filterToOne(hasText("M1")).assertTextEquals("M1")
        rule.onNodeWithTag("transit_leg_1_direction", useUnmergedTree = true).assertTextEquals("Towards Charlie Town")
        rule.onNodeWithTag("transit_leg_1_board", useUnmergedTree = true).assertTextEquals("Board at Alpha Square")
        rule.onNodeWithTag("transit_leg_1_stops", useUnmergedTree = true).assertTextEquals("2 stops")
        rule.onNodeWithTag("transit_leg_1_alight", useUnmergedTree = true).assertTextEquals("Get off at Charlie Town")
        rule.onNodeWithTag("transit_leg_1_arrive", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("transit_leg_2_text", useUnmergedTree = true).assertTextContains("your destination", substring = true)
        rule.onNodeWithTag("transit_note").assertExists()
        rule.onNodeWithTag("transit_back").performClick()
        rule.onNodeWithTag("transit_list").assertIsDisplayed()
        rule.onNodeWithTag("transit_card").assertDoesNotExist()
    }

    @Test
    fun showsTheHonestNoteTheValidityAndTheAttributionWithItsLink() {
        startTransit()
        show()
        rule.onNodeWithTag("transit_note").assertTextContains("theoretical", substring = true)
        rule.onNodeWithTag("transit_note").assertTextContains("real-time", substring = true)
        rule.onNodeWithTag("transit_validity").assertExists()
        rule.onNodeWithTag("transit_attribution_0").assertTextContains("Powered by Test Agency", substring = true)
        rule.onNodeWithTag("transit_attribution_0").assertTextContains("Processed data", substring = true)
        rule.onNodeWithTag("transit_attribution_0_link_0").assertExists()
    }

    @Test
    fun anExpiredFeedShowsAClearMessageAndNoItineraries() {
        startTransit()
        transit.departAt(LocalDateTime.parse("2026-11-20T08:00:00"))
        show()
        rule.onNodeWithTag("transit_error").assertTextContains("expired", substring = true)
        rule.onNodeWithTag("transit_option_0").assertDoesNotExist()
        rule.onNodeWithTag("transit_note").assertDoesNotExist()
    }

    @Test
    fun departurePickerStepsTheTimeFromTheInjectedClock() {
        startTransit()
        show()
        rule.onNodeWithTag("transit_depart_now").assertIsSelected()
        rule.onNodeWithTag("transit_depart_later").performClick()
        rule.onNodeWithTag("transit_depart_time").assertTextEquals("07:55")
        rule.onNodeWithTag("transit_time_next").performClick()
        rule.onNodeWithTag("transit_depart_time").assertTextEquals("08:10")
        rule.onNodeWithTag("transit_time_prev").performClick()
        rule.onNodeWithTag("transit_depart_time").assertTextEquals("07:55")
        rule.onNodeWithTag("transit_time_prev").performClick()
        rule.onNodeWithTag("transit_depart_time").assertTextEquals("07:40")
        rule.onNodeWithTag("transit_depart_now").performClick()
        rule.onNodeWithTag("transit_depart_now").assertIsSelected()
    }
}
