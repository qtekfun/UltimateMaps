package com.qtekfun.ultimatemaps.link

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.link.MapLink
import com.qtekfun.ultimatemaps.core.geo.link.MapLinkParser

/** What the UI should do with an incoming map link. Never involves the network (RF-11, RF-12). */
sealed interface LinkOutcome {
    /** Centre the map on [point] and drop a pin. */
    data class ShowPlace(val point: LatLon, val zoom: Double?, val label: String?) : LinkOutcome

    /** A text search: offline search is not implemented yet, so only warn (and move to [near] if known). */
    data class Search(val query: String, val near: LatLon?, val zoom: Double?) : LinkOutcome

    /** Shortened link: resolving it needs the network and is off by default, so only warn. */
    data object ShortLinkNotResolved : LinkOutcome

    data object Unrecognized : LinkOutcome
}

/** The pin the map must show after this outcome: the place, or none (never the pin of a previous link). */
fun LinkOutcome.pinPoint(): LatLon? = (this as? LinkOutcome.ShowPlace)?.point

/**
 * Interprets a link with [MapLinkParser]. Short links are never resolved here: that path stays disabled
 * (it would have to be authorised by `NetworkPolicy` with `SHORT_LINK_RESOLVE` and opted into by the user),
 * so no connection is attempted and nothing is logged as one.
 */
object LinkHandler {
    fun handle(uri: String?): LinkOutcome {
        if (uri.isNullOrBlank()) return LinkOutcome.Unrecognized
        return when (val link = MapLinkParser.parse(uri)) {
            is MapLink.Coordinates -> LinkOutcome.ShowPlace(link.point, link.zoom, link.label)
            is MapLink.TextSearch -> LinkOutcome.Search(link.query, link.near, link.zoom)
            is MapLink.Route -> fromRoute(link)
            is MapLink.ShortLink -> LinkOutcome.ShortLinkNotResolved
            is MapLink.Unrecognized -> LinkOutcome.Unrecognized
        }
    }

    // Routing is a later phase: show the destination if the link has one.
    private fun fromRoute(r: MapLink.Route): LinkOutcome {
        val dest = r.destination
        return when {
            dest?.point != null -> LinkOutcome.ShowPlace(dest.point!!, null, dest.text)
            !dest?.text.isNullOrBlank() -> LinkOutcome.Search(dest.text!!, r.origin?.point, null)
            else -> LinkOutcome.Unrecognized
        }
    }
}
