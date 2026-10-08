package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.cameras.CAMERA_DATA_PURPOSE
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BikeShareDataManagerTest {
    private class Server(val handler: (path: String, hit: Int) -> Pair<Int, ByteArray>) {
        val hits = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/") { ex ->
                val n = hits.incrementAndGet()
                paths += ex.requestURI.toString()
                val (code, body) = handler(ex.requestURI.path, n)
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
    private fun sample() = javaClass.getResourceAsStream("/bikeshare/sample.bin")!!.readBytes()

    private class Env(val srv: Server, settings: BikeShareSettings, bytes: ByteArray, val dir: File = Files.createTempDirectory("bikeshare").toFile(), assetPresent: Boolean = true, shaOverride: String? = null) {
        val store = InMemoryBikeShareSettingsStore(settings)
        val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
        val asset = if (assetPresent) BikeShareAsset(srv.base + "/bikeshare-es.bin", bytes.size.toLong(), shaOverride ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) else null
        val manager = BikeShareDataManager(
            store, policy, { asset }, dir, BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = BikeShareFile.MAX_BYTES.toLong()),
            io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private val on = BikeShareSettings(enabled = true)

    @Test fun nothingIsRequestedWhileTheSwitchIsOff() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, BikeShareSettings(), bytes)
            env.manager.start()
            assertNull(env.manager.refresh(BikeShareTrigger.USER))
            assertNull(env.manager.refresh(BikeShareTrigger.ENABLED))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get(), "off by default: zero connections")
            assertTrue(env.manager.repository.data.stations.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun enablingDownloadsVerifiesCachesAndALaterStartNeedsNoNetwork() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, BikeShareSettings(), bytes)
            env.manager.start()
            env.store.update { on }
            assertNull(env.manager.refresh(BikeShareTrigger.ENABLED))
            assertEquals(1, srv.hits.get())
            assertEquals(5, env.manager.repository.data.stations.size)
            assertEquals(listOf("/bikeshare-es.bin"), srv.paths.toList(), "no query, no position")
            assertNotNull(env.manager.repository.generatedMillis.value)
            val again = Env(srv, on, bytes, dir = env.dir)
            again.manager.start()
            assertEquals(5, again.manager.repository.data.stations.size, "loaded from the cache")
            assertNull(again.manager.refresh(BikeShareTrigger.FOREGROUND))
            assertEquals(1, srv.hits.get(), "same hash in the catalog: nothing to fetch")
            assertNull(again.manager.refresh(BikeShareTrigger.USER))
            assertEquals(2, srv.hits.get(), "'Update now' always downloads")
            again.store.update { BikeShareSettings() }
            assertTrue(again.manager.repository.data.stations.isEmpty(), "switching off drops the data from memory")
        } finally { srv.stop() }
    }

    @Test fun aWrongHashOrAGarbageFileKeepsWhatWeHad() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val bad = Env(srv, on, bytes, shaOverride = "0".repeat(64))
            assertEquals(DownloadFailure.INVALID_DATA, bad.manager.refresh(BikeShareTrigger.USER))
            assertTrue(bad.manager.repository.data.stations.isEmpty())
        } finally { srv.stop() }
        val junk = "not a zone file at all, but long enough to pass the size check".toByteArray()
        val srv2 = Server { _, _ -> 200 to junk }
        try {
            assertEquals(DownloadFailure.INVALID_DATA, Env(srv2, on, junk).manager.refresh(BikeShareTrigger.USER))
        } finally { srv2.stop() }
    }

    @Test fun theAppWorksWhenTheCatalogHasNoBikeShareFileOrTheServerLacksIt() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 404 to ByteArray(0) }
        try {
            val none = Env(srv, on, bytes, assetPresent = false)
            none.manager.start()
            assertEquals(DownloadFailure.NO_CATALOG, none.manager.refresh(BikeShareTrigger.USER))
            assertNull(none.manager.refresh(BikeShareTrigger.FOREGROUND), "silent in the background")
            assertEquals(0, srv.hits.get())
            val missing = Env(srv, on, bytes)
            assertEquals(DownloadFailure.NETWORK, missing.manager.refresh(BikeShareTrigger.USER))
            assertTrue(missing.manager.repository.data.stations.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun offlineModeStopsTheDownloadBeforeAnyConnection() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, on, bytes)
            env.policy.offlineMode = true
            assertEquals(DownloadFailure.OFFLINE_MODE, env.manager.refresh(BikeShareTrigger.USER))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun anOldCachedCatalogWithoutTheBikeShareBlockIsRefreshedBeforeTheLookup() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val store = InMemoryBikeShareSettingsStore(BikeShareSettings())
            val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
            var asset: BikeShareAsset? = null
            val syncs = ArrayList<Boolean>()
            val manager = BikeShareDataManager(
                store, policy, { asset }, Files.createTempDirectory("bikeshare").toFile(),
                BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = BikeShareFile.MAX_BYTES.toLong()),
                io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
                syncCatalog = { force -> syncs += force; asset = BikeShareAsset(srv.base + "/bikeshare-es.bin", bytes.size.toLong(), sha(bytes)) },
            )
            manager.start()
            store.update { on }
            assertNull(manager.refresh(BikeShareTrigger.ENABLED), "found only because the catalog was refreshed first")
            assertEquals(5, manager.repository.data.stations.size)
            assertEquals(listOf(true), syncs)
            assertNull(manager.refresh(BikeShareTrigger.FOREGROUND))
            assertEquals(listOf(true, false), syncs)
        } finally { srv.stop() }
    }
}
