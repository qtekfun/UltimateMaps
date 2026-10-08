package com.qtekfun.ultimatemaps.core.weather

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Unpacks what AEMET's `avisos_cap/ultimoelaborado/area/esp` data URL returns: a tar archive of CAP XML files (the archive may
 * be gzipped; a single bare XML document is also accepted). Defensive: bounded entry count and size, only regular files named
 * `.xml`, no path is ever used (nothing is written to disk).
 */
object CapBundle {
    const val MAX_ENTRIES = 5_000
    const val MAX_ENTRY_BYTES = 1 shl 20
    const val MAX_TOTAL_BYTES = 48L shl 20

    /** The XML documents of [bytes]. Throws [IOException] when the data is neither a tar nor an XML document. */
    fun documents(bytes: ByteArray): List<ByteArray> {
        var data = bytes
        if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            data = gunzip(data)
        }
        return when {
            looksLikeXml(data) -> listOf(data)
            data.size >= 512 && isZeroBlock(data, 0) -> emptyList() // an empty archive: nothing in force
            isTar(data) -> tar(data)
            else -> throw IOException("neither a tar archive nor an XML document")
        }
    }

    private fun looksLikeXml(d: ByteArray): Boolean {
        var i = 0
        if (d.size >= 3 && d[0] == 0xEF.toByte() && d[1] == 0xBB.toByte() && d[2] == 0xBF.toByte()) i = 3
        while (i < d.size && (d[i] == ' '.code.toByte() || d[i] == '\n'.code.toByte() || d[i] == '\r'.code.toByte() || d[i] == '\t'.code.toByte())) i++
        return i < d.size && d[i] == '<'.code.toByte()
    }

    private fun isTar(d: ByteArray): Boolean =
        d.size >= 512 && d[257] == 'u'.code.toByte() && d[258] == 's'.code.toByte() && d[259] == 't'.code.toByte() && d[260] == 'a'.code.toByte() && d[261] == 'r'.code.toByte()

    private fun gunzip(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(d)).use { copyCapped(it, out) }
        return out.toByteArray()
    }

    private fun copyCapped(input: InputStream, out: ByteArrayOutputStream) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            total += n
            if (total > MAX_TOTAL_BYTES) throw IOException("archive larger than $MAX_TOTAL_BYTES bytes")
            out.write(buf, 0, n)
        }
    }

    private fun tar(d: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        var entries = 0
        while (pos + 512 <= d.size) {
            if (isZeroBlock(d, pos)) break
            if (++entries > MAX_ENTRIES) throw IOException("too many entries")
            val name = cString(d, pos, 100)
            val size = octal(d, pos + 124, 12)
            if (size < 0 || size > Int.MAX_VALUE) throw IOException("bad entry size")
            val type = d[pos + 156].toInt().toChar()
            val start = pos + 512
            val end = start.toLong() + size
            if (end > d.size) throw IOException("truncated archive")
            val isFile = type == '0' || type == '\u0000'
            if (isFile && name.endsWith(".xml", ignoreCase = true) && size in 1..MAX_ENTRY_BYTES.toLong()) {
                out += d.copyOfRange(start, end.toInt())
            }
            pos = start + ((size + 511) / 512 * 512).toInt()
        }
        return out
    }

    private fun isZeroBlock(d: ByteArray, pos: Int): Boolean {
        for (i in pos until pos + 512) if (d[i].toInt() != 0) return false
        return true
    }

    private fun cString(d: ByteArray, pos: Int, max: Int): String {
        var n = 0
        while (n < max && d[pos + n].toInt() != 0) n++
        return String(d, pos, n, Charsets.ISO_8859_1)
    }

    /** Octal number as written by tar (digits, then a space or NUL padding); base-256 (huge) values are treated as an error. */
    private fun octal(d: ByteArray, pos: Int, len: Int): Long {
        if (d[pos].toInt() and 0x80 != 0) return -1
        var v = 0L
        for (i in pos until pos + len) {
            val c = d[i].toInt().toChar()
            when (c) {
                in '0'..'7' -> v = v * 8 + (c - '0')
                ' ', '\u0000' -> if (v != 0L) return v
                else -> return -1
            }
        }
        return v
    }
}
