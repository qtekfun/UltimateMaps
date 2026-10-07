package com.qtekfun.mapas.core.transit

import com.qtekfun.mapas.core.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransitTest {
    private val wed = day("2026-10-14") // Wednesday
    private val sun = day("2026-10-18")
    private val nearA = LatLon(40.0001, -3.0)
    private val nearF = LatLon(40.0401, -3.0)

    private fun best(js: List<Journey>) = js.minWith(compareBy({ it.arriveSec }, { it.transfers }))

    // ------------------------------------------------------------------ CSV

    @Test
    fun csvHandlesBomQuotesPaddingAndCrlf() {
        val text = "﻿a,b,c\r\n  x  ,\"hello, \"\"world\"\"\", 7 \r\n\r\nlast,\"multi\nline\",\r\n"
        CsvTable(ByteArrayInputStream(text.toByteArray())).use { t ->
            val a = t.col("a")
            val b = t.col("b")
            val c = t.col("c")
            assertTrue(t.next())
            assertEquals("x", t.str(a))
            assertEquals("hello, \"world\"", t.str(b))
            assertEquals(7, t.int(c))
            assertTrue(t.next())
            assertEquals("last", t.str(a))
            assertEquals("multi\nline", t.str(b))
            assertEquals("", t.str(c))
            assertEquals(-1, t.col("missing"))
        }
    }

    @Test
    fun csvParsesTimesAboveTwentyFourHours() {
        CsvTable(ByteArrayInputStream("t\n8:05:09\n25:30:00\n\n".toByteArray())).use { t ->
            val c = t.col("t")
            t.next()
            assertEquals(hms(8, 5, 9), t.timeSec(c))
            t.next()
            assertEquals(hms(25, 30), t.timeSec(c))
        }
    }

    // ------------------------------------------------------------------ planner

    @Test
    fun findsTransferItineraryAndPrefersItOverSlowDirectLine() {
        val planner = TransitPlanner(Fixtures.index())
        val js = planner.plan(nearA, nearF, wed, hms(7, 55))
        val j = best(js)
        assertEquals(2, j.rideCount)
        assertEquals(1, j.transfers)
        val rides = j.legs.filterIsInstance<Leg.Ride>()
        assertEquals("L1", planner.index.lineShortName[rides[0].line])
        assertEquals("L2", planner.index.lineShortName[rides[1].line])
        assertEquals(hms(8, 0), rides[0].departSec)
        assertEquals(2, rides[0].stopCount)
        assertEquals(hms(8, 10), rides[0].arriveSec)
        assertEquals(hms(8, 20), rides[1].departSec)
        assertEquals(hms(8, 40), rides[1].arriveSec)
        // walk between C and D sits between the rides
        val mid = j.legs[j.legs.indexOf(rides[0]) + 1] as Leg.Walk
        assertEquals("Charlie", planner.index.stopName[mid.fromStop])
        assertEquals("Delta", planner.index.stopName[mid.toStop])
        // access and egress walks bracket the journey
        assertTrue(j.legs.first() is Leg.Walk && j.legs.last() is Leg.Walk)
        assertTrue(j.arriveSec in hms(8, 40)..hms(8, 41))
        // Pareto set also contains the one-ride slow direct alternative, which arrives later
        assertTrue(js.any { it.rideCount == 1 && it.arriveSec > j.arriveSec })
    }

    @Test
    fun missesTightConnectionBecauseOfSlackAndWalking() {
        // L1 arrives at C 08:10 (+93 s walk +60 s slack) so the 08:10 trip of L2 (leaves D 08:10) is missed
        val planner = TransitPlanner(Fixtures.index())
        val j = best(planner.plan(nearA, nearF, wed, hms(7, 55)))
        val second = j.legs.filterIsInstance<Leg.Ride>()[1]
        assertTrue(second.departSec >= hms(8, 12))
    }

    @Test
    fun weekdayServiceDoesNotRunOnSunday() {
        val planner = TransitPlanner(Fixtures.index())
        val js = planner.plan(nearA, LatLon(40.0201, -3.0), sun, hms(9, 0))
        // only the weekend L1 trip at 10:00 exists, it reaches C
        val j = best(js)
        assertEquals(1, j.rideCount)
        assertEquals(hms(10, 0), j.legs.filterIsInstance<Leg.Ride>().single().departSec)
        // L2 (weekday only) is not available on Sunday: reaching F is only possible on Monday (next service day)
        val toF = planner.plan(nearA, nearF, sun, hms(9, 0))
        assertTrue(toF.isNotEmpty() && toF.all { it.arriveSec > 86400 }, toF.joinToString { it.format(planner.index) })
    }

    @Test
    fun calendarDatesRemoveAndAddServices() {
        val extra = mapOf(
            "calendar_dates.txt" to "service_id,date,exception_type\nWK,20261014,2\nWE,20261014,1\n",
        )
        val planner = TransitPlanner(Fixtures.index(extra))
        // Wednesday 14 Oct is turned into a weekend-pattern day: only the 10:00 L1 trip runs
        val j = best(planner.plan(nearA, LatLon(40.0201, -3.0), wed, hms(7, 55)))
        assertEquals(hms(10, 0), j.legs.filterIsInstance<Leg.Ride>().single().departSec)
    }

    @Test
    fun tripsPastMidnightBelongToThePreviousServiceDay() {
        // Night trip of the weekday service: A 24:30 -> B 24:40 -> C 24:50, i.e. 00:30 on Thursday.
        val extraTrips = "R1,WK,NIGHT,C\n"
        val extraTimes = "NIGHT,24:30:00,24:30:00,A,0\nNIGHT,24:40:00,24:40:00,B,1\nNIGHT,24:50:00,24:50:00,C,2\n"
        val planner = TransitPlanner(Fixtures.index(extraTrips = extraTrips, extraStopTimes = extraTimes))
        val thursday = wed + 1
        // Thursday 00:10 query: the night trip of Wednesday's service (time 24:30 of Wednesday) is boardable
        val j = best(planner.plan(nearA, LatLon(40.0201, -3.0), thursday, hms(0, 10)))
        val ride = j.legs.filterIsInstance<Leg.Ride>().single()
        assertEquals(hms(0, 30), ride.departSec)
        assertEquals(hms(0, 50), ride.arriveSec)
    }

    @Test
    fun journeysCanCrossMidnight() {
        val extraTrips = "R1,WK,NIGHT,C\n"
        val extraTimes = "NIGHT,23:50:00,23:50:00,A,0\nNIGHT,24:00:00,24:00:00,B,1\nNIGHT,24:10:00,24:10:00,C,2\n"
        val planner = TransitPlanner(Fixtures.index(extraTrips = extraTrips, extraStopTimes = extraTimes))
        val j = best(planner.plan(nearA, LatLon(40.0201, -3.0), wed, hms(23, 40)))
        assertTrue(j.arriveSec > 86400)
    }

    @Test
    fun frequenciesAreExpandedIntoExplicitTrips() {
        val files = Fixtures.files(
            extra = mapOf("frequencies.txt" to "trip_id,start_time,end_time,headway_secs\nL1_0,12:00:00,13:00:00,900\n"),
        )
        val feed = GtfsReader.read(MapGtfsSource(files))
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("f", "f", ""))
        val idx = b.build()
        val planner = TransitPlanner(idx)
        // kept as ONE frequency trip with 4 runs (14 trips: 6 + 6 + 1 + 1 weekend, the template included)
        assertEquals(14, idx.tripCount)
        assertEquals(4, idx.tripRuns.max())
        assertEquals(900, idx.tripHeadway.max())
        // mid-window boarding uses the closed-form next run: 12:20 -> 12:30
        val mid = best(planner.plan(nearA, LatLon(40.0201, -3.0), wed, hms(12, 20)))
        assertEquals(hms(12, 30), mid.legs.filterIsInstance<Leg.Ride>().single().departSec)
        // template trip L1_0 (08:00) is replaced by 12:00, 12:15, 12:30, 12:45
        val js = planner.planNextDepartures(nearA, LatLon(40.0201, -3.0), wed, hms(11, 55), 4)
        assertEquals(listOf(hms(12, 0), hms(12, 15), hms(12, 30), hms(12, 45)), js.map { it.legs.filterIsInstance<Leg.Ride>().single().departSec })
        // and 08:00 is no longer served by that trip (the 08:10 trip is the first one)
        val early = best(planner.plan(nearA, LatLon(40.0201, -3.0), wed, hms(7, 55)))
        assertEquals(hms(8, 10), early.legs.filterIsInstance<Leg.Ride>().single().departSec)
    }

    @Test
    fun nextDeparturesAreStrictlyIncreasing() {
        val planner = TransitPlanner(Fixtures.index())
        val js = planner.planNextDepartures(nearA, LatLon(40.0201, -3.0), wed, hms(7, 55), 4)
        assertEquals(4, js.size)
        val deps = js.map { it.legs.filterIsInstance<Leg.Ride>().first().departSec }
        assertEquals(deps.sorted(), deps)
        assertEquals(deps.size, deps.toSet().size)
        assertEquals(listOf(hms(8, 0), hms(8, 10), hms(8, 20), hms(8, 30)), deps)
    }

    @Test
    fun shortTripIsWalkOnlyAndFarAwayPlacesAreUnreachable() {
        val planner = TransitPlanner(Fixtures.index())
        val walk = planner.plan(LatLon(40.0, -3.0), LatLon(40.002, -3.0), wed, hms(9, 0))
        assertEquals(1, walk.size)
        assertEquals(0, walk[0].rideCount)
        // 2 km from any stop: no access
        assertTrue(planner.plan(LatLon(40.2, -3.0), nearF, wed, hms(9, 0)).isEmpty())
    }

    @Test
    fun transferTypeThreeBlocksTheWalkingTransfer() {
        val extra = mapOf("transfers.txt" to "from_stop_id,to_stop_id,transfer_type,min_transfer_time\nC,D,3,\n")
        val planner = TransitPlanner(Fixtures.index(extra))
        val j = best(planner.plan(nearA, nearF, wed, hms(7, 55)))
        // the only remaining way to F is the slow direct line
        assertEquals(1, j.rideCount)
        assertEquals("L3", planner.index.lineShortName[j.legs.filterIsInstance<Leg.Ride>().single().line])
    }

    @Test
    fun missingIntermediateTimesAreInterpolated() {
        val files = Fixtures.files(
            extraTrips = "R1,WK,GAP,C\n",
            extraStopTimes = "GAP,13:00:00,13:00:00,A,0\nGAP,,,B,1\nGAP,13:20:00,13:20:00,C,2\n",
        )
        val feed = GtfsReader.read(MapGtfsSource(files))
        val gap = feed.tripIds.indexOf("GAP")
        val k = feed.tripStopStart[gap] + 1
        assertEquals(hms(13, 10), feed.stTimeDep[k])
    }

    @Test
    fun stationPlatformsUseTheMinimumStationTransferTime() {
        // C and D become platforms of one station: walking is 93 s, the station minimum (120 s) wins.
        val stops = Fixtures.stopsTxt.replace("stop_id,stop_name,stop_lat,stop_lon", "stop_id,stop_name,stop_lat,stop_lon,parent_station")
            .lines().filter { it.isNotBlank() }.mapIndexed { i, l ->
                if (i == 0) l else if (l.startsWith("C,") || l.startsWith("D,")) "$l,STN" else "$l,"
            }.joinToString("\n") + "\n"
        val planner = TransitPlanner(Fixtures.index(mapOf("stops.txt" to stops)))
        val j = best(planner.plan(nearA, nearF, wed, hms(7, 55)))
        val walk = j.legs.filterIsInstance<Leg.Walk>().single { it.fromStop >= 0 && it.toStop >= 0 }
        assertEquals(120, walk.arriveSec - walk.departSec)
    }

    // ------------------------------------------------------------------ index file

    @Test
    fun indexFileRoundTripGivesIdenticalResults() {
        val idx = Fixtures.index()
        val bytes = ByteArrayOutputStream().also { TransitIndexIo.write(idx, it) }.toByteArray()
        val back = TransitIndexIo.read(ByteArrayInputStream(bytes))
        assertEquals(idx.describe(), back.describe())
        assertContentEquals(idx.arrivals, back.arrivals)
        assertContentEquals(idx.departures, back.departures)
        assertContentEquals(idx.patternStops, back.patternStops)
        assertEquals(idx.sources, back.sources)
        assertEquals("FF0000", "%06X".format(back.lineColor[0]))
        val a = best(TransitPlanner(idx).plan(nearA, nearF, wed, hms(7, 55)))
        val b = best(TransitPlanner(back).plan(nearA, nearF, wed, hms(7, 55)))
        assertEquals(a.legs.map { it::class }, b.legs.map { it::class })
        assertEquals(a.arriveSec, b.arriveSec)
        assertNotNull(back.sources.single().attribution)
    }

    @Test
    fun indexFileStoresRepeatedTimeProfilesOnce() {
        // 200 identical-shape trips share one stored profile: each costs only its (service, headsign, profile, time, headway, runs) tuple
        val trips = StringBuilder()
        val times = StringBuilder()
        for (i in 0 until 200) {
            trips.append("R1,WK,X$i,C\n")
            for ((k, s) in listOf("A", "B", "C").withIndex()) {
                val t = hms(9, 0) + i * 60 + k * 300
                times.append("X$i,${"%02d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)},${"%02d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)},$s,$k\n")
            }
        }
        val small = ByteArrayOutputStream().also { TransitIndexIo.write(Fixtures.index(), it) }.size()
        val big = ByteArrayOutputStream().also { TransitIndexIo.write(Fixtures.index(extraTrips = trips.toString(), extraStopTimes = times.toString()), it) }.size()
        assertTrue(big - small < 200 * 10, "200 extra trips added ${big - small} bytes")
    }

    @Test
    fun expiredCalendarCanBeForcedValid() {
        val feedFiles = Fixtures.files()
        val feed = GtfsReader.read(MapGtfsSource(feedFiles), GtfsReadOptions(ignoreCalendarRange = true))
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("f", "f", ""), ignoredCalendarRange = true)
        val planner = TransitPlanner(b.build())
        // March 2027 is outside the declared October 2026 validity, but the weekday pattern is used anyway
        val j = best(planner.plan(nearA, nearF, day("2027-03-10"), hms(7, 55)))
        assertEquals(2, j.rideCount)
    }

    @Test
    fun stopFilterCutsTripsToKeptStops() {
        val feed = GtfsReader.read(
            MapGtfsSource(Fixtures.files()),
            GtfsReadOptions(stopFilter = { lat, _ -> lat < 40.015 }), // keeps A and B only
        )
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("f", "f", ""))
        val idx = b.build()
        assertEquals(2, idx.stopCount)
    }
}
