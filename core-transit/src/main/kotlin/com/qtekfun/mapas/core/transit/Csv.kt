package com.qtekfun.mapas.core.transit

import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader

/** Growable primitive int list (avoids boxing on multi-million-row GTFS files). */
class IntList(initial: Int = 16) {
    var data = IntArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun clear() {
        size = 0
    }
    fun toArray(): IntArray = data.copyOf(size)
}

/**
 * Streaming, allocation-light CSV reader for GTFS files.
 *
 * Handles a UTF-8 BOM, CRLF/LF line ends, quoted fields with embedded commas, newlines and doubled quotes,
 * and trims every field (the Renfe feed pads its fields with spaces). Columns are addressed by header name;
 * a missing column reads as the empty string.
 */
class CsvTable(input: InputStream) : Closeable {
    private val reader = InputStreamReader(input, Charsets.UTF_8)
    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var lim = 0
    private var eof = false

    private var line = CharArray(1024)
    private var lineLen = 0
    private var starts = IntArray(32)
    private var ends = IntArray(32)
    private var fieldCount = 0

    private val columns = HashMap<String, Int>()

    init {
        if (readRow()) {
            for (i in 0 until fieldCount) columns[field(i).removePrefix("﻿")] = i
        }
    }

    fun hasColumn(name: String): Boolean = columns.containsKey(name)

    /** Column index or -1. Resolve once outside the row loop for speed. */
    fun col(name: String): Int = columns[name] ?: -1

    /** Advances to the next non-blank data row; false at end of file. */
    fun next(): Boolean {
        while (readRow()) {
            if (fieldCount > 1 || (fieldCount == 1 && starts[0] != ends[0])) return true
        }
        return false
    }

    fun str(col: Int): String = if (col < 0 || col >= fieldCount) "" else field(col)

    fun int(col: Int, default: Int = 0): Int {
        if (col < 0 || col >= fieldCount) return default
        var s = starts[col]
        val e = ends[col]
        if (s == e) return default
        var neg = false
        if (line[s] == '-') {
            neg = true
            s++
        }
        var v = 0
        while (s < e) {
            val c = line[s++]
            if (c < '0' || c > '9') return default
            v = v * 10 + (c - '0')
        }
        return if (neg) -v else v
    }

    fun double(col: Int): Double = str(col).toDoubleOrNull() ?: Double.NaN

    /** GTFS time "H:MM:SS" / "HH:MM:SS" (hours may exceed 24) in seconds, or -1 if empty or malformed. */
    fun timeSec(col: Int): Int {
        if (col < 0 || col >= fieldCount) return -1
        var s = starts[col]
        val e = ends[col]
        if (s == e) return -1
        var parts = 0
        var cur = 0
        var total = 0
        while (s < e) {
            val c = line[s++]
            if (c == ':') {
                total = total * 60 + cur
                cur = 0
                parts++
            } else if (c in '0'..'9') {
                cur = cur * 10 + (c - '0')
            } else {
                return -1
            }
        }
        if (parts != 2) return -1
        return total * 60 + cur
    }

    private fun field(i: Int): String = String(line, starts[i], ends[i] - starts[i])

    private fun nextChar(): Int {
        if (pos == lim) {
            if (eof) return -1
            lim = reader.read(buf, 0, buf.size)
            pos = 0
            if (lim <= 0) {
                eof = true
                lim = 0
                return -1
            }
        }
        return buf[pos++].code
    }

    private fun peekChar(): Int {
        if (pos == lim) {
            if (eof) return -1
            lim = reader.read(buf, 0, buf.size)
            pos = 0
            if (lim <= 0) {
                eof = true
                lim = 0
                return -1
            }
        }
        return buf[pos].code
    }

    private fun append(c: Char) {
        if (lineLen == line.size) line = line.copyOf(line.size * 2)
        line[lineLen++] = c
    }

    private fun endField(start: Int) {
        if (fieldCount == starts.size) {
            starts = starts.copyOf(fieldCount * 2)
            ends = ends.copyOf(fieldCount * 2)
        }
        var s = start
        var e = lineLen
        while (s < e && line[s] <= ' ') s++
        while (e > s && line[e - 1] <= ' ') e--
        starts[fieldCount] = s
        ends[fieldCount] = e
        fieldCount++
    }

    /** Reads one logical row into [line]; false at EOF with nothing read. */
    private fun readRow(): Boolean {
        lineLen = 0
        fieldCount = 0
        var fieldStart = 0
        var inQuotes = false
        var any = false
        while (true) {
            val c = nextChar()
            if (c < 0) {
                if (!any) return false
                endField(fieldStart)
                return true
            }
            any = true
            val ch = c.toChar()
            if (inQuotes) {
                if (ch == '"') {
                    if (peekChar() == '"'.code) {
                        nextChar()
                        append('"')
                    } else {
                        inQuotes = false
                    }
                } else {
                    append(ch)
                }
            } else when (ch) {
                '"' -> inQuotes = true
                ',' -> {
                    endField(fieldStart)
                    fieldStart = lineLen
                }
                '\r' -> Unit
                '\n' -> {
                    endField(fieldStart)
                    return true
                }
                else -> append(ch)
            }
        }
    }

    override fun close() = reader.close()
}
