package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Scripted stop/go signal: STOPPED for the listed time ranges (seconds since T0), MOVING otherwise. */
private class FakeStopGo(private val stopped: List<IntRange>, private val t0: Long) : StopGoSignal {
    override fun motionState(nowMillis: Long): MotionState {
        val s = ((nowMillis - t0) / 1000).toInt()
        return if (stopped.any { s in it }) MotionState.STOPPED else MotionState.MOVING
    }
}

/**
 * A drive along a straight route with a known truth. Virtual time: second `i` is `T0 + i * 1000`, nothing reads the
 * real clock. Fixes arrive every second except inside [loss]; there the tracker only gets ticks.
 */
private class Drive(
    plan: RoutePlan,
    private val speedAt: (Int) -> Double,
    private val loss: IntRange,
    spans: List<TunnelSpan>? = null,
    config: NavConfig = NavConfig(),
    stopGo: StopGoSignal = StopGoSignal.NONE,
    source: TunnelSpanSource? = spans?.let { s -> TunnelSpanSource { s } },
    startAlong: Double = 0.0,
    val onAnnouncement: (Announcement) -> Unit = {},
) {
    val tracker = RouteTracker(plan, config, 0, startAlong, null, source, stopGo, onAnnouncement)
    private val truthAlong = ArrayList<Double>().also { it += startAlong }
    var second = 0
        private set

    fun truth(i: Int = second) = truthAlong[i]
    fun time(i: Int = second) = T0 + i * 1000L

    /** Advances one second. Returns the published state. */
    fun step(accuracy: Float = 5f): NavState {
        // Truth moves with the speed of the second just ended (integrated in 0.1 s steps).
        val next = second + 1
        var s = truthAlong.last()
        val v0 = speedAt(second)
        val v1 = speedAt(next)
        for (k in 1..10) s += (v0 + (v1 - v0) * k / 10.0) * 0.1
        truthAlong += s
        second = next
        if (second in loss) {
            tracker.onTick(time())
        } else {
            tracker.onFix(
                LocationFix(pt(0.0, s), accuracy, 0f, speedAt(second).toFloat(), time()),
            )
        }
        return tracker.snapshot()
    }

    fun run(untilSecond: Int, each: (Int, NavState) -> Unit = { _, _ -> }) {
        while (second < untilSecond) {
            val st = step()
            each(second, st)
        }
    }

    companion object {
        const val T0 = 1_000_000L
    }
}

private fun straight(km: Double, maneuverAt: Double? = null): RoutePlan {
    val b = RouteBuilder().lineTo(0.0, km * 1000)
    val last = b.lastIndex
    if (maneuverAt == null) return b.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(last, TurnType.ARRIVE)))
    val rb = RouteBuilder().lineTo(0.0, maneuverAt)
    val m = rb.lastIndex
    rb.lineTo(0.0, km * 1000)
    return rb.plan(listOf(maneuver(0, TurnType.DEPART), maneuver(m, TurnType.EXIT_RIGHT), maneuver(rb.lastIndex, TurnType.ARRIVE)))
}

class TunnelEstimatorTest {
    private val tunnel = listOf(TunnelSpan(1500.0, 3500.0))
    private fun constant(v: Double): (Int) -> Double = { v }

    @Test fun constantSpeedTunnelKeepsAdvancingPastThe30sCapAndStaysInsideTheSpan() {
        val d = Drive(straight(5.0), constant(25.0), loss = 59..139, spans = tunnel)
        var worst = 0.0
        var lastError = 0.0
        var sawTunnel = false
        d.run(139) { i, st ->
            if (i in 59..139) {
                if (st.estimated) {
                    sawTunnel = sawTunnel || st.inTunnel
                    worst = maxOf(worst, abs(st.traveledMeters - d.truth()))
                    assertTrue(st.traveledMeters <= 3500.0, "never beyond the exit while without a fix")
                    assertTrue(st.errorMeters >= lastError - 1e-9, "the error only grows")
                    lastError = st.errorMeters
                }
            }
        }
        assertTrue(sawTunnel)
        // The old cap would have frozen the dot 750 m after the last fix; the truth is ~2 km further.
        assertTrue(d.tracker.snapshot().traveledMeters - d.truth(58) > 1500.0)
        // Constant speed in, constant speed through: the estimate is within a few metres of the truth.
        assertTrue(worst < 15.0, "worst estimate error was $worst m")
        // The reported error is an honest bound of the real error here.
        assertTrue(lastError >= worst)
        // Exit and resync: the real fix wins and the loss state is cleared.
        val st = d.step()
        assertFalse(st.estimated)
        assertFalse(st.inTunnel)
        assertEquals(0.0, st.errorMeters)
        assertEquals(NavStatus.ON_ROUTE, st.status)
        assertEquals(d.truth(), st.traveledMeters, 2.0)
    }

