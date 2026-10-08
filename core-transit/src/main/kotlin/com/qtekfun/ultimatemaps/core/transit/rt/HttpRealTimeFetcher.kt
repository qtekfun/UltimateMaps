package com.qtekfun.ultimatemaps.core.transit.rt

import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.net.NetworkDecision
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * The real [RealTimeFetcher]: one GET per call, first authorized by the [NetworkPolicy] under [RenfeFeeds.PURPOSE] (so it
 * shows in the local connection log and offline mode stops it). https only unless [allowInsecure] (tests); no cookies, no
 * body, no query, no identifier: the only header that says anything is a fixed, generic `User-Agent`. The body is capped at
 * [maxBytes] after decompression; connect and read times are bounded. Redirects are followed at most [MAX_REDIRECTS] times,
 * each hop authorized again.
 */
class HttpRealTimeFetcher(
    private val policy: NetworkPolicy,
    private val allowInsecure: Boolean = false,
    private val maxBytes: Int = 8 shl 20,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : RealTimeFetcher {
    override fun get(url: String): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val uri = try { URI.create(current) } catch (e: IllegalArgumentException) {
                throw RealTimeException(RealTimeFailure.NOT_ALLOWED, "bad URL", e)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && !(allowInsecure && scheme == "http")) {
                throw RealTimeException(RealTimeFailure.NOT_ALLOWED, "insecure or unsupported scheme")
            }
            val host = uri.host ?: throw RealTimeException(RealTimeFailure.NOT_ALLOWED, "no host")
            val decision = policy.authorize(host, RenfeFeeds.PURPOSE)
            if (decision is NetworkDecision.Denied) {
                throw RealTimeException(
                    if (decision.reason == DenyReason.OFFLINE_MODE) RealTimeFailure.OFFLINE else RealTimeFailure.NOT_ALLOWED,
                    "not allowed: ${decision.reason}",
                )
            }
            val c = try { uri.toURL().openConnection() as HttpURLConnection } catch (e: IOException) {
                throw RealTimeException(RealTimeFailure.NETWORK, "cannot open connection", e)
            }
            try {
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = readTimeoutMs
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Accept-Encoding", "gzip")
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: throw RealTimeException(RealTimeFailure.NETWORK, "redirect without Location")
                        current = uri.resolve(loc).toString()
                    }
                    code == 200 -> {
                        if (c.contentLengthLong > maxBytes && c.contentEncoding.isNullOrEmpty()) {
                            throw RealTimeException(RealTimeFailure.TOO_LARGE, "body too large")
                        }
                        var stream = c.inputStream
                        if (c.contentEncoding.equals("gzip", ignoreCase = true)) stream = GZIPInputStream(stream)
                        return stream.use { readCapped(it) }
                    }
                    code in 500..599 -> throw RealTimeException(RealTimeFailure.SERVER, "HTTP $code")
                    else -> throw RealTimeException(RealTimeFailure.NETWORK, "HTTP $code")
                }
            } catch (e: RealTimeException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw RealTimeException(RealTimeFailure.TIMEOUT, "timeout", e)
            } catch (e: IOException) {
                throw RealTimeException(RealTimeFailure.NETWORK, e.message ?: "I/O error", e)
            } finally {
                c.disconnect()
            }
        }
        throw RealTimeException(RealTimeFailure.NETWORK, "too many redirects")
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw RealTimeException(RealTimeFailure.TOO_LARGE, "body too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_REDIRECTS = 2

        /** The same for every user and every version: it says only that this is a map app. */
        const val USER_AGENT = "UltimateMaps"
    }
}
