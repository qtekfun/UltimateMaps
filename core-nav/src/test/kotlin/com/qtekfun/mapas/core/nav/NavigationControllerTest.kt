package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.TurnType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerTest {
    private class Env : NavEnvironment {
        @Volatile var permission = true
        @Volatile var enabled = true
        @Volatile var powerSave = false
        override fun hasLocationPermission() = permission
        override fun isLocationEnabled() = enabled
        override fun isPowerSaveMode() = powerSave
    }

    private class CountingSource : LocationSource {
        val inner = SimulatedLocationSource()
        var starts = 0
        override fun lastKnown() = inner.lastKnown()
        override fun start(listener: LocationSource.Listener) { starts++; inner.start(listener) }
        override fun stop() = inner.stop()
    }

    private fun file() = File.createTempFile("navc", ".bin").also { it.delete(); it.deleteOnExit() }

    private fun TestScope.controller(
        source: LocationSource, store: NavStateStore, env: Env = Env(), routes: RouteProvider? = null,
    ) = NavigationController(
        backgroundScope, source, store, env, routes, NavConfig(), { testScheduler.currentTime + 1_000L },
        UnconfinedTestDispatcher(testScheduler), persistEveryMillis = 5_000L, watchEveryMillis = 1_000L,
    )

    private suspend fun TestScope.ride(source: SimulatedLocationSource, plan: RoutePlan, seconds: Int, startAlong: Double = 0.0, speed: Double = 10.0) {
        val sim = RouteSimulator(plan.geometry, speed, startMillis = testScheduler.currentTime + 2_000L, startAlongMeters = startAlong)
        for (f in sim.fixes().take(seconds)) {
            val wait = f.timeMillis - 1_000L - testScheduler.currentTime
            if (wait > 0) delay(wait)
            source.emit(f)
            testScheduler.runCurrent()
        }
    }

    @Test fun startPublishesStateAndStopForgetsEverything() = runTest {
        val source = SimulatedLocationSource()
        val store = NavStateStore(file(), { testScheduler.currentTime + 1_000L })
        val c = controller(source, store)
        assertNull(c.state.value)
        assertTrue(c.start(lPlan()))
        assertNotNull(c.state.value)
        assertNotNull(c.route.value)
        testScheduler.runCurrent()
        assertTrue(source.isStarted)
        ride(source, lPlan(), 20)
        assertTrue(c.state.value!!.traveledMeters > 100)
        assertTrue(store.load() != null) // saved on start and while riding
        c.stop()
        assertNull(c.state.value)
        assertNull(store.load())
        assertFalse(source.isStarted)
    }

    @Test fun anUnusablePlanIsRefusedAndTheCurrentTripKeepsGoing() = runTest {
        val source = SimulatedLocationSource()
        val c = controller(source, NavStateStore(file()))
        c.start(lPlan())
        assertFalse(c.start(RoutePlan(emptyList(), 0.0, 0.0)))
        assertNotNull(c.state.value)
        c.stop()
    }

    @Test fun aKilledProcessResumesFromTheSavedProgress() = runTest {
        val f = file()
        val plan = straightPlan()
        val source1 = SimulatedLocationSource()
        val c1 = controller(source1, NavStateStore(f, { testScheduler.currentTime + 1_000L }))
        c1.start(plan)
        ride(source1, plan, 60) // ~600 m
        val progress = c1.state.value!!.traveledMeters
        // The process dies: no stop(), the file stays. A new controller (new process) resumes.
        val source2 = SimulatedLocationSource()
        val c2 = controller(source2, NavStateStore(f, { testScheduler.currentTime + 1_000L }))
        assertTrue(c2.hasResumable())
        assertTrue(c2.resume())
        assertEquals(plan.geometry, c2.route.value!!.geometry)
        assertEquals(progress, c2.state.value!!.traveledMeters, 60.0) // saved at most 5 s (50 m) before
        assertTrue(c2.state.value!!.traveledMeters > 400.0)
        c2.stop()
        c1.stop()
    }

    @Test fun nothingToResumeWhenIdleOrExpired() = runTest {
        val f = file()
        var now = 0L
        val store = NavStateStore(f, { now }, maxAgeMillis = 1_000)
        val c = controller(SimulatedLocationSource(), store)
        assertFalse(c.resume())
        store.save(lPlan(), 5.0)
        now = 5_000
        assertFalse(c.resume())
        assertNull(c.state.value)
    }

    @Test fun arrivingClearsTheSavedState() = runTest {
        val source = SimulatedLocationSource()
        val store = NavStateStore(file(), { testScheduler.currentTime + 1_000L })
        val plan = straightPlan()
        val c = controller(source, store)
        c.start(plan)
        ride(source, plan, 500, speed = 20.0)
        assertEquals(NavStatus.ARRIVED, c.state.value!!.status)
        assertNull(store.load())
        c.stop()
    }

    @Test fun aRevokedPermissionAndSwitchedOffGpsAreReportedAndLocationRestarts() = runTest {
        val source = CountingSource()
        val env = Env()
        val c = controller(source, NavStateStore(file()), env)
        c.start(lPlan())
        testScheduler.runCurrent()
        assertNull(c.problem.value)
        val starts = source.starts
        env.permission = false
        delay(1_500)
        assertEquals(NavProblem.LOCATION_PERMISSION, c.problem.value)
        env.permission = true
        env.enabled = false
        delay(1_500)
        assertEquals(NavProblem.LOCATION_DISABLED, c.problem.value)
        env.enabled = true
        delay(1_500)
        assertNull(c.problem.value)
        assertTrue(source.starts > starts, "location was not restarted")
        c.stop()
    }

    @Test fun noSignalShowsWhenThePositionsStop() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        val c = controller(source, NavStateStore(file(), { testScheduler.currentTime + 1_000L }))
        c.start(plan)
        ride(source, plan, 20)
        delay(8_000)
        assertEquals(NavStatus.NO_SIGNAL, c.state.value!!.status)
        c.stop()
    }

    @Test fun reroutesGoThroughTheProviderWithOnlyTheStopsStillAhead() = runTest {
        val b = RouteBuilder().lineTo(0.0, 800.0)
        val s1 = b.lastIndex
        b.lineTo(0.0, 1600.0)
        val s2 = b.lastIndex
        b.lineTo(0.0, 2400.0)
        val plan = RoutePlan(b.points.toList(), 2400.0, 240.0, RouteGuidance(listOf(maneuver(0, TurnType.DEPART), maneuver(b.lastIndex, TurnType.ARRIVE)), stops = listOf(s1, s2)))
        val seen = ArrayList<Triple<LatLon, List<LatLon>, LatLon>>()
        val source = SimulatedLocationSource()
        val provider = RouteProvider { from, _, via, destination ->
            seen += Triple(from, via, destination)
            RoutePlan(listOf(from, pt(0.0, 1600.0), pt(0.0, 2400.0)), 2000.0, 200.0)
        }
        val c = controller(source, NavStateStore(file(), { testScheduler.currentTime + 1_000L }), routes = provider)
        val events = ArrayList<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { events += it } }
        c.start(plan)
        // Ride past the first stop, then leave the road sideways.
        ride(source, plan, 85)
        assertTrue(events.any { it is NavEvent.StopReached && it.stopIndex == 0 }, "events $events")
        val last = source.lastKnown()!!
        for (i in 1..12) {
            delay(1_000)
            source.emit(last.copy(point = pt(90.0 + i * 5.0, 850.0 + i * 10.0), timeMillis = last.timeMillis + i * 1_000L, bearingDegrees = 0f))
            testScheduler.runCurrent()
        }
        delay(5_000)
        assertTrue(seen.isNotEmpty(), "no reroute requested")
        assertEquals(1, seen.first().second.size) // only the second stop is left
        assertTrue(c.state.value!!.routeRevision >= 1)
        assertTrue(seen.drop(1).all { it.second.isEmpty() }, "later reroutes carry the stops of the new route: none")
        c.stop()
    }
}
