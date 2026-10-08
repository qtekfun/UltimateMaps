package com.qtekfun.ultimatemaps.bench

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import com.qtekfun.ultimatemaps.core.nav.RouteTunnelSpanSource
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.nativecomaps.CoMapsCore
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import java.io.File
import kotlin.concurrent.thread

/**
 * CoMaps core test bench (debug only). Launched with
 * `am start -n com.qtekfun.ultimatemaps/.bench.CoreBenchActivity` (with `--ez guidance true` it only dumps the guidance) and writes to logcat under the UMBENCH tag.
 * The maps go in `filesDir/maps-core/<version>/` with the .mwm files (including World.mwm). It does not store user locations.
 */
class CoreBenchActivity : Activity() {
    private val tag = "UMBENCH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Core test bench: see logcat (UMBENCH)" })
        // `--ez guidance true`: instead of the full bench, dumps the guidance of test routes (maneuvers, lanes, limits).
        val guidanceOnly = intent?.getBooleanExtra("guidance", false) == true
        // `--ez matrix true`: long routes between cities to find where routing fails between regions (also takes
        // `--ei runs <n>`, see dumpMatrix).
        val matrix = intent?.getBooleanExtra("matrix", false) == true
        // `--ez reroute true`: simulates a long drive Madrid to Barcelona with a recalculation every 10% of the route
        // (the position nudged about 300 m off the line, as when the driver leaves it).
        val reroute = intent?.getBooleanExtra("reroute", false) == true
        // `--ez tunnels true`: routes by car through the Madrid tunnels (Calle 30 / M-30, Paseo del Prado) and logs the
        // tunnel ranges the native core reports (CoMaps patch 0004), as point indices and metres along the route.
        val tunnels = intent?.getBooleanExtra("tunnels", false) == true
        thread(name = "umbench") {
            runCatching { if (tunnels) dumpTunnels() else if (reroute) dumpReroute() else if (matrix) dumpMatrix() else if (guidanceOnly) dumpGuidance() else run() }.onFailure { Log.e(tag, "FAILED: $it", it) }
        }
    }

    /** Native heap and total PSS in MB. */
    private fun memory(): String {
        val info = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
        return "native_mb=${android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} pss_mb=${info.totalPss / 1024}"
    }

    /** A long drive with recalculations: the route times after each recalculation. */
    private fun dumpReroute() {
        val core = CoMapsCore()
        core.init(applicationInfo.sourceDir, File(filesDir, "maps-core").absolutePath, cacheDir.absolutePath, "es")
        Log.i(tag, "reroute init maps=${core.refreshMaps()}")
        Log.i(tag, "reroute ${memory()}")
        // The navigation uses the engine with guidance, so do the same here.
        val router = core.routingEngine(withGuidance = true)
        val madrid = LatLon(40.4168, -3.7038)
        val bcn = LatLon(41.3874, 2.1686)
        fun log(step: String, out: RouteOutcome, ms: Long) = Log.i(
            tag,
            "reroute step=$step code=${out.code} ms=$ms m=${out.plan?.distanceMeters} s=${out.plan?.durationSeconds} " +
                "pts=${out.plan?.geometry?.size} maneuvers=${out.plan?.guidance?.maneuvers?.size} ${memory()}",
        )
        var t0 = SystemClock.elapsedRealtime()
        val out = router.routeDetailed(RouteRequest(madrid, bcn, profile = RoutingProfile.CAR))
        log("initial", out, SystemClock.elapsedRealtime() - t0)
        val line = out.plan?.geometry.orEmpty()
        if (line.size > 20) {
            for (tenth in 1..9) {
                val p = line[line.size * tenth / 10]
                val from = LatLon(p.lat + 0.003, p.lon + 0.002)
                t0 = SystemClock.elapsedRealtime()
                val r = router.routeDetailed(RouteRequest(from, bcn, profile = RoutingProfile.CAR))
                log("reroute$tenth", r, SystemClock.elapsedRealtime() - t0)
            }
        }
        // The way back, then a short route in one region.
        t0 = SystemClock.elapsedRealtime()
        log("back", router.routeDetailed(RouteRequest(bcn, madrid, profile = RoutingProfile.CAR)), SystemClock.elapsedRealtime() - t0)
        t0 = SystemClock.elapsedRealtime()
        log("urban", router.routeDetailed(RouteRequest(LatLon(40.4170, -3.7036), LatLon(40.4065, -3.6890), profile = RoutingProfile.CAR)), SystemClock.elapsedRealtime() - t0)
        Log.i(tag, "FIN reroute")
    }

    /** City pairs by car: core code, time and length (no coordinates in the log, only city names). */
    private fun dumpMatrix() {
        val core = CoMapsCore()
        core.init(applicationInfo.sourceDir, File(filesDir, "maps-core").absolutePath, cacheDir.absolutePath, "es")
        Log.i(tag, "matrix init maps=${core.refreshMaps()}")
        val c = mapOf(
            "Madrid" to LatLon(40.4168, -3.7038), "Guadalajara" to LatLon(40.6333, -3.1667),
            "Medinaceli" to LatLon(41.1667, -2.4333), "Zaragoza" to LatLon(41.6488, -0.8891),
            "Lleida" to LatLon(41.6176, 0.6200), "Tarragona" to LatLon(41.1189, 1.2445), "Barcelona" to LatLon(41.3874, 2.1686),
        )
        val pairs = listOf(
            "Madrid" to "Guadalajara", "Madrid" to "Medinaceli", "Madrid" to "Zaragoza", "Madrid" to "Lleida",
            "Madrid" to "Tarragona", "Madrid" to "Barcelona", "Zaragoza" to "Lleida", "Zaragoza" to "Barcelona",
            "Lleida" to "Barcelona", "Lleida" to "Tarragona", "Tarragona" to "Barcelona", "Barcelona" to "Madrid",
        )
        // `--ei runs <n>`: how many times each pair is routed in a row.
        val runs = (intent?.getIntExtra("runs", 1) ?: 1).coerceIn(1, 10)
        Log.i(tag, "matrix runs=$runs")
        val router = core.routingEngine()
        for ((a, b) in pairs) {
            repeat(runs) { run ->
                val t0 = SystemClock.elapsedRealtime()
                val out = router.routeDetailed(RouteRequest(c.getValue(a), c.getValue(b), profile = RoutingProfile.CAR))
                val ms = SystemClock.elapsedRealtime() - t0
                // m= and s= are exact, so two runs can be compared for identical routes.
                Log.i(
                    tag,
                    "matrix run=$run $a->$b code=${out.code} ms=$ms km=${out.plan?.distanceMeters?.div(1000)?.toInt()} " +
                        "m=${out.plan?.distanceMeters} s=${out.plan?.durationSeconds} pts=${out.plan?.geometry?.size} " +
                        "absent=${out.absentCountries}",
                )
            }
        }
        Log.i(tag, "FIN matrix")
    }

    /** Dumps to logcat the guidance of an urban route in Madrid by car, bike and on foot. Not run yet: see docs/phase2/maneuvers.md. */
    private fun dumpGuidance() {
        val mapsDir = File(filesDir, "maps-core")
        val core = CoMapsCore()
        core.init(applicationInfo.sourceDir, mapsDir.absolutePath, cacheDir.absolutePath, "es")
        Log.i(tag, "guidance init maps=${core.refreshMaps()}")
        val plain = core.routingEngine()
        val guided = core.routingEngine(withGuidance = true)
        val sol = LatLon(40.4170, -3.7036)
        val atocha = LatLon(40.4065, -3.6890)
        for ((name, p) in listOf("car" to RoutingProfile.CAR, "bike" to RoutingProfile.BIKE, "foot" to RoutingProfile.FOOT)) {
            val request = RouteRequest(sol, atocha, profile = p)
            val tPlain = ms { plain.routeDetailed(request) }
            var out: RouteOutcome? = null
            val tGuided = ms { out = guided.routeDetailed(request) }
            val plan = out?.plan
            Log.i(
                tag,
                "guidance profile=$name code=${out?.code} plain_ms=$tPlain guided_ms=$tGuided points=${plan?.geometry?.size} " +
                    "maneuvers=${plan?.guidance?.maneuvers?.size} limits=${plan?.guidance?.speedLimits?.size} err=${out?.guidanceError}",
            )
            plan?.guidance?.maneuvers?.forEach { m ->
                val lanes = m.lanes.joinToString("|") { l -> (if (l.recommended) "*" else "") + l.directions.joinToString("+") }
                Log.i(tag, "  maneuver idx=${m.geometryIndex} type=${m.type} street='${m.streetName}' exit=${m.roundaboutExit} lanes=[$lanes]")
            }
            plan?.guidance?.speedLimits?.forEach { s -> Log.i(tag, "  limit ${s.startIndex}..${s.endIndex} kmh=${s.kmh}") }
        }
        Log.i(tag, "FIN guidance")
    }

    /**
     * Logs the tunnel ranges of car routes that should cross tunnels (Madrid Calle 30 south and east stretches, the
     * Paseo del Prado / Atocha underpasses). Not run yet: the expectation is at least one range per route and exits that
     * are close to the real portals; compare with the map. Only counts, indices and metres are logged, no coordinates.
     */
    private fun dumpTunnels() {
        val core = CoMapsCore()
        core.init(applicationInfo.sourceDir, File(filesDir, "maps-core").absolutePath, cacheDir.absolutePath, "es")
        Log.i(tag, "tunnels init maps=${core.refreshMaps()}")
        val guided = core.routingEngine(withGuidance = true)
        val routes = listOf(
            "sol-atocha" to (LatLon(40.4170, -3.7036) to LatLon(40.4065, -3.6890)),
            "nuevos-ministerios-atocha" to (LatLon(40.4460, -3.6920) to LatLon(40.4065, -3.6890)),
            "puente-toledo-avenida-america" to (LatLon(40.4010, -3.7220) to LatLon(40.4400, -3.6760)),
        )
        for ((name, ends) in routes) {
            val out = guided.routeDetailed(RouteRequest(ends.first, ends.second, profile = RoutingProfile.CAR))
            val plan = out.plan
            if (plan == null) {
                Log.i(tag, "tunnels route=$name code=${out.code} (no plan)")
                continue
            }
            val ranges = plan.guidance.tunnels
            Log.i(tag, "tunnels route=$name code=${out.code} points=${plan.geometry.size} km=${plan.distanceMeters / 1000} ranges=${ranges.size} err=${out.guidanceError}")
            val spans = RouteTunnelSpanSource(ranges).spansFor(RouteGeometry(plan.geometry))
            ranges.zip(spans).forEach { (r, sp) ->
                Log.i(tag, "  tunnel idx=${r.startIndex}..${r.endIndex} along=${sp.startMeters.toInt()}..${sp.endMeters.toInt()} length_m=${sp.lengthMeters.toInt()}")
            }
        }
        Log.i(tag, "FIN tunnels")
    }

    private fun ms(block: () -> Unit): Long {
        val t = SystemClock.elapsedRealtime(); block(); return SystemClock.elapsedRealtime() - t
    }

    private fun run() {
        val mapsDir = File(filesDir, "maps-core")
        Log.i(tag, "maps: ${mapsDir.walkTopDown().filter { it.extension == "mwm" }.map { it.name }.toList()}")
        val core = CoMapsCore()
        val initMs = ms { core.init(applicationInfo.sourceDir, mapsDir.absolutePath, cacheDir.absolutePath, "es") }
        Log.i(tag, "init_ms=$initMs maps=${core.refreshMaps()}")

        val search = core.searchEngine("es")
        val madrid = LatLon(40.4168, -3.7038)
        for (q in listOf("c", "ca", "cal", "call", "calle", "calle m", "calle ma", "calle may", "calle mayor")) {
            var n = 0
            val t = ms { n = search.search(q, madrid, 10).size }
            Log.i(tag, "search q='$q' results=$n ms=$t")
        }
        for (q in listOf("Puerta del Sol", "Sagrada Familia", "farmacia")) {
            var n = 0
            val t = ms { n = search.search(q, madrid, 10).size }
            Log.i(tag, "search_warm q='$q' results=$n ms=$t")
        }

        val router = core.routingEngine()
        val bcn = LatLon(41.3874, 2.1686)
        repeat(3) { i ->
            val t0 = SystemClock.elapsedRealtime()
            val out = router.routeDetailed(RouteRequest(madrid, bcn, profile = RoutingProfile.CAR))
            val t = SystemClock.elapsedRealtime() - t0
            Log.i(tag, "route_mad_bcn run=$i code=${out.code} ms=$t km=${out.plan?.distanceMeters?.div(1000)} s=${out.plan?.durationSeconds}")
        }
        val sol = LatLon(40.4170, -3.7036)
        val atocha = LatLon(40.4065, -3.6890)
        for ((name, p) in listOf("car" to RoutingProfile.CAR, "foot" to RoutingProfile.FOOT, "bike" to RoutingProfile.BIKE)) {
            val t0 = SystemClock.elapsedRealtime()
            val out = router.routeDetailed(RouteRequest(sol, atocha, profile = p))
            Log.i(tag, "route_urban profile=$name code=${out.code} ms=${SystemClock.elapsedRealtime() - t0} km=${out.plan?.distanceMeters?.div(1000)}")
        }
        Log.i(tag, "FIN")
    }
}
