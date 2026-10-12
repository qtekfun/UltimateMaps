package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import com.qtekfun.ultimatemaps.core.transit.RideShape
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Following a train on a winding rail line (the Cercanias C5 situation: hops whose track is 300 to 600 m from the chord between
 * the stops). Virtual time only: every test moves a clock by hand and feeds fixes one second apart.
 *
 * Track: five stops 3 km apart along an S-curve (each hop bends by 1 rad, left then right), so the chord between two stops is
 * up to about 370 m from the rails. Stations sit 40 m beside the track. The train follows its timetable exactly: 300 s per hop at
 * 10 m/s, 40 s standing at every intermediate stop.
 */
class RailFollowTest {
    private val t0 = 1_800_000_000L
    private val lat0 = 40.35
    private val lon0 = -3.75
    private val ky = 6_371_008.8 * PI / 180.0
    private val kx = ky * cos(Math.toRadians(lat0))
    private val hopLength = 3000.0
    private val hops = 4
    private val stepM = 25.0

    private fun ll(x: Double, y: Double) = LatLon(lat0 + y / ky, lon0 + x / kx)

    private class V(val x: Double, val y: Double, val heading: Double)

    /** Rail vertices every [stepM] metres; hop k bends left (even) or right (odd) by 1 rad. */
    private val rail: List<V> = buildList {
        var x = 0.0
        var y = 0.0
        var h = 0.3
        add(V(x, y, h))
        for (k in 0 until hops) {
            val n = (hopLength / stepM).toInt()
            val turn = if (k % 2 == 0) 1.0 else -1.0
            repeat(n) {
                h += turn * 1.0 / n
                x += stepM * cos(h)
                y += stepM * sin(h)
                add(V(x, y, h))
            }
        }
    }
    private val verticesPerHop = (hopLength / stepM).toInt()

