package com.qtekfun.ultimatemaps.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.geo.io.PathKind
import com.qtekfun.ultimatemaps.core.geo.io.TrackPoint
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.roundToLong

/**
 * [PlacesRepository] on plain SQLite through androidx.sqlite's [SQLiteDriver] (no Room, so no annotation
 * processing is needed and the same code runs on Android and on the JVM). One connection guarded by a lock.
 * Nothing here logs names or coordinates.
 */
class SqlitePlacesRepository(driver: SQLiteDriver, fileName: String) : PlacesRepository {
    private val lock = Any()
    private val db: SQLiteConnection = driver.open(fileName)
    private var depth = 0

    init {
        db.execSQL("PRAGMA foreign_keys = ON")
        migrate()
    }

    private fun migrate() {
        val version = db.prepare("PRAGMA user_version").use { it.step(); it.getLong(0).toInt() }
        require(version <= SCHEMA_VERSION) { "database is newer ($version) than this app supports ($SCHEMA_VERSION)" }
        if (version == SCHEMA_VERSION) return
        transaction {
            if (version < 1) {
                SCHEMA_V1.forEach { db.execSQL(it) }
            }
            if (version < 2) {
                SCHEMA_V2.forEach { db.execSQL(it) }
            }
            db.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    override fun close() = synchronized(lock) { db.close() }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (depth > 0) return block() // nested: the outer transaction decides
        db.execSQL("BEGIN IMMEDIATE")
        depth++
        try {
            val r = block()
            db.execSQL("COMMIT")
            r
        } catch (e: Throwable) {
            runCatching { db.execSQL("ROLLBACK") }
            throw e
        } finally {
            depth--
        }
    }

    // ---- helpers ----

    private inline fun <T> stmt(sql: String, bind: (SQLiteStatement) -> Unit = {}, body: (SQLiteStatement) -> T): T =
        db.prepare(sql).use { s -> bind(s); body(s) }

    private fun exec(sql: String, bind: (SQLiteStatement) -> Unit = {}): Int =
        stmt(sql, bind) { s -> s.step(); db.prepare("SELECT changes()").use { c -> c.step(); c.getLong(0).toInt() } }

    private fun lastId(): Long = db.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }

    private fun SQLiteStatement.text(i: Int, v: String?) = if (v == null) bindNull(i) else bindText(i, v)
    private fun SQLiteStatement.int(i: Int, v: Int?) = if (v == null) bindNull(i) else bindLong(i, v.toLong())
    private fun SQLiteStatement.real(i: Int, v: Double?) = if (v == null) bindNull(i) else bindDouble(i, v)
    private fun SQLiteStatement.long(i: Int, v: Long?) = if (v == null) bindNull(i) else bindLong(i, v)
    private fun SQLiteStatement.textOrNull(c: Int) = if (isNull(c)) null else getText(c)
    private fun SQLiteStatement.intOrNull(c: Int) = if (isNull(c)) null else getLong(c).toInt()

    private fun SQLiteStatement.place() = Place(
        id = getLong(0), name = getText(1), point = LatLon(getDouble(2), getDouble(3)),
        notes = textOrNull(4), icon = textOrNull(5), color = intOrNull(6), createdAt = getLong(7), updatedAt = getLong(8),
    )

    private fun SQLiteStatement.list() = PlaceList(
        id = getLong(0), name = getText(1), color = intOrNull(2), icon = textOrNull(3), notes = textOrNull(4),
        placeCount = getLong(5).toInt(),
    )

    private fun SQLiteStatement.trackInfo() = TrackInfo(
        id = getLong(0), name = getText(1), kind = PathKind.valueOf(getText(2)), notes = textOrNull(3),
        color = intOrNull(4), pointCount = getLong(5).toInt(), createdAt = getLong(6),
    )

    // ---- places ----

    override fun addPlace(name: String, point: LatLon, notes: String?, icon: String?, color: Int?, createdAt: Long): Long =
        synchronized(lock) {
            exec(
                "INSERT INTO places(name,lat,lon,notes,icon,color,created_at,updated_at,dedup_key,search_text) VALUES(?,?,?,?,?,?,?,?,?,?)",
            ) { s ->
                s.bindText(1, name); s.bindDouble(2, point.lat); s.bindDouble(3, point.lon)
                s.text(4, notes); s.text(5, icon); s.int(6, color); s.bindLong(7, createdAt); s.bindLong(8, createdAt)
                s.bindText(9, placeKey(name, point)); s.bindText(10, fold("$name ${notes.orEmpty()}"))
            }
            lastId()
        }

