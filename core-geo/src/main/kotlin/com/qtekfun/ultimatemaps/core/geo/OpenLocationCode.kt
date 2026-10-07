package com.qtekfun.ultimatemaps.core.geo

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Open Location Code ("Plus Codes"), implemented offline from the public specification and reference algorithm
 * (Apache-2.0, https://github.com/google/open-location-code). This file is an independent Kotlin implementation (no
 * code copied); it uses the integer arithmetic of the current reference implementations so the results match the
 * published test vectors (see `OpenLocationCodeTest`).
 */
object OpenLocationCode {
    const val SEPARATOR = '+'
    const val PADDING = '0'
    const val ALPHABET = "23456789CFGHJMPQRVWX"
    const val SEPARATOR_POSITION = 8
    const val PAIR_CODE_LENGTH = 10
    const val MAX_CODE_LENGTH = 15
    const val DEFAULT_CODE_LENGTH = 10

    private const val BASE = 20
    private const val GRID_ROWS = 5
    private const val GRID_COLUMNS = 4

    // Finest cell (15 digits) in integer units per degree: 20^3 * 5^5 and 20^3 * 4^5.
    private const val FINAL_LAT_PRECISION = 8000L * 3125L
    private const val FINAL_LNG_PRECISION = 8000L * 1024L

    /** The area of a code; [center] is its middle point. */
    data class CodeArea(val latLo: Double, val lonLo: Double, val latHi: Double, val lonHi: Double, val codeLength: Int) {
        val center: LatLon
            get() = LatLon(((latLo + latHi) / 2).coerceIn(-90.0, 90.0), ((lonLo + lonHi) / 2).coerceIn(-180.0, 180.0))
    }

    /**
     * Encodes [point] with [codeLength] digits: 2, 4, 6, 8, 10 (the default, about 14 m) or 11 to 15. An odd length
     * below 10 is lowered by one and a length above 15 becomes 15, as in the reference implementations.
     */
    fun encode(point: LatLon, codeLength: Int = DEFAULT_CODE_LENGTH): String = encode(point.lat, point.lon, codeLength)

    /** Same as above for raw degrees: latitude is clipped to the poles and longitude wraps around. */
    fun encode(lat: Double, lon: Double, codeLength: Int = DEFAULT_CODE_LENGTH): String {
        require(lat.isFinite() && lon.isFinite()) { "coordinates must be finite" }
        require(codeLength >= 2) { "code length must be at least 2" }
        val length = when {
            codeLength > MAX_CODE_LENGTH -> MAX_CODE_LENGTH
            codeLength < PAIR_CODE_LENGTH && codeLength % 2 == 1 -> codeLength - 1
            else -> codeLength
        }
        var latVal = scaled(lat.coerceIn(-90.0, 90.0) + 90.0, FINAL_LAT_PRECISION)
        var lngVal = scaled(normalizeLongitude(lon) + 180.0, FINAL_LNG_PRECISION)
        // 90 degrees north belongs to the cell below it.
        latVal = latVal.coerceIn(0L, 180L * FINAL_LAT_PRECISION - 1)
        lngVal = lngVal.coerceIn(0L, 360L * FINAL_LNG_PRECISION - 1)

        val digits = CharArray(MAX_CODE_LENGTH)
        for (i in MAX_CODE_LENGTH - 1 downTo PAIR_CODE_LENGTH) {
            digits[i] = ALPHABET[((latVal % GRID_ROWS) * GRID_COLUMNS + (lngVal % GRID_COLUMNS)).toInt()]
            latVal /= GRID_ROWS
            lngVal /= GRID_COLUMNS
        }
        for (i in PAIR_CODE_LENGTH - 2 downTo 0 step 2) {
            digits[i + 1] = ALPHABET[(lngVal % BASE).toInt()]
            digits[i] = ALPHABET[(latVal % BASE).toInt()]
            latVal /= BASE
            lngVal /= BASE
        }
        val sb = StringBuilder()
        if (length <= SEPARATOR_POSITION) {
            for (i in 0 until length) sb.append(digits[i])
            while (sb.length < SEPARATOR_POSITION) sb.append(PADDING)
            sb.append(SEPARATOR)
        } else {
            for (i in 0 until SEPARATOR_POSITION) sb.append(digits[i])
            sb.append(SEPARATOR)
            for (i in SEPARATOR_POSITION until length) sb.append(digits[i])
        }
        return sb.toString()
    }

