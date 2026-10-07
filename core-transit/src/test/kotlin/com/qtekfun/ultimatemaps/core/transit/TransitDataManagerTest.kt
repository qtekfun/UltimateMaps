package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.regions.RegionAsset
import com.qtekfun.ultimatemaps.core.regions.TransitAsset
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verified download of a city index through the NetworkPolicy, against a local server (no real network). */
class TransitDataManagerTest {
    @TempDir lateinit var tmp: File

    private val hits = CopyOnWriteArrayList<String>()
    private val files = HashMap<String, ByteArray>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits += ex.requestURI.path
            val data = files[ex.requestURI.path]
            if (data == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.write(data)
            }
            ex.close()
        }
        start()
    }

    @AfterEach fun stop() = server.stop(0)

    private val today = LocalDate.parse("2026-10-14")
    private val indexBytes = ByteArrayOutputStream().also { TransitIndexIo.write(Fixtures.index(), it) }.toByteArray()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun asset(bytes: ByteArray = indexBytes, validTo: String = "2026-10-31", sha: String = sha(bytes), size: Long = bytes.size.toLong()) = TransitAsset(
        "test", "Testville", RegionAsset("http://127.0.0.1:${server.address.port}/transit-test.umti", size, sha, "transit-test.umti"),
        "2026-10-01", validTo, "Europe/Madrid", null, listOf("Powered by tests"),
    )

    private fun manager(allowed: Boolean = true, offline: Boolean = false, now: LocalDate = today): TransitDataManager {
        val policy = DefaultNetworkPolicy(
            if (allowed) listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = true)) else emptyList(),
            offlineMode = offline,
        )
        return TransitDataManager(File(tmp, "transit"), policy, { now }, allowInsecure = true)
    }

    @Test
    fun `a verified download is installed and can be opened`() {
        files["/transit-test.umti"] = indexBytes
        val m = manager()
        assertNull(m.installedSha("test"))
        assertNull(m.download(asset()))
        assertEquals(sha(indexBytes), m.installedSha("test"))
        val info = m.installed().single()
        assertEquals("Testville", info.city)
        assertEquals("Europe/Madrid", info.timezone)
        assertEquals("2026-10-31", info.validTo)
        assertEquals(listOf("Powered by tests"), info.attribution)
        assertEquals(sha(indexBytes), info.sha256)
        val index = assertNotNull(m.open("test"))
        assertEquals(Fixtures.index().describe(), index.describe())
        m.delete("test")
        assertFalse(m.isInstalled("test"))
        assertNull(m.open("test"))
        assertTrue(m.installed().isEmpty())
    }

    @Test
    fun `a wrong hash is rejected and nothing is installed`() {
        files["/transit-test.umti"] = indexBytes
        val m = manager()
        assertEquals(TransitFailure.INTEGRITY, m.download(asset(sha = "0".repeat(64))))
        assertFalse(m.isInstalled("test"))
        assertFalse(File(tmp, "transit/test.umti.part").exists())
    }

    @Test
    fun `a file that matches the hash but is not an index is rejected`() {
        val junk = "this is not an index".toByteArray()
        files["/transit-test.umti"] = junk
        val m = manager()
        assertEquals(TransitFailure.INVALID_DATA, m.download(asset(bytes = junk)))
        assertFalse(m.isInstalled("test"))
    }

    @Test
    fun `offline mode never touches the network`() {
        files["/transit-test.umti"] = indexBytes
        assertEquals(TransitFailure.OFFLINE_MODE, manager(offline = true).download(asset()))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a host that is not whitelisted is refused`() {
        files["/transit-test.umti"] = indexBytes
        assertEquals(TransitFailure.NOT_ALLOWED, manager(allowed = false).download(asset()))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `an entry whose validity already ended is not downloaded`() {
        files["/transit-test.umti"] = indexBytes
        assertEquals(TransitFailure.EXPIRED, manager().download(asset(validTo = "2026-10-13")))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `an index that expired in the file itself is refused after download`() {
        // catalog claims it is still valid, the file's own calendar (ends 2026-10-31) says otherwise
        files["/transit-test.umti"] = indexBytes
        assertEquals(TransitFailure.EXPIRED, manager(now = LocalDate.parse("2026-11-20")).download(asset(validTo = "2026-12-31")))
        assertFalse(manager().isInstalled("test"))
    }

    @Test
    fun `a missing file is a network failure and a damaged installed file reads as absent`() {
        val m = manager()
        assertEquals(TransitFailure.NETWORK, m.download(asset()))
        File(tmp, "transit").mkdirs()
        File(tmp, "transit/test.umti").writeText("garbage")
        assertNull(m.open("test"))
    }
}
