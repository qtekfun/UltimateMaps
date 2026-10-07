package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import com.qtekfun.mapas.core.nav.Announcement
import com.qtekfun.mapas.core.nav.AnnouncementKind
import com.qtekfun.mapas.core.nav.NavConfig
import com.qtekfun.mapas.core.nav.NavEnvironment
import com.qtekfun.mapas.core.nav.NavEvent
import com.qtekfun.mapas.core.nav.NavStateStore
import com.qtekfun.mapas.core.nav.NavTrip
import com.qtekfun.mapas.core.nav.NavigationController
import com.qtekfun.mapas.core.nav.RerouteConfig
import com.qtekfun.mapas.core.nav.RouteProvider
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.TurnType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The navigation model end to end on the JVM: the real [NavigationController] and follower, a fake engine for
 * reroutes, a simulated location, no Android. Everything runs on ONE thread with a clock that only moves when the
 * test (or the simulation) moves it, and the simulation yields between fixes instead of sleeping, so the order of
 * events is fixed and nothing depends on timing; the `await` calls only wait for that thread to reach a state
 * (their timeout is a failure guard, never a part of the logic).
 */
class NavScreenControllerTest {
    private val rigs = ArrayList<NavTestRig>()

    private fun Harness(
        file: File = File.createTempFile("navui", ".bin").also { it.delete(); it.deleteOnExit() },
        routes: RouteProvider? = null,
        config: NavConfig = NavConfig(),
    ) = NavTestRig(file, routes, config).also { rigs += it }

    @After fun tearDown() = rigs.forEach { it.close() }

    @Test fun `a simulated trip reaches the arrival, never uses the real location, is never saved, and stop cleans up`() {
        val h = Harness()
        assertTrue(h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true, speedKmh = 50))
        val arrived = h.await("arrived") { it.phase == NavPhase.ARRIVED }
        val summary = assertNotNull(arrived.summary)
        assertTrue(summary.simulated)
        assertEquals(2000.0, summary.distanceMeters, 80.0)
        assertTrue(summary.durationMillis > 60_000L) // 2 km at 50 km/h is 2.4 min of simulated clock
        assertFalse(h.real.isStarted, "the real location source must never start during a simulation")
        assertNull(h.store.load(), "a simulation is never saved as a real trip")
        assertEquals(0, h.service.starts.get()) // no foreground service (and no location permission) for a simulation

        // The follower announced the right turn, each level once, in order.
        val right = h.sink.announcements.filter { it.maneuver.type == TurnType.RIGHT }.map { it.kind }
        assertEquals(listOf(AnnouncementKind.FAR, AnnouncementKind.NEAR, AnnouncementKind.NOW), right)
        assertEquals(listOf("started:true", "ended:true"), h.sink.log)

