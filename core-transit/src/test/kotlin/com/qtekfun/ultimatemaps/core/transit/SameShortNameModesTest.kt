package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Two unrelated lines share the short name "C2" (a train and a bus): the filter must go by each line's own type. */
class SameShortNameModesTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()

    // P to Q (4 km) is served by the train C2 (type 2, fast) and by the bus C2 (type 3, slower).
    private fun service(): TransitService {
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nP,Pine,40.1000,-3.0000\nQ,Quince,40.1360,-3.0000\n",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nRAIL,C2,Cercanias,2\nBUS,C2,Circular 2,3\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20261001,20261031\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\nRAIL,WK,T1,Train\nBUS,WK,T2,Bus\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "T1,08:10:00,08:10:00,P,0\nT1,08:20:00,08:20:00,Q,1\nT2,08:10:00,08:10:00,P,0\nT2,08:40:00,08:40:00,Q,1\n",
        )
        val b = TransitIndexBuilder()
        b.addFeed(GtfsReader.read(MapGtfsSource(files)), FeedOptions("two", "two", "test"))
        return TransitService(b.build(), zone)
    }

    private val p = LatLon(40.1000, -3.0)
    private val q = LatLon(40.1360, -3.0)

    private fun rideTypes(modes: Set<TransitMode>): List<Int> {
        val plan = service().plan(p, q, at("2026-10-14T08:00:00"), options = PlanOptions(modes = modes))
        return assertIs<TransitPlan.Found>(plan).itineraries.flatMap { it.legs }.filterIsInstance<ItineraryLeg.Ride>().map { it.line.routeType }
    }

    @Test
    fun `with trains off only the bus C2 is boarded`() {
        val types = rideTypes(TransitMode.ALL - TransitMode.TRAIN)
        assertTrue(types.isNotEmpty())
        assertTrue(types.all { it == 3 }, "types=$types")
    }

    @Test
    fun `with buses off only the train C2 is boarded`() {
        val types = rideTypes(TransitMode.ALL - TransitMode.BUS)
        assertTrue(types.isNotEmpty())
        assertTrue(types.all { it == 2 }, "types=$types")
    }

    @Test
    fun `with every mode on the faster train C2 is the one offered`() {
        val types = rideTypes(TransitMode.ALL)
        assertTrue(2 in types, "types=$types")
    }
}
