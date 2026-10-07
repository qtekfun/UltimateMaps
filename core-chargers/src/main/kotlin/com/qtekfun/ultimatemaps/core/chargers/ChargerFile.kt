package com.qtekfun.ultimatemaps.core.chargers

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.CRC32

class ChargerFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reader of `chargers-es.bin`, written by `scripts/build-chargers.py` (layout in docs/phase2/ev-chargers-data.md): big
 * endian, a CRC32 of everything before the last four bytes, strict bounds on every count. Anything damaged, truncated or
 * from a newer version is rejected as a whole ("no data"); the caller keeps what it had.
 */
object ChargerFile {
    const val MAGIC = 0x554D4556 // "UMEV"
    const val VERSION = 1
    const val MAX_BYTES = 8 shl 20
    private const val MAX_RECORDS = 500_000
    private const val MAX_SOCKETS = 32
    private const val MIN_BYTES = 4 + 2 + 8 + 1 + 4 + 4

    fun parse(bytes: ByteArray): ChargerDataset {
        if (bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) throw ChargerFileException("bad size ${bytes.size}")
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        val stored = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt().toLong() and 0xFFFFFFFFL
        if (crc != stored) throw ChargerFileException("checksum mismatch")
        try {
            val inp = DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 4))
            if (inp.readInt() != MAGIC) throw ChargerFileException("not a charger file")
            val version = inp.readUnsignedShort()
            if (version != VERSION) throw ChargerFileException("unsupported version $version")
            val generated = inp.readLong()
            val flags = inp.readUnsignedByte()
            val n = inp.readInt()
            if (n !in 0..MAX_RECORDS) throw ChargerFileException("bad count")
            val out = ArrayList<Charger>(n)
            repeat(n) { i ->
                val lat = inp.readInt() / 1e6
                val lon = inp.readInt() / 1e6
                val location = LatLon.ofOrNull(lat, lon) ?: throw ChargerFileException("bad position")
                val fee = ChargerFee.entries.getOrElse(inp.readUnsignedByte()) { ChargerFee.UNKNOWN }
                val access = ChargerAccess.entries.getOrElse(inp.readUnsignedByte()) { ChargerAccess.UNKNOWN }
                val auth = inp.readUnsignedByte()
                val capacity = inp.readUnsignedShort()
                val ns = inp.readUnsignedByte()
                if (ns > MAX_SOCKETS) throw ChargerFileException("bad socket count")
                val sockets = List(ns) {
                    val type = SocketType.ofCode(inp.readUnsignedByte())
                    val count = inp.readUnsignedByte()
                    val dkw = inp.readUnsignedShort()
                    ChargerSocket(type, count, if (dkw > 0) dkw / 10.0 else null)
                }
                val operator = inp.readUTF()
                val network = inp.readUTF()
                val name = inp.readUTF()
                val hours = inp.readUTF()
                out += Charger("c$i", location, operator, network, name, capacity, sockets, fee, access, auth, hours)
            }
            if (inp.available() != 0) throw ChargerFileException("trailing bytes")
            return ChargerDataset(generated, flags, out)
        } catch (e: EOFException) {
            throw ChargerFileException("truncated", e)
        } catch (e: ChargerFileException) {
            throw e
        } catch (e: IOException) {
            throw ChargerFileException("unreadable", e)
        }
    }
}