    /** Position on the rails after [s] metres (clamped), with the left normal. */
    private fun railAt(s: Double): Triple<Double, Double, Double> {
        val c = s.coerceIn(0.0, hops * hopLength) / stepM
        val i = c.toInt().coerceAtMost(rail.size - 2)
        val f = c - i
        val a = rail[i]
        val b = rail[i + 1]
        return Triple(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), a.heading + f * (b.heading - a.heading))
    }

    private fun onRail(s: Double, cross: Double = 0.0): LatLon {
        val (x, y, h) = railAt(s)
        return ll(x - sin(h) * cross, y + cos(h) * cross)
    }

    private val line = LineInfo("C5", "Cercanias C5", 0xFF0066B3.toInt(), 0xFFFFFFFF.toInt(), 2)
    private val depart = LongArray(hops + 1) { 600L + it * 340L }
    private val arrive = LongArray(hops + 1) { if (it == 0) 600L else depart[it] - 40 }
    private val stops = (0..hops).map { k ->
        ItineraryStop("S$k", onRail(k * hopLength, 40.0), t0 + arrive[k], t0 + if (k == hops) arrive[k] else depart[k])
    }

    private fun shape(): RideShape {
        val pts = rail.map { ll(it.x, it.y) }
        return RideShape(pts, IntArray(hops + 1) { it * verticesPerHop }, BooleanArray(hops) { true })
    }

    private fun itinerary(withGeometry: Boolean): Itinerary {
        val first = rail.first()
        val end = rail.last()
        val origin = ll(first.x - 300.0 * cos(first.heading), first.y - 300.0 * sin(first.heading))
        val destination = ll(end.x + 300.0 * cos(end.heading), end.y + 300.0 * sin(end.heading))
        return Itinerary(
            listOf(
                ItineraryLeg.Walk(null, "S0", origin, stops[0].point, 350, t0, t0 + 400),
                ItineraryLeg.Ride(line, "Atocha", stops, if (withGeometry) shape() else null),
                ItineraryLeg.Walk("S$hops", null, stops[hops].point, destination, 350, t0 + arrive[hops], t0 + arrive[hops] + 400),
            ),
        )
    }

    private class Clock(var sec: Long = 0L)

    private val clock = Clock()

    /** Metres along the rails of the train at [t] seconds after T0 (standing before 600 s and at the stops). */
    private fun trainAlong(t: Double): Double {
        if (t <= depart[0]) return 0.0
        for (k in 0 until hops) {
            val ride0 = depart[k].toDouble()
            val ride1 = arrive[k + 1].toDouble()
            if (t < ride0) return k * hopLength
            if (t <= ride1) return k * hopLength + hopLength * (t - ride0) / (ride1 - ride0)
        }
        return hops * hopLength
    }

    private fun speedAt(t: Double): Float {
        val dt = 0.5
        return (abs(trainAlong(t + dt) - trainAlong(t - dt)) / (2 * dt)).toFloat()
    }

    private class Run(val phases: MutableList<FollowPhase> = ArrayList(), var maxAhead: Int = 0, var maxBehind: Int = 0) {
        val offPlanSeen: Boolean get() = FollowPhase.OFF_PLAN in phases
    }

    /**
     * Plays the train from [fromSec] to [toSec], one fix per second, with uniform cross-track noise of +-[noiseM] metres and the
     * accuracy [accuracy]. [mutate] may replace or drop a fix (return null) at a given second.
     */
    private fun play(
        f: ItineraryFollower, run: Run, fromSec: Int, toSec: Int, noiseM: Double = 30.0, accuracy: Float = 20f, seed: Long = 7,
        mutate: (Int, LocationFix) -> LocationFix? = { _, fix -> fix },
    ) {
        val rnd = Random(seed)
        for (t in fromSec..toSec) {
            clock.sec = t.toLong()
            val s = trainAlong(t.toDouble())
            val cross = (rnd.nextDouble() * 2 - 1) * noiseM + 40.0 * 0 // rails are the reference; the station offset is not on the track
            val fix = LocationFix(onRail(s, cross), accuracyMeters = accuracy, speedMps = speedAt(t.toDouble()))
            val use = mutate(t, fix)
            val update = if (use == null) f.tick() else f.onFix(use)
            run.phases.add(update.state.phase)
            update.state.planOffsetSec?.let { off ->
                if (update.state.phase == FollowPhase.ON_BOARD || update.state.phase == FollowPhase.ALIGHT_NEXT) {
                    run.maxBehind = maxOf(run.maxBehind, off)
                    run.maxAhead = maxOf(run.maxAhead, -off)
                }
            }
        }
    }

    private fun follower(withGeometry: Boolean = true) =
        ItineraryFollower(itinerary(withGeometry), FollowerConfig(), { (t0 + clock.sec) * 1000 })

    // ---------------------------------------------------------------------------------------------- the bug

    @Test
    fun `a train on winding rails is not off plan and not ahead of plan when the line follows the rails`() {
        val f = follower()
        val run = Run()
        val last = (arrive[hops] + 60).toInt()
        // the walker reaches the first station on time
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        play(f, run, 381, last)
        assertTrue(!run.offPlanSeen, "never off plan: ${run.phases.distinct()}")
        assertTrue(FollowPhase.ON_BOARD in run.phases && FollowPhase.ALIGHT_NEXT in run.phases)
        assertEquals(FollowPhase.FINAL_WALK, f.state.phase)
        assertTrue(run.maxAhead <= 90 && run.maxBehind <= 90, "plan offset stays within the on-plan band: ahead ${run.maxAhead}, behind ${run.maxBehind}")
    }

    @Test
    fun `the same trip drawn as chords is lost by the follower, which is what the owner saw`() {
        val f = follower(withGeometry = false)
        val run = Run()
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        play(f, run, 381, (arrive[hops] + 60).toInt())
        assertTrue(run.offPlanSeen, "the chord corridor (150 m) cannot hold a train 300 m away from it")
    }

    @Test
    fun `progress only moves forward along the rails while a fix jumps 200 m once`() {
        val f = follower()
        val run = Run()
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        var before = -1.0
        var after = -1.0
        play(f, run, 381, 1100, noiseM = 20.0) { t, fix ->
            when (t) {
                1000 -> fix.copy(point = onRail(trainAlong(t.toDouble()), 200.0), accuracyMeters = 25f) // a single wild fix
                999 -> fix.also { before = f.boardedRide()?.progress ?: -1.0 }
                1001 -> fix.also { after = f.boardedRide()?.progress ?: -1.0 }
                else -> fix
            }
        }
        assertTrue(!run.offPlanSeen, "a single outlier is not off plan")
        assertTrue(before >= 0.0 && after >= before, "progress stayed monotone through the jump: $before -> $after")
    }

    @Test
    fun `standing 60 seconds in a station with vague and wandering fixes is never off plan nor the wrong way`() {
        val f = follower()
        val run = Run()
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        play(f, run, 381, (depart[1] - 80).toInt()) // ride to just before stop 1
        val rnd = Random(3)
        // 60 s at stop 1: the receiver wanders up to 130 m around the station with 120 m accuracy and no speed
        for (t in (depart[1] - 40).toInt()..(depart[1] + 20).toInt()) {
            clock.sec = t.toLong()
            val p = onRail(hopLength, (rnd.nextDouble() * 2 - 1) * 130.0)
            val update = f.onFix(LocationFix(p, accuracyMeters = 120f))
            run.phases.add(update.state.phase)
        }
        assertTrue(!run.offPlanSeen, "standing is not off plan: ${run.phases.distinct()}")
        val ride = assertNotNull(f.boardedRide())
        assertTrue(ride.progress >= 0.9, "still aboard at stop 1: ${ride.progress}")
        // and the train leaves again normally
        play(f, run, (depart[1] + 21).toInt(), (depart[1] + 200).toInt())
        assertTrue(!run.offPlanSeen)
    }

    @Test
    fun `waiting in the station with a bad receiver stays waiting and boards when the train leaves`() {
        val f = follower()
        val rnd = Random(11)
        val phases = ArrayList<FollowPhase>()
        for (t in 0..590) {
            clock.sec = t.toLong()
            // 0..390: walking in; then 200 s on the platform, receiver 150 m accurate, wandering 120 m
            val p = if (t < 380) onRail(t * 0.8, 60.0) else onRail(0.0, (rnd.nextDouble() * 2 - 1) * 120.0)
            val update = f.onFix(LocationFix(p, accuracyMeters = if (t < 380) 10f else 150f))
            phases.add(update.state.phase)
        }
        assertTrue(FollowPhase.OFF_PLAN !in phases, "waiting is never off plan: ${phases.distinct()}")
        assertNull(f.boardedRide())
        val run = Run()
        play(f, run, 591, 700)
        assertNotNull(f.boardedRide(), "boarded once the train moves along the line")
        assertTrue(!run.offPlanSeen)
    }

    // ---------------------------------------------------------------------------------------------- really the wrong way

    @Test
    fun `a train really going the other way is flagged after a sustained, consistent move back, not before`() {
        val f = follower()
        val run = Run()
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        play(f, run, 381, (depart[2] + 100).toInt(), noiseM = 20.0)
        assertTrue(!run.offPlanSeen)
        val start = trainAlong((depart[2] + 100).toDouble())
        // now it goes back along the line at 10 m/s with the same noise
        val rnd = Random(5)
        var flaggedAfterM = -1.0
        for (k in 1..120) {
            clock.sec = depart[2] + 100 + k
            val s = start - 10.0 * k
            val fix = LocationFix(onRail(s, (rnd.nextDouble() * 2 - 1) * 20.0), accuracyMeters = 20f, speedMps = 10f)
            val update = f.onFix(fix)
            if (update.state.phase == FollowPhase.OFF_PLAN && flaggedAfterM < 0) flaggedAfterM = 10.0 * k
        }
        assertTrue(flaggedAfterM > 0, "wrong way is flagged")
        assertTrue(flaggedAfterM >= 250.0, "not before 250 m of consistent movement back: $flaggedAfterM")
        assertTrue(flaggedAfterM <= 600.0, "and soon enough to be useful: $flaggedAfterM")
        assertTrue(f.state.canReplan)
    }

    @Test
    fun `an estimated position in a tunnel is never the wrong way and a real fix resynchronises`() {
        val f = follower()
        val run = Run()
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        // no fixes at all during hop 1 (a tunnel): only the clock moves
        play(f, run, 381, (depart[1] + 20).toInt())
        play(f, run, (depart[1] + 21).toInt(), (depart[2] - 70).toInt()) { _, _ -> null }
        assertTrue(f.state.estimated, "the timetable fills the gap")
        assertTrue(!run.offPlanSeen)
        // the signal comes back at the stop: a normal fix there, wherever the estimate was
        play(f, run, (depart[2] - 60).toInt(), (depart[2] + 60).toInt())
        assertTrue(!run.offPlanSeen)
        assertTrue(!f.state.estimated)
        assertTrue(assertNotNull(f.boardedRide()).progress >= 2.0)
    }

    // ---------------------------------------------------------------------------------------------- "I am on board"

    private val notes = ArrayList<String>()

    private fun chordFollower(snapshot: FollowerSnapshot? = null) =
        ItineraryFollower(itinerary(false), FollowerConfig(), { (t0 + clock.sec) * 1000 }, snapshot, notes = { notes.add(it) })

    /** Plays the chord-drawn ride until the follower is off plan; returns the second it happened. */
    private fun playUntilOffPlan(f: ItineraryFollower, from: Int = 381, to: Int = 1500): Int {
        clock.sec = 380
        f.onFix(LocationFix(stops[0].point, accuracyMeters = 10f))
        for (t in from..to) {
            clock.sec = t.toLong()
            val fix = LocationFix(onRail(trainAlong(t.toDouble())), accuracyMeters = 20f, speedMps = speedAt(t.toDouble()))
            if (f.onFix(fix).state.phase == FollowPhase.OFF_PLAN) return t
        }
        error("never off plan")
    }

    @Test
    fun `off plan aboard a train drawn as chords, I am on board makes it boarded and it stays boarded to the end`() {
        val f = chordFollower()
        val offAt = playUntilOffPlan(f)
        assertTrue(f.state.aboard, "the follower thinks it is aboard (that is why the button used to do nothing)")
        assertTrue(f.state.canReplan)
        // the owner taps "I am on board"
        assertTrue(f.assumeBoarded())
        assertEquals(FollowPhase.ON_BOARD, f.state.phase, "no stale off-plan state")
        assertTrue(f.state.nextStopName != null && f.state.nextStopIndex >= 1, "the header shows the next stop")
        assertTrue(!f.state.canReplan)
        assertTrue(f.snapshot().confirmed)
        val phases = ArrayList<FollowPhase>()
        var worstProgressError = 0.0
        for (t in (offAt + 1)..(arrive[hops] + 60).toInt()) {
            clock.sec = t.toLong()
            val fix = LocationFix(onRail(trainAlong(t.toDouble()), 20.0), accuracyMeters = 20f, speedMps = speedAt(t.toDouble()))
            val u = f.onFix(fix)
            phases.add(u.state.phase)
            f.boardedRide()?.let { r ->
                // the timetable is exact in this simulation: progress follows the clock along the planned stops
                val sched = ((t - depart[0]).toDouble() / (depart[1] - depart[0])).coerceAtLeast(0.0)
                if (t < depart[2] - 100 && sched <= 2.0 && sched >= 1.0) worstProgressError = maxOf(worstProgressError, abs(r.progress - sched))
            }
        }
        assertTrue(FollowPhase.OFF_PLAN !in phases, "never flips back to off plan: ${phases.distinct()}")
        assertTrue(FollowPhase.ALIGHT_NEXT in phases)
        assertEquals(FollowPhase.FINAL_WALK, f.state.phase)
        assertTrue(worstProgressError < 0.35, "progress follows the plan by the clock: $worstProgressError")
        assertTrue(notes.any { it.contains("reason=user-confirmed-aboard") && it.contains("was off plan") }, notes.toString())
        assertTrue(notes.any { it.contains("reason=outside-corridor") })
    }

    @Test
    fun `without a usable line at all the progress is dead-reckoned from the plan and a wild burst of fixes does not undo the confirmation`() {
        val f = chordFollower()
        val offAt = playUntilOffPlan(f)
        f.assumeBoarded()
        val rnd = Random(9)
        val phases = ArrayList<FollowPhase>()
        // 90 s of fixes 2.5 km off the rails and the chord (outside even the wide corridor), at train speed, in the direction of the line
        for (k in 1..90) {
            clock.sec = (offAt + k).toLong()
            val p = onRail(trainAlong((offAt + k).toDouble()), 2500.0 + rnd.nextDouble() * 50)
            phases.add(f.onFix(LocationFix(p, accuracyMeters = 25f, speedMps = 10f)).state.phase)
        }
        assertTrue(phases.all { it != FollowPhase.OFF_PLAN }, "inside the grace nothing makes it off plan: ${phases.distinct()}")
        val sched = (clock.sec - depart[0]).toDouble() / (depart[1] - depart[0])
        assertTrue(abs(assertNotNull(f.boardedRide()).progress - sched.coerceIn(0.0, 3.0)) < 0.5)
        assertTrue(f.state.estimated, "honestly marked as estimated")
    }

    @Test
    fun `the confirmation can still be contradicted, but only after the grace and a long stretch outside`() {
        val f = chordFollower()
        val offAt = playUntilOffPlan(f)
        f.assumeBoarded()
        val confirmedAt = clock.sec
        var offAfter = -1L
        // the traveller got off and walks away 1.5 km from the line at 1.4 m/s
        for (k in 1..600) {
            clock.sec = confirmedAt + k
            val p = onRail(trainAlong(offAt.toDouble()), 1500.0 + k * 1.4)
            val u = f.onFix(LocationFix(p, accuracyMeters = 15f, speedMps = 1.4f))
            if (u.state.phase == FollowPhase.OFF_PLAN && offAfter < 0) offAfter = k.toLong()
        }
        assertTrue(offAfter > 0, "a clear contradiction is flagged")
        assertTrue(offAfter >= FollowerConfig().confirmedGraceSec, "not within the grace: $offAfter s")
        assertTrue(offAfter >= FollowerConfig().confirmedGraceSec + FollowerConfig().confirmedOffSec - 5, "needs a long stretch: $offAfter s")
        assertTrue(f.state.aboard && f.state.phase == FollowPhase.OFF_PLAN)
        // and the answer can be given again: it clears it and the grace starts over
        assertTrue(f.assumeBoarded())
        assertEquals(FollowPhase.ON_BOARD, f.state.phase)
    }

    @Test
    fun `no, I am not on board goes back to waiting or walking and clears the confirmation`() {
        val f = chordFollower()
        playUntilOffPlan(f)
        f.assumeBoarded()
        assertTrue(f.snapshot().confirmed)
        f.assumeNotBoarded()
        assertNull(f.boardedRide())
        assertTrue(!f.snapshot().confirmed)
        assertTrue(!f.state.aboard)
    }

    @Test
    fun `the confirmation survives a process restart through the snapshot`() {
        val f = chordFollower()
        val offAt = playUntilOffPlan(f)
        f.assumeBoarded()
        val snap = f.snapshot()
        assertTrue(snap.boarded && snap.confirmed)
        val again = chordFollower(snap)
        val phases = ArrayList<FollowPhase>()
        for (k in 1..60) {
            clock.sec = (offAt + k).toLong()
            val p = onRail(trainAlong((offAt + k).toDouble()), 20.0)
            phases.add(again.onFix(LocationFix(p, accuracyMeters = 20f, speedMps = speedAt((offAt + k).toDouble()))).state.phase)
        }
        assertTrue(FollowPhase.OFF_PLAN !in phases, "${phases.distinct()}")
    }

    // ---------------------------------------------------------------------------------------------- tracks

    @Test
    fun `without geometry a ride is its chords and progress is the fraction of the chord`() {
        val ride = itinerary(withGeometry = false).legs[1] as ItineraryLeg.Ride
        val t = RideTrack(ride)
        assertTrue(!t.hasShape)
        assertEquals(listOf(0, 1, 2, 3, 4), t.stopVertex.toList())
        val half = (t.stopAlong[1] + t.stopAlong[2]) / 2
        assertEquals(1.5, t.progressAt(half), 1e-9)
        assertEquals(half, t.alongOf(1.5), 1e-9)
    }

    @Test
    fun `with geometry the metres along the track are longer than the chords and round trip`() {
        val chord = itinerary(withGeometry = false).legs[1] as ItineraryLeg.Ride
        val curved = itinerary(withGeometry = true).legs[1] as ItineraryLeg.Ride
        val a = RideTrack(chord)
        val b = RideTrack(curved)
        assertTrue(b.hasShape && b.hopOnShape.all { it })
        assertTrue(b.stopAlong[4] > a.stopAlong[4] * 1.02, "${b.stopAlong[4]} vs ${a.stopAlong[4]}")
        for (p in listOf(0.0, 0.25, 1.5, 2.999, 3.5, 4.0)) assertEquals(p, b.progressAt(b.alongOf(p)), 1e-9)
        assertTrue(hypot(1.0, 1.0) > 0) // keep the import honest
    }
}
