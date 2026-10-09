package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A route of the size in a bug report (Leganes to Motril, about 500 km) through everything that post-processes a plan. */
class LongRouteTest {
    private val n = 60_000

    private fun geometry() = List(n) { i ->
        val t = i / (n - 1.0)
        LatLon(40.3167 + (36.75 - 40.3167) * t, -3.7667 + (-3.5167 + 3.7667) * t + 0.03 * sin(t * 60))
    }

    private fun fullPlan(altitudes: List<Double>): RoutePlan {
        val maneuvers = List(2_000) { i ->
            Maneuver(
                geometryIndex = (i + 1) * (n - 2) / 2_001,
                type = if (i % 10 == 5) TurnType.EXIT_RIGHT else TurnType.STRAIGHT,
                streetName = "Autovia del Sur",
                lanes = if (i % 3 == 0) listOf(Lane(setOf(LaneDirection.THROUGH, LaneDirection.RIGHT), true)) else emptyList(),
                exitRef = if (i % 10 == 5) "${i % 400}" else null,
                towardRef = if (i % 10 == 5) "A-44" else null,
                towardName = if (i % 10 == 5) "Granada; Motril" else null,
            )
        }
        val limits = List(1_000) { i -> SpeedLimit(i * 59, i * 59 + 58, if (i % 5 == 0) null else 90 + i % 4 * 10) }
        val tunnels = List(300) { i -> TunnelRange(i * 150, i * 150 + 40) }
        return RoutePlan(geometry(), 520_000.0, 19_000.0, RouteGuidance(maneuvers, limits, listOf(100, 30_000), tunnels), altitudes)
    }

    private fun heights() = List(n) { i -> if (i % 23 == 0) Double.NaN else 650.0 + 300.0 * sin(i / 3000.0) }

    private fun encode(plan: RoutePlan) = ByteArrayOutputStream().also { RoutePlanCodec.write(DataOutputStream(it), plan) }.toByteArray()
    private fun decode(bytes: ByteArray) = RoutePlanCodec.read(DataInputStream(bytes.inputStream()))

    @Test fun `a 500 km plan with every trailer round trips and its size is known`() {
        val plan = fullPlan(heights())
        val bytes = encode(plan)
        // About 1 MB: far over one Binder transaction, which is why the isolated core sends it in chunks.
        assertTrue(bytes.size in 900_000..2_000_000, "encoded size ${bytes.size}")
        val back = decode(bytes)
        assertEquals(plan.geometry, back.geometry)
        assertEquals(plan.guidance.maneuvers, back.guidance.maneuvers)
        assertEquals(plan.guidance.speedLimits, back.guidance.speedLimits)
        assertEquals(plan.guidance.tunnels, back.guidance.tunnels)
        assertEquals(plan.altitudes.size, back.altitudes.size)
        assertTrue(back.altitudes[0].isNaN())
    }

    @Test fun `the elevation profile of a 500 km route with gaps is fast and bounded`() {
        val g = geometry()
        val start = System.nanoTime()
        val e = assertNotNull(ElevationProfile.of(g, heights()))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 3_000, "took $ms ms")
        assertTrue(e.samples.size <= ElevationProfile.MAX_SAMPLES)
        assertTrue(e.totalMeters > 400_000)
    }

    @Test fun `odd altitude lists never throw in the profile`() {
        val g = geometry()
        assertEquals(null, ElevationProfile.of(g, emptyList()))
        assertEquals(null, ElevationProfile.of(g, List(n - 1) { 1.0 }))
        assertEquals(null, ElevationProfile.of(g, List(n) { Double.NaN }))
        assertEquals(null, ElevationProfile.of(g, List(n) { 5.0 })) // flat means no data
        ElevationProfile.of(g, List(n) { if (it % 2 == 0) Double.POSITIVE_INFINITY else -1e9 })
        ElevationProfile.of(g.take(2), listOf(1.0, 2.0))
        ElevationProfile.of(List(10) { g[0] }, List(10) { it.toDouble() }) // zero length
    }

    @Test fun `corrupt or truncated plans give an IOException and nothing else`() {
        val bytes = encode(fullPlan(heights()))
        val rnd = Random(7)
        repeat(200) {
            val copy = bytes.copyOf()
            repeat(1 + rnd.nextInt(8)) { copy[rnd.nextInt(copy.size)] = rnd.nextInt().toByte() }
            val cut = if (it % 4 == 0) copy.copyOf(rnd.nextInt(copy.size)) else copy
            try {
                decode(cut) // a flipped byte may still be a valid plan: both outcomes are fine, an exception other than IOException is not
            } catch (_: IOException) {
            } catch (e: Throwable) {
                throw AssertionError("decode threw ${e.javaClass.name} instead of IOException", e)
            }
        }
        assertFailsWith<IOException> { decode(bytes.copyOf(bytes.size / 2)) }
    }
}
