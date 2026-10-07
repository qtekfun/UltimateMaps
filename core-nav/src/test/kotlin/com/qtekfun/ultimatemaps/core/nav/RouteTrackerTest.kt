package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.SpeedLimit
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTrackerTest {
    private fun sim(plan: RoutePlan, speed: Double = 10.0, noise: Double = 0.0, seed: Long = 7, interval: Long = 1000, gaps: List<LongRange> = emptyList()) =
        RouteSimulator(plan.geometry, speed, interval, noise, seed, gaps)

    private fun assertMonotone(run: Run) {
        run.states.zipWithNext().forEach { (a, b) ->
            assertTrue(b.traveledMeters >= a.traveledMeters - 1e-9, "progress went back: ${a.traveledMeters} -> ${b.traveledMeters}")
        }
    }

    /** Largest |reported - true| progress over the run, with the true progress from the simulator clock. */
    private fun maxError(run: Run, speed: Double): Double =
        run.states.indices.maxOf { i ->
            if (run.states[i].status == NavStatus.ARRIVED) 0.0
            else abs(run.states[i].traveledMeters - speed * (run.fixes[i].timeMillis - 1000) / 1000.0)
        }

    private fun assertAnnouncementsSane(run: Run, plan: RoutePlan) {
        val seen = HashSet<Pair<Maneuver, AnnouncementKind>>()
        for (a in run.announcements) assertTrue(seen.add(a.maneuver to a.kind), "duplicate $a")
        // Per maneuver, kinds only get more urgent.
        run.announcements.groupBy { it.maneuver }.forEach { (_, list) ->
            assertEquals(list.map { it.kind.ordinal }.sorted(), list.map { it.kind.ordinal })
        }
        // Maneuvers are announced in route order.
        val order = plan.guidance.maneuvers.map { it.geometryIndex }
        val announcedOrder = run.announcements.map { it.maneuver.geometryIndex }
        assertEquals(announcedOrder.sorted(), announcedOrder)
        assertTrue(order.containsAll(announcedOrder))
    }

    @Test fun straightRouteIsFollowedToTheEnd() {
        val plan = straightPlan()
        val run = track(plan, sim(plan).fixes())
        assertMonotone(run)
        assertTrue(maxError(run, 10.0) < 2.0, "error ${maxError(run, 10.0)}")
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE })
        assertEquals(0.0, run.states.last().remainingMeters, 1e-6)
        assertAnnouncementsSane(run, plan)
    }

    @Test fun lRouteAnnouncesTheTurnOnceEachLevelInOrder() {
        val plan = lPlan()
        val run = track(plan, sim(plan).fixes())
        val turn = run.announcements.filter { it.maneuver.type == TurnType.RIGHT }
        assertEquals(listOf(AnnouncementKind.FAR, AnnouncementKind.NEAR, AnnouncementKind.NOW), turn.map { it.kind })
        // At 10 m/s: far 300 m, near 100 m, now 30 m.
        assertTrue(turn[0].meters in 250..300, "far at ${turn[0].meters}")
        assertTrue(turn[1].meters in 85..100, "near at ${turn[1].meters}")
        assertTrue(turn[2].meters in 20..30, "now at ${turn[2].meters}")
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        assertAnnouncementsSane(run, plan)
    }

    @Test fun nextAndFollowingManeuverAdvanceAsTheTurnIsPassed() {
        val plan = lPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(plan.geometry.first(), 1000))
        val s0 = tracker.snapshot()
        assertEquals(TurnType.RIGHT, s0.nextManeuver?.maneuver?.type)
        assertEquals(TurnType.ARRIVE, s0.followingManeuver?.maneuver?.type)
        assertEquals(1000.0, s0.nextManeuver!!.distanceMeters, 1.0)
        // Drive past the corner on a fresh tracker (at 10 m/s, from 950 m on).
        val tracker2 = RouteTracker(plan)
        RouteSimulator(plan.geometry, 10.0, startAlongMeters = 950.0).fixes().take(10).forEach(tracker2::onFix)
        val s1 = tracker2.snapshot()
        assertEquals(TurnType.ARRIVE, s1.nextManeuver?.maneuver?.type)
        assertNull(s1.followingManeuver)
    }

    @Test fun roundaboutDoesNotLoseTheRoute() {
        val plan = roundaboutPlan()
        val run = track(plan, sim(plan, speed = 8.0).fixes())
        assertMonotone(run)
        assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE }, "went off route in the roundabout")
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        val types = run.announcements.map { it.maneuver.type }.distinct()
        assertTrue(TurnType.ROUNDABOUT_ENTER in types && TurnType.ROUNDABOUT_LEAVE in types, "$types")
        assertAnnouncementsSane(run, plan)
    }

    @Test fun uTurnStaysOnTheOutwardLegThenTheReturnLeg() {
        val plan = uTurnPlan()
        // Legs are 30 m apart, noise 5 m: the matcher must not hop to the other leg.
        val run = track(plan, sim(plan, noise = 5.0, seed = 3).fixes())
        assertMonotone(run)
        assertTrue(maxError(run, 10.0) < 25.0, "error ${maxError(run, 10.0)}")
        assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE })
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        assertTrue(run.announcements.any { it.maneuver.type == TurnType.U_TURN_RIGHT && it.kind == AnnouncementKind.NOW })
    }

    @Test fun routeThatPassesTwiceOverTheSameRoadIsNotConfused() {
        val plan = loopPlan()
        val run = track(plan, sim(plan, noise = 5.0, seed = 11).fixes())
        assertMonotone(run)
        assertTrue(maxError(run, 10.0) < 25.0, "error ${maxError(run, 10.0)}")
        assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE })
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        // The five real maneuvers (4 corners + arrival) announced exactly once per level.
        assertAnnouncementsSane(run, plan)
        assertEquals(5, run.announcements.map { it.maneuver }.distinct().size)
    }

    @Test fun loopRouteWithoutBearingStillAdvances() {
        // Without heading information the window alone must keep the second pass from snapping back.
        val plan = loopPlan()
        val fixes = sim(plan, noise = 3.0, seed = 5).fixes().map { it.copy(bearingDegrees = null) }
        val run = track(plan, fixes)
        assertMonotone(run)
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        assertTrue(maxError(run, 10.0) < 25.0)
    }

    @Test fun gpsNoiseOf5MetresNeverLeavesTheRoute() {
        for (seed in 1L..20L) {
            val plan = lPlan()
            val run = track(plan, sim(plan, noise = 5.0, seed = seed).fixes())
            assertMonotone(run)
            assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE }, "seed $seed went off route")
            assertTrue(maxError(run, 10.0) < 15.0, "seed $seed error ${maxError(run, 10.0)}")
            assertEquals(NavStatus.ARRIVED, run.states.last().status, "seed $seed")
            assertAnnouncementsSane(run, plan)
        }
    }

    @Test fun gpsNoiseOf20MetresNeverLeavesTheRoute() {
        for (seed in 1L..20L) {
            val plan = lPlan()
            val run = track(plan, sim(plan, noise = 20.0, seed = seed).fixes())
            assertMonotone(run)
            assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE }, "seed $seed went off route")
            assertTrue(maxError(run, 10.0) < 45.0, "seed $seed error ${maxError(run, 10.0)}")
            assertEquals(NavStatus.ARRIVED, run.states.last().status, "seed $seed")
            assertAnnouncementsSane(run, plan)
        }
    }

    @Test fun anIsolatedGarbageFixDoesNotMoveOrDerailTheTracker() {
        val plan = straightPlan()
        val clean = sim(plan).fixes().toList()
        // A fix 3 km east that claims good accuracy, and another one with an absurd accuracy.
        val dirty = clean.toMutableList().also {
            it[60] = it[60].copy(point = pt(3000.0, 600.0))
            it[100] = it[100].copy(point = pt(-2000.0, 1000.0), accuracyMeters = 900f)
        }
        val run = track(plan, dirty.asSequence())
        assertMonotone(run)
        assertTrue(run.states.none { it.status == NavStatus.OFF_ROUTE })
        assertEquals(clean.size, run.states.size)
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        // The garbage fix left progress where it was.
        assertEquals(run.states[59].traveledMeters, run.states[60].traveledMeters, 1e-9)
    }

    @Test fun threeConsecutiveFarFixesDoTriggerOffRoute() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 100.0), 1000))
        for (i in 1..2) {
            tracker.onFix(fix(pt(500.0, 100.0 + i * 10), 1000L + i * 1000))
            assertEquals(NavStatus.ON_ROUTE, tracker.status, "after $i far fixes")
        }
        tracker.onFix(fix(pt(500.0, 130.0), 4000))
        assertEquals(NavStatus.OFF_ROUTE, tracker.status)
    }

    @Test fun leavingTheRouteIsConfirmedOnlyAfterEnoughFixesAndTime() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000))
        // 45 m to the side (above max(30, 2*5) but below the "far" factor of 3x): slow confirmation.
        var status = tracker.status
        var confirmedAt = -1
        for (i in 1..8) {
            tracker.onFix(fix(pt(45.0, 500.0 + i * 10.0), 1000L + i * 1000L, bearing = 0f))
            status = tracker.status
            if (status == NavStatus.OFF_ROUTE && confirmedAt < 0) confirmedAt = i
        }
        assertEquals(5, confirmedAt, "needs ${NavConfig().offRouteFixes} fixes")
    }

    @Test fun slowFixRateStillConfirmsAfterTheTimeLimit() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000))
        tracker.onFix(fix(pt(45.0, 510.0), 6000))
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        tracker.onFix(fix(pt(45.0, 520.0), 12_000)) // 6 s after the first off fix... not yet 10 s
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        tracker.onFix(fix(pt(45.0, 530.0), 17_000)) // 11 s
        assertEquals(NavStatus.OFF_ROUTE, tracker.status)
    }

    @Test fun anOnRouteFixResetsTheOffRouteCount() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000))
        var t = 2000L
        repeat(10) { round ->
            repeat(4) { tracker.onFix(fix(pt(45.0, 500.0 + round), t, bearing = 0f)); t += 1000 }
            tracker.onFix(fix(pt(0.0, 500.0 + round), t, bearing = 0f)); t += 1000
            assertEquals(NavStatus.ON_ROUTE, tracker.status)
        }
    }

    @Test fun accuracyWidensTheOffRouteThreshold() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000, acc = 40f))
        // 60 m away is beyond 30 m, but within 2 x 40 m accuracy: never off route.
        for (i in 1..30) tracker.onFix(fix(pt(60.0, 500.0 + i), 1000L + i * 1000L, acc = 40f, bearing = 0f))
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
    }

    @Test fun returningToTheRouteRejoins() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000))
        var t = 2000L
        for (i in 1..6) { tracker.onFix(fix(pt(80.0, 500.0 + i * 10), t, bearing = 0f)); t += 1000 }
        assertEquals(NavStatus.OFF_ROUTE, tracker.status)
        tracker.setRerouting(true)
        assertEquals(NavStatus.REROUTING, tracker.status)
        // A fix in the "between" band does not rejoin; one close to the road does.
        tracker.onFix(fix(pt(25.0, 600.0), t, bearing = 0f)); t += 1000
        assertEquals(NavStatus.REROUTING, tracker.status)
        tracker.onFix(fix(pt(3.0, 620.0), t, bearing = 0f))
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        assertEquals(620.0, tracker.snapshot().traveledMeters, 2.0)
    }

    @Test fun drivingTheRouteTheWrongWayCountsAsOffRoute() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000, bearing = 0f))
        var t = 2000L
        for (i in 1..8) { tracker.onFix(fix(pt(0.0, 500.0 - i * 10.0), t, bearing = 180f)); t += 1000 }
        assertEquals(NavStatus.OFF_ROUTE, tracker.status)
    }

    @Test fun arrivesWithinTheRadius() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        val fixes = RouteSimulator(plan.geometry, 10.0, startAlongMeters = 1900.0).fixes().toList()
        // Fixes every 10 m: 1900, 1910, ... 1970 (30 m left), then 1980 (20 m left).
        fixes.take(8).forEach(tracker::onFix)
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        tracker.onFix(fixes[8])
        assertEquals(NavStatus.ARRIVED, tracker.status)
        val s = tracker.snapshot()
        assertEquals(0.0, s.remainingMeters, 1e-9)
        assertEquals(0.0, s.remainingSeconds, 1e-9)
        // Final: nothing changes afterwards.
        tracker.onFix(fix(pt(0.0, 100.0), 4000))
        assertEquals(NavStatus.ARRIVED, tracker.status)
    }

    @Test fun arrivesWhenStoppedNearTheEnd() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        val ride = RouteSimulator(plan.geometry, 10.0, startAlongMeters = 1900.0).fixes().take(6).toList() // up to 1950
        ride.forEach(tracker::onFix)
        // 50 m short, stopped: not arrived at first, arrived after the stop time.
        var t = ride.last().timeMillis + 1000
        repeat(7) { tracker.onFix(fix(pt(0.0, 1950.0), t, speed = 0f)); t += 1000 }
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        repeat(3) { tracker.onFix(fix(pt(0.0, 1950.0), t, speed = 0f)); t += 1000 }
        assertEquals(NavStatus.ARRIVED, tracker.status)
    }

    @Test fun doesNotArriveJustBecauseTheRouteEndsWhereItStarts() {
        val b = RouteBuilder().lineTo(300.0, 0.0).lineTo(300.0, 300.0).lineTo(0.0, 300.0).lineTo(0.0, 0.0)
        val plan = b.plan(listOf(maneuver(b.lastIndex, TurnType.ARRIVE)))
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(2.0, 1.0), 1000, bearing = 90f))
        tracker.onFix(fix(pt(12.0, 0.0), 2000, bearing = 90f))
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        assertTrue(tracker.snapshot().remainingMeters > 1100)
    }

    @Test fun fastAnnouncementsAreNeitherSkippedNorRepeated() {
        // 36 m/s (130 km/h) with a fix every 2 s: 72 m between fixes.
        val plan = lPlan()
        val run = track(plan, sim(plan, speed = 36.0, interval = 2000).fixes())
        val byManeuver = run.announcements.groupBy { it.maneuver.type }
        val turn = byManeuver.getValue(TurnType.RIGHT)
        assertTrue(turn.any { it.kind == AnnouncementKind.NOW }, "the turn was never announced as NOW: $turn")
        assertEquals(turn.size, turn.map { it.kind }.distinct().size)
        assertTrue(byManeuver.getValue(TurnType.ARRIVE).any { it.kind == AnnouncementKind.NOW })
        assertAnnouncementsSane(run, plan)
        // Thresholds scale with speed: far = 36 * 30 = 1080 m, so the first prompt is early.
        assertTrue(turn.first().meters > 600, "first prompt at ${turn.first().meters}")
    }

    @Test fun walkingAnnouncementsUseTheMinimumDistances() {
        val plan = lPlan()
        val run = track(plan, sim(plan, speed = 1.4).fixes().take(760), NavConfig())
        val turn = run.announcements.filter { it.maneuver.type == TurnType.RIGHT }
        assertEquals(listOf(AnnouncementKind.FAR, AnnouncementKind.NEAR, AnnouncementKind.NOW), turn.map { it.kind })
        assertTrue(turn[0].meters in 190..200, "far at ${turn[0].meters}")
        assertTrue(turn[1].meters in 55..60)
        assertTrue(turn[2].meters in 17..20)
    }

    @Test fun twoCloseManeuversAreEachAnnouncedAtTheirOwnPace() {
        val b = RouteBuilder().lineTo(0.0, 500.0)
        val a = b.lastIndex
        b.lineTo(80.0, 500.0)
        val c = b.lastIndex
        b.lineTo(80.0, 1000.0)
        val plan = b.plan(listOf(maneuver(a, TurnType.RIGHT), maneuver(c, TurnType.LEFT), maneuver(b.lastIndex, TurnType.ARRIVE)))
        val run = track(plan, sim(plan).fixes())
        assertAnnouncementsSane(run, plan)
        val kinds = run.announcements.filter { it.maneuver.type == TurnType.LEFT }.map { it.kind }
        assertTrue(AnnouncementKind.NOW in kinds, "$kinds")
    }

    @Test fun firstFixInTheMiddleSkipsThePassedManeuvers() {
        val plan = lPlan()
        val run = track(plan, RouteSimulator(plan.geometry, 10.0, startAlongMeters = 1500.0).fixes())
        assertTrue(run.announcements.none { it.maneuver.type == TurnType.RIGHT })
        assertEquals(1500.0, run.states.first().traveledMeters, 15.0)
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
    }

    @Test fun speedLimitFollowsTheRouteAndOverspeedHasHysteresis() {
        val b = RouteBuilder().lineTo(0.0, 1000.0)
        val mid = b.lastIndex
        b.lineTo(0.0, 2000.0)
        val plan = b.plan(
            listOf(maneuver(b.lastIndex, TurnType.ARRIVE)),
            listOf(SpeedLimit(0, mid, 50), SpeedLimit(mid, b.lastIndex, null)),
        )
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 100.0), 1000, speed = 12f))
        assertEquals(50, tracker.snapshot().speedLimitKmh)
        assertFalse(tracker.snapshot().overSpeedLimit) // 43 km/h
        // 16 m/s = 57.6 km/h: over. EMA speed takes a couple of fixes to settle.
        var t = 2000L
        repeat(4) { tracker.onFix(fix(pt(0.0, 100.0 + (t / 1000) * 16), t, speed = 16f)); t += 1000 }
        assertTrue(tracker.snapshot().overSpeedLimit)
        // 13.5 m/s = 48.6 km/h: below 50 but within the 2 km/h hysteresis of the limit -> still flagged.
        repeat(6) { tracker.onFix(fix(pt(0.0, 200.0 + (t / 1000) * 14), t, speed = 13.7f)); t += 1000 }
        assertTrue(tracker.snapshot().overSpeedLimit, "hysteresis should keep it on at ${tracker.snapshot().speedMps * 3.6} km/h")
        // 11 m/s = 39.6 km/h: clearly under.
        repeat(8) { tracker.onFix(fix(pt(0.0, 300.0 + (t / 1000) * 11), t, speed = 11f)); t += 1000 }
        assertFalse(tracker.snapshot().overSpeedLimit)
        // Past the unlimited section there is no limit and no overspeed.
        tracker.onFix(fix(pt(0.0, 1500.0), t + 40_000, speed = 30f))
        assertNull(tracker.snapshot().speedLimitKmh)
        assertFalse(tracker.snapshot().overSpeedLimit)
    }

    @Test fun overspeedFiresOnlyStrictlyAboveTheLimitAndToleranceAtTheBoundary() {
        fun over(limit: Int, speedKmh: Float, tolerance: Double = 0.0): Boolean {
            val b = RouteBuilder().lineTo(0.0, 5000.0)
            val plan = b.plan(listOf(maneuver(b.lastIndex, TurnType.ARRIVE)), listOf(SpeedLimit(0, b.lastIndex, limit)))
            val tracker = RouteTracker(plan, NavConfig(speedToleranceKmh = tolerance))
            var t = 1000L
            repeat(6) { tracker.onFix(fix(pt(0.0, 100.0 + (t / 1000) * speedKmh / 3.6), t, speed = speedKmh / 3.6f)); t += 1000 }
            return tracker.snapshot().overSpeedLimit
        }
        assertFalse(over(50, 50f), "exactly the limit (the device test showed a warning here)")
        assertFalse(over(50, 50.4f), "rounds to 50 on the speedometer")
        assertTrue(over(50, 51f))
        assertFalse(over(50, 55f, tolerance = 5.0), "at limit + tolerance")
        assertTrue(over(50, 56f, tolerance = 5.0))
    }

    @Test fun lanesOfTheNextManeuverArePublished() {
        val lanes = listOf(
            Lane(setOf(LaneDirection.LEFT), recommended = false),
            Lane(setOf(LaneDirection.THROUGH, LaneDirection.RIGHT), recommended = true),
        )
        val b = RouteBuilder().lineTo(0.0, 500.0)
        val corner = b.lastIndex
        b.lineTo(500.0, 500.0)
        val plan = b.plan(listOf(Maneuver(corner, TurnType.RIGHT, lanes = lanes), maneuver(b.lastIndex, TurnType.ARRIVE)))
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 10.0), 1000))
        assertEquals(lanes, tracker.snapshot().lanes)
        val tracker2 = RouteTracker(plan)
        RouteSimulator(plan.geometry, 10.0, startAlongMeters = 450.0).fixes().take(10).forEach(tracker2::onFix)
        assertEquals(emptyList(), tracker2.snapshot().lanes)
    }

    @Test fun remainingTimeScalesWithRemainingDistance() {
        val plan = straightPlan().copy(durationSeconds = 200.0)
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 1000.0), 1000))
        val s = tracker.snapshot()
        assertEquals(100.0, s.remainingSeconds, 1.0)
        assertEquals(1000.0, s.remainingMeters, 1.0)
        assertEquals(1000.0, s.traveledMeters, 1.0)
        assertEquals(0f, s.bearingDegrees, 0.5f)
    }

    @Test fun signalLossEstimatesBySpeedThenResyncs() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        var t = 1000L
        for (i in 0..20) { tracker.onFix(fix(pt(0.0, i * 10.0), t, speed = 10f, bearing = 0f)); t += 1000 }
        val last = t - 1000
        assertFalse(tracker.onTick(last + 4000), "still within the loss delay")
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
        assertTrue(tracker.onTick(last + 10_000))
        var s = tracker.snapshot()
        assertEquals(NavStatus.NO_SIGNAL, s.status)
        assertTrue(s.estimated)
        assertEquals(300.0, s.traveledMeters, 3.0) // 200 + 10 m/s * 10 s
        assertTrue(tracker.onTick(last + 20_000))
        assertEquals(400.0, tracker.snapshot().traveledMeters, 3.0)
        // The estimate stops after the max duration (30 s).
        tracker.onTick(last + 60_000)
        assertEquals(500.0, tracker.snapshot().traveledMeters, 3.0)
        // Signal back: the real position wins (here the vehicle was slower), and it is not estimated any more.
        tracker.onFix(fix(pt(0.0, 330.0), last + 61_000, speed = 10f, bearing = 0f))
        s = tracker.snapshot()
        assertEquals(NavStatus.ON_ROUTE, s.status)
        assertFalse(s.estimated)
        assertEquals(330.0, s.traveledMeters, 2.0)
    }

    @Test fun tunnelInASimulatedRideKeepsAnnouncingAndLandsAgain() {
        val plan = lPlan()
        // 20 s tunnel in the middle of the first leg.
        val fixes = sim(plan, gaps = listOf(30_000L..50_000L)).fixes().toList()
        val tracker = RouteTracker(plan)
        val announcements = ArrayList<Announcement>()
        val noisy = RouteTracker(plan, onAnnouncement = { announcements += it })
        var lastT = 0L
        var sawEstimate = false
        for (f in fixes) {
            // Tick once per second through the silence, like the session does.
            var t = lastT + 1000
            while (lastT != 0L && t < f.timeMillis) { if (noisy.onTick(t)) sawEstimate = sawEstimate || noisy.snapshot().estimated; t += 1000 }
            noisy.onFix(f)
            tracker.onFix(f)
            lastT = f.timeMillis
        }
        assertTrue(sawEstimate)
        assertEquals(NavStatus.ARRIVED, noisy.status)
        assertEquals(1, announcements.count { it.maneuver.type == TurnType.RIGHT && it.kind == AnnouncementKind.NOW })
    }

    @Test fun signalLossWhileOffRouteStaysOffRoute() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 1000))
        var t = 2000L
        repeat(6) { tracker.onFix(fix(pt(80.0, 500.0 + it * 10), t, bearing = 0f)); t += 1000 }
        assertEquals(NavStatus.OFF_ROUTE, tracker.status)
        tracker.onTick(t + 20_000)
        assertEquals(NavStatus.OFF_ROUTE, tracker.status)
    }

    @Test fun noFixesMeansNoTicksEffect() {
        val tracker = RouteTracker(straightPlan())
        assertFalse(tracker.onTick(1_000_000))
        val s = tracker.snapshot()
        assertEquals(NavStatus.ON_ROUTE, s.status)
        assertEquals(0.0, s.traveledMeters)
        assertEquals(plan0Length(), s.remainingMeters, 1.0)
        assertNotNull(s.nextManeuver)
    }

    private fun plan0Length() = RouteGeometry(straightPlan().geometry).totalMeters

    @Test fun routeWithoutGuidanceStillTracks() {
        val plan = straightPlan().copy(guidance = com.qtekfun.ultimatemaps.core.routing.RouteGuidance.EMPTY)
        val run = track(plan, sim(plan).fixes())
        assertEquals(NavStatus.ARRIVED, run.states.last().status)
        assertTrue(run.announcements.isEmpty())
        assertNull(run.states.first().nextManeuver)
    }

    @Test fun staleTimestampsDoNotMoveProgressBackwards() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        tracker.onFix(fix(pt(0.0, 500.0), 10_000))
        tracker.onFix(fix(pt(0.0, 510.0), 11_000))
        val before = tracker.snapshot().traveledMeters
        tracker.onFix(fix(pt(0.0, 400.0), 9_000)) // out-of-order fix
        assertTrue(tracker.snapshot().traveledMeters >= before)
    }

    @Test fun stationaryNoiseDoesNotMakeProgressCreepForward() {
        val plan = straightPlan()
        val tracker = RouteTracker(plan)
        val random = java.util.Random(4)
        tracker.onFix(fix(pt(0.0, 500.0), 1000, speed = 0f))
        repeat(600) {
            val p = pt(random.nextGaussian() * 8, 500.0 + random.nextGaussian() * 8)
            tracker.onFix(fix(p, 2000L + it * 1000L, acc = 8f, speed = 0f))
        }
        assertEquals(500.0, tracker.snapshot().traveledMeters, 1.0)
        assertEquals(NavStatus.ON_ROUTE, tracker.status)
    }

    @Test fun geometrySearchUsesWindowAndHeadingForOverlap() {
        val g = RouteGeometry(loopPlan().geometry)
        val match = RouteMatch()
        // The point (200, 0) is both at along 200 and at along 1600 + 200.
        g.search(ORIGIN_LAT + 0.0, ORIGIN_LON + 200.0 / (111_194.9266 * Math.cos(Math.toRadians(ORIGIN_LAT))), 1400.0, 2000.0, 90.0, 25.0, match)
        assertEquals(1800.0, match.along, 2.0)
        g.search(pt(200.0, 0.0).lat, pt(200.0, 0.0).lon, 0.0, 500.0, Double.NaN, 25.0, match)
        assertEquals(200.0, match.along, 2.0)
        assertTrue(match.distance < 1.0)
    }

    @Test fun invalidInputsAreRejected() {
        kotlin.runCatching { RouteGeometry(listOf(LatLon(0.0, 0.0))) }.let { assertTrue(it.isFailure) }
        val fixNoAccuracy = LocationFix(pt(0.0, 10.0), null, null, null, 1000)
        val tracker = RouteTracker(straightPlan())
        tracker.onFix(fixNoAccuracy)
        assertEquals(10.0, tracker.snapshot().traveledMeters, 1.0)
    }
}
