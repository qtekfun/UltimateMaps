package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.traffic.TileFailure
import com.qtekfun.ultimatemaps.core.traffic.TileFetchException
import com.qtekfun.ultimatemaps.core.traffic.TileId
import com.qtekfun.ultimatemaps.core.traffic.TomTomEndpoints
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The real tile fetch against a loopback server: what leaves the phone (the key in a header, not in the URL) and how errors map. */
class TomTomFlowFetchTest {
    private val seen = mutableListOf<Map<String, String>>()
    private var status = 200
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            seen += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() } + ("uri" to ex.requestURI.toString())
            val body = byteArrayOf(1, 2, 3)
            ex.sendResponseHeaders(status, if (status == 200) body.size.toLong() else -1L)
            if (status == 200) ex.responseBody.use { it.write(body) } else ex.close()
        }
        start()
    }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val tile = TileId(10, 501, 386)

    @After fun stop() = server.stop(0)

    private fun policy(offline: Boolean = false) =
        DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.TRAFFIC_FLOW, true)), offlineMode = offline)

    private fun fetch(offline: Boolean = false) =
        TomTomFlowFetch.over(policy(offline), "ua", allowInsecure = true) { t -> "$base/maps/orbis/traffic/flow/vector/tile/${t.z}/${t.x}/${t.y}?apiVersion=2" }

    @Test fun theKeyTravelsInAHeaderAndNeverInTheUrl() {
        assertContentEquals(byteArrayOf(1, 2, 3), fetch().get(tile, "my-secret-key-0123456789"))
        val h = seen.single()
        assertEquals("my-secret-key-0123456789", h[TomTomEndpoints.KEY_HEADER.lowercase()])
        assertEquals("/maps/orbis/traffic/flow/vector/tile/10/501/386?apiVersion=2", h["uri"])
        assertTrue("my-secret" !in h["uri"].orEmpty())
        assertTrue(h.keys.none { it == "cookie" || it == "authorization" || it == "referer" }, h.keys.toString())
    }

    @Test fun theOfficialUrlIsTheOrbisFlowTile() {
        assertEquals("https://api.tomtom.com/maps/orbis/traffic/flow/vector/tile/10/501/386?apiVersion=2", TomTomEndpoints.tileUrl(tile))
    }

    @Test fun statusesAndOfflineModeMapToTheirReasons() {
        for ((code, reason) in listOf(403 to TileFailure.BAD_KEY, 401 to TileFailure.BAD_KEY, 429 to TileFailure.RATE_LIMITED, 404 to TileFailure.NETWORK, 503 to TileFailure.NETWORK)) {
            status = code
            assertEquals(reason, assertFailsWith<TileFetchException> { fetch().get(tile, "k") }.failure, "HTTP $code")
        }
        status = 200
        seen.clear()
        assertEquals(TileFailure.OFFLINE, assertFailsWith<TileFetchException> { fetch(offline = true).get(tile, "k") }.failure)
        assertTrue(seen.isEmpty(), "nothing reached the server")
    }
}
