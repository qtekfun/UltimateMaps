package com.qtekfun.ultimatemaps.core.routes

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

class RouteDataManagerTest {
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
    private fun sample() = javaClass.getResourceAsStream("/routes/sample.bin")!!.readBytes()

    private class Env(val srv: Server, settings: RouteSettings, bytes: ByteArray, val dir: File = Files.createTempDirectory("rte").toFile(), assetPresent: Boolean = true, shaOverride: String? = null) {
        val store = InMemoryRouteSettingsStore(settings)
        val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
        val asset = if (assetPresent) RouteAsset(srv.base + "/routes-es.bin", bytes.size.toLong(), shaOverride ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) else null
        val manager = RouteDataManager(
            store, policy, { asset }, dir, BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = RouteFile.MAX_BYTES.toLong()),
            io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private val on = RouteSettings(enabled = true)

    @Test fun nothingIsRequestedWhileTheSwitchIsOff() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, RouteSettings(), bytes)
            env.manager.start()
            assertNull(env.manager.refresh(RouteTrigger.USER))
            assertNull(env.manager.refresh(RouteTrigger.ENABLED))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get(), "off by default: zero connections")
            assertTrue(env.manager.repository.data.trails.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun enablingDownloadsVerifiesCachesAndALaterStartNeedsNoNetwork() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, RouteSettings(), bytes)
            env.manager.start()
            env.store.update { on }
            assertNull(env.manager.refresh(RouteTrigger.ENABLED))
            assertEquals(1, srv.hits.get())
            assertEquals(4, env.manager.repository.data.trails.size)
            assertEquals(listOf("/routes-es.bin"), srv.paths.toList(), "no query, no position")
            assertNotNull(env.manager.repository.generatedMillis.value)
            val again = Env(srv, on, bytes, dir = env.dir)
            again.manager.start()
            assertEquals(4, again.manager.repository.data.trails.size, "loaded from the cache")
            assertNull(again.manager.refresh(RouteTrigger.FOREGROUND))
            assertEquals(1, srv.hits.get(), "same hash in the catalog: nothing to fetch")
            assertNull(again.manager.refresh(RouteTrigger.USER))
            assertEquals(2, srv.hits.get(), "'Update now' always downloads")
            again.store.update { RouteSettings() }
            assertTrue(again.manager.repository.data.trails.isEmpty(), "switching off drops the data from memory")
        } finally { srv.stop() }
    }

    @Test fun aWrongHashOrAGarbageFileKeepsWhatWeHad() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val bad = Env(srv, on, bytes, shaOverride = "0".repeat(64))
            assertEquals(DownloadFailure.INVALID_DATA, bad.manager.refresh(RouteTrigger.USER))
            assertTrue(bad.manager.repository.data.trails.isEmpty())
        } finally { srv.stop() }
        val junk = "not a route file at all, but long enough to pass the size check".toByteArray()
        val srv2 = Server { _, _ -> 200 to junk }
        try {
            assertEquals(DownloadFailure.INVALID_DATA, Env(srv2, on, junk).manager.refresh(RouteTrigger.USER))
        } finally { srv2.stop() }
    }

    @Test fun theAppWorksWhenTheCatalogHasNoRouteFileOrTheServerLacksIt() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 404 to ByteArray(0) }
        try {
            val none = Env(srv, on, bytes, assetPresent = false)
            none.manager.start()
            assertEquals(DownloadFailure.NO_CATALOG, none.manager.refresh(RouteTrigger.USER))
            assertNull(none.manager.refresh(RouteTrigger.FOREGROUND), "silent in the background")
            assertEquals(0, srv.hits.get())
            val missing = Env(srv, on, bytes)
            assertEquals(DownloadFailure.NETWORK, missing.manager.refresh(RouteTrigger.USER))
            assertTrue(missing.manager.repository.data.trails.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun offlineModeStopsTheDownloadBeforeAnyConnection() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, on, bytes)
            env.policy.offlineMode = true
            assertEquals(DownloadFailure.OFFLINE_MODE, env.manager.refresh(RouteTrigger.USER))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun anOldCachedCatalogWithoutTheRoutesBlockIsRefreshedBeforeTheLookup() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val store = InMemoryRouteSettingsStore(RouteSettings())
            val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
            var asset: RouteAsset? = null
            val syncs = ArrayList<Boolean>()
            val manager = RouteDataManager(
                store, policy, { asset }, Files.createTempDirectory("rte").toFile(),
                BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = RouteFile.MAX_BYTES.toLong()),
                io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
                syncCatalog = { force -> syncs += force; asset = RouteAsset(srv.base + "/routes-es.bin", bytes.size.toLong(), sha(bytes)) },
            )
            manager.start()
            store.update { on }
            assertNull(manager.refresh(RouteTrigger.ENABLED), "found only because the catalog was refreshed first")
            assertEquals(4, manager.repository.data.trails.size)
            assertEquals(listOf(true), syncs)
            assertNull(manager.refresh(RouteTrigger.FOREGROUND))
            assertEquals(listOf(true, false), syncs)
        } finally { srv.stop() }
    }

    @Test fun aNewerFileIsFetchedInTheBackgroundOnlyWhenTheCachedOneIsOldButUserUpdatesAlways() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            var now = System.currentTimeMillis()
            val store = InMemoryRouteSettingsStore(on)
            val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
            var asset = RouteAsset(srv.base + "/routes-es.bin", bytes.size.toLong(), sha(bytes))
            val manager = RouteDataManager(
                store, policy, { asset }, Files.createTempDirectory("rte").toFile(),
                BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = RouteFile.MAX_BYTES.toLong()),
                clock = { now }, io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
            )
            assertNull(manager.refresh(RouteTrigger.ENABLED))
            assertEquals(1, srv.hits.get())
            // The catalog now names another file (any other hash); the cache is fresh, so the background does nothing.
            asset = asset.copy(sha256 = "1".repeat(64))
            assertNull(manager.refresh(RouteTrigger.FOREGROUND))
            assertEquals(1, srv.hits.get(), "fresh cache: no multi-MB download in the background")
            now += RouteDataManager.FOREGROUND_MIN_AGE_MS + 60_000
            assertEquals(DownloadFailure.INVALID_DATA, manager.refresh(RouteTrigger.FOREGROUND), "old cache: tried (the hash does not match the sample)")
            assertEquals(2, srv.hits.get())
            assertEquals(4, manager.repository.data.trails.size, "a failed download keeps what we had")
        } finally { srv.stop() }
    }
}
