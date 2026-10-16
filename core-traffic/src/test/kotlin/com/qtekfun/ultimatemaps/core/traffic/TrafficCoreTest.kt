package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrafficCoreTest {
    // A tile over Madrid at zoom 10.
    private val tile = TileMath.tileOf(40.4168, -3.7038, 10)

    private fun at(px: Int, py: Int) = TileMath.toLatLon(tile, px.toDouble(), py.toDouble(), 4096)

    private fun flowTile(vararg f: MvtEncoder.Feat) = MvtEncoder.tile("Traffic flow", 4096, f.toList())

    @Test fun `tile maths round trips and the tile of Madrid is where it should be`() {
        assertEquals(TileId(10, 501, 386), tile) // x = (-3.7038 + 180) / 360 * 1024
        val corner = TileMath.toLatLon(tile, 0.0, 0.0, 4096)
        assertEquals(tile, TileMath.tileOf(corner.lat - 1e-6, corner.lon + 1e-6, 10))
        val far = TileMath.toLatLon(tile, 4096.0, 4096.0, 4096)
        assertTrue(far.lat < corner.lat && far.lon > corner.lon)
    }

    @Test fun `tiles along a route are the ones it crosses, without repeats`() {
        val a = LatLon(40.4168, -3.7038)
        val b = LatLon(41.3874, 2.1686) // Barcelona
        val tiles = TileMath.tilesAlong(listOf(a, b), 10)
        assertEquals(tiles.size, tiles.toSet().size)
        assertTrue(tiles.size in 20..80, "about 500 km at 40 km a tile: ${tiles.size}")
        assertEquals(TileMath.tileOf(a.lat, a.lon, 10), tiles.first())
        assertEquals(TileMath.tileOf(b.lat, b.lon, 10), tiles.last())
    }

    @Test fun `a tile is decoded into its features with tags and lines`() {
        val bytes = flowTile(
            MvtEncoder.Feat(mapOf("relative_speed" to 0.5, "road_type" to "Motorway", "road_closure" to true), listOf(MvtEncoder.Line(listOf(100 to 100, 200 to 150, 150 to 400)))),
            MvtEncoder.Feat(mapOf("relative_speed" to 1.0), listOf(MvtEncoder.Line(listOf(0 to 0, 10 to 10)), MvtEncoder.Line(listOf(20 to 20, 30 to 0)))),
        )
        val layers = Mvt.decode(bytes)
        assertEquals(1, layers.size)
        assertEquals("Traffic flow", layers[0].name)
        assertEquals(4096, layers[0].extent)
        val f0 = layers[0].features[0]
        assertEquals(0.5, f0.properties["relative_speed"])
        assertEquals("Motorway", f0.properties["road_type"])
        assertEquals(true, f0.properties["road_closure"])
        assertEquals(listOf(100 to 100, 200 to 150, 150 to 400), f0.lines.single().map { it[0] to it[1] })
        assertEquals(2, layers[0].features[1].lines.size)
    }

    @Test fun `a damaged tile is an error and not a crash`() {
        val bytes = flowTile(MvtEncoder.Feat(mapOf("relative_speed" to 0.5), listOf(MvtEncoder.Line(listOf(1 to 1, 2 to 2)))))
        val cut = bytes.copyOf(bytes.size - 5)
        val failed = runCatching { Mvt.decode(cut) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException, "$failed")
    }

    @Test fun `the flow field answers the nearest segment, honours one-side direction, ignores no data and reports closures`() {
        val bytes = flowTile(
            // a road going north (decreasing py), 0.4 of free flow, one side
            MvtEncoder.Feat(mapOf("relative_speed" to 0.4, "traffic_road_coverage" to "one_side"), listOf(MvtEncoder.Line(listOf(2000 to 3000, 2000 to 2000)))),
            // the same road southbound, free
            MvtEncoder.Feat(mapOf("relative_speed" to 1.0, "traffic_road_coverage" to "one_side"), listOf(MvtEncoder.Line(listOf(2002 to 2000, 2002 to 3000)))),
            // no data (level 0, no closure)
            MvtEncoder.Feat(mapOf("relative_speed" to 0.0), listOf(MvtEncoder.Line(listOf(500 to 500, 900 to 500)))),
            // a closed road
            MvtEncoder.Feat(mapOf("relative_speed" to 0.0, "road_closure" to true), listOf(MvtEncoder.Line(listOf(3000 to 500, 3400 to 500)))),
        )
        val field = FlowField.Builder().addTile(tile, Mvt.decode(bytes)).build()
        val p = at(2000, 2500)
        val north = assertNotNull(field.sampleAt(p.lat, p.lon, headingDeg = 0.0))
        assertEquals(0.4, north.ratio, 1e-9)
        val south = assertNotNull(field.sampleAt(at(2002, 2500).lat, at(2002, 2500).lon, headingDeg = 180.0))
        assertEquals(1.0, south.ratio, 1e-9)
        assertNull(field.sampleAt(at(700, 500).lat, at(700, 500).lon, null), "level 0 without closure is no data")
        assertTrue(assertNotNull(field.sampleAt(at(3200, 500).lat, at(3200, 500).lon, null)).closed)
        assertNull(field.sampleAt(at(1000, 3900).lat, at(1000, 3900).lon, null), "nothing within 25 m")
    }

    // A straight road north of (40, -3), 10 km, in 1 km pieces with their own level.
    private fun road(levels: List<Double?>): Pair<List<LatLon>, FlowField> {
        val route = (0..10).map { LatLon(40.0 + it * 0.009, -3.0) }
        val b = FlowField.Builder()
        levels.forEachIndexed { i, l ->
            if (l != null) b.addSegment(FlowField.Segment(route[i].lat, route[i].lon, route[i + 1].lat, route[i + 1].lon, l, oneSide = false, closed = false))
        }
        return route to b.build()
    }

    @Test fun `free flow everywhere adds no delay and a jam adds exactly its share`() {
        val (route, free) = road(List(10) { 1.0 })
        val none = RouteTraffic.estimate(route, 600.0, free)
        assertEquals(0.0, none.delaySeconds, 1e-6)
        assertEquals(1.0, none.coverage, 1e-9)
        // the 1 km at 0.25 of free flow: that tenth of the time takes four times as long
        val (_, jam) = road(List(10) { if (it == 4) 0.25 else 1.0 })
        val e = RouteTraffic.estimate(route, 600.0, jam)
        assertEquals(60.0 * (1.0 / 0.25 - 1.0), e.delaySeconds, 45.0) // the samples are ~167 m apart: the jam's edges can add or drop one, worth ~30 s
        assertEquals(0.25, e.worstRatio, 1e-9)
        assertEquals(600.0 + e.delaySeconds, e.adjustedSeconds, 1e-6)
        assertTrue(e.delayMinutes in 3..4, "${e.delayMinutes}")
    }

    @Test fun `the base speed decides how much of the time a jammed stretch holds`() {
        val route = road(List(10) { 1.0 }).first
        val (_, jam) = road(List(10) { if (it < 5) 0.5 else 1.0 })
        // the first half is slow urban (5 m/s), the second a motorway (30 m/s): the urban half holds most of the time
        val withSpeeds = RouteTraffic.estimate(route, 600.0, jam) { along -> if (along < 5_000.0) 5.0 else 30.0 }
        val uniform = RouteTraffic.estimate(route, 600.0, jam)
        assertTrue(withSpeeds.delaySeconds > uniform.delaySeconds * 1.5, "${withSpeeds.delaySeconds} vs ${uniform.delaySeconds}")
    }

    @Test fun `no data and closures are told apart`() {
        val route = road(List(10) { 1.0 }).first
        val noData = FlowField.Builder().addSegment(FlowField.Segment(40.5, -2.0, 40.6, -2.0, 0.3, false, false)).build() // elsewhere
        val e = RouteTraffic.estimate(route, 600.0, noData)
        assertEquals(0.0, e.delaySeconds)
        assertEquals(0.0, e.coverage, 1e-9)
        val closed = FlowField.Builder().addSegment(FlowField.Segment(route[3].lat, route[3].lon, route[4].lat, route[4].lon, 0.0, false, true)).build()
        val c = RouteTraffic.estimate(route, 600.0, closed)
        assertTrue(c.hasClosure && abs(c.closedMeters - 1_000.0) < 250.0, "closed ${c.closedMeters} m")
        assertTrue(c.delaySeconds > 600.0, "a closed kilometre is a very long delay")
    }

    @Test fun `degenerate routes are returned unchanged`() {
        val f = road(List(10) { 0.5 }).second
        assertEquals(100.0, RouteTraffic.estimate(emptyList(), 100.0, f).adjustedSeconds)
        assertEquals(100.0, RouteTraffic.estimate(listOf(LatLon(40.0, -3.0)), 100.0, f).adjustedSeconds)
        assertEquals(0.0, RouteTraffic.estimate(listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.0)), 0.0, f).adjustedSeconds)
    }
}
