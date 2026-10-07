package com.qtekfun.mapas.core.geo

import java.util.Locale

/**
 * Recognises a search-box text that is a position instead of a place name, entirely offline:
 * decimal degrees (`40.4168, -3.7038`, `40.4168N 3.7038W`, `40,4168 -3,7038`), degrees-minutes-seconds
 * (`40°26'46"N 3°42'14"W`, `40 26 46 N 3 42 14 W`) and Open Location Codes (`8FVC2222+22`, or a short code
 * such as `9QCJ+2VX`, see [parse] for the rules).
 *
 * The whole text must be the position (any other word means it is a normal search), latitude comes first unless
 * hemisphere letters say otherwise, and an out-of-range value is rejected, never swapped or clamped.
 */
object CoordinateQuery {
    enum class Kind { DECIMAL, DMS, PLUS_CODE, PLUS_CODE_SHORT }

    /** [plusCode] is the full upper-case code for the Plus Code kinds (a short code completed), null for degrees. */
    data class Parsed(val point: LatLon, val kind: Kind, val plusCode: String? = null)

    /**
     * [reference] (the map centre) is only used to complete a short Plus Code; without it short codes are not
     * recognised. A short code needs at least four characters before the `+`, so short texts like `+34` (a phone
     * prefix) are left to the normal search. A locality after a code (`9G8F+6W Madrid`) is not supported.
     */
    fun parse(text: String, reference: LatLon? = null): Parsed? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return null
        plusCode(t, reference)?.let { return it }
        return degrees(t)
    }

    private fun plusCode(t: String, reference: LatLon?): Parsed? {
        if (t.indexOf('+') < 0 || t.any { it.isWhitespace() }) return null
        if (!OpenLocationCode.isValid(t)) return null
        if (OpenLocationCode.isFull(t)) return Parsed(OpenLocationCode.decode(t).center, Kind.PLUS_CODE, t.uppercase())
        if (reference == null || t.indexOf('+') < MIN_SHORT_PREFIX) return null
        val full = OpenLocationCode.recoverNearest(t, reference)
        return Parsed(OpenLocationCode.decode(full).center, Kind.PLUS_CODE_SHORT, full)
    }

    // ---- degrees ---------------------------------------------------------------------------------------------

    private sealed interface Token {
        /** [negative] is the sign written in the text (it survives for "-0"). */
        data class Num(val value: Double, val negative: Boolean, val hasFraction: Boolean) : Token
        data class Letter(val hemisphere: Char) : Token // N, S, E or W (O is west)
        data object Sep : Token // comma or semicolon between the two coordinates
        data object Mark : Token // degree, minute or second sign
    }

    private class Group(val nums: List<Token.Num>, val hemisphere: Char?)

    private fun degrees(raw: String): Parsed? {
        val s = raw.replace('\u2212', '-').replace('\u2013', '-') // minus and en dash
        val tokens = tokenize(s) ?: return null
        if (tokens.none { it is Token.Num }) return null
        val lettered = tokens.any { it is Token.Letter }
        val marked = tokens.any { it is Token.Mark }
        val groups = (if (lettered) byLetters(tokens) else byNumbers(tokens)) ?: return null
        if (groups.size != 2) return null
        // Two bare numbers without any decimal point (like "12 34") are far more likely something else.
        if (!lettered && !marked && groups.all { it.nums.size == 1 } && groups.none { g -> g.nums.any { it.hasFraction } }) return null
        var lat = groups[0]
        var lon = groups[1]
        val h0 = lat.hemisphere
        val h1 = lon.hemisphere
        if (h0 != null && h1 != null) {
            if (isLat(h0) == isLat(h1)) return null
            if (!isLat(h0)) { val tmp = lat; lat = lon; lon = tmp } // longitude written first
        }
        val la = value(lat, 90.0) ?: return null
        val lo = value(lon, 180.0) ?: return null
        val point = LatLon.ofOrNull(la, lo) ?: return null
        return Parsed(point, if (groups.any { it.nums.size > 1 }) Kind.DMS else Kind.DECIMAL)
    }

    private fun isLat(h: Char) = h == 'N' || h == 'S'

    /** Degrees (+ minutes + seconds) of a group as a signed decimal; null when something is out of range. */
    private fun value(g: Group, max: Double): Double? {
        val n = g.nums
        if (n.isEmpty() || n.size > 3) return null
        // Fractions are only allowed on the last part, and only the first part may carry a sign.
        for (i in 0 until n.size - 1) if (n[i].hasFraction) return null
        for (i in 1 until n.size) if (n[i].negative) return null
        val min = if (n.size > 1) n[1].value else 0.0
        val sec = if (n.size > 2) n[2].value else 0.0
        if (min >= 60 || sec >= 60) return null
        val v = kotlin.math.abs(n[0].value) + min / 60.0 + sec / 3600.0
        if (v > max) return null
        val negative = n[0].negative
        val south = g.hemisphere == 'S' || g.hemisphere == 'W'
        if (g.hemisphere != null && negative) return null // "-40 S" contradicts itself
        return if (south || negative) -v else v
    }

    /** Splits tokens at hemisphere letters: a leading letter starts a group, a trailing one ends it. */
    private fun byLetters(tokens: List<Token>): List<Group>? {
        val meaningful = tokens.filter { it !is Token.Mark }
        val prefixStyle = meaningful.first { it !is Token.Sep } is Token.Letter
        val groups = mutableListOf<Group>()
        var nums = mutableListOf<Token.Num>()
        var pending: Char? = null
        fun close(letter: Char?): Boolean {
            if (nums.isEmpty() || letter == null) return false
            groups += Group(nums, letter)
            nums = mutableListOf()
            pending = null
            return true
        }
        for (tok in meaningful) when (tok) {
            is Token.Num -> nums += tok
            is Token.Mark -> {}
            is Token.Sep -> if (nums.isNotEmpty() && !close(pending)) return null
            is Token.Letter -> if (prefixStyle) {
                if (nums.isNotEmpty() && !close(pending)) return null
                pending = tok.hemisphere
            } else if (!close(tok.hemisphere)) return null
        }
        if (nums.isNotEmpty() && !close(pending)) return null
        return groups
    }

    /** No letters: a separator splits the groups; otherwise 2, 4 or 6 numbers split evenly (D, D M or D M S each). */
    private fun byNumbers(tokens: List<Token>): List<Group>? {
        val sep = tokens.indexOfFirst { it is Token.Sep }
        if (sep >= 0) {
            if (tokens.count { it is Token.Sep } != 1) return null
            val left = tokens.subList(0, sep).filterIsInstance<Token.Num>()
            val right = tokens.subList(sep + 1, tokens.size).filterIsInstance<Token.Num>()
            if (left.isEmpty() || right.isEmpty()) return null
            return listOf(Group(left, null), Group(right, null))
        }
        val nums = tokens.filterIsInstance<Token.Num>()
        if (nums.size != 2 && nums.size != 4 && nums.size != 6) return null
        val half = nums.size / 2
        return listOf(Group(nums.subList(0, half), null), Group(nums.subList(half, nums.size), null))
    }

    /** Null when the text has anything that is not a number, a hemisphere letter, a separator or a degree mark. */
    private fun tokenize(s: String): List<Token>? {
        // A comma between digits is a decimal comma only when dots are absent and there are two such commas.
        val digitCommas = Regex("\\d,\\d").findAll(s).count()
        val decimalComma = !s.contains('.') && digitCommas >= 2
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == ';' -> { tokens += Token.Sep; i++ }
                c == ',' -> { tokens += Token.Sep; i++ }
                c in MARKS -> { tokens += Token.Mark; i++ }
                c == '\'' || c == '"' -> { tokens += Token.Mark; i++ }
                c.isLetter() -> {
                    val h = when (c.uppercaseChar()) {
                        'N' -> 'N'; 'S' -> 'S'; 'E' -> 'E'; 'W', 'O' -> 'W'
                        else -> return null
                    }
                    // A letter glued to another letter is a word, not a hemisphere.
                    if (i + 1 < s.length && s[i + 1].isLetter()) return null
                    if (i > 0 && s[i - 1].isLetter()) return null
                    tokens += Token.Letter(h); i++
                }
                c.isDigit() || c == '-' || c == '+' || c == '.' -> {
                    val m = numberAt(s, i, decimalComma) ?: return null
                    val text = m.replace(',', '.')
                    val v = text.toDoubleOrNull() ?: return null
                    tokens += Token.Num(v, text.startsWith("-"), text.contains('.'))
                    i += m.length
                }
                else -> return null
            }
        }
        return tokens
    }

    private fun numberAt(s: String, start: Int, decimalComma: Boolean): String? {
        var i = start
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        val digitsStart = i
        while (i < s.length && s[i].isDigit()) i++
        var digits = i - digitsStart
        if (i < s.length && (s[i] == '.' || (decimalComma && s[i] == ',')) && i + 1 < s.length && s[i + 1].isDigit()) {
            i++
            while (i < s.length && s[i].isDigit()) { i++; digits++ }
        }
        return if (digits == 0) null else s.substring(start, i)
    }

    /** Degree, minute and second signs in their usual spellings (ASCII quotes are handled apart). */
    private val MARKS = "°º˚′″’”‘“"

    private const val MAX_LENGTH = 80
    private const val MIN_SHORT_PREFIX = 4

    /** "40.41680, -3.70380": five decimals (about a metre), dot decimals, shown the same in every language. */
    fun formatDecimal(point: LatLon): String =
        "%.5f, %.5f".format(Locale.ROOT, point.lat, point.lon)
}
