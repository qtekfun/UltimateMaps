package com.qtekfun.ultimatemaps.regions

import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.regions.AssetKind
import com.qtekfun.ultimatemaps.core.regions.CancelToken
import com.qtekfun.ultimatemaps.core.regions.DownloadCancelledException
import com.qtekfun.ultimatemaps.core.regions.HashMismatchException
import com.qtekfun.ultimatemaps.core.regions.NetworkDeniedException
import com.qtekfun.ultimatemaps.core.regions.Region
import com.qtekfun.ultimatemaps.core.regions.RegionAsset
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionDownloadsTest {
    private val hash = "a".repeat(64)

    private fun region(id: String, size: Long = 100) = Region(
        id, id, null, "1",
        mapOf(
            AssetKind.RENDER to RegionAsset("https://x/$id.pmtiles", size, hash, "$id.pmtiles"),
            AssetKind.SEARCH to RegionAsset("https://x/$id.mwm", size, hash, "$id.mwm"),
        ),
    )

    private val direct = Executor { it.run() }

    private fun failing(e: Exception): RegionDownloads {
        val d = RegionDownloads({ _, _, _ -> throw e }, direct)
        d.enqueue(region("a"))
        return d
    }

    @Test
    fun `success leaves no entry and reports the install`() {
        val installed = mutableListOf<String>()
        val d = RegionDownloads({ r, _, progress -> progress(r.totalBytes, r.totalBytes) }, direct, onInstalled = { installed += it })
        d.enqueue(region("a"))
        assertEquals(listOf("a"), installed)
        assertNull(d.state("a"))
        assertFalse(d.isActive)
    }

    @Test
    fun `every failure maps to its own reason`() {
        assertEquals(
            DownloadState.Failed(FailureReason.OFFLINE_MODE),
            failing(NetworkDeniedException("h", DenyReason.OFFLINE_MODE)).state("a"),
        )
        assertEquals(
            DownloadState.Failed(FailureReason.NOT_ALLOWED),
            failing(NetworkDeniedException("h", DenyReason.NOT_WHITELISTED)).state("a"),
        )
        assertEquals(
            DownloadState.Failed(FailureReason.NO_SPACE, 500, 20),
            failing(InsufficientSpaceException(500, 20)).state("a"),
        )
        assertEquals(DownloadState.Failed(FailureReason.CORRUPT), failing(HashMismatchException("x", "y")).state("a"))
        assertEquals(DownloadState.Failed(FailureReason.NETWORK), failing(IOException("reset")).state("a"))
    }

    @Test
    fun `a failed download can be retried and then succeeds`() {
        val calls = AtomicInteger()
        val d = RegionDownloads({ _, _, _ -> if (calls.incrementAndGet() == 1) throw IOException("x") }, direct)
        d.enqueue(region("a"))
        assertIs<DownloadState.Failed>(d.state("a"))
        d.enqueue(region("a"))
        assertNull(d.state("a"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `pause keeps progress and resume finishes`() {
        val running = CountDownLatch(1)
        val calls = AtomicInteger()
        val ex = Executors.newSingleThreadExecutor()
        val installed = CountDownLatch(1)
        val d = RegionDownloads(
            { r, cancel: CancelToken, progress ->
                if (calls.incrementAndGet() == 1) {
                    progress(40, r.totalBytes)
                    running.countDown()
                    while (!cancel.isCancelled) Thread.sleep(5)
                    throw DownloadCancelledException()
                }
                progress(r.totalBytes, r.totalBytes)
            },
            ex, onInstalled = { installed.countDown() },
        )
        try {
            d.enqueue(region("a", 100))
            assertTrue(running.await(5, TimeUnit.SECONDS))
            d.pause("a")
            assertEquals(DownloadState.Paused(40, 200), d.state("a")) // two assets of 100 bytes
            assertFalse(d.isActive)
            awaitTrue { calls.get() == 1 && d.state("a") is DownloadState.Paused }
            d.enqueue(region("a", 100)) // resume
            assertTrue(installed.await(5, TimeUnit.SECONDS))
            assertNull(d.state("a"))
            assertEquals(2, calls.get())
        } finally {
            ex.shutdownNow()
        }
    }

    @Test
    fun `regions download one at a time and a queued one can be paused before it starts`() {
        val gate = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val ex = Executors.newSingleThreadExecutor()
        val d = RegionDownloads({ r, _, _ -> order += r.id; if (r.id == "a") gate.await(5, TimeUnit.SECONDS) }, ex)
        try {
            d.enqueue(region("a"))
            d.enqueue(region("b"))
            d.enqueue(region("c"))
            awaitTrue { d.state("a") is DownloadState.Running }
            assertEquals(DownloadState.Queued, d.state("b"))
            d.pause("b")
            assertIs<DownloadState.Paused>(d.state("b"))
            gate.countDown()
            awaitTrue { !d.isActive }
            assertEquals(listOf("a", "c"), order.toList()) // b never ran
            assertIs<DownloadState.Paused>(d.state("b"))
        } finally {
            ex.shutdownNow()
        }
    }

    @Test
    fun `cancel forgets the region and clear drops a failure`() {
        val d = RegionDownloads({ _, _, _ -> throw IOException() }, direct)
        d.enqueue(region("a"))
        assertIs<DownloadState.Failed>(d.state("a"))
        d.clear("a")
        assertNull(d.state("a"))
        d.enqueue(region("a"))
        d.cancel("a")
        assertNull(d.state("a"))
    }

    private fun awaitTrue(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timeout" }
            Thread.sleep(5)
        }
    }
}
