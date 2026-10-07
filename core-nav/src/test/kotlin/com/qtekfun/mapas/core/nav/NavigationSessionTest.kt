package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.TurnType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val T0 = 1_000L // the simulator starts at 1000 ms; virtual time starts at 0

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSessionTest {
    private fun TestScope.newSession(
        plan: RoutePlan,
        source: SimulatedLocationSource,
        reroute: Rerouter? = null,
        config: NavConfig = NavConfig(),
    ) = NavigationSession(plan, source, backgroundScope, config, reroute) { testScheduler.currentTime + T0 }

    private suspend fun TestScope.feed(source: SimulatedLocationSource, fixes: Sequence<LocationFix>) {
        for (f in fixes) {
            val wait = f.timeMillis - T0 - testScheduler.currentTime
            if (wait > 0) delay(wait)
            source.emit(f)
            testScheduler.runCurrent()
        }
    }

    private fun TestScope.collectStatuses(session: NavigationSession): MutableList<NavStatus> {
        val list = ArrayList<NavStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { list += it.status } }
        return list
    }

    /** Rides [plan] up to [alongMeters], then wanders 60 m to the side of it, heading on at 10 m/s. */
    private fun ridingThenLeaving(plan: RoutePlan, alongMeters: Double, sideSeconds: Int): Sequence<LocationFix> {
        val ride = RouteSimulator(plan.geometry, 10.0).fixes().takeWhile { (it.timeMillis - T0) / 1000.0 * 10.0 <= alongMeters }.toList()
        val lastT = ride.last().timeMillis
        val side = (1..sideSeconds).map { i ->
            LocationFix(pt(60.0, alongMeters + i * 10.0), 5f, 0f, 10f, lastT + i * 1000L)
        }
        return (ride + side).asSequence()
    }

    private fun planFrom(from: LatLon, east: Double): RoutePlan {
        val to = LatLon(from.lat, from.lon + east / (111_194.9266 * Math.cos(Math.toRadians(from.lat))))
        return RoutePlan(listOf(from, to), east, east / 10.0)
    }

    @Test fun followsASimulatedRideAndPublishesStateAndAnnouncements() = runTest {
        val source = SimulatedLocationSource()
        val plan = lPlan()
        val session = newSession(plan, source)
        val announcements = ArrayList<Announcement>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.announcements.collect { announcements += it } }
        val statuses = collectStatuses(session)
        session.start()
        assertTrue(source.isStarted)
        assertEquals(NavStatus.ON_ROUTE, session.state.value.status)
        feed(source, RouteSimulator(plan.geometry, 10.0, noiseMeters = 5.0, seed = 9).fixes())
        assertEquals(NavStatus.ARRIVED, session.state.value.status)
        assertEquals(0.0, session.state.value.remainingMeters, 1e-6)
        assertTrue(statuses.none { it == NavStatus.OFF_ROUTE || it == NavStatus.REROUTING })
        val turn = announcements.filter { it.maneuver.type == TurnType.RIGHT }.map { it.kind }
        assertEquals(listOf(AnnouncementKind.FAR, AnnouncementKind.NEAR, AnnouncementKind.NOW), turn)
        session.stop()
        assertFalse(source.isStarted)
    }

    @Test fun fixesWithoutTimestampAreStampedWithTheClock() = runTest {
        val source = SimulatedLocationSource()
        val session = newSession(straightPlan(), source)
        session.start()
        for (i in 0..10) {
            delay(1000)
            val p = pt(0.0, i * 10.0)
            source.emit(LocationFix(p, 5f, 0f, 10f)) // timeMillis = 0
            testScheduler.runCurrent()
        }
        assertEquals(100.0, session.state.value.traveledMeters, 5.0)
        assertEquals(NavStatus.ON_ROUTE, session.state.value.status)
    }

    @Test fun startIsIdempotentAndPicksUpTheLastKnownFix() = runTest {
        val source = SimulatedLocationSource()
        source.emit(LocationFix(pt(0.0, 300.0), 5f, 0f, 10f, T0))
        val session = newSession(straightPlan(), source)
        session.start()
        session.start()
        testScheduler.runCurrent()
        assertEquals(300.0, session.state.value.traveledMeters, 3.0)
    }

    @Test fun anIsolatedBadFixDoesNotTriggerAReroute() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var calls = 0
        val session = newSession(plan, source, { _, _ -> calls++; null })
        session.start()
        val fixes = RouteSimulator(plan.geometry, 10.0).fixes().toMutableList()
        fixes[40] = fixes[40].copy(point = pt(2000.0, 400.0))
        feed(source, fixes.asSequence())
        delay(30_000)
        assertEquals(0, calls)
        assertEquals(NavStatus.ARRIVED, session.state.value.status)
    }

    @Test fun leavingTheRouteReroutesAndSwitchesToTheNewRoute() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        val newPlan = planFrom(pt(60.0, 560.0), 600.0)
        val requests = ArrayList<Pair<LatLon, Float?>>()
        val session = newSession(plan, source, { from, bearing -> requests += from to bearing; delay(500); newPlan })
        val statuses = collectStatuses(session)
        session.start()
        feed(source, ridingThenLeaving(plan, 500.0, 8))
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()
        assertEquals(1, requests.size)
        assertEquals(0f, requests[0].second)
        assertEquals(newPlan, session.route.value)
        assertEquals(1, session.state.value.routeRevision)
        assertEquals(NavStatus.ON_ROUTE, session.state.value.status)
        assertTrue(NavStatus.OFF_ROUTE in statuses && NavStatus.REROUTING in statuses)
        // Following the new route works and ends in arrival.
        val start = session.state.value.position
        assertTrue(planar(start, newPlan.geometry.first()) < 30.0)
        val t = testScheduler.currentTime + T0
        feed(source, RouteSimulator(newPlan.geometry, 10.0, startMillis = t + 1000).fixes())
        assertEquals(NavStatus.ARRIVED, session.state.value.status)
    }

    @Test fun aFailedRerouteRetriesThenWaitsAndNeverRunsTwoAtOnce() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var calls = 0
        var running = 0
        var maxRunning = 0
        val callTimes = ArrayList<Long>()
        val session = newSession(plan, source, { _, _ ->
            calls++
            callTimes += testScheduler.currentTime
            running++
            maxRunning = maxOf(maxRunning, running)
            try { delay(300); null } finally { running-- }
        })
        session.start()
        feed(source, ridingThenLeaving(plan, 500.0, 8))
        delay(40_000)
        // Cycles of 3 attempts, 2 s apart, 8 s cooldown between cycles.
        assertTrue(calls >= 6, "calls=$calls at $callTimes")
        assertEquals(1, maxRunning)
        val first = callTimes.take(3)
        // 300 ms of work plus the 2 s retry delay.
        assertEquals(2300L, first[1] - first[0])
        assertEquals(2300L, first[2] - first[1])
        assertTrue(callTimes[3] - callTimes[2] >= 8000, "cooldown not respected: $callTimes")
        assertTrue(session.state.value.status == NavStatus.OFF_ROUTE || session.state.value.status == NavStatus.REROUTING)
        assertEquals(0, session.state.value.routeRevision)
        session.stop()
    }

    @Test fun aSlowRerouteIsNotStartedAgainByLaterFixes() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var calls = 0
        val session = newSession(plan, source, { from, _ -> calls++; delay(20_000); planFrom(from, 600.0) })
        session.start()
        feed(source, ridingThenLeaving(plan, 500.0, 15))
        assertEquals(1, calls)
        assertEquals(NavStatus.REROUTING, session.state.value.status)
        session.stop()
    }

    @Test fun aRerouteThatThrowsCountsAsAFailure() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var calls = 0
        val session = newSession(plan, source, { from, _ -> calls++; if (calls < 3) error("engine crashed") else planFrom(from, 600.0) })
        session.start()
        feed(source, ridingThenLeaving(plan, 500.0, 8))
        delay(10_000)
        assertEquals(3, calls)
        assertEquals(1, session.state.value.routeRevision)
        session.stop()
    }

    @Test fun anUnusableNewRouteIsTreatedAsAFailure() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var calls = 0
        val session = newSession(plan, source, { from, _ -> calls++; if (calls == 1) RoutePlan(listOf(from), 0.0, 0.0) else planFrom(from, 600.0) })
        session.start()
        feed(source, ridingThenLeaving(plan, 500.0, 8))
        delay(10_000)
        assertEquals(2, calls)
        assertEquals(1, session.state.value.routeRevision)
        session.stop()
    }

    @Test fun stoppingCancelsARunningReroute() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var cancelled = false
        val session = newSession(plan, source, { _, _ ->
            try { delay(60_000); null } catch (e: CancellationException) { cancelled = true; throw e }
        })
        session.start()
        feed(source, ridingThenLeaving(plan, 500.0, 8))
        testScheduler.runCurrent()
        assertEquals(NavStatus.REROUTING, session.state.value.status)
        session.close()
        testScheduler.runCurrent()
        assertTrue(cancelled)
        assertFalse(source.isStarted)
    }

    @Test fun rejoiningTheRouteCancelsTheReroute() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var cancelled = false
        val session = newSession(plan, source, { from, _ ->
            try { delay(30_000); planFrom(from, 600.0) } catch (e: CancellationException) { cancelled = true; throw e }
        })
        session.start()
        val away = ridingThenLeaving(plan, 500.0, 8).toList()
        feed(source, away.asSequence())
        assertEquals(NavStatus.REROUTING, session.state.value.status)
        // Back to the road, ahead of where it left.
        var t = away.last().timeMillis
        for (i in 1..5) { t += 1000; feed(source, sequenceOf(LocationFix(pt(2.0, 580.0 + i * 10), 5f, 0f, 10f, t))) }
        testScheduler.runCurrent()
        assertEquals(NavStatus.ON_ROUTE, session.state.value.status)
        assertTrue(cancelled)
        delay(60_000)
        assertEquals(0, session.state.value.routeRevision)
        assertEquals(plan, session.route.value)
        session.stop()
    }

    @Test fun tunnelShowsNoSignalAndEstimatesThenRecovers() = runTest {
        val source = SimulatedLocationSource()
        val plan = lPlan()
        val session = newSession(plan, source)
        val estimated = ArrayList<Double>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.state.collect { if (it.estimated) estimated += it.traveledMeters }
        }
        val statuses = collectStatuses(session)
        session.start()
        feed(source, RouteSimulator(plan.geometry, 10.0, gaps = listOf(20_000L..45_000L)).fixes())
        assertTrue(NavStatus.NO_SIGNAL in statuses)
        assertTrue(estimated.size >= 5)
        assertEquals(estimated.sorted(), estimated)
        assertEquals(NavStatus.ARRIVED, session.state.value.status)
        assertFalse(session.state.value.estimated)
    }

    @Test fun silenceFromTheStartDoesNotInventAPosition() = runTest {
        val source = SimulatedLocationSource()
        val session = newSession(straightPlan(), source)
        session.start()
        delay(60_000)
        assertEquals(NavStatus.ON_ROUTE, session.state.value.status)
        assertEquals(0.0, session.state.value.traveledMeters)
    }
}
