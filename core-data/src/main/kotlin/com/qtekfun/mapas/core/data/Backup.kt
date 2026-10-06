package com.qtekfun.mapas.core.data

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.io.PathKind
import com.qtekfun.mapas.core.geo.io.TrackPoint
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class RestoreMode {
    /** Adds what is missing, skipping places/tracks that already exist. Lists with the same name are reused. */
    MERGE,

    /** Wipes the database first. */
    REPLACE,
}

/**
 * Full backup (places, lists with membership, tracks) as a ZIP with one JSON entry, [ENTRY]. The JSON has a
 * `format` version. No credentials are included (WebDAV settings are not part of this data set).
 */
class BackupService(private val repo: PlacesRepository) {

    fun write(out: OutputStream) {
        val doc = repo.transaction { snapshot() }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY))
            zip.write(JSON.encodeToString(BackupDoc.serializer(), doc).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun restore(input: InputStream, mode: RestoreMode = RestoreMode.MERGE): ImportResult {
        val doc = readDoc(input)
        return repo.transaction {
            if (mode == RestoreMode.REPLACE) repo.clearAll()
            apply(doc)
        }
    }

    private fun snapshot(): BackupDoc {
        val lists = repo.lists()
        val places = repo.places()
        val members = lists.map { l -> repo.places(listId = l.id).map { it.id }.toSet() }
        return BackupDoc(
            format = FORMAT,
            places = places.map { PlaceDto(it.id, it.name, it.point.lat, it.point.lon, it.notes, it.icon, it.color, it.createdAt) },
            lists = lists.mapIndexed { i, l -> ListDto(l.name, l.color, l.icon, l.notes, members[i].sorted()) },
            tracks = repo.tracks().mapNotNull { repo.track(it.id) }.map { t ->
                TrackDto(
                    t.info.name, t.info.kind.name, t.info.notes, t.info.color, t.info.createdAt,
                    t.segments.map { seg -> seg.map { PointDto(it.point.lat, it.point.lon, it.elevation, it.timeMillis) } },
                )
            },
        )
    }

    private fun readDoc(input: InputStream): BackupDoc {
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: throw BackupException("not a backup: $ENTRY is missing")
                    if (e.name == ENTRY) {
                        val text = zip.readNBytes(MAX_JSON_BYTES + 1)
                        if (text.size > MAX_JSON_BYTES) throw BackupException("backup too large")
                        val doc = JSON.decodeFromString(BackupDoc.serializer(), String(text, Charsets.UTF_8))
                        if (doc.format != FORMAT) throw BackupException("unsupported backup format ${doc.format}")
                        return doc
                    }
                }
            }
        } catch (e: SerializationException) {
            throw BackupException("corrupt backup: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("corrupt backup: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw BackupException("cannot read backup: ${e.message}", e)
        }
    }

    private fun apply(doc: BackupDoc): ImportResult {
        var pa = 0; var pd = 0; var ta = 0; var td = 0
        val idMap = HashMap<Long, Long>()
        for (p in doc.places) {
            val pt = LatLon.ofOrNull(p.lat, p.lon) ?: throw BackupException("corrupt backup: invalid coordinates")
            val r = repo.addPlaceIfNew(p.name, pt, p.notes, p.icon, p.color)
            if (r.created) pa++ else pd++
            idMap[p.id] = r.id
        }
        val existing = repo.lists().associateBy { it.name }
        for (l in doc.lists) {
            val listId = existing[l.name]?.id ?: repo.addList(l.name, l.color, l.icon, l.notes)
            for (old in l.places) idMap[old]?.let { repo.addToList(listId, it) }
        }
        for (t in doc.tracks) {
            val kind = runCatching { PathKind.valueOf(t.kind) }.getOrElse { throw BackupException("corrupt backup: track kind") }
            val segs = t.segments.map { seg ->
                seg.map { TrackPoint(LatLon.ofOrNull(it.lat, it.lon) ?: throw BackupException("corrupt backup: invalid coordinates"), it.ele, it.t) }
            }
            val r = repo.addTrackIfNew(t.name, kind, segs)
            if (r.created) {
                // addTrackIfNew stores without notes/color: restore them on the new row.
                repo.updateTrack(TrackInfo(r.id, t.name, kind, t.notes, t.color))
                ta++
            } else td++
        }
        return ImportResult(pa, pd, ta, td, 0)
    }

    private companion object {
        const val ENTRY = "mapas-backup.json"
        const val FORMAT = 1
        const val MAX_JSON_BYTES = 512 * 1024 * 1024
        val JSON = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }
    }
}

@Serializable
internal class BackupDoc(val format: Int, val places: List<PlaceDto>, val lists: List<ListDto>, val tracks: List<TrackDto>)

@Serializable
internal class PlaceDto(
    val id: Long, val name: String, val lat: Double, val lon: Double,
    val notes: String? = null, val icon: String? = null, val color: Int? = null, val created: Long = 0,
)

@Serializable
internal class ListDto(
    val name: String, val color: Int? = null, val icon: String? = null, val notes: String? = null,
    val places: List<Long> = emptyList(),
)

@Serializable
internal class TrackDto(
    val name: String, val kind: String, val notes: String? = null, val color: Int? = null, val created: Long = 0,
    val segments: List<List<PointDto>>,
)

@Serializable
internal class PointDto(val lat: Double, val lon: Double, val ele: Double? = null, val t: Long? = null)
