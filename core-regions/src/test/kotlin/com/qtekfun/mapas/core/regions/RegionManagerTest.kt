package com.qtekfun.mapas.core.regions

import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local HTTP server with Range support; optionally cuts a response after [dropAfter] bytes (once per arm). */
private class TestServer {
    val files = HashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<Pair<String, String?>>() // path to Range header
    val connections = AtomicInteger()
    @Volatile var dropAfter: Int? = null
    @Volatile var ignoreRange = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        connections.incrementAndGet()
        val path = ex.requestURI.path
        val range = ex.requestHeaders.getFirst("Range")
        requests += path to range
        val data = files[path]
        if (data == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
        var start = 0
        if (range != null && !ignoreRange) start = range.removePrefix("bytes=").removeSuffix("-").toInt()
        val len = data.size - start
        if (start > 0) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
        ex.sendResponseHeaders(if (start > 0) 206 else 200, len.toLong())
        val cut = dropAfter
        try {
            if (cut != null && cut < len) {
                dropAfter = null
                ex.responseBody.write(data, start, cut)
                ex.responseBody.flush()
            } else {
                ex.responseBody.write(data, start, len)
            }
        } catch (_: IOException) {
        } finally {
            try { ex.close() } catch (_: IOException) {}
        }
    }

    fun stop() = server.stop(0)
}

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

class RegionManagerTest {
    @TempDir lateinit var tmp: File
    private lateinit var server: TestServer
    private lateinit var policy: DefaultNetworkPolicy
    private lateinit var manager: RegionManager
    private lateinit var root: File

    private val render = Random(1).nextBytes(600_000)
    private val search = Random(2).nextBytes(250_000)