    @Test fun lossWithNoTunnelInformationKeepsTheOld30sBehaviour() {
        val d = Drive(straight(5.0), constant(25.0), loss = 59..139, spans = null)
        d.run(58)
        val anchor = d.tracker.snapshot().traveledMeters
        var frozen = 0.0
        d.run(120) { i, st -> if (i == 100) frozen = st.traveledMeters }
        val st = d.tracker.snapshot()
        assertEquals(NavStatus.NO_SIGNAL, st.status)
        assertFalse(st.inTunnel)
        assertEquals(anchor + 25.0 * 30.0, st.traveledMeters, 30.0)
        assertEquals(frozen, st.traveledMeters, 1e-9)
        assertTrue(st.estimated && st.errorMeters > 0.0)
    }

    @Test fun lossAwayFromAnyKnownTunnelStaysAGenericNoSignal() {
        // A bridge at 500 m with a tunnel known at 1500..3500: the loss is not expected and keeps the 30 s cap.
        val d = Drive(straight(5.0), constant(25.0), loss = 20..80, spans = tunnel)
        d.run(19)
        val anchor = d.tracker.snapshot().traveledMeters
        d.run(60)
        val st = d.tracker.snapshot()
        assertEquals(NavStatus.NO_SIGNAL, st.status)
        assertFalse(st.inTunnel)
        assertEquals(anchor + 750.0, st.traveledMeters, 30.0)
    }

    @Test fun slowingInsideTheTunnelStaysBoundedByTheSpanAndTheReportedError() {
        // 25 m/s into the tunnel, then a jam: 5 m/s from second 70 on.
        val speed: (Int) -> Double = { if (it < 70) 25.0 else 5.0 }
        val d = Drive(straight(5.0), speed, loss = 59..139, spans = tunnel)
        var maxErr = 0.0
        d.run(139) { _, st ->
            if (st.estimated) {
                assertTrue(st.traveledMeters <= 3500.0)
                assertTrue(st.errorMeters <= 2000.0 + 150.0 + 1e-6, "error is bounded by the span")
                maxErr = maxOf(maxErr, abs(st.traveledMeters - d.truth()))
            }
        }
        // Without help the estimate runs ahead: the car is at ~2.1 km, the dot is at the exit. Bounded by the span.
        assertTrue(maxErr > 500.0, "the jam is not visible without a motion sensor ($maxErr)")
        assertTrue(maxErr <= 2000.0)
        // And the first fix after the exit puts it right.
        d.step()
        assertEquals(d.truth(), d.tracker.snapshot().traveledMeters, 2.0)
    }

    @Test fun aStopSignalFreezesTheEstimateAndTheErrorIsMuchSmaller() {
        // 25 m/s, stops for good at second 80 (queue at the exit), the loss ends at 130 with the car still stopped.
        val speed: (Int) -> Double = { if (it < 80) 25.0 else 0.0 }
        fun worst(stopGo: StopGoSignal): Double {
            val d = Drive(straight(5.0), speed, loss = 59..129, spans = tunnel, stopGo = stopGo)
            var w = 0.0
            d.run(129) { _, st -> if (st.estimated) w = maxOf(w, abs(st.traveledMeters - d.truth())) }
            return w
        }
        val blind = worst(StopGoSignal.NONE)
        val sensed = worst(FakeStopGo(listOf(80..200), Drive.T0))
        println("stop in tunnel: blind=$blind m, with stop/go=$sensed m")
        assertTrue(blind > 400.0, "blind=$blind")
        assertTrue(sensed < 60.0, "sensed=$sensed")
        assertTrue(sensed < blind / 5)
    }

