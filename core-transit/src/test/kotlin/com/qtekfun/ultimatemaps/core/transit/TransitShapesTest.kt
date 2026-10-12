package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Line geometry end to end on the small synthetic feed: shapes.txt -> index -> file -> planner -> itinerary. */
class TransitShapesTest {
    private val zone = ZoneId.of("Europe/Madrid")

    /** Line L2 (metro, route R2: D, E, F along lon -3.0) given a shape that bulges 250 m east between the stops. Rows unordered. */
    private fun files(withShape: Boolean = true): Map<String, String> {
        val base = Fixtures.files()
        if (!withShape) return base
        val trips = base.getValue("trips.txt").lines().filter { it.isNotBlank() }.mapIndexed { i, l ->
            if (i == 0) "$l,shape_id" else if (l.startsWith("R2,")) "$l,SH2" else "$l,"
        }.joinToString("\n") + "\n"
        // lon offsets: 0.0035 deg is about 300 m at 40 N
        val shape = """
            shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
            SH2,40.0300,-3.0000,50
            SH2,40.0208,-3.0000,10
            SH2,40.0250,-2.9965,30
            SH2,40.0230,-2.9960,20
            SH2,40.0275,-2.9962,40
            SH2,40.0400,-3.0000,60
            SH2,40.0350,-2.9962,55
        """.trimIndent() + "\n"
        return base + mapOf("trips.txt" to trips, "shapes.txt" to shape)
    }

