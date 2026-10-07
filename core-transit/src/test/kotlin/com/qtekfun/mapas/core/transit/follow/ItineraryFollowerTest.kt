package com.qtekfun.mapas.core.transit.follow

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.F
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.O
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.fix
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.metroStops
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.onBus
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.onMetro
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.rideMetroTo
import com.qtekfun.mapas.core.transit.follow.FollowFixtures.stopLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The follower on the synthetic trip of [FollowFixtures]: simulated fixes and a hand-driven clock, no real time. */
class ItineraryFollowerTest {
    private val clock = TestClock()
    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected +/- $tolerance but was $actual")

    private fun follower(config: FollowerConfig = FollowerConfig()) = ItineraryFollower(FollowFixtures.itinerary(), config, clock::millis)

    @Test
    fun `before any fix the first walk shows the planned figures and no signal`() {
        val s = follower().state
        assertEquals(FollowPhase.BEFORE_START, s.phase)
        assertEquals(FollowBasis.NO_SIGNAL, s.basis)
        assertEquals("A", s.targetName)
        assertEquals(520, s.walkMeters)
        assertEquals("L5", s.line?.shortName)
        assertEquals(FollowFixtures.T0 + 600, s.boardAt)
        assertNull(s.planOffsetSec)
    }

    @Test
    fun `walking to the first stop shows distance and time left and keeps the connection ok`() {
        val f = follower()
        clock.sec = 0
        val s = f.onFix(fix(O)).state
        assertEquals(FollowPhase.BEFORE_START, s.phase)
        assertEquals(FollowBasis.GNSS, s.basis)
        // 400 m straight line times the 1.3 detour
        assertNear(520, s.walkMeters!!, 15)
        assertNear(416, s.walkSeconds!!, 15)
        assertEquals(ConnectionStatus.OK, s.connection)
        assertEquals(PlanStatus.ON_PLAN, s.plan)
        assertFalse(s.canReplan)
    }

    @Test
    fun `arriving at the stop starts waiting and says board now one minute before the departure`() {
        val f = follower()
        clock.sec = 100
        f.onFix(fix(O))
        clock.sec = 400
        val u = f.onFix(fix(metroStops[0].point))
        assertEquals(FollowPhase.WAITING, u.state.phase)
        assertEquals(200L, u.state.secondsToBoard)
        assertTrue(u.prompts.isEmpty())
        clock.sec = 545
        val boardNow = f.tick().prompts
        assertEquals(listOf(PromptKind.BOARD_NOW), boardNow.map { it.kind })
        assertEquals("L5", boardNow[0].line)
        assertEquals("Westbound", boardNow[0].headsign)
        clock.sec = 550
        assertTrue(f.tick().prompts.isEmpty(), "each prompt is said once")
    }

    @Test
    fun `a slow walk that cannot make the departure is at risk and then missed`() {
        val f = follower()
        clock.sec = 450
        val risky = f.onFix(fix(LatLon(stopLat[0] - 0.0036, -3.0))).state // still at the origin, 150 s to go...
        assertEquals(PlanStatus.BEHIND, risky.plan)
        assertEquals(ConnectionStatus.MISSED, risky.connection)
        assertTrue(risky.canReplan)
        // closer: 120 s of walking left, 150 s to the departure -> margin 30 s -> at risk
        clock.sec = 450
        val g = follower()
        val atRisk = g.onFix(fix(LatLon(stopLat[0] - 0.0013, -3.0))).state
        assertEquals(ConnectionStatus.AT_RISK, atRisk.connection)
        assertFalse(atRisk.canReplan)
    }

