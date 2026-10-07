package com.qtekfun.mapas.core.regions

import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.NetworkDecision
import com.qtekfun.mapas.core.net.NetworkPolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads and parses the region catalog (a small JSON). Like [ResumableDownloader], every connection and
 * redirect hop is first authorized by the [NetworkPolicy] (`MAP_DOWNLOAD`), https only unless [allowInsecure]
 * (tests), and the body is capped at [maxBytes] so a hostile server cannot exhaust memory.
 */
class CatalogFetcher(
    private val policy: NetworkPolicy,
    private val allowInsecure: Boolean = false,
    private val maxBytes: Int = 8 shl 20,
    private val timeoutMs: Int = 15_000,
) {
    /** Returns the raw JSON text (already validated with [RegionCatalog.parse]) and the parsed catalog. */
    fun fetch(url: String): Pair<String, RegionCatalog> {
        val text = fetchText(url)
        return text to RegionCatalog.parse(text, baseUrl = url)
    }

    private fun fetchText(startUrl: String): String {
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
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                        current = uri.resolve(loc).toString()
                    }
                    code == 200 -> return readCapped(c)
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun readCapped(c: HttpURLConnection): String {
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw IOException("catalog larger than $maxBytes bytes")
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
