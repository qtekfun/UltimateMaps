package com.qtekfun.ultimatemaps.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.ConnectionRecord
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.net.NetworkDecision
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import com.qtekfun.ultimatemaps.core.transit.rt.HttpRealTimeFetcher
import com.qtekfun.ultimatemaps.core.transit.rt.RealTimeFetcher
import com.qtekfun.ultimatemaps.core.transit.rt.RealTimeRepository
import com.qtekfun.ultimatemaps.core.transit.rt.RenfeFeeds
import com.qtekfun.ultimatemaps.transit.follow.InMemoryTransitTripSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opt-in wiring: with the switch off there is NO network access at all; with it on the host is listed and logged. */
class CercaniasRealTimeTest {
    /** Fails the test on ANY call: nothing may even ask whether a connection is allowed. */
    private class ForbiddenPolicy : NetworkPolicy {
        var calls = 0
        override var offlineMode: Boolean = false
        override fun authorize(host: String, purpose: ConnectionPurpose): NetworkDecision {
            calls++
            throw AssertionError("network access attempted: $host / $purpose")
        }
        override fun possibleConnections(): List<AllowedEndpoint> = emptyList()
        override fun setEndpointEnabled(host: String, purpose: ConnectionPurpose, enabled: Boolean) = Unit
        override fun recentConnections(): List<ConnectionRecord> = emptyList()
        override fun clearLog() = Unit
    }

    private val now = 1_800_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun ride() = ItineraryLeg.Ride(
        LineInfo("C4", "Line", 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 2), "Parla",
        listOf("S1", "S2").mapIndexed { i, id -> ItineraryStop(id, LatLon(40.0 + i, -3.0), now + 600 + i * 300L, now + 600 + i * 300L, id) },
        tripId = "TRIP1",
    )

    private class CountingFetcher(val now: Long) : RealTimeFetcher {
        val urls = mutableListOf<String>()
        override fun get(url: String): ByteArray {
            urls += url
            return if (url == RenfeFeeds.ALERTS) """{"header":{},"entity":[]}""".toByteArray()
            else """{"header":{"timestamp":"$now"},"entity":[{"id":"x","tripUpdate":{"trip":{"tripId":"TRIP1"},"stopTimeUpdate":[{"arrival":{"delay":240,"time":"${now + 840}"},"stopId":"S1"}],"delay":240}}]}""".toByteArray()
        }
    }

    @Test
    fun withTheSwitchOffNothingTouchesTheNetworkOrThePolicy() {
        val policy = ForbiddenPolicy()
        val added = mutableListOf<AllowedEndpoint>()
        val settings = InMemoryTransitTripSettings()
        val rt = CercaniasRealTime(
            settings, { added += it }, { }, RealTimeRepository(HttpRealTimeFetcher(policy)), scope, io = Dispatchers.Unconfined, clockSec = { now },
        )
        rt.start()
        rt.refresh()
        rt.acquire()
        assertNull(rt.forRide(ride()))
        assertTrue(rt.supports(ride()))
        assertFalse(rt.enabled)
        rt.release()
        assertEquals(0, policy.calls)
        assertTrue(added.isEmpty(), "the Renfe host is not even listed while the switch is off")
    }

    @Test
    fun turningItOnListsTheHostAndFetchesThroughThePolicyAndTurningItOffForgetsEverything() {
        val policy = DefaultNetworkPolicy()
        val settings = InMemoryTransitTripSettings()
        val fetcher = CountingFetcher(now)
        val rt = CercaniasRealTime(
            settings, policy::addEndpoint, policy::removeEndpoint, RealTimeRepository(fetcher), scope, io = Dispatchers.Unconfined, clockSec = { now },
        )
        rt.start()
        assertTrue(policy.possibleConnections().isEmpty())
        settings.setRealTimeEnabled(true)
        val listed = policy.possibleConnections().single()
        assertEquals(RenfeFeeds.HOST, listed.host)
        assertEquals(ConnectionPurpose.TRANSIT_REALTIME, listed.purpose)
        assertTrue(listed.enabled)
        assertTrue(rt.enabled)

        val before = rt.version
        rt.refresh()
        assertEquals(listOf(RenfeFeeds.TRIP_UPDATES, RenfeFeeds.ALERTS), fetcher.urls)
        assertTrue(rt.version > before)
        assertEquals(240, rt.forRide(ride())?.delaySec)

        settings.setRealTimeEnabled(false)
        assertTrue(policy.possibleConnections().isEmpty())
        assertNull(rt.forRide(ride()), "the data is dropped with the switch")
        fetcher.urls.clear()
        rt.refresh()
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test
    fun refreshingAgainAndAgainStillAsksOncePer30Seconds() {
        val policy = DefaultNetworkPolicy()
        val settings = InMemoryTransitTripSettings(realTime = true)
        val fetcher = CountingFetcher(now)
        val rt = CercaniasRealTime(
            settings, policy::addEndpoint, policy::removeEndpoint, RealTimeRepository(fetcher), scope, io = Dispatchers.Unconfined, clockSec = { now },
        )
        rt.start()
        repeat(20) { rt.refresh() }
        assertEquals(1, fetcher.urls.count { it == RenfeFeeds.TRIP_UPDATES })
    }

    @Test
    fun followingATripFetchesAtOnceAndStopsWhenTheTripEnds() {
        val policy = DefaultNetworkPolicy()
        val settings = InMemoryTransitTripSettings(realTime = true)
        val fetcher = CountingFetcher(now)
        val rt = CercaniasRealTime(
            settings, policy::addEndpoint, policy::removeEndpoint, RealTimeRepository(fetcher), scope, io = Dispatchers.Unconfined,
            pollMillis = 60_000L, clockSec = { now },
        )
        rt.start()
        rt.acquire()
        assertNotNull(rt.forRide(ride()))
        assertEquals(1, fetcher.urls.count { it == RenfeFeeds.TRIP_UPDATES })
        rt.release()
        rt.acquire()
        rt.release()
        assertEquals(1, fetcher.urls.count { it == RenfeFeeds.TRIP_UPDATES }, "the minimum interval holds across trips too")
    }

    @Test
    fun aRideWithoutAFeedIdIsNotSupportedSoItIsNeverLabelledRealTime() {
        val settings = InMemoryTransitTripSettings(realTime = true)
        val rt = CercaniasRealTime(settings, { }, { }, RealTimeRepository(CountingFetcher(now)), scope, io = Dispatchers.Unconfined, clockSec = { now })
        rt.start()
        assertFalse(rt.supports(ride().copy(tripId = null)))
        assertNull(rt.forRide(ride().copy(tripId = null)))
    }
}
