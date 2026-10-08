package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.CRC32

class ZbeFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reader of `zbe-es.bin`, written by `scripts/build-zbe.py` (layout in docs/phase2/zbe-data.md): big endian, a CRC32 of
 * everything before the last four bytes, strict bounds on every count. Anything damaged, truncated or from a newer version is
 * rejected as a whole ("no data"); the caller keeps what it had.
 */
object ZbeFile {
    const val MAGIC = 0x554D5A42 // "UMZB"
    const val VERSION = 1
    const val MAX_BYTES = 4 shl 20
    private const val MAX_ZONES = 5_000
    private const val MAX_RING_POINTS = 20_000
    private const val MIN_BYTES = 4 + 2 + 8 + 1 + 4 + 4

    fun parse(bytes: ByteArray): ZbeDataset {
        if (bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) throw ZbeFileException("bad size ${bytes.size}")
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        val stored = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt().toLong() and 0xFFFFFFFFL
        if (crc != stored) throw ZbeFileException("checksum mismatch")
        try {
            val inp = DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 4))
            if (inp.readInt() != MAGIC) throw ZbeFileException("not a zone file")
            val version = inp.readUnsignedShort()
            if (version != VERSION) throw ZbeFileException("unsupported version $version")
            val generated = inp.readLong()
            val flags = inp.readUnsignedByte()
            val n = inp.readInt()
            if (n !in 0..MAX_ZONES) throw ZbeFileException("bad count")
            val zones = ArrayList<ZbeZone>(n)
            repeat(n) { zi ->
                val name = inp.readUTF()
                val city = inp.readUTF()
                val restriction = inp.readUTF()
                val np = inp.readUnsignedByte()
                if (np == 0) throw ZbeFileException("zone without polygon")
                val polygons = List(np) {
                    val nr = inp.readUnsignedByte()
                    if (nr == 0) throw ZbeFileException("polygon without ring")
                    ZbePolygon(List(nr) { readRing(inp) })
                }
                zones += ZbeZone("z$zi", name, city, restriction, polygons)
            }
            if (inp.available() != 0) throw ZbeFileException("trailing bytes")
            return ZbeDataset(generated, flags, zones)
        } catch (e: EOFException) {
            throw ZbeFileException("truncated", e)
        } catch (e: ZbeFileException) {
            throw e
        } catch (e: IOException) {
            throw ZbeFileException("unreadable", e)
        }
    }

    private fun readRing(inp: DataInputStream): ZbeRing {
        val n = inp.readUnsignedShort()
        if (n < 3 || n > MAX_RING_POINTS) throw ZbeFileException("bad ring size")
        val c = DoubleArray(2 * n)
        for (i in 0 until n) {
            val lat = inp.readInt() / 1e6
            val lon = inp.readInt() / 1e6
            LatLon.ofOrNull(lat, lon) ?: throw ZbeFileException("bad position")
            c[2 * i] = lat
            c[2 * i + 1] = lon
        }
        return ZbeRing(c)
    }
}
