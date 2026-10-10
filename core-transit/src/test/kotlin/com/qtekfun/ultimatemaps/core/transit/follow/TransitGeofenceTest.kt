package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.GeofenceTarget
import com.qtekfun.ultimatemaps.core.map.Geofencer
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The geofences of the trip: which stops are watched, and what an entry does when no ordinary fix comes. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitGeofenceTest {
    private val dir = java.nio.file.Files.createTempDirectory("geofence-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class FakeGeofencer : Geofencer {
        var targets: List<GeofenceTarget> = emptyList()
        var cleared = 0
        private var cb: ((String) -> Unit)? = null
        override fun set(targets: List<GeofenceTarget>, onEnter: (String) -> Unit) {
            this.targets = targets
            cb = onEnter
        }
        override fun clear() {
            targets = emptyList()
            cleared++
        }
        fun enter(id: String) = cb!!.invoke(id)
    }

    private fun TestScope.at(sec: Long) {
        val delta = sec * 1000 - testScheduler.currentTime
        if (delta > 0) advanceTimeBy(delta)
        runCurrent()
    }

    private fun TestScope.rig(g: Geofencer): Pair<TransitTripController, SimulatedLocationSource> {
        val location = SimulatedLocationSource()
        val clock = { FollowFixtures.T0 * 1000 + testScheduler.currentTime }
        return TransitTripController(this, location, TransitTripStore(File(dir, "t.bin"), clock), clock = clock, geofencer = g) to location
    }

    @Test
    fun `a trip watches the first boarding stop and where to get off, and clears them at the end`() = runTest(UnconfinedTestDispatcher()) {
        val g = FakeGeofencer()
        val (c, _) = rig(g)
        c.start(FollowFixtures.itinerary(), "UTC")
        assertEquals(setOf("board:1", "alight:1"), g.targets.map { it.id }.toSet())
        assertTrue(g.targets.all { it.radiusMeters >= 100f })
        c.stop()
        assertTrue(g.targets.isEmpty() && g.cleared >= 1)
    }

    @Test
    fun `once aboard the boarding stop is no longer watched`() = runTest(UnconfinedTestDispatcher()) {
        val g = FakeGeofencer()
        val (c, location) = rig(g)
        c.start(FollowFixtures.itinerary(), "UTC")
        at(400)
        location.emit(fix(metroStops[0].point))
        at(600)
        location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0012, -3.0), speed = 8f))
        at(605)
        location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0020, -3.0), speed = 8f))
        assertEquals(FollowPhase.ON_BOARD, c.state.value!!.follow.phase)
        assertTrue(g.targets.none { it.id.startsWith("board:1") }, "${g.targets.map { it.id }}")
        assertTrue(g.targets.any { it.id == "alight:1" })
        c.stop()
    }

    @Test
    fun `entering the alighting geofence says get off even when no position update arrives`() = runTest(UnconfinedTestDispatcher()) {
        val g = FakeGeofencer()
        val (c, location) = rig(g)
        val seen = mutableListOf<PromptKind>()
        val job = launch { c.prompts.collect { seen.add(it.kind) } }
        c.start(FollowFixtures.itinerary(), "UTC")
        at(400)
        location.emit(fix(metroStops[0].point))
        at(600)
        location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0012, -3.0), speed = 8f))
        at(605)
        location.emit(fix(LatLon(FollowFixtures.stopLat[0] + 0.0020, -3.0), speed = 8f))
        at(1100)
        g.enter("alight:1") // the phone was throttled: the system still noticed the stop
        assertTrue(PromptKind.GET_OFF_NOW in seen, "$seen")
        // the ride is not over because of a circle: only a real fix at the stop ends it
        assertTrue(c.state.value!!.follow.phase != FollowPhase.TRANSFER, "${c.state.value!!.follow.phase}")
        job.cancel()
        c.stop()
    }
}
