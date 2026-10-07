package com.qtekfun.ultimatemaps.route

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.nav.LaunchStatus
import com.qtekfun.ultimatemaps.nav.NavLaunchState
import com.qtekfun.ultimatemaps.nav.NavStartHost
import com.qtekfun.ultimatemaps.nav.RouteAdvice
import com.qtekfun.ultimatemaps.nav.RouteFailure
import com.qtekfun.ultimatemaps.nav.RouteFailureKind
import com.qtekfun.ultimatemaps.places.PlaceInfo
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
import kotlin.test.assertEquals

/** The route card's "Start" and "Simulate" buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutePanelStartTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val line = listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1))

    private val route = RoutePreviewController(
        scope, Dispatchers.IO,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: RouteRequest) = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 12_345.0, 1_500.0))
                override fun route(request: RouteRequest) = routeDetailed(request).plan
                override fun close() = Unit
            }
        },
        userLocation = { LatLon(40.0, -3.0) }, showRoute = {}, clearRoute = {}, clock = { 0L },
        log = { _, _, _ -> },
    )

    private val launchState = NavLaunchState()
    private val calls = mutableListOf<String>()
    private val host = NavStartHost(launchState, onStart = { calls += "start" }, onSimulate = { calls += "simulate" })

    @After fun tearDown() = scope.cancel()

    /** The route is computed first (on its own thread) and the card is composed after, so it reads the final state. */
    private fun showWith(navStart: NavStartHost?) {
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        val end = System.nanoTime() + 5_000_000_000L
        while (route.state.status != RouteStatus.DONE) {
            check(System.nanoTime() < end) { "the route did not finish: ${route.state.status}" }
            Thread.sleep(10)
        }
        rule.setContent { MapasTheme(darkTheme = false) { RoutePanel(route, onUseLocation = {}, originSearch = {}, navStart = navStart) } }
    }

    @Test fun `Start and Simulate appear under the summary and call their actions`() {
        showWith(host)
        rule.onNodeWithTag("route_summary").assertIsDisplayed()
        rule.onNodeWithTag("route_start").assertIsDisplayed()
        rule.onNodeWithTag("route_simulate").assertIsDisplayed()
        rule.onNodeWithTag("route_start").performClick()
        rule.onNodeWithTag("route_simulate").performClick()
        assertEquals(listOf("start", "simulate"), calls)
    }

    @Test fun `without a navigation the card is the preview it always was`() {
        showWith(null)
        rule.onNodeWithTag("route_summary").assertIsDisplayed()
        rule.onNodeWithTag("route_start").assertDoesNotExist()
        rule.onNodeWithTag("route_start_block").assertDoesNotExist()
    }

    @Test fun `while the guided route is being calculated the buttons are off and a note says so`() {
        launchState.status = LaunchStatus.COMPUTING
        showWith(host)
        rule.onNodeWithTag("route_start").assertIsNotEnabled()
        rule.onNodeWithTag("route_simulate").assertIsNotEnabled()
        rule.onNodeWithTag("route_start_status").assertIsDisplayed()
    }

    @Test fun `a failed start explains why`() {
        launchState.status = LaunchStatus.FAILED
        launchState.failure = RouteFailure(RouteFailureKind.NEED_MORE_MAPS, RouteAdvice.DOWNLOAD_MAPS)
        showWith(host)
        rule.onNodeWithTag("route_start_status").assertIsDisplayed()
    }
}
