package com.qtekfun.ultimatemaps.route

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.zbe.ZbeIndex
import com.qtekfun.ultimatemaps.core.zbe.ZbePolygon
import com.qtekfun.ultimatemaps.core.zbe.ZbeRing
import com.qtekfun.ultimatemaps.core.zbe.ZbeZone
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The low-emission-zone warning of the route summary: only for car routes, follows the selected alternative, empty without data. */
class ZbeRouteWarningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val home = LatLon(40.00, -3.00)
    private val dest = PlaceInfo("Office", LatLon(40.10, -3.00))

    // The zone sits west of the straight road at lon -3.0; the detour goes around it on the west... no: the zone is ON the main road.
    private val zone = ZbeZone("z", "Centro", "Madrid", "", listOf(ZbePolygon(listOf(ZbeRing(doubleArrayOf(40.04, -3.01, 40.04, -2.99, 40.06, -2.99, 40.06, -3.01))))))
    private val direct = listOf(home, LatLon(40.05, -3.00), dest.point)
    private val detour = listOf(home, LatLon(40.05, -2.95), dest.point)

    private var data: ZbeIndex = ZbeIndex(listOf(zone))
    private var asked = 0

    private val route = RoutePreviewController(
        scope, Dispatchers.Unconfined,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: RouteRequest): RouteOutcome {
                    val plan = if (request.options.avoidTolls) RoutePlan(detour, 14_000.0, 1_000.0) else RoutePlan(direct, 11_000.0, 900.0)
                    return RouteOutcome(RouteCode.NO_ERROR, plan)
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
        lowEmissionZones = { geometry -> asked++; data.crossings(geometry) },
    )

    @After fun tearDown() = scope.cancel()

    @Test fun aCarRouteThroughAZoneCarriesTheWarning() {
        route.start(dest)
        assertEquals(RouteStatus.DONE, route.state.status)
        val c = route.state.lowEmission.single()
        assertEquals("z", c.zone.id)
        assertTrue(c.entryMeters in 4_000.0..5_000.0, "${c.entryMeters}")
    }

    @Test fun theWarningFollowsTheSelectedAlternativeAndClearsOnClose() {
        route.start(dest)
        route.findAlternatives()
        val i = route.state.alternatives.indexOfFirst { it.options == RouteOptions(avoidTolls = true) }
        assertTrue(i >= 0)
        route.selectAlternative(i)
        assertTrue(route.state.lowEmission.isEmpty(), "the detour goes around the zone")
        route.selectAlternative(null)
        assertEquals(1, route.state.lowEmission.size)
        route.close()
        assertTrue(route.state.lowEmission.isEmpty())
    }

    @Test fun otherProfilesAreNotAskedAndNothingIsShown() {
        route.start(dest)
        asked = 0
        route.setProfile(RoutingProfile.BIKE)
        assertEquals(RouteStatus.DONE, route.state.status)
        assertTrue(route.state.lowEmission.isEmpty())
        route.setProfile(RoutingProfile.FOOT)
        assertTrue(route.state.lowEmission.isEmpty())
        assertEquals(0, asked, "the zone check is for cars only")
        route.setProfile(RoutingProfile.CAR)
        assertEquals(1, route.state.lowEmission.size)
    }

    @Test fun withoutZoneDataNothingIsShown() {
        data = ZbeIndex(emptyList())
        route.start(dest)
        assertEquals(RouteStatus.DONE, route.state.status)
        assertTrue(route.state.lowEmission.isEmpty())
    }
}
