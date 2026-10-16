package com.qtekfun.ultimatemaps.core.traffic

/** A feature of a Mapbox Vector Tile: its tags and its lines (tile coordinates). */
class MvtFeature(val properties: Map<String, Any>, val lines: List<List<IntArray>>)

/** A decoded layer. */
class MvtLayer(val name: String, val extent: Int, val features: List<MvtFeature>)

/**
 * A minimal reader of the Mapbox Vector Tile protobuf format (the one TomTom's flow tiles use): layers, string/number/bool tags and
 * line geometries. Points and polygons are skipped (flow tiles have only lines). Pure; throws [IllegalArgumentException] on a
 * damaged tile.
 */
object Mvt {
    fun decode(bytes: ByteArray): List<MvtLayer> {
        val layers = ArrayList<MvtLayer>()
        val r = Reader(bytes)
        while (r.more()) {
            val tag = r.varint().toInt()
            if (tag shr 3 == 3 && tag and 7 == 2) layers += layer(r.bytes()) else r.skip(tag and 7)
        }
        return layers
    }

    private fun layer(b: ByteArray): MvtLayer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val rawFeatures = ArrayList<ByteArray>()
        val r = Reader(b)
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                1 -> name = String(r.bytes(), Charsets.UTF_8)
                2 -> rawFeatures += r.bytes()
                3 -> keys += String(r.bytes(), Charsets.UTF_8)
                4 -> values += value(r.bytes())
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        return MvtLayer(name, extent, rawFeatures.mapNotNull { feature(it, keys, values) })
    }

    private fun value(b: ByteArray): Any {
        val r = Reader(b)
        var out: Any = ""
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                1 -> out = String(r.bytes(), Charsets.UTF_8)
                2 -> out = java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
                3 -> out = java.lang.Double.longBitsToDouble(r.fixed64())
                4 -> out = r.varint()
                5 -> out = r.varint()
                6 -> out = zigzag(r.varint())
                7 -> out = r.varint() != 0L
                else -> r.skip(tag and 7)
            }
        }
        return out
    }

    private fun feature(b: ByteArray, keys: List<String>, values: List<Any>): MvtFeature? {
        var type = 0
        var tags = IntArray(0)
        var geometry = IntArray(0)
        val r = Reader(b)
        while (r.more()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                2 -> tags = r.packed()
                3 -> type = r.varint().toInt()
                4 -> geometry = r.packed()
                else -> r.skip(tag and 7)
            }
        }
        if (type != 2) return null // lines only
        val props = HashMap<String, Any>()
        var i = 0
        while (i + 1 < tags.size) {
            val k = keys.getOrNull(tags[i])
            val v = values.getOrNull(tags[i + 1])
            if (k != null && v != null) props[k] = v
            i += 2
        }
        return MvtFeature(props, lines(geometry))
    }

    /** MoveTo (1) starts a line, LineTo (2) extends it; parameters are zigzag deltas. */
    private fun lines(g: IntArray): List<List<IntArray>> {
        val out = ArrayList<List<IntArray>>()
        var cur = ArrayList<IntArray>()
        var x = 0
        var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (cmd) {
                1, 2 -> repeat(count) {
                    require(i + 1 < g.size) { "truncated geometry" }
                    x += zigzagInt(g[i]); y += zigzagInt(g[i + 1]); i += 2
                    if (cmd == 1) {
                        if (cur.size >= 2) out += cur
                        cur = ArrayList()
                    }
                    cur += intArrayOf(x, y)
                }
                7 -> Unit
                else -> throw IllegalArgumentException("unknown geometry command $cmd")
            }
        }
        if (cur.size >= 2) out += cur
        return out
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)
    private fun zigzagInt(n: Int): Int = (n ushr 1) xor -(n and 1)

    private class Reader(private val b: ByteArray) {
        private var p = 0
        fun more() = p < b.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(p < b.size) { "truncated varint" }
                val c = b[p++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        fun bytes(): ByteArray {
            val n = varint().toInt()
            require(n >= 0 && p + n <= b.size) { "truncated field" }
            return b.copyOfRange(p, p + n).also { p += n }
        }

        fun packed(): IntArray {
            val inner = Reader(bytes())
            val out = ArrayList<Int>()
            while (inner.more()) out += inner.varint().toInt()
            return out.toIntArray()
        }

        fun fixed32(): Int {
            require(p + 4 <= b.size) { "truncated fixed32" }
            var v = 0
            for (k in 0 until 4) v = v or ((b[p + k].toInt() and 0xFF) shl (8 * k))
            p += 4
            return v
        }

        fun fixed64(): Long {
            require(p + 8 <= b.size) { "truncated fixed64" }
            var v = 0L
            for (k in 0 until 8) v = v or ((b[p + k].toLong() and 0xFF) shl (8 * k))
            p += 8
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> fixed64()
                2 -> bytes()
                5 -> fixed32()
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }
    }
}
