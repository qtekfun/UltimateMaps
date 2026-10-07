package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic chaos tests of the follower: every scenario is seeded, nothing depends on the wall clock.
 * Properties checked everywhere: no exception, no prompt twice, progress never goes back without a cause.
 */
class RobustnessTest {
    private class Result(val states: List<NavState>, val announcements: List<Announcement>, val events: List<NavEvent>, val tracker: RouteTracker)

    /** Drives a tracker like the session does: a tick per second of silence between fixes. */
    private fun drive(plan: RoutePlan, fixes: Sequence<LocationFix>, config: NavConfig = NavConfig(), startAlong: Double = 0.0): Result {
        val announcements = ArrayList<Announcement>()
        val events = ArrayList<NavEvent>()
        val tracker = RouteTracker(plan, config, 0, startAlong, { events += it }) { announcements += it }
        val states = ArrayList<NavState>()
        var lastT = -1L
        for (f in fixes) {
            if (lastT >= 0 && f.timeMillis > lastT) {
                var t = lastT + 1000
                while (t < f.timeMillis) {
                    if (tracker.onTick(t)) states += tracker.snapshot()
                    t += 1000
                }
            }
            tracker.onFix(f)
            states += tracker.snapshot()
            if (f.timeMillis > lastT) lastT = f.timeMillis
        }
        return Result(states, announcements, events, tracker)
    }

    private fun assertNoDuplicatePrompts(a: List<Announcement>) {
        val seen = HashSet<Pair<Int, AnnouncementKind>>()
        for (x in a) assertTrue(seen.add(x.maneuver.geometryIndex to x.kind), "prompt repeated: $x")
    }

    /** Progress may only go back after a state without a trustworthy fix (signal loss, off route) or a clock re-base. */
    private fun assertProgressNeverGoesBack(states: List<NavState>, slack: Double = 0.01) {
        for (i in 1 until states.size) {
            val prev = states[i - 1]
            val cur = states[i]
            if (cur.traveledMeters < prev.traveledMeters - slack) {
                val excused = prev.status != NavStatus.ON_ROUTE || prev.estimated || cur.routeRevision != prev.routeRevision
                assertTrue(excused, "progress went back ${prev.traveledMeters} -> ${cur.traveledMeters} at state $i (${prev.status})")
            }
        }
    }

