package com.qtekfun.ultimatemaps.core.fuel

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

/** Why a download failed. Stable: the settings screen maps each one to a message. */
enum class FuelFailure { OFFLINE_MODE, NOT_ALLOWED, NETWORK, TIMEOUT, SERVER, TOO_LARGE, INVALID_DATA }

/** An IOException so that it passes untouched through the parser when thrown from the capped stream. */
class FuelException(val failure: FuelFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/** The purpose under which the fuel host is listed in [NetworkPolicy] (there is no dedicated purpose in `:core-net`). */
val FUEL_PURPOSE = ConnectionPurpose.OTHER

/**
 * Downloads ONE file per fuel: `<base>EstacionesTerrestres/FiltroProducto/<IDProducto>`. The request names a fuel,
 * never a province, a municipality or a coordinate, so the server cannot learn where the user is. Every hop
 * (including redirects) is first authorized by the [NetworkPolicy]; https only unless [allowInsecure] (tests); the
 * body is read as a stream capped at [maxBytes]; connect, read and total time are bounded.
 */
class FuelClient(
    private val policy: NetworkPolicy,
    private val allowInsecure: Boolean = false,
    private val maxBytes: Long = 20L shl 20,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val totalTimeoutMs: Long = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun urlFor(baseUrl: String, fuel: FuelType): String {
        val id = fuel.sourceProductId ?: throw FuelException(FuelFailure.INVALID_DATA, "${fuel.id} has no source id")
        val base = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        return "${base}EstacionesTerrestres/FiltroProducto/$id"
    }

    fun fetch(baseUrl: String, fuel: FuelType): FuelFeed {
        val deadline = clock() + totalTimeoutMs
        var current = urlFor(baseUrl, fuel)
        repeat(MAX_REDIRECTS + 1) {
            val uri = try { URI.create(current) } catch (e: IllegalArgumentException) {
                throw FuelException(FuelFailure.NOT_ALLOWED, "bad URL", e)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && !(allowInsecure && scheme == "http")) {
                throw FuelException(FuelFailure.NOT_ALLOWED, "insecure or unsupported scheme: $scheme")
            }
            val host = uri.host ?: throw FuelException(FuelFailure.NOT_ALLOWED, "no host")
            val decision = policy.authorize(host, FUEL_PURPOSE)
            if (decision is NetworkDecision.Denied) {
                throw FuelException(
                    if (decision.reason == DenyReason.OFFLINE_MODE) FuelFailure.OFFLINE_MODE else FuelFailure.NOT_ALLOWED,
                    "not allowed: ${decision.reason}",
                )
            }
            val c = try { uri.toURL().openConnection() as HttpURLConnection } catch (e: IOException) {
                throw FuelException(FuelFailure.NETWORK, "cannot open connection", e)
            }
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = readTimeoutMs
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: throw FuelException(FuelFailure.NETWORK, "redirect without Location")
                        current = uri.resolve(loc).toString()
                    }
                    code == 200 -> {
                        if (c.contentLengthLong > maxBytes) throw FuelException(FuelFailure.TOO_LARGE, "body larger than $maxBytes bytes")
                        return readFeed(c.inputStream, deadline, fuel)
                    }
                    code in 500..599 -> throw FuelException(FuelFailure.SERVER, "HTTP $code")
                    else -> throw FuelException(FuelFailure.NETWORK, "HTTP $code")
                }
            } catch (e: FuelException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw FuelException(FuelFailure.TIMEOUT, "timeout", e)
            } catch (e: IOException) {
                throw FuelException(FuelFailure.NETWORK, e.message ?: "I/O error", e)
            } finally {
                c.disconnect()
            }
        }
        throw FuelException(FuelFailure.NETWORK, "too many redirects")
    }

    private fun readFeed(raw: InputStream, deadline: Long, fuel: FuelType): FuelFeed {
        val capped = CappedStream(raw, maxBytes, deadline, clock)
        try {
            return FuelFeedParser.parse(capped, fuel)
        } catch (e: FuelParseException) {
            // A body cut short by the connection is a network problem, not a format one; either way nothing is kept.
            throw FuelException(FuelFailure.INVALID_DATA, e.message ?: "invalid data", e)
        }
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
                if (total > max) throw FuelException(FuelFailure.TOO_LARGE, "body larger than $max bytes")
            }
            if (clock() > deadline) throw FuelException(FuelFailure.TIMEOUT, "download took too long")
        }

        override fun read(): Int = super.read().also { if (it >= 0) account(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { account(it) }
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}