    @Test
    fun `at risk and missed are announced once each`() {
        val f = follower()
        clock.sec = 450
        val first = f.onFix(fix(LatLon(stopLat[0] - 0.0013, -3.0)))
        assertEquals(listOf(PromptKind.CONNECTION_AT_RISK), first.prompts.map { it.kind })
        assertEquals("L5", first.prompts[0].line)
        clock.sec = 452
        assertTrue(f.onFix(fix(LatLon(stopLat[0] - 0.0013, -3.0))).prompts.isEmpty())
        clock.sec = 700
        val missed = f.onFix(fix(LatLon(stopLat[0] - 0.0013, -3.0)))
        assertEquals(listOf(PromptKind.CONNECTION_MISSED), missed.prompts.map { it.kind })
        assertTrue(missed.state.canReplan)
    }

    @Test
    fun `waiting well past the scheduled departure offers a re-plan`() {
        val f = follower()
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        clock.sec = 700 // 100 s late: vehicles run late, still waiting
        assertEquals(ConnectionStatus.OK, f.onFix(fix(metroStops[0].point)).state.connection)
        clock.sec = 730 // 130 s late
        val s = f.onFix(fix(metroStops[0].point)).state
        assertEquals(ConnectionStatus.MISSED, s.connection)
        assertTrue(s.canReplan)
        assertEquals(PlanStatus.BEHIND, s.plan)
        assertEquals(2, s.planMinutes)
    }

    @Test
    fun `boarding needs riding speed on the line away from the stop and then reports the next stop`() {
        val f = follower()
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        clock.sec = 600
        // one moving fix is not enough
        val one = f.onFix(fix(LatLon(stopLat[0] + 0.0012, -3.0), speed = 8f)).state
        assertEquals(FollowPhase.WAITING, one.phase)
        clock.sec = 605
        val s = f.onFix(fix(LatLon(stopLat[0] + 0.0020, -3.0), speed = 8f)).state
        assertEquals(FollowPhase.ON_BOARD, s.phase)
        assertEquals("L5", s.line?.shortName)
        assertEquals("B", s.nextStopName)
        assertEquals(4, s.stopsRemaining)
        assertEquals("E", s.alightName)
        assertEquals(FollowFixtures.T0 + 1170, s.alightAt)
        assertEquals(PlanStatus.ON_PLAN, s.plan)
    }

    @Test
    fun `walking past the stop slowly is not boarding`() {
        val f = follower()
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        for (k in 1..4) {
            clock.sec = 400 + 4L * k
            val s = f.onFix(fix(LatLon(stopLat[0] + 0.0004 * k, -3.0), speed = 1.4f)).state
            assertTrue(s.phase != FollowPhase.ON_BOARD)
        }
    }

