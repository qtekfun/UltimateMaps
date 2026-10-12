package com.qtekfun.ultimatemaps.core.geo.link

import com.qtekfun.ultimatemaps.core.geo.CoordinateQuery

/** A map link found in text another app shared, with the place name that came with it (when there was one). */
data class SharedLink(val url: String, val name: String?)

/**
 * Reads the text of an Android share (`ACTION_SEND`, `text/plain`) and finds the map link in it. Google Maps shares
 * `Name\nAddress\nhttps://maps.app.goo.gl/...` or just the URL; a browser shares `Page title\nURL`; some apps share bare
 * coordinates. Pure and offline: it only finds and cleans text, it never resolves a short link.
 */
object SharedTextParser {
    private val urlToken = Regex("""(?i)\b(?:https?://|geo:)[^\s<>"'`]+""")
    private const val TRAILING = ".,;:!?)]}>"
    private const val MAX_NAME = 120
    private const val MAX_COORDINATE_TEXT = 80

    /** The first link of [text] that [MapLinkParser] understands, or the coordinates when the whole text is a position. */
    fun extract(text: String?): SharedLink? {
        if (text.isNullOrBlank()) return null
        for (match in urlToken.findAll(text)) {
            var url = match.value
            // Sentence punctuation after the link is not part of it; a ")" closes a "(Label)" of the link itself.
            while (url.isNotEmpty() && url.last() in TRAILING && !(url.last() == ')' && '(' in url)) url = url.dropLast(1)
            if (url.isEmpty() || MapLinkParser.parse(url) is MapLink.Unrecognized) continue
            return SharedLink(url, nameBefore(text, match))
        }
        val whole = text.trim()
        if (whole.length <= MAX_COORDINATE_TEXT && '\n' !in whole) {
            val parsed = CoordinateQuery.parse(whole, null)
            if (parsed != null && (parsed.kind == CoordinateQuery.Kind.DECIMAL || parsed.kind == CoordinateQuery.Kind.DMS)) {
                return SharedLink("geo:${parsed.point.lat},${parsed.point.lon}", null)
            }
        }
        return null
    }

    /** The place name that precedes the link: the first line that is not a link, or the words before it on its own line. */
    private fun nameBefore(text: String, match: MatchResult): String? {
        val before = text.substring(0, match.range.first)
        val line = before.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && urlToken.find(it) == null }
            ?: return null
        return line.trim(' ', '-', ':', '|', '–', '—').take(MAX_NAME).ifBlank { null }
    }
}
