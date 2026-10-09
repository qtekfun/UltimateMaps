package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The low-emission matcher on a route the size of the one in a bug report (Leganes to Motril, about 500 km, tens of thousands
 * of points) against as many zones as the real file has (56), each with a detailed outline. It runs on the main thread when
 * a route is shown, so it has to finish quickly and never throw, whatever the route looks like.
 */
class ZbeLongRouteTest {
    /** A wobbly circle of [vertices] vertices around [lat], [lon] with a radius in degrees of latitude. */
    private fun blob(lat: Double, lon: Double, radius: Double, vertices: Int, seed: Int): ZbeZone {
        val ring = DoubleArray(vertices * 2)
        for (i in 0 until vertices) {
            val a = 2 * PI * i / vertices
            val r = radius * (1.0 + 0.15 * sin(a * 7 + seed))
            ring[2 * i] = lat + r * sin(a)
            ring[2 * i + 1] = lon + r * cos(a) / cos(Math.toRadians(lat))
        }
        return ZbeZone("z$seed", "Zone $seed", "City $seed", "", listOf(ZbePolygon(listOf(ZbeRing(ring)))))
    }

    private val madrid = LatLon(40.3167, -3.7667) // Leganes, inside the first zone
    private val motril = LatLon(36.75, -3.5167)

    private fun zones(): List<ZbeZone> {
        val list = ArrayList<ZbeZone>()
        list += blob(40.35, -3.75, 0.12, 4_000, 0) // a Madrid-sized zone that contains the start
        list += blob(37.18, -3.60, 0.04, 2_000, 1) // Granada, on the way
        list += blob(36.75, -3.52, 0.02, 1_500, 2) // Motril itself
        for (i in 3 until 56) list += blob(36.0 + (i % 9) * 0.7, -6.0 + (i % 11) * 0.5, 0.03, 1_500, i) // the rest of Spain
        return list
    }

    /** [n] points from Leganes to Motril with a bit of wiggle, like a real road. */
    private fun route(n: Int) = List(n) { i ->
        val t = i / (n - 1.0)
        LatLon(madrid.lat + (motril.lat - madrid.lat) * t, madrid.lon + (motril.lon - madrid.lon) * t + 0.03 * sin(t * 60))
    }

    @Test fun `a 500 km route of 60000 points against 56 detailed zones finishes fast and finds the zones it enters`() {
        val index = ZbeIndex(zones())
        val r = route(60_000)
        val start = System.nanoTime()
        val crossings = index.crossings(r)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
        assertTrue(crossings.any { it.zone.id == "z0" && it.startsInside }, "the route starts inside the Madrid-sized zone")
        assertTrue(crossings.zipWithNext().all { (a, b) -> a.entryMeters <= b.entryMeters }, "ordered along the route")
        assertTrue(crossings.all { it.exitMeters >= it.entryMeters })
    }

    @Test fun `degenerate routes never throw`() {
        val index = ZbeIndex(zones())
        assertEquals(emptyList(), index.crossings(emptyList()))
        assertEquals(emptyList(), index.crossings(listOf(madrid)))
        // Every point identical (a zero-length route inside a zone): no crossing deep enough, and no division by zero.
        index.crossings(List(1_000) { madrid })
        // A route that doubles back on itself over the same ground, and one that hugs the antimeridian.
        index.crossings(route(2_000) + route(2_000).reversed())
        index.crossings(listOf(LatLon(0.0, 179.99), LatLon(0.0, -179.99)))
        index.crossings(listOf(LatLon(89.9, 0.0), LatLon(-89.9, 0.0)))
    }
}
