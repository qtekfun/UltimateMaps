package com.qtekfun.ultimatemaps.route

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.weather.WeatherWarning
import com.qtekfun.ultimatemaps.core.zbe.ZbeCrossing
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bug report: "a route from my location to Motril with Avoid tolls on closes the app". Whatever the cause, a route that
 * fails in ANY way (an exception in the core client, in the heights, in the low-emission matcher, in the warnings, in
 * the drawing, or an Error such as out of memory on the worker) has to end as a message on screen, never as an uncaught
 * exception on the main thread or on a worker. These tests make every step of the route pipeline throw.
 */
class RouteNeverClosesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val madrid = LatLon(40.3167, -3.7667) // Leganes
    private val motril = PlaceInfo("Motril", LatLon(36.7500, -3.5167))

    /** About 500 km of road in 30,000 points, like the real thing, from Leganes towards the Granada coast. */
    private val longLine = List(30_000) { i ->
        val t = i / 29_999.0
        LatLon(madrid.lat + (motril.point.lat - madrid.lat) * t, madrid.lon + (motril.point.lon - madrid.lon) * t + 0.05 * Math.sin(t * 40))
    }

    private fun plan(altitudes: List<Double> = emptyList()) = RoutePlan(longLine, 520_000.0, 19_000.0, altitudes = altitudes)

    private class Engine(val answer: (RouteRequest) -> RouteOutcome) : DetailedRoutingEngine {
        val requests = CopyOnWriteArrayList<RouteRequest>()
        override fun routeDetailed(request: RouteRequest): RouteOutcome {
            requests += request
            return answer(request)
        }
        override fun route(request: RouteRequest) = routeDetailed(request).plan
        override fun close() = Unit
    }

    private val noted = CopyOnWriteArrayList<String>()
    private val drawn = CopyOnWriteArrayList<Int>()

    private fun controller(
        engine: DetailedRoutingEngine,
        showRoute: (List<LatLon>) -> Unit = { drawn += it.size },
        clearRoute: () -> Unit = {},
        lowEmissionZones: (List<LatLon>) -> List<ZbeCrossing> = { emptyList() },
        weatherWarnings: (List<LatLon>) -> List<WeatherWarning> = { emptyList() },
        log: RouteLog = RouteLog { _, _, _ -> },
        showAlternatives: (List<List<LatLon>>) -> Unit = {},
        backend: RouteBackend = RouteBackend { _, _ -> engine },
    ) = RoutePreviewController(
        scope, Dispatchers.IO, Regions(CoreMaps(File("/maps"), 3)),
        backend = backend,
        userLocation = { madrid },
        showRoute = showRoute,
        clearRoute = clearRoute,
        clock = { System.nanoTime() / 1_000_000 },
        log = log,
        showAlternatives = showAlternatives,
        lowEmissionZones = lowEmissionZones,
        weatherWarnings = weatherWarnings,
        onFailure = { where, e -> noted += "$where:${e.javaClass.simpleName}" },
    )

    private class Regions(var maps: CoreMaps?) : InstalledRegions {
        override fun coreMaps() = maps
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun aLongRouteWithAvoidTollsIsRequestedWithTheTollFlagAndShown() {
        val engine = Engine { RouteOutcome(RouteCode.NO_ERROR, plan(List(longLine.size) { 600.0 + it % 50 })) }
        val c = controller(engine)
        c.setOptions(RouteOptions(avoidTolls = true))
        c.start(motril)
        await("route") { c.state.status == RouteStatus.DONE }
        val sent = engine.requests.single()
        assertTrue(sent.options.avoidTolls && !sent.options.avoidMotorways)
        assertEquals(2, sent.options.toFlags()) // um::kAvoidToll
        assertEquals(RoutingProfile.CAR, sent.profile)
        assertNotNull(c.state.elevation)
        assertEquals(listOf(30_000), drawn.toList())
    }

    @Test
    fun anExceptionInEveryOptionalStepStillShowsTheRoute() {
        val engine = Engine { RouteOutcome(RouteCode.NO_ERROR, plan(List(longLine.size) { 600.0 + it % 50 })) }
        var draws = 0
        val c = controller(
            engine,
            showRoute = { draws++; throw IllegalStateException("map view gone") },
            lowEmissionZones = { throw ArrayIndexOutOfBoundsException(7) },
            weatherWarnings = { throw NullPointerException() },
        )
        c.start(motril)
        await("route") { c.state.status == RouteStatus.DONE }
        assertEquals(1, draws)
        assertTrue(noted.containsAll(listOf("zones:ArrayIndexOutOfBoundsException", "weather:NullPointerException", "draw:IllegalStateException")), noted.toString())
        assertEquals(520_000.0, c.state.distanceMeters)
    }

    @Test
    fun aMalformedAltitudeListIsIgnoredNotFatal() {
        // Wrong size: the profile is simply absent.
        val engine = Engine { RouteOutcome(RouteCode.NO_ERROR, plan(listOf(1.0, 2.0, 3.0))) }
        val c = controller(engine)
        c.start(motril)
        await("route") { c.state.status == RouteStatus.DONE }
        assertEquals(null, c.state.elevation)
        assertTrue(noted.isEmpty())
    }

    @Test
    fun anErrorOnTheWorkerIsAnInternalFailureNotAProcessCrash() {
        val c = controller(Engine { throw OutOfMemoryError("route too big") })
        c.start(motril)
        await("failure") { c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.INTERNAL, c.state.error)
        assertEquals(listOf("native:OutOfMemoryError"), noted.toList())
    }

    @Test
    fun aBackendThatCannotOpenIsAnInternalFailure() {
        val c = controller(Engine { error("unused") }, backend = RouteBackend { _, _ -> throw UnsatisfiedLinkError("libumcomaps.so") })
        c.start(motril)
        await("failure") { c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.INTERNAL, c.state.error)
    }

    @Test
    fun anExceptionWhileFinishingTheRouteIsTheLastResortState() {
        // The logger is called by the controller itself, outside the guarded optional steps: the last-resort catch has it.
        var first = true
        val c = controller(
            Engine { RouteOutcome(RouteCode.NO_ERROR, plan()) },
            log = RouteLog { _, _, _ -> if (first) { first = false; throw IllegalStateException("log gone") } },
        )
        c.start(motril)
        await("last resort") { c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.UNEXPECTED, c.state.error)
        assertEquals(listOf("route:IllegalStateException"), noted.toList())
    }

    @Test
    fun theUserCanTryAgainAfterTheLastResort() {
        var fail = true
        val c = controller(
            Engine { RouteOutcome(RouteCode.NO_ERROR, plan()) },
            log = RouteLog { _, _, _ -> if (fail) { fail = false; throw IllegalStateException() } },
        )
        c.start(motril)
        await("last resort") { c.state.status == RouteStatus.ERROR }
        c.setProfile(RoutingProfile.FOOT) // any change recomputes
        await("second try") { c.state.status == RouteStatus.DONE }
        assertEquals(null, c.state.error)
    }

    @Test
    fun alternativesThatThrowLeaveTheMainRouteAlone() {
        val engine = Engine { r ->
            if (r.options.avoidMotorways) throw IllegalArgumentException("boom") else RouteOutcome(RouteCode.NO_ERROR, plan())
        }
        val c = controller(engine)
        c.start(motril)
        await("route") { c.state.status == RouteStatus.DONE }
        c.findAlternatives()
        await("alternatives") { c.state.alternativesStatus == AlternativesStatus.DONE }
        assertEquals(RouteStatus.DONE, c.state.status)
        assertEquals(null, c.state.error)
        // The first alternative (avoid motorways) failed inside runNative; the second (avoid tolls) came back identical and was dropped.
        assertTrue(c.state.alternatives.isEmpty())
    }

    @Test
    fun anExceptionWhileDrawingAlternativesIsNotFatal() {
        val engine = Engine { r ->
            val line = if (r.options.avoidMotorways) longLine.reversed() else longLine
            RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 530_000.0, 20_000.0))
        }
        var drawCalls = 0
        val c = controller(engine, showAlternatives = { drawCalls++; throw IllegalStateException("map gone") })
        c.start(motril)
        await("route") { c.state.status == RouteStatus.DONE }
        c.findAlternatives()
        await("alternatives") { c.state.alternativesStatus == AlternativesStatus.DONE }
        assertEquals(1, c.state.alternatives.size)
        assertTrue(drawCalls > 0 && noted.any { it.startsWith("draw-alternatives:") })
    }

    @Test
    fun everyRouterCodeEndsInAMessageAndNeverThrows() {
        // CoMaps' RouterResultCode 0..16, the app's own codes, and values nobody has defined yet.
        val codes = (0..16) + listOf(RouteCode.NO_CYCLE_ROUTE, RouteCode.CORE_CRASHED, RouteCode.CORE_UNAVAILABLE, RouteCode.CORE_INTERNAL, 999, -1)
        for (code in codes) {
            val c = controller(Engine { RouteOutcome(code, null) })
            c.start(motril)
            await("code $code") { c.state.status != RouteStatus.COMPUTING }
            if (code == RouteCode.NO_ERROR || code == RouteCode.HAS_WARNINGS) {
                // "Succeeded" without a plan is still a failure the user can read.
                assertEquals(RouteStatus.ERROR, c.state.status, "code $code")
                assertEquals(RouteError.ROUTE_NOT_FOUND, c.state.error, "code $code")
            } else {
                assertEquals(RouteStatus.ERROR, c.state.status, "code $code")
                assertNotNull(c.state.error, "code $code")
            }
            c.close()
        }
        assertEquals(RouteError.NEED_MORE_MAPS, RoutePreviewController.errorFor(RouteOutcome(RouteCode.NEED_MORE_MAPS, null)))
        assertTrue(noted.isEmpty(), noted.toString())
    }
}