    @Test
    fun `progress follows the stops and the next stop and remaining count update`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        var s = f.state
        assertEquals("C", s.nextStopName)
        assertEquals(3, s.stopsRemaining)
        clock.sec = 800
        s = f.onFix(fix(onMetro(1, 0.5), speed = 12f)).state
        assertEquals("C", s.nextStopName)
        clock.sec = 870
        s = f.onFix(fix(onMetro(2), speed = 12f)).state
        assertEquals("D", s.nextStopName)
        assertEquals(2, s.stopsRemaining)
        assertEquals(FollowPhase.ON_BOARD, s.phase)
    }

    @Test
    fun `the last stop before the alighting one says get ready, and near it get off now`() {
        val f = follower()
        rideMetroTo(f, clock, 2)
        clock.sec = 1020
        val ready = f.onFix(fix(onMetro(3), speed = 12f))
        assertEquals(FollowPhase.ALIGHT_NEXT, ready.state.phase)
        assertEquals(1, ready.state.stopsRemaining)
        assertEquals("E", ready.state.nextStopName)
        assertEquals(listOf(PromptKind.GET_READY), ready.prompts.map { it.kind })
        assertEquals("E", ready.prompts[0].stop)
        clock.sec = 1080
        assertTrue(f.onFix(fix(onMetro(3, 0.5), speed = 12f)).prompts.isEmpty())
        clock.sec = 1150
        val now = f.onFix(fix(onMetro(3, 0.9), speed = 12f)) // about 100 m before E
        assertEquals(listOf(PromptKind.GET_OFF_NOW), now.prompts.map { it.kind })
        assertFalse(now.prompts[0].estimated)
    }

    @Test
    fun `running late and early on the ride shows minutes against the plan`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 900 + 300
        var s = f.onFix(fix(onMetro(2), speed = 10f)).state // leaving C was due at 900: 300 s late
        assertEquals(PlanStatus.BEHIND, s.plan)
        assertEquals(5, s.planMinutes)
        assertEquals(FollowFixtures.itinerary().arriveAt + 300, s.etaAt)
        clock.sec = 1050 - 200
        s = f.onFix(fix(onMetro(3), speed = 10f)).state // leaving D was due at 1050: 200 s early
        assertEquals(PlanStatus.AHEAD, s.plan)
        assertEquals(3, s.planMinutes)
        clock.sec = 1170 + 60
        s = f.onFix(fix(onMetro(3, 0.5), speed = 10f)).state // inside the dead band again
        assertTrue(s.planOffsetSec!! > 0)
    }

    @Test
    fun `within the tolerance the chip says on plan`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 870 + 80
        assertEquals(PlanStatus.ON_PLAN, f.onFix(fix(onMetro(2), speed = 10f)).state.plan)
    }

    @Test
    fun `progress never goes backwards on noisy fixes`() {
        val f = follower()
        rideMetroTo(f, clock, 2)
        clock.sec = 880
        assertEquals("D", f.onFix(fix(onMetro(2, 0.2), speed = 10f)).state.nextStopName)
        clock.sec = 882
        // a fix that jitters back towards C does not move the next stop back
        val back = f.onFix(fix(onMetro(2, 0.0), speed = 10f, accuracy = 40f)).state
        assertEquals("D", back.nextStopName)
        assertEquals(2, back.stopsRemaining)
        clock.sec = 884
        assertEquals("D", f.onFix(fix(onMetro(2, 0.25), speed = 10f)).state.nextStopName)
    }

    @Test
    fun `skipping several stops needs two agreeing fixes`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 725
        // a single fix at D (two stops ahead of the last one) is held back
        val first = f.onFix(fix(onMetro(3), speed = 10f))
        assertEquals("C", first.state.nextStopName)
        clock.sec = 727
        val second = f.onFix(fix(onMetro(3), speed = 10f)).state
        assertEquals("E", second.nextStopName)
        assertEquals(FollowPhase.ALIGHT_NEXT, second.phase)
    }

    @Test
    fun `fixes with a bad accuracy are ignored`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 730
        val s = f.onFix(fix(onMetro(3), speed = 10f, accuracy = 400f)).state
        assertEquals("C", s.nextStopName)
        assertEquals(FollowBasis.GNSS, s.basis)
    }

    @Test
    fun `reaching the alighting stop starts the transfer walk with the connection margin`() {
        val f = follower()
        rideMetroTo(f, clock, 3)
        clock.sec = 1170
        val s = f.onFix(fix(metroStops[4].point, speed = 0f)).state
        assertEquals(FollowPhase.TRANSFER, s.phase)
        assertEquals("F", s.targetName)
        assertEquals("27", s.line?.shortName)
        assertEquals("Hospital", s.headsign)
        assertEquals(FollowFixtures.T0 + 1500, s.boardAt)
        // 170 m straight line, times 1.3, at 1.25 m/s is about 177 s: margin about 330 - 177
        assertEquals(ConnectionStatus.OK, s.connection)
        assertNear(150, s.connectionMarginSec!!, 20)
    }

    @Test
    fun `a transfer prompt says change here once`() {
        val f = follower()
        rideMetroTo(f, clock, 3)
        clock.sec = 1170
        val u = f.onFix(fix(metroStops[4].point, speed = 0f))
        assertEquals(listOf(PromptKind.CHANGE_HERE), u.prompts.map { it.kind })
        assertEquals("27", u.prompts[0].line)
        clock.sec = 1180
        assertTrue(f.onFix(fix(metroStops[4].point, speed = 0f)).prompts.none { it.kind == PromptKind.CHANGE_HERE })
    }

    @Test
    fun `a late metro puts the bus connection at risk while still on board`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 870 + 250 // 250 s late at C
        val u = f.onFix(fix(onMetro(2), speed = 10f))
        // planned margin 1500 - (1170 + 180) = 150 s; 250 s late -> -100 s
        assertEquals(ConnectionStatus.MISSED, u.state.connection)
        assertTrue(u.state.canReplan)
        assertEquals(listOf(PromptKind.CONNECTION_MISSED), u.prompts.map { it.kind })
        assertEquals("27", u.prompts[0].line)
    }

    @Test
    fun `a missed transfer after getting off offers a re-plan`() {
        val f = follower()
        rideMetroTo(f, clock, 3)
        clock.sec = 1500
        val s = f.onFix(fix(metroStops[4].point, speed = 0f)).state
        assertEquals(FollowPhase.TRANSFER, s.phase)
        assertEquals(ConnectionStatus.MISSED, s.connection)
        assertTrue(s.canReplan)
    }

    @Test
    fun `transfer, bus ride and final walk lead to arrival`() {
        val f = follower()
        rideMetroTo(f, clock, 3)
        clock.sec = 1170
        f.onFix(fix(metroStops[4].point, speed = 0f))
        clock.sec = 1350
        var s = f.onFix(fix(F)).state
        assertEquals(FollowPhase.WAITING, s.phase)
        assertEquals("27", s.line?.shortName)
        assertEquals(150L, s.secondsToBoard)
        clock.sec = 1500
        f.onFix(fix(onBus(0, 0.1), speed = 9f))
        clock.sec = 1505
        s = f.onFix(fix(onBus(0, 0.2), speed = 9f)).state
        assertEquals(FollowPhase.ON_BOARD, s.phase)
        assertEquals("G", s.nextStopName)
        clock.sec = 1620
        s = f.onFix(fix(onBus(1), speed = 9f)).state
        assertEquals(FollowPhase.ALIGHT_NEXT, s.phase)
        assertEquals("H", s.nextStopName)
        clock.sec = 1800
        s = f.onFix(fix(busStopsH(), speed = 0f)).state
        assertEquals(FollowPhase.FINAL_WALK, s.phase)
        assertNull(s.targetName)
        clock.sec = 1900
        val done = f.onFix(fix(FollowFixtures.destination))
        assertEquals(FollowPhase.ARRIVED, done.state.phase)
        assertEquals(listOf(PromptKind.ARRIVED), done.prompts.map { it.kind })
        clock.sec = 1910
        assertTrue(f.tick().prompts.isEmpty())
    }

    private fun busStopsH() = FollowFixtures.busStops[2].point

    @Test
    fun `leaving the line is off plan after several fixes, and coming back recovers`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        val away = LatLon(stopLat[1] + 0.005, -3.02) // about 1.7 km east of the line
        var last: FollowUpdate? = null
        for (k in 1..4) {
            clock.sec = 760L + 5 * k
            last = f.onFix(fix(away, speed = 10f))
            if (k < 4) assertEquals(FollowPhase.ON_BOARD, last.state.phase, "needs several fixes")
        }
        assertEquals(FollowPhase.OFF_PLAN, last!!.state.phase)
        assertTrue(last.state.canReplan)
        assertEquals(listOf(PromptKind.OFF_PLAN), last.prompts.map { it.kind })
        // more off-plan fixes do not repeat the prompt
        clock.sec = 800
        assertTrue(f.onFix(fix(away, speed = 10f)).prompts.isEmpty())
        // back on the line: recovers, no new off-plan prompt
        clock.sec = 840
        val back = f.onFix(fix(onMetro(1, 0.8), speed = 10f))
        assertEquals(FollowPhase.ON_BOARD, back.state.phase)
        assertFalse(back.state.canReplan)
    }

    @Test
    fun `walking away from the target is off plan`() {
        val f = follower()
        clock.sec = 100
        f.onFix(fix(LatLon(stopLat[0] - 0.0020, -3.0)))
        var s = f.state
        for (k in 1..5) {
            clock.sec = 100L + 10 * k
            s = f.onFix(fix(LatLon(stopLat[0] - 0.0020 - 0.0030 * k, -3.0))).state
        }
        assertEquals(FollowPhase.OFF_PLAN, s.phase)
        assertTrue(s.canReplan)
    }

    // ------------------------------------------------------------------------------------------------ underground

    @Test
    fun `without fixes on a metro leg the progress is estimated from the timetable and marked`() {
        val f = follower()
        rideMetroTo(f, clock, 1) // at B, 720 s, on the timetable
        clock.sec = 740
        assertEquals(FollowBasis.GNSS, f.tick().state.basis)
        clock.sec = 720 + 50 // 50 s without a fix: signal lost
        var s = f.tick().state
        assertEquals(FollowBasis.ESTIMATED, s.basis)
        assertTrue(s.estimated)
        assertEquals("C", s.nextStopName)
        clock.sec = 960 // between C (900) and D (1020)
        s = f.tick().state
        assertEquals("D", s.nextStopName)
        assertEquals(2, s.stopsRemaining)
        assertEquals(FollowBasis.ESTIMATED, s.basis)
    }

    @Test
    fun `the estimate holds the last known offset`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 720 + 120
        f.onFix(fix(onMetro(1, 0.1), speed = 10f)) // 840 s at 0.1: scheduled 762 -> 78 s late
        clock.sec = 840 + 60
        val s = f.tick().state // estimated; timetable time 900 - 78 = 822: still before C (870)
        assertEquals(FollowBasis.ESTIMATED, s.basis)
        assertEquals("C", s.nextStopName)
        assertEquals(78, s.planOffsetSec)
    }

    @Test
    fun `the estimate never completes the ride, and a fix after the gap resynchronises`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 1500 // long after the scheduled arrival at E
        var s = f.tick().state
        assertEquals(FollowPhase.ALIGHT_NEXT, s.phase)
        assertTrue(s.estimated)
        assertEquals(1, s.stopsRemaining)
        // the train was in fact slow: a fix near D says so, even though the estimate was further on
        clock.sec = 1510
        s = f.onFix(fix(onMetro(3, 0.2), speed = 9f)).state
        assertEquals(FollowBasis.GNSS, s.basis)
        assertEquals(FollowPhase.ALIGHT_NEXT, s.phase)
        assertEquals("E", s.nextStopName)
        assertEquals(FollowPhase.ALIGHT_NEXT, s.phase)
    }

    @Test
    fun `recovery after a gap may jump over several stops with one fix`() {
        val f = follower()
        rideMetroTo(f, clock, 1)
        clock.sec = 1000
        f.tick() // estimated
        clock.sec = 1010
        val s = f.onFix(fix(onMetro(3, 0.1), speed = 10f)).state
        assertEquals("E", s.nextStopName)
        assertEquals(FollowBasis.GNSS, s.basis)
    }

    @Test
    fun `the estimated get off prompt is marked as an estimate`() {
        val f = follower()
        rideMetroTo(f, clock, 3)
        clock.sec = 1060 // D was left at 1050; fix is 10 s old... then silence
        f.onFix(fix(onMetro(3, 0.1), speed = 10f))
        clock.sec = 1060 + 50
        val ready = f.tick()
        assertEquals(FollowPhase.ALIGHT_NEXT, ready.state.phase)
        assertTrue(ready.state.estimated)
        clock.sec = 1160 // within 20 s of the scheduled arrival at 1170
        val now = f.tick()
        assertEquals(listOf(PromptKind.GET_OFF_NOW), now.prompts.map { it.kind })
        assertTrue(now.prompts[0].estimated)
    }

    @Test
    fun `a bus leg has no timetable estimate, only no signal`() {
        val f = follower()
        rideMetroTo(f, clock, 3)
        clock.sec = 1170
        f.onFix(fix(metroStops[4].point, speed = 0f))
        clock.sec = 1350
        f.onFix(fix(F))
        clock.sec = 1500
        f.onFix(fix(onBus(0, 0.1), speed = 9f))
        clock.sec = 1505
        f.onFix(fix(onBus(0, 0.2), speed = 9f))
        clock.sec = 1700
        val s = f.tick().state
        assertEquals(FollowBasis.NO_SIGNAL, s.basis)
        assertEquals("G", s.nextStopName) // last known, not moved by the clock
    }

    @Test
    fun `boarding underground is estimated only when the last fix was at the stop and the time has come`() {
        val f = follower()
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        clock.sec = 560
        assertEquals(FollowPhase.WAITING, f.tick().state.phase)
        clock.sec = 640 // past the departure, signal lost for 240 s
        val s = f.tick().state
        assertEquals(FollowPhase.ON_BOARD, s.phase)
        assertTrue(s.estimated)
        // a fix still at the stop, standing: the traveller never left
        clock.sec = 650
        val back = f.onFix(fix(metroStops[0].point, speed = 0f)).state
        assertEquals(FollowPhase.WAITING, back.phase)
        assertEquals(FollowBasis.GNSS, back.basis)
    }

    // ------------------------------------------------------------------------------------------------ misc

    @Test
    fun `snapshot restores the leg and progress`() {
        val f = follower()
        rideMetroTo(f, clock, 2)
        val snap = f.snapshot()
        assertEquals(1, snap.legIndex)
        assertTrue(snap.boarded)
        val g = ItineraryFollower(FollowFixtures.itinerary(), FollowerConfig(), clock::millis, snap)
        assertEquals(FollowPhase.ON_BOARD, g.state.phase)
        assertEquals("D", g.state.nextStopName)
        // the first fix after a restart may jump
        clock.sec = 1020
        assertEquals("E", g.onFix(fix(onMetro(3, 0.3), speed = 10f)).state.nextStopName)
    }

    @Test
    fun `an itinerary that starts on a vehicle has no walk phase and waits at the stop`() {
        val rideOnly = com.qtekfun.mapas.core.transit.Itinerary(listOf(FollowFixtures.itinerary().legs[1]))
        val f = ItineraryFollower(rideOnly, FollowerConfig(), clock::millis)
        clock.sec = 100
        val away = f.onFix(fix(LatLon(stopLat[0] - 0.0020, -3.0))).state
        assertEquals(FollowPhase.BEFORE_START, away.phase)
        assertEquals("A", away.targetName)
        clock.sec = 300
        assertEquals(FollowPhase.WAITING, f.onFix(fix(metroStops[0].point)).state.phase)
    }

    @Test
    fun `zero length walks are skipped`() {
        val legs = FollowFixtures.itinerary().legs.toMutableList()
        legs[0] = com.qtekfun.mapas.core.transit.ItineraryLeg.Walk(null, "A", metroStops[0].point, metroStops[0].point, 0, FollowFixtures.T0, FollowFixtures.T0)
        val f = ItineraryFollower(com.qtekfun.mapas.core.transit.Itinerary(legs), FollowerConfig(), clock::millis)
        assertEquals(1, f.state.legIndex)
        assertNotNull(f.state.line)
    }

    @Test
    fun `fixes without an accuracy use the default and do not crash the matching`() {
        val f = follower()
        clock.sec = 400
        val s = f.onFix(com.qtekfun.mapas.core.map.LocationFix(metroStops[0].point)).state
        assertEquals(FollowPhase.WAITING, s.phase)
    }
}
