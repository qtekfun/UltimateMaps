package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walking alternative, savings thresholds, walking cap and mode filter, on made-up feeds. */
class TransitOptionsTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val wed = day("2026-10-14")
    private fun at(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()

    // ---------------------------------------------------------------- the main synthetic network (Fixtures)

    private val nearA = LatLon(40.0001, -3.0)
    private val nearB = LatLon(40.0101, -3.0)
    private val nearC = LatLon(40.0201, -3.0)
    private val nearF = LatLon(40.0401, -3.0)
    private fun service() = TransitService(Fixtures.index(), zone)

    @Test
    fun `mode of a route type covers the basic and the extended codes`() {
        val expected = mapOf(
            3 to TransitMode.BUS, 11 to TransitMode.BUS, 700 to TransitMode.BUS, 714 to TransitMode.BUS, 800 to TransitMode.BUS,
            1 to TransitMode.METRO, 400 to TransitMode.METRO, 401 to TransitMode.METRO,
            0 to TransitMode.TRAM, 900 to TransitMode.TRAM, 901 to TransitMode.TRAM,
            2 to TransitMode.TRAIN, 100 to TransitMode.TRAIN, 109 to TransitMode.TRAIN, 300 to TransitMode.TRAIN,
            4 to TransitMode.FERRY, 1200 to TransitMode.FERRY, 1000 to TransitMode.FERRY,
            5 to TransitMode.OTHER, 6 to TransitMode.OTHER, 7 to TransitMode.OTHER, 12 to TransitMode.OTHER, 1300 to TransitMode.OTHER, 1500 to TransitMode.OTHER,
        )
        for ((type, mode) in expected) assertEquals(mode, TransitMode.ofRouteType(type), "route type $type")
    }

    @Test
    fun `an index reports the modes of its lines`() {
        assertEquals(setOf(TransitMode.BUS, TransitMode.METRO), Fixtures.index().availableModes)
    }

    @Test
    fun `walking is offered next to the ride when it takes under the limit`() {
        val found = assertIs<TransitPlan.Found>(service().plan(nearA, nearB, at("2026-10-14T07:55:00")))
        val walk = found.itineraries.single { it.isWalkOnly }
        assertNull(walk.note, "the ride saves a lot: no reason is needed")
        assertTrue(found.itineraries.any { !it.isWalkOnly })
        assertEquals(found.itineraries.sortedBy { it.arriveAt }, found.itineraries)
    }

    @Test
    fun `walking is not offered when it takes longer than the limit`() {
        val found = assertIs<TransitPlan.Found>(service().plan(nearA, nearB, at("2026-10-14T07:55:00"), options = PlanOptions(walkAlternativeMaxSec = 600)))
        assertTrue(found.itineraries.none { it.isWalkOnly })
        // and a far trip never offers a walk
        val far = assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-14T07:55:00")))
        assertTrue(far.itineraries.none { it.isWalkOnly })
    }

    @Test
    fun `the walk is kept when the list is cut`() {
        // two quick buses a minute apart both beat the 9-minute walk by more than 5 minutes; three results fit two places
        val found = assertIs<TransitPlan.Found>(twoStops(2 * 60, gapSec = 60, trips = 2).plan(p, q, at("2026-10-14T07:59:00"), maxItineraries = 2))
        assertEquals(2, found.itineraries.size)
        assertEquals(1, found.itineraries.count { it.isWalkOnly })
        assertFalse(found.itineraries.first().isWalkOnly)
    }

    // ---------------------------------------------------------------- savings thresholds (a two-stop line)

    /** P and Q are about 555 m apart (walking 578 s with the detour); one line runs P to Q taking [hopSec]. */
    private fun twoStops(hopSec: Int, gapSec: Int = 1800, trips: Int = 2): TransitService {
        val tripRows = (0 until trips).joinToString("") { "R,WK,T$it,Quince\n" }
        val timeRows = (0 until trips).joinToString("") {
            "T$it,${hms(it * gapSec)},${hms(it * gapSec)},P,0\nT$it,${hms(it * gapSec + hopSec)},${hms(it * gapSec + hopSec)},Q,1\n"
        }
        val files = mapOf(
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nP,Pine,40.1000,-3.0000\nQ,Quince,40.1050,-3.0000\n",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nR,B1,Bus one,3\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20261001,20261031\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n$tripRows",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n$timeRows",
        )
        val b = TransitIndexBuilder()
        b.addFeed(GtfsReader.read(MapGtfsSource(files)), FeedOptions("two", "two", "test"))
        return TransitService(b.build(), zone)
    }

    private fun hms(afterEight: Int): String = "%02d:%02d:%02d".format(8 + afterEight / 3600, afterEight / 60 % 60, afterEight % 60)

    private val p = LatLon(40.1000, -3.0)
    private val q = LatLon(40.1050, -3.0)

    @Test
    fun `a bus for two stops that saves two minutes is dropped and walking comes first with a reason`() {
        // walking 07:59 -> about 08:08:38; the bus arrives 08:07 (+ a few seconds to walk off): saves under 2 minutes
        val found = assertIs<TransitPlan.Found>(twoStops(7 * 60).plan(p, q, at("2026-10-14T07:59:00")))
        assertTrue(found.itineraries.all { it.isWalkOnly }, "no ride is offered")
        assertEquals(JourneyNote.WALK_ABOUT_AS_FAST, found.itineraries.first().note)
    }

    @Test
    fun `a ride that saves enough is kept next to the walk and carries no note`() {
        // the bus takes 2 minutes: arrives 08:02, saving about 6.5 minutes (and well over 20 percent)
        val found = assertIs<TransitPlan.Found>(twoStops(2 * 60).plan(p, q, at("2026-10-14T07:59:00")))
        assertTrue(found.itineraries.any { !it.isWalkOnly })
        val walk = found.itineraries.single { it.isWalkOnly }
        assertNull(walk.note)
        assertFalse(found.itineraries.first().isWalkOnly, "the quicker ride comes first")
    }

    @Test
    fun `the minimum saving is a setting`() {
        val s = twoStops(2 * 60)
        // saving is about 390 s: needs less than that to be kept
        assertTrue(assertIs<TransitPlan.Found>(s.plan(p, q, at("2026-10-14T07:59:00"), options = PlanOptions(minTransitSavingSec = 300))).itineraries.any { !it.isWalkOnly })
        val strict = assertIs<TransitPlan.Found>(s.plan(p, q, at("2026-10-14T07:59:00"), options = PlanOptions(minTransitSavingSec = 600)))
        assertTrue(strict.itineraries.all { it.isWalkOnly })
        assertEquals(JourneyNote.WALK_ABOUT_AS_FAST, strict.itineraries.single().note)
    }

    @Test
    fun `a saving under twenty percent of the walk is dropped even with a zero minimum`() {
        // bus arrives 08:06: saves about 158 s of a 578 s walk (27 %) -> kept; arrives 08:07 -> 98 s (17 %) -> dropped
        val zero = PlanOptions(minTransitSavingSec = 0)
        assertTrue(assertIs<TransitPlan.Found>(twoStops(6 * 60).plan(p, q, at("2026-10-14T07:59:00"), options = zero)).itineraries.any { !it.isWalkOnly })
        assertTrue(assertIs<TransitPlan.Found>(twoStops(7 * 60).plan(p, q, at("2026-10-14T07:59:00"), options = zero)).itineraries.all { it.isWalkOnly })
    }

    @Test
    fun `a later departure is compared with leaving on foot now`() {
        // asking at 07:30: the 08:00 bus arrives long after walking from 07:30 would; nothing but the walk is offered
        val found = assertIs<TransitPlan.Found>(twoStops(2 * 60).plan(p, q, at("2026-10-14T07:30:00")))
        assertTrue(found.itineraries.all { it.isWalkOnly })
    }

    // ---------------------------------------------------------------- the walking cap

    private val farWest = LatLon(39.9946, -3.0) // about 600 m south of A: 624 s on foot

    private fun journeys(cap: Int?, modes: Set<TransitMode> = TransitMode.ALL): List<Journey> {
        val planner = TransitPlanner(Fixtures.index())
        return planner.plan(farWest, nearF, wed, 7 * 3600 + 40 * 60, PlanOptions(modes = modes, maxTotalWalkSec = cap))
    }

    @Test
    fun `the walking cap prunes journeys that walk too much`() {
        // no cap: both the L1+L2 transfer trip and the slow direct L3 are found
        val all = journeys(0)
        assertTrue(all.any { it.transfers == 1 })
        assertTrue(all.any { j -> j.legs.filterIsInstance<Leg.Ride>().singleOrNull()?.line == 2 })
        // 700 s: access (624 s) is fine, but with the transfer walk and the exit the L1+L2 trip walks about 730 s
        // (beyond the cap only the marked "walk the rest" extras may appear)
        val some = journeys(700).filter { it.note != JourneyNote.WALK_THE_REST }
        assertTrue(some.none { it.transfers == 1 }, "the transfer trip walks too much")
        assertTrue(some.isNotEmpty() && some.all { it.walkSec <= 700 })
        // 600 s: even the first walk is too long, nothing can be boarded
        assertTrue(journeys(600).none { it.note != JourneyNote.WALK_THE_REST })
    }

    @Test
    fun `the default cap is fifteen minutes and zero means no cap`() {
        assertEquals(900, PlannerConfig().maxTotalWalkSec)
        assertTrue(journeys(null).filter { it.note != JourneyNote.WALK_THE_REST }.all { it.walkSec <= 900 })
        assertTrue(journeys(null).filter { it.note == JourneyNote.WALK_THE_REST }.all { it.walkSec > 900 })
        assertTrue(journeys(0).isNotEmpty())
    }

    // ---------------------------------------------------------------- the mode filter

    @Test
    fun `excluding metro leaves the direct bus and drops the trip that needs the metro`() {
        val found = assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-14T07:55:00"), options = PlanOptions(modes = TransitMode.ALL - TransitMode.METRO)))
        // (L1 followed by a long walk is a marked extra, not a trip that needs the metro)
        val lines = found.itineraries.filter { it.note != JourneyNote.WALK_THE_REST }.flatMap { it.rides }.map { it.line.shortName }.toSet()
        assertEquals(setOf("L3"), lines)
    }

    @Test
    fun `a transfer walk is never excluded`() {
        // the L1 + L2 trip uses a walk between C and D: excluding tram (not used here) keeps it whole
        val found = assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-14T07:55:00"), options = PlanOptions(modes = TransitMode.ALL - TransitMode.TRAM)))
        val trip = found.itineraries.first { it.rides.size == 2 }
        assertEquals(listOf("L1", "L2"), trip.rides.map { it.line.shortName })
        assertNotNull(trip.legs.filterIsInstance<ItineraryLeg.Walk>().firstOrNull { it.fromName == "Charlie" })
    }

    @Test
    fun `when the allowed modes leave nothing the answer says so`() {
        val none = service().plan(nearA, nearF, at("2026-10-14T07:55:00"), options = PlanOptions(modes = setOf(TransitMode.TRAM, TransitMode.OTHER)))
        assertEquals(TransitPlan.NoRouteWithModes, none)
        // the same trip without a filter has a route; and a place with no service is plain "no route"
        assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-14T07:55:00")))
        assertEquals(TransitPlan.NoRoute, service().plan(nearA, LatLon(41.5, -3.0), at("2026-10-14T08:00:00"), options = PlanOptions(modes = setOf(TransitMode.TRAM))))
    }

    @Test
    fun `the next departures keep respecting the allowed modes`() {
        // from Charlie to Foxtrot only the metro (L2) serves: three distinct departures when buses are excluded
        val onlyMetro = PlanOptions(modes = TransitMode.ALL - TransitMode.BUS)
        val found = assertIs<TransitPlan.Found>(service().plan(nearC, nearF, at("2026-10-14T08:00:00"), options = onlyMetro))
        assertEquals(3, found.itineraries.size)
        assertTrue(found.itineraries.all { it.rides.all { r -> r.line.shortName == "L2" } })
        assertEquals(3, found.itineraries.map { it.rides.single().boarding.departAt }.toSet().size)
        // and with metro excluded the same trip has nothing
        assertEquals(TransitPlan.NoRouteWithModes, service().plan(nearC, nearF, at("2026-10-14T08:00:00"), options = PlanOptions(modes = TransitMode.ALL - TransitMode.METRO)))
    }
}
