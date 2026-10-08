package com.qtekfun.ultimatemaps.transit

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.regions.RegionAsset
import com.qtekfun.ultimatemaps.core.regions.TransitAsset
import com.qtekfun.ultimatemaps.core.regions.TransitBounds
import com.qtekfun.ultimatemaps.core.transit.TransitDataManager
import com.qtekfun.ultimatemaps.core.transit.TransitIndexIo
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Several transit indexes at once: choice by location, trips across two of them, opt-in downloads, the planner's message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitMultiIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val bytes: ByteArray = ByteArrayOutputStream().also { TransitIndexIo.write(TransitTestSupport.service().index, it) }.toByteArray()
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

    private val sync = Executor { it.run() }
    private val now = TransitTestSupport.clockAt("2026-10-14T07:55:00")

    // Testville (around lat 40, lon -3) and Otherton (around lat 38, lon -1), far apart.
    private val testvilleBox = TransitBounds(39.9, -3.1, 40.1, -2.9)
    private val othertonBox = TransitBounds(37.9, -1.1, 38.1, -0.9)
    private val regionBox = TransitBounds(39.0, -4.0, 41.0, -2.0) // a large box around Testville

    private fun asset(id: String, city: String, box: TransitBounds?) = TransitAsset(
        id, city, RegionAsset("http://127.0.0.1:${server.address.port}/transit-$id.umti", bytes.size.toLong(), sha, "transit-$id.umti"),
        "2026-10-01", "2026-10-31", "Europe/Madrid", box, listOf("Powered by MITRAMS"),
    )

    private fun repo(offered: List<TransitAsset>): TransitRepository {
        val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = true)))
        val manager = TransitDataManager(tmp.newFolder("transit"), policy, { LocalDate.parse("2026-10-14") }, allowInsecure = true)
        return TransitRepository(manager, { offered }, sync, now)
    }

    private val inTestville = LatLon(40.0001, -3.0)
    private val inTestville2 = LatLon(40.0201, -3.0)
    private val inOtherton = LatLon(38.0, -1.0)

    @Test
    fun eachIndexIsDownloadedOnlyWhenChosenAndTheTripPicksTheRightOne() {
        val r = repo(listOf(asset("testville", "Testville", testvilleBox), asset("otherton", "Otherton", othertonBox)))
        assertEquals(listOf("Otherton", "Testville"), r.rows().map { it.city }.sorted())
        assertTrue(hits.isEmpty(), "listing never downloads")
        assertEquals(TransitLookup.NotDownloaded("Testville"), r.lookup(inTestville, inTestville2))
        assertEquals(TransitLookup.NotDownloaded("Otherton"), r.lookup(inOtherton, inOtherton))
        r.download("otherton")
        assertEquals(listOf("/transit-otherton.umti"), hits)
        // Otherton is now installed; Testville still is not
        assertEquals("Otherton", assertIs<TransitLookup.Ready>(r.lookup(inOtherton, inOtherton)).city)
        assertEquals(TransitLookup.NotDownloaded("Testville"), r.lookup(inTestville, inTestville2))
        r.download("testville")
        assertEquals("Testville", assertIs<TransitLookup.Ready>(r.lookup(inTestville, inTestville2)).city)
        assertEquals(listOf(true, true), r.rows().map { it.installed })
    }

    @Test
    fun aTripBetweenTwoIndexesSaysSoWhetherInstalledOrOnlyOffered() {
        val r = repo(listOf(asset("testville", "Testville", testvilleBox), asset("otherton", "Otherton", othertonBox)))
        assertEquals(TransitLookup.AcrossIndexes("Testville", "Otherton"), r.lookup(inTestville, inOtherton))
        r.download("testville")
        r.download("otherton")
        assertEquals(TransitLookup.AcrossIndexes("Otherton", "Testville"), r.lookup(inOtherton, inTestville))
        // an end with no data at all is plain "outside coverage"
        assertEquals(TransitLookup.OutsideCoverage, r.lookup(inTestville, LatLon(43.0, 5.0)))
    }

    @Test
    fun overlappingBoxesUseTheTighterInstalledIndex() {
        val r = repo(listOf(asset("region", "Whole region", regionBox), asset("testville", "Testville", testvilleBox)))
        r.download("region")
        r.download("testville")
        assertEquals("Testville", assertIs<TransitLookup.Ready>(r.lookup(inTestville, inTestville2)).city)
        // from Testville to a point only the large box holds
        assertEquals("Whole region", assertIs<TransitLookup.Ready>(r.lookup(inTestville, LatLon(39.5, -3.5))).city)
        r.delete("testville")
        assertEquals("Whole region", assertIs<TransitLookup.Ready>(r.lookup(inTestville, inTestville2)).city)
    }

    @Test
    fun attributionsOfEveryInstalledIndexAreListedOnce() {
        val r = repo(listOf(asset("testville", "Testville", testvilleBox), asset("otherton", "Otherton", othertonBox)))
        r.download("testville")
        r.download("otherton")
        assertEquals(listOf("Powered by MITRAMS"), r.attributions)
    }

    @Test
    fun theControllerExplainsAcrossIndexTrips() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = TransitController(scope, Dispatchers.Unconfined, { _, _ -> TransitLookup.AcrossIndexes("Madrid", "Valencia") }, now)
        c.plan(inTestville, inOtherton)
        assertEquals(TransitError.ACROSS_INDEXES, c.state.error)
        assertEquals("Madrid", c.state.errorCity)
        assertEquals("Valencia", c.state.errorCity2)
        c.clear()
        assertEquals(null, c.state.errorCity2)
        scope.cancel()
    }

    @Test
    fun theMapsRowShowsTheDownloadSizeUntilInstalled() {
        val rows = listOf(
            TransitCityRow("a", "City a", "2026-10-07", "2026-11-05", 3_000_000, false, false, false, false, null, listOf("x")),
            TransitCityRow("b", "City b", "2026-10-07", "2026-11-05", 3_000_000, true, false, false, false, null, listOf("x")),
        )
        compose.setContent { MapasTheme(darkTheme = false) { TransitMapsSection(rows, false, {}, {}) } }
        compose.onNodeWithTag("transit_city_a_size").assertExists()
        compose.onNodeWithTag("transit_city_b_size").assertDoesNotExist()
    }
}
