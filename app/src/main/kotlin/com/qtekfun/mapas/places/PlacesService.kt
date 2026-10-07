package com.qtekfun.mapas.places

import com.qtekfun.mapas.core.data.GeoDataTransfer
import com.qtekfun.mapas.core.data.ImportResult
import com.qtekfun.mapas.core.data.Place
import com.qtekfun.mapas.core.data.PlaceList
import com.qtekfun.mapas.core.data.PlacesRepository
import com.qtekfun.mapas.core.data.TakeoutImport
import com.qtekfun.mapas.core.data.TakeoutSummary
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.distanceTo
import com.qtekfun.mapas.core.geo.io.GpxExporter
import com.qtekfun.mapas.core.geo.io.KmlExporter
import com.qtekfun.mapas.core.search.SearchResult
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import kotlin.math.roundToLong

/** What the place card shows: a search result, or a saved place re-opened from a list. */
data class PlaceInfo(val name: String, val point: LatLon, val address: String? = null, val category: String? = null)

fun SearchResult.toPlaceInfo() = PlaceInfo(name, point, address, category)

/** A row of a list screen. [distanceMeters] is null when the position of reference is unknown. */
data class PlaceRow(val place: Place, val distanceMeters: Double?)

enum class GeoFormat(val extension: String, val mime: String) {
    GPX("gpx", "application/gpx+xml"),
    KML("kml", "application/vnd.google-earth.kml+xml"),
    KMZ("kmz", "application/vnd.google-earth.kmz");

    companion object {
        /** Decides by file extension first, then by content (a ZIP is KMZ; text with `<gpx` or `<kml`). */
        fun detect(fileName: String?, head: ByteArray): GeoFormat? {
            when (fileName?.substringAfterLast('.', "")?.lowercase()) {
                "gpx" -> return GPX
                "kml" -> return KML
                "kmz" -> return KMZ
            }
            if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return KMZ
            val text = String(head, Charsets.ISO_8859_1).lowercase()
            return when {
                "<gpx" in text -> GPX
                "<kml" in text -> KML
                else -> null
            }
        }
    }
}

/** `geo:` URI for sharing a place (RF-11 round trip: our own parser reads it back). */
object GeoShare {
    fun uri(point: LatLon, name: String?): String {
        val coords = "%.6f,%.6f".format(java.util.Locale.ROOT, point.lat, point.lon)
        val label = name?.replace('(', '[')?.replace(')', ']')?.trim().orEmpty()
        return if (label.isEmpty()) "geo:$coords" else "geo:0,0?q=$coords(${URLEncoder.encode(label, "UTF-8").replace("+", "%20")})"
    }
}

/** Human distance: whole metres under 1 km, kilometres with one decimal under 10 km, whole kilometres above. */
fun formatDistance(meters: Double, decimalComma: Boolean = false): String {
    val m = meters.coerceAtLeast(0.0)
    if (m < 995) return "${m.roundToLong()} m"
    val km = if (m < 9950) "%.1f".format(java.util.Locale.ROOT, m / 1000) else (m / 1000).roundToLong().toString()
    return (if (decimalComma) km.replace('.', ',') else km) + " km"
}

/** Keeps the id of the default list ("Favorites") across launches. */
interface LongSetting {
    fun get(): Long?
    fun set(value: Long)
}

/** The default list is created on demand with the localized [name]; if the user deleted it, it is created again. */
class DefaultList(private val repo: PlacesRepository, private val setting: LongSetting, private val name: () -> String) {
    fun ensure(): PlaceList {
        setting.get()?.let { id -> repo.getList(id)?.let { return it } }
        val id = repo.addList(name())
        setting.set(id)
        return repo.getList(id)!!
    }
}

/** Result of [PlacesService.importAny]: a GPX/KML/KMZ import, or a Google Takeout export (it makes its own lists). */
sealed interface ImportOutcome {
    data class Geo(val result: ImportResult) : ImportOutcome
    data class Takeout(val summary: TakeoutSummary) : ImportOutcome
}

sealed interface SaveOutcome {
    data class Saved(val placeId: Long, val listName: String, val created: Boolean) : SaveOutcome
}

/**
 * Blocking operations on saved places, lists and files. Run off the main thread. Never logs names or coordinates.
 */
class PlacesService(private val repo: PlacesRepository, private val defaultList: DefaultList) {
    private val transfer = GeoDataTransfer(repo)

