package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Point-in-polygon and route-crossing tests on synthetic zones around Madrid (0.01 degrees is about 1.1 km north-south and 0.85 km east-west). */
class ZbeIndexTest {
    private fun ring(vararg latLon: Pair<Double, Double>) = ZbeRing(latLon.flatMap { listOf(it.first, it.second) }.toDoubleArray())
    private fun zone(id: String = "z", vararg polygons: List<ZbeRing>) = ZbeZone(id, id, "Madrid", "", polygons.map { ZbePolygon(it) })
    private fun route(vararg p: Pair<Double, Double>) = p.map { LatLon(it.first, it.second) }
    private fun index(vararg z: ZbeZone) = ZbeIndex(z.toList())
    private fun lonMeters(lat: Double, degrees: Double) = degrees * 111_194.9 * Math.cos(Math.toRadians(lat))
    private fun length(r: List<LatLon>) = r.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

    private val s = 40.40
    private val w = -3.70

    /** A square, 0.02 degrees a side: lat 40.40..40.42, lon -3.70..-3.68. */
    private val square = zone("sq", listOf(ring(s to w, s to w + 0.02, s + 0.02 to w + 0.02, s + 0.02 to w)))

    /** A U opening to the east: arms at lat 0..1 and 2..3, base at lon 0..1 (units of 0.01 degree from (s, w)). */
    private fun u(notchHeightUnits: Double = 1.0): ZbeZone {
        val h = 0.01
        val a = 0.01 + (1.0 - notchHeightUnits) * 0.005 // keep the notch centred on lat 1.5 units
        val lo = s + a
        val hi = lo + notchHeightUnits * h
        return zone(
            "u",
            listOf(ring(s to w, s to w + 0.03, lo to w + 0.03, lo to w + 0.01, hi to w + 0.01, hi to w + 0.03, s + 0.03 to w + 0.03, s + 0.03 to w)),
        )
    }

    // ------------------------------------------------------------------------------------------------ points

    @Test fun pointInsideAndOutsideASquare() {
        assertTrue(square.contains(40.41, -3.69))
        assertFalse(square.contains(40.43, -3.69))
        assertFalse(square.contains(40.41, -3.71))
        assertFalse(square.contains(40.41, -3.67))
    }

    @Test fun pointsInAConcavePolygon() {
        val z = u()
        assertTrue(z.contains(s + 0.005, w + 0.02), "south arm")
        assertTrue(z.contains(s + 0.025, w + 0.02), "north arm")
        assertFalse(z.contains(s + 0.015, w + 0.02), "inside the notch")
        assertTrue(z.contains(s + 0.015, w + 0.005), "the base")
    }

    @Test fun pointsAroundAHole() {
        val z = zone("h", listOf(
            ring(s to w, s to w + 0.03, s + 0.03 to w + 0.03, s + 0.03 to w),
            ring(s + 0.01 to w + 0.01, s + 0.01 to w + 0.02, s + 0.02 to w + 0.02, s + 0.02 to w + 0.01),
        ))
        assertTrue(z.contains(s + 0.005, w + 0.005))
        assertFalse(z.contains(s + 0.015, w + 0.015), "in the hole")
        assertTrue(z.contains(s + 0.015, w + 0.025))
    }