    private fun index(withShape: Boolean = true, useShapes: Boolean = true): TransitIndex {
        val feed = GtfsReader.read(MapGtfsSource(files(withShape)), GtfsReadOptions(shapeRouteTypes = ::isRailLike))
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("synthetic", "syn", "test", useShapes = useShapes))
        return b.build()
    }

    @Test
    fun `reader keeps shape points in sequence order and only for the asked route types`() {
        val feed = GtfsReader.read(MapGtfsSource(files()), GtfsReadOptions(shapeRouteTypes = ::isRailLike))
        assertEquals(listOf("SH2"), feed.shapeIds.toList())
        assertEquals(7, feed.shapeStart[1] - feed.shapeStart[0])
        assertEquals(40.0208, feed.shapeLat[0], 1e-9) // sequence 10 first
        assertEquals(40.0400, feed.shapeLat[6], 1e-9) // sequence 60 last
        val routeOfShaped = (feed.tripIds.indices).filter { feed.tripShape[it] >= 0 }.map { feed.routeShort[feed.tripRoute[it]] }.toSet()
        assertEquals(setOf("L2"), routeOfShaped) // L1 and L3 are buses: not asked
        val none = GtfsReader.read(MapGtfsSource(files()))
        assertTrue(none.shapeIds.isEmpty() && none.tripShape.all { it == -1 })
    }

    @Test
    fun `the index carries a polyline for the shaped rail pattern only`() {
        val idx = index()
        val shaped = (0 until idx.patternCount).filter { idx.hasShape(it) }
        assertEquals(1, shaped.size)
        assertEquals("L2", idx.lineShortName[idx.patternLine[shaped[0]]])
        assertEquals(1, idx.shapedPatternCount)
        val cov = idx.geometryCoverage()
        assertEquals(setOf(TransitMode.METRO), cov.keys) // buses are not counted
        assertEquals(GeometryCoverage(patterns = 1, withGeometry = 1, fullyOnShape = 1, hops = 2, hopsOnShape = 2), cov.getValue(TransitMode.METRO))
        assertNull(index(withShape = true, useShapes = false).patternShapeOffset)
    }

    @Test
    fun `file round trip keeps the polyline and an index without geometry still reads`() {
        val idx = index()
        val bytes = ByteArrayOutputStream().also { TransitIndexIo.write(idx, it) }.toByteArray()
        val back = TransitIndexIo.read(ByteArrayInputStream(bytes))
        assertEquals(idx.describe(), back.describe())
        assertEquals(idx.shapeLat!!.toList(), back.shapeLat!!.toList())
        assertEquals(idx.shapeLon!!.toList(), back.shapeLon!!.toList())
        assertEquals(idx.patternShapeOffset!!.toList(), back.patternShapeOffset!!.toList())
        assertEquals(idx.patternStopVertex!!.toList(), back.patternStopVertex!!.toList())
        assertEquals(idx.patternHopOnShape!!.toList(), back.patternHopOnShape!!.toList())

        val plain = index(withShape = false)
        val plainBack = TransitIndexIo.read(ByteArrayInputStream(ByteArrayOutputStream().also { TransitIndexIo.write(plain, it) }.toByteArray()))
        assertNull(plainBack.shapeLat)
        assertEquals(0, plainBack.shapedPatternCount)
    }

    @Test
    fun `geometry sits after the id sections so a reader that stops there is unaffected`() {
        // The id strings come first in the trailing section; an older reader reads flags 1 and 2 and ignores the rest.
        val idx = TransitIndexBuilder().let { b ->
            val feed = GtfsReader.read(MapGtfsSource(files()), GtfsReadOptions(shapeRouteTypes = ::isRailLike))
            b.addFeed(feed, FeedOptions("synthetic", "syn", "test", keepIds = true, useShapes = true))
            b.build()
        }
        val back = TransitIndexIo.read(ByteArrayInputStream(ByteArrayOutputStream().also { TransitIndexIo.write(idx, it) }.toByteArray()))
        assertNotNull(back.stopExtId)
        assertNotNull(back.tripExtId)
        assertEquals(idx.stopExtId!!.toList(), back.stopExtId!!.toList())
        assertEquals(1, back.shapedPatternCount)
    }

    @Test
    fun `an itinerary ride follows the polyline between its stops and slices keep it consistent`() {
        val service = TransitService(index(), zone)
        val at = LocalDateTime.parse("2026-10-14T08:30:00").atZone(zone).toInstant()
        val plan = service.plan(LatLon(40.0209, -3.0), LatLon(40.0401, -3.0), at)
        val ride = assertIs<TransitPlan.Found>(plan).itineraries.flatMap { it.rides }.first { it.line.shortName == "L2" }
        val g = assertNotNull(ride.geometry)
        assertEquals(ride.stops.size, g.stopVertex.size)
        assertEquals(g.points, ride.path)
        assertTrue(g.points.size > ride.stops.size, "the polyline has the bulge's vertices")
        assertTrue(g.onShape.all { it })
        // every stop's vertex is within 60 m of the stop (stations lie close to the line)
        for ((i, s) in ride.stops.withIndex()) assertTrue(g.points[g.stopVertex[i]].distanceTo(s.point) < 60.0, "stop $i")
        // the middle of the first hop is hundreds of metres from the chord: the line follows the shape
        val chordMid = LatLon((ride.stops[0].point.lat + ride.stops[1].point.lat) / 2, ride.stops[0].point.lon)
        assertTrue(g.points.subList(g.stopVertex[0], g.stopVertex[1] + 1).maxOf { it.distanceTo(chordMid) } > 200.0)
        // slicing to the first hop keeps the same vertices and the matching indices
        val head = ride.slice(0, 1)
        assertEquals(g.points.subList(g.stopVertex[0], g.stopVertex[1] + 1), head.path)
        assertEquals(listOf(0, head.path.size - 1), head.geometry!!.stopVertex.toList())
        // and joining the slices gives the original back
        val tail = ride.slice(1, 2)
        assertEquals(g.points, head.geometry!!.then(tail.geometry!!).points)
        assertEquals(g.stopVertex.toList(), head.geometry!!.then(tail.geometry!!).stopVertex.toList())
    }

    @Test
    fun `without geometry the ride is the stop-to-stop line as before`() {
        val service = TransitService(index(withShape = false), zone)
        val plan = service.plan(LatLon(40.0001, -3.0), LatLon(40.0401, -3.0), Instant.parse("2026-10-14T05:55:00Z"))
        val rides = assertIs<TransitPlan.Found>(plan).itineraries.first { it.rides.isNotEmpty() }.rides
        assertTrue(rides.all { it.geometry == null && it.shape == null })
        assertEquals(rides[0].stops.map { it.point }, rides[0].path)
    }
}