    /** True for a well-formed full or short code (case-insensitive). */
    fun isValid(code: String): Boolean {
        val sep = code.indexOf(SEPARATOR)
        if (sep < 0 || sep != code.lastIndexOf(SEPARATOR)) return false
        if (code.length == 1) return false
        if (sep > SEPARATOR_POSITION || sep % 2 == 1) return false
        if (code.length - sep - 1 == 1) return false // a single digit after the separator
        val pad = code.indexOf(PADDING)
        if (pad >= 0) {
            if (sep < SEPARATOR_POSITION) return false // a short code cannot be padded
            if (pad == 0) return false
            val tail = code.substring(pad)
            val run = tail.takeWhile { it == PADDING }
            if (run.length % 2 == 1 || run.length > SEPARATOR_POSITION - 2) return false
            if (tail.substring(run.length) != SEPARATOR.toString()) return false // only the separator may follow
        }
        for (ch in code) {
            if (ch != SEPARATOR && ch != PADDING && ALPHABET.indexOf(ch.uppercaseChar()) < 0) return false
        }
        if (sep == SEPARATOR_POSITION) {
            // The first digits cannot exceed the latitude and longitude ranges.
            if (ALPHABET.indexOf(code[0].uppercaseChar()) * BASE >= 180) return false
            if (ALPHABET.indexOf(code[1].uppercaseChar()) * BASE >= 360) return false
        }
        return true
    }

    fun isShort(code: String): Boolean = isValid(code) && code.indexOf(SEPARATOR) < SEPARATOR_POSITION

    fun isFull(code: String): Boolean = isValid(code) && code.indexOf(SEPARATOR) == SEPARATOR_POSITION

    /** Decodes a full code; throws [IllegalArgumentException] for anything else. */
    fun decode(code: String): CodeArea {
        require(isFull(code)) { "not a full Open Location Code" }
        val clean = code.replace(SEPARATOR.toString(), "").replace(PADDING.toString(), "").uppercase().take(MAX_CODE_LENGTH)
        var latVal = -90L * FINAL_LAT_PRECISION
        var lngVal = -180L * FINAL_LNG_PRECISION
        // The first pair digit is worth 20 degrees: start one step above it (400 = 20 * 20).
        var latPlace = FINAL_LAT_PRECISION * BASE * BASE
        var lngPlace = FINAL_LNG_PRECISION * BASE * BASE
        var i = 0
        while (i < minOf(clean.length, PAIR_CODE_LENGTH)) {
            latPlace /= BASE
            lngPlace /= BASE
            latVal += ALPHABET.indexOf(clean[i]) * latPlace
            lngVal += ALPHABET.indexOf(clean[i + 1]) * lngPlace
            i += 2
        }
        for (j in PAIR_CODE_LENGTH until clean.length) {
            latPlace /= GRID_ROWS
            lngPlace /= GRID_COLUMNS
            val idx = ALPHABET.indexOf(clean[j])
            latVal += (idx / GRID_COLUMNS) * latPlace
            lngVal += (idx % GRID_COLUMNS) * lngPlace
        }
        return CodeArea(
            latVal.toDouble() / FINAL_LAT_PRECISION, lngVal.toDouble() / FINAL_LNG_PRECISION,
            (latVal + latPlace).toDouble() / FINAL_LAT_PRECISION, (lngVal + lngPlace).toDouble() / FINAL_LNG_PRECISION,
            clean.length,
        )
    }

    /**
     * Turns a short code (e.g. `9G8F+6W`) into the full code whose centre is nearest to [reference]; a full code is
     * returned upper-cased. Throws [IllegalArgumentException] for an invalid code.
     */
    fun recoverNearest(code: String, reference: LatLon): String {
        require(isValid(code)) { "invalid Open Location Code" }
        if (!isShort(code)) return code.uppercase()
        val refLat = reference.lat
        val refLon = normalizeLongitude(reference.lon)
        val paddingLength = SEPARATOR_POSITION - code.indexOf(SEPARATOR)
        // The missing leading digits span `resolution` degrees; the answer lies within half of it of the reference.
        val resolution = BASE.toDouble().pow(2 - paddingLength / 2)
        val half = resolution / 2.0
        val prefix = encode(reference).take(paddingLength)
        val area = decode(prefix + code.uppercase())
        var lat = area.center.lat
        var lon = area.center.lon
        if (refLat + half < lat && lat - resolution >= -90.0) lat -= resolution
        else if (refLat - half > lat && lat + resolution <= 90.0) lat += resolution
        if (refLon + half < lon) lon -= resolution else if (refLon - half > lon) lon += resolution
        return encode(LatLon(lat.coerceIn(-90.0, 90.0), normalizeLongitude(lon)), area.codeLength)
    }

    /** Scales [degrees] to integer cell units; the 1e6 rounding step removes floating point noise (as the reference does). */
    private fun scaled(degrees: Double, precision: Long): Long = floor((degrees * precision * 1e6).roundToLong() / 1e6).toLong()

    private fun normalizeLongitude(lon: Double): Double {
        var l = lon
        while (l < -180.0) l += 360.0
        while (l >= 180.0) l -= 360.0
        return l
    }
}
