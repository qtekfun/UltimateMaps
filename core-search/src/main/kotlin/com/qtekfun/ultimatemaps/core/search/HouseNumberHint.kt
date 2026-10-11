package com.qtekfun.ultimatemaps.core.search

/**
 * "You typed a house number and the map has none": a query like `calle del pensamiento 2` whose results are only streets,
 * because the map data (OpenStreetMap) has no address with that number there. The core answers numbers where the data has them
 * (`Calle Pensamiento, 2`), so the absence of any result that mentions the number is the signal. Pure.
 */
object HouseNumberHint {
    /** A number of one to four digits and an optional letter at the end of the query, after a space or a comma, optionally as "nº 2". */
    private val trailing = Regex("""(?:^|[\s,])(?:n[º°o]\.?\s*)?(\d{1,4}[a-zA-Z]?)\s*$""", RegexOption.IGNORE_CASE)

    /** Words after which a trailing number is not a house number (a road, a kilometre point, an exit, a postcode, a year...). */
    private val notHouseWords = setOf("km", "salida", "exit", "sector", "fase", "zona", "area", "área", "a", "ap", "n", "m", "r", "c", "e", "ctra", "carretera")

    /**
     * The house number typed at the end of [query] when no result of [results] mentions it, else null (no number typed, the
     * number belongs to a name that was found, or nothing was found at all).
     */
    fun missingNumber(query: String, results: List<SearchResult>): String? {
        if (results.isEmpty()) return null
        val match = trailing.find(query.trim()) ?: return null
        val number = match.groupValues[1]
        val before = query.trim().substring(0, match.range.first).trim().trimEnd(',')
        val previousWord = before.substringAfterLast(' ').lowercase().trim(',', '.')
        // "calle 30" is a street called 30; a bare number or only a number is a search for something else.
        if (before.none { it.isLetter() } || previousWord in notHouseWords) return null
        val mention = Regex("""(?:^|[\s,])${Regex.escape(number)}(?:$|[\s,])""", RegexOption.IGNORE_CASE)
        val found = results.any { mention.containsMatchIn(it.name) }
        return if (found) null else number
    }
}
