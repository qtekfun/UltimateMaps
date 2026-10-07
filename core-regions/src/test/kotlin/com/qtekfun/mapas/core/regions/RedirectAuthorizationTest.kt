package com.qtekfun.mapas.core.regions

import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.net.DenyReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GitHub Releases answer with 302 to another host (`release-assets.githubusercontent.com`, formerly
 * `objects.githubusercontent.com`). Every hop must be authorized on its own: here "127.0.0.1" plays github.com
 * and "localhost" (same machine, different host name) plays the asset host.
 */
class RedirectAuthorizationTest {
    @TempDir lateinit var tmp: File
    private val origin = LocalHost()
    private val data = Random(7).nextBytes(100_000)

    private fun policy(vararg hosts: String) =
        DefaultNetworkPolicy(hosts.map { AllowedEndpoint(it, ConnectionPurpose.MAP_DOWNLOAD, enabled = true) })

    @AfterEach fun stop() = origin.close()

    private fun setUp() {
        origin.files["/asset"] = data
        origin.redirects["/latest"] = origin.url("/asset", alias = true) // another host, as GitHub does
        origin.files["/catalog.json"] = "{\"schema\":1,\"catalogVersion\":\"t\",\"regions\":[]}".toByteArray()
        origin.redirects["/catalog-latest"] = origin.url("/catalog.json", alias = true)
    }

    @Test
    fun `download follows a redirect to a second host when both are authorized`() {
        setUp()
        val p = policy("127.0.0.1", "localhost")
        val part = File(tmp, "a.part")
        ResumableDownloader(p, allowInsecure = true).download(origin.url("/latest"), part, data.size.toLong(), sha256Hex(data))
        assertContentEquals(data, part.readBytes())
        assertEquals(setOf("127.0.0.1", "localhost"), p.recentConnections().map { it.host }.toSet())
        assertTrue(p.recentConnections().all { it.allowed && it.purpose == ConnectionPurpose.MAP_DOWNLOAD })
    }

    @Test
    fun `download refuses a redirect to a host that is not authorized and never contacts it`() {
        setUp()
        val p = policy("127.0.0.1") // the asset host is NOT whitelisted
        val e = assertFailsWith<NetworkDeniedException> {
            ResumableDownloader(p, allowInsecure = true).download(origin.url("/latest"), File(tmp, "b.part"), data.size.toLong(), sha256Hex(data))
        }
        assertEquals("localhost", e.host)
        assertEquals(DenyReason.NOT_WHITELISTED, e.reason)
        assertEquals(listOf("/latest"), origin.hits.toList()) // the second hop never reached the server
        assertFalse(File(tmp, "b.part").exists())
    }

    @Test
    fun `a disabled second host is refused too`() {
        setUp()
        val p = DefaultNetworkPolicy(
            listOf(
                AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = true),
                AllowedEndpoint("localhost", ConnectionPurpose.MAP_DOWNLOAD, enabled = false),
            ),
        )
        val e = assertFailsWith<NetworkDeniedException> {
            ResumableDownloader(p, allowInsecure = true).download(origin.url("/latest"), File(tmp, "c.part"), data.size.toLong(), sha256Hex(data))
        }
        assertEquals(DenyReason.DISABLED_BY_USER, e.reason)
        assertEquals(listOf("/latest"), origin.hits.toList())
    }

    @Test
    fun `catalog fetch follows an authorized redirect and refuses an unauthorized one`() {
        setUp()
        val ok = CatalogFetcher(policy("127.0.0.1", "localhost"), allowInsecure = true).fetch(origin.url("/catalog-latest"))
        assertEquals("t", ok.second.catalogVersion)
        origin.hits.clear()
        assertFailsWith<NetworkDeniedException> {
            CatalogFetcher(policy("127.0.0.1"), allowInsecure = true).fetch(origin.url("/catalog-latest"))
        }
        assertEquals(listOf("/catalog-latest"), origin.hits.toList())
    }

    @Test
    fun `plain http is refused without any connection when insecure is not allowed`() {
        setUp()
        val p = policy("127.0.0.1", "localhost")
        assertFailsWith<IOException> {
            ResumableDownloader(p).download(origin.url("/latest"), File(tmp, "d.part"), data.size.toLong(), sha256Hex(data))
        }
        assertTrue(origin.hits.isEmpty())
        assertTrue(p.recentConnections().isEmpty())
    }
}
