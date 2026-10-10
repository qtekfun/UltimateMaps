package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.fix
import com.qtekfun.ultimatemaps.core.transit.follow.FollowFixtures.metroStops
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fewer fixes while aboard far from the stop to get off at, every second again near it and for everything else. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitPaceTest {
    private val dir = java.nio.file.Files.createTempDirectory("pace-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun TestScope.at(sec: Long) {
        val delta = sec * 1000 - testScheduler.currentTime
        if (delta > 0) advanceTimeBy(delta)
        runCurrent()
    }

    @Test
    fun `a long ride is followed at the slow pace until the stop is near`() = runTest(UnconfinedTestDispatcher()) {
        val location = SimulatedLocationSource()
        val clock = { FollowFixtures.T0 * 1000 + testScheduler.currentTime }
        val c = TransitTripController(this, location, TransitTripStore(File(dir, "t.bin"), clock), clock = clock)
        c.start(FollowFixtures.itinerary(), "UTC")
        assertEquals(TransitTripController.FAST_PACE_MS, location.paceMillis) // walking to the stop: every second
        at(400)
        location.emit(fix(metroStops[0].point))
        at(600)
        location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0012, -3.0), speed = 8f))
        at(605)
        location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0020, -3.0), speed = 8f))
        assertEquals(FollowPhase.ON_BOARD, c.state.value!!.follow.phase)
        // the alighting stop E is scheduled at 1170: 565 s away
        assertEquals(TransitTripController.SLOW_PACE_MS, location.paceMillis)
        at(720)
        location.emit(fix(FollowFixtures.onMetro(1), speed = 10f))
        assertEquals(TransitTripController.SLOW_PACE_MS, location.paceMillis)
        at(1000) // 170 s to go: inside the exit limit
        location.emit(fix(FollowFixtures.onMetro(3), speed = 10f))
        assertEquals(TransitTripController.FAST_PACE_MS, location.paceMillis)
        c.stop()
        assertEquals(TransitTripController.FAST_PACE_MS, location.paceMillis)
    }
}
