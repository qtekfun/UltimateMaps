package com.qtekfun.ultimatemaps.core.traffic

import java.io.ByteArrayOutputStream

/** Builds Mapbox Vector Tiles for the tests (the decoder's counterpart): one layer of line features with tags. */
object MvtEncoder {
    class Line(val points: List<Pair<Int, Int>>)
    class Feat(val tags: Map<String, Any>, val lines: List<Line>)

    fun tile(layerName: String, extent: Int, features: List<Feat>): ByteArray {
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val featureBytes = features.map { f ->
            val tags = ArrayList<Int>()
            for ((k, v) in f.tags) {
                tags += keys.indexOf(k).takeIf { it >= 0 } ?: keys.also { it += k }.lastIndex
                tags += values.indexOf(v).takeIf { it >= 0 } ?: values.also { it += v }.lastIndex
            }
            val geom = ArrayList<Int>()
            var x = 0
            var y = 0
            for (line in f.lines) {
                geom += (1 or (1 shl 3))
                geom += zz(line.points[0].first - x); geom += zz(line.points[0].second - y)
                x = line.points[0].first; y = line.points[0].second
                geom += (2 or ((line.points.size - 1) shl 3))
                for (p in line.points.drop(1)) {
                    geom += zz(p.first - x); geom += zz(p.second - y)
                    x = p.first; y = p.second
                }
            }
            msg { bytesField(2, packed(tags)); varintField(3, 2L); bytesField(4, packed(geom)) }
        }
        val layer = msg {
            bytesField(1, layerName.toByteArray())
            featureBytes.forEach { bytesField(2, it) }
            keys.forEach { bytesField(3, it.toByteArray()) }
            values.forEach { bytesField(4, value(it)) }
            varintField(5, extent.toLong())
            varintField(15, 2L)
        }
        return msg { bytesField(3, layer) }
    }

    private fun zz(n: Int) = (n shl 1) xor (n shr 31)

    private fun packed(ints: List<Int>): ByteArray {
        val o = ByteArrayOutputStream()
        ints.forEach { varint(o, it.toLong() and 0xFFFFFFFFL) }
        return o.toByteArray()
    }

    private fun value(v: Any): ByteArray = when (v) {
        is String -> msg { bytesField(1, v.toByteArray()) }
        is Double -> msg {
            tag(3, 1)
            val bits = java.lang.Double.doubleToLongBits(v)
            for (k in 0 until 8) write(((bits shr (8 * k)) and 0xFF).toInt())
        }
        is Boolean -> msg { varintField(7, if (v) 1L else 0L) }
        else -> error("unsupported $v")
    }

    private class W : ByteArrayOutputStream() {
        fun tag(field: Int, wire: Int) = varint(this, ((field shl 3) or wire).toLong())
        fun varintField(field: Int, v: Long) { tag(field, 0); varint(this, v) }
        fun bytesField(field: Int, b: ByteArray) { tag(field, 2); varint(this, b.size.toLong()); write(b) }
    }

    private fun msg(block: W.() -> Unit): ByteArray = W().also(block).toByteArray()

    private fun varint(o: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            o.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        o.write(v.toInt())
    }
}
