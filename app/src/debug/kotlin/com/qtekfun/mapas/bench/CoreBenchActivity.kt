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
import java.io.File
import kotlin.concurrent.thread

/**
 * Banco de pruebas del núcleo de CoMaps (solo debug). Se lanza con
 * `am start -n com.qtekfun.mapas/.bench.CoreBenchActivity` y escribe en logcat con la etiqueta UMBENCH.
 * Los mapas van en `filesDir/maps-core/<versión>/` con los ficheros .mwm (incluido World.mwm). No guarda ubicaciones del usuario.
 */
class CoreBenchActivity : Activity() {
    private val tag = "UMBENCH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Banco de pruebas del núcleo: mira logcat (UMBENCH)" })
        thread(name = "umbench") { runCatching { run() }.onFailure { Log.e(tag, "FALLO: $it", it) } }
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
