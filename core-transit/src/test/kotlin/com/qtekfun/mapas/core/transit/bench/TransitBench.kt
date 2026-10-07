package com.qtekfun.mapas.core.transit.bench

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.transit.FeedOptions
import com.qtekfun.mapas.core.transit.GtfsReadOptions
import com.qtekfun.mapas.core.transit.GtfsReader
import com.qtekfun.mapas.core.transit.Journey
import com.qtekfun.mapas.core.transit.TransitIndex
import com.qtekfun.mapas.core.transit.TransitIndexBuilder
import com.qtekfun.mapas.core.transit.TransitIndexIo
import com.qtekfun.mapas.core.transit.TransitPlanner
import com.qtekfun.mapas.core.transit.ZipGtfsSource
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/**
 * Measured run on the real Madrid feeds (spike). Usage:
 * `./gradlew :core-transit:transitBench -PtransitData=$HOME/mapas-data/transit/raw`
 * Prints everything it measures; nothing is hard-coded.
 */

private class FeedSpec(val zip: String, val label: String, val ns: String, val attribution: String, val ignoreCalendar: Boolean, val bbox: Boolean)

private val MADRID_BBOX = { lat: Double, lon: Double -> lat in 39.8..41.2 && lon in -4.6..-3.0 }

private val FEEDS = listOf(
    FeedSpec("crtm_metro.zip", "CRTM Metro de Madrid", "crtm", "Powered by CRTM (https://www.crtm.es/), processed data", true, false),
    FeedSpec("crtm_metroligero.zip", "CRTM Metro Ligero", "crtm", "Powered by CRTM (https://www.crtm.es/), processed data", false, false),
    FeedSpec("crtm_emt.zip", "CRTM EMT Madrid buses", "emt", "Powered by CRTM (https://www.crtm.es/), processed data", false, false),
    FeedSpec("crtm_interurban.zip", "CRTM interurban buses", "crtm", "Powered by CRTM (https://www.crtm.es/), processed data", false, false),
    FeedSpec("crtm_urban_other.zip", "CRTM other urban buses", "crtm", "Powered by CRTM (https://www.crtm.es/), processed data", false, false),
    FeedSpec("renfe_cercanias.zip", "Renfe Cercanias (Madrid bbox)", "renfe", "Renfe Operadora, CC BY 4.0, processed data", false, true),
)

private class Place(val name: String, val lat: Double, val lon: Double)

// Coordinates of well-known places: approximate (general knowledge) except Alcala, Colmenar, Vallecas and Rivas, which are the
// Renfe / Metro station coordinates read from the downloaded feeds.
private val PLACES = mapOf(
    "Sol" to Place("Puerta del Sol", 40.4169, -3.7035),
    "Atocha" to Place("Atocha station", 40.4066, -3.6892),
    "Chamartin" to Place("Chamartin station", 40.4721, -3.6826),
    "NuevosMin" to Place("Nuevos Ministerios", 40.4466, -3.6921),
    "PrincipePio" to Place("Principe Pio", 40.4210, -3.7208),
    "PlazaCastilla" to Place("Plaza de Castilla", 40.4658, -3.6890),
    "Moncloa" to Place("Moncloa", 40.4348, -3.7189),
    "T4" to Place("Airport T4", 40.4913, -3.5930),
    "Alcala" to Place("Alcala de Henares (Renfe station)", 40.4891, -3.3662),
    "Getafe" to Place("Getafe centro", 40.3057, -3.7320),
    "Alcobendas" to Place("Alcobendas", 40.5475, -3.6420),
    "Leganes" to Place("Leganes", 40.3279, -3.7635),
    "LasRozas" to Place("Las Rozas", 40.4929, -3.8738),
    "Vallecas" to Place("Puente de Vallecas (Metro)", 40.3982, -3.6691),
    "Elliptica" to Place("Plaza Eliptica", 40.3852, -3.7184),
    "Bernabeu" to Place("Santiago Bernabeu", 40.4530, -3.6883),
    "Retiro" to Place("Puerta de Alcala", 40.4200, -3.6888),
    "Torrejon" to Place("Torrejon de Ardoz", 40.4590, -3.4790),
    "Alcorcon" to Place("Alcorcon", 40.3499, -3.8244),
    "Fuenlabrada" to Place("Fuenlabrada", 40.2842, -3.7942),
    "Mostoles" to Place("Mostoles", 40.3224, -3.8650),
    "Rivas" to Place("Rivas Futura (Metro)", 40.34134, -3.52479),
    "Colmenar" to Place("Colmenar Viejo (Renfe station)", 40.6452, -3.7766),
    "Aranjuez" to Place("Aranjuez", 40.0330, -3.6020),
)

