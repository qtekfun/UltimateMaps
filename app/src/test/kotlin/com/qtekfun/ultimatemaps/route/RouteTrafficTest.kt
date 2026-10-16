package com.qtekfun.ultimatemaps.route

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.traffic.TrafficEstimate
import com.qtekfun.ultimatemaps.core.traffic.TrafficOutcome
import com.qtekfun.ultimatemaps.core.traffic.TrafficProvider
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live traffic in the route preview, with a fake feed: no network, no real time (Unconfined dispatchers run every step inline). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteTrafficTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val home = LatLon(40.0, -3.0)
    private val dest = PlaceInfo("Plaza Mayor", LatLon(40.2, -3.2))
    private val mainLine = listOf(home, LatLon(40.1, -3.1), dest.point)
    private val noMotorway = listOf(home, LatLon(40.05, -3.2), dest.point)
    private val noTolls = listOf(home, LatLon(40.2, -3.0), dest.point)

    /** Extra seconds the feed adds per geometry (missing: free flow). */
    private val delays = mutableMapOf<List<LatLon>, Double>()
    private var feedEnabled = true
    private var feedOutcome: TrafficOutcome.Reason? = null
    private var asked = 0

    private val feed = object : TrafficProvider {
        override val enabled: Boolean get() = feedEnabled
        override suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)?): TrafficOutcome {
            asked++
            feedOutcome?.let { return TrafficOutcome.Unavailable(it) }
            val delay = delays[geometry] ?: 0.0
            return TrafficOutcome.Ready(TrafficEstimate(coreSeconds + delay, delay, if (delay > 0) 0.4 else 1.0, 0.0, 1.0))
        }
    }

    private fun controller(traffic: TrafficProvider?) = RoutePreviewController(
        scope, Dispatchers.Unconfined,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: RouteRequest): RouteOutcome {
                    val plan = when {
                        request.options.avoidMotorways -> RoutePlan(noMotorway, 30_000.0, 2_400.0)
                        request.options.avoidTolls -> RoutePlan(noTolls, 26_000.0, 1_500.0)
                        request.via.isNotEmpty() -> null
                        else -> RoutePlan(mainLine, 25_000.0, 1_200.0)
                    }
                    return if (plan != null) RouteOutcome(RouteCode.NO_ERROR, plan) else RouteOutcome(RouteCode.ROUTE_NOT_FOUND, null)
                }
                override fun route(request: RouteRequest) = routeDetailed(request).plan
                override fun close() = Unit
            }
        },
        userLocation = { home },
        showRoute = {},
        clearRoute = {},
        clock = { 0L },
        log = { _, _, _ -> },
        traffic = traffic ?: com.qtekfun.ultimatemaps.core.traffic.NoTrafficProvider,
    )

    @After fun tearDown() = scope.cancel()

    @Test fun withoutTrafficTheRouterTimeIsShownAndTheFeedIsNeverAsked() {
        val route = controller(null)
        route.start(dest)
        assertEquals(1_200.0, route.state.durationSeconds)
        assertNull(route.state.trafficDelaySeconds)
        assertEquals(0, asked)
    }

    @Test fun aSmallDelayCorrectsTheTimeButStartsNothingElse() {
        delays[mainLine] = 120.0
        val route = controller(feed)
        route.start(dest)
        assertEquals(1_320.0, route.state.durationSeconds)
        assertEquals(1_320.0, route.state.baseDurationSeconds)
        assertEquals(120.0, route.state.trafficDelaySeconds)
        assertEquals(AlternativesStatus.NONE, route.state.alternativesStatus)
    }

    @Test fun aBigJamFindsAlternativesAndShowsTheFastestWithTraffic() {
        delays[mainLine] = 600.0 // 30 min with the jam
        val route = controller(feed) // the toll-free road is 25 min and clear
        route.start(dest)
        assertEquals(AlternativesStatus.DONE, route.state.alternativesStatus)
        val i = route.state.alternatives.indexOfFirst { it.kind == AlternativeKind.AVOID_TOLLS }
        assertEquals(i, route.state.selectedAlternative)
        assertEquals(1_500.0, route.state.durationSeconds)
        assertEquals(1_800.0, route.state.baseDurationSeconds, "the comparison base is the main route with its jam")
        assertEquals(0.0, route.state.trafficDelaySeconds)
    }

    @Test fun aRouteTheOwnerChoseIsNotReplacedByTheAutomaticChoice() {
        delays[mainLine] = 600.0
        val route = controller(feed)
        route.start(dest)
        route.selectAlternative(null) // back to the main route by hand
        assertNull(route.state.selectedAlternative)
        assertEquals(1_800.0, route.state.durationSeconds)
        assertEquals(600.0, route.state.trafficDelaySeconds)
    }

    @Test fun aRejectedKeyOrTheLimitIsReportedAndTheRouterTimeStays() {
        feedOutcome = TrafficOutcome.Reason.BAD_KEY
        val route = controller(feed)
        route.start(dest)
        assertEquals(1_200.0, route.state.durationSeconds)
        assertEquals(TrafficOutcome.Reason.BAD_KEY, route.state.trafficProblem)
        feedOutcome = TrafficOutcome.Reason.OFFLINE // offline is not worth a message
        route.start(dest)
        assertNull(route.state.trafficProblem)
    }

    @Test fun walkingAndCyclingDoNotAskForTraffic() {
        val route = controller(feed)
        route.state.profile = RoutingProfile.FOOT
        route.start(dest)
        assertEquals(0, asked)
        assertNotNull(route.state.durationSeconds)
        assertTrue(route.state.trafficDelaySeconds == null)
        assertEquals(RouteOptions(), route.state.options)
    }
}
