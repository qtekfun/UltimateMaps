package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.fix
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.metroStops
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trip controller over a [SimulatedLocationSource] and the virtual time of a test scope (its clock drives the follower's). */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitTripControllerTest {
    private val dir = java.nio.file.Files.createTempDirectory("transit-ctl-test").toFile()
    private val file = File(dir, "state.bin")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class Rig(val scope: TestScope, val file: File, replanner: TransitReplanner? = null) {
        val location = SimulatedLocationSource()
        val clock = { FollowFixtures.T0 * 1000 + scope.testScheduler.currentTime }
        val store = TransitTripStore(file, clock)
        val controller = TransitTripController(scope, location, store, replanner, clock = clock)
    }

    private fun TestScope.at(sec: Long) {
        val target = sec * 1000
        val delta = target - testScheduler.currentTime
        if (delta > 0) advanceTimeBy(delta)
        runCurrent()
    }

    @Test
    fun `a walk-only itinerary cannot be started`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file)
        val walkOnly = Itinerary(listOf(FollowFixtures.itinerary().legs[0]))
        assertFalse(rig.controller.start(walkOnly, "UTC"))
        assertFalse(rig.controller.isActive)
        assertFalse(rig.location.isStarted)
    }

    @Test
    fun `start listens to the location and publishes the state from the fixes`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file)
        assertTrue(rig.controller.start(FollowFixtures.itinerary(), "Europe/Madrid"))
        assertTrue(rig.location.isStarted)
        assertEquals(FollowPhase.BEFORE_START, rig.controller.state.value!!.follow.phase)
        at(400)
        rig.location.emit(fix(metroStops[0].point))
        assertEquals(FollowPhase.WAITING, rig.controller.state.value!!.follow.phase)
        assertEquals("Europe/Madrid", rig.controller.state.value!!.zoneId)
        rig.controller.stop()
    }

    @Test
    fun `the ticker lets the clock alone change the state and say board now`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file)
        val seen = mutableListOf<PromptKind>()
        val job = launch { rig.controller.prompts.collect { seen.add(it.kind) } }
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        at(400)
        rig.location.emit(fix(metroStops[0].point))
        at(560)
        assertEquals(listOf(PromptKind.BOARD_NOW), seen)
        assertEquals(40L, rig.controller.state.value!!.follow.secondsToBoard)
        job.cancel()
        rig.controller.stop()
    }

    @Test
    fun `the last known fix is used at once when starting`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file)
        rig.location.emit(fix(metroStops[0].point))
        at(500)
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        assertEquals(FollowPhase.WAITING, rig.controller.state.value!!.follow.phase)
        rig.controller.stop()
    }

    @Test
    fun `a process death leaves a trip that resume continues where it was`() = runTest(UnconfinedTestDispatcher()) {
        val first = Rig(this, file)
        first.controller.start(FollowFixtures.itinerary(), "UTC")
        at(400)
        first.location.emit(fix(metroStops[0].point))
        at(600)
        first.location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0012, -3.0), speed = 8f))
        at(605)
        first.location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0020, -3.0), speed = 8f))
        at(720)
        first.location.emit(fix(FollowFixtures.onMetro(1), speed = 10f))
        // the process dies here: no stop(); a second controller (new process) reads the file
        val second = Rig(this, file)
        assertTrue(second.controller.hasResumable())
        assertTrue(second.controller.resume())
        val s = second.controller.state.value!!.follow
        assertEquals(FollowPhase.ON_BOARD, s.phase)
        assertEquals("C", s.nextStopName)
        first.controller.stop()
        second.controller.stop()
    }

    @Test
    fun `stop forgets the trip and the saved file`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file)
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        assertTrue(file.exists())
        rig.controller.stop()
        assertNull(rig.controller.state.value)
        assertFalse(file.exists())
        assertFalse(rig.location.isStarted)
        assertFalse(rig.controller.resume())
    }

    @Test
    fun `arrival clears the saved trip`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file)
        val rideOnly = Itinerary(listOf(FollowFixtures.itinerary().legs[1]))
        rig.controller.start(rideOnly, "UTC")
        at(400)
        rig.location.emit(fix(metroStops[0].point))
        at(1170) // underground since 400: boarding and progress were estimated, then a fix at the last stop
        assertTrue(rig.controller.state.value!!.follow.estimated)
        rig.location.emit(fix(metroStops[4].point, speed = 0f))
        assertEquals(FollowPhase.ARRIVED, rig.controller.state.value!!.follow.phase)
        assertFalse(file.exists())
        rig.controller.stop()
    }

    @Test
    fun `replan plans from the last known position to the same destination and swaps the itinerary`() = runTest(UnconfinedTestDispatcher()) {
        val requests = mutableListOf<Triple<LatLon, LatLon, Instant>>()
        val replacement = Itinerary(listOf(FollowFixtures.itinerary().legs[3], FollowFixtures.itinerary().legs[4]))
        val rig = Rig(this, file) { from, to, at ->
            requests.add(Triple(from, to, at))
            replacement
        }
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        at(100)
        val here = LatLon(40.0010, -3.0001)
        rig.location.emit(fix(here))
        assertTrue(rig.controller.replan())
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(here, requests[0].first)
        assertEquals(FollowFixtures.destination, requests[0].second)
        assertEquals(Instant.ofEpochMilli(FollowFixtures.T0 * 1000 + 100_000), requests[0].third)
        val state = rig.controller.state.value!!
        assertEquals(replacement, state.itinerary)
        assertFalse(state.replanning)
        assertFalse(state.replanFailed)
        rig.controller.stop()
    }

    @Test
    fun `a failed replan keeps the trip and says so`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, file) { _, _, _ -> null }
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        at(100)
        rig.location.emit(fix(LatLon(40.0010, -3.0001)))
        rig.controller.replan()
        runCurrent()
        val s = rig.controller.state.value!!
        assertEquals(FollowFixtures.itinerary(), s.itinerary)
        assertTrue(s.replanFailed)
        assertFalse(s.replanning)
        rig.controller.stop()
    }

    @Test
    fun `replan is refused without a position, a planner or a trip`() = runTest(UnconfinedTestDispatcher()) {
        val noPlanner = Rig(this, file)
        noPlanner.controller.start(FollowFixtures.itinerary(), "UTC")
        assertFalse(noPlanner.controller.replan())
        noPlanner.controller.stop()
        val rig = Rig(this, file) { _, _, _ -> null }
        assertFalse(rig.controller.replan()) // no trip
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        assertFalse(rig.controller.replan()) // no position yet
        rig.controller.stop()
    }

    @Test
    fun `a replan that finishes after the trip ended is dropped`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Itinerary?>()
        val rig = Rig(this, file) { _, _, _ -> gate.await() }
        rig.controller.start(FollowFixtures.itinerary(), "UTC")
        rig.location.emit(fix(LatLon(40.0010, -3.0001)))
        assertTrue(rig.controller.replan())
        runCurrent()
        assertTrue(rig.controller.state.value!!.replanning)
        rig.controller.stop()
        gate.complete(FollowFixtures.itinerary())
        runCurrent()
        assertNull(rig.controller.state.value)
        assertNotNull(rig.controller) // nothing resurrected the trip
        assertFalse(file.exists())
    }
}
