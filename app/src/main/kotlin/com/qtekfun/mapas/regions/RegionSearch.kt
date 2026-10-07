package com.qtekfun.mapas.regions

import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionCatalog
import java.text.Normalizer
import java.util.Locale

/**
 * Search over the region hierarchy. The catalog names come from CoMaps ids in English
 * (`Catalonia - Provincia de Barcelona`), so a small table of well-known Spanish exonyms is added to the
 * searchable key. Only names that exist in the published catalog are listed; nothing is guessed for the rest.
 */
object RegionSearch {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Lowercase, no accents, punctuation turned into single spaces: "Cataluña" -> "cataluna". */
    fun normalize(s: String): String =
        NON_ALNUM.replace(DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT), " ").trim()

    /** "Catalonia - Provincia de Barcelona" -> ["Catalonia", "Provincia de Barcelona"]. */
    fun segments(name: String): List<String> = name.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }

    /** Spanish names for English name segments (keys normalized). */
    internal val SPANISH: Map<String, String> = mapOf(
        // Spain: the autonomous communities and splits present in the catalog.
        "Spain" to "España", "Andalusia" to "Andalucía", "Aragon" to "Aragón",
        "Balearic Islands" to "Islas Baleares Baleares", "Basque Country" to "País Vasco Euskadi",
        "Canary Islands" to "Islas Canarias Canarias", "Castile and Leon" to "Castilla y León",
        "Castile-La Mancha" to "Castilla-La Mancha", "Catalonia" to "Cataluña",
        "Community of Madrid" to "Comunidad de Madrid", "Comunidad Foral de Navarra" to "Navarra",
        "Principado de Asturias" to "Asturias", "Region de Murcia" to "Murcia",
        "Valencian Community" to "Comunidad Valenciana",
        "West" to "Oeste", "East" to "Este", "North" to "Norte", "South" to "Sur",
        // Countries (common Spanish names).
        "Germany" to "Alemania", "France" to "Francia", "Italy" to "Italia", "United Kingdom" to "Reino Unido",
        "United States of America" to "Estados Unidos EEUU", "Netherlands" to "Países Bajos Holanda",
        "Switzerland" to "Suiza", "Belgium" to "Bélgica", "Ireland" to "Irlanda", "Morocco" to "Marruecos",
        "Mexico" to "México", "Brazil" to "Brasil", "Russian Federation" to "Rusia",
        "People's Republic of China" to "China", "Japan" to "Japón", "Greece" to "Grecia", "Poland" to "Polonia",
        "Sweden" to "Suecia", "Norway" to "Noruega", "Denmark" to "Dinamarca", "Turkey" to "Turquía",
        "Egypt" to "Egipto", "Czech Republic" to "Chequia República Checa", "Canada" to "Canadá",
        "Croatia" to "Croacia", "Hungary" to "Hungría", "Romania" to "Rumanía Rumania",
        "Ukraine" to "Ucrania", "Finland" to "Finlandia", "Algeria" to "Argelia", "Tunisia" to "Túnez",
        "Peru" to "Perú", "Panama" to "Panamá", "South Africa" to "Sudáfrica",
        "Saudi Arabia" to "Arabia Saudí", "Iceland" to "Islandia", "Lithuania" to "Lituania",
    ).mapKeys { normalize(it.key) }

    /** Searchable text of a region: every name the catalog has (any language) plus the aliases of its English segments. */
    internal fun ownKey(region: Region): String =
        normalize((listOf(ownKey(region.name)) + region.names.values).joinToString(" "))

    /** Searchable text of one node: its own name plus Spanish aliases of each name segment. */
    internal fun ownKey(name: String): String {
        val extra = segments(name).mapNotNull { SPANISH[normalize(it)] }
        return normalize((listOf(name) + extra).joinToString(" "))
    }

    class Entry(
        val region: Region,
        val ownKey: String,
        /** Normalized text of the ancestors (so "cataluna barcelona" can match a child of Catalonia). */
        val pathKey: String,
        /** "Spain › Catalonia › Provincia de Barcelona". */
        val path: String,
        val sortName: String,
        val leaves: List<Region>,
        val isGroup: Boolean,
    )

    /**
     * Precomputed once per catalog; filtering it is a linear scan over strings. Paths and sort order use the names in
     * [language] (`es`) when the catalog has them; the search matches the names of every language.
     */
    class Index(val catalog: RegionCatalog, val language: String = "en") {
        val entries: List<Entry>

        init {
            val kids = catalog.regions.filterNot { it.isBaseFile }.groupBy { it.parentId }
            val leavesOf = HashMap<String, List<Region>>()
            fun leaves(r: Region): List<Region> = leavesOf.getOrPut(r.id) {
                if (r.isDownloadable) listOf(r) else kids[r.id].orEmpty().flatMap { leaves(it) }
            }
            val out = ArrayList<Entry>(catalog.regions.size)
            fun walk(parent: Region?, parentKey: String, parentPath: List<String>) {
                for (r in kids[parent?.id].orEmpty()) {
                    val own = ownKey(r)
                    val shown = r.displayName(language)
                    val path = parentPath + segments(shown)
                    out += Entry(
                        r, own, parentKey, path.joinToString(" › "), normalize(shown),
                        leaves(r), kids[r.id].orEmpty().isNotEmpty(),
                    )
                    walk(r, (parentKey + " " + own).trim(), path)
                }
            }
            walk(null, "", emptyList())
            entries = out
        }
    }

    /**
     * Entries matching every word of [query] (substring, accent- and case-insensitive), at least one of them in the
     * node's own name. Downloadable leaves first, then groups with something to download, then the rest; within
     * each, names with a word starting like the first query word come first, then alphabetical.
     */
    fun filter(index: Index, query: String): List<Entry> {
        val tokens = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val first = tokens[0]
        return index.entries.asSequence()
            .filter { e -> tokens.all { it in e.ownKey || it in e.pathKey } && tokens.any { it in e.ownKey } }
            .sortedWith(
                compareBy<Entry>(
                    { if (it.region.isDownloadable) 0 else if (it.leaves.isNotEmpty()) 1 else 2 },
                    { if (it.ownKey.startsWith(first) || " $first" in it.ownKey) 0 else 1 },
                    { it.sortName },
                ),
            )
            .toList()
    }
}
