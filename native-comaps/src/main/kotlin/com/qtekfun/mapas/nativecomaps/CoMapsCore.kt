package com.qtekfun.mapas.nativecomaps

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteOptions
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingEngine
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.core.search.SearchEngine
import com.qtekfun.mapas.core.search.SearchResult

/** Codigos de `routing::RouterResultCode` de CoMaps que la UI necesita distinguir. */
object RouteCode {
    const val NO_ERROR = 0
    const val CANCELLED = 1
    const val START_NOT_FOUND = 5
    const val END_NOT_FOUND = 6
    const val ROUTE_NOT_FOUND = 8
    const val NEED_MORE_MAPS = 9
    const val INTERNAL_ERROR = 10
    const val INTERMEDIATE_NOT_FOUND = 12
    const val HAS_WARNINGS = 16
}

/** Resultado detallado de una ruta: [plan] es null si no hay ruta y [code] dice por que. */
data class RouteOutcome(val code: Int, val plan: RoutePlan?)

/** Un [RoutingEngine] que ademas explica por que no hay ruta. */
interface DetailedRoutingEngine : RoutingEngine {
    fun routeDetailed(request: RouteRequest): RouteOutcome
}

/**
 * Nucleo de CoMaps (busqueda + routing) como fachada Kotlin. No conoce Android ni la ubicacion ni la red:
 * se le pasan rutas de ficheros. Un solo [CoMapsCore] por proceso.
 *
 * `init(apkPath, mapsDir, ...)`: `apkPath` es el APK con `assets/` del nucleo (ver `:native-comaps`);
 * `mapsDir` contiene `<version>/<Region>.mwm`, incluido `World.mwm`.
 */
class CoMapsCore internal constructor(private val bridge: NativeBridge) : AutoCloseable {
    constructor() : this(NativeCore())

    private var initialized = false

    /** @throws IllegalStateException si el nucleo no arranca (faltan datos, etc.). */
    @Synchronized
    fun init(apkPath: String, mapsDir: String, tmpDir: String, locale: String = "en") {
        if (initialized) return
        val err = bridge.init(apkPath, mapsDir, tmpDir, locale)
        check(err.isEmpty()) { err }
        initialized = true
    }

    /** Re-escanea el directorio de mapas tras descargar o borrar. Devuelve cuantos mapas hay registrados. */
    @Synchronized
    fun refreshMaps(): Int {
        check(initialized) { "CoMapsCore.init() no llamado" }
        return bridge.refreshMaps()
    }

    fun searchEngine(locale: String = "en", timeoutMs: Int = 8000): SearchEngine =
        CoMapsSearchEngine(this, locale, timeoutMs)

    fun routingEngine(timeoutSec: Int = 120): DetailedRoutingEngine = CoMapsRoutingEngine(this, timeoutSec)

    internal fun search(query: String, near: LatLon?, limit: Int, locale: String, timeoutMs: Int): List<SearchResult> {
        check(initialized) { "CoMapsCore.init() no llamado" }
        val raw = bridge.search(query, near != null, near?.lat ?: 0.0, near?.lon ?: 0.0, limit, timeoutMs, locale)
        return decodeSearch(raw)
    }

    internal fun route(request: RouteRequest, timeoutSec: Int): RouteOutcome {
        check(initialized) { "CoMapsCore.init() no llamado" }
        val pts = (listOf(request.from) + request.via + request.to).flatMap { listOf(it.lat, it.lon) }
        val raw = bridge.route(request.profile.toNative(), pts.toDoubleArray(), request.options.toFlags(), timeoutSec)
        return decodeRoute(raw)
    }

    override fun close() {
        // El estado global de CoMaps vive hasta el fin del proceso (singleton nativo inmortal).
    }
}

internal class CoMapsSearchEngine(
    private val core: CoMapsCore,
    private val locale: String,
    private val timeoutMs: Int,
) : SearchEngine {
    override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> =
        if (query.isBlank()) emptyList() else core.search(query.trim(), near, limit, locale, timeoutMs)

    override fun close() = Unit
}

internal class CoMapsRoutingEngine(private val core: CoMapsCore, private val timeoutSec: Int) :
    DetailedRoutingEngine {
    override fun route(request: RouteRequest): RoutePlan? = routeDetailed(request).plan

    override fun routeDetailed(request: RouteRequest): RouteOutcome = core.route(request, timeoutSec)

    override fun close() = Unit
}

internal fun RoutingProfile.toNative(): Int = when (this) {
    RoutingProfile.CAR -> 0
    RoutingProfile.FOOT -> 1
    RoutingProfile.BIKE -> 2
}

/** Mismos bits que `um::AvoidFlags` en C++. */
internal fun RouteOptions.toFlags(): Int =
    (if (avoidMotorways) 1 else 0) or (if (avoidTolls) 2 else 0) or
        (if (avoidFerries) 4 else 0) or (if (avoidUnpaved) 8 else 0)

internal fun decodeSearch(raw: Array<String>): List<SearchResult> {
    require(raw.size % 5 == 0) { "respuesta de busqueda mal formada: ${raw.size}" }
    return raw.toList().chunked(5).mapNotNull { (name, address, category, lat, lon) ->
        val p = LatLon.ofOrNull(lat.toDoubleOrNull() ?: return@mapNotNull null, lon.toDoubleOrNull() ?: return@mapNotNull null)
            ?: return@mapNotNull null
        SearchResult(name, p, address.ifEmpty { null }, category.ifEmpty { null })
    }
}

internal fun decodeRoute(raw: DoubleArray): RouteOutcome {
    require(raw.size >= 3 && (raw.size - 3) % 2 == 0) { "respuesta de ruta mal formada: ${raw.size}" }
    val code = raw[0].toInt()
    if (code != RouteCode.NO_ERROR && code != RouteCode.HAS_WARNINGS) return RouteOutcome(code, null)
    val geometry = (3 until raw.size step 2).mapNotNull { LatLon.ofOrNull(raw[it], raw[it + 1]) }
    return RouteOutcome(code, RoutePlan(geometry, raw[1], raw[2]))
}