    @Test fun movingAgainAfterAStopResumesGraduallyAndNeverFasterThanBefore() {
        val speed: (Int) -> Double = { if (it in 80..110) 0.0 else 25.0 }
        val d = Drive(straight(5.0), speed, loss = 59..160, spans = tunnel, stopGo = FakeStopGo(listOf(80..110), Drive.T0))
        d.run(82)
        val frozen = d.tracker.snapshot().traveledMeters
        d.run(108)
        assertEquals(frozen, d.tracker.snapshot().traveledMeters, 1.0)
        d.run(130)
        val resumed = d.tracker.snapshot()
        assertTrue(resumed.traveledMeters > frozen + 50.0, "moves again after the stop")
        assertTrue(resumed.speedMps <= 5.0 + 1e-6, "resumes at the capped speed")
    }

    @Test fun brakingBeforeTheTunnelIsCarriedBetterThanHoldingTheLastSpeed() {
        // 25 m/s, braking to 14 m/s over 3 s (seconds 55..58), loss from 58 (last fix at 57), truth then holds 14.
        val speed: (Int) -> Double = {
            when {
                it <= 55 -> 25.0
                it >= 58 -> 14.0
                else -> 25.0 - (it - 55) * (11.0 / 3.0)
            }
        }
        fun errorAt(second: Int, spans: List<TunnelSpan>?): Double {
            val d = Drive(straight(5.0), speed, loss = 58..130, spans = spans)
            d.run(second)
            return abs(d.tracker.snapshot().traveledMeters - d.truth())
        }
        // 30 s after the last fix, still inside the cap of the plain estimate, so both are advancing.
        val naive = errorAt(87, null)
        val smart = errorAt(87, tunnel)
        println("braking: held-speed error=$naive m, trend model error=$smart m")
        assertTrue(naive > 120.0, "naive=$naive")
        assertTrue(smart < 0.6 * naive, "smart=$smart naive=$naive")
    }

    @Test fun aVeryLongTunnelHitsTheHardDistanceCap() {
        val long = listOf(TunnelSpan(1000.0, 24_000.0))
        val d = Drive(straight(25.0), constant(30.0), loss = 31..900, spans = long)
        d.run(32)
        val anchor = d.tracker.snapshot().traveledMeters
        var atCap = 0.0
        d.run(500) { i, st -> if (i == 400) atCap = st.traveledMeters }
        val st = d.tracker.snapshot()
        assertTrue(st.traveledMeters - anchor <= 10_000.0 + 1.0, "travelled ${st.traveledMeters - anchor}")
        assertTrue(st.traveledMeters - anchor > 9_000.0)
        assertEquals(atCap, st.traveledMeters, 1e-6, "frozen after the cap")
        assertEquals(NavStatus.NO_SIGNAL, st.status)
        assertEquals(0.0, st.speedMps, 1e-9)
    }

    @Test fun theHardTimeCapStopsTheEstimateWhateverTheSpanSays() {
        val cfg = NavConfig(tunnel = TunnelConfig(hardMaxMillis = 60_000L))
        val d = Drive(straight(25.0), constant(10.0), loss = 31..900, spans = listOf(TunnelSpan(100.0, 20_000.0)), config = cfg)
        d.run(31)
        val anchor = d.tracker.snapshot().traveledMeters
        d.run(300)
        // 60 s of loss at 10 m/s from the last fix (second 30), not a metre more.
        assertEquals(anchor + 600.0, d.tracker.snapshot().traveledMeters, 15.0)
    }

