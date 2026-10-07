package com.qtekfun.mapas.route

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.search.CoreMaps
import com.qtekfun.mapas.search.InstalledRegions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteStopsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home = LatLon(40.4168, -3.7038)
    private val dest = PlaceInfo("Destino", LatLon(41.3851, 2.1734))
    private val s1 = PlaceInfo("Parada 1", LatLon(41.0, -1.0))
    private val s2 = PlaceInfo("Parada 2", LatLon(41.1, 0.0))
    private val s3 = PlaceInfo("Parada 3", LatLon(41.2, 1.0))
    private val line = listOf(home, LatLon(40.9, -1.0), dest.point)

    private class FakeEngine(val answer: (RouteRequest) -> RouteOutcome) : DetailedRoutingEngine {
        val requests = CopyOnWriteArrayList<RouteRequest>()
        override fun routeDetailed(request: RouteRequest): RouteOutcome {
            requests += request
            return answer(request)
        }
        override fun route(request: RouteRequest) = routeDetailed(request).plan
        override fun close() = Unit
    }

    private class Log : RouteLog {
        val events = CopyOnWriteArrayList<String>()
        override fun computed(profile: RoutingProfile, millis: Long, result: String) { events += "plain:$result" }
        override fun computed(profile: RoutingProfile, millis: Long, result: String, stops: Int) { events += "stops=$stops:$result" }
    }

    private val log = Log()
    private val engine = FakeEngine { r -> RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 100_000.0 * (1 + r.via.size), 3_600.0 * (1 + r.via.size))) }

    private fun controller(e: FakeEngine = engine) = RoutePreviewController(
        scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ -> e }, userLocation = { home }, showRoute = {}, clearRoute = {}, clock = { 5L }, log = log,
        timeoutMs = 5_000,
    )

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun started(): RoutePreviewController {
        val c = controller()
        c.start(dest)
        await("first route") { c.state.status == RouteStatus.DONE }
        return c
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun withoutAnActiveRouteAStopIsRefused() {
        val c = controller()
        assertEquals(StopResult.NO_ROUTE, c.addStop(s1))
        assertTrue(c.state.stops.isEmpty())
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun aStopGoesBeforeTheDestinationAndTheRouteIsRecalculatedWithVia() {
        val c = started()
        assertEquals(StopResult.ADDED, c.addStop(s1))
        await("recalculated") { engine.requests.size == 2 && c.state.status == RouteStatus.DONE }
        assertEquals(listOf(s1), c.state.stops)
        assertEquals(RouteRequest(home, dest.point, via = listOf(s1.point)), engine.requests.last())
        assertEquals(200_000.0, c.state.distanceMeters) // the total of the whole route, as returned by the engine

        assertEquals(StopResult.ADDED, c.addStop(s2))
        await("third") { engine.requests.size == 3 && c.state.status == RouteStatus.DONE }
        assertEquals(listOf(s1.point, s2.point), engine.requests.last().via) // new stops append, destination stays last
        assertEquals(dest.point, engine.requests.last().to)
    }

    @Test
    fun aDuplicateStopAndAStopEqualToTheDestinationAreRefusedWithoutRecalculating() {
        val c = started()
        c.addStop(s1)
        await("two") { engine.requests.size == 2 && c.state.status == RouteStatus.DONE }
        val near = PlaceInfo("Same place, another name", LatLon(s1.point.lat + 0.0001, s1.point.lon)) // ~11 m away
        assertEquals(StopResult.DUPLICATE, c.addStop(near))
        assertEquals(StopResult.SAME_AS_DESTINATION, c.addStop(PlaceInfo("x", dest.point)))
        Thread.sleep(100)
        assertEquals(2, engine.requests.size)
        assertEquals(listOf(s1), c.state.stops)
    }

    @Test
    fun theStopLimitIsEnforcedWithItsOwnResult() {
        val c = started()
        repeat(RoutePreviewController.MAX_STOPS) { i ->
            assertEquals(StopResult.ADDED, c.addStop(PlaceInfo("p$i", LatLon(41.0 + i * 0.1, -1.0 + i * 0.1))))
        }
        assertEquals(StopResult.LIMIT, c.addStop(PlaceInfo("one more", LatLon(42.0, 1.5))))
        assertEquals(RoutePreviewController.MAX_STOPS, c.state.stops.size)
    }

    @Test
    fun removeAndReorderRecalculateInTheNewOrder() {
        val c = started()
        c.addStop(s1); c.addStop(s2); c.addStop(s3)
        await("three stops") { c.state.status == RouteStatus.DONE && engine.requests.last().via.size == 3 }

        c.moveStop(2, -1) // s3 before s2
        await("reordered") { engine.requests.last().via == listOf(s1.point, s3.point, s2.point) }
        c.moveStop(0, -1) // already first: ignored
        c.moveStop(2, 1) // already last: ignored
        c.moveStop(7, 1) // out of range: ignored
        val before = engine.requests.size
        c.removeStop(0)
        await("removed") { engine.requests.last().via == listOf(s3.point, s2.point) }
        assertEquals(listOf(s3, s2), c.state.stops)
        assertEquals(before + 1, engine.requests.size)
        c.removeStop(9) // ignored
        c.removeStop(0); c.removeStop(0)
        await("none") { c.state.stops.isEmpty() && c.state.status == RouteStatus.DONE && engine.requests.last().via.isEmpty() }
    }

    @Test
    fun aNewDestinationOrClosingDropsTheStops() {
        val c = started()
        c.addStop(s1)
        c.start(PlaceInfo("Otro", LatLon(39.0, -0.5)))
        assertTrue(c.state.stops.isEmpty())
        await("route") { c.state.status == RouteStatus.DONE }
        c.addStop(s2)
        c.close()
        assertTrue(c.state.stops.isEmpty())
        assertEquals(StopResult.NO_ROUTE, c.addStop(s2))
    }

    @Test
    fun anUnreachableStopHasItsOwnError() {
        val c = controller(FakeEngine { r ->
            if (r.via.isEmpty()) RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 1.0, 1.0)) else RouteOutcome(RouteCode.INTERMEDIATE_NOT_FOUND, null)
        })
        c.start(dest)
        await("ok") { c.state.status == RouteStatus.DONE }
        c.addStop(s1)
        await("error") { c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.STOP_NOT_FOUND, c.state.error)
        c.removeStop(0) // removing it recovers
        await("recovered") { c.state.status == RouteStatus.DONE }
    }

    @Test
    fun latencyLogCarriesTheStopCountButNoPlaces() {
        val c = started()
        c.addStop(s1)
        await("two logs") { log.events.size == 2 && c.state.status == RouteStatus.DONE }
        assertEquals(listOf("stops=0:ok", "stops=1:ok"), log.events.toList())
        assertTrue(log.events.none { "Parada" in it || "41." in it })
    }
}
