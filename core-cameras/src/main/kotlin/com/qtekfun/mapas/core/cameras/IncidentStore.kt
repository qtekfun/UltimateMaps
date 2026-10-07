package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

/**
 * Local cache of the last incident download: one compact binary file with its own fetch time (so the TTL survives a
 * restart and the data shows offline). Written to a temporary file, synced and moved into place atomically; a CRC32
 * at the end rejects anything damaged. A file that cannot be read is simply "no data".
 */
class IncidentCache(private val dir: File) {
    private val file get() = File(dir, "incidents.bin")

    fun write(data: IncidentData) {
        dir.mkdirs()
        val tmp = File(dir, "incidents.bin.tmp")
        FileOutputStream(tmp).use { fos ->
            val crc = CheckedOutputStream(BufferedOutputStream(fos, 64 * 1024), CRC32())
            val out = DataOutputStream(crc)
            out.writeInt(MAGIC)
            out.writeShort(VERSION)
            out.writeLong(data.fetchedAtMillis)
            out.writeLong(data.publishedMillis ?: -1L)
            out.writeInt(data.incidents.size)
            for (i in data.incidents) {
                out.writeUTF(i.id.take(MAX_TEXT))
                out.writeByte(i.kind.ordinal)
                out.writeUTF(i.road.take(MAX_TEXT))
                out.writeDouble(i.location.lat); out.writeDouble(i.location.lon)
                out.writeBoolean(i.end != null)
                if (i.end != null) { out.writeDouble(i.end.lat); out.writeDouble(i.end.lon) }
                out.writeShort(i.directionDeg ?: -1)
                out.writeUTF(i.municipality.orEmpty().take(MAX_TEXT))
                out.writeUTF(i.province.orEmpty().take(MAX_TEXT))
                out.writeDouble(i.kmPoint ?: Double.NaN)
                out.writeLong(i.startMillis ?: -1L)
                out.writeLong(i.endMillis ?: -1L)
            }
            out.flush()
            val sum = crc.checksum.value
            DataOutputStream(BufferedOutputStream(fos)).apply { writeLong(sum); flush() }
            fos.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun read(): IncidentData? {
        val f = file
        if (!f.isFile || f.length() > MAX_FILE) return null
        return try {
            FileInputStream(f).use { fis ->
                val checked = CheckedInputStream(BufferedInputStream(fis, 64 * 1024), CRC32())
                val inp = DataInputStream(checked)
                if (inp.readInt() != MAGIC || inp.readShort().toInt() != VERSION) return null
                val fetched = inp.readLong()
                val published = inp.readLong().takeIf { it >= 0 }
                val n = inp.readInt()
                if (n !in 0..MAX_ENTRIES) return null
                val kinds = IncidentKind.entries
                val list = ArrayList<TrafficIncident>(n)
                repeat(n) {
                    val id = inp.readUTF()
                    val kind = kinds.getOrNull(inp.readUnsignedByte()) ?: throw IOException("bad kind")
                    val road = inp.readUTF()
                    val loc = LatLon.ofOrNull(inp.readDouble(), inp.readDouble()) ?: throw IOException("bad position")
                    val end = if (inp.readBoolean()) LatLon.ofOrNull(inp.readDouble(), inp.readDouble()) ?: throw IOException("bad position") else null
                    val dir = inp.readShort().toInt().takeIf { it in 0..359 }
                    val muni = inp.readUTF().ifEmpty { null }
                    val prov = inp.readUTF().ifEmpty { null }
                    val km = inp.readDouble().takeIf { !it.isNaN() }
                    val start = inp.readLong().takeIf { it >= 0 }
                    val endMs = inp.readLong().takeIf { it >= 0 }
                    list += TrafficIncident(id, kind, road, loc, end, dir, muni, prov, km, start, endMs)
                }
                val expected = checked.checksum.value
                if (!crcOk(f, expected)) return null
                IncidentData(fetched, published, list)
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun crcOk(f: File, expected: Long): Boolean = try {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 8) return false
            r.seek(r.length() - 8)
            r.readLong() == expected
        }
    } catch (e: IOException) { false }

    fun delete() {
        file.delete()
    }

    private companion object {
        const val MAGIC = 0x554D4943 // "UMIC"
        const val VERSION = 1
        const val MAX_TEXT = 8_000
        const val MAX_ENTRIES = 200_000
        const val MAX_FILE = 64L shl 20
    }
}

/** In-memory incidents plus the structures for the map (scan with limit) and the warner (targets). */
class IncidentDataRepository : IncidentRepository {
    private class Snapshot(val data: IncidentData?) {
        val byId: Map<String, TrafficIncident> = data?.incidents?.associateBy { it.id } ?: emptyMap()
    }

    @Volatile private var snap = Snapshot(null)
    private val updated = MutableStateFlow<Long?>(null)
    override val lastUpdateMillis: StateFlow<Long?> get() = updated

    /** Replaces the data ([null] clears it). */
    fun install(data: IncidentData?) {
        snap = Snapshot(data)
        updated.value = data?.fetchedAtMillis
    }

    val data: IncidentData? get() = snap.data

    override fun incidentsIn(bounds: LatLonBounds, kinds: Set<IncidentKind>, limit: Int): List<TrafficIncident> {
        if (limit <= 0 || kinds.isEmpty()) return emptyList()
        val all = snap.data?.incidents ?: return emptyList()
        val hits = all.filter { it.kind in kinds && (bounds.contains(it.location) || it.end?.let(bounds::contains) == true) }
        // Most important first when the view holds more than [limit]: beacons and accidents before the rest.
        return if (hits.size <= limit) hits else hits.sortedBy { it.kind.ordinal }.take(limit)
    }

    override fun incident(id: String): TrafficIncident? = snap.byId[id]

    /**
     * The announceable targets of the current data for the kinds [kinds] returns, rebuilt only when the data or the kinds
     * change. Roadworks and weather are shown on the map but never announced.
     */
    fun alertSource(kinds: () -> Set<IncidentKind>): AlertSource = object : AlertSource {
        private var builtFor: Pair<IncidentData?, Set<IncidentKind>>? = null
        private var grid = TargetGrid(emptyList())

        override fun forEachNear(lat: Double, lon: Double, radiusMeters: Double, visitor: AlertSource.Visitor) {
            val d = snap.data
            val k = kinds()
            val key = builtFor
            if (key == null || key.first !== d || key.second != k) {
                grid = TargetGrid(IncidentTargets.of(d?.incidents.orEmpty(), k))
                builtFor = d to k
            }
            grid.forEachNear(lat, lon, radiusMeters, visitor)
        }
    }
}

object IncidentTargets {
    private const val DIRECTION_TOLERANCE_DEG = 60

    fun categoryOf(kind: IncidentKind): AlertCategory? = when (kind) {
        IncidentKind.V16 -> AlertCategory.V16
        IncidentKind.ACCIDENT -> AlertCategory.ACCIDENT
        IncidentKind.CLOSURE -> AlertCategory.CLOSURE
        IncidentKind.CONGESTION -> AlertCategory.CONGESTION
        IncidentKind.OBSTACLE -> AlertCategory.OBSTACLE
        IncidentKind.WEATHER, IncidentKind.ROADWORKS -> null
    }

    fun of(incidents: List<TrafficIncident>, kinds: Set<IncidentKind>): List<AlertTarget> {
        val out = ArrayList<AlertTarget>()
        for (i in incidents) {
            if (i.kind !in kinds) continue
            val cat = categoryOf(i.kind) ?: continue
            val sense = if (i.directionDeg != null) AxisSense.ALONG else AxisSense.BOTH
            out += AlertTarget(i.id + "a", i.id, cat, i.location.lat, i.location.lon, i.directionDeg, sense, DIRECTION_TOLERANCE_DEG, null)
            i.end?.let { out += AlertTarget(i.id + "b", i.id, cat, it.lat, it.lon, i.directionDeg, sense, DIRECTION_TOLERANCE_DEG, null) }
        }
        return out
    }
}
