package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.fix
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.metroStops
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
import com.qtekfun.ultimatemaps.core.transit.rt.RideRealTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The follower with and without real time, on the synthetic trip (its first ride stands for a Cercanias train). */
class FollowerRealTimeTest {
    private val clock = TestClock()
    private var rt: LegRealTime? = null

    /** Answers for the metro ride only (index 1 of the itinerary). */
    private val provider = RideRealTime { ride -> if (ride.line.shortName == "L5") rt else null }

    private fun follower(withRt: Boolean = true): ItineraryFollower {
        val it: Itinerary = FollowFixtures.itinerary()
        return ItineraryFollower(it, FollowerConfig(), clock::millis, realTime = if (withRt) provider else RideRealTime.NONE)
    }

    private fun waitingAtFirstStop(f: ItineraryFollower): FollowState {
        clock.sec = 0
        f.onFix(fix(FollowFixtures.O))
        clock.sec = 400
        return f.onFix(fix(metroStops[0].point)).state
    }

    @Test
    fun `without real time nothing changes`() {
        val s = waitingAtFirstStop(follower())
        assertNull(s.realTime)
        assertEquals(FollowFixtures.T0 + 600, s.boardAt)
        assertEquals(200L, s.secondsToBoard)
    }

    @Test
    fun `a delayed train moves the departure and the behind-plan chip while waiting`() {
        rt = LegRealTime(delaySec = 240)
        val s = waitingAtFirstStop(follower())
        assertEquals(FollowPhase.WAITING, s.phase)
        assertEquals(FollowFixtures.T0 + 840, s.boardAt)
        assertEquals(440L, s.secondsToBoard)
        assertEquals(240, s.planOffsetSec)
        assertEquals(PlanStatus.BEHIND, s.plan)
        assertEquals(4, s.planMinutes)
        assertEquals(240, s.realTime?.delaySec)
    }

    @Test
    fun `board now waits for the delayed departure`() {
        rt = LegRealTime(delaySec = 240)
        val f = follower()
        waitingAtFirstStop(f)
        clock.sec = 545 // one minute before the SCHEDULED time: too early with a 4 minute delay
        assertTrue(f.tick().prompts.isEmpty())
        clock.sec = 785
        assertEquals(listOf(PromptKind.BOARD_NOW), f.tick().prompts.map { it.kind })
    }

    @Test
    fun `waiting past the scheduled time is not a missed train when real time says it is coming`() {
        rt = LegRealTime(delaySec = 600)
        val f = follower()
        waitingAtFirstStop(f)
        clock.sec = 900 // 5 minutes after the schedule, 5 before the real departure; still standing at the stop
        val s = f.onFix(fix(metroStops[0].point)).state
        assertEquals(FollowPhase.WAITING, s.phase)
        assertEquals(ConnectionStatus.OK, s.connection)
        assertFalse(s.canReplan)
        // the same without real time is a missed connection
        val plain = follower(withRt = false)
        waitingAtFirstStop(plain)
        clock.sec = 900
        assertEquals(ConnectionStatus.MISSED, plain.onFix(fix(metroStops[0].point)).state.connection)
    }

    @Test
    fun `a cancelled train is a missed connection and offers re-plan`() {
        rt = LegRealTime(cancelled = true)
        val s = waitingAtFirstStop(follower())
        assertEquals(ConnectionStatus.MISSED, s.connection)
        assertTrue(s.canReplan)
        assertTrue(s.realTime!!.cancelled)
    }

    @Test
    fun `a cancelled train is also seen while still walking to the stop`() {
        rt = LegRealTime(cancelled = true)
        clock.sec = 0
        val s = follower().onFix(fix(FollowFixtures.O)).state
        assertEquals(FollowPhase.BEFORE_START, s.phase)
        assertEquals(ConnectionStatus.MISSED, s.connection)
        assertTrue(s.canReplan)
    }

    @Test
    fun `on board the real delay replaces the schedule comparison`() {
        // the traveller rides exactly on the timetable, but the feed says the train is 3 minutes late
        rt = LegRealTime(delaySec = 180)
        val f = follower()
        FollowFixtures.rideMetroTo(f, clock, 2)
        val s = f.tick().state
        assertEquals(FollowPhase.ON_BOARD, s.phase)
        assertEquals(180, s.planOffsetSec)
        assertEquals(PlanStatus.BEHIND, s.plan)
        assertEquals(3, s.planMinutes)
        assertEquals(FollowFixtures.T0 + 1920 + 180, s.etaAt)
    }

    @Test
    fun `on board without real time the schedule comparison is unchanged`() {
        val f = follower(withRt = false)
        FollowFixtures.rideMetroTo(f, clock, 2)
        val s = f.tick().state
        assertEquals(PlanStatus.ON_PLAN, s.plan)
        assertNull(s.realTime)
    }

    @Test
    fun `alerts alone reach the state without changing times`() {
        rt = LegRealTime(alerts = listOf("Retrasos por obras"))
        val s = waitingAtFirstStop(follower())
        assertEquals(listOf("Retrasos por obras"), s.realTime!!.alerts)
        assertEquals(FollowFixtures.T0 + 600, s.boardAt)
        assertEquals(PlanStatus.ON_PLAN, s.plan)
    }

    @Test
    fun `a failing provider never breaks the follower`() {
        val broken = RideRealTime { _ -> throw IllegalStateException("boom") }
        val f = ItineraryFollower(FollowFixtures.itinerary(), FollowerConfig(), clock::millis, realTime = broken)
        clock.sec = 0
        assertEquals(FollowPhase.BEFORE_START, f.onFix(fix(FollowFixtures.O)).state.phase)
    }
}
