package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.cameras.DownloadException
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The real fetch (BoundedHttp) against a loopback server: the headers that leave the phone, and the policy gate. */
class PhotonHttpTest {
    private val seen = mutableListOf<Map<String, String>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/api/") { ex ->
            seen += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() } + ("uri" to ex.requestURI.toString())
            val body = """{"features":[]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @After fun stop() = server.stop(0)

    private fun policy(enabled: Boolean = true, offline: Boolean = false) = DefaultNetworkPolicy(
        listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.ONLINE_SEARCH, enabled)), offlineMode = offline,
    )

    @Test
    fun theRequestCarriesTheUserAgentAndNoCookiesOrIdentifiers() {
        val ua = PhotonGeocoder.userAgent("0.1.0-test")
        val body = PhotonGeocoder.fetchOver(policy(), ua, allowInsecure = true).get("$base/api/?q=plaza&limit=8")
        assertEquals("""{"features":[]}""", body)
        val h = seen.single()
        assertEquals(ua, h["user-agent"])
        assertEquals("/api/?q=plaza&limit=8", h["uri"])
        assertTrue(h.keys.none { it == "cookie" || it == "authorization" || it == "referer" || it.startsWith("x-") }, h.keys.toString())
    }

    @Test
    fun theConnectionGateIsAskedFirst() {
        assertFailsWith<DownloadException> {
            PhotonGeocoder.fetchOver(policy(offline = true), "ua", allowInsecure = true).get("$base/api/?q=plaza")
        }.also { assertEquals(DownloadFailure.OFFLINE_MODE, it.failure) }
        assertFailsWith<DownloadException> {
            PhotonGeocoder.fetchOver(policy(enabled = false), "ua", allowInsecure = true).get("$base/api/?q=plaza")
        }.also { assertEquals(DownloadFailure.NOT_ALLOWED, it.failure) }
        // https only: a plain http address is refused before any connection unless the test asks for it.
        assertFailsWith<DownloadException> { PhotonGeocoder.fetchOver(policy(), "ua").get("$base/api/?q=plaza") }
        assertTrue(seen.isEmpty(), "nothing reached the server")
    }
}
