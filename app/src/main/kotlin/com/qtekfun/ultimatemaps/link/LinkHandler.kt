package com.qtekfun.ultimatemaps.link

import com.qtekfun.ultimatemaps.core.geo.CoordinateQuery
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.link.MapLink
import com.qtekfun.ultimatemaps.core.geo.link.MapLinkParser
import com.qtekfun.ultimatemaps.core.geo.link.SharedTextParser
import com.qtekfun.ultimatemaps.places.PlaceInfo

/** What the UI should do with an incoming map link. Never involves the network (RF-11, RF-12). */
sealed interface LinkOutcome {
    /** Centre the map on [point] and drop a pin. */
    data class ShowPlace(val point: LatLon, val zoom: Double?, val label: String?) : LinkOutcome

    /** A text search: the UI runs it through the search box's coordinator (after moving to [near] if known). */
    data class Search(val query: String, val near: LatLon?, val zoom: Double?) : LinkOutcome

    /** Shortened link: resolving it needs the network and is off by default, so only warn. */
    data object ShortLinkNotResolved : LinkOutcome

    /**
     * Shortened link that a flavor allowed to resolve it (play) expands through `NetworkPolicy` and then runs again through
     * [LinkHandler.classify]. [name] is the place name that came with a shared text.
     */
    data class ShortLink(val url: String, val name: String?) : LinkOutcome

    data object Unrecognized : LinkOutcome
}

/** The place card for a link's point: the link's label, or its coordinates when it has none (never a blank name). */
fun LinkOutcome.ShowPlace.toPlaceInfo(): PlaceInfo =
    PlaceInfo(label?.takeIf { it.isNotBlank() } ?: CoordinateQuery.formatDecimal(point), point)

/** The pin the map must show after this outcome: the place, or none (never the pin of a previous link). */
fun LinkOutcome.pinPoint(): LatLon? = (this as? LinkOutcome.ShowPlace)?.point

/**
 * Interprets a link with [MapLinkParser]. Short links are never resolved here: that path stays disabled
 * (it would have to be authorised by `NetworkPolicy` with `SHORT_LINK_RESOLVE` and opted into by the user),
 * so no connection is attempted and nothing is logged as one.
 */
object LinkHandler {
    fun handle(uri: String?): LinkOutcome =
        when (val outcome = classify(uri, null)) {
            is LinkOutcome.ShortLink -> LinkOutcome.ShortLinkNotResolved
            else -> outcome
        }

    /** A text another app shared (`ACTION_SEND`): "Name\nURL", just the URL, or coordinates. Never touches the network. */
    fun handleShared(text: String?): LinkOutcome {
        val shared = SharedTextParser.extract(text) ?: return LinkOutcome.Unrecognized
        return classify(shared.url, shared.name)
    }

    /**
     * Like [handle], but a short link comes back as [LinkOutcome.ShortLink] so a caller that may expand it can, and
     * [name] (the place name of a shared text) labels a point that the link itself does not name.
     */
    fun classify(uri: String?, name: String?): LinkOutcome {
        if (uri.isNullOrBlank()) return LinkOutcome.Unrecognized
        return when (val link = MapLinkParser.parse(uri)) {
            is MapLink.Coordinates -> LinkOutcome.ShowPlace(link.point, link.zoom, link.label?.takeIf { it.isNotBlank() } ?: name)
            is MapLink.TextSearch -> LinkOutcome.Search(link.query, link.near, link.zoom)
            is MapLink.Route -> fromRoute(link)
            is MapLink.ShortLink -> LinkOutcome.ShortLink(link.url, name)
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
