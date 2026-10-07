package com.qtekfun.ultimatemaps.core.geo.link

import com.qtekfun.ultimatemaps.core.geo.LatLon

/** Geohash decoding (used by Waze `/ul/h<geohash>` links). Returns the centre of the cell. */
internal object Geohash {
    private const val ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun decode(hash: String): LatLon? {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var evenBit = true
        for (ch in hash.lowercase()) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            for (bit in 4 downTo 0) {
                val set = (idx shr bit) and 1 == 1
                if (evenBit) {
                    val mid = (lonMin + lonMax) / 2
                    if (set) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (set) latMin = mid else latMax = mid
                }
                evenBit = !evenBit
            }
        }
        return LatLon.ofOrNull((latMin + latMax) / 2, (lonMin + lonMax) / 2)
    }
}
