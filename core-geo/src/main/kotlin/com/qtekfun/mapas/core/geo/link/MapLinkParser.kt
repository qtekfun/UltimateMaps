package com.qtekfun.mapas.core.geo.link

import com.qtekfun.mapas.core.geo.LatLon
import java.net.URLDecoder

/**
 * Interprets map links (RF-11): `geo:`, Google Maps, Apple Maps and Waze. Pure and offline: short
 * links are only detected and returned as [MapLink.ShortLink]; resolving them is the caller's
 * decision (opt-in, through NetworkPolicy).
 */
object MapLinkParser {

    fun parse(input: String): MapLink {
        val text = input.trim()
        val url = RawUrl.parse(text) ?: return MapLink.Unrecognized(input)
        val result = when (url.scheme) {
            "geo" -> parseGeo(url)
            "waze" -> parseWaze(url)
            "http", "https" -> parseWeb(url, text)
            else -> null
        }
        return result ?: MapLink.Unrecognized(input)
    }

    // ---------------------------------------------------------------- web dispatch

    private fun parseWeb(url: RawUrl, original: String): MapLink? {
        val host = url.host
        return when {
            host == "maps.app.goo.gl" -> MapLink.ShortLink(original)
            host == "goo.gl" || host == "www.goo.gl" ->
                if (url.path.startsWith("/maps")) MapLink.ShortLink(original) else null
            isGoogleMaps(host, url.path) -> parseGoogle(url)
            host == "maps.apple.com" -> parseApple(url)
            host == "waze.com" || host.endsWith(".waze.com") -> parseWaze(url)
            else -> null
        }
    }

    private val googleHost = Regex("""(^|\.)google\.[a-z]{2,3}(\.[a-z]{2})?$""")

    private fun isGoogleMaps(host: String, path: String): Boolean {
        if (!googleHost.containsMatchIn(host)) return false
        return host.startsWith("maps.google.") || path == "/maps" || path.startsWith("/maps/") ||
            path.startsWith("/maps?")
    }

    // ---------------------------------------------------------------- geo:

