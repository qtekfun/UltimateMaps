package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.fix
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.metroStops
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.rideMetroTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Knowing whether the traveller is aboard: the follower's evidence, the manual answer and a re-plan that starts on the vehicle. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardDetectionTest {
    private val clock = TestClock()
    private val dir = java.nio.file.Files.createTempDirectory("onboard-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun follower() = ItineraryFollower(FollowFixtures.itinerary(), FollowerConfig(), clock::millis)

    @Test
    fun `a fast train far from the straight line between stops is still recognised as boarded`() {
        val f = follower()
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        // the track curves 425 m east of the stop-to-stop line: outside the normal corridor
        val east = -3.005
        clock.sec = 600
        f.onFix(fix(LatLon(40.0060, east), speed = 20f))
        clock.sec = 605
        f.onFix(fix(LatLon(40.0070, east), speed = 20f))
        assertEquals(FollowPhase.WAITING, f.state.phase)
        clock.sec = 610
        f.onFix(fix(LatLon(40.0080, east), speed = 20f))
        assertEquals(FollowPhase.ON_BOARD, f.state.phase)
        assertNotNull(f.boardedRide())
    }

    private fun slowTrainFixes(f: ItineraryFollower) {
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        val east = -3.005
        for (k in 0 until 4) {
            clock.sec = 600L + k * 5
            f.onFix(fix(LatLon(40.0060 + k * 0.0004, east), speed = 4f)) // 14 km/h, far from the straight line
        }
    }

    @Test
    fun `a slow train off the line is boarded only when the system says the device is in a vehicle`() {
        val without = follower()
        slowTrainFixes(without)
        assertNull(without.boardedRide())
        val hinted = ItineraryFollower(FollowFixtures.itinerary(), FollowerConfig(), clock::millis, inVehicle = { true })
        slowTrainFixes(hinted)
        assertNotNull(hinted.boardedRide())
        val onFoot = ItineraryFollower(FollowFixtures.itinerary(), FollowerConfig(), clock::millis, inVehicle = { false })
        slowTrainFixes(onFoot)
        assertNull(onFoot.boardedRide())
    }

    @Test
    fun `walking along the track at walking speed is never taken for a train`() {
        val f = follower()
        clock.sec = 400
        f.onFix(fix(metroStops[0].point))
        for (k in 0 until 20) {
            clock.sec = 600L + k * 5
            f.onFix(fix(LatLon(40.0042 + k * 0.00006, -3.005), speed = 1.4f))
        }
        assertNull(f.boardedRide())
        assertTrue(f.state.phase != FollowPhase.ON_BOARD)
    }

    @Test
    fun `saying I am on the train boards the ride where the last fix is and the fixes keep it`() {
        val f = follower()
        clock.sec = 700
        f.onFix(fix(FollowFixtures.onMetro(0, 0.5))) // no speed: nothing proved it
        assertTrue(f.state.phase != FollowPhase.ON_BOARD)
        assertTrue(f.assumeBoarded())
        assertEquals(FollowPhase.ON_BOARD, f.state.phase)
        assertEquals("B", f.state.nextStopName)
        clock.sec = 730
        f.onFix(fix(FollowFixtures.onMetro(0, 0.9), speed = 10f))
        assertEquals(FollowPhase.ON_BOARD, f.state.phase)
    }

    @Test
    fun `saying I am not on the train goes back to waiting for it`() {
        val f = follower()
        FollowFixtures.rideMetroTo(f, clock, 1)
        assertEquals(FollowPhase.ON_BOARD, f.state.phase)
        f.assumeNotBoarded()
        assertNull(f.boardedRide())
        assertTrue(f.state.phase != FollowPhase.ON_BOARD)
    }

    // ------------------------------------------------------------------------------------------ re-plan aboard

    private class Rig(scope: TestScope, dir: File, replanner: TransitReplanner) {
        val location = SimulatedLocationSource()
        val clock = { FollowFixtures.T0 * 1000 + scope.testScheduler.currentTime }
        val controller = TransitTripController(scope, location, TransitTripStore(File(dir, "t.bin"), clock), replanner, clock = clock)
    }

    private fun TestScope.at(sec: Long) {
        val delta = sec * 1000 - testScheduler.currentTime
        if (delta > 0) advanceTimeBy(delta)
        runCurrent()
    }

    private fun TestScope.rideTo(rig: Rig, stop: Int) {
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        at(400)
        rig.location.emit(fix(metroStops[0].point))
        at(600)
        rig.location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0012, -3.0), speed = 8f))
        at(605)
        rig.location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0020, -3.0), speed = 8f))
        at(720)
        rig.location.emit(fix(FollowFixtures.onMetro(stop), speed = 10f))
    }

    @Test
    fun `replanning aboard starts from the next stops of the vehicle and never asks to board again`() = runTest(UnconfinedTestDispatcher()) {
        val requests = mutableListOf<Pair<LatLon, Instant>>()
        val rig = Rig(this, dir) { from, to, at ->
            requests.add(from to at)
            // from any stop: walk to the destination
            Itinerary(listOf(ItineraryLeg.Walk(null, null, from, to, 800, at.epochSecond, at.epochSecond + 700)))
        }
        rideTo(rig, 1)
        assertEquals(FollowPhase.ON_BOARD, rig.controller.state.value!!.follow.phase)
        assertTrue(rig.controller.replan())
        runCurrent()
        // asked from the stops ahead of the vehicle (C, D, E), never from the traveller's own position
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { (p, _) -> metroStops.drop(2).any { it.point == p } })
        // the earliest arrival wins: getting off at C
        val s = rig.controller.state.value!!
        assertEquals(FollowPhase.ALIGHT_NEXT, s.follow.phase) // still on the vehicle, and C is the next stop
        assertEquals("C", s.follow.alightName)
        val ride = s.itinerary.legs[0] as ItineraryLeg.Ride
        assertEquals("C", ride.alighting.name)
        assertEquals(ItineraryLeg.Walk::class, s.itinerary.legs[1]::class)
        rig.controller.stop()
    }

    @Test
    fun `replanning aboard keeps staying on the same vehicle as one ride`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, dir) { from, to, at ->
            val base = FollowFixtures.itinerary().legs[1] as ItineraryLeg.Ride
            val i = metroStops.indexOfFirst { it.point == from }
            // the planner would "board" the very train the traveller is on, from this stop to the last one
            Itinerary(listOf(base.copy(stops = metroStops.drop(i), tripId = "T1"), ItineraryLeg.Walk("E", null, metroStops[4].point, to, 500, base.arriveAt, base.arriveAt + 400)))
        }
        rideTo(rig, 1)
        rig.controller.replan()
        runCurrent()
        val legs = rig.controller.state.value!!.itinerary.legs
        assertEquals(2, legs.size) // one ride and the final walk: no second boarding of the same train
        val ride = legs[0] as ItineraryLeg.Ride
        assertEquals("E", ride.alighting.name)
        assertEquals(listOf("B", "C", "D", "E"), ride.stops.map { it.name }.let { if (it.first() == "A") it.drop(1) else it })
        rig.controller.stop()
    }

    @Test
    fun `not aboard, replanning still starts from the position`() = runTest(UnconfinedTestDispatcher()) {
        val requests = mutableListOf<LatLon>()
        val rig = Rig(this, dir) { from, to, at ->
            requests.add(from)
            Itinerary(listOf(ItineraryLeg.Walk(null, null, from, to, 800, at.epochSecond, at.epochSecond + 700)))
        }
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        at(100)
        val here = LatLon(40.0010, -3.0001)
        rig.location.emit(fix(here))
        assertTrue(rig.controller.replan())
        runCurrent()
        assertEquals(listOf(here), requests)
        rig.controller.stop()
    }

    @Test
    fun `the manual answer on the controller reaches the follower`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, dir) { _, _, _ -> null }
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        at(700)
        rig.location.emit(fix(FollowFixtures.onMetro(0, 0.5)))
        assertTrue(rig.controller.setOnBoard(true))
        assertEquals(FollowPhase.ON_BOARD, rig.controller.state.value!!.follow.phase)
        assertTrue(rig.controller.setOnBoard(false))
        assertTrue(rig.controller.state.value!!.follow.phase != FollowPhase.ON_BOARD)
        rig.controller.stop()
    }
}
