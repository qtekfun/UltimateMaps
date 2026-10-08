package com.qtekfun.ultimatemaps.core.transit.rt

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real fetcher against a local server: what is sent, what is refused, what is logged. */
class HttpRealTimeFetcherTest {
    private val requests = CopyOnWriteArrayList<Map<String, List<String>>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/ok") { ex ->
            requests += ex.requestHeaders.toMap()
            val body = """{"header":{},"entity":[]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/boom") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
        createContext("/big") { ex ->
            val body = ByteArray(200_000) { 'a'.code.toByte() }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    private fun policy(enabled: Boolean = true) =
        DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.TRANSIT_REALTIME, enabled)))

    @Test
    fun `a plain GET with a generic user agent, no cookies and no identifiers`() {
        val p = policy()
        val body = HttpRealTimeFetcher(p, allowInsecure = true).get("$base/ok")
        assertTrue(body.toString(Charsets.UTF_8).contains("entity"))
        val h = requests.single().mapKeys { it.key.lowercase() }
        assertEquals(listOf("UltimateMaps"), h["user-agent"])
        assertFalse("cookie" in h)
        assertFalse("authorization" in h)
        assertFalse("referer" in h)
        assertFalse("x-requested-with" in h)
        // the policy saw the host and the purpose, and logged them locally
        val rec = p.recentConnections().single()
        assertEquals("127.0.0.1", rec.host)
        assertEquals(ConnectionPurpose.TRANSIT_REALTIME, rec.purpose)
        assertTrue(rec.allowed)
    }

    @Test
    fun `offline mode and a disabled host stop it before any connection`() {
        val off = policy().also { it.offlineMode = true }
        assertEquals(RealTimeFailure.OFFLINE, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(off, true).get("$base/ok") }.failure)
        assertEquals(RealTimeFailure.NOT_ALLOWED, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(policy(false), true).get("$base/ok") }.failure)
        val unlisted = DefaultNetworkPolicy()
        assertEquals(RealTimeFailure.NOT_ALLOWED, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(unlisted, true).get("$base/ok") }.failure)
        assertTrue(requests.isEmpty(), "the server never saw a request")
    }

    @Test
    fun `plain http is refused unless a test allows it`() {
        assertEquals(RealTimeFailure.NOT_ALLOWED, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(policy()).get("$base/ok") }.failure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `server errors and oversized bodies are failures`() {
        assertEquals(RealTimeFailure.SERVER, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(policy(), true).get("$base/boom") }.failure)
        assertEquals(RealTimeFailure.TOO_LARGE, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(policy(), true, maxBytes = 1000).get("$base/big") }.failure)
    }

    @Test
    fun `an unreachable server is a network failure`() {
        server.stop(0)
        assertEquals(RealTimeFailure.NETWORK, assertFailsWith<RealTimeException> { HttpRealTimeFetcher(policy(), true).get("$base/ok") }.failure)
    }
}
