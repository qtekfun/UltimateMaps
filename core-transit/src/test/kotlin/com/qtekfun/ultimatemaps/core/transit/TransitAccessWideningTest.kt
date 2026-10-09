package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mode filter applies before the nearest stops are chosen, and the access radius widens when nothing is reachable.
 * Made-up feed: many bus stops next to the origin, a train station [stationLat] away.
 */
class TransitAccessWideningTest {
    private val wed = day("2026-10-14")
    private val origin = LatLon(40.0001, -3.0)
    private val destination = LatLon(40.1200, -3.0)

    private fun hhmmss(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

    /** [busStops] bus stops from the origin northwards, one bus line to the destination, and a train from a station at [stationLat]. */
    private fun build(busStops: Int, stationLat: Double): TransitIndex {
        val stops = StringBuilder()
        for (i in 0 until busStops) stops.append("S$i,Bus $i,${java.lang.String.format(java.util.Locale.ROOT, "%.4f", 40.0000 + i * 0.0002)},-3.0000\n")
        stops.append("BE,Bus end,40.1200,-3.0000\nT,Station,${java.lang.String.format(java.util.Locale.ROOT, "%.4f", stationLat)},-3.0000\nU,Station end,40.1200,-3.0000\n")
        val busTimes = (0 until busStops).map { "S$it" to hms(8, 0) + it * 60 } + ("BE" to hms(9, 30))
        val trips = listOf(
            Triple("B", "B0", busTimes),
            Triple("R", "R0", listOf("T" to hms(8, 40), "U" to hms(8, 55))),
        )
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\n$stops",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nB,B1,Bus,3\nR,R1,Train,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20261001,20261031\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n" + trips.joinToString("") { "${it.first},WK,${it.second},${it.third.last().first}\n" },
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + trips.joinToString("") { t ->
                t.third.withIndex().joinToString("") { (i, st) -> "${t.second},${hhmmss(st.second)},${hhmmss(st.second)},${st.first},$i\n" }
            },
        )
        val b = TransitIndexBuilder()
        b.addFeed(GtfsReader.read(MapGtfsSource(files)), FeedOptions("w", "w", "test"))
        return b.build()
    }

    /** 60 minutes of walking at most is the default of the app setting. */
    private fun plan(index: TransitIndex, modes: Set<TransitMode> = TransitMode.ALL, cap: Int = 3600) =
        TransitPlanner(index).plan(origin, destination, wed, hms(7, 55), PlanOptions(modes = modes, maxTotalWalkSec = cap))

    private fun rides(j: Journey) = j.legs.filterIsInstance<Leg.Ride>()

    @Test
    fun `bus excluded finds the train past the crowd of bus stops and says the walk is long`() {
        val index = build(busStops = 35, stationLat = 40.0180)
        val js = plan(index, TransitMode.ALL - TransitMode.BUS)
        assertTrue(js.isNotEmpty(), "a train journey exists")
        for (j in js) for (r in rides(j)) assertEquals(2, index.lineType[r.line])
        val j = js.first()
        assertEquals(JourneyNote.LONG_WALK_TO_STATION, j.note)
        val access = j.legs.first() as Leg.Walk
        assertTrue(access.arriveSec - access.departSec > 1800, "about 2 km of walking")
        assertTrue(access.arriveSec - access.departSec <= 3600)
    }

    @Test
    fun `the widening follows the walking cap of the request`() {
        val index = build(busStops = 35, stationLat = 40.0180) // about 2 km: 35 min of access
        val noBus = TransitMode.ALL - TransitMode.BUS
        assertTrue(plan(index, noBus, cap = 1800).isEmpty(), "30 min is not enough")
        assertTrue(plan(index, noBus, cap = 0).isNotEmpty(), "no cap: up to the ceiling")
    }

    @Test
    fun `everything allowed is unchanged`() {
        val index = build(busStops = 35, stationLat = 40.0180)
        val js = plan(index)
        assertTrue(js.isNotEmpty())
        assertTrue(js.none { it.note == JourneyNote.LONG_WALK_TO_STATION })
        assertTrue(js.any { j -> rides(j).any { index.lineType[it.line] == 3 } }, "the bus is offered")
    }

    @Test
    fun `no station within the maximum access walk gives nothing`() {
        val index = build(busStops = 35, stationLat = 40.0540) // 6 km away
        assertTrue(plan(index, TransitMode.ALL - TransitMode.BUS).isEmpty())
        assertTrue(plan(index).isNotEmpty(), "the bus still works")
    }

    @Test
    fun `a station within the normal radius never gets the long walk note`() {
        val index = build(busStops = 3, stationLat = 40.0050) // 550 m
        val js = plan(index, TransitMode.ALL - TransitMode.BUS)
        assertTrue(js.isNotEmpty())
        assertNull(js.first().note)
    }
}
