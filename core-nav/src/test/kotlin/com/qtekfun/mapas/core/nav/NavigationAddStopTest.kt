package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.TurnType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Add stop" in the middle of a trip: the fake route provider and the simulated location source decide everything. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAddStopTest {
    private class Env : NavEnvironment {
        override fun hasLocationPermission() = true
        override fun isLocationEnabled() = true
        override fun isPowerSaveMode() = false
    }

    private class Call(val from: LatLon, val via: List<LatLon>, val destination: LatLon)

    private fun file() = File.createTempFile("navstop", ".bin").also { it.delete(); it.deleteOnExit() }

    private fun TestScope.controller(source: SimulatedLocationSource, routes: RouteProvider?) = NavigationController(
        backgroundScope, source, NavStateStore(file(), { testScheduler.currentTime + 1_000L }), Env(), routes, NavConfig(),
        { testScheduler.currentTime + 1_000L }, UnconfinedTestDispatcher(testScheduler), persistEveryMillis = 5_000L, watchEveryMillis = 1_000L,
    )

    private suspend fun TestScope.ride(source: SimulatedLocationSource, plan: RoutePlan, seconds: Int, startAlong: Double = 0.0) {
        val sim = RouteSimulator(plan.geometry, 10.0, startMillis = testScheduler.currentTime + 2_000L, startAlongMeters = startAlong)
        for (f in sim.fixes().take(seconds)) {
            val wait = f.timeMillis - 1_000L - testScheduler.currentTime
            if (wait > 0) delay(wait)
            source.emit(f)
            testScheduler.runCurrent()
        }
    }

    /** 2.4 km north; [stops] are indexes of intermediate stops already in the guidance. */
    private fun plan(vararg stopsNorth: Double): RoutePlan {
        val b = RouteBuilder()
        val indexes = ArrayList<Int>()
        for (n in stopsNorth) {
            b.lineTo(0.0, n)
            indexes += b.lastIndex
        }
        b.lineTo(0.0, 2400.0)
        return RoutePlan(b.points.toList(), 2400.0, 240.0, RouteGuidance(listOf(maneuver(0, TurnType.DEPART), maneuver(b.lastIndex, TurnType.ARRIVE)), stops = indexes))
    }

    /** A provider that drives straight from the request's origin through every via to the destination. */
    private fun straightProvider(seen: MutableList<Call>) = RouteProvider { from, _, via, destination, _ ->
        seen += Call(from, via, destination)
        val points = ArrayList<LatLon>()
        points += from
        val chain = via + destination
        var prev = from
        for (p in chain) {
            val parts = 20
            for (i in 1..parts) points += LatLon(prev.lat + (p.lat - prev.lat) * i / parts, prev.lon + (p.lon - prev.lon) * i / parts)
            prev = p
        }
        RoutePlan(points, 3000.0, 300.0, RouteGuidance(listOf(maneuver(0, TurnType.DEPART), maneuver(points.size - 1, TurnType.ARRIVE))))
    }

    @Test fun addingAStopReplansThroughItAndKeepsTheTripGoing() = runTest {
        val source = SimulatedLocationSource()
        val seen = ArrayList<Call>()
        val old = plan()
        val c = controller(source, straightProvider(seen))
        c.start(old)
        ride(source, old, 40)
        val traveled = c.state.value!!.traveledMeters
        assertTrue(traveled > 300)
        val stop = pt(60.0, 1200.0) // a little to the side of the road, 800 m ahead
        val out = c.addStop(stop)
        testScheduler.runCurrent()
        assertEquals(AddStopResult.ADDED, out.result)
        assertEquals(1, seen.size)
        assertEquals(listOf(stop), seen[0].via)
        assertTrue(planar(seen[0].from, pt(0.0, traveled)) < 60.0, "planned from the current position")
        assertEquals(old.geometry.last(), seen[0].destination)
        assertTrue(c.isActive)
        val state = c.state.value!!
        assertEquals(1, state.routeRevision)
        assertEquals(1, state.stopsRemaining)
        assertEquals(out.plan!!.geometry, c.route.value!!.geometry)
        assertEquals(1, c.route.value!!.guidance.stops.size)
        c.stop()
    }

    @Test fun theNewStopIsReachedAndTheTripEndsAtTheOldDestination() = runTest {
        val source = SimulatedLocationSource()
        val old = plan()
        val c = controller(source, straightProvider(ArrayList()))
        val events = ArrayList<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { events += it } }
        c.start(old)
        ride(source, old, 30)
        val out = c.addStop(pt(0.0, 1000.0))
        assertEquals(AddStopResult.ADDED, out.result)
        ride(source, out.plan!!, 120)
        assertTrue(events.any { it is NavEvent.StopReached && it.stopIndex == 0 }, "events $events")
        assertEquals(0, c.state.value!!.stopsRemaining)
        c.stop()
    }

    @Test fun whenNoRouteIsFoundTheOldTripIsKept() = runTest {
        val source = SimulatedLocationSource()
        val old = plan()
        val c = controller(source, { _, _, _, _, _ -> null })
        c.start(old)
        ride(source, old, 20)
        val out = c.addStop(pt(0.0, 1500.0))
        assertEquals(AddStopResult.NO_ROUTE, out.result)
        assertNull(out.plan)
        assertEquals(0, c.state.value!!.routeRevision)
        assertEquals(old.geometry, c.route.value!!.geometry)
        c.stop()
    }

    @Test fun aProviderThatThrowsCountsAsNoRoute() = runTest {
        val source = SimulatedLocationSource()
        val old = plan()
        val c = controller(source, { _, _, _, _, _ -> error("engine crashed") })
        c.start(old)
        ride(source, old, 20)
        assertEquals(AddStopResult.NO_ROUTE, c.addStop(pt(0.0, 1500.0)).result)
        assertEquals(0, c.state.value!!.routeRevision)
        c.stop()
    }

    @Test fun aRouteThatDoesNotPassThroughTheStopOrChangesTheDestinationIsRefused() = runTest {
        val source = SimulatedLocationSource()
        val old = plan()
        var answer: (LatLon, List<LatLon>, LatLon) -> RoutePlan = { from, _, dest -> RoutePlan(listOf(from, dest), 1000.0, 100.0) }
        val c = controller(source, { from, _, via, dest, _ -> answer(from, via, dest) })
        c.start(old)
        ride(source, old, 20)
        // The stop is 2 km east of the road: a direct route does not pass within reach of it.
        assertEquals(AddStopResult.NO_ROUTE, c.addStop(pt(2000.0, 1500.0)).result)
        // A route that ends somewhere else is an engine glitch.
        answer = { from, via, _ -> RoutePlan(listOf(from, via[0], pt(5000.0, 5000.0)), 1000.0, 100.0) }
        assertEquals(AddStopResult.NO_ROUTE, c.addStop(pt(0.0, 1500.0)).result)
        assertEquals(0, c.state.value!!.routeRevision)
        c.stop()
    }

    @Test fun theLimitOfFiveStopsAheadDuplicatesAndTheDestinationAreRefused() = runTest {
        val source = SimulatedLocationSource()
        val seen = ArrayList<Call>()
        val full = plan(400.0, 700.0, 1000.0, 1300.0, 1600.0)
        val c = controller(source, straightProvider(seen))
        c.start(full)
        assertEquals(AddStopResult.LIMIT, c.addStop(pt(0.0, 1900.0)).result)
        c.stop()

        val four = plan(400.0, 700.0, 1000.0, 1300.0)
        c.start(four)
        assertEquals(AddStopResult.DUPLICATE, c.addStop(pt(10.0, 1000.0)).result)
        assertEquals(AddStopResult.SAME_AS_DESTINATION, c.addStop(pt(10.0, 2400.0)).result)
        assertTrue(seen.isEmpty(), "refusals must not ask the engine")
        assertEquals(AddStopResult.ADDED, c.addStop(pt(0.0, 1800.0)).result) // the fifth is allowed
        c.stop()
    }

    @Test fun reachedStopsDoNotCountAgainstTheLimit() = runTest {
        val source = SimulatedLocationSource()
        val full = plan(300.0, 500.0, 800.0, 1300.0, 1600.0)
        val c = controller(source, straightProvider(ArrayList()))
        c.start(full)
        ride(source, full, 70) // past the first two stops
        assertEquals(3, c.state.value!!.stopsRemaining)
        assertEquals(AddStopResult.ADDED, c.addStop(pt(0.0, 1900.0)).result)
        c.stop()
    }

    @Test fun aStopBehindTheUserGoesFirstAndOneOffTheRouteGoesWhereItCostsLeast() = runTest {
        val source = SimulatedLocationSource()
        val seen = ArrayList<Call>()
        val old = plan(1400.0)
        val c = controller(source, straightProvider(seen))
        c.start(old)
        ride(source, old, 60) // about 600 m
        val behind = pt(20.0, 200.0)
        val first = c.addStop(behind)
        assertEquals(AddStopResult.ADDED, first.result)
        assertEquals(behind, seen[0].via[0], "a stop behind the user is the next one")
        assertEquals(2, seen[0].via.size)
        val off = pt(900.0, 2000.0) // east of the road near the end: between the old stop and the destination
        val rideOn = first.plan!!
        ride(source, rideOn, 5)
        assertEquals(AddStopResult.ADDED, c.addStop(off).result)
        assertEquals(off, seen[1].via.last(), "from=${seen[1].from} via=${seen[1].via}")
        c.stop()
    }

    @Test fun insertionIndexPicksTheCheapestDetour() {
        val from = pt(0.0, 0.0)
        val remaining = listOf(pt(0.0, 1000.0), pt(0.0, 2000.0))
        val dest = pt(0.0, 3000.0)
        assertEquals(0, StopInsertion.insertionIndex(from, remaining, dest, pt(0.0, 500.0)))
        assertEquals(1, StopInsertion.insertionIndex(from, remaining, dest, pt(0.0, 1500.0)))
        assertEquals(2, StopInsertion.insertionIndex(from, remaining, dest, pt(0.0, 2500.0)))
        assertEquals(0, StopInsertion.insertionIndex(from, remaining, dest, pt(0.0, -300.0))) // behind
        assertEquals(0, StopInsertion.insertionIndex(from, emptyList(), dest, pt(0.0, 100.0)))
    }

    @Test fun nothingToAddWhenIdleOrWithoutAProvider() = runTest {
        val source = SimulatedLocationSource()
        val idle = controller(source, straightProvider(ArrayList()))
        assertEquals(AddStopResult.NOT_NAVIGATING, idle.addStop(pt(0.0, 100.0)).result)
        val none = controller(SimulatedLocationSource(), null)
        none.start(plan())
        assertEquals(AddStopResult.NO_ROUTE, none.addStop(pt(0.0, 100.0)).result)
        none.stop()
    }

    @Test fun onlyOneRequestAtATimeAndAnEndedTripDropsTheAnswer() = runTest {
        val source = SimulatedLocationSource()
        val gate = CompletableDeferred<Unit>()
        val inner = straightProvider(ArrayList())
        val c = controller(source, { from, b, via, dest, trip -> gate.await(); inner.route(from, b, via, dest, trip) })
        val old = plan()
        c.start(old)
        ride(source, old, 10)
        val first = async(UnconfinedTestDispatcher(testScheduler)) { c.addStop(pt(0.0, 1000.0)) }
        testScheduler.runCurrent()
        assertEquals(AddStopResult.BUSY, c.addStop(pt(0.0, 1200.0)).result)
        c.stop() // the trip ends while the route is being computed
        gate.complete(Unit)
        assertEquals(AddStopResult.NOT_NAVIGATING, first.await().result)
        assertNull(c.route.value)
        // The controller is usable again.
        c.start(old)
        assertNotNull(c.state.value)
        c.stop()
    }
}