private val PAIRS = listOf(
    "Sol" to "Atocha", "Chamartin" to "NuevosMin", "Sol" to "PrincipePio", "PlazaCastilla" to "Moncloa",
    "Atocha" to "T4", "Chamartin" to "Atocha", "Moncloa" to "Vallecas", "Bernabeu" to "Retiro",
    "Sol" to "Getafe", "Alcobendas" to "Sol", "Leganes" to "NuevosMin", "LasRozas" to "Atocha",
    "Alcala" to "Chamartin", "Torrejon" to "Sol", "Elliptica" to "PlazaCastilla", "Alcorcon" to "Moncloa",
    "Fuenlabrada" to "Atocha", "Mostoles" to "PrincipePio", "Rivas" to "Vallecas", "Colmenar" to "Chamartin",
)

private fun rssKb(field: String): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith(field) }
    ?.filter { it.isDigit() }?.toLongOrNull() ?: -1

private fun usedHeapMb(): Double {
    repeat(3) { System.gc() }
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
}

private fun hm(t: Int) = "%02d:%02d".format(t / 3600 % 24, t / 60 % 60)

fun main(args: Array<String>) {
    val dir = File(args.firstOrNull().orEmpty().ifEmpty { System.getProperty("user.home") + "/mapas-data/transit/raw" })
    val outDir = dir.parentFile
    val indexFile = File(outDir, "madrid.umti")
    println("== machine")
    println("cpu: " + File("/proc/cpuinfo").readLines().first { it.startsWith("model name") }.substringAfter(": "))
    println("logical cores: ${Runtime.getRuntime().availableProcessors()}, RAM total: " + File("/proc/meminfo").readLines().first().substringAfter(":").trim())
    println("jvm: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, max heap ${Runtime.getRuntime().maxMemory() / 1048576} MB")

    println("== build")
    val builder = TransitIndexBuilder()
    val t0 = System.nanoTime()
    for (f in FEEDS) {
        val zip = File(dir, f.zip)
        val ts = System.nanoTime()
        val feed = GtfsReader.read(ZipGtfsSource(zip), GtfsReadOptions(stopFilter = if (f.bbox) MADRID_BBOX else null, ignoreCalendarRange = f.ignoreCalendar))
        val tr = System.nanoTime()
        builder.addFeed(feed, FeedOptions(f.label, f.ns, f.attribution), f.ignoreCalendar)
        val te = System.nanoTime()
        println(
            "%-32s zip %6.1f MB  trips %7d  stopTimes %8d  freqWindows %6d  read %6.1f s  merge %5.1f s".format(
                f.label, zip.length() / 1048576.0, feed.tripIds.size, feed.stTimeStop.size, feed.freqTrip.size, (tr - ts) / 1e9, (te - tr) / 1e9,
            ),
        )
    }
    val tb = System.nanoTime()
    var index: TransitIndex? = builder.build()
    val buildSec = (System.nanoTime() - t0) / 1e9
    println("index finalise %.1f s; total read+build %.1f s".format((System.nanoTime() - tb) / 1e9, buildSec))
    println(index!!.describe())
    println("peak RSS of this JVM so far (VmHWM): ${rssKb("VmHWM") / 1024} MB")

    println("== file")
    val tw = System.nanoTime()
    indexFile.outputStream().use { TransitIndexIo.write(index!!, it) }
    println("write %.1f s; raw size %.2f MB".format((System.nanoTime() - tw) / 1e9, indexFile.length() / 1048576.0))
    val gz = File(outDir, "madrid.umti.gz")
    val tg = System.nanoTime()
    GZIPOutputStream(gz.outputStream()).use { out -> indexFile.inputStream().use { it.copyTo(out) } }
    println("gzip size %.2f MB (gzip %.1f s)".format(gz.length() / 1048576.0, (System.nanoTime() - tg) / 1e9))
    gz.delete()
    for (s in index!!.sources) println("source: ${s.label} version=${s.version} calendarRangeIgnored=${s.calendarRangeIgnored} attribution='${s.attribution}'")

    println("== load")
    builder.let { }
    index = null
    val base = usedHeapMb()
    val tl = System.nanoTime()
    val loaded = indexFile.inputStream().use { TransitIndexIo.read(it) }
    val loadSec = (System.nanoTime() - tl) / 1e9
    val afterLoad = usedHeapMb()
    println("load %.2f s; heap held by the loaded index ~ %.0f MB (before %.0f, after %.0f; builder still referenced? no)".format(loadSec, afterLoad - base, base, afterLoad))
    val tp = System.nanoTime()
    val planner = TransitPlanner(loaded)
    val planSec = (System.nanoTime() - tp) / 1e9
    val afterPlanner = usedHeapMb()
    println("planner init %.2f s (footpath edges %d); extra heap ~ %.0f MB".format(planSec, planner.footpathCount, afterPlanner - afterLoad))

    println("== sanity of coverage")
    val day = LocalDate.of(2026, 10, 14).toEpochDay().toInt()
    println("query date 2026-10-14 (Wednesday), departure 09:00 for sanity trips, 08:30 for timing")

    fun plan(a: String, b: String, dep: Int): List<Journey> = planner.plan(
        LatLon(PLACES.getValue(a).lat, PLACES.getValue(a).lon), LatLon(PLACES.getValue(b).lat, PLACES.getValue(b).lon), day, dep,
    )

    println("== sanity trips")
    for ((a, b) in listOf("Sol" to "Atocha", "Chamartin" to "NuevosMin", "Atocha" to "T4", "Sol" to "PrincipePio", "Alcala" to "Chamartin")) {
        val tq = System.nanoTime()
        val js = plan(a, b, 9 * 3600)
        println("--- ${PLACES.getValue(a).name} -> ${PLACES.getValue(b).name}: ${js.size} pareto itineraries (%.1f ms, cold JIT)".format((System.nanoTime() - tq) / 1e6))
        js.forEach { println(it.format(loaded)) }
    }

    System.getProperty("transit.debug")?.let { spec ->
        println("== debug $spec")
        // spec: lat,lon;lat,lon;HH:MM[;stopNameSubstring]
        val parts = spec.split(";")
        val (la, lo) = parts[0].split(",").map { it.toDouble() }
        val (lb, lob) = parts[1].split(",").map { it.toDouble() }
        val (hh, mm) = parts[2].split(":").map { it.toInt() }
        planner.plan(LatLon(la, lo), LatLon(lb, lob), day, hh * 3600 + mm * 60).forEach { println(it.format(loaded)) }
        parts.getOrNull(3)?.let { sub ->
            for (st in 0 until loaded.stopCount) if (loaded.stopName[st].contains(sub, ignoreCase = true)) {
                val lines = HashSet<String>()
                for (pt in 0 until loaded.patternCount) {
                    val off = loaded.patternStopOffset[pt]
                    if ((0 until loaded.patternStopCount(pt)).any { loaded.patternStops[off + it] == st }) {
                        lines.add(loaded.lineShortName[loaded.patternLine[pt]] + "(" + loaded.patternTrips(pt) + ")")
                    }
                }
                println("stop #$st '${loaded.stopName[st]}' ${loaded.stopLat[st] / 1e6},${loaded.stopLon[st] / 1e6} lines: $lines")
            }
        }
    }
    println("== timing (20 pairs, 08:30, warm-up pass then 5 measured passes)")
    for ((a, b) in PAIRS) plan(a, b, 8 * 3600 + 1800) // warm-up (JIT)
    val first = ArrayList<Double>()
    val all = ArrayList<Double>()
    val rows = ArrayList<String>()
    val found = IntArray(PAIRS.size)
    val perPair = Array(PAIRS.size) { ArrayList<Double>() }
    repeat(5) { pass ->
        for ((i, p) in PAIRS.withIndex()) {
            val s = System.nanoTime()
            val js = plan(p.first, p.second, 8 * 3600 + 1800)
            val ms = (System.nanoTime() - s) / 1e6
            all.add(ms)
            perPair[i].add(ms)
            found[i] = js.size
            if (pass == 0) {
                val best = js.minWithOrNull(compareBy({ it.arriveSec }, { it.transfers }))
                rows.add(
                    "%-14s -> %-14s %s".format(
                        p.first, p.second,
                        if (best == null) "NO ROUTE" else "dep %s arr %s (%d min) rides=%d walk-only=%b".format(
                            hm(best.departSec), hm(best.arriveSec), (best.arriveSec - best.departSec) / 60, best.rideCount, best.rideCount == 0,
                        ),
                    ),
                )
            }
        }
    }
    rows.forEachIndexed { i, r -> println("%s  [%.1f ms]".format(r, perPair[i].sorted()[perPair[i].size / 2])) }
    all.sort()
    fun pct(p: Double) = all[minOf(all.size - 1, Math.ceil(p * all.size).toInt() - 1)]
    println("query time over ${all.size} samples: median %.1f ms, p95 %.1f ms, min %.1f ms, max %.1f ms".format(pct(0.5), pct(0.95), all.first(), all.last()))
    first.clear()
    println("== next departures fallback (Sol -> Atocha, 09:00, next 4)")
    planner.planNextDepartures(
        LatLon(PLACES.getValue("Sol").lat, PLACES.getValue("Sol").lon), LatLon(PLACES.getValue("Atocha").lat, PLACES.getValue("Atocha").lon), day, 9 * 3600, 4,
    ).forEach { println(it.format(loaded)) }
    val tn = System.nanoTime()
    planner.planNextDepartures(
        LatLon(PLACES.getValue("Sol").lat, PLACES.getValue("Sol").lon), LatLon(PLACES.getValue("Atocha").lat, PLACES.getValue("Atocha").lon), day, 9 * 3600, 4,
    )
    println("next-4-departures query: %.1f ms".format((System.nanoTime() - tn) / 1e6))
    println("peak RSS final (VmHWM): ${rssKb("VmHWM") / 1024} MB")
}
