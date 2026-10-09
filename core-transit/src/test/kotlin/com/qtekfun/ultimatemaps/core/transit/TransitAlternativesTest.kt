package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bounded set of alternatives with different trade-offs, on made-up feeds. */
class TransitAlternativesTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val wed = day("2026-10-14")
    private fun at(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()

    /** One trip: the stops with their times (seconds since midnight; arrival = departure). */
    private class Trip(val route: String, val id: String, val stops: List<Pair<String, Int>>)

    private fun build(stops: String, routes: String, trips: List<Trip>): TransitIndex {
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\n$stops\n",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\n$routes\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20261001,20261031\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n" + trips.joinToString("") { "${it.route},WK,${it.id},${it.stops.last().first}\n" },
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + trips.joinToString("") { t ->
                t.stops.withIndex().joinToString("") { (i, st) -> "${t.id},${hhmmss(st.second)},${hhmmss(st.second)},${st.first},$i\n" }
            },
        )
        val b = TransitIndexBuilder()
        b.addFeed(GtfsReader.read(MapGtfsSource(files)), FeedOptions("alt", "alt", "test"))
        return b.build()
    }

    private fun hhmmss(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

    /**
     * Walk the rest: X rides A to B (08:00 to 08:20); Y rides B2 (150 m from B) to E in 8 minutes, leaving at 08:25. The
     * destination is 1.5 km from B and next to E. The fastest trip changes from X to Y; X plus walking changes nowhere but walks
     * 26 minutes (more than the 15 minute cap) and arrives later.
     */
    private val walkTheRest: TransitIndex by lazy {
        build(
            stops = "A,Alpha,40.0000,-3.0000\nB,Bravo,40.1000,-3.0000\nB2,Bravo two,40.1013,-3.0000\nE,Echo,40.1136,-3.0000",
            routes = "X,LX,Line X,3\nY,LY,Line Y,3",
            trips = listOf(
                Trip("X", "X0", listOf("A" to hms(8, 0), "B" to hms(8, 20))),
                Trip("Y", "Y0", listOf("B2" to hms(8, 25), "E" to hms(8, 33))),
            ),
        )
    }
    private val origin = LatLon(40.0001, -3.0)
    private val farDestination = LatLon(40.1136, -3.0) // next to E, about 1.5 km north of B

    private fun journeys(index: TransitIndex, config: PlannerConfig = PlannerConfig(), options: PlanOptions = PlanOptions(), to: LatLon = farDestination, from: LatLon = origin) =
        TransitPlanner(index, config).plan(from, to, wed, hms(7, 55), options)

    @Test
    fun `a line whose station is 1_5 km from the destination offers walking the rest`() {
        val js = journeys(walkTheRest)
        val rest = js.single { it.note == JourneyNote.WALK_THE_REST }
        assertEquals(1, rest.rideCount)
        assertTrue(rest.walkSec > 900, "beyond the 15 minute cap: ${rest.walkSec}")
        val last = rest.legs.last()
        assertIs<Leg.Walk>(last)
        assertTrue(last.meters > 1500, "walks the rest from the station: ${last.meters} m")
        // the fastest trip is the one that changes, has no note, and comes before the extra
        val fastest = js.filter { it.rideCount > 0 }.minBy { it.arriveSec }
        assertEquals(2, fastest.rideCount)
        assertNull(fastest.note)
        assertTrue(fastest.arriveSec < rest.arriveSec)
        assertTrue(js.indexOf(fastest) < js.indexOf(rest))
    }

    @Test
    fun `a bigger walking limit makes the same trip an ordinary alternative with fewer changes`() {
        val js = journeys(walkTheRest, options = PlanOptions(maxTotalWalkSec = 3000))
        assertTrue(js.none { it.note == JourneyNote.WALK_THE_REST })
        val fewer = js.single { it.note == JourneyNote.FEWER_CHANGES }
        assertEquals(1, fewer.rideCount)
    }

    @Test
    fun `the walking limit is respected by the default list and extras can be switched off`() {
        val js = journeys(walkTheRest)
        assertTrue(js.filter { it.note != JourneyNote.WALK_THE_REST }.all { it.walkSec <= 900 })
        val none = journeys(walkTheRest, PlannerConfig(maxExtendedAlternatives = 0))
        assertTrue(none.none { it.note == JourneyNote.WALK_THE_REST })
        assertTrue(none.isNotEmpty())
        // a shorter extended limit than the walk: the extra is not found
        assertTrue(journeys(walkTheRest, PlannerConfig(extendedWalkSec = 1200)).none { it.note == JourneyNote.WALK_THE_REST })
    }

    @Test
    fun `the extra appears in the service list after the journeys within the limit`() {
        val found = assertIs<TransitPlan.Found>(TransitService(walkTheRest, zone).plan(origin, farDestination, at("2026-10-14T07:55:00")))
        val notes = found.itineraries.map { it.note }
        assertEquals(JourneyNote.WALK_THE_REST, notes.last())
        assertEquals(1, notes.count { it == JourneyNote.WALK_THE_REST })
        assertTrue(found.itineraries.first().rides.size == 2)
    }

    /**
     * Less walking: from the origin, a slow line S leaves a stop 100 m away at 08:00 (arrives 08:50), a fast line T leaves a stop
     * 700 m away at 08:10 (arrives 08:30).
     */
    private val lessWalking: TransitIndex by lazy {
        build(
            stops = "N,Near,40.0009,-3.0000\nF,Far,40.0063,-3.0000\nG,Goal,40.0600,-3.0000",
            routes = "S,LS,Slow,3\nT,LT,Fast,3",
            trips = listOf(
                Trip("S", "S0", listOf("N" to hms(8, 0), "G" to hms(8, 50))),
                Trip("T", "T0", listOf("F" to hms(8, 10), "G" to hms(8, 30))),
            ),
        )
    }

    @Test
    fun `an option with clearly less walking is offered and labelled`() {
        val js = journeys(lessWalking, to = LatLon(40.0600, -3.0), from = LatLon(40.0, -3.0)).filter { it.rideCount > 0 }
        assertEquals(2, js.size)
        val fast = js.minBy { it.arriveSec }
        val calm = js.maxBy { it.arriveSec }
        assertNull(fast.note)
        assertEquals(JourneyNote.LESS_WALKING, calm.note)
        assertTrue(calm.walkSec + 120 <= fast.walkSec)
    }

    @Test
    fun `nothing is shown that another option beats in arrival, walking and changes`() {
        for (idx in listOf(walkTheRest, lessWalking, Fixtures.index())) {
            val planner = TransitPlanner(idx)
            val tos = listOf(LatLon(40.0401, -3.0), farDestination, LatLon(40.0600, -3.0))
            for (to in tos) {
                val js = planner.plan(LatLon(40.0001, -3.0), to, wed, hms(7, 55)).filter { it.rideCount > 0 }
                for (a in js) for (b in js) if (a !== b) {
                    val dominated = a.arriveSec <= b.arriveSec && a.walkSec <= b.walkSec && a.rideCount <= b.rideCount
                    assertTrue(!dominated, "dominated:\n${b.format(idx)}\nby\n${a.format(idx)}")
                }
            }
        }
    }

    @Test
    fun `journeys that board the same lines at the same stops are merged`() {
        val idx = Fixtures.index()
        val js = TransitPlanner(idx).plan(LatLon(40.0001, -3.0), LatLon(40.0401, -3.0), wed, hms(7, 55)).filter { it.rideCount > 0 }
        val signatures = js.map { j -> j.legs.filterIsInstance<Leg.Ride>().map { it.line to it.fromStop } }
        assertEquals(signatures.size, signatures.toSet().size)
    }

    @Test
    fun `the number of alternatives is bounded`() {
        val one = journeys(Fixtures.index(), PlannerConfig(maxAlternatives = 1, maxExtendedAlternatives = 0), to = LatLon(40.0401, -3.0))
            .filter { it.rideCount > 0 }
        assertEquals(1, one.size)
        val capped = TransitPlanner(Fixtures.index(), PlannerConfig(maxAlternatives = 2, maxExtendedAlternatives = 1))
            .plan(LatLon(40.0001, -3.0), LatLon(40.0401, -3.0), wed, hms(7, 55)).filter { it.rideCount > 0 }
        assertTrue(capped.size <= 3)
    }

    @Test
    fun `without alternatives only the earliest arrival per number of rides is searched`() {
        val js = TransitPlanner(walkTheRest).plan(origin, farDestination, wed, hms(7, 55), alternatives = false)
        assertTrue(js.none { it.note != null })
        assertTrue(js.all { it.walkSec <= 900 })
    }
}
