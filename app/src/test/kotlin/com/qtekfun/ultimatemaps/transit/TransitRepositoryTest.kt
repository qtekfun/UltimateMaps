package com.qtekfun.ultimatemaps.transit

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.regions.RegionAsset
import com.qtekfun.ultimatemaps.core.regions.TransitAsset
import com.qtekfun.ultimatemaps.core.regions.TransitBounds
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.FeedOptions
import com.qtekfun.ultimatemaps.core.transit.TransitDataManager
import com.qtekfun.ultimatemaps.core.transit.TransitFailure
import com.qtekfun.ultimatemaps.core.transit.TransitIndexBuilder
import com.qtekfun.ultimatemaps.core.transit.TransitIndexIo
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lookup, catalog rows and user-started downloads of the transit data, against a local server. No real network. */
class TransitRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes: ByteArray = ByteArrayOutputStream().also {
        // the made-up feed of TransitTestSupport, written as an index file
        TransitIndexIo.write(TransitTestSupport.service().index, it)
    }.toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val hits = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits += ex.requestURI.path
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }
        start()
    }

    @After fun stop() = server.stop(0)

    private val now = TransitTestSupport.clockAt("2026-10-14T07:55:00")
    private val sync = Executor { it.run() }

    private fun asset(validTo: String = "2026-10-31", id: String = "testville") = TransitAsset(
        id, "Testville", RegionAsset("http://127.0.0.1:${server.address.port}/transit-$id.umti", bytes.size.toLong(), sha, "transit-$id.umti"),
        "2026-10-01", validTo, "Europe/Madrid", TransitBounds(39.9, -3.1, 40.1, -2.9), listOf("Powered by Test Agency"),
    )

    private fun repo(offered: List<TransitAsset>, offline: Boolean = false): TransitRepository {
        val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = true)), offlineMode = offline)
        val manager = TransitDataManager(tmp.newFolder("transit"), policy, { LocalDate.parse("2026-10-14") }, allowInsecure = true)
        return TransitRepository(manager, { offered }, sync, now)
    }

    private val a = TransitTestSupport.nearA
    private val c = TransitTestSupport.nearC

    @Test
    fun withoutAnyDataTheLookupSaysSo() {
        assertEquals(TransitLookup.NoData, repo(emptyList()).lookup(a, c))
    }

    @Test
    fun theCatalogOffersACityThatIsNotDownloadedYetAndNothingIsFetchedByLookup() {
        val r = repo(listOf(asset()))
        assertEquals(TransitLookup.NotDownloaded("Testville"), r.lookup(a, c))
        assertTrue(hits.isEmpty(), "looking up never touches the network")
        val row = r.rows().single()
        assertEquals("Testville", row.city)
        assertTrue(!row.installed && !row.expired)
    }

    @Test
    fun aUserStartedDownloadInstallsTheCityAndThePlannerWorks() {
        val r = repo(listOf(asset()))
        r.download("testville")
        val row = r.rows().single()
        assertTrue(row.installed && !row.downloading && row.failure == null)
        assertEquals(listOf("/transit-testville.umti"), hits)
        val ready = assertIs<TransitLookup.Ready>(r.lookup(a, c))
        assertEquals("Testville", ready.city)
        assertEquals(listOf("Powered by Test Agency (https://agency.example/). Processed data."), ready.service.attributions)
        assertEquals(listOf("Powered by Test Agency"), r.attributions)
        // a trip outside the city's box is not covered
        assertEquals(TransitLookup.OutsideCoverage, r.lookup(a, LatLon(41.5, -3.0)))
        r.delete("testville")
        assertTrue(r.rows().none { it.installed })
        assertEquals(TransitLookup.NotDownloaded("Testville"), r.lookup(a, c))
    }

    @Test
    fun anExpiredCatalogEntryIsShownAsExpiredAndCannotBeInstalled() {
        val r = repo(listOf(asset(validTo = "2026-05-27")))
        assertTrue(r.rows().single().expired)
        r.download("testville")
        assertTrue(hits.isEmpty())
        assertEquals(TransitFailure.EXPIRED, r.rows().single().failure)
        assertTrue(!r.rows().single().installed)
    }

    @Test
    fun offlineModeBlocksTheDownloadAndSaysWhy() {
        val r = repo(listOf(asset()), offline = true)
        r.download("testville")
        assertTrue(hits.isEmpty())
        assertEquals(TransitFailure.OFFLINE_MODE, r.rows().single().failure)
    }

    @Test
    fun aNewerFileInTheCatalogOffersAnUpdateAndUnknownIdsAreIgnored() {
        val r1 = repo(listOf(asset()))
        r1.download("testville")
        r1.download("nope") // not in the catalog: nothing happens
        assertEquals(1, hits.size)
        val newer = asset().let { it.copy(asset = it.asset.copy(sha256 = "c".repeat(64))) }
        val r2 = TransitRepository(
            TransitDataManager(java.io.File(tmp.root, "transit"), DefaultNetworkPolicy(), { LocalDate.parse("2026-10-14") }, allowInsecure = true),
            { listOf(newer) }, sync, now,
        )
        r2.refresh()
        assertTrue(r2.rows().single().updateAvailable)
        assertNull(r2.rows().single().failure)
    }
}