    override fun addPlaceIfNew(name: String, point: LatLon, notes: String?, icon: String?, color: Int?): AddResult =
        synchronized(lock) {
            val existing = stmt("SELECT id FROM places WHERE dedup_key=? LIMIT 1", { it.bindText(1, placeKey(name, point)) }) {
                if (it.step()) it.getLong(0) else null
            }
            if (existing != null) AddResult(existing, false) else AddResult(addPlace(name, point, notes, icon, color), true)
        }

    override fun updatePlace(place: Place): Boolean = synchronized(lock) {
        exec(
            "UPDATE places SET name=?,lat=?,lon=?,notes=?,icon=?,color=?,updated_at=?,dedup_key=?,search_text=? WHERE id=?",
        ) { s ->
            s.bindText(1, place.name); s.bindDouble(2, place.point.lat); s.bindDouble(3, place.point.lon)
            s.text(4, place.notes); s.text(5, place.icon); s.int(6, place.color); s.bindLong(7, System.currentTimeMillis())
            s.bindText(8, placeKey(place.name, place.point)); s.bindText(9, fold("${place.name} ${place.notes.orEmpty()}"))
            s.bindLong(10, place.id)
        } > 0
    }

    override fun deletePlace(id: Long): Boolean = synchronized(lock) { exec("DELETE FROM places WHERE id=?") { it.bindLong(1, id) } > 0 }

    override fun getPlace(id: Long): Place? = synchronized(lock) {
        stmt("SELECT $PLACE_COLS FROM places p WHERE p.id=?", { it.bindLong(1, id) }) { if (it.step()) it.place() else null }
    }

