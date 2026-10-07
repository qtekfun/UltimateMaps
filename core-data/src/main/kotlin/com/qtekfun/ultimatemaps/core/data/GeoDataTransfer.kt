package com.qtekfun.ultimatemaps.core.data

import com.qtekfun.ultimatemaps.core.geo.io.GeoDocument
import com.qtekfun.ultimatemaps.core.geo.io.GeoExporter
import com.qtekfun.ultimatemaps.core.geo.io.GeoStreamImporter
import com.qtekfun.ultimatemaps.core.geo.io.GpxImporter
import com.qtekfun.ultimatemaps.core.geo.io.ImportedPath
import com.qtekfun.ultimatemaps.core.geo.io.ImportedPlace
import com.qtekfun.ultimatemaps.core.geo.io.KmlImporter
import com.qtekfun.ultimatemaps.core.geo.io.TakeoutImporter
import java.io.InputStream
import java.io.OutputStream

/** Counts for the user; [skipped] are invalid entries and places without coordinates. */
data class ImportResult(
    val placesAdded: Int, val placesDuplicate: Int, val tracksAdded: Int, val tracksDuplicate: Int, val skipped: Int,
)

/** Connects the file importers/exporters to a [PlacesRepository] (RF-09). */
class GeoDataTransfer(private val repo: PlacesRepository) {

    /** Stores [doc]; entries already present (same name and position / same geometry) are not duplicated. */
    fun import(doc: GeoDocument, intoList: Long? = null): ImportResult = repo.transaction {
        var pa = 0; var pd = 0; var ta = 0; var td = 0; var skipped = doc.skipped
        for (p in doc.places) {
            val pt = p.point
            if (pt == null) { skipped++; continue }
            val r = repo.addPlaceIfNew(p.name ?: DEFAULT_NAME, pt, notes = p.description)
            if (r.created) pa++ else pd++
            if (intoList != null) repo.addToList(intoList, r.id)
        }
        for (path in doc.paths) {
            if (path.pointCount == 0) { skipped++; continue }
            val r = repo.addTrackIfNew(path.name ?: DEFAULT_NAME, path.kind, path.segments)
            if (r.created) ta++ else td++
        }
        ImportResult(pa, pd, ta, td, skipped)
    }

    fun importGpx(input: InputStream, intoList: Long? = null) = import(GpxImporter.parse(input), intoList)
    fun importKml(input: InputStream, intoList: Long? = null) = import(KmlImporter.parse(input), intoList)
    fun importKmz(input: InputStream, intoList: Long? = null) = import(KmlImporter.parseKmz(input), intoList)
    fun importTakeoutGeoJson(input: InputStream, intoList: Long? = null) =
        import(TakeoutImporter.parseSavedPlacesGeoJson(input), intoList)

    /** A Takeout CSV list has no coordinates, so those rows count as skipped (to be resolved later). */
    fun importTakeoutCsv(input: InputStream, intoList: Long? = null) = import(TakeoutImporter.parseListCsv(input), intoList)

    fun importWith(importer: GeoStreamImporter, input: InputStream, intoList: Long? = null) =
        import(importer.parse(input), intoList)

    /** Builds a document from a list (or all places when [listId] is null) plus, optionally, all tracks. */
    fun toDocument(listId: Long? = null, includeTracks: Boolean = listId == null): GeoDocument {
        val places = repo.places(listId = listId).map { ImportedPlace(it.point, it.name, it.notes) }
        val paths = if (includeTracks) repo.tracks().mapNotNull { repo.track(it.id) }
            .map { ImportedPath(it.info.kind, it.info.name, it.segments) } else emptyList()
        return GeoDocument(places, paths, 0)
    }

    fun export(exporter: GeoExporter, out: OutputStream, listId: Long? = null, includeTracks: Boolean = listId == null) =
        exporter.write(toDocument(listId, includeTracks), out)

    private companion object {
        const val DEFAULT_NAME = "(unnamed)"
    }
}