        h.screen.stop()
        assertNull(h.screen.ui.value.phase)
        assertNull(h.controller.state.value)
        assertEquals(1, h.service.stops.get())
        assertFalse(h.switch.isSimulated)
    }

    @Test fun `the arrival summary survives the service stopping the controller and goes away only when dismissed`() {
        val h = Harness()
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true)
        h.await("arrived") { it.phase == NavPhase.ARRIVED }
        h.controller.stop() // what the service does on arrival
        h.await("controller gone") { h.controller.state.value == null }
        h.settle()
        assertEquals(NavPhase.ARRIVED, h.screen.ui.value.phase)
        assertNotNull(h.screen.ui.value.summary)
        h.screen.stop() // "Done"
        assertNull(h.screen.ui.value.phase)
        assertNull(h.screen.ui.value.summary)
    }

    @Test fun `intermediate stops reach the session, the stop banner shows, and the trip goes on to the arrival`() {
        val h = Harness()
        val via = listOf(pt(250.0, 1000.0)) // on the east leg
        h.gate = CompletableDeferred()
        h.gateWhen = { it.stopsReached >= 1 }
        assertTrue(h.screen.begin(cityPlan(), via, NavTrip(), simulate = true))
        // withStops was applied: the session's route carries the stop's geometry index.
        val stops = h.controller.route.value!!.guidance.stops
        assertEquals(1, stops.size)
        assertTrue(stops[0] > cityIndexAt(1000.0), "the stop is after the corner")

        val ui = h.await("stop reached") { it.phase == NavPhase.STOP_REACHED }
        assertEquals(1, ui.stopsReached)
        assertTrue(h.sink.events.any { it is NavEvent.StopReached })
        h.gate!!.complete(Unit)
        val end = h.await("arrived") { it.phase == NavPhase.ARRIVED }
        assertEquals(1, end.summary!!.stopsReached)
    }

    @Test fun `a real trip starts the service, saves its state, and a stop from outside closes the screen`() {
        val h = Harness()
        assertTrue(h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false))
        assertEquals(1, h.service.starts.get())
        assertNotNull(h.store.load())
        h.emitReal(pt(0.0, 20.0))
        h.await("on route") { it.phase == NavPhase.ON_ROUTE && it.nav != null && it.nav.traveledMeters > 5 }
        h.controller.stop() // the notification's Stop button
        val ui = h.await("closed") { it.phase == null }
        assertFalse(ui.active)
        assertEquals(listOf("started:false", "ended:false"), h.sink.log)
    }

    @Test fun `off route with no route found shows OFF_ROUTE, then a recalculation shows REROUTING and rejoins on the new route`() {
        val calls = AtomicInteger()
        val answer = CompletableDeferred<RoutePlan?>()
        val provider = RouteProvider { from, _, _, destination, _ ->
            when (calls.incrementAndGet()) {
                1 -> null // the engine finds nothing
                else -> answer.await()?.copy(geometry = listOf(from, destination))
            }
        }
        val config = NavConfig(reroute = RerouteConfig(maxAttempts = 1, retryDelayMillis = 1, cooldownMillis = 30_000))
        val h = Harness(routes = provider, config = config)
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false)
        h.emitReal(pt(0.0, 40.0), seconds = 3)
        h.await("on route") { it.phase == NavPhase.ON_ROUTE && (it.nav?.traveledMeters ?: 0.0) > 20 }

        h.emitReal(pt(300.0, 400.0), seconds = 8) // 300 m off the road
        h.await("off route, reroute failed") { it.phase == NavPhase.OFF_ROUTE }
        assertEquals(1, calls.get())

        h.clock.addAndGet(60_000L) // past the cooldown: the next fix retries
        h.emitReal(pt(300.0, 400.0))
        h.await("rerouting") { it.phase == NavPhase.REROUTING }
        answer.complete(RoutePlan(emptyList(), 1500.0, 120.0, RouteGuidance(listOf(Maneuver(1, TurnType.ARRIVE)))))
        val back = h.await("new route") { it.phase == NavPhase.ON_ROUTE && (it.nav?.routeRevision ?: 0) == 1 }
        assertEquals(2, calls.get())
        assertEquals(1, back.nav!!.routeRevision)
    }

    @Test fun `without fixes for a while the screen shows NO_SIGNAL`() {
        val config = NavConfig(tickMillis = 20L, signalLossMillis = 5_000L)
        val h = Harness(config = config)
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false)
        h.emitReal(pt(0.0, 40.0), seconds = 2)
        h.await("on route") { it.phase == NavPhase.ON_ROUTE && (it.nav?.traveledMeters ?: 0.0) > 20 }
        h.clock.addAndGet(20_000L) // the next tick sees 20 s without a fix
        h.await("no signal") { it.phase == NavPhase.NO_SIGNAL }
        h.emitReal(pt(0.0, 400.0)) // the signal is back
        h.await("back on route") { it.phase == NavPhase.ON_ROUTE }
    }

    @Test fun `a trip saved before the process died is offered, resumed or discarded`() {
        val file = File.createTempFile("navui-resume", ".bin").also { it.delete(); it.deleteOnExit() }
        val first = Harness(file)
        first.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false)
        first.emitReal(pt(0.0, 100.0), seconds = 2)
        first.await("riding") { (it.nav?.traveledMeters ?: 0.0) > 50 }
        // The process dies: no stop(). A new process (new objects, same file) starts and the activity comes up.
        val second = Harness(file)
        second.clock.set(first.clock.get() + 60_000L)
        assertNull(second.screen.ui.value.phase)
        second.screen.refreshResumable()
        assertTrue(second.screen.ui.value.resumable)
        assertTrue(second.screen.resume())
        val ui = second.await("resumed") { it.phase == NavPhase.ON_ROUTE }
        assertFalse(ui.resumable)
        assertEquals(1, second.service.resumes.get())

        val third = Harness(file)
        third.clock.set(second.clock.get())
        third.screen.refreshResumable()
        assertTrue(third.screen.ui.value.resumable)
        third.screen.discardResumable()
        assertFalse(third.screen.ui.value.resumable)
        assertNull(third.store.load())
    }

    @Test fun `an unusable plan changes nothing`() {
        val h = Harness()
        assertFalse(h.screen.begin(RoutePlan(emptyList(), 0.0, 0.0), emptyList(), NavTrip(), simulate = false))
        assertNull(h.screen.ui.value.phase)
        assertEquals(0, h.service.starts.get())
    }

    @Test fun `following, recentering and glove mode are remembered`() {
        val h = Harness()
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true)
        assertTrue(h.screen.ui.value.following)
        h.screen.onUserMovedMap()
        assertFalse(h.screen.ui.value.following)
        h.screen.recenter()
        assertTrue(h.screen.ui.value.following)
        h.screen.setGlove(true)
        assertTrue(h.screen.ui.value.glove)
        h.screen.stop()
        assertTrue(h.screen.ui.value.glove) // the preference outlives the trip
    }

    @Test fun `the simulation speed is adjustable and the trip continues from where it was`() {
        val h = Harness()
        h.gate = CompletableDeferred()
        h.gateWhen = { (it.nav?.traveledMeters ?: 0.0) > 300.0 }
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true, speedKmh = 50)
        h.await("past 300 m") { (it.nav?.traveledMeters ?: 0.0) > 300.0 }
        h.screen.simulationFaster()
        assertEquals(90, h.screen.ui.value.simulationSpeedKmh)
        h.screen.simulationSlower()
        h.screen.simulationSlower()
        assertEquals(30, h.screen.ui.value.simulationSpeedKmh)
        h.gate!!.complete(Unit)
        val arrived = h.await("arrived at the new speed") { it.phase == NavPhase.ARRIVED }
        assertEquals(30, arrived.simulationSpeedKmh)
        assertEquals(2000.0, arrived.summary!!.distanceMeters, 80.0)
    }
}
