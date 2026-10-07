package com.qtekfun.ultimatemaps.core.transit

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate

/** In-memory GTFS feed for tests. */
class MapGtfsSource(private val files: Map<String, String>) : GtfsSource {
    override fun open(name: String): InputStream? = files[name]?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
}

/** Epoch day of a date given as ISO text. */
fun day(iso: String): Int = LocalDate.parse(iso).toEpochDay().toInt()

fun hms(h: Int, m: Int, s: Int = 0): Int = h * 3600 + m * 60 + s

private fun t(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

/**
 * Synthetic network (about 0.01 degrees of latitude = 1.1 km):
 *
 * ```
 *  A --L1--> B --L1--> C ..(90 m walk).. D --L2--> E --L2--> F      (L1, L2 every 10 min, 5 min per hop)
 *  A ------------------L3 (slow, direct, 08:05 -> 09:30)-------> F
 * ```
 *
 * Service WK runs Monday to Friday during October 2026; WE runs Saturday and Sunday.
 */
object Fixtures {
    const val A_LAT = 40.0000
    const val A_LON = -3.0000
    val stopsTxt = """
        stop_id,stop_name,stop_lat,stop_lon
        A,Alpha,40.0000,-3.0000
        B,Bravo,40.0100,-3.0000
        C,Charlie,40.0200,-3.0000
        D,Delta,40.0208,-3.0000
        E,Echo,40.0300,-3.0000
        F,Foxtrot,40.0400,-3.0000
    """.trimIndent() + "\n"

    val routesTxt = """
        route_id,route_short_name,route_long_name,route_type,route_color,route_text_color
        R1,L1,Line one,3,FF0000,FFFFFF
        R2,L2,Line two,1,0000FF,FFFFFF
        R3,L3,Slow direct,3,,
    """.trimIndent() + "\n"

    val calendarTxt = """
        service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
        WK,1,1,1,1,1,0,0,20261001,20261031
        WE,0,0,0,0,0,1,1,20261001,20261031
    """.trimIndent() + "\n"

    /** Departures of a line from its first stop, each hop taking [hopSec]. */
    private fun trips(route: String, svc: String, prefix: String, firstDeps: List<Int>, stops: List<String>, hopSec: Int): Pair<String, String> {
        val tripLines = StringBuilder()
        val stLines = StringBuilder()
        for ((i, dep) in firstDeps.withIndex()) {
            val id = "$prefix$i"
            tripLines.append("$route,$svc,$id,${stops.last()}\n")
            for ((k, s) in stops.withIndex()) stLines.append("$id,${t(dep + k * hopSec)},${t(dep + k * hopSec)},$s,$k\n")
        }
        return tripLines.toString() to stLines.toString()
    }

    fun files(extra: Map<String, String> = emptyMap(), extraTrips: String = "", extraStopTimes: String = ""): Map<String, String> {
        val l1 = trips("R1", "WK", "L1_", (0 until 6).map { hms(8, 0) + it * 600 }, listOf("A", "B", "C"), 300)
        val l2 = trips("R2", "WK", "L2_", (0 until 6).map { hms(8, 20) + it * 600 }, listOf("D", "E", "F"), 600)
        val l3 = trips("R3", "WK", "L3_", listOf(hms(8, 5)), listOf("A", "F"), 85 * 60)
        val we = trips("R1", "WE", "L1WE_", listOf(hms(10, 0)), listOf("A", "B", "C"), 300)
        val m = mutableMapOf(
            "stops.txt" to stopsTxt,
            "routes.txt" to routesTxt,
            "calendar.txt" to calendarTxt,
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n" + l1.first + l2.first + l3.first + we.first + extraTrips,
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + l1.second + l2.second + l3.second + we.second + extraStopTimes,
        )
        m.putAll(extra)
        return m
    }

    fun index(extra: Map<String, String> = emptyMap(), extraTrips: String = "", extraStopTimes: String = ""): TransitIndex {
        val feed = GtfsReader.read(MapGtfsSource(files(extra, extraTrips, extraStopTimes)))
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("synthetic", "syn", "test"))
        return b.build()
    }
}
