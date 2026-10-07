package com.qtekfun.mapas.transit

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.transit.FeedOptions
import com.qtekfun.mapas.core.transit.GtfsReader
import com.qtekfun.mapas.core.transit.GtfsSource
import com.qtekfun.mapas.core.transit.TransitIndexBuilder
import com.qtekfun.mapas.core.transit.TransitService
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A made-up three-stop line, valid on weekdays of October 2026: no real data, no personal data. */
object TransitTestSupport {
    val zone: ZoneId = ZoneId.of("Europe/Madrid")
    val nearA = LatLon(40.0001, -3.0)
    val nearC = LatLon(40.0201, -3.0)

    private class MapSource(private val files: Map<String, String>) : GtfsSource {
        override fun open(name: String): InputStream? = files[name]?.let { ByteArrayInputStream(it.toByteArray()) }
    }

    private fun t(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

    fun service(): TransitService {
        val stopTimes = StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n")
        val trips = StringBuilder("route_id,service_id,trip_id,trip_headsign\n")
        for (n in 0 until 6) {
            val first = 8 * 3600 + n * 600
            trips.append("R1,WK,T$n,Charlie Town\n")
            listOf("A", "B", "C").forEachIndexed { k, s -> stopTimes.append("T$n,${t(first + k * 300)},${t(first + k * 300)},$s,$k\n") }
        }
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nA,Alpha Square,40.0000,-3.0000\nB,Bravo Street,40.0100,-3.0000\nC,Charlie Town,40.0200,-3.0000\n",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type,route_color,route_text_color\nR1,M1,Metro one,1,0000FF,FFFFFF\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20261001,20261031\n",
            "trips.txt" to trips.toString(),
            "stop_times.txt" to stopTimes.toString(),
        )
        val b = TransitIndexBuilder()
        b.addFeed(GtfsReader.read(MapSource(files)), FeedOptions("synthetic", "syn", "Powered by Test Agency (https://agency.example/). Processed data."))
        return TransitService(b.build(), zone)
    }

    fun clockAt(iso: String): TransitClock {
        val instant: Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()
        return object : TransitClock {
            override fun now(): Instant = instant
            override fun zone(): ZoneId = TransitTestSupport.zone
        }
    }
}
