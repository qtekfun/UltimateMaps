package com.qtekfun.mapas.places

import com.qtekfun.mapas.core.geo.LatLon
import java.util.Locale

/**
 * The text shared from a place card: the name, a `geo:` URI (opens in any maps app) and an OpenStreetMap link (opens in
 * a browser). It is built on the device and handed to the system share sheet; nothing is sent by this app and no
 * server is involved.
 */
object PlaceShare {
    private const val OSM_ZOOM = 17

    /** `https://www.openstreetmap.org/?mlat=..&mlon=..#map=17/lat/lon`, which shows a marker at the point. */
    fun osmLink(point: LatLon): String {
        val lat = "%.6f".format(Locale.ROOT, point.lat)
        val lon = "%.6f".format(Locale.ROOT, point.lon)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=$OSM_ZOOM/$lat/$lon"
    }

    fun text(info: PlaceInfo): String =
        listOf(info.name.trim(), GeoShare.uri(info.point, info.name), osmLink(info.point)).filter { it.isNotEmpty() }.joinToString("\n")
}
