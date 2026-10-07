package com.qtekfun.ultimatemaps.core.fuel

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

/**
 * Local cache: one compact binary file per fuel (`fuel-<id>.bin`) with its own fetch time. Strings that repeat a lot
 * (brand, municipality, province, schedule) go in a table once. Written to a temporary file, synced and moved into
 * place atomically, so a crash never leaves a half-written file; a CRC32 at the end rejects anything damaged.
 * A file that cannot be read is simply "no data" for that fuel.
 */
class FuelCache(private val dir: File) {
    private fun file(fuelId: String): File {
        require(ID.matches(fuelId)) { "bad fuel id" }
        return File(dir, "fuel-$fuelId.bin")
    }

    fun write(data: FuelData) {
        dir.mkdirs()
        val dest = file(data.fuelId)
        val tmp = File(dir, dest.name + ".tmp")
        val table = LinkedHashMap<String, Int>()
        fun idx(s: String?): Int = if (s == null) -1 else table.getOrPut(s) { table.size }
        // First pass builds the table, so it can be written before the stations.
        val rows = data.stations.map { s ->
            intArrayOf(idx(s.brand), idx(s.address), idx(s.municipality), idx(s.province), idx(s.schedule))
        }
        FileOutputStream(tmp).use { fos ->
            val crc = CheckedOutputStream(BufferedOutputStream(fos, 64 * 1024), CRC32())
            val out = DataOutputStream(crc)
            out.writeInt(MAGIC)
            out.writeShort(VERSION)
            out.writeUTF(data.fuelId)
            out.writeLong(data.fetchedAtMillis)
            out.writeUTF(data.serviceDate.orEmpty())
            out.writeInt(table.size)
            for (s in table.keys) out.writeUTF(s.take(MAX_TEXT))
            out.writeInt(data.stations.size)
            data.stations.forEachIndexed { i, s ->
                val r = rows[i]
                out.writeUTF(s.id.take(MAX_TEXT))
                for (v in r) out.writeInt(v)
                out.writeDouble(s.location.lat)
                out.writeDouble(s.location.lon)
                out.writeInt(Math.round(s.price * 1000).toInt())
            }
            out.flush()
            val sum = crc.checksum.value
            // The CRC itself is written outside the checked stream.
            DataOutputStream(BufferedOutputStream(fos)).apply { writeLong(sum); flush() }
            fos.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The cached data of [fuelId], or null when there is none or it is damaged. */
    fun read(fuelId: String): FuelData? {
        val f = file(fuelId)
        if (!f.isFile || f.length() > MAX_FILE) return null
        return try {
            FileInputStream(f).use { fis ->
                val checked = CheckedInputStream(BufferedInputStream(fis, 64 * 1024), CRC32())
                val inp = DataInputStream(checked)
                if (inp.readInt() != MAGIC || inp.readShort().toInt() != VERSION) return null
                val id = inp.readUTF()
                if (id != fuelId) return null
                val fetched = inp.readLong()
                val date = inp.readUTF().ifEmpty { null }
                val n = inp.readInt()
                if (n !in 0..MAX_ENTRIES) return null
                val table = Array(n) { inp.readUTF() }
                val count = inp.readInt()
                if (count !in 0..MAX_ENTRIES) return null
                fun str(i: Int): String? = if (i == -1) null else table.getOrNull(i) ?: throw IOException("bad index")
                val stations = ArrayList<RawStation>(count)
                repeat(count) {
                    val sid = inp.readUTF()
                    val brand = inp.readInt(); val addr = inp.readInt(); val muni = inp.readInt(); val prov = inp.readInt(); val sch = inp.readInt()
                    val lat = inp.readDouble(); val lon = inp.readDouble()
                    val price = inp.readInt() / 1000.0
                    val loc = LatLon.ofOrNull(lat, lon) ?: throw IOException("bad position")
                    stations += RawStation(sid, str(brand).orEmpty(), str(addr).orEmpty(), str(muni).orEmpty(), str(prov).orEmpty(), loc, str(sch), price)
                }
                val expected = checked.checksum.value
                if (!crcOk(f, expected)) return null
                FuelData(fuelId, fetched, date, stations)
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** The checksum of what was read must equal the 8 trailing bytes of the file. */
    private fun crcOk(f: File, expected: Long): Boolean = try {
        java.io.RandomAccessFile(f, "r").use { r ->
            if (r.length() < 8) return false
            r.seek(r.length() - 8)
            r.readLong() == expected
        }
    } catch (e: IOException) { false }

    fun delete(fuelId: String) {
        file(fuelId).delete()
    }

    private companion object {
        const val MAGIC = 0x554D4655 // "UMFU"
        const val VERSION = 1
        const val MAX_TEXT = 16_000
        const val MAX_ENTRIES = 500_000
        const val MAX_FILE = 64L shl 20
        val ID = Regex("[a-z0-9_]{1,24}")
    }
}
