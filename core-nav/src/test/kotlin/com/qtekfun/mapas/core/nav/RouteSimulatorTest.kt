package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.SimulatedLocationSource
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteSimulatorTest {
    private val plan = straightPlan()

    @Test fun walksTheRouteAtTheRequestedSpeedAndEndsAtTheEnd() {
        val fixes = RouteSimulator(plan.geometry, 10.0, startMillis = 5_000).fixes().toList()
        assertEquals(5_000L, fixes.first().timeMillis)
        assertEquals(1000L, fixes[1].timeMillis - fixes[0].timeMillis)
        assertEquals(10.0, planar(fixes[0].point, fixes[1].point), 0.05)
        assertEquals(2000.0, planar(fixes.first().point, fixes.last().point), 0.5)
        assertEquals(0f, fixes[3].bearingDegrees)
        assertEquals(10f, fixes[3].speedMps)
        assertEquals(RouteSimulator(plan.geometry, 10.0).durationMillis, 200_000L)
    }

    @Test fun sameSeedGivesTheSameNoiseAndDifferentSeedsDiffer() {
        val a = RouteSimulator(plan.geometry, 10.0, noiseMeters = 8.0, seed = 42).fixes().toList()
        val b = RouteSimulator(plan.geometry, 10.0, noiseMeters = 8.0, seed = 42).fixes().toList()
        val c = RouteSimulator(plan.geometry, 10.0, noiseMeters = 8.0, seed = 43).fixes().toList()
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun noiseHasTheRequestedStandardDeviation() {
        val clean = RouteSimulator(plan.geometry, 10.0).fixes().toList()
        val noisy = RouteSimulator(plan.geometry, 10.0, noiseMeters = 10.0, seed = 5).fixes().toList()
        val errors = clean.indices.flatMap { i ->
            val c = clean[i].point
            val n = noisy[i].point
            listOf((n.lon - c.lon) * 111_194.9266 * Math.cos(Math.toRadians(c.lat)), (n.lat - c.lat) * 111_194.9266)
        }
        val mean = errors.average()
        val sd = sqrt(errors.sumOf { (it - mean) * (it - mean) } / errors.size)
        assertEquals(0.0, mean, 1.0)
        assertEquals(10.0, sd, 1.0)
        assertEquals(10f, noisy[0].accuracyMeters)
    }

    @Test fun gapsRemoveFixesWithoutChangingTheNoiseOfTheOthers() {
        val full = RouteSimulator(plan.geometry, 10.0, noiseMeters = 5.0, seed = 1).fixes().toList()
        val gapped = RouteSimulator(plan.geometry, 10.0, noiseMeters = 5.0, seed = 1, gaps = listOf(10_000L..19_999L)).fixes().toList()
        assertEquals(full.size - 10, gapped.size)
        assertTrue(gapped.none { it.timeMillis - 1000 in 10_000L..19_999L })
        assertEquals(full.filter { it.timeMillis - 1000 !in 10_000L..19_999L }, gapped)
    }

    @Test fun canStartMidRouteAndEmitIntoASimulatedSource() {
        val sim = RouteSimulator(plan.geometry, 20.0, startAlongMeters = 1800.0, intervalMillis = 500)
        val source = SimulatedLocationSource()
        val received = ArrayList<LocationFix>()
        source.start { received += it }
        var calls = 0
        sim.emitAll(source) { calls++ }
        assertEquals(received.size, calls)
        // 200 m at 20 m/s = 10 s, a fix every 0.5 s, both ends included (a 22nd fix appears when the haversine length is a hair above 2000 m).
        assertTrue(received.size in 21..22, "${received.size} fixes")
        assertEquals(sim.fixes().toList(), received)
        assertEquals(1800.0, planar(plan.geometry.first(), received.first().point), 0.5)
    }
}