    @Test fun neverReportsArrivalInsideATunnelThatEndsAtTheDestination() {
        val plan = straight(2.0)
        val d = Drive(plan, constant(25.0), loss = 45..400, spans = listOf(TunnelSpan(1000.0, 2000.0)))
        d.run(300) { _, st ->
            assertNotEquals(NavStatus.ARRIVED, st.status)
            assertTrue(st.traveledMeters <= plan.distanceMeters - 0.9, "${st.traveledMeters}")
        }
        assertEquals(NavStatus.NO_SIGNAL, d.tracker.status)
    }

    @Test fun aPoorFixNearThePortalSnapsToTheExitAndAGoodFixDoesNot() {
        fun exitWith(accuracy: Float): Double {
            val d = Drive(straight(5.0), constant(25.0), loss = 59..138, spans = tunnel)
            d.run(138)
            // Second 139: the car is ~40 m before the portal (truth 3475..), the fix is placed 30 m before it.
            val fix = LocationFix(pt(0.0, 3470.0), accuracy, 0f, 25f, d.time(139))
            d.tracker.onFix(fix)
            return d.tracker.snapshot().traveledMeters
        }
        assertEquals(3500.0, exitWith(50f), 1e-6)
        assertEquals(3470.0, exitWith(5f), 3.0)
    }

    @Test fun aFixFarFromTheEstimateAfterALongTunnelSearchesTheWholeRoute() {
        // The estimate froze at the cap far behind the truth; the returning fix is 4 km ahead of the anchor.
        val d = Drive(straight(8.0), constant(25.0), loss = 31..400, spans = null)
        d.run(300)
        d.tracker.onFix(LocationFix(pt(0.0, 6000.0), 5f, 0f, 25f, d.time(301)))
        val st = d.tracker.snapshot()
        assertEquals(NavStatus.ON_ROUTE, st.status)
        assertEquals(6000.0, st.traveledMeters, 3.0)
    }

    @Test fun theNowPromptInsideATunnelIsSuppressedWhenTheEstimateIsLoose() {
        val got = ArrayList<Announcement>()
        // Exit ramp at 2500 m, 1 km into a tunnel: error ~100 m > the 75 m band of 25 m/s.
        val plan = straight(5.0, maneuverAt = 2500.0)
        val d = Drive(plan, constant(25.0), loss = 59..139, spans = tunnel, onAnnouncement = { got += it })
        d.run(139)
        val exit = got.filter { it.maneuver.type == TurnType.EXIT_RIGHT }
        assertTrue(exit.isNotEmpty(), "far/near prompts still come from the estimate")
        assertTrue(exit.none { it.kind == AnnouncementKind.NOW }, "no precise 'now' with a loose estimate: $exit")
        d.step()
        d.run(180)
        assertEquals(exit.size, got.count { it.maneuver.type == TurnType.EXIT_RIGHT }, "nothing is repeated after the resync")
    }

    @Test fun theNowPromptStillComesWhenTheEstimateIsTight() {
        val got = ArrayList<Announcement>()
        // Ramp 250 m after the last fix: error ~ 5 + 12 + 10 = 27 m, inside the band.
        val plan = straight(5.0, maneuverAt = 1700.0)
        val d = Drive(plan, constant(25.0), loss = 59..139, spans = tunnel, onAnnouncement = { got += it })
        d.run(139)
        assertTrue(got.any { it.maneuver.type == TurnType.EXIT_RIGHT && it.kind == AnnouncementKind.NOW }, "$got")
    }

    @Test fun withoutSpansTheNowPromptIsNeverSuppressed() {
        val got = ArrayList<Announcement>()
        val plan = straight(5.0, maneuverAt = 2000.0)
        val d = Drive(plan, constant(25.0), loss = 59..139, spans = null, onAnnouncement = { got += it })
        d.run(139)
        // Old behaviour: the held-speed estimate crosses the ramp and the "now" prompt is given as before.
        assertTrue(got.any { it.maneuver.type == TurnType.EXIT_RIGHT && it.kind == AnnouncementKind.NOW }, "$got")
    }

