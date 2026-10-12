package com.qtekfun.ultimatemaps.core.transit

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
 * The geometry builder on synthetic rail networks written in metres (east, north) around a fixed origin: a curved line, a
 * fork, a loop, a missing link, a shape published in the other direction.
 */
class ShapeMatcherTest {
    private val lat0 = 40.35
    private val lon0 = -3.75
    private val ky = 6_371_008.8 * PI / 180.0
    private val kx = ky * cos(Math.toRadians(lat0))

    private class P(val x: Double, val y: Double)

    private fun lat(p: P) = lat0 + p.y / ky
    private fun lon(p: P) = lon0 + p.x / kx
    private fun p(x: Double, y: Double) = P(x, y)

    private fun match(shape: List<P>, stops: List<P>): PatternGeometry? = ShapeMatcher.match(
        DoubleArray(shape.size) { lat(shape[it]) }, DoubleArray(shape.size) { lon(shape[it]) }, 0, shape.size,
        DoubleArray(stops.size) { lat(stops[it]) }, DoubleArray(stops.size) { lon(stops[it]) },
    )

    private fun lengthOf(g: PatternGeometry, from: Int, to: Int): Double {
        var d = 0.0
        for (v in g.stopVertex[from] until g.stopVertex[to]) {
            d += hypot((g.lon[v + 1] - g.lon[v]) / 1e6 * kx, (g.lat[v + 1] - g.lat[v]) / 1e6 * ky)
        }
        return d
    }

    /** A quarter circle of radius 3000 m sampled every 50 m of arc, starting east at (0,0) turning north. */
    private fun arc(): List<P> = (0..94).map { i ->
        val a = i * 50.0 / 3000.0
        p(3000.0 * sin(a), 3000.0 * (1 - cos(a)))
    }

    private fun onArc(angleDeg: Double, offset: Double = 0.0): P {
        val a = Math.toRadians(angleDeg)
        val r = 3000.0 + offset
        return p(r * sin(a), 3000.0 - r * cos(a))
    }

    @Test
    fun `stops of a curved line get a polyline along the curve, not the chord`() {
        val stops = listOf(onArc(0.0, 40.0), onArc(30.0, -30.0), onArc(60.0, 25.0), onArc(85.0, -40.0))
        val g = assertNotNull(match(arc(), stops))
        assertEquals(4, g.stopVertex.size)
        assertEquals(listOf(true, true, true), g.onShape.toList().dropLast(1))
        // the first hop is 30 degrees of a 3 km arc (about 1571 m); the chord is 1553 m, so the polyline is longer than
        // the chord, and it never leaves the arc by more than a few metres
        val chord = hypot(stops[0].x - stops[1].x, stops[0].y - stops[1].y)
        assertTrue(lengthOf(g, 0, 1) > chord, "polyline ${lengthOf(g, 0, 1)} vs chord $chord")
        for (v in 0 until g.vertexCount) {
            val x = (g.lon[v] / 1e6 - lon0) * kx
            val y = (g.lat[v] / 1e6 - lat0) * ky
            val r = hypot(x, 3000.0 - y)
            assertTrue(abs(r - 3000.0) < 6.0, "vertex $v is ${r - 3000.0} m off the arc")
        }
        // stop vertices are non-decreasing
        assertTrue(g.stopVertex.toList().zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `a shape published in the other direction is used reversed`() {
        val stops = listOf(onArc(5.0), onArc(40.0), onArc(80.0))
        val forward = assertNotNull(match(arc(), stops))
        val backward = assertNotNull(match(arc().reversed(), stops))
        assertEquals(forward.onShape.toList(), backward.onShape.toList())
        assertTrue(forward.onShape.dropLast(1).all { it })
        assertTrue(abs(lengthOf(forward, 0, 2) - lengthOf(backward, 0, 2)) < 15.0)
    }

    @Test
    fun `a fork keeps the shape for the trunk and the chord for the branch the shape does not cover`() {
        // trunk east along y = 0 from 0 to 4000 m; the shape continues on branch A (north-east). Branch B (south-east) has stops
        // 600 m off the shape.
        val shape = listOf(p(0.0, 0.0), p(2000.0, 0.0), p(4000.0, 0.0), p(5000.0, 800.0), p(6000.0, 1600.0))
        val stops = listOf(p(100.0, 20.0), p(1900.0, -15.0), p(3500.0, 10.0), p(4200.0, -700.0), p(5000.0, -1500.0))
        val g = assertNotNull(match(shape, stops))
        assertEquals(listOf(true, true, false, false), g.onShape.toList().dropLast(1))
        // the two off-shape stops keep their own coordinates
        val last = g.vertexCount - 1
        assertEquals(Math.round(lat(stops[4]) * 1e6).toInt(), g.lat[g.stopVertex[4]])
        assertEquals(last, g.stopVertex[4])
    }

    @Test
    fun `a loop that passes the same station twice places each visit on its own pass`() {
        // shape: out east 2 km to J, around a 1 km square loop, back through J, on to the east for 2 km
        val j = p(2000.0, 0.0)
        val shape = listOf(
            p(0.0, 0.0), j, p(2000.0, 1000.0), p(3000.0, 1000.0), p(3000.0, 0.0), p(2000.0, 0.0),
            p(2000.0, 0.0), p(4000.0, 0.0),
        )
        val stops = listOf(p(0.0, 30.0), p(2000.0, 20.0), p(2000.0, 1000.0), p(3000.0, 500.0), p(2000.0, 20.0), p(3900.0, 10.0))
        val g = assertNotNull(match(shape, stops))
        assertEquals(listOf(true, true, true, true, true), g.onShape.toList().dropLast(1))
        // J the first time is before the loop vertices, J the second time after them
        assertTrue(g.stopVertex[1] < g.stopVertex[2] && g.stopVertex[3] < g.stopVertex[4], g.stopVertex.toList().toString())
        assertTrue(lengthOf(g, 1, 4) > 3000.0, "the loop is 3 km long, got ${lengthOf(g, 1, 4)}")
    }

    @Test
    fun `a stop missing from the shape leaves chords on its two hops only`() {
        val shape = listOf(p(0.0, 0.0), p(5000.0, 0.0))
        val stops = listOf(p(0.0, 0.0), p(1000.0, 10.0), p(2000.0, 900.0), p(3000.0, 10.0), p(4000.0, -10.0))
        val g = assertNotNull(match(shape, stops))
        assertEquals(listOf(true, false, false, true), g.onShape.toList().dropLast(1))
    }

    @Test
    fun `no placeable stop pair gives no geometry`() {
        val shape = listOf(p(0.0, 0.0), p(5000.0, 0.0))
        assertNull(match(shape, listOf(p(0.0, 2000.0), p(1000.0, 2000.0), p(2000.0, 2000.0))))
        assertNull(match(shape, listOf(p(10.0, 0.0))))
        assertNull(match(listOf(p(0.0, 0.0)), listOf(p(0.0, 0.0), p(100.0, 0.0))))
    }

    @Test
    fun `stops visited against the shape order are explained by the shape taken backwards`() {
        val shape = listOf(p(0.0, 0.0), p(3000.0, 0.0))
        val g = assertNotNull(match(shape, listOf(p(2800.0, 5.0), p(200.0, -5.0))))
        assertTrue(g.onShape[0])
        assertTrue(lengthOf(g, 0, 1) in 2500.0..2700.0)
    }
}
