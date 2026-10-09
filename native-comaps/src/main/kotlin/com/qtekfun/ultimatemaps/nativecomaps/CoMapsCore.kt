package com.qtekfun.ultimatemaps.nativecomaps

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingEngine
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.search.PlaceExtras
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.core.search.Wheelchair

/** Codes of CoMaps' `routing::RouterResultCode` that the UI needs to tell apart. */
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

    /**
     * Own code (not from CoMaps): bike routing with [com.qtekfun.ultimatemaps.core.routing.BikeCycleways.ONLY] found no route
     * (start or end not near cycle infrastructure, or a gap in the network). The UI suggests "Prefer". Same value as
     * `um::kRouteNoCycleRoute` in C++.
     */
    const val NO_CYCLE_ROUTE = 1004

    /**
     * Own codes (not coming from CoMaps) of the isolated core: the `:core` process died and so did the retry,
     * it could not be started/connected, or the call failed for another reason. A timeout is returned as
     * [CANCELLED] (the UI already shows it as "took too long").
     */
    const val CORE_CRASHED = 1001
    const val CORE_UNAVAILABLE = 1002
    const val CORE_INTERNAL = 1003
}

/**
 * What the rest of the app uses from the core, be it the one in this process ([CoMapsCore]) or the one isolated in `:core`
 * ([com.qtekfun.ultimatemaps.nativecomaps.isolation.IsolatedCore]). The default values live here.
 */
interface CoreHandle {
    /** @throws IllegalStateException if the core does not start. In the isolated one the real startup is lazy. */
    fun init(apkPath: String, mapsDir: String, tmpDir: String, locale: String = "en")

    /** Rescans the maps directory. Returns how many maps are registered. */
    fun refreshMaps(): Int

    fun searchEngine(locale: String = "en", timeoutMs: Int = 8000): SearchEngine

    fun routingEngine(timeoutSec: Int = 120, withGuidance: Boolean = false): DetailedRoutingEngine
}

/**
 * Detailed result of a route: [plan] is null if there is no route and [code] says why.
 * [absentCountries] are the CoMaps country ids (`Spain_Catalonia_Barcelona`) that the router found missing with
 * [RouteCode.NEED_MORE_MAPS]. Today it arrives empty: `Route::GetAbsentCountries()` exists in the core but is not yet
 * exposed through JNI (pending, see `docs/phase2/robustness.md`); the rest of the app already uses it if it comes.
 */
data class RouteOutcome(
    val code: Int,
    val plan: RoutePlan?,
    val guidanceError: String? = null,
    val absentCountries: List<String> = emptyList(),
)

/** A [RoutingEngine] that also explains why there is no route. */
interface DetailedRoutingEngine : RoutingEngine {
    fun routeDetailed(request: RouteRequest): RouteOutcome
}

/**
 * CoMaps core (search + routing) as a Kotlin facade. It knows nothing about Android, location or the network:
 * it is handed file paths. Only one [CoMapsCore] per process.
 *
 * `init(apkPath, mapsDir, ...)`: `apkPath` is the APK with the core's `assets/` (see `:native-comaps`);
 * `mapsDir` contains `<version>/<Region>.mwm`, including `World.mwm`.
 */
class CoMapsCore internal constructor(private val bridge: NativeBridge) : CoreHandle, AutoCloseable {
    constructor() : this(NativeCore())

    private var initialized = false

    /** @throws IllegalStateException if the core does not start (missing data, etc.). */
    @Synchronized
    override fun init(apkPath: String, mapsDir: String, tmpDir: String, locale: String) {
        if (initialized) return
        val err = bridge.init(apkPath, mapsDir, tmpDir, locale)
        check(err.isEmpty()) { err }
        initialized = true
    }

    /**
     * For the isolated core process only: a native crash there (a CoMaps `CHECK`, a bad access) ends that process quietly and
     * appends a short note (signal, fault address, frames as `module+offset`; no message, no position) to [path], instead of
     * raising the system's "app keeps stopping" dialog over the user's screen. The client already restarts the core.
     */
    fun installCrashNote(path: String) = bridge.installCrashNote(path)

    /** Rescans the maps directory after a download or delete. Returns how many maps are registered. */
    @Synchronized
    override fun refreshMaps(): Int {
        check(initialized) { "CoMapsCore.init() not called" }
        return bridge.refreshMaps()
    }

    override fun searchEngine(locale: String, timeoutMs: Int): SearchEngine =
        CoMapsSearchEngine(this, locale, timeoutMs)

    /**
     * [withGuidance] = false (the default) is the usual route, at no extra cost. With `true`, `RoutePlan.guidance`
     * carries maneuvers, lanes and limits; if the guidance arrives malformed the route is still valid (empty guidance) and
     * [RouteOutcome.guidanceError] says why.
     */
    override fun routingEngine(timeoutSec: Int, withGuidance: Boolean): DetailedRoutingEngine =
        CoMapsRoutingEngine(this, timeoutSec, withGuidance)

    internal fun search(
        query: String, near: LatLon?, limit: Int, locale: String, timeoutMs: Int, categorial: Boolean = false,
    ): List<SearchResult> {
        check(initialized) { "CoMapsCore.init() not called" }
        val args = Triple(near != null, near?.lat ?: 0.0, near?.lon ?: 0.0)
        val raw = if (categorial) {
            bridge.searchCategory(query, args.first, args.second, args.third, limit, timeoutMs, locale)
        } else {
            bridge.search(query, args.first, args.second, args.third, limit, timeoutMs, locale)
        }
        return decodeSearch(raw)
    }

