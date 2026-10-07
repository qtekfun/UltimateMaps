package com.qtekfun.ultimatemaps.core.regions

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Catalog -> install a fake region together with its World -> `maps-core/<version>/` with the names CoMaps
 * wants. The catalog is served by a local "github.com" that redirects the assets to a second host, like
 * GitHub Releases do.
 */
class CoreLinkIntegrationTest {
    @TempDir lateinit var tmp: File
    private val origin = LocalHost()

    private val world = Random(1).nextBytes(30_000)
    private val coasts = Random(2).nextBytes(20_000)
    private val rioja = Random(3).nextBytes(50_000)
    private val riojaTiles = Random(4).nextBytes(40_000)

    @AfterEach fun stop() = origin.close()

    private fun asset(path: String, bytes: ByteArray, file: String) =
        """{"url":"${origin.url(path)}","size":${bytes.size},"sha256":"${sha256Hex(bytes)}","file":"$file"}"""

    private fun publish(version: String) {
        // Files live behind a redirect to the "other host", as in GitHub Releases.
        val files = mapOf("/w$version" to world, "/c$version" to coasts, "/r$version.mwm" to rioja, "/r$version.pm" to riojaTiles)
        files.forEach { (p, b) -> origin.files["/cdn$p"] = b; origin.redirects[p] = origin.url("/cdn$p", alias = true) }
        origin.files["/catalog.json"] = """
            {"schema":1,"catalogVersion":"$version",
             "base":{"version":"$version","world":${asset("/w$version", world, "World.mwm")},"worldCoasts":${asset("/c$version", coasts, "WorldCoasts.mwm")}},
             "regions":[
              {"id":"spain","name":"Spain","parent":null,"version":"$version"},
              {"id":"spain_la-rioja","comapsId":"Spain_La Rioja","name":"La Rioja","parent":"spain","version":"$version",
               "assets":{"render":${asset("/r$version.pm", riojaTiles, "spain_la-rioja.pmtiles")},
                         "search":${asset("/r$version.mwm", rioja, "spain_la-rioja.mwm")}}}]}
        """.trimIndent().toByteArray()
    }

    @Test
    fun `catalog to install to maps-core with CoMaps names, then update and delete`() {
        publish("261004")
        val policy = DefaultNetworkPolicy(
            listOf("127.0.0.1", "localhost").map { AllowedEndpoint(it, ConnectionPurpose.MAP_DOWNLOAD, enabled = true) },
        )
        val manager = RegionManager(File(tmp, "regions"), ResumableDownloader(policy, allowInsecure = true))
        val linker = CoreMapsLinker(File(tmp, "files/maps-core"))
        val core = File(tmp, "files/maps-core")

        val (_, catalog) = CatalogFetcher(policy, allowInsecure = true).fetch(origin.url("/catalog.json"))
        val region = assertNotNull(catalog["spain_la-rioja"])
        val progress = ArrayList<Pair<Long, Long>>()
        manager.install(region, base = catalog.base, onProgress = { d, t -> progress += d to t })
        assertEquals(world.size + coasts.size + rioja.size + riojaTiles.size.toLong(), progress.last().second)
        assertEquals(progress.last().second, progress.last().first)

        val report = linker.sync(manager.installed(), manager.installedBases())
        assertEquals(listOf("Spain_La Rioja.mwm", "World.mwm", "WorldCoasts.mwm"), File(core, "261004").list()!!.sorted())
        assertEquals(sha256Hex(rioja), sha256Hex(File(core, "261004/Spain_La Rioja.mwm").readBytes()))
        assertEquals(sha256Hex(world), sha256Hex(File(core, "261004/World.mwm").readBytes()))
        assertEquals(sha256Hex(coasts), sha256Hex(File(core, "261004/WorldCoasts.mwm").readBytes()))
        assertTrue(report.failed.isEmpty())

        // A second region of the same version does not download World again.
        origin.hits.clear()
        manager.install(region.copy(id = "spain_other", comapsId = "Spain_Other"), base = catalog.base)
        assertTrue(origin.hits.none { it.endsWith("/w261004") || it.endsWith("/c261004") }, "base maps were downloaded twice: ${origin.hits}")
        linker.sync(manager.installed(), manager.installedBases())
        assertEquals(listOf("Spain_La Rioja.mwm", "Spain_Other.mwm", "World.mwm", "WorldCoasts.mwm"), File(core, "261004").list()!!.sorted())

        // Update to a new data version: new directory, old one gone, no restart needed.
        publish("261105")
        val (_, next) = CatalogFetcher(policy, allowInsecure = true).fetch(origin.url("/catalog.json"))
        assertEquals(listOf("spain_la-rioja"), manager.updatesAvailable(next).map { it.id }.filter { it == "spain_la-rioja" })
        manager.install(next["spain_la-rioja"]!!, base = next.base)
        manager.delete("spain_other")
        manager.cleanup()
        val up = linker.sync(manager.installed(), manager.installedBases())
        assertEquals(listOf("261105"), core.list()!!.filter { !it.startsWith(".") })
        assertEquals(listOf("Spain_La Rioja.mwm", "World.mwm", "WorldCoasts.mwm"), File(core, "261105").list()!!.sorted())
        assertEquals(listOf("Spain_Other.mwm"), up.droppedMaps) // only the deleted region needs a core restart

        // Delete the last region: nothing is left for the core, bases are swept.
        manager.delete("spain_la-rioja")
        manager.cleanup()
        assertEquals(1, manager.installedBases().size) // kept until the caller knows no storage has regions
        manager.removeBases()
        val gone = linker.sync(manager.installed(), manager.installedBases())
        assertEquals(emptyList(), core.list()!!.filter { !it.startsWith(".") })
        assertEquals(listOf("Spain_La Rioja.mwm"), gone.droppedMaps)
        assertTrue(manager.installedBases().isEmpty())
    }
}
