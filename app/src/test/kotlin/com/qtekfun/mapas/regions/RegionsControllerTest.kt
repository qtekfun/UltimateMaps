package com.qtekfun.mapas.regions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Catalog loading, offline mode, storage and downloads against a local HTTP server (JVM, no device). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionsControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val direct = Executor { it.run() }
    private val hits = AtomicInteger()
    private lateinit var server: HttpServer
    private lateinit var policy: DefaultNetworkPolicy
    private val render = ByteArray(300) { (it * 7).toByte() }
    private val search = ByteArray(200) { (it * 3).toByte() }
    private val world = ByteArray(120) { (it * 5).toByte() }
    private val coasts = ByteArray(90) { (it * 11).toByte() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private var catalogJson = ""

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val body = when (ex.requestURI.path) {
                "/catalog.json" -> catalogJson.toByteArray()
                "/madrid.pmtiles" -> render
                "/madrid.mwm" -> search
                "/World.mwm" -> world
                "/WorldCoasts.mwm" -> coasts
                else -> null
            }
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.start()
        catalogJson = """{"schema":1,"catalogVersion":"t",
          "base":{"version":"2","world":{"url":"World.mwm","size":${world.size},"sha256":"${sha(world)}","file":"World.mwm"},
                  "worldCoasts":{"url":"WorldCoasts.mwm","size":${coasts.size},"sha256":"${sha(coasts)}","file":"WorldCoasts.mwm"}},
          "regions":[
          {"id":"spain","name":"Spain","parent":null,"version":"2"},
          {"id":"madrid","comapsId":"Spain_Community of Madrid","name":"Madrid","parent":"spain","version":"2","assets":{
            "render":{"url":"madrid.pmtiles","size":${render.size},"sha256":"${sha(render)}","file":"madrid.pmtiles"},
            "search":{"url":"madrid.mwm","size":${search.size},"sha256":"${sha(search)}","file":"madrid.mwm"}}},
          {"id":"huge","name":"Huge","parent":"spain","version":"2","assets":{
            "render":{"url":"huge.pmtiles","size":${Long.MAX_VALUE / 4},"sha256":"${sha(render)}","file":"huge.pmtiles"},
            "search":{"url":"huge.mwm","size":10,"sha256":"${sha(search)}","file":"huge.mwm"}}}]}"""
        policy = DefaultNetworkPolicy()
        context.getSharedPreferences(RegionsController.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "regions-catalog.json").delete()
        File(context.filesDir, "regions").deleteRecursively()
    }

    @After
    fun tearDown() {
        server.stop(0)
        CoreLinks.resetForTests()
    }

    private fun controller(): RegionsController =
        RegionsController(context, policy, policy::addEndpoint, allowInsecure = true, downloadExecutor = direct, ioExecutor = direct, startService = {})
            .also { it.restore() }

    @Test
    fun `the default catalog is the project's data release and only lists hosts, never connects`() {
        val c = controller()
        assertEquals(RegionsController.DEFAULT_CATALOG_URL, c.serverUrl)
        assertTrue(RegionsController.DEFAULT_CATALOG_URL.startsWith("https://github.com/qtekfun/UltimateMaps-data/releases/latest/download/"))
        val listed = policy.possibleConnections().filter { it.purpose == ConnectionPurpose.MAP_DOWNLOAD }
        assertEquals(
            setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            listed.map { it.host }.toSet(),
        )
        assertTrue(listed.all { it.enabled })
        assertTrue(policy.recentConnections().isEmpty()) // nothing was contacted
        assertEquals(0, hits.get())
    }

    @Test
    fun `a custom server does not whitelist the github hosts`() {
        context.getSharedPreferences(RegionsController.PREFS, Context.MODE_PRIVATE).edit().putString("catalog_url", "https://maps.example.org/c.json").commit()
        controller()
        assertEquals(listOf("maps.example.org"), policy.possibleConnections().map { it.host })
    }

    @Test
    fun `clearing the server on purpose survives a restart and nothing is contacted`() {
        val c = controller()
        assertTrue(c.saveServerUrl(""))
        assertEquals(CatalogState.NoServer, c.catalogState)
        val again = controller()
        assertEquals("", again.serverUrl)
        again.refreshCatalog()
        assertEquals(CatalogState.NoServer, again.catalogState)
        assertEquals(CatalogState.NoServer, c.catalogState)
        assertEquals(0, hits.get())
        assertTrue(policy.recentConnections().isEmpty())
    }

    @Test
    fun `server address must be a valid url and is whitelisted for downloads only`() {
        val c = controller()
        assertFalse(c.saveServerUrl("not a url"))
        assertFalse(c.saveServerUrl("ftp://x.org/c.json"))
        assertTrue(c.saveServerUrl("$base/catalog.json"))
        val e = policy.possibleConnections().single { it.host == "127.0.0.1" }
        assertEquals(ConnectionPurpose.MAP_DOWNLOAD, e.purpose)
        assertTrue(e.enabled)
    }

    @Test
    fun `refresh loads the catalog and a later failure falls back to the saved copy`() {
        val c = controller()
        c.saveServerUrl("$base/catalog.json")
        c.refreshCatalog()
        val loaded = assertIs<CatalogState.Loaded>(c.catalogState)
        assertEquals(3, loaded.catalog.regions.size)
        assertFalse(loaded.stale)

        server.stop(0) // the server disappears
        c.refreshCatalog()
        val stale = assertIs<CatalogState.Loaded>(c.catalogState)
        assertTrue(stale.stale)
        assertEquals(CatalogError.NETWORK, stale.refreshError)

        val fresh = controller() // new process: the saved copy is shown without any request
        assertTrue(assertIs<CatalogState.Loaded>(fresh.catalogState).stale)
    }

    @Test
    fun `no saved copy and no network is an error state`() {
        val c = controller()
        c.saveServerUrl("$base/nothing-here.json")
        c.refreshCatalog()
        assertEquals(CatalogState.Failed(CatalogError.NETWORK), c.catalogState)
    }

    @Test
    fun `offline mode makes zero connections for the catalog and for downloads`() {
        val c = controller()
        c.saveServerUrl("$base/catalog.json")
        c.refreshCatalog()
        val madrid = assertIs<CatalogState.Loaded>(c.catalogState).catalog["madrid"]!!
        val before = hits.get()

        c.setOfflineMode(true)
        assertTrue(policy.offlineMode)
        c.refreshCatalog()
        assertEquals(CatalogError.OFFLINE_MODE, assertIs<CatalogState.Loaded>(c.catalogState).refreshError)
        c.download(madrid)
        assertEquals(DownloadState.Failed(FailureReason.OFFLINE_MODE), c.downloadStates["madrid"])
        assertEquals(before, hits.get(), "no request may reach the server in offline mode")
        assertTrue(policy.recentConnections().take(2).none { it.allowed })

        // the choice survives a restart
        assertTrue(controller().offline)
    }

    @Test
    fun `downloads a region, installs it, and deleting frees it`() {
        val c = controller()
        c.saveServerUrl("$base/catalog.json")
        c.refreshCatalog()
        val madrid = assertIs<CatalogState.Loaded>(c.catalogState).catalog["madrid"]!!
        c.download(madrid)
        assertEquals(emptyMap(), c.downloadStates)
        val entry = assertNotNull(c.installed.singleOrNull())
        assertEquals("madrid", entry.region.id)
        assertEquals((render.size + search.size).toLong(), entry.bytes)
        assertTrue(entry.region.files.values.all { it.isFile })
        assertEquals(entry.region.files.getValue(com.qtekfun.mapas.core.regions.AssetKind.RENDER).readBytes().toList(), render.toList())

        // The core finds it where it looks: maps-core/<version>/<comapsId>.mwm, with World next to it.
        val core = File(context.filesDir, "maps-core/2")
        assertEquals(listOf("Spain_Community of Madrid.mwm", "World.mwm", "WorldCoasts.mwm"), core.list()!!.sorted())
        assertEquals(search.toList(), File(core, "Spain_Community of Madrid.mwm").readBytes().toList())
        assertEquals(world.toList(), File(core, "World.mwm").readBytes().toList())
        assertFalse(c.linkProblem)

        CoreLinks.coreLoaded = true // the core is running: deleting needs a restart to forget the map
        c.delete("madrid")
        assertTrue(c.restartForSearch)
        assertFalse(File(context.filesDir, "maps-core/2").exists())
        assertTrue(c.installed.isEmpty())
        assertFalse(File(context.filesDir, "regions/madrid").exists())
    }

    @Test
    fun `not enough space fails before any download connection`() {
        val c = controller()
        c.saveServerUrl("$base/catalog.json")
        c.refreshCatalog()
        val huge = assertIs<CatalogState.Loaded>(c.catalogState).catalog["huge"]!!
        val before = hits.get()
        c.download(huge)
        val failed = assertIs<DownloadState.Failed>(c.downloadStates["huge"])
        assertEquals(FailureReason.NO_SPACE, failed.reason)
        assertTrue(failed.neededBytes > failed.freeBytes)
        assertEquals(before, hits.get())
        assertTrue(c.installed.isEmpty())
    }

    @Test
    fun `internal storage is always offered with its free space`() {
        val c = controller()
        val internal = c.storage.first()
        assertEquals(RegionStorage.INTERNAL_ID, internal.location.id)
        assertTrue(internal.totalBytes >= internal.freeBytes)
        assertEquals(RegionStorage.INTERNAL_ID, c.selectedLocationId)
    }
}
