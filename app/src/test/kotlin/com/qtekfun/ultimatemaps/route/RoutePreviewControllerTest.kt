package com.qtekfun.ultimatemaps.route

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.BikeCycleways
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePreviewControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val home = LatLon(40.4168, -3.7038)
    private val dest = PlaceInfo("Secret Cafe", LatLon(41.3851, 2.1734))
    private val line = listOf(LatLon(40.4168, -3.7038), LatLon(40.9, -1.0), LatLon(41.3851, 2.1734))

    private fun ok(distance: Double = 620_000.0) = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, distance, 21_600.0))

    private class FakeEngine(val answer: (RouteRequest) -> RouteOutcome) : DetailedRoutingEngine {
        val requests = CopyOnWriteArrayList<RouteRequest>()
        override fun routeDetailed(request: RouteRequest): RouteOutcome {
            requests += request
            return answer(request)
        }
        override fun route(request: RouteRequest) = routeDetailed(request).plan
        override fun close() = Unit
    }

    private class FakeLog : RouteLog {
        val events = CopyOnWriteArrayList<String>()
        override fun computed(profile: RoutingProfile, millis: Long, result: String) {
            events += "${profile.name.lowercase()} ms=$millis $result"
        }
    }

    private class Regions(var maps: CoreMaps?) : InstalledRegions {
        override fun coreMaps() = maps
    }

    private val installed = CoreMaps(File("/maps"), 2)
    private val ticks = AtomicLong()
    private val log = FakeLog()
    private val drawn = CopyOnWriteArrayList<List<LatLon>>()
    private val clears = AtomicInteger()
    private val opens = AtomicInteger()
    private var location: LatLon? = home

    private fun controller(
        engine: FakeEngine,
        regions: Regions = Regions(installed),
        timeoutMs: Long = 5_000,
        defaultBike: BikeCycleways = BikeCycleways.OFF,
    ) =
        RoutePreviewController(
            scope, Dispatchers.IO, regions,
            backend = { _, _ -> opens.incrementAndGet(); engine },
            userLocation = { location },
            showRoute = { drawn += it },
            clearRoute = { clears.incrementAndGet() },
            clock = { ticks.addAndGet(7) },
            log = log,
            timeoutMs = timeoutMs,
            defaultBikeCycleways = { defaultBike },
        )

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun successDrawsTheRouteAndReportsDistanceAndTime() {
        val release = CountDownLatch(1)
        val engine = FakeEngine { release.await(5, TimeUnit.SECONDS); ok() }
        val c = controller(engine)
        c.start(dest)
        assertEquals(RouteStatus.COMPUTING, c.state.status)
        release.countDown()
        await("route") { c.state.status == RouteStatus.DONE }
        assertEquals(620_000.0, c.state.distanceMeters)
        assertEquals(21_600.0, c.state.durationSeconds)
        assertEquals(listOf(line), drawn.toList())
        assertEquals(RouteRequest(home, dest.point), engine.requests.single())
        assertEquals(listOf("car ms=7 ok"), log.events.toList())
    }

    @Test
    fun everyCoreCodeHasItsOwnError() {
        val expected = mapOf(
            RouteCode.NEED_MORE_MAPS to RouteError.NEED_MORE_MAPS,
            RouteCode.START_NOT_FOUND to RouteError.START_NOT_FOUND,
            RouteCode.END_NOT_FOUND to RouteError.END_NOT_FOUND,
            RouteCode.ROUTE_NOT_FOUND to RouteError.ROUTE_NOT_FOUND,
            RouteCode.INTERMEDIATE_NOT_FOUND to RouteError.STOP_NOT_FOUND,
            RouteCode.CANCELLED to RouteError.TIMEOUT,
            RouteCode.INTERNAL_ERROR to RouteError.INTERNAL,
            99 to RouteError.INTERNAL,
        )
        for ((code, error) in expected) {
            val c = controller(FakeEngine { RouteOutcome(code, null) })
            c.start(dest)
            await("error $code") { c.state.status == RouteStatus.ERROR }
            assertEquals(error, c.state.error, "code $code")
            assertTrue(drawn.isEmpty())
            assertEquals("car ms=7 ${error.name.lowercase()}", log.events.last())
        }
    }

    @Test
    fun anEmptyPlanCountsAsNoRoute() {
        val c = controller(FakeEngine { RouteOutcome(RouteCode.NO_ERROR, RoutePlan(emptyList(), 0.0, 0.0)) })
        c.start(dest)
        await("error") { c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.ROUTE_NOT_FOUND, c.state.error)
    }

    @Test
    fun noInstalledRegionsAndEngineFailuresAreReported() {
        val none = controller(FakeEngine { ok() }, Regions(null))
        none.start(dest)
        await("no regions") { none.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.NO_REGIONS, none.state.error)

        val boom = controller(FakeEngine { error("jni exploded") })
        boom.start(dest)
        await("failure") { boom.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.INTERNAL, boom.state.error)
        assertTrue(log.events.none { "jni" in it })
    }

    @Test
    fun changingTheProfileCancelsTheRunningRouteAndRecomputes() {
        val gate = CountDownLatch(1)
        val engine = FakeEngine { r ->
            if (r.profile == RoutingProfile.CAR) { gate.await(5, TimeUnit.SECONDS); ok(1.0) } else ok(2.0)
        }
        val c = controller(engine)
        c.start(dest)
        await("car started") { engine.requests.size == 1 }
        c.setProfile(RoutingProfile.FOOT) // serialised behind the car call, which cannot be interrupted
        assertEquals(RoutingProfile.FOOT, c.state.profile)
        gate.countDown()
        await("foot route") { c.state.status == RouteStatus.DONE }
        assertEquals(2.0, c.state.distanceMeters) // the cancelled car result never reaches the state
        assertEquals(listOf(RoutingProfile.CAR, RoutingProfile.FOOT), engine.requests.map { it.profile })
        assertEquals(1, drawn.size)
        await("car cancellation logged") { log.events.any { it.startsWith("car") } }
        assertTrue(log.events.any { it.startsWith("car") && it.endsWith("cancelled") }, log.events.toString())
        assertTrue(log.events.any { it.startsWith("foot") && it.endsWith("ok") })
        c.setProfile(RoutingProfile.FOOT) // same profile: nothing new
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun avoidOptionsReachTheEngine() {
        val engine = FakeEngine { ok() }
        val c = controller(engine)
        c.start(dest)
        await("first") { c.state.status == RouteStatus.DONE }
        val options = RouteOptions(avoidMotorways = true, avoidTolls = true, avoidFerries = true, avoidUnpaved = true)
        c.setOptions(options)
        await("second") { engine.requests.size == 2 && c.state.status == RouteStatus.DONE }
        assertEquals(options, engine.requests.last().options)
        assertEquals(1, opens.get()) // the engine is reused
    }

    @Test
    fun aRouteThatNeverReturnsTimesOutAndFreesTheUi() {
        val gate = CountDownLatch(1)
        val engine = FakeEngine { gate.await(5, TimeUnit.SECONDS); ok() }
        val c = controller(engine, timeoutMs = 100)
        c.start(dest)
        await("timeout") { c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.TIMEOUT, c.state.error)
        assertTrue(drawn.isEmpty())
        assertTrue(log.events.single().endsWith("timeout"))
        gate.countDown() // the late native result is dropped
        Thread.sleep(50)
        assertEquals(RouteStatus.ERROR, c.state.status)
        assertTrue(drawn.isEmpty())
    }

    @Test
    fun closingWhileCalculatingCancelsAndClears() {
        val gate = CountDownLatch(1)
        val engine = FakeEngine { gate.await(5, TimeUnit.SECONDS); ok() }
        val c = controller(engine)
        c.start(dest)
        await("started") { engine.requests.size == 1 }
        c.close()
        assertEquals(RouteStatus.IDLE, c.state.status)
        assertTrue(!c.state.active)
        gate.countDown()
        await("cancel logged") { log.events.any { it.endsWith("cancelled") } }
        assertTrue(drawn.isEmpty())
        assertEquals(RouteStatus.IDLE, c.state.status)
        assertTrue(clears.get() >= 1)
    }

    @Test
    fun withoutLocationTheOriginCanBePickedBySearchOrTap() {
        location = null
        val engine = FakeEngine { ok() }
        val c = controller(engine)
        c.start(dest)
        assertEquals(RouteStatus.NEEDS_ORIGIN, c.state.status)
        assertTrue(engine.requests.isEmpty())

        val tap = LatLon(40.5, -3.5)
        c.pickOrigin(tap, null) // not asked to pick: a stray tap is ignored
        assertEquals(RouteStatus.NEEDS_ORIGIN, c.state.status)

        c.beginPickOrigin()
        c.pickOrigin(tap, "Atocha")
        assertEquals(RouteOrigin.Picked(tap, "Atocha"), c.state.origin)
        await("route") { c.state.status == RouteStatus.DONE }
        assertEquals(tap, engine.requests.single().from)
    }

    @Test
    fun aLateLocationFixRetriesTheRoute() {
        location = null
        val engine = FakeEngine { ok() }
        val c = controller(engine)
        c.start(dest)
        assertEquals(RouteStatus.NEEDS_ORIGIN, c.state.status)
        location = home
        c.onUserLocation()
        await("route") { c.state.status == RouteStatus.DONE }
        assertEquals(home, engine.requests.single().from)

        c.onUserLocation() // already done: no recalculation per fix
        Thread.sleep(30)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun logsCarryNeitherPositionsNorNames() {
        val c = controller(FakeEngine { ok() })
        c.start(dest)
        await("route") { c.state.status == RouteStatus.DONE }
        val text = log.events.joinToString("\n")
        assertNull(Regex("""\d+\.\d{3,}""").find(text))
        assertTrue("Secret" !in text && "40.41" !in text && "2.17" !in text)
    }

    @Test
    fun theNoCycleRouteCodeHasItsOwnErrorAndTheLevelReachesTheEngine() {
        assertEquals(RouteError.NO_CYCLE_ROUTE, RoutePreviewController.errorFor(RouteOutcome(RouteCode.NO_CYCLE_ROUTE, null)))
        val engine = FakeEngine { RouteOutcome(RouteCode.NO_CYCLE_ROUTE, null) }
        val c = controller(engine)
        c.setProfile(RoutingProfile.BIKE)
        c.start(dest)
        await("first error") { c.state.status == RouteStatus.ERROR }
        c.setOptions(RouteOptions(bikeCycleways = BikeCycleways.ONLY))
        await("second error") { engine.requests.size == 2 && c.state.status == RouteStatus.ERROR }
        assertEquals(RouteError.NO_CYCLE_ROUTE, c.state.error)
        assertEquals(BikeCycleways.ONLY, engine.requests.last().options.bikeCycleways)
        assertEquals(RoutingProfile.BIKE, engine.requests.last().profile)
    }

    @Test
    fun aNewPreviewStartsFromTheSettingsDefaultAndARunningOneKeepsTheUsersChoice() {
        val engine = FakeEngine { ok() }
        val c = controller(engine, defaultBike = BikeCycleways.PREFER)
        c.start(dest)
        await("first") { c.state.status == RouteStatus.DONE }
        assertEquals(BikeCycleways.PREFER, c.state.options.bikeCycleways)
        c.setOptions(c.state.options.copy(bikeCycleways = BikeCycleways.ONLY))
        await("second") { engine.requests.size == 2 && c.state.status == RouteStatus.DONE }
        c.start(dest) // already active: the choice for this trip stays
        await("third") { engine.requests.size == 3 && c.state.status == RouteStatus.DONE }
        assertEquals(BikeCycleways.ONLY, engine.requests.last().options.bikeCycleways)
        c.close()
        c.start(dest) // a new session goes back to the default
        await("fourth") { engine.requests.size == 4 && c.state.status == RouteStatus.DONE }
        assertEquals(BikeCycleways.PREFER, engine.requests.last().options.bikeCycleways)
    }
}
