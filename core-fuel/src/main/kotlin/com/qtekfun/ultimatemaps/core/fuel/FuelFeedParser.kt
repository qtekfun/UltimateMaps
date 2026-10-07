package com.qtekfun.ultimatemaps.core.fuel

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/** One station of a per-fuel file, with the price of that fuel only. */
data class RawStation(
    val id: String,
    val brand: String,
    val address: String,
    val municipality: String,
    val province: String,
    val location: LatLon,
    val schedule: String?,
    val price: Double,
)

/** Parsed per-fuel file. [skipped] counts entries dropped for lacking an id, a position or a valid price. */
data class FuelFeed(val serviceDate: String?, val stations: List<RawStation>, val skipped: Int)

/** The body is not what we expect (truncated, not JSON, an error answer, or nothing usable). */
class FuelParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parser of `EstacionesTerrestres/FiltroProducto/{id}` (JSON). Streaming: the body is read once and one station
 * object at a time is kept, never the whole document (the biggest file is about 10 MB). Tolerant of what the
 * (undocumented) service has been seen to do: a UTF-8 BOM, decimal commas, empty prices, numbers instead of
 * strings, unknown or missing fields, other key order. Strict about truncation: the closing of the document
 * must be there, otherwise the whole file is rejected (the caller then keeps the last good data).
 */
object FuelFeedParser {
    private const val MAX_STATIONS = 100_000
    private const val MAX_STRING = 4_096
    private const val MAX_FIELDS = 64
    private const val MAX_DEPTH = 32

    /** [fuel], when given, also enables the national-file fallback for the price (see [FuelTypes.nationalField]). */
    fun parse(input: InputStream, fuel: FuelType? = null): FuelFeed =
        parse(InputStreamReader(input, Charsets.UTF_8).buffered(32 * 1024), fuel)

    fun parse(text: String, fuel: FuelType? = null): FuelFeed = parse(text.reader().buffered(), fuel)

    fun parse(reader: Reader, fuel: FuelType? = null): FuelFeed {
        val priceKeys = listOfNotNull("PrecioProducto", fuel?.let { FuelTypes.nationalField(it.id) })
        val s = Stream(reader)
        try {
            s.skipBom()
            s.expect('{')
            var date: String? = null
            var result: String? = null
            var list: List<RawStation>? = null
            var skipped = 0
            if (s.peekNonWs() == '}') throw FuelParseException("empty object")
            while (true) {
                val key = s.readString()
                s.expect(':')
                when (key) {
                    "Fecha" -> date = s.readScalarOrSkip()
                    "ResultadoConsulta" -> result = s.readScalarOrSkip()
                    "ListaEESSPrecio" -> {
                        if (s.peekNonWs() == '[') {
                            val out = ArrayList<RawStation>()
                            s.expect('[')
                            if (s.peekNonWs() == ']') s.expect(']') else {
                                while (true) {
                                    val fields = s.readFlatObject(MAX_FIELDS)
                                    val st = toStation(fields, priceKeys)
                                    if (st != null) out += st else skipped++
                                    if (out.size + skipped > MAX_STATIONS) throw FuelParseException("too many stations")
                                    if (s.nextSeparator(']')) break
                                }
                            }
                            list = out
                        } else s.skipValue(MAX_DEPTH) // null or something else: treated as missing
                    }
                    else -> s.skipValue(MAX_DEPTH)
                }
                if (s.nextSeparator('}')) break
            }
            if (result != null && !result.equals("OK", ignoreCase = true)) throw FuelParseException("service answered: $result")
            val stations = list ?: throw FuelParseException("no station list")
            if (stations.isEmpty() && skipped > 0) throw FuelParseException("no usable station among $skipped entries")
            return FuelFeed(date, stations, skipped)
        } catch (e: FuelParseException) {
            throw e
        } catch (e: java.io.IOException) {
            throw e // network or size limit: not a format problem, the caller classifies it
        } catch (e: Exception) {
            throw FuelParseException("malformed document: ${e.message}", e)
        }
    }

    private fun toStation(f: Map<String, String?>, priceKeys: List<String>): RawStation? {
        val id = f["IDEESS"]?.trim().orEmpty()
        if (id.isEmpty()) return null
        val price = priceKeys.firstNotNullOfOrNull { parseDecimal(f[it]) }?.takeIf { it > 0.0 && it < 100.0 } ?: return null
        val lat = parseDecimal(f["Latitud"]) ?: return null
        val lon = parseDecimal(f["Longitud (WGS84)"]) ?: parseDecimal(f["Longitud"]) ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val location = LatLon.ofOrNull(lat, lon) ?: return null
        fun text(k: String) = f[k]?.trim().orEmpty()
        return RawStation(
            id = id,
            brand = text("Rótulo"),
            address = text("Dirección"),
            municipality = text("Municipio").ifEmpty { text("Localidad") },
            province = text("Provincia"),
            location = location,
            schedule = text("Horario").ifEmpty { null },
            price = price,
        )
    }

