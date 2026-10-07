package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataManagersTest {
    private class Server(val handler: (path: String, hit: Int) -> Triple<Int, ByteArray, Map<String, String>>) {
        val hits = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val acceptEncodings = CopyOnWriteArrayList<String?>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/") { ex ->
                val n = hits.incrementAndGet()
                paths += ex.requestURI.toString()
                acceptEncodings += ex.requestHeaders.getFirst("Accept-Encoding")
                val (code, body, headers) = handler(ex.requestURI.path, n)
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.write(body)
                ex.close()
            }
            s.start()
        }
        val base get() = "http://127.0.0.1:${http.address.port}"
        fun stop() = http.stop(0)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun sample() = javaClass.getResourceAsStream("/cameras/sample.bin")!!.readBytes()
    private fun tmp(): File = Files.createTempDirectory("cams").toFile()

    // ------------------------------------------------------------------ camera file

    private class CamEnv(val srv: Server, settings: CameraSettings, bytes: ByteArray, val dir: File = Files.createTempDirectory("cams").toFile(), assetPresent: Boolean = true, shaOverride: String? = null) {
        val store = InMemoryCameraSettingsStore(settings)
        val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
        val asset = if (assetPresent) CameraAsset(srv.base + "/speedcams-es.bin", bytes.size.toLong(), shaOverride ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) else null
        val manager = CameraDataManager(
            store, policy, { asset }, dir, BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = CameraFile.MAX_BYTES.toLong()),
            io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private val camsOn = CameraSettings(fixedEnabled = true, acknowledged = true)

    @Test fun cameraFileIsNotRequestedWhileTheSwitchesAreOff() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(200, bytes, emptyMap()) }
        try {
            val env = CamEnv(srv, CameraSettings(), bytes)
            env.manager.start()
            assertNull(env.manager.refresh(CameraTrigger.USER))
            assertNull(env.manager.refresh(CameraTrigger.ENABLED))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get(), "off by default: zero connections")
            assertTrue(env.manager.repository.data.fixed.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun enablingDownloadsVerifiesCachesAndALaterStartNeedsNoNetwork() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(200, bytes, emptyMap()) }
        try {
            val env = CamEnv(srv, CameraSettings(), bytes)
            env.manager.start()
            env.store.update { camsOn }
            assertNull(env.manager.refresh(CameraTrigger.ENABLED))
            assertEquals(1, srv.hits.get())
            assertEquals(2, env.manager.repository.data.fixed.size)
            assertEquals(listOf("/speedcams-es.bin"), srv.paths.toList(), "no query, no position")
            assertNotNull(env.manager.repository.generatedMillis.value)
            // A restart: the cache is loaded with no connection, and a foreground with the same catalog hash does nothing.
            val again = CamEnv(srv, camsOn, bytes, dir = env.dir)
            again.manager.start()
            assertEquals(2, again.manager.repository.data.fixed.size)
            assertNull(again.manager.refresh(CameraTrigger.FOREGROUND))
            assertEquals(1, srv.hits.get())
            // "Update now" always downloads.
            assertNull(again.manager.refresh(CameraTrigger.USER))
            assertEquals(2, srv.hits.get())
            // Switching off drops the data from memory.
            again.store.update { CameraSettings() }
            assertTrue(again.manager.repository.data.fixed.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun aWrongHashOrAGarbageFileKeepsWhatWeHad() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(200, bytes, emptyMap()) }
        try {
            val bad = CamEnv(srv, camsOn, bytes, shaOverride = "0".repeat(64))
            assertEquals(DownloadFailure.INVALID_DATA, bad.manager.refresh(CameraTrigger.USER))
            assertTrue(bad.manager.repository.data.fixed.isEmpty())
            val junk = "not a camera file at all, but long enough to pass the size check".toByteArray()
            val srv2 = Server { _, _ -> Triple(200, junk, emptyMap()) }
            try {
                val env = CamEnv(srv2, camsOn, junk)
                assertEquals(DownloadFailure.INVALID_DATA, env.manager.refresh(CameraTrigger.USER))
            } finally { srv2.stop() }
        } finally { srv.stop() }
    }

    @Test fun theAppWorksWhenTheCatalogHasNoCameraFileOrTheServerLacksIt() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(404, ByteArray(0), emptyMap()) }
        try {
            val none = CamEnv(srv, camsOn, bytes, assetPresent = false)
            none.manager.start()
            assertEquals(DownloadFailure.NO_CATALOG, none.manager.refresh(CameraTrigger.USER))
            assertNull(none.manager.refresh(CameraTrigger.FOREGROUND), "silent in the background")
            assertEquals(0, srv.hits.get())
            val missing = CamEnv(srv, camsOn, bytes)
            assertEquals(DownloadFailure.NETWORK, missing.manager.refresh(CameraTrigger.USER))
            assertTrue(missing.manager.repository.data.fixed.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun offlineModeStopsTheCameraDownloadBeforeAnyConnection() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(200, bytes, emptyMap()) }
        try {
            val env = CamEnv(srv, camsOn, bytes)
            env.policy.offlineMode = true
            assertEquals(DownloadFailure.OFFLINE_MODE, env.manager.refresh(CameraTrigger.USER))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun anOldCachedCatalogWithoutTheCameraBlockIsRefreshedBeforeTheLookup() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(200, bytes, emptyMap()) }
        try {
            val store = InMemoryCameraSettingsStore(CameraSettings())
            val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
            var asset: CameraAsset? = null // the cached catalog predates the `cameras` block
            val syncs = ArrayList<Boolean>()
            val manager = CameraDataManager(
                store, policy, { asset }, tmp(), BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = CameraFile.MAX_BYTES.toLong()),
                io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
                syncCatalog = { force -> syncs += force; asset = CameraAsset(srv.base + "/speedcams-es.bin", bytes.size.toLong(), sha(bytes)) },
            )
            manager.start()
            store.update { camsOn }
            assertNull(manager.refresh(CameraTrigger.ENABLED), "the file was found only because the catalog was refreshed first")
            assertEquals(2, manager.repository.data.fixed.size)
            assertEquals(listOf(true), syncs, "turning the switch on forces the refresh")
            assertNull(manager.refresh(CameraTrigger.FOREGROUND))
            assertEquals(listOf(true, false), syncs, "a plain foreground only asks, the app decides whether to fetch")
            policy.offlineMode = true
            manager.refresh(CameraTrigger.USER)
            assertEquals(2, syncs.size, "offline mode: the catalog is not even attempted")
        } finally { srv.stop() }
    }

    @Test fun aFailingCatalogSyncFallsBackToTheCachedCatalog() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> Triple(200, bytes, emptyMap()) }
        try {
            val env = CamEnv(srv, camsOn, bytes)
            val m = CameraDataManager(
                env.store, env.policy, { env.asset }, env.dir, BoundedHttp(env.policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = CameraFile.MAX_BYTES.toLong()),
                io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined), syncCatalog = { error("catalog server down") },
            )
            assertNull(m.refresh(CameraTrigger.USER))
            assertEquals(2, m.repository.data.fixed.size)
        } finally { srv.stop() }
    }

    // ------------------------------------------------------------------ incidents

    private val feedXml = """<?xml version="1.0" encoding="UTF-8"?>
<d2:payload xmlns:d2="d" xmlns:sit="s" xmlns:com="c" xmlns:loc="l" xmlns:xsi="x"><com:publicationTime>2026-10-07T15:28:08.921+02:00</com:publicationTime>
<sit:situation id="1"><sit:situationRecord id="10"><sit:situationRecordCreationReference>V16_abc_1</sit:situationRecordCreationReference>
<sit:validity><com:validityStatus>active</com:validityStatus></sit:validity><sit:cause><sit:causeType>vehicleObstruction</sit:causeType></sit:cause>
<sit:locationReference><loc:roadName>N-540</loc:roadName><loc:point><loc:pointCoordinates><loc:latitude>42.98</loc:latitude><loc:longitude>-7.58</loc:longitude></loc:pointCoordinates></loc:point></sit:locationReference>
</sit:situationRecord></sit:situation></d2:payload>"""

    private class IncEnv(val srv: Server, settings: CameraSettings, val dir: File, var now: Long = 1_000_000L) {
        val store = InMemoryCameraSettingsStore(settings)
        val policy = DefaultNetworkPolicy()
        val manager = IncidentDataManager(
            store, policy, policy::addEndpoint, policy::removeEndpoint, IncidentCache(dir),
            BoundedHttp(policy, INCIDENT_PURPOSE, allowInsecure = true), srv.base + "/datex2_v37.xml", { now },
            scope = CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined,
        )
    }

    private val incidentsOn = CameraSettings(incidentsEnabled = true, v16Enabled = true, incidentRefreshMinutes = 10)

    @Test fun incidentsAreNotFetchedUnlessTheUserOptedInAndTheHostIsNotListedBefore() = runBlocking {
        val srv = Server { _, _ -> Triple(200, feedXml.toByteArray(), emptyMap()) }
        try {
            val env = IncEnv(srv, CameraSettings(), tmp())
            env.manager.start()
            assertNull(env.manager.refresh(IncidentTrigger.USER))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get())
            assertTrue(env.policy.possibleConnections().none { it.purpose == ConnectionPurpose.TRAFFIC_INCIDENTS }, "not even listed while off")
            env.store.update { incidentsOn }
            assertEquals(listOf("127.0.0.1"), env.policy.possibleConnections().filter { it.purpose == ConnectionPurpose.TRAFFIC_INCIDENTS }.map { it.host })
            env.store.update { CameraSettings() }
            assertTrue(env.policy.possibleConnections().none { it.purpose == ConnectionPurpose.TRAFFIC_INCIDENTS }, "removed again when switched off")
        } finally { srv.stop() }
    }

    @Test fun fetchParsesGzipFiltersOnDeviceAndSendsNoPosition() = runBlocking {
        val srv = Server { _, _ -> Triple(200, gzip(feedXml.toByteArray()), mapOf("Content-Encoding" to "gzip")) }
        try {
            val env = IncEnv(srv, incidentsOn, tmp())
            env.manager.start()
            assertNull(env.manager.refresh(IncidentTrigger.ENABLED))
            assertEquals(listOf("/datex2_v37.xml"), srv.paths.toList(), "one national file, no query, no coordinates")
            assertEquals("gzip", srv.acceptEncodings.single())
            val bounds = LatLonBounds(42.0, -8.0, 43.5, -7.0)
            assertEquals(listOf("10"), env.manager.repository.incidentsIn(bounds, setOf(IncidentKind.V16)).map { it.id })
            assertTrue(env.manager.repository.incidentsIn(bounds, setOf(IncidentKind.ACCIDENT)).isEmpty())
            assertEquals(env.now, env.manager.repository.lastUpdateMillis.value)
        } finally { srv.stop() }
    }

    @Test fun ttlControlsForegroundAndEnabledButNotUserRefreshes() = runBlocking {
        val srv = Server { _, _ -> Triple(200, feedXml.toByteArray(), emptyMap()) }
        try {
            val env = IncEnv(srv, incidentsOn, tmp())
            env.manager.start()
            env.manager.refresh(IncidentTrigger.ENABLED)
            assertEquals(1, srv.hits.get())
            env.now += 9 * 60_000L
            env.manager.refresh(IncidentTrigger.FOREGROUND)
            env.manager.refresh(IncidentTrigger.ENABLED)
            assertEquals(1, srv.hits.get(), "still fresh at 9 of 10 minutes")
            env.manager.refresh(IncidentTrigger.USER)
            assertEquals(2, srv.hits.get(), "Update now ignores the TTL")
            env.now += 11 * 60_000L
            env.manager.refresh(IncidentTrigger.FOREGROUND)
            assertEquals(3, srv.hits.get(), "expired")
            env.manager.refresh(IncidentTrigger.FOREGROUND)
            assertEquals(3, srv.hits.get(), "and fresh again")
        } finally { srv.stop() }
    }

    @Test fun cachedIncidentsLoadOfflineAndOfflineModeMakesNoConnection() = runBlocking {
        val srv = Server { _, _ -> Triple(200, feedXml.toByteArray(), emptyMap()) }
        try {
            val dir = tmp()
            val first = IncEnv(srv, incidentsOn, dir)
            first.manager.start()
            first.manager.refresh(IncidentTrigger.USER)
            assertEquals(1, srv.hits.get())
            val second = IncEnv(srv, incidentsOn, dir)
            second.policy.offlineMode = true
            second.manager.start()
            assertEquals(1, second.manager.repository.data!!.incidents.size, "shown from the cache with no network")
            assertEquals(DownloadFailure.OFFLINE_MODE, second.manager.refresh(IncidentTrigger.USER))
            second.manager.onForeground()
            assertEquals(1, srv.hits.get(), "offline mode: zero connections")
            assertEquals(1, second.manager.repository.data!!.incidents.size, "and the old data stays")
        } finally { srv.stop() }
    }

    @Test fun failuresKeepTheLastGoodData() = runBlocking {
        var fail = false
        val srv = Server { _, _ -> if (fail) Triple(500, "x".toByteArray(), emptyMap()) else Triple(200, feedXml.toByteArray(), emptyMap()) }
        try {
            val env = IncEnv(srv, incidentsOn, tmp())
            env.manager.start()
            env.manager.refresh(IncidentTrigger.USER)
            fail = true
            assertEquals(DownloadFailure.SERVER, env.manager.refresh(IncidentTrigger.USER))
            assertEquals(1, env.manager.repository.data!!.incidents.size)
            val broken = Server { _, _ -> Triple(200, feedXml.dropLast(150).toByteArray(), emptyMap()) }
            try {
                val e2 = IncEnv(broken, incidentsOn, tmp())
                assertEquals(DownloadFailure.INVALID_DATA, e2.manager.refresh(IncidentTrigger.USER))
                assertNull(e2.manager.repository.data)
            } finally { broken.stop() }
        } finally { srv.stop() }
    }

    @Test fun theDownloadIsCappedAfterDecompression() = runBlocking {
        val big = gzip(("<a>" + "x".repeat(200_000) + "</a>").toByteArray())
        val srv = Server { _, _ -> Triple(200, big, mapOf("Content-Encoding" to "gzip")) }
        try {
            val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", INCIDENT_PURPOSE, enabled = true)))
            val http = BoundedHttp(policy, INCIDENT_PURPOSE, allowInsecure = true, maxBytes = 50_000)
            val e = assertFailsWith<DownloadException> { http.get(srv.base + "/x", "application/xml") { it.readBytes() } }
            assertEquals(DownloadFailure.TOO_LARGE, e.failure, "a small gzip body cannot expand past the cap")
        } finally { srv.stop() }
    }

    @Test fun incidentCacheRoundTripsAndRejectsDamage() {
        val dir = tmp()
        val cache = IncidentCache(dir)
        assertNull(cache.read())
        val feed = DatexIncidentParser.parse(feedXml.byteInputStream())
        cache.write(IncidentData(5L, feed.publishedMillis, feed.incidents))
        val back = cache.read()!!
        assertEquals(5L, back.fetchedAtMillis)
        assertEquals(feed.incidents, back.incidents)
        val f = File(dir, "incidents.bin")
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf().also { it[30] = (it[30] + 1).toByte() })
        assertNull(cache.read(), "a flipped byte makes it 'no data'")
        f.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(cache.read())
    }
}
