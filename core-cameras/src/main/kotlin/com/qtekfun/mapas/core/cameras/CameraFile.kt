package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.CRC32

class CameraFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reader of `speedcams-es.bin`, written by `scripts/build-cameras.py` (layout in docs/phase2/cameras-data.md):
 * big endian, a CRC32 of everything before the last four bytes, strict bounds on every count. Anything damaged,
 * truncated or from a newer version is rejected as a whole ("no data"); the caller keeps what it had.
 */
object CameraFile {
    const val MAGIC = 0x554D4341 // "UMCA"
    const val VERSION = 1
    const val MAX_BYTES = 8 shl 20
    private const val MAX_RECORDS = 200_000
    private const val MAX_POINTS = 5_000

    fun parse(bytes: ByteArray): CameraDataset {
        if (bytes.size < 4 + 2 + 8 + 1 + 12 + 4 || bytes.size > MAX_BYTES) throw CameraFileException("bad size ${bytes.size}")
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        val stored = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt().toLong() and 0xFFFFFFFFL
        if (crc != stored) throw CameraFileException("checksum mismatch")
        try {
            val inp = DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 4))
            if (inp.readInt() != MAGIC) throw CameraFileException("not a camera file")
            val version = inp.readUnsignedShort()
            if (version != VERSION) throw CameraFileException("unsupported version $version")
            val generated = inp.readLong()
            val flags = inp.readUnsignedByte()
            val nf = inp.readInt()
            val ns = inp.readInt()
            val nz = inp.readInt()
            if (nf !in 0..MAX_RECORDS || ns !in 0..MAX_RECORDS || nz !in 0..MAX_RECORDS) throw CameraFileException("bad counts")
            fun pos(): LatLon {
                val lat = inp.readInt() / 1e6
                val lon = inp.readInt() / 1e6
                return LatLon.ofOrNull(lat, lon) ?: throw CameraFileException("bad position")
            }
            fun speed(v: Int): Int? = if (v in 10..200) v else null
            val fixed = ArrayList<SpeedCamera>(nf)
            repeat(nf) { i ->
                val p = pos()
                val src = inp.readUnsignedByte()
                val ms = inp.readUnsignedByte()
                val axis = inp.readShort().toInt()
                val sense = inp.readUnsignedByte()
                val road = inp.readUTF()
                fixed += SpeedCamera(
                    "f$i", CameraKind.FIXED, p, null, road, speed(ms), axis.takeIf { it in 0..359 },
                    AxisSense.entries.getOrElse(sense) { AxisSense.BOTH }.takeIf { axis in 0..359 } ?: AxisSense.BOTH, src,
                )
            }
            val sections = ArrayList<SpeedCamera>(ns)
            repeat(ns) { i ->
                val a = pos()
                val b = pos()
                val src = inp.readUnsignedByte()
                val ms = inp.readUnsignedByte()
                val road = inp.readUTF()
                sections += SpeedCamera("s$i", CameraKind.SECTION, a, b, road, speed(ms), null, AxisSense.BOTH, src)
            }
            val zones = ArrayList<MobileZone>(nz)
            repeat(nz) { i ->
                val road = inp.readUTF()
                val province = inp.readUTF()
                val from = inp.readInt()
                val to = inp.readInt()
                val n = inp.readUnsignedShort()
                if (n > MAX_POINTS) throw CameraFileException("bad zone")
                val line = ArrayList<LatLon>(n)
                repeat(n) { line += pos() }
                zones += MobileZone("z$i", road, province, from, to, line)
            }
            if (inp.available() != 0) throw CameraFileException("trailing bytes")
            return CameraDataset(generated, flags, fixed, sections, zones)
        } catch (e: EOFException) {
            throw CameraFileException("truncated", e)
        } catch (e: CameraFileException) {
            throw e
        } catch (e: IOException) {
            throw CameraFileException("unreadable", e)
        }
    }
}