    /** "1,849" -> 1.849; "1.234,5" -> 1234.5; blank, "-" or garbage -> null. */
    fun parseDecimal(raw: String?): Double? {
        var t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.indexOf(',') >= 0) t = t.replace(".", "").replace(',', '.')
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Minimal JSON tokenizer over a [Reader]. Throws on truncation (EOF where a token is needed). */
    private class Stream(private val r: Reader) {
        private var peeked = -2

        private fun read(): Int {
            if (peeked != -2) return peeked.also { peeked = -2 }
            return r.read()
        }

        private fun peek(): Int {
            if (peeked == -2) peeked = r.read()
            return peeked
        }

        fun skipBom() {
            if (peek() == 0xFEFF) read()
        }

        private fun nextNonWs(): Int {
            while (true) {
                val c = read()
                if (c == -1) throw FuelParseException("truncated document")
                if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
            }
        }

        fun peekNonWs(): Char {
            val c = nextNonWs()
            peeked = c
            return c.toChar()
        }

        fun expect(ch: Char) {
            val c = nextNonWs()
            if (c != ch.code) throw FuelParseException("expected '$ch' but found '${c.toChar()}'")
        }

        /** After a value: consumes ',' (returns false: more) or [close] (returns true: done). */
        fun nextSeparator(close: Char): Boolean {
            val c = nextNonWs()
            return when (c) {
                ','.code -> false
                close.code -> true
                else -> throw FuelParseException("expected ',' or '$close' but found '${c.toChar()}'")
            }
        }

        fun readString(): String {
            expect('"')
            return readStringBody()
        }

        private fun readStringBody(): String {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) throw FuelParseException("truncated document")
                when (c) {
                    '"'.code -> return sb.toString()
                    '\\'.code -> {
                        val e = read()
                        val ch = when (e) {
                            -1 -> throw FuelParseException("truncated document")
                            'n'.code -> '\n'
                            't'.code -> '\t'
                            'r'.code -> '\r'
                            'b'.code -> '\b'
                            'f'.code -> '\u000C'
                            'u'.code -> {
                                var v = 0
                                repeat(4) {
                                    val h = Character.digit(read(), 16)
                                    if (h < 0) throw FuelParseException("bad unicode escape")
                                    v = v * 16 + h
                                }
                                v.toChar()
                            }
                            else -> e.toChar() // \" \\ \/
                        }
                        if (sb.length < MAX_STRING) sb.append(ch)
                    }
                    else -> if (sb.length < MAX_STRING) sb.append(c.toChar())
                }
            }
        }

        /** A string, number, true/false (as text) or null (as null); anything nested is skipped (returns null). */
        fun readScalarOrSkip(): String? {
            val c = peekNonWs()
            return when {
                c == '"' -> { read(); readStringBody() }
                c == '{' || c == '[' -> { skipValue(MAX_DEPTH); null }
                else -> readBare().takeIf { it != "null" }
            }
        }

        private fun readBare(): String {
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
                if (sb.length >= 64) throw FuelParseException("token too long")
                sb.append(read().toChar())
            }
            if (sb.isEmpty()) throw FuelParseException("unexpected character")
            return sb.toString()
        }

        /** Reads an object whose values are scalars (nested values are skipped and dropped). */
        fun readFlatObject(maxFields: Int): Map<String, String?> {
            expect('{')
            val out = HashMap<String, String?>(40)
            if (peekNonWs() == '}') { read(); return out }
            while (true) {
                val key = readString()
                expect(':')
                val v = readScalarOrSkip()
                if (out.size < maxFields) out[key] = v
                if (nextSeparator('}')) return out
            }
        }

        fun skipValue(maxDepth: Int) {
            val c = peekNonWs()
            if (c != '{' && c != '[') { readScalarOrSkip(); return }
            var depth = 0
            do {
                val ch = nextNonWs()
                when (ch) {
                    '"'.code -> readStringBody()
                    '{'.code, '['.code -> { depth++; if (depth > maxDepth) throw FuelParseException("nesting too deep") }
                    '}'.code, ']'.code -> depth--
                }
            } while (depth > 0)
        }
    }
}
