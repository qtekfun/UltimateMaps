package com.qtekfun.ultimatemaps.core.routes

import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class RouteFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reader of `routes-es.bin`, written by `scripts/build-routes.py` (layout in docs/phase2/osm-routes.md): big endian header,
 * a deflated body of delta-coded varints, a CRC32 of everything before the last four bytes, strict bounds on every count.
 * Anything damaged, truncated or from a newer version is rejected as a whole; the caller keeps what it had.
 */
object RouteFile {
    const val MAGIC = 0x554D5254 // "UMRT"
    const val VERSION = 1
    const val MAX_BYTES = 40 shl 20
    const val MAX_RAW_BYTES = 120 shl 20
    private const val MAX_ROUTES = 200_000
    private const val MAX_POINTS_TOTAL = 20_000_000
    private const val HEADER = 4 + 2 + 8 + 4 + 4

    fun parse(bytes: ByteArray): TrailDataset {
        if (bytes.size < HEADER + 4 || bytes.size > MAX_BYTES) throw RouteFileException("bad size ${bytes.size}")
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        val stored = ByteBuffer.wrap(bytes, bytes.size - 4, 4).int.toLong() and 0xFFFFFFFFL
        if (crc != stored) throw RouteFileException("checksum mismatch")
        val head = ByteBuffer.wrap(bytes, 0, HEADER)
        if (head.int != MAGIC) throw RouteFileException("not a routes file")
        val version = head.short.toInt() and 0xFFFF
        if (version != VERSION) throw RouteFileException("unsupported version $version")
        val generated = head.long
        val n = head.int
        val rawLen = head.int
        if (n !in 0..MAX_ROUTES) throw RouteFileException("bad count")
        if (rawLen !in 0..MAX_RAW_BYTES) throw RouteFileException("bad length")
        val raw = ByteArray(rawLen)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, HEADER, bytes.size - 4 - HEADER)
            var off = 0
            while (off < rawLen) {
                val got = inflater.inflate(raw, off, rawLen - off)
                if (got == 0 && (inflater.finished() || inflater.needsInput())) break
                off += got
            }
            // Exactly rawLen bytes, and nothing more in the stream.
            if (off != rawLen || inflater.inflate(ByteArray(1)) != 0 || !inflater.finished()) throw RouteFileException("bad body length")
        } catch (e: DataFormatException) {
            throw RouteFileException("corrupt body", e)
        } finally {
            inflater.end()
        }
        return try {
            TrailDataset(generated, readBody(ByteBuffer.wrap(raw), n))
        } catch (e: BufferUnderflowException) {
            throw RouteFileException("truncated", e)
        }
    }

    private fun readBody(b: ByteBuffer, n: Int): List<Trail> {
        val out = ArrayList<Trail>(n)
        var pointsLeft = MAX_POINTS_TOTAL
        repeat(n) { id ->
            val kind = TrailKind.ofCode(b.get().toInt() and 0xFF) ?: throw RouteFileException("bad kind")
            val level = TrailLevel.ofCode(b.get().toInt() and 0xFF) ?: throw RouteFileException("bad level")
            val flags = b.get().toInt() and 0xFF
            val length = b.int
            if (length < 0) throw RouteFileException("bad length")
            val name = utf(b)
            val ref = utf(b)
            val operator = utf(b)
            val ns = b.short.toInt() and 0xFFFF
            val segments = ArrayList<IntArray>(ns)
            repeat(ns) {
                val cnt = varint(b)
                if (cnt !in 2..pointsLeft.toLong()) throw RouteFileException("bad point count")
                pointsLeft -= cnt.toInt()
                val a = IntArray(cnt.toInt() * 2)
                var lat = 0L
                var lon = 0L
                for (i in 0 until cnt.toInt()) {
                    lat += varint(b)
                    lon += varint(b)
                    if (lat !in -90_000_000..90_000_000 || lon !in -180_000_000..180_000_000) throw RouteFileException("bad position")
                    a[i * 2] = lat.toInt()
                    a[i * 2 + 1] = lon.toInt()
                }
                segments += a
            }
            if (segments.isEmpty()) throw RouteFileException("route without geometry")
            out += Trail(id, kind, level, length, flags and 1 != 0, name, ref, operator, segments)
        }
        if (b.hasRemaining()) throw RouteFileException("trailing bytes")
        return out
    }

    private fun utf(b: ByteBuffer): String {
        val len = b.short.toInt() and 0xFFFF
        val bytes = ByteArray(len)
        b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Zigzag varint (the first value of a segment is the point count, read the same way: it is never negative). */
    private fun varint(b: ByteBuffer): Long {
        var shift = 0
        var v = 0L
        while (true) {
            val x = b.get().toInt() and 0xFF
            v = v or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return (v ushr 1) xor -(v and 1)
            shift += 7
            if (shift > 63) throw RouteFileException("bad varint")
        }
    }
}
