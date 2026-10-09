package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The choice of accepting vehicle changes or not. The origin is 200 m from stop N (line P to the hub H, then line Q to the
 * destination D: one change) and 1.5 km from stop F, whose line R goes straight to D. The sensible option for someone who
 * dislikes changes is walking to F, even if it is longer; it has to be offered.
 */
class TransitTransfersOptionTest {
    private val zone = java.time.ZoneId.of("Europe/Madrid")
    private val wed = day("2026-10-14")

    private class Trip(val route: String, val id: String, val stops: List<Pair<String, Int>>)

    private fun build(stops: String, routes: String, trips: List<Trip>): TransitIndex {
        fun hhmmss(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
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
        b.addFeed(GtfsReader.read(MapGtfsSource(files)), FeedOptions("tr", "tr", "test"))
        return b.build()
    }

    // Origin (40.0000,-3.0000). N is 200 m north, F 1.5 km east. H hub far north, D destination north-east of H.
    private val index: TransitIndex by lazy {
        build(
            stops = "N,Near,40.0018,-3.0000\nH,Hub,40.0500,-3.0000\nH2,Hub two,40.0502,-3.0000\nD,Dest,40.1000,-2.9900\nF,Far,40.0000,-2.9825\nFD,Far dest,40.1002,-2.9902",
            routes = "P,LP,Line P,2\nQ,LQ,Line Q,2\nR,LR,Line R,2",
            trips = listOf(
                Trip("P", "P0", listOf("N" to hms(8, 0), "H" to hms(8, 10))),
                Trip("Q", "Q0", listOf("H2" to hms(8, 15), "D" to hms(8, 22))),
                Trip("R", "R0", listOf("F" to hms(8, 30), "FD" to hms(8, 45))),
            ),
        )
    }
    private val origin = LatLon(40.0000, -3.0000)
    private val destination = LatLon(40.1001, -2.9901)

    private fun plan(options: PlanOptions) = TransitPlanner(index).plan(origin, destination, wed, hms(7, 50), options)

    @Test
    fun `by default the nearest stop with a change is the fastest and the direct line from a farther stop is also offered`() {
        val js = plan(PlanOptions(maxTotalWalkSec = 3600)).filter { it.rideCount > 0 }
        val fastest = js.minBy { it.arriveSec }
        assertEquals(2, fastest.rideCount)
        val direct = js.firstOrNull { it.rideCount == 1 }
        assertNotNull(direct, "a journey without changes is offered: $js")
        assertTrue((direct.legs.first() as Leg.Walk).meters > 1400, "it walks to the farther stop: ${direct.legs}")
    }

    @Test
    fun `with the default walking limit the direct line is still offered as a longer walk`() {
        val js = plan(PlanOptions(maxTotalWalkSec = 900)).filter { it.rideCount > 0 }
        assertTrue(js.any { it.rideCount == 1 }, "a journey without changes is offered: $js")
    }

    @Test
    fun `no changes allowed leaves only the direct journeys`() {
        val js = plan(PlanOptions(maxTotalWalkSec = 3600, maxTransfers = 0)).filter { it.rideCount > 0 }
        assertTrue(js.isNotEmpty())
        assertTrue(js.all { it.transfers == 0 })
    }

    @Test
    fun `no changes allowed with the normal walking limit offers nothing beyond the limit`() {
        val js = plan(PlanOptions(maxTotalWalkSec = 900, maxTransfers = 0)).filter { it.rideCount > 0 }
        assertTrue(js.all { it.transfers == 0 }, "$js")
    }

    @Test
    fun `any number of changes keeps the fastest journey as before`() {
        val js = plan(PlanOptions(maxTotalWalkSec = 3600)).filter { it.rideCount > 0 }
        assertEquals(js.minBy { it.arriveSec }.transfers, 1)
    }
}
