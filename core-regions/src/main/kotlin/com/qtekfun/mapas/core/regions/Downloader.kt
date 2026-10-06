package com.qtekfun.mapas.core.regions

import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DenyReason
import com.qtekfun.mapas.core.net.NetworkDecision
import com.qtekfun.mapas.core.net.NetworkPolicy
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class NetworkDeniedException(val host: String, val reason: DenyReason) : IOException("network denied for $host: $reason")
class HashMismatchException(val expected: String, val actual: String) : IOException("sha256 mismatch: expected $expected, got $actual")
class DownloadCancelledException : IOException("download cancelled")

/** Cooperative cancellation; the partial file is kept so the download can be resumed. */
class CancelToken {
    private val flag = AtomicBoolean(false)
    fun cancel() = flag.set(true)
    val isCancelled: Boolean get() = flag.get()
}

/**
 * Resumable HTTP downloader. Every connection (including each redirect hop) is first authorized
 * by the [NetworkPolicy] with [ConnectionPurpose.MAP_DOWNLOAD]; the policy only ever sees the host.
 * Plain http is refused unless [allowInsecure] (tests only).
 */
class ResumableDownloader(
    private val policy: NetworkPolicy,
    private val allowInsecure: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /**
     * Downloads [url] into [partial] (appending if it already has bytes), verifies size and SHA-256.
     * Returns normally only when [partial] is a complete, verified file. On a size or hash mismatch the
     * partial file is deleted and an exception is thrown. On any other failure the partial is kept.
     */
    fun download(
        url: String, partial: File, expectedSize: Long, expectedSha256: String,
        cancel: CancelToken = CancelToken(), onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        partial.parentFile?.mkdirs()
        if (partial.length() > expectedSize) partial.delete()
        if (partial.length() < expectedSize) fetch(url, partial, expectedSize, cancel, onProgress)
        val got = partial.length()
        if (got < expectedSize) throw IOException("truncated: $got of $expectedSize bytes") // partial kept for resume
        if (got > expectedSize) {
            partial.delete()
            throw IOException("size mismatch: expected $expectedSize, got $got")
        }
        val actual = sha256(partial)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            partial.delete()
            throw HashMismatchException(expectedSha256, actual)
        }
    }

    private fun fetch(url: String, partial: File, size: Long, cancel: CancelToken, onProgress: (Long, Long) -> Unit) {
        var offset = partial.length()
        val conn = open(url, offset)
        try {
            when (val code = conn.responseCode) {
                206 -> Unit // resume honoured
                200 -> { offset = 0; partial.delete() } // server ignores Range: restart
                else -> throw IOException("HTTP $code")
            }
            RandomAccessFile(partial, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        if (cancel.isCancelled) throw DownloadCancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        offset += n
                        onProgress(offset, size)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(startUrl: String, offset: Long): HttpURLConnection {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val uri = URI.create(current)
            if (uri.scheme != "https" && !(allowInsecure && uri.scheme == "http")) {
                throw IOException("insecure or unsupported scheme: ${uri.scheme}")
            }
            val host = uri.host ?: throw IOException("no host")
            val decision = policy.authorize(host, ConnectionPurpose.MAP_DOWNLOAD)
            if (decision is NetworkDecision.Denied) throw NetworkDeniedException(host, decision.reason)
            val c = uri.toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                c.disconnect()
                current = uri.resolve(loc).toString()
            } else {
                return c
            }
        }
        throw IOException("too many redirects")
    }

    companion object {
        private const val MAX_REDIRECTS = 5

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(b)
                    if (n < 0) break
                    md.update(b, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