    private fun parseGeo(url: RawUrl): MapLink? {
        val body = url.opaque
        val coordPart = body.substringBefore(';').trim()
        val point = if (coordPart.isEmpty()) null else {
            val parts = coordPart.split(',')
            if (parts.size < 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            LatLon.ofOrNull(lat, lon) ?: return null
        }
        val zoom = url.query["z"]?.let(::parseZoom)
        val q = url.query["q"]?.takeIf { it.isNotBlank() }
        if (q != null) {
            val parsed = parseCoordinateText(q)
            if (parsed != null) return MapLink.Coordinates(parsed.first, zoom, parsed.second)
            // geo:0,0?q=text means "no position bias" by convention.
            val near = point?.takeUnless { it.lat == 0.0 && it.lon == 0.0 }
            return MapLink.TextSearch(q.trim(), near, zoom)
        }
        return point?.let { MapLink.Coordinates(it, zoom) }
    }

    // ---------------------------------------------------------------- Google

    private val dataCoords = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")

    private fun parseGoogle(url: RawUrl): MapLink? {
        val query = url.query
        val segs = url.pathSegments()
        // Drop the leading "maps" segment when present.
        val rest = if (segs.firstOrNull() == "maps") segs.drop(1) else segs
        val mode = query["travelmode"]?.let(::parseTravelMode)

        val viewport = rest.firstOrNull { it.startsWith("@") }?.let(::parseViewport)
        val dataPoint = dataCoords.find(url.rawPath)?.let {
            LatLon.ofOrNull(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
        }
        val zoom = viewport?.zoom ?: query["z"]?.let(::parseZoom)

        when (rest.firstOrNull()) {
            "dir" -> return googleDirections(rest.drop(1), query, mode)
            "place" -> {
                val name = rest.getOrNull(1)?.takeUnless { it.startsWith("@") || it.startsWith("data=") }
                val named = name?.let(::parseCoordinateText)
                val point = named?.first ?: dataPoint ?: viewport?.point
                return when {
                    point != null -> MapLink.Coordinates(point, zoom, if (named != null) named.second else name)
                    name != null -> MapLink.TextSearch(name)
                    else -> null
                }
            }
            "search" -> {
                val q = rest.getOrNull(1)?.takeUnless { it.startsWith("@") } ?: query["query"] ?: query["q"]
                return searchResult(q, viewport?.point, zoom)
            }
        }
        if (query["saddr"] != null || query["daddr"] != null) {
            return googleDirections(
                emptyList(), query, mode,
            )
        }
        val q = query["q"] ?: query["query"]
        val ll = (query["ll"] ?: query["center"])?.let(::parseCoordinateText)?.first
        if (q != null) return searchResult(q, ll ?: viewport?.point, zoom)
        if (ll != null) return MapLink.Coordinates(ll, zoom)
        if (viewport != null) return MapLink.Coordinates(viewport.point, zoom)
        return null
    }

    private fun searchResult(q: String?, near: LatLon?, zoom: Double?): MapLink? {
        if (q.isNullOrBlank()) return null
        parseCoordinateText(q)?.let { return MapLink.Coordinates(it.first, zoom, it.second) }
        return MapLink.TextSearch(q.trim(), near, zoom)
    }

    private fun googleDirections(pathParts: List<String>, query: Map<String, String>, mode: TravelMode?): MapLink? {
        val navigate = query["dir_action"] == "navigate"
        var origin: String? = query["origin"] ?: query["saddr"]
        var destination: String? = query["destination"] ?: query["daddr"]
        var waypoints: List<String> = query["waypoints"]?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
        if (origin == null && destination == null) {
            val stops = pathParts.takeWhile { !it.startsWith("@") && !it.startsWith("data=") }
                .filter { it.isNotBlank() }
            when {
                stops.isEmpty() -> return null
                stops.size == 1 -> destination = stops[0]
                else -> {
                    origin = stops.first()
                    destination = stops.last()
                    waypoints = stops.subList(1, stops.size - 1)
                }
            }
        }
        if (origin.isNullOrBlank() && destination.isNullOrBlank()) return null
        return MapLink.Route(
            origin = origin?.let(::endpoint),
            destination = destination?.let(::endpoint),
            waypoints = waypoints.mapNotNull(::endpoint),
            mode = mode,
            navigate = navigate,
        )
    }

    private fun endpoint(s: String): RouteEndpoint? {
        if (s.isBlank()) return null
        val c = parseCoordinateText(s)
        return if (c != null) RouteEndpoint(point = c.first, text = c.second) else RouteEndpoint(text = s.trim())
    }

    private class Viewport(val point: LatLon, val zoom: Double?)

    /** `@lat,lon,15z` (also `...,1500m` for satellite views, where there is no zoom level). */
    private fun parseViewport(seg: String): Viewport? {
        val parts = seg.removePrefix("@").split(',')
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val point = LatLon.ofOrNull(lat, lon) ?: return null
        val zoom = parts.getOrNull(2)?.takeIf { it.endsWith("z") }?.let(::parseZoom)
        return Viewport(point, zoom)
    }

    // ---------------------------------------------------------------- Apple

    private fun parseApple(url: RawUrl): MapLink? {
        val q = url.query
        val zoom = q["z"]?.let(::parseZoom)
        if (q["daddr"] != null || q["saddr"] != null) {
            val mode = when (q["dirflg"]) {
                "d" -> TravelMode.DRIVING
                "w" -> TravelMode.WALKING
                "r" -> TravelMode.TRANSIT
                "b" -> TravelMode.CYCLING
                else -> null
            }
            return MapLink.Route(
                origin = q["saddr"]?.let(::endpoint),
                destination = q["daddr"]?.let(::endpoint),
                mode = mode,
            )
        }
        // Newer format: /place?coordinate=lat,lon&name=...
        q["coordinate"]?.let(::parseCoordinateText)?.let {
            return MapLink.Coordinates(it.first, zoom, q["name"] ?: it.second)
        }
        val ll = q["ll"]?.let(::parseCoordinateText)?.first
        val text = q["q"]?.takeIf { it.isNotBlank() } ?: q["address"]?.takeIf { it.isNotBlank() }
            ?: q["name"]?.takeIf { it.isNotBlank() }
        if (ll != null) return MapLink.Coordinates(ll, zoom, text)
        if (text != null) {
            parseCoordinateText(text)?.let { return MapLink.Coordinates(it.first, zoom, it.second) }
            val near = (q["sll"] ?: q["near"])?.let(::parseCoordinateText)?.first
            return MapLink.TextSearch(text.trim(), near, zoom)
        }
        return null
    }

    // ---------------------------------------------------------------- Waze

    private fun parseWaze(url: RawUrl): MapLink? {
        val q = url.query
        val segs = url.pathSegments()
        // Live-map directions: /live-map/directions?to=ll.lat,lon&from=ll.lat,lon
        if (segs.firstOrNull() == "live-map") {
            val to = q["to"]?.let(::wazePlaceRef)
            val from = q["from"]?.let(::wazePlaceRef)
            if (to == null && from == null) return null
            return MapLink.Route(origin = from, destination = to, navigate = q["navigate"] == "yes")
        }
        val zoom = q["z"]?.let(::parseZoom) ?: q["zoom"]?.let(::parseZoom)
        val navigate = q["navigate"] == "yes"
        // /ul/h<geohash>
        val geohash = segs.getOrNull(1)?.takeIf { segs[0] == "ul" && it.startsWith("h") && it.length > 1 }
            ?.let { Geohash.decode(it.substring(1)) }
        val ll = q["ll"]?.let(::parseCoordinateText)?.first ?: geohash
        val text = q["q"]?.takeIf { it.isNotBlank() }
        if (navigate) {
            if (ll != null) return MapLink.Route(origin = null, destination = RouteEndpoint(ll, text), navigate = true)
            if (text != null) return MapLink.Route(origin = null, destination = RouteEndpoint(text = text.trim()), navigate = true)
            return null
        }
        if (text != null) {
            parseCoordinateText(text)?.let { return MapLink.Coordinates(it.first, zoom, it.second) }
            return MapLink.TextSearch(text.trim(), ll, zoom)
        }
        return ll?.let { MapLink.Coordinates(it, zoom) }
    }

    private fun wazePlaceRef(s: String): RouteEndpoint? {
        val ref = s.removePrefix("ll.")
        return parseCoordinateText(ref)?.let { RouteEndpoint(point = it.first) }
    }

    // ---------------------------------------------------------------- shared helpers

    private val decimalPair =
        Regex("""^\s*([+-]?\d{1,3}(?:\.\d+)?)\s*,\s*([+-]?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")
    private val dms = Regex(
        """^\s*(\d{1,3})°\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)["″]?\s*([NS])\s*[,+ ]*\s*""" +
            """(\d{1,3})°\s*(\d{1,2})['′]\s*(\d{1,2}(?:\.\d+)?)["″]?\s*([EW])\s*$""",
    )

    /** "40.4,-3.7", "40.4, -3.7 (Label)" or DMS ("40°26'46"N 79°58'56"W"). */
    internal fun parseCoordinateText(s: String): Pair<LatLon, String?>? {
        decimalPair.matchEntire(s)?.let { m ->
            val p = LatLon.ofOrNull(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) ?: return null
            return p to m.groupValues[3].ifBlank { null }
        }
        dms.matchEntire(s)?.let { m ->
            val g = m.groupValues
            var lat = g[1].toDouble() + g[2].toDouble() / 60 + g[3].toDouble() / 3600
            var lon = g[5].toDouble() + g[6].toDouble() / 60 + g[7].toDouble() / 3600
            if (g[4] == "S") lat = -lat
            if (g[8] == "W") lon = -lon
            return LatLon.ofOrNull(lat, lon)?.let { it to null }
        }
        return null
    }

    private fun parseZoom(s: String): Double? =
        s.removeSuffix("z").toDoubleOrNull()?.takeIf { it in 0.0..30.0 }

    private fun parseTravelMode(s: String): TravelMode? = when (s.lowercase()) {
        "driving", "car" -> TravelMode.DRIVING
        "walking", "walk" -> TravelMode.WALKING
        "bicycling", "cycling", "bike" -> TravelMode.CYCLING
        "transit" -> TravelMode.TRANSIT
        else -> null
    }

    /** Minimal URL split: enough for map links, tolerant of malformed percent-escapes. */
    internal class RawUrl(
        val scheme: String,
        val host: String,
        val rawPath: String,
        val query: Map<String, String>,
        val opaque: String,
    ) {
        val path: String get() = rawPath

        fun pathSegments(): List<String> =
            rawPath.split('/').filter { it.isNotEmpty() }.map { decode(it) }

        companion object {
            private val schemeRe = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(.*)$""", RegexOption.DOT_MATCHES_ALL)

            fun parse(text: String): RawUrl? {
                val m = schemeRe.matchEntire(text) ?: return null
                val scheme = m.groupValues[1].lowercase()
                var rest = m.groupValues[2].substringBefore('#')
                val rawQuery = if ('?' in rest) rest.substringAfter('?') else ""
                rest = rest.substringBefore('?')
                val query = parseQuery(rawQuery)
                if (!rest.startsWith("//")) return RawUrl(scheme, "", "", query, rest)
                val authAndPath = rest.removePrefix("//")
                val slash = authAndPath.indexOf('/')
                val authority = if (slash < 0) authAndPath else authAndPath.substring(0, slash)
                val path = if (slash < 0) "" else authAndPath.substring(slash)
                val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
                return RawUrl(scheme, host, path, query, "")
            }

            private fun parseQuery(raw: String): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                for (pair in raw.split('&')) {
                    if (pair.isEmpty()) continue
                    val k = decode(pair.substringBefore('=')).lowercase()
                    val v = decode(pair.substringAfter('=', ""))
                    out.putIfAbsent(k, v)
                }
                return out
            }

            private fun decode(s: String): String = try {
                URLDecoder.decode(s, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                s
            }
        }
    }
}
