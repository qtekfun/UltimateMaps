package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.route.RouteBackend
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Start" and "Simulate": the guided route is asked for, the stops go into the session, failures are explained. */
class NavLauncherTest {
    private val rig = NavTestRig()

    @After fun tearDown() = rig.close()

    private class Engine(var outcome: () -> RouteOutcome) : DetailedRoutingEngine {
        val requests = mutableListOf<RouteRequest>()
        override fun routeDetailed(request: RouteRequest): RouteOutcome { requests += request; return outcome() }
        override fun route(request: RouteRequest) = routeDetailed(request).plan
        override fun close() = Unit
    }

    private val opened = AtomicInteger()
    private var timeoutSeen = 0
    private var started = 0
    private val engine = Engine { RouteOutcome(RouteCode.NO_ERROR, cityPlan()) }
    private var request: RouteRequest? = RouteRequest(pt(0.0, 0.0), pt(1000.0, 1000.0), via = listOf(pt(250.0, 1000.0)), profile = RoutingProfile.BIKE)
    private var regions: CoreMaps? = CoreMaps(File("/maps"), 1)

    private val launcher = NavLauncher(
        scope = rig.scope,
        io = Dispatchers.IO,
        regions = object : InstalledRegions { override fun coreMaps() = regions },
        backend = RouteBackend { _, timeoutSec -> opened.incrementAndGet(); timeoutSeen = timeoutSec; engine },
        runner = RouteRunner(Dispatchers.IO, timeoutMillis = 30_000L),
        screen = rig.screen,
        mutex = Mutex(),
        request = { request },
        onStarted = { started++ },
    )

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what: ${launcher.state.status}" }
            Thread.sleep(5)
        }
    }

    @Test fun `Simulate asks for the guided route with the stops, hands it to the session with them, and closes the preview`() {
        launcher.start(simulate = true)
        waitFor("started") { started == 1 }
        assertEquals(1, opened.get())
        assertEquals(30, timeoutSeen) // the runner's 30 s budget is also the native router's
        assertEquals(listOf(pt(250.0, 1000.0)), engine.requests.single().via)
        assertEquals(RoutingProfile.BIKE, engine.requests.single().profile)
        assertEquals(LaunchStatus.IDLE, launcher.state.status)
        val ui = rig.await("navigating") { it.phase != null }
        assertTrue(ui.simulated)
        // The intermediate stop reached the session as a geometry index (the follower reports it when passed).
        assertEquals(1, rig.controller.route.value!!.guidance.stops.size)
        assertEquals(0, rig.service.starts.get())
    }

    @Test fun `Start is real, the service starts and the real location is used`() {
        launcher.start(simulate = false)
        waitFor("started") { started == 1 }
        assertEquals(1, rig.service.starts.get())
        assertFalse(rig.screen.ui.value.simulated)
        assertNotNull(rig.store.load())
    }

    @Test fun `a route that cannot be found is explained and nothing starts`() {
        engine.outcome = { RouteOutcome(RouteCode.NEED_MORE_MAPS, null) }
        launcher.start(simulate = false)
        waitFor("failed") { launcher.state.status == LaunchStatus.FAILED }
        assertEquals(RouteFailureKind.NEED_MORE_MAPS, launcher.state.failure!!.kind)
        assertEquals(RouteAdvice.DOWNLOAD_MAPS, launcher.state.failure!!.advice)
        assertEquals(0, started)
        assertNull(rig.screen.ui.value.phase)
        assertEquals(0, rig.service.starts.get())
    }

    @Test fun `no installed regions is a failure too, not a crash`() {
        regions = null
        launcher.start(simulate = false)
        waitFor("failed") { launcher.state.status == LaunchStatus.FAILED }
        assertEquals(RouteFailureKind.NO_REGIONS, launcher.state.failure!!.kind)
        assertEquals(0, opened.get())
    }

    @Test fun `a plan without guidance still navigates the line`() {
        engine.outcome = { RouteOutcome(RouteCode.NO_ERROR, RoutePlan(cityPlan().geometry, 2000.0, 150.0, RouteGuidance.EMPTY), guidanceError = "malformed") }
        launcher.start(simulate = true)
        waitFor("started") { started == 1 }
        rig.await("arrived") { it.phase == NavPhase.ARRIVED }
    }

    @Test fun `with no origin yet nothing happens`() {
        request = null
        launcher.start(simulate = true)
        assertEquals(LaunchStatus.IDLE, launcher.state.status)
        assertEquals(0, opened.get())
    }

    @Test fun `reset forgets a failure`() {
        engine.outcome = { RouteOutcome(RouteCode.ROUTE_NOT_FOUND, null) }
        launcher.start(simulate = false)
        waitFor("failed") { launcher.state.status == LaunchStatus.FAILED }
        launcher.reset()
        assertEquals(LaunchStatus.IDLE, launcher.state.status)
        assertNull(launcher.state.failure)
    }
}