    @Test fun theErrorGrowsWithTheDistanceAndTimeAndIsBoundedByTheSpan() {
        val e = TunnelEstimator()
        e.onFix(1000L, 100.0, 20.0, 20.0, 5.0)
        assertEquals(5.0, e.errorMeters(0.0, 0.0), 1e-9)
        assertEquals(5.0 + 0.05 * 400 + 20.0, e.errorMeters(20.0, 400.0), 1e-9)
        assertTrue(e.errorMeters(60.0, 1200.0) > e.errorMeters(30.0, 600.0))
        assertEquals(300.0, e.errorMeters(600.0, 12_000.0, capMeters = 300.0), 1e-9)
    }

    @Test fun learnedTunnelMakesTheNextTripTunnelAware() {
        val store = LearnedTunnelStore()
        // First trip: nothing known, a 40 s loss in the middle of the route (about 1 km at 25 m/s).
        val first = Drive(straight(5.0), constant(25.0), loss = 59..99, source = store)
        first.run(110)
        assertEquals(1, store.size)
        val spans = store.spansFor(RouteGeometry(straight(5.0).geometry))
        assertEquals(1, spans.size)
        assertEquals(TunnelSource.LEARNED, spans[0].source)
        // The loss began at the last fix before it (~1450 m) and ended at the first fix after (~2500 m).
        assertEquals(1450.0, spans[0].startMeters, 40.0)
        assertEquals(2500.0, spans[0].endMeters, 40.0)
        // Second trip over the same road: this time the loss is expected, and the dot is bounded by the learned exit (+ slack).
        val second = Drive(straight(5.0), constant(25.0), loss = 59..300, source = store)
        second.run(250)
        val st = second.tracker.snapshot()
        assertTrue(st.inTunnel)
        val slack = 0.15 * spans[0].lengthMeters + 50.0
        assertTrue(st.traveledMeters <= spans[0].endMeters + slack + 1e-6)
        assertTrue(st.traveledMeters > spans[0].endMeters - 1.0, "it did reach the learned exit")
    }

    @Test fun aShortLossIsNotLearned() {
        val store = LearnedTunnelStore()
        val d = Drive(straight(5.0), constant(25.0), loss = 59..63, source = store)
        d.run(100)
        assertEquals(0, store.size)
    }

    @Test fun learnedStoreIgnoresDuplicatesAndPlacesFarFromTheRoute() {
        val store = LearnedTunnelStore(maxEntries = 2)
        store.onTunnelObserved(pt(0.0, 1000.0), pt(0.0, 2000.0))
        store.onTunnelObserved(pt(5.0, 1005.0), pt(0.0, 2003.0))
        assertEquals(1, store.size)
        store.onTunnelObserved(pt(900.0, 1000.0), pt(900.0, 2000.0))
        val spans = store.spansFor(RouteGeometry(straight(5.0).geometry))
        assertEquals(1, spans.size)
        store.onTunnelObserved(pt(0.0, 3000.0), pt(0.0, 3300.0))
        assertEquals(2, store.size) // the oldest was dropped
    }

    @Test fun spanSanitizingSortsMergesAndDropsInvalidSpans() {
        val out = sanitizeSpans(
            listOf(
                TunnelSpan(300.0, 400.0), TunnelSpan(100.0, 320.0), TunnelSpan(Double.NaN, 5.0),
                TunnelSpan(900.0, 899.0), TunnelSpan(4900.0, 9000.0),
            ),
            5000.0,
        )
        assertEquals(listOf(100.0 to 400.0, 4900.0 to 5000.0), out.map { it.startMeters to it.endMeters })
    }

    @Test fun aBrokenSpanSourceDoesNotBreakTheTracker() {
        val d = Drive(straight(2.0), constant(10.0), loss = 20..40, source = TunnelSpanSource { error("boom") })
        d.run(60)
        assertEquals(NavStatus.ON_ROUTE, d.tracker.status)
    }

    @Test fun theEstimateIsDeterministicForTheSameInputs() {
        fun run(): List<Double> {
            val d = Drive(straight(5.0), { if (it < 70) 25.0 else 12.0 }, loss = 59..139, spans = tunnel)
            val out = ArrayList<Double>()
            d.run(139) { _, st -> out += st.traveledMeters }
            return out
        }
        assertEquals(run(), run())
    }
}