    /** Id of the saved place that matches [info] (same name, within a few metres), or null. */
    fun savedId(info: PlaceInfo): Long? =
        repo.places(query = info.name).firstOrNull {
            it.name.equals(info.name, ignoreCase = true) && it.point.distanceTo(info.point) < SAME_PLACE_METERS
        }?.id

    fun save(info: PlaceInfo): SaveOutcome.Saved = repo.transaction {
        val list = defaultList.ensure()
        val r = repo.addPlaceIfNew(info.name, info.point, notes = info.address)
        repo.addToList(list.id, r.id)
        SaveOutcome.Saved(r.id, list.name, r.created)
    }

    fun unsave(placeId: Long): Boolean = repo.deletePlace(placeId)

    fun lists(): List<PlaceList> {
        defaultList.ensure()
        return repo.lists()
    }

    fun createList(name: String): Long? = name.trim().takeIf { it.isNotEmpty() }?.let { repo.addList(it) }

    fun deleteList(id: Long): Boolean = repo.deleteList(id)

    fun removeFromList(listId: Long, placeId: Long) = repo.removeFromList(listId, placeId)

    /** Places of [listId] (all when null) matching [query]; by distance from [near] or by name. */
    fun rows(listId: Long?, query: String?, near: LatLon?, byDistance: Boolean): List<PlaceRow> =
        repo.places(listId = listId, query = query?.takeIf { it.isNotBlank() }, near = near.takeIf { byDistance })
            .map { PlaceRow(it, near?.let { n -> it.point.distanceTo(n) }) }

    fun import(
        input: InputStream, fileName: String?, intoList: Long?,
    ): ImportResult? {
        val bytes = input.use { readBounded(it, MAX_IMPORT_BYTES + 1) }
        if (bytes.size > MAX_IMPORT_BYTES) return null
        val format = GeoFormat.detect(fileName, bytes.copyOf(minOf(bytes.size, 512))) ?: return null
        val target = intoList ?: defaultList.ensure().id
        return when (format) {
            GeoFormat.GPX -> transfer.importGpx(bytes.inputStream(), target)
            GeoFormat.KML -> transfer.importKml(bytes.inputStream(), target)
            GeoFormat.KMZ -> transfer.importKmz(bytes.inputStream(), target)
        }
    }

    /**
     * Imports whatever the user picked: GPX/KML/KMZ as before, or a Google Takeout export (a `.csv`, a `.json` or a
     * ZIP without KML inside). Null when the file is too big, unreadable or of no known format.
     */
    fun importAny(input: InputStream, fileName: String?, intoList: Long?): ImportOutcome? {
        val bytes = input.use { readBounded(it, MAX_IMPORT_BYTES + 1) }
        if (bytes.size > MAX_IMPORT_BYTES) return null
        if (isTakeoutFile(fileName, bytes)) {
            val summary = try {
                val takeout = TakeoutImport(repo)
                if (bytes.looksLikeZip()) takeout.importZip(bytes.inputStream()) else takeout.importFile(bytes.inputStream(), fileName)
            } catch (e: com.qtekfun.mapas.core.geo.io.GeoImportException) {
                return null
            }
            return ImportOutcome.Takeout(summary)
        }
        return import(bytes.inputStream(), fileName, intoList)?.let { ImportOutcome.Geo(it) }
    }

    /** Writes [listId] (or every place and track when null) in [format] and returns the number of places written. */
    fun export(format: GeoFormat, listId: Long?, out: OutputStream): Int {
        val exporter = if (format == GeoFormat.GPX) GpxExporter else KmlExporter
        transfer.export(exporter, out, listId)
        return repo.places(listId = listId).size
    }

    private fun ByteArray.looksLikeZip() = size >= 2 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()

    private fun isTakeoutFile(fileName: String?, bytes: ByteArray): Boolean =
        when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "csv", "json", "geojson" -> true
            "kmz", "gpx", "kml" -> false
            else -> bytes.looksLikeZip() && !zipHasKml(bytes)
        }

    /** A KMZ holds a `.kml`; a Takeout archive holds CSV/JSON files instead. */
    private fun zipHasKml(bytes: ByteArray): Boolean = try {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.any { it.name.endsWith(".kml", ignoreCase = true) }
        }
    } catch (e: java.io.IOException) {
        false
    }

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val SAME_PLACE_METERS = 5.0
        const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
    }
}
