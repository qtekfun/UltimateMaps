package com.qtekfun.mapas.core.data

import com.qtekfun.mapas.core.geo.io.GeoDocument
import com.qtekfun.mapas.core.geo.io.GeoImportException
import com.qtekfun.mapas.core.geo.io.TakeoutImporter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** What happened to one list of a Takeout export; [noCoordinates] places could not be put on the map and were skipped. */
data class TakeoutListResult(val listName: String, val added: Int, val duplicate: Int, val noCoordinates: Int)

/**
 * Result of a Takeout import. [filesIgnored] are archive entries that are not saved-place data (photos, reviews,
 * other products); [filesFailed] looked like place data but could not be parsed.
 */
data class TakeoutSummary(
    val lists: List<TakeoutListResult>,
    val filesIgnored: Int,
    val filesFailed: Int,
) {
    val placesAdded get() = lists.sumOf { it.added }
    val placesDuplicate get() = lists.sumOf { it.duplicate }
    val noCoordinates get() = lists.sumOf { it.noCoordinates }

    /** True when nothing usable was found (not a Takeout export, or every file failed). */
    val isEmpty get() = lists.isEmpty()
}

/**
 * Imports a Google Takeout "Saved places" export into lists and places, fully offline (RF-09, D2).
 *
 * Layouts handled. NOT VERIFIED against a current real export (the format drifts and varies with the account
 * language); the rules are deliberately tolerant:
 *  - `Saved/<List name>.csv` (columns Title, Note, URL, Tags, Comment): one list per file, named after the file.
 *  - `Maps (your places)/Saved Places.json` and `Labeled places.json`: GeoJSON FeatureCollections, one list per file.
 *  - Any other `.csv` or `.json` entry is tried with the same parsers; entries that do not parse as place data, and
 *    anything whose file name mentions "review", are counted in [TakeoutSummary.filesIgnored] or
 *    [TakeoutSummary.filesFailed].
 * Rows without coordinates (a Takeout CSV carries only a place link, which usually has none) are skipped and counted,
 * never guessed. Names and coordinates are never logged.
 */
class TakeoutImport(private val repo: PlacesRepository) {

    /** Imports a ZIP archive of a whole export. Throws [GeoImportException] if the ZIP is unreadable or too large. */
    fun importZip(input: InputStream): TakeoutSummary {
        val docs = ArrayList<Pair<String, GeoDocument>>()
        var ignored = 0
        var failed = 0
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val kind = kindOf(entry.name)
                    if (kind == null) {
                        ignored++
                        continue
                    }
                    val bytes = readBounded(zip, MAX_ENTRY_BYTES)
                    total += bytes.size
                    if (bytes.size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw GeoImportException("Archive too large")
                    val doc = parse(kind, bytes)
                    if (doc == null) failed++ else docs += listName(entry.name) to doc
                }
            }
        } catch (e: IOException) {
            throw GeoImportException("Cannot read archive: ${e.message}", e)
        }
        return store(docs, ignored, failed)
    }

    /** Imports one CSV or GeoJSON file of an export; [fileName] decides the format and names the list. */
    fun importFile(input: InputStream, fileName: String?): TakeoutSummary {
        val name = fileName ?: DEFAULT_LIST
        val kind = kindOf(name) ?: Kind.JSON
        val bytes = readBounded(input, MAX_ENTRY_BYTES)
        if (bytes.size > MAX_ENTRY_BYTES) throw GeoImportException("File too large")
        val doc = parse(kind, bytes) ?: return TakeoutSummary(emptyList(), 0, 1)
        return store(listOf(listName(name) to doc), 0, 0)
    }

    private fun store(docs: List<Pair<String, GeoDocument>>, ignored: Int, failed: Int): TakeoutSummary =
        repo.transaction {
            val results = ArrayList<TakeoutListResult>()
            for ((name, doc) in docs) {
                val listId = repo.lists().firstOrNull { it.name == name }?.id ?: repo.addList(name)
                var added = 0
                var dup = 0
                var none = doc.skipped
                for (p in doc.places) {
                    val point = p.point
                    if (point == null) {
                        none++
                        continue
                    }
                    val r = repo.addPlaceIfNew(p.name ?: DEFAULT_NAME, point, notes = p.description)
                    if (r.created) added++ else dup++
                    repo.addToList(listId, r.id)
                }
                results += TakeoutListResult(name, added, dup, none)
            }
            TakeoutSummary(results, ignored, failed)
        }

    private enum class Kind { CSV, JSON }

    private fun kindOf(path: String): Kind? {
        val lower = path.lowercase()
        if ("review" in lower.substringAfterLast('/')) return null
        return when (lower.substringAfterLast('.', "")) {
            "csv" -> Kind.CSV
            "json", "geojson" -> Kind.JSON
            else -> null
        }
    }

    private fun parse(kind: Kind, bytes: ByteArray): GeoDocument? = try {
        when (kind) {
            Kind.CSV -> TakeoutImporter.parseListCsv(bytes.inputStream())
            Kind.JSON -> TakeoutImporter.parseSavedPlacesGeoJson(bytes.inputStream())
        }
    } catch (e: GeoImportException) {
        null
    }

    /** The list is named after the file (without folder and extension). */
    private fun listName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().ifEmpty { DEFAULT_LIST }

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val limit = max + 1
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val DEFAULT_NAME = "(unnamed)"
        const val DEFAULT_LIST = "Saved places"

        /** Safety limits against hostile or accidental huge archives, not tuned to real exports. */
        const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