    @BeforeEach
    fun setUp() {
        server = TestServer()
        server.files["/v1/r.pmtiles"] = render
        server.files["/v1/r.mwm"] = search
        policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = true)))
        root = File(tmp, "regions")
        manager = RegionManager(root, ResumableDownloader(policy, allowInsecure = true, readTimeoutMs = 5_000))
    }

    @AfterEach fun tearDown() = server.stop()

    private fun region(version: String = "v1", r: ByteArray = render, s: ByteArray = search, hashR: String = sha(r)) = Region(
        "es-test", "Test", null, version,
        mapOf(
            AssetKind.RENDER to RegionAsset("http://127.0.0.1:${server.port}/$version/r.pmtiles", r.size.toLong(), hashR, "r.pmtiles"),
            AssetKind.SEARCH to RegionAsset("http://127.0.0.1:${server.port}/$version/r.mwm", s.size.toLong(), sha(s), "r.mwm"),
        ),
    )

    @Test
    fun `installs both downloads and verifies`() {
        val inst = manager.install(region())
        assertContentEquals(render, inst.files.getValue(AssetKind.RENDER).readBytes())
        assertContentEquals(search, inst.files.getValue(AssetKind.SEARCH).readBytes())
        assertEquals("v1", manager.installed("es-test")?.version)
        assertTrue(File(root, ".partial").listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancelled download resumes with a Range request`() {
        val cancel = CancelToken()
        assertFailsWith<DownloadCancelledException> {
            manager.install(region(), cancel) { done, _ -> if (done > 200_000) cancel.cancel() }
        }
        assertNull(manager.installed("es-test"))
        val partial = File(root, ".partial").listFiles()!!.single()
        assertTrue(partial.length() in 1 until render.size)
        val had = partial.length()
        server.requests.clear()
        manager.install(region())
        assertEquals("bytes=$had-", server.requests.first().second)
        assertContentEquals(render, manager.installed("es-test")!!.files.getValue(AssetKind.RENDER).readBytes())
    }

    @Test
    fun `connection dropped by the server resumes and ends identical`() {
        server.dropAfter = 100_000
        assertFailsWith<IOException> { manager.install(region()) }
        assertNull(manager.installed("es-test"))
        manager.install(region())
        assertTrue(server.requests.any { it.second != null && it.second!!.startsWith("bytes=") })
        assertContentEquals(render, manager.installed("es-test")!!.files.getValue(AssetKind.RENDER).readBytes())
    }

    @Test
    fun `server that ignores Range restarts from zero`() {
        val cancel = CancelToken()
        assertFailsWith<DownloadCancelledException> { manager.install(region(), cancel) { d, _ -> if (d > 100_000) cancel.cancel() } }
        server.ignoreRange = true
        manager.install(region())
        assertContentEquals(render, manager.installed("es-test")!!.files.getValue(AssetKind.RENDER).readBytes())
    }

    @Test
    fun `wrong hash is rejected, nothing is activated and the partial is discarded`() {
        val bad = region(hashR = "0".repeat(64))
        val e = assertFailsWith<HashMismatchException> { manager.install(bad) }
        assertEquals("0".repeat(64), e.expected)
        assertNull(manager.installed("es-test"))
        assertTrue(File(root, ".partial").listFiles().isNullOrEmpty())
        assertFalse(File(root, "es-test/v1/r.pmtiles").exists())
    }

    @Test
    fun `update is atomic - a failed update keeps the old version, a good one swaps it`() {
        manager.install(region())
        val newRender = Random(3).nextBytes(300_000)
        val newSearch = Random(4).nextBytes(100_000)
        server.files["/v2/r.pmtiles"] = newRender
        server.files["/v2/r.mwm"] = newSearch

        // Second asset corrupt: the first is verified but the region must not switch.
        val broken = region("v2", newRender, newSearch).let { r ->
            r.copy(assets = r.assets + (AssetKind.SEARCH to r.assets.getValue(AssetKind.SEARCH).copy(sha256 = "f".repeat(64))))
        }
        assertFailsWith<HashMismatchException> { manager.install(broken) }
        assertEquals("v1", manager.installed("es-test")?.version)
        assertContentEquals(render, manager.installed("es-test")!!.files.getValue(AssetKind.RENDER).readBytes())

        manager.install(region("v2", newRender, newSearch))
        val now = manager.installed("es-test")!!
        assertEquals("v2", now.version)
        assertContentEquals(newRender, now.files.getValue(AssetKind.RENDER).readBytes())
        assertFalse(File(root, "es-test/v1").exists())
        manager.cleanup()
        assertEquals("v2", manager.installed("es-test")?.version)
    }

    @Test
    fun `updatesAvailable compares catalog version with installed`() {
        manager.install(region())
        val same = RegionCatalog("c1", listOf(region()))
        assertTrue(manager.updatesAvailable(same).isEmpty())
        val newer = RegionCatalog("c2", listOf(region("v2")))
        assertEquals(listOf("es-test"), manager.updatesAvailable(newer).map { it.id })
    }

    @Test
    fun `delete removes region and partials`() {
        manager.install(region())
        manager.delete("es-test")
        assertNull(manager.installed("es-test"))
        assertFalse(File(root, "es-test").exists())
        assertTrue(manager.installed().isEmpty())
    }

    @Test
    fun `offline mode means zero connections`() {
        policy.offlineMode = true
        val e = assertFailsWith<NetworkDeniedException> { manager.install(region()) }
        assertEquals(com.qtekfun.mapas.core.net.DenyReason.OFFLINE_MODE, e.reason)
        assertEquals(0, server.connections.get())
        assertNull(manager.installed("es-test"))
        // The attempt is logged with host and purpose only, never a URL.
        val rec = policy.recentConnections().single()
        assertEquals("127.0.0.1", rec.host)
        assertFalse(rec.allowed)
    }

    @Test
    fun `host not whitelisted or disabled by user makes zero connections`() {
        val strict = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = false)))
        val m = RegionManager(root, ResumableDownloader(strict, allowInsecure = true))
        assertFailsWith<NetworkDeniedException> { m.install(region()) }
        val other = DefaultNetworkPolicy(listOf(AllowedEndpoint("maps.example.org", ConnectionPurpose.MAP_DOWNLOAD, enabled = true)))
        assertFailsWith<NetworkDeniedException> { RegionManager(root, ResumableDownloader(other, allowInsecure = true)).install(region()) }
        assertEquals(0, server.connections.get())
    }

    @Test
    fun `plain http is refused by default`() {
        val m = RegionManager(root, ResumableDownloader(policy))
        assertFailsWith<IOException> { m.install(region()) }
        assertEquals(0, server.connections.get())
    }

    @Test
    fun `storage selector never falls back silently`() {
        val a = StorageLocation("internal", "Internal", File(tmp, "a"), removable = false)
        val b = StorageLocation("sd", "SD card", File(tmp, "b"), removable = true)
        assertEquals(b, StorageSelector.choose(listOf(a, b), "sd", 1_000, reserveBytes = 0))
        assertNull(StorageSelector.choose(listOf(a), "sd", 1_000, reserveBytes = 0))
        assertNull(StorageSelector.choose(listOf(a), "internal", Long.MAX_VALUE / 2))
        assertNotNull(StorageSelector.candidates(listOf(a, b), 1_000, 0).firstOrNull())
    }
}

class CatalogTest {
    private val json = """
    {"schema":1,"catalogVersion":"2026-10-06","regions":[
      {"id":"europe","name":"Europe","parent":null,"version":"261005"},
      {"id":"spain","name":"Spain","parent":"europe","version":"261005"},
      {"id":"spain-madrid","name":"Madrid","parent":"spain","version":"261005","assets":{
        "render":{"url":"pm/madrid.pmtiles","size":100,"sha256":"${"a".repeat(64)}","file":"madrid.pmtiles"},
        "search":{"url":"https://cdn.example.org/261005/Madrid.mwm","size":50,"sha256":"${"B".repeat(64)}","file":"Madrid.mwm"}}}]}
    """.trimIndent()

    @Test
    fun `parses hierarchy, resolves relative urls and round-trips`() {
        val c = RegionCatalog.parse(json, "https://maps.example.org/catalog/")
        assertEquals(listOf("spain"), c.children("europe").map { it.id })
        val m = c["spain-madrid"]!!
        assertEquals("https://maps.example.org/catalog/pm/madrid.pmtiles", m.assets.getValue(AssetKind.RENDER).url)
        assertEquals("b".repeat(64), m.assets.getValue(AssetKind.SEARCH).sha256)
        assertEquals(150, m.totalBytes)
        assertEquals(listOf("spain-madrid"), c.downloadableUnder("europe").map { it.id })
        val again = RegionCatalog.parse(c.toJson())
        assertEquals(c.regions, again.regions)
    }

    @Test
    fun `rejects bad catalogs`() {
        assertFailsWith<CatalogException> { RegionCatalog.parse("{") }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json.replace("\"schema\":1", "\"schema\":9")) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json.replace("\"parent\":\"europe\"", "\"parent\":\"nope\"")) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json.replace("a".repeat(64), "xyz")) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json.replace("madrid.pmtiles\"}", "../x\"}")) }
    }
}
