package com.qtekfun.ultimatemaps.core.geo.link

import com.qtekfun.ultimatemaps.core.geo.LatLon

enum class TravelMode { DRIVING, WALKING, CYCLING, TRANSIT }

/** One end of a route: coordinates, free text (to be geocoded locally), or both. */
data class RouteEndpoint(val point: LatLon? = null, val text: String? = null) {
    init {
        require(point != null || !text.isNullOrBlank()) { "endpoint needs coordinates or text" }
    }
}

/** Result of interpreting a map link. Parsing never performs network access. */
sealed class MapLink {
    /** A concrete place; [label] is the place name when the link carries one. */
    data class Coordinates(val point: LatLon, val zoom: Double? = null, val label: String? = null) : MapLink()

    /** A search by text, optionally biased towards [near]. */
    data class TextSearch(val query: String, val near: LatLon? = null, val zoom: Double? = null) : MapLink()

    /** Directions request. [navigate] is true when the link asks to start navigation right away. */
    data class Route(
        val origin: RouteEndpoint?,
        val destination: RouteEndpoint?,
        val waypoints: List<RouteEndpoint> = emptyList(),
        val mode: TravelMode? = null,
        val navigate: Boolean = false,
    ) : MapLink()

    /** A shortened link whose destination can only be known with a network request (user opt-in). */
    data class ShortLink(val url: String) : MapLink()

    /** Not a map link we understand. */
    data class Unrecognized(val input: String) : MapLink()
}
