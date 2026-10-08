package com.qtekfun.ultimatemaps.core.zbe

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

class ZbeDataManagerTest {
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
    private fun sample() = javaClass.getResourceAsStream("/zbe/sample.bin")!!.readBytes()

    private class Env(val srv: Server, settings: ZbeSettings, bytes: ByteArray, val dir: File = Files.createTempDirectory("zbe").toFile(), assetPresent: Boolean = true, shaOverride: String? = null) {
        val store = InMemoryZbeSettingsStore(settings)
        val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
        val asset = if (assetPresent) ZbeAsset(srv.base + "/zbe-es.bin", bytes.size.toLong(), shaOverride ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) else null
        val manager = ZbeDataManager(
            store, policy, { asset }, dir, BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = ZbeFile.MAX_BYTES.toLong()),
            io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private val on = ZbeSettings(enabled = true)

    @Test fun nothingIsRequestedWhileTheSwitchIsOff() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, ZbeSettings(), bytes)
            env.manager.start()
            assertNull(env.manager.refresh(ZbeTrigger.USER))
            assertNull(env.manager.refresh(ZbeTrigger.ENABLED))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get(), "off by default: zero connections")
            assertTrue(env.manager.repository.data.zones.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun enablingDownloadsVerifiesCachesAndALaterStartNeedsNoNetwork() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, ZbeSettings(), bytes)
            env.manager.start()
            env.store.update { on }
            assertNull(env.manager.refresh(ZbeTrigger.ENABLED))
            assertEquals(1, srv.hits.get())
            assertEquals(2, env.manager.repository.data.zones.size)
            assertEquals(listOf("/zbe-es.bin"), srv.paths.toList(), "no query, no position")
            assertNotNull(env.manager.repository.generatedMillis.value)
            val again = Env(srv, on, bytes, dir = env.dir)
            again.manager.start()
            assertEquals(2, again.manager.repository.data.zones.size, "loaded from the cache")
            assertNull(again.manager.refresh(ZbeTrigger.FOREGROUND))
            assertEquals(1, srv.hits.get(), "same hash in the catalog: nothing to fetch")
            assertNull(again.manager.refresh(ZbeTrigger.USER))
            assertEquals(2, srv.hits.get(), "'Update now' always downloads")
            again.store.update { ZbeSettings() }
            assertTrue(again.manager.repository.data.zones.isEmpty(), "switching off drops the data from memory")
        } finally { srv.stop() }
    }

    @Test fun aWrongHashOrAGarbageFileKeepsWhatWeHad() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val bad = Env(srv, on, bytes, shaOverride = "0".repeat(64))
            assertEquals(DownloadFailure.INVALID_DATA, bad.manager.refresh(ZbeTrigger.USER))
            assertTrue(bad.manager.repository.data.zones.isEmpty())
        } finally { srv.stop() }
        val junk = "not a zone file at all, but long enough to pass the size check".toByteArray()
        val srv2 = Server { _, _ -> 200 to junk }
        try {
            assertEquals(DownloadFailure.INVALID_DATA, Env(srv2, on, junk).manager.refresh(ZbeTrigger.USER))
        } finally { srv2.stop() }
    }

    @Test fun theAppWorksWhenTheCatalogHasNoZbeFileOrTheServerLacksIt() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 404 to ByteArray(0) }
        try {
            val none = Env(srv, on, bytes, assetPresent = false)
            none.manager.start()
            assertEquals(DownloadFailure.NO_CATALOG, none.manager.refresh(ZbeTrigger.USER))
            assertNull(none.manager.refresh(ZbeTrigger.FOREGROUND), "silent in the background")
            assertEquals(0, srv.hits.get())
            val missing = Env(srv, on, bytes)
            assertEquals(DownloadFailure.NETWORK, missing.manager.refresh(ZbeTrigger.USER))
            assertTrue(missing.manager.repository.data.zones.isEmpty())
        } finally { srv.stop() }
    }

    @Test fun offlineModeStopsTheDownloadBeforeAnyConnection() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val env = Env(srv, on, bytes)
            env.policy.offlineMode = true
            assertEquals(DownloadFailure.OFFLINE_MODE, env.manager.refresh(ZbeTrigger.USER))
            env.manager.onForeground()
            assertEquals(0, srv.hits.get())
        } finally { srv.stop() }
    }

    @Test fun anOldCachedCatalogWithoutTheZbeBlockIsRefreshedBeforeTheLookup() = runBlocking {
        val bytes = sample()
        val srv = Server { _, _ -> 200 to bytes }
        try {
            val store = InMemoryZbeSettingsStore(ZbeSettings())
            val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", CAMERA_DATA_PURPOSE, enabled = true)))
            var asset: ZbeAsset? = null
            val syncs = ArrayList<Boolean>()
            val manager = ZbeDataManager(
                store, policy, { asset }, Files.createTempDirectory("zbe").toFile(),
                BoundedHttp(policy, CAMERA_DATA_PURPOSE, allowInsecure = true, maxBytes = ZbeFile.MAX_BYTES.toLong()),
                io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
                syncCatalog = { force -> syncs += force; asset = ZbeAsset(srv.base + "/zbe-es.bin", bytes.size.toLong(), sha(bytes)) },
            )
            manager.start()
            store.update { on }
            assertNull(manager.refresh(ZbeTrigger.ENABLED), "found only because the catalog was refreshed first")
            assertEquals(2, manager.repository.data.zones.size)
            assertEquals(listOf(true), syncs)
            assertNull(manager.refresh(ZbeTrigger.FOREGROUND))
            assertEquals(listOf(true, false), syncs)
        } finally { srv.stop() }
    }
}
