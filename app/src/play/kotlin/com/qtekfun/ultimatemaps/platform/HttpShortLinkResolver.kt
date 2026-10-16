package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.net.NetworkDecision
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.link.ShortLinkResolver
import com.qtekfun.ultimatemaps.link.ShortLinkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder

/** One request that is not allowed to follow redirects: the status code and the `Location` header. */
fun interface RedirectProbe {
    fun probe(url: String): Reply

    class Reply(val code: Int, val location: String?)
}

/**
 * `play` flavor: expands a shortened Google Maps link by asking the short-link host where it redirects, without fetching the
 * target. Every request is authorised by [policy] under [ConnectionPurpose.SHORT_LINK_RESOLVE] first (offline mode and the
 * whitelist apply), is HTTPS only, has no cookies, no body and sends no application identifier: the only data that leaves the
 * phone is the short URL itself (plus the connection's own address, as any request).
 */
class HttpShortLinkResolver(
    private val policy: NetworkPolicy,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val probe: RedirectProbe = JdkRedirectProbe,
) : ShortLinkResolver {

    override suspend fun expand(shortUrl: String): ShortLinkResult = withContext(io) { expandBlocking(shortUrl) }

    private fun expandBlocking(shortUrl: String): ShortLinkResult {
        var current = shortUrl
        repeat(MAX_HOPS) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return ShortLinkResult.Failed
            val host = uri.host?.lowercase() ?: return ShortLinkResult.Failed
            // Only a short-link host is ever asked; anything else is the answer, not somewhere to connect to.
            if (uri.scheme?.lowercase() != "https" || host !in SHORT_HOSTS) return ShortLinkResult.Failed
            when (val d = policy.authorize(host, ConnectionPurpose.SHORT_LINK_RESOLVE)) {
                NetworkDecision.Allowed -> Unit
                is NetworkDecision.Denied ->
                    return if (d.reason == DenyReason.OFFLINE_MODE) ShortLinkResult.Offline else ShortLinkResult.Unavailable
            }
            val reply = try {
                probe.probe(current)
            } catch (_: IOException) {
                return ShortLinkResult.Failed
            }
            val location = reply.location
            if (reply.code !in 300..399 || location.isNullOrBlank()) return ShortLinkResult.Failed
            val next = runCatching { uri.resolve(location).toString() }.getOrNull() ?: return ShortLinkResult.Failed
            val nextHost = runCatching { URI(next).host?.lowercase() }.getOrNull() ?: return ShortLinkResult.Failed
            when {
                nextHost in SHORT_HOSTS -> current = next
                nextHost.startsWith("consent.google.") -> return unwrapConsent(next)
                else -> return ShortLinkResult.Expanded(next)
            }
        }
        return ShortLinkResult.Failed
    }

    /** `https://consent.google.com/...?continue=<target>`: the target is what the link means. */
    private fun unwrapConsent(consentUrl: String): ShortLinkResult {
        val raw = consentUrl.substringAfter('?', "").split('&').firstOrNull { it.startsWith("continue=") }
            ?.removePrefix("continue=") ?: return ShortLinkResult.Failed
        val target = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return ShortLinkResult.Failed
        return if (target.startsWith("https://")) ShortLinkResult.Expanded(target) else ShortLinkResult.Failed
    }

    companion object {
        val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "www.goo.gl")
        private const val MAX_HOPS = 4
    }
}

private const val GENERIC_USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile"

/** The real probe: a plain GET that does not follow redirects and reads no body. */
private object JdkRedirectProbe : RedirectProbe {
    override fun probe(url: String): RedirectProbe.Reply {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.requestMethod = "GET"
            // The same generic agent as the other third-party reads: no app name, version or device model.
            c.setRequestProperty("User-Agent", GENERIC_USER_AGENT)
            return RedirectProbe.Reply(c.responseCode, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }
}
