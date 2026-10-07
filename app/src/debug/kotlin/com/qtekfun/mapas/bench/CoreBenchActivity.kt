package com.qtekfun.mapas.bench

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.CoMapsCore
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import java.io.File
import kotlin.concurrent.thread

/**
 * Banco de pruebas del núcleo de CoMaps (solo debug). Se lanza con
 * `am start -n com.qtekfun.mapas/.bench.CoreBenchActivity` (con `--ez guidance true` solo vuelca el guiado) y escribe en logcat con la etiqueta UMBENCH.
 * Los mapas van en `filesDir/maps-core/<versión>/` con los ficheros .mwm (incluido World.mwm). No guarda ubicaciones del usuario.
 */
class CoreBenchActivity : Activity() {
    private val tag = "UMBENCH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Banco de pruebas del núcleo: mira logcat (UMBENCH)" })
        // `--ez guidance true`: en vez del banco completo, vuelca el guiado de rutas de prueba (maniobras, carriles, límites).
        val guidanceOnly = intent?.getBooleanExtra("guidance", false) == true
        thread(name = "umbench") {
            runCatching { if (guidanceOnly) dumpGuidance() else run() }.onFailure { Log.e(tag, "FALLO: $it", it) }
        }
    }

    /** Vuelca a logcat el guiado de una ruta urbana de Madrid en coche, bici y a pie. Sin ejecutar aún: ver docs/phase2/maneuvers.md. */
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

    private fun ms(block: () -> Unit): Long {
        val t = SystemClock.elapsedRealtime(); block(); return SystemClock.elapsedRealtime() - t
    }

    private fun run() {
        val mapsDir = File(filesDir, "maps-core")
        Log.i(tag, "mapas: ${mapsDir.walkTopDown().filter { it.extension == "mwm" }.map { it.name }.toList()}")
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
