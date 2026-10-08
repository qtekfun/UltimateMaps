package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationProfileTest {
    /** A straight route going north, one vertex every ~11.1 m (1e-4 degrees of latitude). */
    private fun line(points: Int) = List(points) { LatLon(40.0 + it * 1e-4, -3.0) }

    private fun profile(n: Int, h: (Int) -> Double) = ElevationProfile.of(line(n), List(n, h))

    private fun assertNear(expected: Double, actual: Double, tol: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what expected $expected +-$tol but was $actual")

    private fun encode(plan: RoutePlan): ByteArray =
        ByteArrayOutputStream().also { RoutePlanCodec.write(DataOutputStream(it), plan) }.toByteArray()

    private fun decode(bytes: ByteArray): RoutePlan = RoutePlanCodec.read(DataInputStream(bytes.inputStream()))

    @Test
    fun `a steady climb counts as ascent only`() {
        val p = assertNotNull(profile(1001) { 100.0 + it * 0.1 }) // 11 km, +100 m
        assertNear(100.0, p.ascentMeters, 3.0, "ascent")
        assertEquals(0.0, p.descentMeters, 0.01)
        assertNear(100.0, p.minMeters, 0.5)
        assertNear(200.0, p.maxMeters, 0.5)
        assertNear(0.9, p.maxGradePercent, 0.2, "grade") // 0.1 m per 11.1 m
    }

    @Test
    fun `a steady descent counts as descent only and the grade is negative`() {
        val p = assertNotNull(profile(1001) { 300.0 - it * 0.1 })
        assertNear(100.0, p.descentMeters, 3.0)
        assertEquals(0.0, p.ascentMeters, 0.01)
        assertTrue(p.minGradePercent < -0.5)
        assertEquals(0.0, p.maxGradePercent, 0.01)
    }

    @Test
    fun `up then down gives both totals`() {
        val p = assertNotNull(profile(1001) { if (it <= 500) 100.0 + it * 0.2 else 200.0 - (it - 500) * 0.1 })
        assertNear(100.0, p.ascentMeters, 4.0)
        assertNear(50.0, p.descentMeters, 4.0)
    }

    @Test
    fun `noise on a flat road does not invent climbing`() {
        val rnd = Random(7)
        // +-2 m of jitter on every vertex of an 11 km road, plus one real 40 m hill so the profile is not flat.
        val p = assertNotNull(
            profile(1001) { 100.0 + (if (it in 400..600) 40.0 * sin((it - 400) * Math.PI / 200) else 0.0) + rnd.nextDouble(-2.0, 2.0) },
        )
        assertNear(40.0, p.ascentMeters, 8.0, "ascent")
        assertNear(40.0, p.descentMeters, 8.0, "descent")
    }

    @Test
    fun `naive summing would overcount, the profile does not`() {
        val rnd = Random(3)
        val raw = List(1001) { 100.0 + it * 0.05 + rnd.nextDouble(-2.0, 2.0) }
        val naive = raw.zipWithNext().sumOf { (a, b) -> maxOf(0.0, b - a) }
        val p = assertNotNull(ElevationProfile.of(line(1001), raw))
        assertTrue(naive > 500.0, "test premise: the raw sum is $naive")
        assertNear(50.0, p.ascentMeters, 10.0)
    }

    @Test
    fun `a route with every height equal has no profile`() {
        assertNull(profile(100) { 650.0 })
    }

    @Test
    fun `missing, mismatched or too short input gives null`() {
        assertNull(ElevationProfile.of(line(50), emptyList()))
        assertNull(ElevationProfile.of(line(50), List(49) { 1.0 + it }))
        assertNull(ElevationProfile.of(line(1), listOf(1.0)))
        assertNull(profile(3) { it * 10.0 }) // 22 m long
    }

    @Test
    fun `mostly unknown heights give null, a few unknown are interpolated`() {
        assertNull(profile(100) { if (it % 3 == 0) 100.0 + it else Double.NaN })
        val p = assertNotNull(profile(101) { if (it in 40..50) Double.NaN else 100.0 + it })
        assertNear(100.0, p.ascentMeters, 3.0)
        assertEquals(0.0, p.descentMeters, 0.01)
    }

    @Test
    fun `implausible heights are treated as unknown`() {
        val p = assertNotNull(profile(101) { if (it == 50) -32768.0 else 100.0 + it })
        assertNear(100.0, p.ascentMeters, 3.0)
        assertTrue(p.minMeters > 0.0)
    }

    @Test
    fun `ends keep the real height`() {
        val p = assertNotNull(profile(500) { 10.0 + it })
        assertEquals(10.0, p.samples.first().altitudeMeters, 1e-9)
        assertEquals(509.0, p.samples.last().altitudeMeters, 1e-9)
        assertEquals(0.0, p.samples.first().distanceMeters, 0.0)
        assertEquals(p.totalMeters, p.samples.last().distanceMeters, 1e-6)
    }

    @Test
    fun `samples are capped, ordered and bounded in grade for a long route`() {
        val p = assertNotNull(profile(20_000) { 500.0 + 100.0 * sin(it / 800.0) })
        assertEquals(ElevationProfile.MAX_SAMPLES, p.samples.size)
        assertTrue(p.samples.zipWithNext().all { (a, b) -> b.distanceMeters > a.distanceMeters })
        assertTrue(p.samples.all { abs(it.gradePercent) <= 40.0 })
    }

    @Test
    fun `a short route has fewer samples than the cap`() {
        val p = assertNotNull(profile(20) { it * 1.0 })
        assertEquals(20, p.samples.size)
    }

    @Test
    fun `grade is clamped for an absurd cliff`() {
        val p = assertNotNull(profile(100) { if (it < 50) 0.0 else 500.0 })
        assertTrue(p.maxGradePercent <= 40.0)
    }

    @Test
    fun `altitudeAt interpolates and clamps`() {
        val p = assertNotNull(profile(1001) { 100.0 + it * 0.1 })
        assertNear(100.0, p.altitudeAt(-5.0), 0.01)
        assertNear(200.0, p.altitudeAt(1e9), 0.01)
        assertNear(150.0, p.altitudeAt(p.totalMeters / 2), 2.0)
    }

    @Test
    fun `rounded totals`() {
        val p = assertNotNull(profile(1001) { 100.0 + it * 0.1 })
        assertEquals(Math.round(p.ascentMeters).toInt(), p.roundedAscent)
        assertEquals(0, p.roundedDescent)
    }

    @Test
    fun `codec round trip keeps altitudes and still reads version 2`() {
        val plan = RoutePlan(line(3), 22.0, 5.0, altitudes = listOf(100.4, Double.NaN, 102.0))
        val back = decode(encode(plan))
        assertEquals(3, back.altitudes.size)
        assertEquals(100.0, back.altitudes[0])
        assertTrue(back.altitudes[1].isNaN())
        val none = encode(plan.copy(altitudes = emptyList()))
        assertTrue(decode(none).altitudes.isEmpty())
        // A version 2 file is the same bytes without the trailing altitude and exit counts, with the version byte set to 2.
        val v2 = none.copyOf(none.size - 8).also { it[0] = 2 }
        assertTrue(decode(v2).altitudes.isEmpty())
    }
}
