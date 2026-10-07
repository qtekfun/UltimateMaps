package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.TransitIndexIo
import com.qtekfun.ultimatemaps.core.transit.TransitPlan
import com.qtekfun.ultimatemaps.core.transit.TransitService
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whole trips played back by [TripSimulator]: the synthetic one (always) and itineraries planned on the real Madrid index
 * (only when it exists at `~/mapas-data/transit/out/transit-madrid.umti`; the test returns quietly when it does not).
 */
class FullTripFollowTest {
    private fun phasesOf(sim: TripSimulator, follower: ItineraryFollower, lateSec: Long = 0, blackout: (Long) -> Boolean = { false }): Pair<List<FollowPhase>, List<FollowPrompt>> {
        val phases = ArrayList<FollowPhase>()
        val prompts = ArrayList<FollowPrompt>()
        sim.run(follower, lateSec, blackout = blackout) { _, u ->
            if (phases.lastOrNull() != u.state.phase) phases.add(u.state.phase)
            prompts.addAll(u.prompts)
        }
        return phases to prompts
    }

    @Test
    fun `the synthetic trip run on the timetable goes through every phase in order and arrives`() {
        val it = FollowFixtures.itinerary()
        val sim = TripSimulator(it)
        val follower = ItineraryFollower(it, FollowerConfig(), sim.clockMillis)
        val (phases, prompts) = phasesOf(sim, follower)
        assertEquals(
            listOf(
                FollowPhase.BEFORE_START, FollowPhase.WAITING, FollowPhase.ON_BOARD, FollowPhase.ALIGHT_NEXT, FollowPhase.TRANSFER,
                FollowPhase.WAITING, FollowPhase.ON_BOARD, FollowPhase.ALIGHT_NEXT, FollowPhase.FINAL_WALK, FollowPhase.ARRIVED,
            ),
            phases,
        )
        val kinds = prompts.map { p -> p.kind }
        assertEquals(1, kinds.count { k -> k == PromptKind.ARRIVED })
        assertEquals(2, kinds.count { k -> k == PromptKind.BOARD_NOW })
        assertEquals(2, kinds.count { k -> k == PromptKind.GET_OFF_NOW })
        assertEquals(2, kinds.count { k -> k == PromptKind.GET_READY })
        assertFalse(kinds.contains(PromptKind.OFF_PLAN))
        assertFalse(kinds.contains(PromptKind.CONNECTION_MISSED))
    }

    @Test
    fun `running two minutes late shows the delay and keeps the connection`() {
        val it = FollowFixtures.itinerary()
        val sim = TripSimulator(it)
        val follower = ItineraryFollower(it, FollowerConfig(), sim.clockMillis)
        var maxBehind = 0
        sim.run(follower, lateSec = 120) { _, u -> if (u.state.phase == FollowPhase.ON_BOARD) maxBehind = maxOf(maxBehind, u.state.planOffsetSec ?: 0) }
        assertTrue(maxBehind in 100..140, "delay seen on board: $maxBehind")
        assertEquals(FollowPhase.ARRIVED, follower.state.phase)
    }

    @Test
    fun `an underground gap on the metro leg is estimated and recovered without leaving the plan`() {
        val it = FollowFixtures.itinerary()
        val sim = TripSimulator(it)
        val follower = ItineraryFollower(it, FollowerConfig(), sim.clockMillis)
        val t0 = FollowFixtures.T0
        val estimated = ArrayList<Pair<Boolean, String?>>()
        val (phases, _) = phasesOf(sim, follower, blackout = { t -> t in (t0 + 740)..(t0 + 1000) })
        sim.now = it.departAt - 60
        // replay to read the states during the gap
        val f2 = ItineraryFollower(it, FollowerConfig(), sim.clockMillis)
        sim.run(f2, blackout = { t -> t in (t0 + 740)..(t0 + 1000) }) { t, u -> if (t in (t0 + 800)..(t0 + 990)) estimated.add(u.state.estimated to u.state.nextStopName) }
        assertTrue(estimated.all { (e, _) -> e }, "the gap is marked as an estimate: $estimated")
        assertEquals("C", estimated.first().second) // timetable at 800 s: between B (750) and C (870)
        assertFalse(FollowPhase.OFF_PLAN in phases)
        assertEquals(FollowPhase.ARRIVED, follower.state.phase)
    }

    @Test
    fun `a traveller who never leaves the origin is off plan`() {
        val it = FollowFixtures.itinerary()
        val sim = TripSimulator(it)
        val follower = ItineraryFollower(it, FollowerConfig(), sim.clockMillis)
        // He walks the wrong way: away from the first stop.
        var offPlan = false
        for (k in 0 until 12) {
            sim.now += 5
            val u = follower.onFix(com.qtekfun.ultimatemaps.core.map.LocationFix(LatLon(FollowFixtures.O.lat - 0.0005 * k, FollowFixtures.O.lon), accuracyMeters = 10f))
            if (u.state.phase == FollowPhase.OFF_PLAN) offPlan = true
        }
        assertTrue(offPlan)
    }

    // ------------------------------------------------------------------------------------------------ real index

    private val realIndex = File(System.getProperty("user.home"), "mapas-data/transit/out/transit-madrid.umti")

    @Test
    fun `itineraries planned on the real Madrid index are followed to the end when played on the timetable`() {
        if (!realIndex.isFile) return // the real feed is not required: nothing to check on this machine
        val index = realIndex.inputStream().buffered().use { TransitIndexIo.read(it) }
        val zone = ZoneId.of("Europe/Madrid")
        val service = TransitService(index, zone)
        val day = (service.validFrom ?: return).plusDays(1)
        // Pairs of real places in Madrid (public coordinates of well-known squares and stations), all within the index area.
        val pairs = listOf(
            LatLon(40.4169, -3.7035) to LatLon(40.4723, -3.6826), // Puerta del Sol -> Chamartin
            LatLon(40.4065, -3.6895) to LatLon(40.4530, -3.6883), // Atocha -> Santiago Bernabeu
            LatLon(40.4380, -3.7140) to LatLon(40.4090, -3.6920), // Moncloa -> Atocha area
        )
        var followed = 0
        for ((from, to) in pairs) {
            val plan = service.plan(from, to, day.atTime(8, 30).atZone(zone).toInstant())
            val found = plan as? TransitPlan.Found ?: continue
            val it: Itinerary = found.itineraries.first()
            if (it.isWalkOnly) continue
            val sim = TripSimulator(it, stepSec = 5)
            val follower = ItineraryFollower(it, FollowerConfig(), sim.clockMillis)
            val phases = ArrayList<FollowPhase>()
            sim.run(follower) { _, u -> if (phases.lastOrNull() != u.state.phase) phases.add(u.state.phase) }
            assertEquals(FollowPhase.ARRIVED, follower.state.phase, "trip $from -> $to ended in $phases")
            assertFalse(FollowPhase.OFF_PLAN in phases, "trip $from -> $to went off plan: $phases")
            followed++
        }
        assertTrue(followed >= 1, "no itinerary with a vehicle leg could be planned on the real index")
    }
}