    private fun long(km: Int): RoutePlan {
        val b = RouteBuilder(step = 50.0).lineTo(0.0, km * 1000.0 / 2).lineTo(km * 1000.0 / 2, km * 1000.0 / 2)
        return b.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(b.lastIndex, TurnType.ARRIVE)))
    }

    // ---- (a) crossing the "now" band between two fixes -------------------------------------------------------

    @Test fun aManeuverJumpedOverBetweenTwoFixesStillGetsItsNowPrompt() {
        val plan = lPlan()
        val turn = plan.guidance.maneuvers[1]
        // 30 m/s with fixes every 3 s: 90 m apart; the "now" band at that speed is 80 m, so some fixes straddle it.
        val a = ArrayList<Announcement>()
        val tracker = RouteTracker(plan, NavConfig()) { a += it }
        var t = 1_000L
        var along = 0.0
        val points = RouteGeometry(plan.geometry)
        while (along < 1_050.0) {
            tracker.onFix(fix(points.pointAt(along), t, speed = 30f))
            t += 3_000; along += 90.0
        }
        val forTurn = a.filter { it.maneuver == turn }
        assertTrue(forTurn.any { it.kind == AnnouncementKind.NOW }, "no NOW for the turn: $forTurn")
        assertNoDuplicatePrompts(a)
    }

    @Test fun aHugeJumpPastTheManeuverEmitsNowOnlyForTheLastCrossedOne() {
        val plan = loopPlan()
        val g = RouteGeometry(plan.geometry)
        val a = ArrayList<Announcement>()
        val tracker = RouteTracker(plan, NavConfig()) { a += it }
        tracker.onFix(fix(g.pointAt(1.0), 1_000, speed = 20f))
        // At 300 m the corner (400 m) is in the "near" band only; 7 s later (a gap) the car is 40 m past it.
        tracker.onFix(fix(g.pointAt(300.0), 20_000, speed = 20f))
        a.clear()
        tracker.onFix(fix(g.pointAt(440.0), 27_000, speed = 20f))
        val corner = plan.guidance.maneuvers[1].geometryIndex
        assertEquals(listOf(AnnouncementKind.NOW), a.filter { it.maneuver.geometryIndex == corner }.map { it.kind })
        // Nothing is said for the corners that are still ahead, and nothing twice later.
        tracker.onFix(fix(g.pointAt(460.0), 28_000, speed = 20f))
        assertEquals(1, a.count { it.maneuver.geometryIndex == corner })
    }

    // ---- (b) intermediate stops ----------------------------------------------------------------------------

    private fun planWithStops(): RoutePlan {
        val b = RouteBuilder().lineTo(0.0, 1000.0)
        val stop1 = b.lastIndex
        b.lineTo(0.0, 2000.0)
        val stop2 = b.lastIndex
        b.lineTo(0.0, 3000.0)
        return RoutePlan(b.points.toList(), 3000.0, 300.0, RouteGuidance(listOf(maneuver(0, TurnType.DEPART), maneuver(b.lastIndex, TurnType.ARRIVE)), stops = listOf(stop1, stop2)))
    }

    @Test fun reachingAStopIsNotArrivingAndNavigationContinues() {
        val plan = planWithStops()
        val r = drive(plan, RouteSimulator(plan.geometry, 10.0).fixes())
        val reached = r.events.filterIsInstance<NavEvent.StopReached>()
        assertEquals(listOf(0, 1), reached.map { it.stopIndex })
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
        // Between the stops the status was ON_ROUTE, and the counters followed.
        val afterFirst = r.states.first { it.stopsRemaining == 1 }
        assertEquals(NavStatus.ON_ROUTE, afterFirst.status)
        assertTrue(afterFirst.traveledMeters in 900.0..1100.0)
        assertEquals(0, r.states.last().stopsRemaining)
        assertEquals(null, r.states.last().nextStopMeters)
        assertTrue(r.states.first().nextStopMeters!! in 900.0..1100.0)
    }

    @Test fun aStopRejoinedBeyondIsReportedAsSkippedNotReached() {
        val plan = planWithStops()
        val fixes = RouteSimulator(plan.geometry, 10.0).fixes().toList()
        // Lose the signal for 100 s around the first stop (the car drives on), then recover past it.
        val kept = fixes.filter { (it.timeMillis - 1000) !in 60_000L..170_000L }
        val r = drive(plan, kept.asSequence())
        val events = r.events
        assertTrue(events.any { it is NavEvent.StopSkipped && it.stopIndex == 0 }, "events: $events")
        assertEquals(1, events.count { it is NavEvent.StopSkipped || (it is NavEvent.StopReached && it.stopIndex == 0) })
    }

    @Test fun stopsAreProjectedFromViaPointsAndIgnoredWhenFarFromTheRoute() {
        val b = RouteBuilder().lineTo(0.0, 2000.0)
        val plain = b.plan(listOf(maneuver(0, TurnType.DEPART)))
        val withStops = plain.withStops(listOf(pt(30.0, 700.0), pt(5000.0, 1000.0), pt(0.0, 1500.0)))
        assertEquals(2, withStops.guidance.stops.size)
        val g = RouteGeometry(withStops.geometry)
        assertEquals(700.0, g.alongOfIndex(withStops.guidance.stops[0]), 20.0)
        assertEquals(1500.0, g.alongOfIndex(withStops.guidance.stops[1]), 20.0)
        assertEquals(plain, plain.withStops(emptyList()))
    }

    // ---- (c) chaos: noise, spikes, accuracy, duplicates, order, clock -----------------------------------------

    @Test fun noisyFixesWithSpikesNeverBreakTheProperties() {
        for (seed in 1L..6L) {
            val plan = lPlan()
            val random = Random(seed)
            val base = RouteSimulator(plan.geometry, 15.0, noiseMeters = 6.0, seed = seed).fixes().toList()
            val chaotic = base.map { f ->
                when (random.nextInt(40)) {
                    0 -> f.copy(point = LatLon(f.point.lat + 0.004, f.point.lon + 0.004)) // 500 m sideways spike
                    1 -> f.copy(point = LatLon(f.point.lat + 0.0007, f.point.lon)) // ~80 m along-track spike
                    2 -> f.copy(accuracyMeters = 400f) // hopeless accuracy
                    3 -> f.copy(accuracyMeters = null, speedMps = null, bearingDegrees = null)
                    else -> f
                }
            }
            val r = drive(plan, chaotic.asSequence())
            assertNoDuplicatePrompts(r.announcements)
            assertProgressNeverGoesBack(r.states)
            assertEquals(NavStatus.ARRIVED, r.states.last().status, "seed $seed")
            assertTrue(r.states.none { it.status == NavStatus.OFF_ROUTE || it.status == NavStatus.REROUTING }, "false off-route, seed $seed")
        }
    }

    @Test fun aSustainedAlongTrackSpikeDoesNotDragTheProgressAheadForGood() {
        val plan = straightPlan()
        val base = RouteSimulator(plan.geometry, 10.0).fixes().toList()
        // Fixes 40..42 claim to be 250 m ahead (multipath); the rest are fine.
        val g = RouteGeometry(plan.geometry)
        val spiked = base.mapIndexed { i, f -> if (i in 40..41) f.copy(point = g.pointAt(i * 10.0 + 250.0)) else f }
        val r = drive(plan, spiked.asSequence())
        val atFix60 = r.states[60].traveledMeters
        assertEquals(60 * 10.0, atFix60, 25.0)
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
    }

    @Test fun duplicatedAndOutOfOrderFixesAreHarmless() {
        val plan = lPlan()
        val base = RouteSimulator(plan.geometry, 12.0, noiseMeters = 3.0).fixes().toList()
        val random = Random(7)
        val mess = ArrayList<LocationFix>()
        var i = 0
        while (i < base.size) {
            val f = base[i]
            mess += f
            if (random.nextInt(4) == 0) mess += f // duplicate
            if (random.nextInt(6) == 0 && i + 1 < base.size) { mess += base[i + 1]; mess += f; i++ } // swapped pair
            i++
        }
        val r = drive(plan, mess.asSequence())
        assertNoDuplicatePrompts(r.announcements)
        assertProgressNeverGoesBack(r.states)
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
        assertTrue(r.states.none { it.status == NavStatus.OFF_ROUTE })
    }

    @Test fun threeIdenticalFarFixesDoNotCountAsThreeOffRouteFixes() {
        val plan = straightPlan()
        val t = RouteTracker(plan)
        t.onFix(fix(pt(0.0, 100.0), 1_000))
        val far = fix(pt(500.0, 100.0), 2_000)
        repeat(10) { t.onFix(far) }
        assertEquals(NavStatus.ON_ROUTE, t.status)
    }

    @Test fun aClockThatJumpsBackForGoodIsRebasedAfterAFewFixes() {
        val plan = lPlan()
        val base = RouteSimulator(plan.geometry, 12.0, startMillis = 10_000_000_000L).fixes().toList()
        val shifted = base.mapIndexed { i, f -> if (i >= 40) f.copy(timeMillis = f.timeMillis - 9_000_000_000L) else f }
        val r = drive(plan, shifted.asSequence())
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
        assertNoDuplicatePrompts(r.announcements)
        assertProgressNeverGoesBack(r.states)
    }

    @Test fun aClockThatStepsBackBrieflyDoesNotLoseFixes() {
        val plan = lPlan()
        val base = RouteSimulator(plan.geometry, 12.0).fixes().toList()
        val wobble = base.mapIndexed { i, f -> if (i in 30..32) f.copy(timeMillis = f.timeMillis - 2_000) else f }
        val r = drive(plan, wobble.asSequence())
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
        assertProgressNeverGoesBack(r.states)
    }

    @Test fun aLongSignalLossAtHighwaySpeedIsRecoveredEvenBeyondTheSearchWindow() {
        val plan = long(40) // 40 km
        val speed = 30.0
        // 4 min without any fix: 7.2 km at 30 m/s, beyond the 5 km cap of the search window.
        val sim = RouteSimulator(plan.geometry, speed, gaps = listOf(120_000L..360_000L))
        val r = drive(plan, sim.fixes())
        assertTrue(r.states.any { it.status == NavStatus.NO_SIGNAL })
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
        assertNoDuplicatePrompts(r.announcements)
        // Right after the loss ended the position was back on the road, not off route.
        val firstAfter = r.states.first { it.status == NavStatus.ON_ROUTE && it.traveledMeters > 7_000 }
        assertEquals(NavStatus.ON_ROUTE, firstAfter.status)
        assertTrue(r.states.none { it.status == NavStatus.OFF_ROUTE })
    }

    @Test fun noSignalAfterTheEstimateLimitStopsExtrapolating() {
        val plan = long(40)
        val r = drive(plan, RouteSimulator(plan.geometry, 30.0, gaps = listOf(60_000L..900_000L)).fixes().take(61))
        val noSignal = r.states.filter { it.status == NavStatus.NO_SIGNAL }
        assertTrue(noSignal.isNotEmpty())
        // 30 s of extrapolation at most: the estimate stays within ~ (60 + 30) s of travel.
        assertTrue(noSignal.maxOf { it.traveledMeters } <= 30.0 * 95)
    }

    // ---- degenerate routes ---------------------------------------------------------------------------------

    @Test fun degenerateRoutesNeverThrow() {
        val p = pt(0.0, 0.0)
        val plans = listOf(
            RoutePlan(listOf(p), 0.0, 0.0),
            RoutePlan(listOf(p, p), 0.0, 0.0),
            RoutePlan(List(50) { p }, 0.0, 0.0),
            RoutePlan(listOf(p, p, pt(0.0, 100.0), pt(0.0, 100.0), pt(0.0, 100.0), pt(0.0, 200.0)), 200.0, 20.0),
            // Indices that point outside the geometry (a buggy engine) are clamped, never thrown at.
            RoutePlan(listOf(p, pt(0.0, 300.0), pt(0.0, 600.0)), 600.0, 60.0, RouteGuidance(listOf(maneuver(-4, TurnType.LEFT), maneuver(99, TurnType.ARRIVE)), stops = listOf(1, 500, -3))),
        )
        for (plan in plans) {
            assertTrue(plan.isFollowable())
            val r = drive(plan, RouteSimulator(plan.sanitized().geometry, 10.0).fixes())
            assertTrue(r.states.isNotEmpty())
            assertNoDuplicatePrompts(r.announcements)
            assertProgressNeverGoesBack(r.states)
            r.tracker.snapshot()
        }
        assertFalse(RoutePlan(emptyList(), 0.0, 0.0).isFollowable())
    }

    @Test fun aZeroLengthRouteArrivesAtOnce() {
        val p = pt(0.0, 0.0)
        val r = drive(RoutePlan(listOf(p, p), 0.0, 0.0), sequenceOf(fix(p, 1_000)))
        assertEquals(NavStatus.ARRIVED, r.states.last().status)
    }

    @Test fun aVeryLongRouteIsFollowedFastAndCorrectly() {
        val plan = long(1_000) // ~1000 km: 40.000 points at 50 m
        assertTrue(plan.geometry.size > 20_000)
        val started = System.nanoTime()
        // Jump into the middle with a first fix, then ride 5 km.
        val g = RouteGeometry(plan.geometry)
        val sim = RouteSimulator(plan.geometry, 30.0, startAlongMeters = g.totalMeters / 2)
        val r = drive(plan, sim.fixes().take(200), startAlong = g.totalMeters / 2)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 20_000, "took $elapsedMs ms")
        assertEquals(g.totalMeters / 2 + 199 * 30.0, r.states.last().traveledMeters, 60.0)
        assertProgressNeverGoesBack(r.states)
    }

    @Test fun aSecondFirstFixFarAlongTheRouteIsFound() {
        val plan = long(100)
        val g = RouteGeometry(plan.geometry)
        val sim = RouteSimulator(plan.geometry, 20.0, startAlongMeters = 30_000.0)
        val r = drive(plan, sim.fixes().take(10))
        assertTrue(r.states.last().traveledMeters > 29_900.0, "progress ${r.states.last().traveledMeters} of ${g.totalMeters}")
    }

    // ---- session level: weird reroutes, route change, resume ----------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.session(plan: RoutePlan, source: SimulatedLocationSource, reroute: Rerouter?, startAlong: Double = 0.0) =
        NavigationSession(plan, source, backgroundScope, NavConfig(), reroute, startAlong) { testScheduler.currentTime + 1_000L }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.feed(source: SimulatedLocationSource, fixes: Sequence<LocationFix>) {
        for (f in fixes) {
            val wait = f.timeMillis - 1_000L - testScheduler.currentTime
            if (wait > 0) delay(wait)
            source.emit(f)
            testScheduler.runCurrent()
        }
    }

    private fun leavingAt(plan: RoutePlan, along: Double): Sequence<LocationFix> {
        val ride = RouteSimulator(plan.geometry, 10.0).fixes().takeWhile { (it.timeMillis - 1_000L) / 1000.0 * 10.0 <= along }.toList()
        val last = ride.last()
        val side = (1..10).map { last.copy(point = pt(80.0 + it * 3.0, along + it * 10.0), timeMillis = last.timeMillis + it * 1_000L, bearingDegrees = 0f) }
        return (ride + side).asSequence()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun rerouteResultsThatStartElsewhereOrGoElsewhereAreDiscardedAndTheOldRouteIsKept() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        var calls = 0
        val bogus = listOf<(LatLon) -> RoutePlan>(
            { RoutePlan(listOf(pt(5_000.0, 0.0), pt(5_000.0, 2_000.0)), 2_000.0, 200.0) }, // starts 5 km away
            { from -> RoutePlan(listOf(from, pt(9_000.0, 9_000.0)), 9_000.0, 900.0) }, // ends elsewhere
            { from -> RoutePlan(listOf(from), 0.0, 0.0) }, // one point
        )
        val s = session(plan, source, { from, _ -> bogus[(calls++).coerceAtMost(bogus.size - 1)](from) })
        s.start()
        feed(source, leavingAt(plan, 500.0))
        delay(60_000)
        assertTrue(calls >= 3)
        assertEquals(0, s.internalErrors)
        s.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun replacingTheRouteInTheMiddleSwitchesCleanlyAndNeverRepeatsPrompts() = runTest {
        val source = SimulatedLocationSource()
        val plan = lPlan()
        val s = session(plan, source, null)
        val prompts = ArrayList<Announcement>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.announcements.collect { prompts += it } }
        s.start()
        feed(source, RouteSimulator(plan.geometry, 15.0).fixes().takeWhile { (it.timeMillis - 1_000L) < 40_000 })
        val rest = RouteBuilder().lineTo(0.0, 1000.0).lineTo(-800.0, 1000.0)
        val other = rest.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(rest.lastIndex, TurnType.ARRIVE)))
        s.replaceRoute(other)
        s.replaceRoute(RoutePlan(emptyList(), 0.0, 0.0)) // unusable: ignored
        testScheduler.runCurrent()
        assertEquals(1, s.state.value.routeRevision)
        assertEquals(other.geometry, s.route.value.geometry)
        val before = prompts.size
        feed(source, RouteSimulator(other.geometry, 15.0, startMillis = testScheduler.currentTime + 2_000L, startAlongMeters = 600.0).fixes())
        assertEquals(NavStatus.ARRIVED, s.state.value.status)
        assertEquals(0, s.internalErrors)
        assertTrue(prompts.size > before)
        s.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aSessionResumedAtAnAlongPositionFollowsFromThere() = runTest {
        val source = SimulatedLocationSource()
        val plan = straightPlan()
        val s = session(plan, source, null, startAlong = 1_500.0)
        assertEquals(1_500.0, s.state.value.traveledMeters, 0.001)
        s.start()
        feed(source, RouteSimulator(plan.geometry, 10.0, startAlongMeters = 1_500.0, startMillis = 1_000L).fixes())
        assertEquals(NavStatus.ARRIVED, s.state.value.status)
        s.stop()
        assertNotNull(s.route.value)
    }
}
