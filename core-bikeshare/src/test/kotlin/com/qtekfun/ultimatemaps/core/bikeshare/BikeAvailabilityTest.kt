package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.net.NetworkDecision
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BikeAvailabilityTest {
    private class Server(val handler: (hit: Int) -> Pair<Int, String>) {
        val hits = AtomicInteger()
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, List<String>>>>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/") { ex ->
                val n = hits.incrementAndGet()
                requests += ex.requestURI.toString() to ex.requestHeaders.toMap()
                val (code, body) = handler(n)
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
                ex.close()
            }
            s.start()
        }
        val base get() = "http://127.0.0.1:${http.address.port}"
        fun stop() = http.stop(0)
    }

    /** A policy that fails the test on ANY call: used where nothing may even ask. */
    private class ExplodingPolicy : NetworkPolicy {
        override var offlineMode: Boolean = false
        override fun authorize(host: String, purpose: ConnectionPurpose): NetworkDecision = throw AssertionError("policy was asked about $host")
        override fun possibleConnections(): List<AllowedEndpoint> = throw AssertionError("policy listed")
        override fun setEndpointEnabled(host: String, purpose: ConnectionPurpose, enabled: Boolean) = throw AssertionError("policy changed")
        override fun recentConnections() = throw AssertionError("policy log")
        override fun clearLog() = throw AssertionError("policy log")
    }

    private val system = BikeSystem("bicing", "Bicing (Barcelona)", "Bicing: Ajuntament de Barcelona, CC BY 4.0")
    private fun station(id: String = "1") = BikeStation("0:$id", system, id, "Gran Via", LatLon(41.4, 2.18), 45)

    private val feed = """{"last_updated":"2026-10-08T14:59:16Z","ttl":0,"version":"3.0","data":{"stations":[
        {"station_id":"1","num_vehicles_available":2,"num_docks_available":43,"is_installed":true,"is_renting":true,"last_reported":"2026-10-08T14:57:08.249Z"},
        {"station_id":"2","num_vehicles_available":5,"num_docks_available":20},
        {"station_id":"3","num_vehicles_available":1,"num_docks_available":1,"is_installed":false},
        {"station_id":"4","num_bikes_available":7,"num_docks_available":3,"last_reported":1791471000}
    ]}}"""

    private class Env(
        val srv: Server?, settings: BikeShareSettings, val clockMillis: LongArray = longArrayOf(1_791_471_600_000L),
        val policy: NetworkPolicy = DefaultNetworkPolicy(), val added: MutableList<AllowedEndpoint> = mutableListOf(), val removed: MutableList<String> = mutableListOf(),
        urlFor: (String) -> String? = { srv?.let { s -> s.base + "/station_status" } },
    ) {
        val store = InMemoryBikeShareSettingsStore(settings)
        val repo = BikeAvailabilityRepository(
            store.settings, policy,
            addEndpoint = { e -> added += e; (policy as? DefaultNetworkPolicy)?.addEndpoint(e) },
            removeEndpoint = { h -> removed += h; (policy as? DefaultNetworkPolicy)?.removeEndpoint(h) },
            http = BoundedHttp(policy, ConnectionPurpose.BIKE_AVAILABILITY, allowInsecure = true, maxBytes = BikeAvailabilityRepository.MAX_BYTES, userAgent = BikeAvailabilityRepository.GENERIC_USER_AGENT),
            feedUrl = urlFor, clock = { clockMillis[0] }, io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private val on = BikeShareSettings(enabled = true, liveAvailability = true)

    @Test fun nothingIsFetchedAndThePolicyIsNeverAskedWhileTheSwitchesAreOff() = runBlocking {
        val srv = Server { 200 to feed }
        try {
            for (s in listOf(BikeShareSettings(), BikeShareSettings(enabled = true), BikeShareSettings(liveAvailability = true))) {
                val env = Env(srv, s, policy = ExplodingPolicy())
                assertNull(env.repo.availability(station()))
            }
            assertEquals(0, srv.hits.get(), "off by default: zero connections")
        } finally { srv.stop() }
    }

    @Test fun parsesTheFeedAndTheRequestIsAPlainGetWithNoIdentifyingData() = runBlocking {
        val srv = Server { 200 to feed }
        try {
            val env = Env(srv, on)
            val a = assertNotNull(env.repo.availability(station("1")))
            assertEquals(2, a.bikes)
            assertEquals(43, a.freeDocks)
            assertEquals(1_791_471_428_249L, a.updatedAtMillis, "the station's own last_reported")
            assertEquals(1, srv.hits.get())
            val (uri, headers) = srv.requests.single()
            assertEquals("/station_status", uri, "no query, no station id, no position")
            val ua = headers.entries.first { it.key.equals("User-Agent", true) }.value.single()
            assertEquals(BikeAvailabilityRepository.GENERIC_USER_AGENT, ua)
            assertTrue(BikeAvailabilityRepository.userAgentIsGeneric(ua))
            assertTrue(headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) || it.equals("Referer", true) })
            // feed time as a fallback, 2.x names and epoch seconds, uninstalled stations skipped
            assertEquals(1_791_471_556_000L, env.repo.availability(station("2"))!!.updatedAtMillis, "the feed's last_updated")
            val v2 = env.repo.availability(station("4"))!!
            assertEquals(7, v2.bikes)
            assertEquals(1_791_471_000_000L, v2.updatedAtMillis)
            assertNull(env.repo.availability(station("3")), "not installed")
            assertNull(env.repo.availability(station("99")), "unknown station")
            assertEquals(1, srv.hits.get(), "all answered from the one download")
        } finally { srv.stop() }
    }

    @Test fun atMostOneRequestPerThirtySecondsPerSystem() = runBlocking {
        val srv = Server { n -> 200 to feed.replace("\"num_vehicles_available\":2", "\"num_vehicles_available\":${n + 10}") }
        try {
            val env = Env(srv, on)
            assertEquals(11, env.repo.availability(station())!!.bikes)
            env.clockMillis[0] += 29_000
            assertEquals(11, env.repo.availability(station())!!.bikes)
            assertEquals(1, srv.hits.get(), "inside the window: from memory")
            env.clockMillis[0] += 2_000
            assertEquals(12, env.repo.availability(station())!!.bikes)
            assertEquals(2, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun aFailureIsSilentAndAlsoRespectsTheWindow() = runBlocking {
        val srv = Server { n -> if (n == 1) 503 to "" else 200 to feed }
        try {
            val env = Env(srv, on)
            assertNull(env.repo.availability(station()))
            env.clockMillis[0] += 5_000
            assertNull(env.repo.availability(station()))
            assertEquals(1, srv.hits.get(), "a failed attempt is not retried within 30 s")
            env.clockMillis[0] += 30_000
            assertEquals(2, env.repo.availability(station())!!.bikes)
            assertEquals(2, srv.hits.get())
        } finally { srv.stop() }
        val junk = Server { 200 to "<html>not gbfs</html>" }
        try { assertNull(Env(junk, on).repo.availability(station())) } finally { junk.stop() }
        val empty = Server { 200 to """{"data":{"stations":"x"}}""" }
        try { assertNull(Env(empty, on).repo.availability(station())) } finally { empty.stop() }
    }

    @Test fun staleDataIsNotShownAfterTenMinutes() = runBlocking {
        val srv = Server { n -> if (n == 1) 200 to feed else 500 to "" }
        try {
            val env = Env(srv, on)
            assertNotNull(env.repo.availability(station()))
            env.clockMillis[0] += 60_000
            assertNotNull(env.repo.availability(station()), "a minute old and the refresh failed: still shown with its own time")
            env.clockMillis[0] += 11 * 60_000
            assertNull(env.repo.availability(station()))
        } finally { srv.stop() }
    }

    @Test fun offlineModeAndDenialsStopBeforeAnyConnection() = runBlocking {
        val srv = Server { 200 to feed }
        try {
            val env = Env(srv, on)
            env.policy.offlineMode = true
            assertNull(env.repo.availability(station()))
            assertEquals(0, srv.hits.get())
            env.policy.offlineMode = false
            env.clockMillis[0] += 31_000
            assertNotNull(env.repo.availability(station()))
            // a policy that refuses the host: nothing is sent, no crash
            val denying = object : NetworkPolicy by DefaultNetworkPolicy() {
                override fun authorize(host: String, purpose: ConnectionPurpose) = NetworkDecision.Denied(DenyReason.NOT_WHITELISTED)
            }
            val hits = srv.hits.get()
            assertNull(Env(srv, on, policy = denying).repo.availability(station()))
            assertEquals(hits, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun theHostIsListedUnderItsOwnPurposeOnlyWhileTheLiveSwitchIsOn() = runBlocking {
        val srv = Server { 200 to feed }
        try {
            val env = Env(srv, on)
            env.repo.start()
            assertTrue(env.policy.possibleConnections().none { it.purpose == ConnectionPurpose.BIKE_AVAILABILITY }, "not listed before use")
            env.repo.availability(station())
            val listed = env.policy.possibleConnections().single { it.purpose == ConnectionPurpose.BIKE_AVAILABILITY }
            assertEquals("127.0.0.1", listed.host)
            assertTrue(listed.enabled)
            assertEquals(1, env.added.size)
            env.clockMillis[0] += 31_000
            env.repo.availability(station())
            assertEquals(1, env.added.size, "registered once")
            env.store.update { it.copy(liveAvailability = false) }
            assertEquals(listOf("127.0.0.1"), env.removed, "removed when the switch goes off")
            assertTrue(env.policy.possibleConnections().none { it.purpose == ConnectionPurpose.BIKE_AVAILABILITY })
            val before = srv.hits.get()
            assertNull(env.repo.availability(station()))
            assertEquals(before, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun systemsWithoutAKnownFeedNeverGetARequest() = runBlocking {
        val srv = Server { 200 to feed }
        try {
            val env = Env(srv, on, policy = ExplodingPolicy(), urlFor = { null })
            assertNull(env.repo.availability(station()))
            assertEquals(0, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun compiledInFeedsAreHttpsOnKnownHostsAndTheAgentIsGeneric() {
        for (id in listOf("bicing", "bicimad")) {
            val url = BikeShareLiveFeeds.statusUrl(id)!!
            assertTrue(url.startsWith("https://") && url.endsWith("/station_status"), url)
        }
        assertNull(BikeShareLiveFeeds.statusUrl("evil"))
        assertEquals(listOf("barcelona.publicbikesystem.net", "madrid.publicbikesystem.net"), BikeShareLiveFeeds.hosts())
        assertTrue(BikeAvailabilityRepository.userAgentIsGeneric(BikeAvailabilityRepository.GENERIC_USER_AGENT))
        assertFalse(BikeAvailabilityRepository.userAgentIsGeneric("UltimateMaps/1.0"))
    }
}
