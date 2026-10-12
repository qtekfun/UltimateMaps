package com.qtekfun.ultimatemaps.core.transit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The drawn and followed line of one pattern (a line's ordered stop list): a polyline in micro-degrees that passes through
 * every stop, so that stop `i` sits at vertex [stopVertex]`[i]` (non-decreasing). [onShape]`[i]` says whether the piece from
 * stop `i` to stop `i + 1` follows the feed's own shape (true) or is the straight chord between the stops (false, because
 * the shape did not cover that hop). The last entry of [onShape] is unused and false.
 */
class PatternGeometry(val lat: IntArray, val lon: IntArray, val stopVertex: IntArray, val onShape: BooleanArray) {
    val vertexCount: Int get() = lat.size

    /** Number of stop-to-stop hops that follow the shape. */
    val shapeHops: Int get() = onShape.count { it }
    val hops: Int get() = stopVertex.size - 1
}

/**
 * Turns a GTFS shape and the ordered stops of a pattern into a [PatternGeometry]: every stop is placed on the shape (its
 * nearest point, chosen so that the stops follow the shape in order), the piece of the shape between consecutive stops is cut
 * out and simplified, and a hop the shape does not cover keeps the straight chord. Pure; used by the index builder only.
 *
 * Why not "nearest point of the shape" per stop: a line that doubles back (a loop, a terminus stub, a station visited twice)
 * has several near candidates, and only a choice that keeps the order along the shape is the real one. Candidates (local
 * minima of the distance, at most [MAX_CANDIDATES] per stop) are chosen by dynamic programming over the stops: the cost of
 * a choice is the distance of the stop to the shape, a stop left off the shape costs [UNMATCHED_COST_M], and the chosen
 * offsets must not decrease along the shape. The shape is tried in both directions (feeds often publish one shape per line).
 */
object ShapeMatcher {
    /** A stop farther than this from the shape is never placed on it (stations are published as a point near the tracks). */
    const val MAX_STOP_DISTANCE_M = 200.0

    /** Cost (metres) of leaving a stop off the shape; above any distance a good match has. */
    const val UNMATCHED_COST_M = 250.0

    /** Douglas-Peucker tolerance when simplifying the cut pieces. */
    const val SIMPLIFY_M = 3.0

    private const val MAX_CANDIDATES = 6
    private const val METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180.0

