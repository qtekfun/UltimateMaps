package com.qtekfun.ultimatemaps.core.transit.rt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealTimeRepositoryTest {
    private val trips = """{"header":{"timestamp":"100"},"entity":[{"id":"1","tripUpdate":{"trip":{"tripId":"T1"},"delay":120}}]}"""
    private val alerts = """{"header":{"timestamp":"100"},"entity":[{"id":"A","alert":{"descriptionText":{"translation":[{"text":"Aviso","language":"es"}]}}}]}"""

    private class Fake(val bodies: MutableMap<String, Any>) : RealTimeFetcher {
        val calls = ArrayList<String>()
        override fun get(url: String): ByteArray {
            calls += url
            val b = bodies[url] ?: throw RealTimeException(RealTimeFailure.NETWORK, "no route")
            if (b is RealTimeException) throw b
            return (b as String).toByteArray()
        }
    }

    private fun fake() = Fake(mutableMapOf(RenfeFeeds.TRIP_UPDATES to trips, RenfeFeeds.ALERTS to alerts))

    private var now = 1_000_000L
    private fun repo(f: RealTimeFetcher, vehicles: Boolean = false) = RealTimeRepository(f, { now }, withVehicles = vehicles)

    @Test
    fun `nothing is fetched until refresh is called and current is empty`() {
        val f = fake()
        val r = repo(f)
        assertNull(r.current())
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun `a refresh fetches trip updates and alerts only and caches them`() {
        val f = fake()
        val r = repo(f)
        assertEquals(RefreshResult.Updated, r.refresh())
        assertEquals(listOf(RenfeFeeds.TRIP_UPDATES, RenfeFeeds.ALERTS), f.calls)
        val s = assertNotNull(r.current())
        assertEquals(120, s.tripUpdates.getValue("T1").delaySec)
        assertEquals("Aviso", s.alerts.single().text)
        assertEquals(100L, s.feedTimestampSec)
    }

    @Test
    fun `vehicle positions are fetched only when asked for`() {
        val f = fake().also { it.bodies[RenfeFeeds.VEHICLES] = """{"header":{},"entity":[{"id":"v","vehicle":{"trip":{"tripId":"T1"},"stopId":"S","position":{"latitude":1.0,"longitude":2.0}}}]}""" }
        val r = repo(f, vehicles = true)
        r.refresh()
        assertTrue(RenfeFeeds.VEHICLES in f.calls)
        assertEquals("S", r.current()!!.vehicles.getValue("T1").stopId)
    }

    @Test
    fun `it never asks more often than every 30 seconds however often it is called`() {
        val f = fake()
        val r = repo(f)
        r.refresh()
        val calls = f.calls.size
        repeat(50) {
            now += 500
            assertEquals(RefreshResult.Skipped, r.refresh())
        }
        assertEquals(calls, f.calls.size)
        now += 30_000
        assertEquals(RefreshResult.Updated, r.refresh())
        assertEquals(calls * 2, f.calls.size)
    }

    @Test
    fun `data older than three minutes is hidden`() {
        val r = repo(fake())
        r.refresh()
        now += 3 * 60_000L
        assertNotNull(r.current())
        now += 1
        assertNull(r.current())
    }

    @Test
    fun `offline or a server error degrade silently and keep the previous data`() {
        val f = fake()
        val r = repo(f)
        r.refresh()
        f.bodies[RenfeFeeds.TRIP_UPDATES] = RealTimeException(RealTimeFailure.OFFLINE, "offline mode")
        now += 31_000
        assertEquals(RefreshResult.Failed(RealTimeFailure.OFFLINE), r.refresh())
        assertEquals(120, r.current()!!.tripUpdates.getValue("T1").delaySec)
        assertEquals(2, f.calls.count { it == RenfeFeeds.TRIP_UPDATES })
        assertEquals(1, f.calls.count { it == RenfeFeeds.ALERTS }) // alerts were not asked after the failure
        f.bodies[RenfeFeeds.TRIP_UPDATES] = RealTimeException(RealTimeFailure.SERVER, "HTTP 503")
        now += 61_000
        assertEquals(RefreshResult.Failed(RealTimeFailure.SERVER), r.refresh())
    }

    @Test
    fun `failures back off instead of retrying every interval`() {
        val f = fake().also { it.bodies[RenfeFeeds.TRIP_UPDATES] = RealTimeException(RealTimeFailure.TIMEOUT, "t") }
        val r = repo(f)
        assertIs<RefreshResult.Failed>(r.refresh())
        now += 31_000
        assertEquals(RefreshResult.Skipped, r.refresh()) // the wait doubled to 60 s
        now += 30_000
        assertIs<RefreshResult.Failed>(r.refresh())
        assertEquals(2, f.calls.size)
    }

    @Test
    fun `a malformed body is an invalid answer and a good one afterwards recovers`() {
        val f = fake().also { it.bodies[RenfeFeeds.TRIP_UPDATES] = "<html>" }
        val r = repo(f)
        assertEquals(RefreshResult.Failed(RealTimeFailure.INVALID), r.refresh())
        assertNull(r.current())
        f.bodies[RenfeFeeds.TRIP_UPDATES] = trips
        now += 61_000
        assertEquals(RefreshResult.Updated, r.refresh())
        assertNotNull(r.current())
    }

    @Test
    fun `alerts failing do not fail the refresh and the old alerts stay`() {
        val f = fake()
        val r = repo(f)
        r.refresh()
        f.bodies[RenfeFeeds.ALERTS] = RealTimeException(RealTimeFailure.SERVER, "x")
        now += 31_000
        assertEquals(RefreshResult.Updated, r.refresh())
        assertEquals("Aviso", r.current()!!.alerts.single().text)
    }

    @Test
    fun `clear forgets the data and the rate limit`() {
        val f = fake()
        val r = repo(f)
        r.refresh()
        r.clear()
        assertNull(r.current())
        assertEquals(RefreshResult.Updated, r.refresh())
    }
}
