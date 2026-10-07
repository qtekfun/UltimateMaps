package com.qtekfun.ultimatemaps.core.geo

/** A WGS84 position in decimal degrees. Construction fails for out-of-range or non-finite values. */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon.isFinite() && lon in -180.0..180.0) { "longitude out of range: $lon" }
    }

    companion object {
        /** Returns null instead of throwing when the values are not a valid position. */
        fun ofOrNull(lat: Double, lon: Double): LatLon? =
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }
}
