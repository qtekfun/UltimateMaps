package com.qtekfun.mapas.core.regions

import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.net.DenyReason
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogFetcherTest {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    private var body = CATALOG
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/c/catalog.json") { ex ->
            hits.incrementAndGet()
            val b = body.toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/old") { ex ->
            hits.incrementAndGet()
            ex.responseHeaders.add("Location", "/c/catalog.json")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
    }

    @AfterEach fun down() = server.stop(0)

    private fun policy(offline: Boolean = false, enabled: Boolean = true) = DefaultNetworkPolicy(
        listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled)), offlineMode = offline,
    )

    @Test
    fun `fetches the catalog and follows a redirect`() {
        val (text, catalog) = CatalogFetcher(policy(), allowInsecure = true).fetch("$base/old")
        assertTrue(text.contains("catalogVersion"))
        assertEquals(listOf("x"), catalog.regions.map { it.id })
        assertEquals(2, hits.get())
    }

    @Test
    fun `offline mode and disabled hosts make zero connections`() {
        val off = assertFailsWith<NetworkDeniedException> {
            CatalogFetcher(policy(offline = true), allowInsecure = true).fetch("$base/c/catalog.json")
        }
        assertEquals(DenyReason.OFFLINE_MODE, off.reason)
        val dis = assertFailsWith<NetworkDeniedException> {
            CatalogFetcher(policy(enabled = false), allowInsecure = true).fetch("$base/c/catalog.json")
        }
        assertEquals(DenyReason.DISABLED_BY_USER, dis.reason)
        assertEquals(0, hits.get())
    }

    @Test
    fun `http error, plain http, oversized and invalid bodies are errors`() {
        assertFailsWith<IOException> { CatalogFetcher(policy(), allowInsecure = true).fetch("$base/missing") }
        assertFailsWith<IOException> { CatalogFetcher(policy()).fetch("$base/c/catalog.json") }
        assertEquals(0, hits.get())
        assertFailsWith<IOException> { CatalogFetcher(policy(), allowInsecure = true, maxBytes = 10).fetch("$base/c/catalog.json") }
        body = "not json"
        assertFailsWith<CatalogException> { CatalogFetcher(policy(), allowInsecure = true).fetch("$base/c/catalog.json") }
    }

    private companion object {
        const val CATALOG = """{"schema":1,"catalogVersion":"t","regions":[{"id":"x","name":"X","parent":null,"version":"1"}]}"""
    }
}
