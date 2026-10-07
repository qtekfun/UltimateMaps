package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.TurnType
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Simple microbenchmark of the cost per fix on a long zig-zag route driven at 25 m/s with 5 m of noise. It prints
 * the real numbers (see `docs/phase2/following.md`) and only asserts a very loose bound so it never flakes on a
 * slow machine. Run it with `./gradlew :core-nav:test --tests '*Benchmark*' -i`.
 */
class TrackerBenchmarkTest {
    @Test fun costPerFix() {
        val b = RouteBuilder(step = 20.0)
        val maneuvers = ArrayList<Maneuver>()
        var e = 0.0
        var n = 0.0
        for (i in 1..250) {
            // Zig-zag so the route has corners: 400 m legs alternating north and east.
            if (i % 2 == 1) n += 400.0 else e += 400.0
            b.lineTo(e, n)
            maneuvers += Maneuver(b.lastIndex, if (i % 2 == 1) TurnType.RIGHT else TurnType.LEFT)
        }
        val plan = RoutePlan(b.points.toList(), 100_000.0, 4000.0, RouteGuidance(maneuvers))
        val fixes: List<LocationFix> = RouteSimulator(plan.geometry, 25.0, noiseMeters = 5.0, seed = 1).fixes().toList()

        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        var sink = 0.0
        var loopNanos = 0L
        var loopBytes = 0L

        // Only the per-fix loop is timed and counted: building the tracker (once per route) is excluded.
        fun pass(withSnapshot: Boolean) {
            val t = RouteTracker(plan)
            val a0 = bean.getThreadAllocatedBytes(tid)
            val t0 = System.nanoTime()
            for (f in fixes) {
                t.onFix(f)
                if (withSnapshot) sink += t.snapshot().traveledMeters
            }
            loopNanos += System.nanoTime() - t0
            loopBytes += bean.getThreadAllocatedBytes(tid) - a0
            sink += t.snapshot().traveledMeters
        }

        repeat(40) { pass(true) } // warm up the JIT
        fun measure(withSnapshot: Boolean, passes: Int): Pair<Double, Double> {
            loopNanos = 0
            loopBytes = 0
            repeat(passes) { pass(withSnapshot) }
            val count = passes.toDouble() * fixes.size
            return loopNanos / count to loopBytes / count
        }
        val (onFixNs, onFixBytes) = measure(false, 100)
        val (fullNs, fullBytes) = measure(true, 100)
        println("BENCH route=${plan.geometry.size} points, ${maneuvers.size} maneuvers, ${fixes.size} fixes/pass")
        println("BENCH onFix only:      %.0f ns/fix, %.1f B/fix allocated".format(onFixNs, onFixBytes))
        println("BENCH onFix+snapshot:  %.0f ns/fix, %.1f B/fix allocated".format(fullNs, fullBytes))
        println("BENCH (checksum $sink)")
        assertTrue(fullNs < 1_000_000, "more than 1 ms per fix: $fullNs ns")
        assertTrue(onFixBytes < 32, "onFix allocates $onFixBytes B/fix")
    }
}
