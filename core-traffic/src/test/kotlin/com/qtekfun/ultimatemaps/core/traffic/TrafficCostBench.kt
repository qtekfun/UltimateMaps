package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.util.Random

/**
 * Not a test: a hand-run benchmark of what the traffic layer does for a view (`main`), meant to be dexed and run on a phone
 * with `dalvikvm`. It builds synthetic tiles shaped like real flow tiles (many short lines, most of them minor roads, most
 * of them flowing) and times the old way (every line, every point) against the way the layer uses now.
 */
object TrafficCostBench {
    private val categories = listOf("motorway" to 2, "primary" to 5, "secondary" to 10, "tertiary" to 15, "street" to 40, "service" to 28)

    private fun tile(random: Random, features: Int): List<MvtLayer> {
        val list = ArrayList<MvtFeature>(features)
        repeat(features) {
            var roll = random.nextInt(100)
            var category = "service"
            for ((c, w) in categories) { if (roll < w) { category = c; break }; roll -= w }
            val r = random.nextInt(100)
            val ratio = if (r < 85) 0.8 + random.nextDouble() * 0.2 else if (r < 95) 0.45 + random.nextDouble() * 0.3 else random.nextDouble() * 0.38 + 0.01
            val n = 8 + random.nextInt(25)
            var x = random.nextInt(4096)
            var y = random.nextInt(4096)
            val pts = ArrayList<IntArray>(n)
            repeat(n) { pts += intArrayOf(x, y); x += random.nextInt(11) - 5; y += random.nextInt(11) - 5 } // a point every few units: dense, like a real curve
            list += MvtFeature(mapOf("relative_speed" to ratio, "road_category" to category), listOf(pts))
        }
        return listOf(MvtLayer("Traffic flow", 4096, list))
    }

    /** The first version: every line of every tile, every point, no filter, no cap. */
    private fun old(tiles: List<Pair<TileId, List<MvtLayer>>>): Pair<Int, Int> {
        val out = ArrayList<FlowLine>()
        for ((id, layers) in tiles) for (l in layers) for (f in l.features) {
            val ratio = (f.properties["relative_speed"] as Number).toDouble()
            for (line in f.lines) out += FlowLine(FlowLines.levelOf(ratio, false), line.map { TileMath.toLatLon(id, it[0].toDouble(), it[1].toDouble(), l.extent) })
        }
        val sorted = out.sortedBy { it.level }
        return sorted.size to FlowGeoJson.of(sorted).length
    }

    private fun new(tiles: List<Pair<TileId, List<MvtLayer>>>): Pair<Int, Int> {
        val out = ArrayList<FlowLine>()
        for ((id, layers) in tiles) out += FlowLines.of(id, layers)
        val limited = FlowLines.limit(out)
        return limited.size to FlowGeoJson.of(limited).length
    }

    private fun time(runs: Int, block: () -> Pair<Int, Int>): Triple<Long, Int, Int> {
        var last = 0 to 0
        val ms = ArrayList<Long>()
        repeat(runs) { val t = System.nanoTime(); last = block(); ms += (System.nanoTime() - t) / 1_000_000 }
        ms.sort()
        return Triple(ms[ms.size / 2], last.first, last.second)
    }

    @JvmStatic fun main(args: Array<String>) {
        val random = Random(42)
        println("features/tile  tiles | old: ms lines KB | new: ms lines KB")
        for (perTile in listOf(500, 1500, 4000)) {
            val tiles = (0 until 16).map { TileId(13, 4000 + it % 4, 3000 + it / 4) to tile(random, perTile) }
            time(3) { old(tiles) }; time(3) { new(tiles) } // warm-up: ART compiles on use
            val o = time(7) { old(tiles) }
            val n = time(7) { new(tiles) }
            println("%13d  %5d | %6d %6d %5d | %6d %5d %5d".format(perTile, tiles.size, o.first, o.second, o.third / 1024, n.first, n.second, n.third / 1024))
        }
    }
}
