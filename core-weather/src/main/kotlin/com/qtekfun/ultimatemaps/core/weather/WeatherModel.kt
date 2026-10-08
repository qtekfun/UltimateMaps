package com.qtekfun.ultimatemaps.core.weather

/** How serious a warning is. Order matters: later is more serious. AEMET's colours: amarillo, naranja, rojo. */
enum class AlertLevel { YELLOW, ORANGE, RED }

/**
 * One closed ring of a warning area as `lat0, lon0, lat1, lon1, ...` (the closing vertex is not repeated). A point is inside
 * by the even-odd rule.
 */
class WarningPolygon(val coords: DoubleArray) {
    init {
        require(coords.size >= 6 && coords.size % 2 == 0) { "a polygon needs at least three vertices" }
    }

    val size: Int get() = coords.size / 2
    val south: Double
    val north: Double
    val west: Double
    val east: Double

    init {
        var s = Double.MAX_VALUE
        var n = -Double.MAX_VALUE
        var w = Double.MAX_VALUE
        var e = -Double.MAX_VALUE
        for (i in 0 until coords.size / 2) {
            s = minOf(s, coords[2 * i]); n = maxOf(n, coords[2 * i])
            w = minOf(w, coords[2 * i + 1]); e = maxOf(e, coords[2 * i + 1])
        }
        south = s; north = n; west = w; east = e
    }

    fun intersectsBox(s: Double, w: Double, n: Double, e: Double): Boolean = w <= east && e >= west && s <= north && n >= south

    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < south || lat > north || lon < west || lon > east) return false
        var inside = false
        var j = size - 1
        for (i in 0 until size) {
            val yi = coords[2 * i]
            val yj = coords[2 * j]
            if ((yi > lat) != (yj > lat)) {
                val xAt = coords[2 * i + 1] + (lat - yi) / (yj - yi) * (coords[2 * j + 1] - coords[2 * i + 1])
                if (lon < xAt) inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * A weather warning for one area, in one language. [polygons] is empty when the feed gave no geometry for the area; such a
 * warning is still listed (by [areaDesc]) but never matches a position or a route.
 */
class WeatherWarning(
    val id: String,
    val event: String,
    val level: AlertLevel,
    val onsetMillis: Long?,
    val expiresMillis: Long?,
    val sentMillis: Long?,
    val areaDesc: String,
    val headline: String,
    val description: String,
    val instruction: String,
    val polygons: List<WarningPolygon>,
) {
    /** True when the warning has not ended and starts no later than [lookaheadMillis] from [now]. */
    fun isActive(now: Long, lookaheadMillis: Long = 0L): Boolean =
        (expiresMillis == null || expiresMillis > now) && (onsetMillis == null || onsetMillis <= now + lookaheadMillis)

    /** True when the warning is in effect right now (started or no start time, and not ended). */
    fun isInEffect(now: Long): Boolean = isActive(now, 0L)

    fun covers(lat: Double, lon: Double): Boolean = polygons.any { it.contains(lat, lon) }
}