    /**
     * [shapeLat]/[shapeLon] are the shape points in order (degrees, indices [from] until [to] of the arrays); [stopLat]/[stopLon]
     * the pattern's stops in order. Null when fewer than two stops can be placed on the shape.
     */
    fun match(
        shapeLat: DoubleArray, shapeLon: DoubleArray, from: Int, to: Int,
        stopLat: DoubleArray, stopLon: DoubleArray,
    ): PatternGeometry? {
        val n = to - from
        val m = stopLat.size
        if (n < 2 || m < 2) return null
        val lat0 = stopLat.average()
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(lat0))
        val ky = METERS_PER_DEGREE
        val sx = DoubleArray(m) { stopLon[it] * kx }
        val sy = DoubleArray(m) { stopLat[it] * ky }
        val chord = DoubleArray(m) // chord[i]: straight distance from stop i-1 to stop i
        for (i in 1 until m) chord[i] = hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])

        var best: Chain? = null
        for (reversed in booleanArrayOf(false, true)) {
            val lat = DoubleArray(n) { shapeLat[if (reversed) to - 1 - it else from + it] }
            val lon = DoubleArray(n) { shapeLon[if (reversed) to - 1 - it else from + it] }
            val chain = chainFor(lat, lon, kx, ky, sx, sy, chord, m) ?: continue
            if (best == null || chain.cost < best.cost) best = chain
        }
        val chain = best ?: return null
        if (chain.matched < 2) return null
        return assemble(chain, stopLat, stopLon)
    }

    private class Candidate(val offset: Double, val dist: Double, val seg: Int, val t: Double)

    private class Chain(
        val cost: Double,
        val matched: Int,
        /** Candidate chosen per stop, or null when the stop is left off the shape. */
        val chosen: Array<Candidate?>,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val cum: DoubleArray,
        val xs: DoubleArray,
        val ys: DoubleArray,
    )

    private fun chainFor(
        lat: DoubleArray, lon: DoubleArray, kx: Double, ky: Double,
        sx: DoubleArray, sy: DoubleArray, chord: DoubleArray, m: Int,
    ): Chain? {
        val n = lat.size
        val xs = DoubleArray(n) { lon[it] * kx }
        val ys = DoubleArray(n) { lat[it] * ky }
        val cum = DoubleArray(n)
        for (j in 1 until n) cum[j] = cum[j - 1] + hypot(xs[j] - xs[j - 1], ys[j] - ys[j - 1])

        // candidates per stop
        val cands = Array(m) { i -> candidates(xs, ys, cum, sx[i], sy[i]) }
        // DP: best[i][k] = cost of the best chain ending with stop i placed at candidate k
        val best = Array(m) { DoubleArray(cands[it].size) }
        val prevStop = Array(m) { IntArray(cands[it].size) { -1 } }
        val prevCand = Array(m) { IntArray(cands[it].size) { -1 } }
        // pathLen[i]: sum of chords up to stop i, to know the chord length between two stops
        val pathLen = DoubleArray(m)
        for (i in 1 until m) pathLen[i] = pathLen[i - 1] + chord[i]
        for (i in 0 until m) {
            for ((k, c) in cands[i].withIndex()) {
                var cost = UNMATCHED_COST_M * i + c.dist // everything before is unmatched
                var ps = -1
                var pc = -1
                for (j in 0 until i) {
                    for ((l, p) in cands[j].withIndex()) {
                        val gap = c.offset - p.offset
                        val chordLen = hypot(sx[i] - sx[j], sy[i] - sy[j])
                        val pathChord = pathLen[i] - pathLen[j]
                        if (gap < -1.0) continue // the shape must be followed in order
                        if (gap < chordLen * 0.6 - 150.0) continue // too short to be the same ride
                        if (gap > pathChord * 3.0 + 500.0) continue // a detour no vehicle makes between these stops
                        val total = best[j][l] + UNMATCHED_COST_M * (i - j - 1) + c.dist
                        if (total < cost) {
                            cost = total
                            ps = j
                            pc = l
                        }
                    }
                }
                best[i][k] = cost
                prevStop[i][k] = ps
                prevCand[i][k] = pc
            }
        }
        // the best end: the last matched stop is i, the stops after it are unmatched
        var endCost = UNMATCHED_COST_M * m // nothing matched
        var endStop = -1
        var endCand = -1
        for (i in 0 until m) {
            for (k in cands[i].indices) {
                val total = best[i][k] + UNMATCHED_COST_M * (m - 1 - i)
                if (total < endCost) {
                    endCost = total
                    endStop = i
                    endCand = k
                }
            }
        }
        if (endStop < 0) return null
        val chosen = arrayOfNulls<Candidate>(m)
        var matched = 0
        var i = endStop
        var k = endCand
        while (i >= 0) {
            chosen[i] = cands[i][k]
            matched++
            val ni = prevStop[i][k]
            val nk = prevCand[i][k]
            i = ni
            k = nk
        }
        return Chain(endCost, matched, chosen, lat, lon, cum, xs, ys)
    }

    /** Local minima of the distance from the stop to the shape, nearest first, at most [MAX_CANDIDATES], in shape order. */
    private fun candidates(xs: DoubleArray, ys: DoubleArray, cum: DoubleArray, px: Double, py: Double): List<Candidate> {
        val n = xs.size
        val segs = n - 1
        val dist = DoubleArray(segs)
        val tt = DoubleArray(segs)
        for (j in 0 until segs) {
            val bx = xs[j + 1] - xs[j]
            val by = ys[j + 1] - ys[j]
            val l2 = bx * bx + by * by
            val ax = px - xs[j]
            val ay = py - ys[j]
            val t = if (l2 < 1e-9) 0.0 else ((ax * bx + ay * by) / l2).coerceIn(0.0, 1.0)
            tt[j] = t
            dist[j] = hypot(ax - t * bx, ay - t * by)
        }
        val found = ArrayList<Candidate>()
        for (j in 0 until segs) {
            if (dist[j] > MAX_STOP_DISTANCE_M) continue
            val leftOk = j == 0 || dist[j] <= dist[j - 1]
            val rightOk = j == segs - 1 || dist[j] < dist[j + 1]
            if (!(leftOk && rightOk)) continue
            val len = cum[j + 1] - cum[j]
            found.add(Candidate(cum[j] + tt[j] * len, dist[j], j, tt[j]))
        }
        // a vertex shared by two segments gives the same point twice: keep one per 5 m of offset
        found.sortBy { it.dist }
        val kept = ArrayList<Candidate>()
        for (c in found) {
            if (kept.size >= MAX_CANDIDATES) break
            if (kept.none { abs(it.offset - c.offset) < 5.0 }) kept.add(c)
        }
        kept.sortBy { it.offset }
        return kept
    }

    private fun assemble(chain: Chain, stopLat: DoubleArray, stopLon: DoubleArray): PatternGeometry {
        val m = stopLat.size
        val outLat = ArrayList<Int>()
        val outLon = ArrayList<Int>()
        val stopVertex = IntArray(m)
        val onShape = BooleanArray(m)
        fun micro(v: Double) = Math.round(v * 1e6).toInt()
        fun addPoint(la: Double, lo: Double) {
            val a = micro(la)
            val b = micro(lo)
            if (outLat.isNotEmpty() && outLat.last() == a && outLon.last() == b) return
            outLat.add(a)
            outLon.add(b)
        }
        fun snap(c: Candidate): Pair<Double, Double> {
            val la = chain.lat[c.seg] + c.t * (chain.lat[c.seg + 1] - chain.lat[c.seg])
            val lo = chain.lon[c.seg] + c.t * (chain.lon[c.seg + 1] - chain.lon[c.seg])
            return la to lo
        }
        for (i in 0 until m) {
            val c = chain.chosen[i]
            if (i > 0) {
                val p = chain.chosen[i - 1]
                if (p != null && c != null && c.offset >= p.offset) {
                    // the shape between the two snapped stops, interior vertices only, simplified
                    val interior = ArrayList<Int>()
                    for (v in p.seg + 1..c.seg) {
                        if (v >= chain.lat.size) break
                        interior.add(v)
                    }
                    val (pl, po) = snap(p)
                    val (cl, co) = snap(c)
                    val pts = ArrayList<DoubleArray>(interior.size + 2)
                    pts.add(doubleArrayOf(pl, po))
                    for (v in interior) pts.add(doubleArrayOf(chain.lat[v], chain.lon[v]))
                    pts.add(doubleArrayOf(cl, co))
                    val kept = simplify(pts, chain.lat.average())
                    for (q in 1 until kept.size) addPoint(kept[q][0], kept[q][1])
                    onShape[i - 1] = true
                } else {
                    // a hop the shape does not cover: the straight chord (the endpoint is added below)
                }
            }
            val (la, lo) = if (c != null) snap(c) else stopLat[i] to stopLon[i]
            addPoint(la, lo)
            stopVertex[i] = outLat.size - 1
        }
        return PatternGeometry(outLat.toIntArray(), outLon.toIntArray(), stopVertex, onShape)
    }

    /** Douglas-Peucker on (lat, lon) degree pairs with [SIMPLIFY_M] tolerance, keeping the first and last point. */
    private fun simplify(pts: List<DoubleArray>, lat0: Double): List<DoubleArray> {
        if (pts.size <= 2) return pts
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(lat0))
        val ky = METERS_PER_DEGREE
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = pts[a][1] * kx
            val ay = pts[a][0] * ky
            val bx = pts[b][1] * kx - ax
            val by = pts[b][0] * ky - ay
            val l2 = bx * bx + by * by
            var far = -1
            var farD = SIMPLIFY_M
            for (i in a + 1 until b) {
                val px = pts[i][1] * kx - ax
                val py = pts[i][0] * ky - ay
                val t = if (l2 < 1e-9) 0.0 else ((px * bx + py * by) / l2).coerceIn(0.0, 1.0)
                val d = sqrt((px - t * bx) * (px - t * bx) + (py - t * by) * (py - t * by))
                if (d > farD) {
                    farD = d
                    far = i
                }
            }
            if (far >= 0) {
                keep[far] = true
                stack.add(intArrayOf(a, far))
                stack.add(intArrayOf(far, b))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }
}
