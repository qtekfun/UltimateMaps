package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.CRC32

class BikeShareFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reader of `bikeshare-es.bin`, written by `scripts/build-bikeshare.py` (layout in that script and in
 * docs/phase2/bike-share.md): big endian, a CRC32 of everything before the last four bytes, strict bounds on every count.
 * Anything damaged, truncated or from a newer version is rejected as a whole ("no data"); the caller keeps what it had.
 */
object BikeShareFile {
    const val MAGIC = 0x554D424B // "UMBK"
    const val VERSION = 1
    const val MAX_BYTES = 4 shl 20
    private const val MAX_SYSTEMS = 200
    private const val MAX_STATIONS = 100_000
    private const val MIN_BYTES = 4 + 2 + 8 + 2 + 4

    fun parse(bytes: ByteArray): BikeShareDataset {
        if (bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) throw BikeShareFileException("bad size ${bytes.size}")
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        val stored = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt().toLong() and 0xFFFFFFFFL
        if (crc != stored) throw BikeShareFileException("checksum mismatch")
        try {
            val inp = DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 4))
            if (inp.readInt() != MAGIC) throw BikeShareFileException("not a bike-share file")
            val version = inp.readUnsignedShort()
            if (version != VERSION) throw BikeShareFileException("unsupported version $version")
            val generated = inp.readLong()
            val nSystems = inp.readUnsignedShort()
            if (nSystems !in 0..MAX_SYSTEMS) throw BikeShareFileException("bad system count")
            val systems = ArrayList<BikeSystem>(nSystems)
            val stations = ArrayList<BikeStation>()
            repeat(nSystems) { si ->
                val system = BikeSystem(inp.readUTF(), inp.readUTF(), inp.readUTF())
                if (system.id.isBlank()) throw BikeShareFileException("empty system id")
                systems += system
                val n = inp.readInt()
                if (n !in 0..MAX_STATIONS || stations.size + n > MAX_STATIONS) throw BikeShareFileException("bad station count")
                repeat(n) {
                    val stationId = inp.readUTF()
                    val name = inp.readUTF()
                    val lat = inp.readInt() / 1e6
                    val lon = inp.readInt() / 1e6
                    val capacity = inp.readUnsignedShort()
                    val location = LatLon.ofOrNull(lat, lon) ?: throw BikeShareFileException("bad position")
                    stations += BikeStation("$si:$stationId", system, stationId, name, location, capacity)
                }
            }
            if (inp.available() != 0) throw BikeShareFileException("trailing bytes")
            return BikeShareDataset(generated, systems, stations)
        } catch (e: EOFException) {
            throw BikeShareFileException("truncated", e)
        } catch (e: BikeShareFileException) {
            throw e
        } catch (e: IOException) {
            throw BikeShareFileException("unreadable", e)
        }
    }
}