    @Test fun ringOrientationDoesNotMatter() {
        val cw = zone("cw", listOf(ring(s to w, s + 0.02 to w, s + 0.02 to w + 0.02, s to w + 0.02)))
        assertTrue(cw.contains(40.41, -3.69))
        assertEquals(listOf("sq"), index(square).zonesAt(40.41, -3.69).map { it.id })
        assertTrue(index(square).zonesAt(41.0, -3.69).isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ routes

    @Test fun aRouteFarAwayDoesNotCross() {
        assertTrue(index(square).crossings(route(41.0 to -3.0, 41.1 to -3.0)).isEmpty())
        assertTrue(ZbeIndex(emptyList()).crossings(route(s to w, s + 1 to w)).isEmpty())
        assertTrue(index(square).crossings(route(s to w)).isEmpty(), "a single point is not a route")
    }

    @Test fun aRouteAroundTheZoneInsideItsBoxDoesNotCross() {
        val z = u()
        // goes into the notch and turns back without touching the arms: the box overlaps, the polygon does not
        val r = route(s + 0.015 to w + 0.03, s + 0.015 to w + 0.012)
        assertTrue(index(z).crossings(r).isEmpty())
    }

    @Test fun aRouteStraightThroughEntersAndLeaves() {
        val r = route(40.41 to -3.71, 40.41 to -3.67)
        val c = index(square).crossings(r).single()
        val toBorder = r[0].distanceTo(LatLon(40.41, -3.70))
        assertEquals(toBorder, c.entryMeters, 2.0)
        assertEquals(toBorder + lonMeters(40.41, 0.02), c.exitMeters, 5.0)
        assertFalse(c.startsInside)
        assertFalse(c.endsInside)
        assertEquals("sq", c.zone.id)
    }

    @Test fun anIntermediateVertexDoesNotSplitACrossing() {
        val r = route(40.41 to -3.71, 40.41 to -3.69, 40.41 to -3.67)
        assertEquals(1, index(square).crossings(r).size)
    }

    @Test fun aRouteThatStartsInsideHasEntryZero() {
        val r = route(40.41 to -3.69, 40.41 to -3.66)
        val c = index(square).crossings(r).single()
        assertEquals(0.0, c.entryMeters, 0.01)
        assertTrue(c.startsInside)
        assertFalse(c.endsInside)
    }

    @Test fun aRouteThatEndsInsideReportsTheDestination() {
        val r = route(40.41 to -3.72, 40.41 to -3.69)
        val c = index(square).crossings(r).single()
        assertTrue(c.endsInside)
        assertEquals(length(r), c.exitMeters, 0.5)
    }

    @Test fun aRouteEntirelyInsideIsOneCrossingFromStartToEnd() {
        val r = route(40.405 to -3.695, 40.415 to -3.685)
        val c = index(square).crossings(r).single()
        assertTrue(c.startsInside && c.endsInside)
        assertEquals(0.0, c.entryMeters, 0.01)
        assertEquals(length(r), c.exitMeters, 0.5)
    }

    @Test fun aRouteThatOnlyTouchesACornerIsNotACrossing() {
        // the diagonal touches the north-east corner (40.42, -3.68) from outside and leaves again
        val r = route(40.43 to -3.67, 40.42 to -3.68, 40.41 to -3.67)
        assertTrue(index(square).crossings(r).isEmpty())
    }

    @Test fun aRouteAlongTheBorderIsNotACrossing() {
        // exactly on the western edge, then 3 m inside it for 500 m: roads that follow a zone border must not warn
        val onEdge = route(40.40 to -3.70, 40.42 to -3.70)
        assertTrue(index(square).crossings(onEdge).isEmpty())
        val threeMetersIn = route(40.405 to -3.70 + 0.000035, 40.409 to -3.70 + 0.000035)
        assertTrue(index(square).crossings(threeMetersIn).isEmpty(), "3 m deep is below the minimum depth")
    }

    @Test fun aRouteWellInsideTheBorderIsACrossing() {
        val thirtyMetersIn = route(40.405 to -3.70 + 0.00035, 40.409 to -3.70 + 0.00035)
        assertEquals(1, index(square).crossings(thirtyMetersIn).size)
    }

    @Test fun aShallowCutOfACornerIsNotACrossingButADeepOneIs() {
        // a chord that clips the south-west corner for a few metres
        val shallow = route(40.3999 to -3.6995, 40.4004 to -3.7003)
        assertTrue(index(square).crossings(shallow).isEmpty())
        val deep = route(40.39 to -3.69, 40.41 to -3.69)
        assertEquals(1, index(square).crossings(deep).size)
    }

    @Test fun aConcavePolygonGivesTwoCrossingsWhenTheRoutePassesTheNotch() {
        // south to north at lon w + 2 units: arm, notch, arm
        val r = route(s - 0.005 to w + 0.02, s + 0.035 to w + 0.02)
        val cs = index(u()).crossings(r)
        assertEquals(2, cs.size)
        assertTrue(cs[0].exitMeters < cs[1].entryMeters)
        assertEquals(0.01 * 111_195, cs[0].exitMeters - cs[0].entryMeters, 15.0)
    }

    @Test fun aRouteThroughTheNotchOnlyCrossesTheBase() {
        val r = route(s + 0.015 to w + 0.04, s + 0.015 to w - 0.01)
        val c = index(u()).crossings(r).single()
        // enters the base at lon w + 1 unit and leaves it at w
        assertEquals(lonMeters(40.415, 0.01), c.exitMeters - c.entryMeters, 8.0)
    }

    @Test fun twoStretchesAFewMetresApartAreOneCrossing() {
        val narrow = u(notchHeightUnits = 0.009) // a 10 m notch
        val r = route(s - 0.005 to w + 0.02, s + 0.035 to w + 0.02)
        assertEquals(1, index(narrow).crossings(r).size, "a 10 m gap is the route brushing the border, not leaving")
        assertEquals(2, index(u()).crossings(r).size, "a 1 km notch is a real exit")
    }

    @Test fun aRoutePassingThroughAHoleIsTwoCrossings() {
        val z = zone("h", listOf(
            ring(s to w, s to w + 0.03, s + 0.03 to w + 0.03, s + 0.03 to w),
            ring(s + 0.01 to w + 0.01, s + 0.01 to w + 0.02, s + 0.02 to w + 0.02, s + 0.02 to w + 0.01),
        ))
        val r = route(s + 0.015 to w - 0.01, s + 0.015 to w + 0.04)
        val cs = index(z).crossings(r)
        assertEquals(2, cs.size)
    }

    @Test fun aRouteEntirelyInsideAHoleDoesNotCross() {
        val z = zone("h", listOf(
            ring(s to w, s to w + 0.03, s + 0.03 to w + 0.03, s + 0.03 to w),
            ring(s + 0.01 to w + 0.01, s + 0.01 to w + 0.02, s + 0.02 to w + 0.02, s + 0.02 to w + 0.01),
        ))
        val r = route(s + 0.013 to w + 0.012, s + 0.017 to w + 0.018)
        assertTrue(index(z).crossings(r).isEmpty())
    }

    @Test fun aZoneMadeOfTwoPartsIsCrossedTwice() {
        val z = zone("two",
            listOf(ring(s to w, s to w + 0.01, s + 0.02 to w + 0.01, s + 0.02 to w)),
            listOf(ring(s to w + 0.03, s to w + 0.04, s + 0.02 to w + 0.04, s + 0.02 to w + 0.03)),
        )
        val r = route(s + 0.01 to w - 0.01, s + 0.01 to w + 0.05)
        assertEquals(2, index(z).crossings(r).size)
    }

    @Test fun crossingsOfSeveralZonesAreOrderedAlongTheRoute() {
        val east = zone("east", listOf(ring(s to w + 0.05, s to w + 0.07, s + 0.02 to w + 0.07, s + 0.02 to w + 0.05)))
        val r = route(s + 0.01 to w - 0.01, s + 0.01 to w + 0.08)
        assertEquals(listOf("sq", "east"), index(east, square).crossings(r).map { it.zone.id })
    }

    @Test fun overlappingZonesBothReport() {
        val core = zone("core", listOf(ring(s + 0.005 to w + 0.005, s + 0.005 to w + 0.015, s + 0.015 to w + 0.015, s + 0.015 to w + 0.005)))
        val r = route(s + 0.01 to w - 0.01, s + 0.01 to w + 0.03)
        assertEquals(setOf("sq", "core"), index(square, core).crossings(r).map { it.zone.id }.toSet())
    }

    @Test fun aLongRouteWithManyPointsIsHandled() {
        val pts = (0..20_000).map { LatLon(41.0 + it * 1e-5, -3.0 + it * 1e-5) } + listOf(LatLon(40.41, -3.69))
        index(square).crossings(pts) // must simply finish
    }

    @Test fun theLabelUsesTheNameAndTheCity() {
        assertEquals("Centro (Madrid)", ZbeZone("a", "Centro", "Madrid", "", square.polygons).label)
        assertEquals("ZBE Madrid", ZbeZone("a", "ZBE Madrid", "Madrid", "", square.polygons).label)
        assertEquals("Madrid", ZbeZone("a", "", "Madrid", "", square.polygons).label)
    }
}
