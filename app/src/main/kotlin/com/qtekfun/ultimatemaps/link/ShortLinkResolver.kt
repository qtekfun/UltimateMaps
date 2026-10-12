package com.qtekfun.ultimatemaps.link

/** What expanding a shortened map link gave. */
sealed interface ShortLinkResult {
    /** The address the short link points to (a long map link, not fetched). */
    data class Expanded(val url: String) : ShortLinkResult

    /** This build never resolves short links (the `foss` flavor), or the connection is not whitelisted. Nothing was sent. */
    data object Unavailable : ShortLinkResult

    /** Offline mode is on. Nothing was sent. */
    data object Offline : ShortLinkResult

    /** The request failed or the answer was not a redirect to somewhere useful. */
    data object Failed : ShortLinkResult
}

/**
 * Turns `https://maps.app.goo.gl/...` into the long link it redirects to. The only request that is made carries the short URL
 * and nothing else (no position, no query text), goes through `NetworkPolicy` and never follows the redirect into the final
 * Google page. The `foss` flavor has [NoShortLinkResolver]; the `play` flavor an HTTP one (`PlatformServices`).
 */
interface ShortLinkResolver {
    suspend fun expand(shortUrl: String): ShortLinkResult
}

/** Never resolves anything and never connects. */
object NoShortLinkResolver : ShortLinkResolver {
    override suspend fun expand(shortUrl: String): ShortLinkResult = ShortLinkResult.Unavailable
}
