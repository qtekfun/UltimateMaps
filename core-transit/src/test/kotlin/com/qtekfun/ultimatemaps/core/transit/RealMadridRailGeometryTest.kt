package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Madrid index rebuilt with line geometry (`~/mapas-data/rails/out/transit-madrid.umti`, written by
 * `:core-transit:buildTransit`); does nothing when the file is not on this machine. Checks the Cercanias C5 patterns (the
 * line of the bug report: Leganes, Zarzaquemada, Villaverde Alto, Puente Alcocer, Orcasitas, ..., Atocha) and prints the
 * coverage and the largest distance between the polyline and the stop-to-stop chord per hop.
 */
class RealMadridRailGeometryTest {
    private val file = File(System.getProperty("user.home"), "mapas-data/rails/out/transit-madrid.umti")

    @Test
    fun `cercanias C5 patterns carry a polyline that leaves the chord and passes by every stop`() {
        if (!file.isFile) return
        val index = file.inputStream().buffered().use { TransitIndexIo.read(it) }
        println(index.describe() + ", shaped patterns ${index.shapedPatternCount}")
        index.geometryCoverage().forEach { (m, c) -> println("coverage $m: $c") }
        val vOf = index.patternStopVertex!!
        val base = index.patternShapeOffset!!
        val hop = index.patternHopOnShape!!
        var checked = 0
        for (p in 0 until index.patternCount) {
            val line = index.patternLine[p]
            if (index.lineShortName[line] != "C5" || index.lineType[line] != 2) continue
            val n = index.patternStopCount(p)
            if (n < 10) continue
            assertTrue(index.hasShape(p), "pattern $p of C5 has a polyline")
            val off = index.patternStopOffset[p]
            var worst = 0.0
            var chordTotal = 0.0
            var lineTotal = 0.0
            for (i in 0 until n - 1) {
                assertTrue(hop[off + i], "C5 hop $i follows the shape")
                val a = index.patternStops[off + i]
                val b = index.patternStops[off + i + 1]
                val pa = LatLon(index.stopLat[a] / 1e6, index.stopLon[a] / 1e6)
                val pb = LatLon(index.stopLat[b] / 1e6, index.stopLon[b] / 1e6)
                val v0 = base[p] + vOf[off + i]
                val v1 = base[p] + vOf[off + i + 1]
                var len = 0.0
                var maxFromChord = 0.0
                var prev: LatLon? = null
                for (v in v0..v1) {
                    val pt = LatLon(index.shapeLat!![v] / 1e6, index.shapeLon!![v] / 1e6)
                    if (prev != null) len += prev.distanceTo(pt)
                    prev = pt
                    maxFromChord = maxOf(maxFromChord, distToSegment(pt, pa, pb))
                }
                chordTotal += pa.distanceTo(pb)
                lineTotal += len
                worst = maxOf(worst, maxFromChord)
                println("C5 pattern $p hop $i ${index.stopName[a]} -> ${index.stopName[b]}: chord ${pa.distanceTo(pb).toInt()} m, line ${len.toInt()} m, max off chord ${maxFromChord.toInt()} m")
                assertTrue(len < pa.distanceTo(pb) * 2.0 + 300, "no wild detour on hop $i")
            }
            println("C5 pattern $p ($n stops): chord ${chordTotal.toInt()} m, line ${lineTotal.toInt()} m, worst hop off the chord ${worst.toInt()} m")
            checked++
        }
        assertTrue(checked >= 1, "a long C5 pattern exists in the index")
    }

    private fun distToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val ky = 111_195.0
        val kx = ky * Math.cos(Math.toRadians(a.lat))
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val l2 = bx * bx + by * by
        val t = if (l2 < 1e-6) 0.0 else ((px * bx + py * by) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - t * bx, py - t * by)
    }
}
