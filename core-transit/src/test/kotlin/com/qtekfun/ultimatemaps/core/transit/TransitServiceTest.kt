package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planner behind the UI: itinerary model, validity window, expired feeds. All on the small synthetic feed. */
class TransitServiceTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val nearA = LatLon(40.0001, -3.0)
    private val nearF = LatLon(40.0401, -3.0)

    private fun at(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()

    private fun service() = TransitService(Fixtures.index(), zone)

    @Test
    fun `itinerary carries lines, ordered stops with times and coordinates, and walking legs`() {
        val plan = service().plan(nearA, nearF, at("2026-10-14T07:55:00"))
        val found = assertIs<TransitPlan.Found>(plan)
        val it = found.itineraries.first()
        assertEquals(1, it.transfers)
        val rides = it.rides
        assertEquals(listOf("L1", "L2"), rides.map { r -> r.line.shortName })
        assertEquals(0xFFFF0000.toInt(), rides[0].line.color) // route_color from the feed
        assertEquals(0xFFFFFFFF.toInt(), rides[0].line.textColor)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), rides[0].stops.map { s -> s.name })
        assertEquals(2, rides[0].stopCount)
        assertEquals(at("2026-10-14T08:00:00").epochSecond, rides[0].departAt)
        assertEquals(at("2026-10-14T08:10:00").epochSecond, rides[0].arriveAt)
        assertEquals(40.01, rides[0].stops[1].point.lat, 1e-6)
        assertEquals(listOf("Delta", "Echo", "Foxtrot"), rides[1].stops.map { s -> s.name })
        assertEquals(at("2026-10-14T08:40:00").epochSecond, rides[1].arriveAt)
        assertNull(rides[0].shape) // the index has no shapes: the stop-to-stop line is drawn
        assertEquals(rides[0].stops.map { s -> s.point }, rides[0].path)
        // walking: origin -> Alpha, Charlie -> Delta, Foxtrot -> destination
        val walks = it.legs.filterIsInstance<ItineraryLeg.Walk>()
        assertEquals(3, walks.size)
        assertNull(walks.first().fromName)
        assertEquals("Alpha", walks.first().toName)
        assertEquals(nearA, walks.first().from)
        assertEquals("Charlie", walks[1].fromName)
        assertNull(walks.last().toName)
        assertEquals(nearF, walks.last().to)
        assertTrue(it.walkMeters > 0 && it.durationSec > 0)
        // legs are contiguous in time
        it.legs.zipWithNext().forEach { (a, b) -> assertTrue(a.arriveAt <= b.departAt) }
    }

    @Test
    fun `offers up to three itineraries sorted by arrival`() {
        val found = assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-14T07:55:00")))
        assertTrue(found.itineraries.count { it.note != JourneyNote.WALK_THE_REST } in 2..3)
        val main = found.itineraries.filter { it.note != JourneyNote.WALK_THE_REST }
        assertEquals(main.sortedBy { it.arriveAt }.map { it.arriveAt }, main.map { it.arriveAt })
        assertEquals(found.itineraries.size, found.itineraries.map { i -> i.rides.map { r -> r.boarding.departAt } }.toSet().size)
    }

    @Test
    fun `lines without a colour get a mode default and a readable text colour`() {
        // L3 has no route_color in the fixture
        val found = assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-14T07:55:00"), maxItineraries = 8))
        val l3 = found.itineraries.flatMap { it.rides }.firstOrNull { it.line.shortName == "L3" }
        assertNotNull(l3, "the slow direct line is one of the options")
        assertEquals(LineInfo.defaultColor(3), l3.line.color)
        assertEquals(LineInfo.contrastText(l3.line.color), l3.line.textColor)
    }

    @Test
    fun `validity window comes from the feed calendar`() {
        val s = service()
        assertEquals(LocalDate.parse("2026-10-01"), s.validFrom)
        assertEquals(LocalDate.parse("2026-10-31"), s.validTo)
        assertEquals(0, s.validity!!.unverifiedFeeds)
        assertEquals(listOf("test"), s.attributions)
    }

    @Test
    fun `an expired feed refuses to plan and says until when it was valid`() {
        val s = service()
        val plan = s.plan(nearA, nearF, at("2026-11-03T08:00:00"))
        assertEquals(TransitPlan.Expired(LocalDate.parse("2026-10-31")), plan)
        assertTrue(s.isExpiredOn(LocalDate.parse("2026-11-01")))
        assertTrue(!s.isExpiredOn(LocalDate.parse("2026-10-31")))
    }

    @Test
    fun `a day before the data starts is refused too`() {
        assertEquals(TransitPlan.NotYetValid(LocalDate.parse("2026-10-01")), service().plan(nearA, nearF, at("2026-09-20T08:00:00")))
    }

    @Test
    fun `last valid day still plans and far away places have no route`() {
        assertIs<TransitPlan.Found>(service().plan(nearA, nearF, at("2026-10-30T07:55:00")))
        assertEquals(TransitPlan.NoRoute, service().plan(nearA, LatLon(41.5, -3.0), at("2026-10-14T08:00:00")))
    }

    @Test
    fun `a feed built with its range ignored adds no bound but is counted as unverified`() {
        val feed = GtfsReader.read(MapGtfsSource(Fixtures.files()), GtfsReadOptions(ignoreCalendarRange = true))
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("f", "f", "x"), ignoredCalendarRange = true)
        val s = TransitService(b.build(), zone)
        assertNull(s.validity)
        assertNull(s.validTo)
        assertIs<TransitPlan.Found>(s.plan(nearA, nearF, at("2027-03-10T07:55:00")))
    }

    @Test
    fun `day start follows the gtfs noon-minus-twelve-hours rule across a clock change`() {
        // 2026-10-25 is the end of summer time in Madrid (25 hours long): 08:00 schedule time is 08:00 local.
        val feed = GtfsReader.read(MapGtfsSource(Fixtures.files()), GtfsReadOptions(ignoreCalendarRange = true))
        val b = TransitIndexBuilder()
        b.addFeed(feed, FeedOptions("f", "f", "x"), ignoredCalendarRange = true)
        val s = TransitService(b.build(), zone)
        // Sunday: only the weekend L1 trip at 10:00 runs, A -> C
        val found = assertIs<TransitPlan.Found>(s.plan(nearA, LatLon(40.0201, -3.0), at("2026-10-25T09:50:00")))
        val ride = found.itineraries.first().rides.first()
        assertEquals(at("2026-10-25T10:00:00").epochSecond, ride.departAt)
    }

    @Test
    fun `non positive duration trips are dropped when asked and counted`() {
        val extraTrips = "R1,WK,BAD,Nowhere\n"
        val extraTimes = "BAD,08:00:00,08:00:00,A,0\nBAD,08:00:00,08:00:00,B,1\n"
        val feed = GtfsReader.read(MapGtfsSource(Fixtures.files(extraTrips = extraTrips, extraStopTimes = extraTimes)))
        val kept = TransitIndexBuilder().also { it.addFeed(feed, FeedOptions("f", "f", "x")) }
        val dropping = TransitIndexBuilder().also { it.addFeed(feed, FeedOptions("f", "f", "x", dropNonPositiveDuration = true)) }
        assertEquals(0, kept.droppedTrips)
        assertEquals(1, dropping.droppedTrips)
        assertEquals(kept.build().tripCount - 1, dropping.build().tripCount)
    }
}
