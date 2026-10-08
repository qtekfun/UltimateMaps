package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.net.NetworkDecision
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.zip.GZIPInputStream

/** Why a download failed. Stable: the settings screen maps each one to a message. */
enum class DownloadFailure { OFFLINE_MODE, NOT_ALLOWED, NETWORK, TIMEOUT, SERVER, TOO_LARGE, INVALID_DATA, NO_CATALOG }

/** An IOException so that it passes untouched through a parser when thrown from the capped stream. */
class DownloadException(
    val failure: DownloadFailure,
    message: String,
    cause: Throwable? = null,
    /** The HTTP status when the server answered with one this class does not accept (a 4xx), else null. */
    val httpStatus: Int? = null,
) : IOException(message, cause)

/**
 * One GET of a fixed URL with the app's network rules: every hop (redirects included) is first authorized by the
 * [NetworkPolicy] under [purpose]; https only unless [allowInsecure] (tests); no cookies, no body, no query built from
 * user data (the caller passes a URL that never contains a position); the body is read as a stream capped at
 * [maxBytes] AFTER decompression; connect, read and total time are bounded.
 */
class BoundedHttp(
    private val policy: NetworkPolicy,
    private val purpose: ConnectionPurpose,
    private val allowInsecure: Boolean = false,
    private val maxBytes: Long = 32L shl 20,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val totalTimeoutMs: Long = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Sent as `User-Agent` when set; null leaves the platform's default. Never carries an app or user identifier. */
    private val userAgent: String? = null,
) {
    fun <T> get(url: String, accept: String, headers: Map<String, String> = emptyMap(), reader: (InputStream) -> T): T {
        val deadline = clock() + totalTimeoutMs
        val firstHost = runCatching { URI.create(url).host?.lowercase() }.getOrNull()
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val uri = try { URI.create(current) } catch (e: IllegalArgumentException) {
                throw DownloadException(DownloadFailure.NOT_ALLOWED, "bad URL", e)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && !(allowInsecure && scheme == "http")) {
                throw DownloadException(DownloadFailure.NOT_ALLOWED, "insecure or unsupported scheme: $scheme")
            }
            val host = uri.host ?: throw DownloadException(DownloadFailure.NOT_ALLOWED, "no host")
            val decision = policy.authorize(host, purpose)
            if (decision is NetworkDecision.Denied) {
                throw DownloadException(
                    if (decision.reason == DenyReason.OFFLINE_MODE) DownloadFailure.OFFLINE_MODE else DownloadFailure.NOT_ALLOWED,
                    "not allowed: ${decision.reason}",
                )
            }
            val c = try { uri.toURL().openConnection() as HttpURLConnection } catch (e: IOException) {
                throw DownloadException(DownloadFailure.NETWORK, "cannot open connection", e)
            }
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = readTimeoutMs
                c.setRequestProperty("Accept", accept)
                userAgent?.let { c.setRequestProperty("User-Agent", it) }
                // Extra headers (an API key) go only to the host first asked for, never to a host a redirect points to.
                if (headers.isNotEmpty() && host.lowercase() == firstHost) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.setRequestProperty("Accept-Encoding", "gzip")
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: throw DownloadException(DownloadFailure.NETWORK, "redirect without Location")
                        current = uri.resolve(loc).toString()
                    }
                    code == 200 -> {
                        if (c.contentLengthLong > maxBytes && c.contentEncoding.isNullOrEmpty()) {
                            throw DownloadException(DownloadFailure.TOO_LARGE, "body larger than $maxBytes bytes")
                        }
                        var stream: InputStream = c.inputStream
                        if (c.contentEncoding.equals("gzip", ignoreCase = true)) stream = GZIPInputStream(stream)
                        return try {
                            reader(CappedStream(stream, maxBytes, deadline, clock))
                        } catch (e: DownloadException) {
                            throw e
                        } catch (e: SocketTimeoutException) {
                            throw DownloadException(DownloadFailure.TIMEOUT, "timeout", e)
                        } catch (e: IOException) {
                            throw DownloadException(DownloadFailure.INVALID_DATA, e.message ?: "invalid data", e)
                        }
                    }
                    code in 500..599 -> throw DownloadException(DownloadFailure.SERVER, "HTTP $code")
                    else -> throw DownloadException(DownloadFailure.NETWORK, "HTTP $code", httpStatus = code)
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw DownloadException(DownloadFailure.TIMEOUT, "timeout", e)
            } catch (e: IOException) {
                throw DownloadException(DownloadFailure.NETWORK, e.message ?: "I/O error", e)
            } finally {
                c.disconnect()
            }
        }
        throw DownloadException(DownloadFailure.NETWORK, "too many redirects")
    }

    private class CappedStream(
        input: InputStream,
        private val max: Long,
        private val deadline: Long,
        private val clock: () -> Long,
    ) : FilterInputStream(input) {
        private var total = 0L

        private fun account(n: Int) {
            if (n > 0) {
                total += n
                if (total > max) throw DownloadException(DownloadFailure.TOO_LARGE, "body larger than $max bytes")
            }
            if (clock() > deadline) throw DownloadException(DownloadFailure.TIMEOUT, "download took too long")
        }

        override fun read(): Int = super.read().also { if (it >= 0) account(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { account(it) }
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}
