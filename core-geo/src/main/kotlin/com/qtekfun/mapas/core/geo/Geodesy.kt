package com.qtekfun.mapas.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle (haversine) distance in metres. */
fun LatLon.distanceTo(other: LatLon): Double {
    val p1 = Math.toRadians(lat)
    val p2 = Math.toRadians(other.lat)
    val dp = p2 - p1
    val dl = Math.toRadians(other.lon - lon)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
