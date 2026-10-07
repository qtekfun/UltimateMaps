package com.qtekfun.mapas.route

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.core.routing.RouteOptions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.search.CoreMaps
import com.qtekfun.mapas.search.InstalledRegions
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
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val line = listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1))
    private var outcome = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 12_345.0, 1_500.0))
    private val profiles = mutableListOf<RoutingProfile>()

    private val route = RoutePreviewController(
        scope, Dispatchers.IO,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine {
                override fun routeDetailed(request: com.qtekfun.mapas.core.routing.RouteRequest): RouteOutcome {
                    profiles += request.profile
                    return outcome
                }
                override fun route(request: com.qtekfun.mapas.core.routing.RouteRequest) = outcome.plan
                override fun close() = Unit
            }
        },
        userLocation = { LatLon(40.0, -3.0) }, showRoute = {}, clearRoute = {}, clock = { 0L },
        log = { _, _, _ -> },
    )

    @After fun tearDown() = scope.cancel()

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) { RoutePanel(route, onUseLocation = {}, originSearch = {}) }
    }

    /**
     * Waits (without touching the UI) for the controller to finish the computation on its background thread. The tests compose the
     * screen AFTER, so that the first composition already reads the final state: waiting for a recomposition triggered
     * from another thread made the test fail 1 time out of 2 in the full suite (diagnosis: the state was
     * ERROR but the screen was still on "Calculating…").
     */
    private fun settle(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "the computation did not finish in 5 s: ${route.state.status}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun showsSummaryProfilesAndClosesTheRoute() {
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        settle { route.state.status == RouteStatus.DONE }
        show()
        rule.onNodeWithText("To Plaza Mayor").assertIsDisplayed()
        rule.onNodeWithTag("route_summary").assertIsDisplayed()
        rule.onNodeWithText("25 min").assertIsDisplayed() // the time first, large...
        rule.onNodeWithText("12.3 km").assertIsDisplayed() // ...then the distance

        // Switch profile: the click and the profile are on the main thread (deterministic); the computation, in the background.
        rule.onNodeWithTag("profile_foot").performClick()
        settle { profiles.size == 2 && route.state.status == RouteStatus.DONE }
        assertEquals(listOf(RoutingProfile.CAR, RoutingProfile.FOOT), profiles)
        rule.onNodeWithTag("route_options_toggle").performClick() // the options are collapsed by default
        rule.onNodeWithTag("avoid_motorways").assertIsNotEnabled() // car-only option

        rule.onNodeWithTag("route_close").performClick()
        assertEquals(false, route.state.active)
    }

    @Test
    fun explainsNeedMoreMaps() {
        outcome = RouteOutcome(RouteCode.NEED_MORE_MAPS, null)
        route.start(PlaceInfo("Far", LatLon(10.0, 10.0)))
        settle { route.state.status == RouteStatus.ERROR }
        show()
        rule.onNodeWithText("Maps are missing for this route. Download the regions it crosses.").assertIsDisplayed()
    }

    @Test
    fun listsStopsWithTotalsAndMovesAndRemovesThem() {
        route.start(PlaceInfo("Destino", LatLon(40.1, -3.1)))
        settle { route.state.status == RouteStatus.DONE }
        route.addStop(PlaceInfo("Uno", LatLon(40.02, -3.02)))
        route.addStop(PlaceInfo("Dos", LatLon(40.05, -3.05)))
        settle { route.state.status == RouteStatus.DONE }
        show()
        rule.onNodeWithText("Stops (2 of 5)").assertIsDisplayed()
        rule.onNodeWithText("1. Uno").assertIsDisplayed()
        rule.onNodeWithText("2. Dos").assertIsDisplayed()
        rule.onNodeWithText("25 min").assertIsDisplayed() // total of the whole route
        rule.onNodeWithText("12.3 km").assertIsDisplayed()
        rule.onNodeWithTag("route_stop_up_0").assertIsNotEnabled()
        rule.onNodeWithTag("route_stop_down_1").assertIsNotEnabled()

        rule.onNodeWithTag("route_stop_down_0").performClick()
        assertEquals(listOf("Dos", "Uno"), route.state.stops.map { it.name })
        rule.onNodeWithTag("route_stop_remove_0").performClick()
        assertEquals(listOf("Uno"), route.state.stops.map { it.name })
        settle { route.state.status == RouteStatus.DONE }
    }

    @Test
    fun travelModeIsASingleSelectionGroupOfRadioButtons() {
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        settle { route.state.status == RouteStatus.DONE }
        show()
        rule.onNodeWithTag("profile_car").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithTag("profile_foot").assertIsNotSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithTag("profile_bike").assertIsNotSelected()
    }

    @Test
    fun closeIsAnIconWithADescriptionAndATouchTarget() {
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        settle { route.state.status == RouteStatus.DONE }
        show()
        rule.onNodeWithTag("route_close").assertContentDescriptionEquals("Close").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun optionsAreCollapsedAndTheHeaderSummarisesWhatIsAvoided() {
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        settle { route.state.status == RouteStatus.DONE }
        route.setOptions(RouteOptions(avoidTolls = true, avoidFerries = true))
        settle { profiles.size == 2 && route.state.status == RouteStatus.DONE }
        show()
        rule.onNodeWithTag("avoid_tolls").assertDoesNotExist()
        rule.onNodeWithText("Avoiding: tolls, ferries").assertIsDisplayed()

        rule.onNodeWithTag("route_options_toggle").performClick()
        rule.onNodeWithTag("avoid_tolls").assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        rule.onNodeWithTag("avoid_unpaved").assertIsOff()
    }
}
