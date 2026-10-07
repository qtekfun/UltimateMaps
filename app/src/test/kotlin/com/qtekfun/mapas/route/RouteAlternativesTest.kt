package com.qtekfun.mapas.route

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteOptions
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine
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
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alternative routes with a fake engine on Unconfined dispatchers: every step runs inline, no real time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RouteAlternativesTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val home = LatLon(40.0, -3.0)
    private val dest = PlaceInfo("Plaza Mayor", LatLon(40.2, -3.2))

    private val mainLine = listOf(home, LatLon(40.1, -3.1), dest.point)
    private val noMotorway = listOf(home, LatLon(40.05, -3.2), dest.point)
    private val noTolls = listOf(home, LatLon(40.2, -3.0), dest.point)

    /** What the "native" side answers per option set; null = the route fails. */
    private var answers: (RouteOptions) -> RoutePlan? = { o ->
        when {
            o.avoidMotorways -> RoutePlan(noMotorway, 30_000.0, 2_400.0) // 40 min, 30 km
            o.avoidTolls -> RoutePlan(noTolls, 26_000.0, 1_500.0)
            else -> RoutePlan(mainLine, 25_000.0, 1_200.0) // 20 min, 25 km
        }
    }
    private val requests = mutableListOf<RouteRequest>()
    private val mainDrawn = mutableListOf<List<LatLon>>()
    private val altDrawn = mutableListOf<List<List<LatLon>>>()

    private val route = RoutePreviewController(
        scope, Dispatchers.Unconfined,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: RouteRequest): RouteOutcome {
                    requests += request
                    val plan = answers(request.options)
                    return if (plan != null) RouteOutcome(RouteCode.NO_ERROR, plan) else RouteOutcome(RouteCode.ROUTE_NOT_FOUND, null)
                }
                override fun route(request: RouteRequest) = routeDetailed(request).plan
                override fun close() = Unit
            }
        },
        userLocation = { home },
        showRoute = { mainDrawn += it },
        clearRoute = {},
        clock = { 0L },
        log = { _, _, _ -> },
        showAlternatives = { altDrawn += it },
    )

    @After fun tearDown() = scope.cancel()

    private fun startMain() {
        route.start(dest)
        assertEquals(RouteStatus.DONE, route.state.status)
    }

    @Test fun carAlternativesAreMotorwayAndTollsAndSkipWhatIsAlreadyOn() {
        assertEquals(
            listOf(AlternativeKind.AVOID_MOTORWAYS, AlternativeKind.AVOID_TOLLS),
            RoutePreviewController.alternativeKinds(RoutingProfile.CAR, RouteOptions()),
        )
        assertEquals(
            listOf(AlternativeKind.AVOID_TOLLS),
            RoutePreviewController.alternativeKinds(RoutingProfile.CAR, RouteOptions(avoidMotorways = true)),
        )
        assertEquals(
            listOf(AlternativeKind.AVOID_UNPAVED, AlternativeKind.AVOID_FERRIES),
            RoutePreviewController.alternativeKinds(RoutingProfile.BIKE, RouteOptions(avoidMotorways = true)),
        )
        assertTrue(
            RoutePreviewController.alternativeKinds(RoutingProfile.FOOT, RouteOptions(avoidUnpaved = true, avoidFerries = true)).isEmpty(),
        )
    }

    @Test fun nothingExtraIsCalculatedUntilAsked() {
        startMain()
        assertEquals(1, requests.size)
        assertEquals(AlternativesStatus.NONE, route.state.alternativesStatus)
    }

    @Test fun findingAlternativesAddsOneRoutePerRestrictionAndDrawsThemLighter() {
        startMain()
        route.findAlternatives()
        assertEquals(AlternativesStatus.DONE, route.state.alternativesStatus)
        assertEquals(listOf(AlternativeKind.AVOID_MOTORWAYS, AlternativeKind.AVOID_TOLLS), route.state.alternatives.map { it.kind })
        assertEquals(RouteOptions(avoidMotorways = true), route.state.alternatives[0].options)
        assertEquals(RouteOptions(avoidTolls = true), route.state.alternatives[1].options)
        assertEquals(listOf(noMotorway, noTolls), altDrawn.last()) // the main route stays the selected, solid one
        assertNull(route.state.selectedAlternative)
    }

    @Test fun anIdenticalOrFailedRouteIsDropped() {
        answers = { o ->
            when {
                o.avoidMotorways -> RoutePlan(mainLine, 25_000.0, 1_200.0) // the motorway was not used: same geometry
                o.avoidTolls -> null // no route without tolls
                else -> RoutePlan(mainLine, 25_000.0, 1_200.0)
            }
        }
        startMain()
        route.findAlternatives()
        assertEquals(AlternativesStatus.DONE, route.state.alternativesStatus)
        assertTrue(route.state.alternatives.isEmpty())
        assertEquals(3, requests.size)
    }

    @Test fun selectingAnAlternativeSwapsTheFiguresTheLineAndTheStartRequest() {
        startMain()
        route.findAlternatives()
        route.selectAlternative(0)
        assertEquals(0, route.state.selectedAlternative)
        assertEquals(30_000.0, route.state.distanceMeters)
        assertEquals(2_400.0, route.state.durationSeconds)
        assertEquals(25_000.0, route.state.baseDistanceMeters) // the main figures stay for the deltas
        assertEquals(noMotorway, mainDrawn.last())
        assertEquals(listOf(mainLine, noTolls), altDrawn.last()) // the main route is now an alternative
        // "Start" and "Simulate" read this request: it must carry the restriction of the selected route.
        assertEquals(RouteOptions(avoidMotorways = true), route.currentRequest()!!.options)

        route.selectAlternative(null)
        assertEquals(25_000.0, route.state.distanceMeters)
        assertEquals(mainLine, mainDrawn.last())
        assertEquals(RouteOptions(), route.currentRequest()!!.options)
        route.selectAlternative(7) // out of range: ignored
        assertNull(route.state.selectedAlternative)
    }

    @Test fun anyNewCalculationDropsTheAlternatives() {
        startMain()
        route.findAlternatives()
        route.selectAlternative(1)
        route.setProfile(RoutingProfile.FOOT)
        assertTrue(route.state.alternatives.isEmpty())
        assertEquals(AlternativesStatus.NONE, route.state.alternativesStatus)
        assertNull(route.state.selectedAlternative)
        assertEquals(emptyList(), altDrawn.last())
        assertEquals(RouteOptions(), route.currentRequest()!!.options)
    }

    @Test fun deltaTextsAreSignedAndRoundedAndEmptyWhenTheyVanish() {
        assertEquals("+20 min", RouteDelta.duration(1_200.0))
        assertEquals("−1 h 5 min", RouteDelta.duration(-3_900.0))
        assertNull(RouteDelta.duration(20.0))
        assertEquals("+5.0 km", RouteDelta.distance(5_000.0, Locale.US))
        assertEquals("−350 m", RouteDelta.distance(-350.0, Locale.US))
        assertNull(RouteDelta.distance(3.0, Locale.US))
    }

    @Test fun panelOffersTheButtonThenListsRoutesWithDeltasAndSwitchesOnTap() {
        startMain()
        rule.setContent { MapasTheme(darkTheme = false) { AlternativesSection(route) } }
        rule.onNodeWithTag("route_alt_find").assertIsDisplayed()

        rule.onNodeWithTag("route_alt_find").performClick() // runs inline: the list is ready at once
        rule.waitForIdle()
        rule.onNodeWithTag("route_alt_main").assertIsDisplayed()
        rule.onNodeWithText("Avoiding motorways").assertIsDisplayed()
        rule.onNodeWithText("Avoiding tolls").assertIsDisplayed()
        rule.onNodeWithText("40 min · 30.0 km (+20 min, +5.0 km)").assertIsDisplayed()
        rule.onNodeWithText("25 min · 26.0 km (+5 min, +1.0 km)").assertIsDisplayed()

        rule.onNodeWithTag("route_alt_1").performClick()
        assertEquals(1, route.state.selectedAlternative)
        rule.onNodeWithTag("route_alt_main").performClick()
        assertNull(route.state.selectedAlternative)
    }

    @Test fun panelSaysSoWhenNoAlternativeExists() {
        answers = { RoutePlan(mainLine, 25_000.0, 1_200.0) }
        startMain()
        route.findAlternatives()
        rule.setContent { MapasTheme(darkTheme = false) { AlternativesSection(route) } }
        rule.onNodeWithText("No different route found with other restrictions.").assertIsDisplayed()
    }
}