    internal fun route(request: RouteRequest, timeoutSec: Int, withGuidance: Boolean = false): RouteOutcome {
        check(initialized) { "CoMapsCore.init() not called" }
        val pts = (listOf(request.from) + request.via + request.to).flatMap { listOf(it.lat, it.lon) }
        if (withGuidance) {
            val g = bridge.routeGuidance(request.profile.toNative(), pts.toDoubleArray(), request.options.toFlags(), timeoutSec)
            return decodeGuidedRoute(g)
        }
        val raw = bridge.route(request.profile.toNative(), pts.toDoubleArray(), request.options.toFlags(), timeoutSec)
        return decodeRoute(raw)
    }

    override fun close() {
        // CoMaps' global state lives until the end of the process (immortal native singleton).
    }
}

internal class CoMapsSearchEngine(
    private val core: CoMapsCore,
    private val locale: String,
    private val timeoutMs: Int,
) : SearchEngine {
    override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> =
        if (query.isBlank()) emptyList() else core.search(query.trim(), near, limit, locale, timeoutMs)

    override fun searchCategory(query: String, near: LatLon?, limit: Int): List<SearchResult> =
        if (query.isBlank()) emptyList() else core.search(query.trim(), near, limit, locale, timeoutMs, categorial = true)

    override fun close() = Unit
}

internal class CoMapsRoutingEngine(
    private val core: CoMapsCore,
    private val timeoutSec: Int,
    private val withGuidance: Boolean = false,
) : DetailedRoutingEngine {
    override fun route(request: RouteRequest): RoutePlan? = routeDetailed(request).plan

    override fun routeDetailed(request: RouteRequest): RouteOutcome = core.route(request, timeoutSec, withGuidance)

    override fun close() = Unit
}

internal fun RoutingProfile.toNative(): Int = when (this) {
    RoutingProfile.CAR -> 0
    RoutingProfile.FOOT -> 1
    RoutingProfile.BIKE -> 2
}

/**
 * Layout of the flat string array that `nativeSearch` returns. The only place that knows the stride: C++
 * (`kSearchStride` in `um_jni.cpp`) must match [VERSION].
 *
 * - v1: name, address, category, lat, lon (stride 5).
 * - v2: v1 + phone, website, wheelchair, opening hours (stride 9); an absent tag is the empty string.
 */
internal object SearchWire {
    const val V1 = 1
    const val V2 = 2

    /** The layout the bundled native library produces. */
    const val VERSION = V2

    fun stride(version: Int): Int = when (version) {
        V1 -> 5
        V2 -> 9
        else -> throw IllegalArgumentException("unknown search layout: $version")
    }
}

internal fun decodeSearch(raw: Array<String>, version: Int = SearchWire.VERSION): List<SearchResult> {
    val stride = SearchWire.stride(version)
    require(raw.size % stride == 0) { "malformed search response: ${raw.size}" }
    return raw.toList().chunked(stride).mapNotNull { f ->
        val p = LatLon.ofOrNull(f[3].toDoubleOrNull() ?: return@mapNotNull null, f[4].toDoubleOrNull() ?: return@mapNotNull null)
            ?: return@mapNotNull null
        val extras = if (version >= SearchWire.V2) {
            PlaceExtras(
                phone = f[5].ifBlank { null },
                website = f[6].ifBlank { null },
                wheelchair = Wheelchair.fromOsm(f[7]),
                openingHours = f[8].ifBlank { null },
            ).takeUnless { it.isEmpty }
        } else {
            null
        }
        SearchResult(f[0], p, f[1].ifEmpty { null }, f[2].ifEmpty { null }, extras = extras)
    }
}

/** Optional altitude trailer of the route array: `..., alt0..altN-1, N, ALTITUDE_MARKER` (see `RouteFlat` in um_jni.cpp). */
internal object RouteWire {
    const val ALTITUDE_MARKER = -7_777_777.0
    const val NO_ALTITUDE = -32768.0
}

internal fun decodeRoute(raw: DoubleArray): RouteOutcome {
    require(raw.size >= 3) { "malformed route response: ${raw.size}" }
    var end = raw.size
    var altitudes: List<Double> = emptyList()
    if (end >= 5 && raw[end - 1] == RouteWire.ALTITUDE_MARKER) {
        val n = raw[end - 2]
        require(n == Math.rint(n) && n >= 0 && n <= (end - 5).toDouble()) { "malformed altitude trailer: $n" }
        val count = n.toInt()
        altitudes = (end - 2 - count until end - 2).map { raw[it].let { a -> if (a == RouteWire.NO_ALTITUDE) Double.NaN else a } }
        end -= 2 + count
    }
    require((end - 3) % 2 == 0) { "malformed route response: ${raw.size}" }
    val code = raw[0].toInt()
    if (code != RouteCode.NO_ERROR && code != RouteCode.HAS_WARNINGS) return RouteOutcome(code, null)
    val points = (end - 3) / 2
    val geometry = (3 until end step 2).mapNotNull { LatLon.ofOrNull(raw[it], raw[it + 1]) }
    // A dropped invalid point would misalign the heights with the geometry: then there is no profile rather than a wrong one.
    val aligned = if (altitudes.size == points && geometry.size == points) altitudes else emptyList()
    return RouteOutcome(code, RoutePlan(geometry, raw[1], raw[2], altitudes = aligned))
}

/** Route + guidance. The route is validated just as strictly as in [decodeRoute]; a broken guidance does not bring the route down. */
internal fun decodeGuidedRoute(g: RawGuidedRoute): RouteOutcome {
    val base = decodeRoute(g.route)
    val plan = base.plan ?: return base
    return try {
        base.copy(plan = plan.copy(guidance = GuidanceWire.decode(g.guidance, g.names, plan.geometry.size)))
    } catch (e: IllegalArgumentException) {
        base.copy(guidanceError = e.message ?: "malformed guidance")
    }
}
