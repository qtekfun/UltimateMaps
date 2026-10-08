package com.qtekfun.ultimatemaps.core.transit.rt

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.Fixtures
import com.qtekfun.ultimatemaps.core.transit.FeedOptions
import com.qtekfun.ultimatemaps.core.transit.GtfsReader
import com.qtekfun.ultimatemaps.core.transit.MapGtfsSource
import com.qtekfun.ultimatemaps.core.transit.TransitIndex
import com.qtekfun.ultimatemaps.core.transit.TransitIndexBuilder
import com.qtekfun.ultimatemaps.core.transit.TransitIndexIo
import com.qtekfun.ultimatemaps.core.transit.TransitPlan
import com.qtekfun.ultimatemaps.core.transit.TransitService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed ids kept in the index: what lets a ride be matched to a GTFS-RT trip update. */
class IndexFeedIdsTest {
    private val zone = ZoneId.of("Europe/Madrid")

    private fun build(keepIds: Boolean): TransitIndex {
        val feed = GtfsReader.read(MapGtfsSource(Fixtures.files()))
        return TransitIndexBuilder().also { it.addFeed(feed, FeedOptions("f", "renfe", "x", keepIds = keepIds)) }.build()
    }

    private fun roundTrip(i: TransitIndex): TransitIndex {
        val out = ByteArrayOutputStream()
        TransitIndexIo.write(i, out)
        return TransitIndexIo.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `stop and trip ids are kept only when asked and survive the file format`() {
        val with = build(true)
        assertEquals(with.stopCount, with.stopExtId!!.size)
        assertEquals(with.tripCount, with.tripExtId!!.size)
        assertTrue("Alpha" in with.stopName && with.stopExtId!![with.stopName.indexOf("Alpha")] == "A")
        assertTrue(with.tripExtId!!.all { it.isNotEmpty() })
        val back = roundTrip(with)
        assertEquals(with.stopExtId!!.toList(), back.stopExtId!!.toList())
        assertEquals(with.tripExtId!!.toList(), back.tripExtId!!.toList())

        val without = build(false)
        assertNull(without.stopExtId)
        assertNull(without.tripExtId)
    }

    @Test
    fun `a file without the optional section (an older index) still loads, with no ids`() {
        val back = roundTrip(build(false))
        assertNull(back.stopExtId)
        assertNull(back.tripExtId)
        assertEquals(build(false).describe(), back.describe())
    }

    @Test
    fun `ids stay aligned with the trips after the index sorts them`() {
        // L1 trips leave A at 08:00 + 10 min * k and are named L1_k in the feed
        val i = roundTrip(build(true))
        val svc = TransitService(i, zone)
        val at = LocalDateTime.parse("2026-10-14T08:25:00").atZone(zone).toInstant()
        val plan = svc.plan(LatLon(40.0001, -3.0), LatLon(40.0201, -3.0), at) as TransitPlan.Found
        val ride = plan.itineraries.first().rides.first()
        assertEquals("L1_3", ride.tripId) // 08:30 is the fourth departure
        assertEquals(listOf("A", "B", "C"), ride.stops.map { it.feedId })
    }

    @Test
    fun `rides of an index without ids carry none`() {
        val svc = TransitService(build(false), zone)
        val at = LocalDateTime.parse("2026-10-14T08:25:00").atZone(zone).toInstant()
        val ride = (svc.plan(LatLon(40.0001, -3.0), LatLon(40.0201, -3.0), at) as TransitPlan.Found).itineraries.first().rides.first()
        assertNull(ride.tripId)
        assertTrue(ride.stops.all { it.feedId == null })
    }

    @Test
    fun `the real Madrid index, when present, only keeps ids if it was rebuilt with them`() {
        val f = File(System.getProperty("user.home"), "mapas-data/transit/out/transit-madrid.umti")
        if (!f.isFile) return
        val i = f.inputStream().use { TransitIndexIo.read(it) }
        // Not an assertion about the file's age: only that whichever it is loads and is consistent.
        i.tripExtId?.let { assertEquals(i.tripCount, it.size) }
        i.stopExtId?.let { assertEquals(i.stopCount, it.size) }
        assertNotNull(i.describe())
    }
}
