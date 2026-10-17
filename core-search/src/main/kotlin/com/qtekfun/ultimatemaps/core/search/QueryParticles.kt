package com.qtekfun.ultimatemaps.core.search

import java.text.Normalizer

/**
 * Makes searches that contain small linking words ("de", "del", "la", "of"...) fast without losing the order that the full text
 * would give. The offline engine reads and intersects the whole list of places for every such word, so `plaza de espana`
 * took about 9 s on the Pixel 8 while `plaza espana` took 0.85 s, `calle de alcala` 8.4 s against 0.77 s for `alcala` and
 * `puerta del sol` 3.6 s against 0.78 s for `puerta sol` (`docs/phase2/speed-plan.md`, section 7). So the engine is asked for the
 * text without those words and the answer is put in the order of the typed phrase. Pure.
 */
object QueryParticles {
    /** Linking words of the app's languages; one letter words (a, e, i, o, y) are not here because they are also names and letters. */
    private val particles = setOf(
        "de", "del", "la", "las", "el", "los", // es
        "les", "els", // ca
        "do", "da", "dos", "das", // gl, pt
        "du", "des", "le", // fr
        "di", "della", "dei", "delle", "il", "gli", "lo", // it
        "der", "die", "den", "dem", "und", // de
        "the", "of", "and", // en
    )

    /**
     * [query] without its linking words, or null when it has none to remove or nothing but them would stay (then the typed text
     * is searched as it is). At least one remaining word has two or more letters.
     */
    fun withoutParticles(query: String): String? {
        val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val kept = words.filter { fold(it).trim(',', '.', ';') !in particles }
        if (kept.size == words.size) return null
        if (kept.none { fold(it).count(Char::isLetterOrDigit) >= 2 }) return null
        return kept.joinToString(" ")
    }

    /**
     * Puts first the [results] whose name contains the typed phrase (ignoring case and accents), the others after them, each
     * group in the engine's order. [query] is the text as typed, linking words included.
     */
    fun rerank(query: String, results: List<SearchResult>): List<SearchResult> {
        val phrase = fold(query).split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        if (phrase.isEmpty() || results.size < 2) return results
        val (exact, rest) = results.partition { fold(it.name).split(WHITESPACE).filter { w -> w.isNotEmpty() }.joinToString(" ").contains(phrase) }
        return exact + rest
    }

    private val WHITESPACE = Regex("""\s+""")
    private val ACCENTS = Regex("""\p{Mn}+""")

    private fun fold(text: String): String = ACCENTS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
}
