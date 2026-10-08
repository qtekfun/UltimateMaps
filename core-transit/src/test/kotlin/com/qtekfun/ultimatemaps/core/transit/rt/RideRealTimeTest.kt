package com.qtekfun.ultimatemaps.core.transit.rt

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideRealTimeTest {
    private val now = 1_800_000_000L
    private val line = LineInfo("C4", "Parla - Alcobendas", 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 2)

    /** Three stops 5 minutes apart; the train is due at S1 at now + 600. */
    private fun ride(tripId: String? = "TRIP1", feedIds: Boolean = true, shift: Long = 0L) = ItineraryLeg.Ride(
        line, "Parla",
        listOf("S1", "S2", "S3").mapIndexed { i, id ->
            val t = now + shift + 600 + i * 300L
            ItineraryStop("Stop $id", LatLon(40.0 + i * 0.01, -3.0), t, t, id.takeIf { feedIds })
        },
        tripId = tripId,
    )

    private fun snap(
        trips: List<RtTripUpdate> = emptyList(),
        alerts: List<RtAlert> = emptyList(),
        fetchedAt: Long = 0L,
    ) = RtSnapshot(trips.associateBy { it.tripId }, alerts, emptyMap(), now, fetchedAt)

    private fun update(delay: Int?, stopDelay: Int? = delay, scheduled: Long = now + 600, stop: String = "S1", cancelled: Boolean = false, skipped: List<String> = emptyList()) =
        RtTripUpdate(
            "TRIP1", cancelled, delay,
            listOfNotNull(stopDelay?.let { RtStopUpdate(stop, arrivalTime = scheduled + it, arrivalDelaySec = it) }) + skipped.map { RtStopUpdate(it, skipped = true) },
        )

    @Test
    fun `a delayed train gives its delay`() {
        val rt = assertNotNull(RideMatcher.match(ride(), snap(listOf(update(240))), now))
        assertEquals(240, rt.delaySec)
        assertFalse(rt.cancelled)
        assertTrue(rt.trainFound)
    }

    @Test
    fun `an early train gives a negative delay and zero is a real answer`() {
        assertEquals(-120, RideMatcher.match(ride(), snap(listOf(update(-120))), now)!!.delaySec)
        val onTime = RideMatcher.match(ride(), snap(listOf(update(0))), now)!!
        assertEquals(0, onTime.delaySec)
        assertTrue(onTime.trainFound)
    }

    @Test
    fun `without a stop update the trip level delay is used`() {
        val rt = RideMatcher.match(ride(), snap(listOf(update(delay = 300, stopDelay = null))), now)
        assertEquals(300, rt!!.delaySec)
    }

    @Test
    fun `a cancelled trip is reported`() {
        val rt = RideMatcher.match(ride(), snap(listOf(RtTripUpdate("TRIP1", true, null, emptyList()))), now)
        assertTrue(rt!!.cancelled)
        assertNull(rt.delaySec)
    }

    @Test
    fun `skipped boarding or alighting stops are named`() {
        val rt = RideMatcher.match(ride(), snap(listOf(update(60, skipped = listOf("S3", "S2")))), now)
        assertEquals(listOf("Stop S3"), rt!!.skippedStops) // S2 is in the middle: not worth saying
    }

    @Test
    fun `a train that is not in the feed gives nothing, not on time`() {
        assertNull(RideMatcher.match(ride(), snap(), now))
        assertNull(RideMatcher.match(ride(tripId = "OTHER"), snap(listOf(update(60))), now))
    }

    @Test
    fun `a ride without a trip id (an older index) is never matched`() {
        assertNull(RideMatcher.match(ride(tripId = null), snap(listOf(update(60))), now))
    }

    @Test
    fun `the same trip id on another day is ignored`() {
        // the feed's schedule for the trip is today's; the ride is planned for tomorrow at the same time
        val tomorrow = ride(shift = 86_400L)
        assertNull(RideMatcher.match(tomorrow, snap(listOf(update(60))), now))
        // and a ride planned for later today whose times the feed disagrees with
        val disagreeing = ride(shift = 3_000L)
        assertNull(RideMatcher.match(disagreeing, snap(listOf(update(60))), now))
    }

    @Test
    fun `rides far from now are not matched at all`() {
        assertNull(RideMatcher.match(ride(shift = 5 * 3600L), snap(listOf(update(60, scheduled = now + 600 + 5 * 3600L))), now))
        assertNull(RideMatcher.match(ride(shift = -4 * 3600L), snap(listOf(update(60, scheduled = now + 600 - 4 * 3600L))), now))
    }

    @Test
    fun `alerts are picked by line, stop or trip and only while active`() {
        val byLine = RtAlert("1", listOf("10T0011C4"), emptyList(), emptyList(), now - 100, null, "line alert")
        val otherLine = RtAlert("2", listOf("10T0011C3"), emptyList(), emptyList(), now - 100, null, "other line")
        val variant = RtAlert("3", listOf("10T0014C4a"), emptyList(), emptyList(), now - 100, null, "variant line")
        val byStop = RtAlert("4", emptyList(), listOf("S2"), emptyList(), null, null, "stop alert")
        val byTrip = RtAlert("5", emptyList(), emptyList(), listOf("TRIP1"), null, null, "trip alert")
        val expired = RtAlert("6", listOf("10T0011C4"), emptyList(), emptyList(), now - 1000, now - 10, "old alert")
        val future = RtAlert("7", listOf("10T0011C4"), emptyList(), emptyList(), now + 10, null, "future alert")
        val rt = RideMatcher.match(ride(), snap(listOf(update(0)), listOf(byLine, otherLine, variant, byStop, byTrip, expired, future)), now)!!
        assertEquals(listOf("line alert", "stop alert", "trip alert"), rt.alerts)
    }

    @Test
    fun `alerts alone are enough to show something, and at most three`() {
        val many = (1..6).map { RtAlert("$it", listOf("10T0011C4"), emptyList(), emptyList(), null, null, "alert $it") }
        val rt = RideMatcher.match(ride(), snap(alerts = many), now)!!
        assertNull(rt.delaySec)
        assertFalse(rt.trainFound)
        assertEquals(3, rt.alerts.size)
    }

    @Test
    fun `route ids are reduced to the line name`() {
        assertEquals("C4a", RideMatcher.routeLine("10T0014C4a"))
        assertEquals("R2N", RideMatcher.routeLine("40T0007R2N"))
        assertEquals("", RideMatcher.routeLine("something"))
    }

    // ------------------------------------------------------------------------ the app-level provider

    private inner class CountingFetcher : RealTimeFetcher {
        var calls = 0
        override fun get(url: String): ByteArray {
            calls++
            return when (url) {
                RenfeFeeds.ALERTS -> """{"header":{},"entity":[]}"""
                else -> """{"header":{"timestamp":"1800000000"},"entity":[{"id":"x","tripUpdate":{"trip":{"tripId":"TRIP1"},"stopTimeUpdate":[{"arrival":{"delay":180,"time":"${now + 780}"},"stopId":"S1"}],"delay":180}}]}"""
            }.toByteArray()
        }
    }

    @Test
    fun `with the switch off the provider answers nothing and never fetches`() {
        val fetcher = CountingFetcher()
        var on = false
        val provider = RenfeRideRealTime(RealTimeRepository(fetcher, { 1000L }), { on }, { now })
        assertNull(provider.refreshIfEnabled())
        assertNull(provider.forRide(ride()))
        assertEquals(0, fetcher.calls)
        on = true
        assertEquals(RefreshResult.Updated, provider.refreshIfEnabled())
        assertEquals(180, provider.forRide(ride())!!.delaySec)
        on = false
        assertNull(provider.forRide(ride()), "switching off hides the data at once")
    }

    @Test
    fun `the provider reads memory only`() {
        val fetcher = CountingFetcher()
        val provider = RenfeRideRealTime(RealTimeRepository(fetcher, { 1000L }), { true }, { now })
        repeat(20) { assertNull(provider.forRide(ride())) }
        assertEquals(0, fetcher.calls)
    }
}
