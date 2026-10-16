package com.qtekfun.ultimatemaps.core.traffic

/** Where the TomTom Orbis Maps v2 traffic feed lives (Traffic API, Vector Flow Tiles). */
object TomTomEndpoints {
    const val HOST = "api.tomtom.com"

    /** The page where a developer account and a free key are made. */
    const val KEY_REQUEST_URL = "https://developer.tomtom.com/"

    const val ACCEPT = "application/vnd.mapbox-vector-tile"

    /** The key goes in the `TomTom-Api-Key` header, never in the URL: a URL can end up in a proxy log. */
    const val KEY_HEADER = "TomTom-Api-Key"

    fun tileUrl(tile: TileId) = "https://$HOST/maps/orbis/traffic/flow/vector/tile/${tile.z}/${tile.x}/${tile.y}?apiVersion=2"
}