    override fun places(listId: Long?, query: String?, near: LatLon?): List<Place> = synchronized(lock) {
        val sql = StringBuilder("SELECT $PLACE_COLS FROM places p")
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        if (listId != null) {
            sql.append(" JOIN list_places lp ON lp.place_id=p.id")
            where += "lp.list_id=?"; args += listId
        }
        for (tok in tokens(query)) {
            where += "p.search_text LIKE ? ESCAPE '\\'"; args += "%${escapeLike(tok)}%"
        }
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where.joinToString(" AND "))
        val out = ArrayList<Place>()
        stmt(sql.toString(), { s ->
            args.forEachIndexed { i, a -> if (a is Long) s.bindLong(i + 1, a) else s.bindText(i + 1, a as String) }
        }) { while (it.step()) out += it.place() }
        if (near != null) out.sortBy { it.point.distanceTo(near) }
        else out.sortWith(compareBy<Place> { fold(it.name) }.thenBy { it.id })
        out
    }

    // ---- lists ----

    override fun addList(name: String, color: Int?, icon: String?, notes: String?): Long = synchronized(lock) {
        exec("INSERT INTO lists(name,color,icon,notes,created_at) VALUES(?,?,?,?,?)") { s ->
            s.bindText(1, name); s.int(2, color); s.text(3, icon); s.text(4, notes); s.bindLong(5, System.currentTimeMillis())
        }
        lastId()
    }

    override fun updateList(list: PlaceList): Boolean = synchronized(lock) {
        exec("UPDATE lists SET name=?,color=?,icon=?,notes=? WHERE id=?") { s ->
            s.bindText(1, list.name); s.int(2, list.color); s.text(3, list.icon); s.text(4, list.notes); s.bindLong(5, list.id)
        } > 0
    }

    override fun deleteList(id: Long): Boolean = synchronized(lock) { exec("DELETE FROM lists WHERE id=?") { it.bindLong(1, id) } > 0 }

    private val listCols = "l.id,l.name,l.color,l.icon,l.notes,(SELECT COUNT(*) FROM list_places x WHERE x.list_id=l.id)"

    override fun getList(id: Long): PlaceList? = synchronized(lock) {
        stmt("SELECT $listCols FROM lists l WHERE l.id=?", { it.bindLong(1, id) }) { if (it.step()) it.list() else null }
    }

    override fun lists(): List<PlaceList> = synchronized(lock) {
        val out = ArrayList<PlaceList>()
        stmt("SELECT $listCols FROM lists l ORDER BY l.id") { while (it.step()) out += it.list() }
        out
    }

    override fun addToList(listId: Long, placeId: Long): Boolean = synchronized(lock) {
        exec("INSERT OR IGNORE INTO list_places(list_id,place_id) VALUES(?,?)") { it.bindLong(1, listId); it.bindLong(2, placeId) } > 0
    }

    override fun removeFromList(listId: Long, placeId: Long): Boolean = synchronized(lock) {
        exec("DELETE FROM list_places WHERE list_id=? AND place_id=?") { it.bindLong(1, listId); it.bindLong(2, placeId) } > 0
    }

    override fun listsOf(placeId: Long): List<PlaceList> = synchronized(lock) {
        val out = ArrayList<PlaceList>()
        stmt(
            "SELECT $listCols FROM lists l JOIN list_places m ON m.list_id=l.id WHERE m.place_id=? ORDER BY l.id",
            { it.bindLong(1, placeId) },
        ) { while (it.step()) out += it.list() }
        out
    }

    // ---- tracks ----

    override fun addTrack(
        name: String, kind: PathKind, segments: List<List<TrackPoint>>, notes: String?, color: Int?, createdAt: Long,
    ): Long = transaction {
        val clean = segments.filter { it.isNotEmpty() }
        exec("INSERT INTO tracks(name,kind,notes,color,point_count,created_at,dedup_key,search_text) VALUES(?,?,?,?,?,?,?,?)") { s ->
            s.bindText(1, name); s.bindText(2, kind.name); s.text(3, notes); s.int(4, color)
            s.bindLong(5, clean.sumOf { it.size }.toLong()); s.bindLong(6, createdAt)
            s.bindText(7, trackKey(name, kind, clean)); s.bindText(8, fold("$name ${notes.orEmpty()}"))
        }
        val id = lastId()
        db.prepare("INSERT INTO track_points(track_id,seg,seq,lat,lon,ele,time) VALUES(?,?,?,?,?,?,?)").use { s ->
            clean.forEachIndexed { seg, pts ->
                pts.forEachIndexed { seq, p ->
                    s.reset()
                    s.bindLong(1, id); s.bindLong(2, seg.toLong()); s.bindLong(3, seq.toLong())
                    s.bindDouble(4, p.point.lat); s.bindDouble(5, p.point.lon); s.real(6, p.elevation); s.long(7, p.timeMillis)
                    s.step()
                }
            }
        }
        id
    }

    override fun addTrackIfNew(name: String, kind: PathKind, segments: List<List<TrackPoint>>): AddResult = transaction {
        val key = trackKey(name, kind, segments.filter { it.isNotEmpty() })
        val existing = stmt("SELECT id FROM tracks WHERE dedup_key=? LIMIT 1", { it.bindText(1, key) }) { if (it.step()) it.getLong(0) else null }
        if (existing != null) AddResult(existing, false) else AddResult(addTrack(name, kind, segments), true)
    }

    override fun updateTrack(info: TrackInfo): Boolean = synchronized(lock) {
        exec("UPDATE tracks SET name=?,notes=?,color=?,search_text=? WHERE id=?") { s ->
            s.bindText(1, info.name); s.text(2, info.notes); s.int(3, info.color)
            s.bindText(4, fold("${info.name} ${info.notes.orEmpty()}")); s.bindLong(5, info.id)
        } > 0
    }

    override fun deleteTrack(id: Long): Boolean = synchronized(lock) { exec("DELETE FROM tracks WHERE id=?") { it.bindLong(1, id) } > 0 }

    private val trackCols = "id,name,kind,notes,color,point_count,created_at"

    override fun tracks(query: String?): List<TrackInfo> = synchronized(lock) {
        val toks = tokens(query)
        val where = if (toks.isEmpty()) "" else " WHERE " + toks.joinToString(" AND ") { "search_text LIKE ? ESCAPE '\\'" }
        val out = ArrayList<TrackInfo>()
        stmt("SELECT $trackCols FROM tracks$where ORDER BY id", { s -> toks.forEachIndexed { i, t -> s.bindText(i + 1, "%${escapeLike(t)}%") } }) {
            while (it.step()) out += it.trackInfo()
        }
        out
    }

    override fun track(id: Long): Track? = synchronized(lock) {
        val info = stmt("SELECT $trackCols FROM tracks WHERE id=?", { it.bindLong(1, id) }) { if (it.step()) it.trackInfo() else null }
            ?: return null
        val segments = ArrayList<MutableList<TrackPoint>>()
        stmt("SELECT seg,lat,lon,ele,time FROM track_points WHERE track_id=? ORDER BY seg,seq", { it.bindLong(1, id) }) { s ->
            var current = -1L
            while (s.step()) {
                val seg = s.getLong(0)
                if (seg != current) { segments.add(ArrayList()); current = seg }
                segments.last() += TrackPoint(
                    LatLon(s.getDouble(1), s.getDouble(2)),
                    if (s.isNull(3)) null else s.getDouble(3),
                    if (s.isNull(4)) null else s.getLong(4),
                )
            }
        }
        Track(info, segments)
    }

    // ---- special places ----

    override fun setSpecial(slot: SpecialSlot, name: String, point: LatLon, savedAt: Long) {
        synchronized(lock) {
            exec("INSERT OR REPLACE INTO special_places(slot,name,lat,lon,saved_at) VALUES(?,?,?,?,?)") { s ->
                s.bindText(1, slot.name); s.bindText(2, name); s.bindDouble(3, point.lat); s.bindDouble(4, point.lon)
                s.bindLong(5, savedAt)
            }
        }
    }

    override fun special(slot: SpecialSlot): SpecialPlace? = synchronized(lock) {
        stmt("SELECT name,lat,lon,saved_at FROM special_places WHERE slot=?", { it.bindText(1, slot.name) }) {
            if (it.step()) SpecialPlace(slot, it.getText(0), LatLon(it.getDouble(1), it.getDouble(2)), it.getLong(3)) else null
        }
    }

    override fun clearSpecial(slot: SpecialSlot): Boolean = synchronized(lock) {
        exec("DELETE FROM special_places WHERE slot=?") { it.bindText(1, slot.name) } > 0
    }

    // ---- recent searches ----

    override fun addSearch(query: String, at: Long, keep: Int) {
        val text = query.trim()
        if (text.isEmpty() || keep <= 0) return
        transaction {
            exec("INSERT OR REPLACE INTO search_history(query_key,query,used_at) VALUES(?,?,?)") { s ->
                s.bindText(1, fold(text)); s.bindText(2, text); s.bindLong(3, at)
            }
            exec(
                "DELETE FROM search_history WHERE query_key NOT IN " +
                    "(SELECT query_key FROM search_history ORDER BY used_at DESC, rowid DESC LIMIT ?)",
            ) { it.bindLong(1, keep.toLong()) }
        }
    }

    override fun recentSearches(limit: Int): List<String> = synchronized(lock) {
        val out = ArrayList<String>()
        stmt("SELECT query FROM search_history ORDER BY used_at DESC, rowid DESC LIMIT ?", { it.bindLong(1, limit.toLong()) }) {
            while (it.step()) out += it.getText(0)
        }
        out
    }

    override fun clearSearches() { synchronized(lock) { db.execSQL("DELETE FROM search_history") } }

    override fun clearAll() = transaction {
        db.execSQL("DELETE FROM track_points"); db.execSQL("DELETE FROM tracks")
        db.execSQL("DELETE FROM list_places"); db.execSQL("DELETE FROM lists"); db.execSQL("DELETE FROM places")
    }

    internal companion object {
        const val SCHEMA_VERSION = 2
        const val PLACE_COLS = "p.id,p.name,p.lat,p.lon,p.notes,p.icon,p.color,p.created_at,p.updated_at"

        val SCHEMA_V1 = listOf(
            """CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
               notes TEXT, icon TEXT, color INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
               dedup_key TEXT NOT NULL, search_text TEXT NOT NULL)""",
            "CREATE INDEX idx_places_dedup ON places(dedup_key)",
            """CREATE TABLE lists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, color INTEGER, icon TEXT, notes TEXT,
               created_at INTEGER NOT NULL)""",
            """CREATE TABLE list_places(list_id INTEGER NOT NULL REFERENCES lists(id) ON DELETE CASCADE,
               place_id INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE, PRIMARY KEY(list_id, place_id)) WITHOUT ROWID""",
            "CREATE INDEX idx_list_places_place ON list_places(place_id)",
            """CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT NOT NULL, notes TEXT, color INTEGER,
               point_count INTEGER NOT NULL, created_at INTEGER NOT NULL, dedup_key TEXT NOT NULL, search_text TEXT NOT NULL)""",
            "CREATE INDEX idx_tracks_dedup ON tracks(dedup_key)",
            """CREATE TABLE track_points(track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, seg INTEGER NOT NULL,
               seq INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER,
               PRIMARY KEY(track_id, seg, seq)) WITHOUT ROWID""",
        )

        /** v2: Home / Work / parked car, and the recent searches. Additive: nothing of v1 changes. */
        val SCHEMA_V2 = listOf(
            """CREATE TABLE special_places(slot TEXT PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
               saved_at INTEGER NOT NULL) WITHOUT ROWID""",
            """CREATE TABLE search_history(query_key TEXT PRIMARY KEY, query TEXT NOT NULL, used_at INTEGER NOT NULL)""",
            "CREATE INDEX idx_search_history_used ON search_history(used_at)",
        )

        /** Lower-case, accent-free text for searching and dedup. */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

        fun tokens(q: String?): List<String> = q?.let { fold(it).split(Regex("\\s+")).filter(String::isNotEmpty) }.orEmpty()

        fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun e5(d: Double) = (d * 1e5).roundToLong()

        /** Same name and the same position to about 1 m. */
        fun placeKey(name: String, p: LatLon) = "${e5(p.lat)}|${e5(p.lon)}|${fold(name)}"

        fun trackKey(name: String, kind: PathKind, segments: List<List<TrackPoint>>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("${fold(name)}|$kind".toByteArray())
            for (seg in segments) {
                md.update('S'.code.toByte())
                for (p in seg) md.update("${(p.point.lat * 1e6).roundToLong()},${(p.point.lon * 1e6).roundToLong()};".toByteArray())
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
