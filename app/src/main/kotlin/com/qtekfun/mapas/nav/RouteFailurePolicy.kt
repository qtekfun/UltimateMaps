package com.qtekfun.mapas.nav

import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.regions.RegionCatalog
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** Why there is no route, from the user's point of view. */
enum class RouteFailureKind {
    NO_REGIONS, NEED_MORE_MAPS, START_NOT_FOUND, END_NOT_FOUND, INTERMEDIATE_NOT_FOUND, ROUTE_NOT_FOUND,
    TIMEOUT, CORE_CRASHED, CORE_UNAVAILABLE, INTERNAL,
}

/** What to do about it. */
enum class RouteAdvice {
    /** Download the missing regions ("Maps" screen). */
    DOWNLOAD_MAPS,

    /** Pick another start, stop or destination (it is not on a road in the downloaded maps). */
    CHANGE_POINT,

    /** Change the profile or the avoid options, or the points. */
    CHANGE_OPTIONS,

    /** Just try again (the core was restarted, or it was slow). */
    RETRY,
}

/** A route that could not be computed: [absentRegionIds] are the CoMaps ids of the maps the router missed, if it said. */
data class RouteFailure(
    val kind: RouteFailureKind,
    val advice: RouteAdvice,
    val absentRegionIds: List<String> = emptyList(),
)

/**
 * What the app does with a failed route, in one place: how each result code is classified, which failures are worth
 * an automatic retry (and how many times, and after how long), and which downloadable regions the failure names.
 *
 * Retrying is only for failures that can go away by themselves: the core process crashed (it is restarted) or the
 * answer was an internal error. "Not found" results are deterministic for the same maps and options, so repeating
 * them only burns the battery; a timeout would very likely time out again, so the user decides.
 */
object RouteFailurePolicy {
    fun classify(outcome: RouteOutcome): RouteFailure {
        val absent = outcome.absentCountries
        return when (outcome.code) {
            RouteCode.NEED_MORE_MAPS -> RouteFailure(RouteFailureKind.NEED_MORE_MAPS, RouteAdvice.DOWNLOAD_MAPS, absent)
            RouteCode.START_NOT_FOUND -> RouteFailure(RouteFailureKind.START_NOT_FOUND, RouteAdvice.CHANGE_POINT)
            RouteCode.END_NOT_FOUND -> RouteFailure(RouteFailureKind.END_NOT_FOUND, RouteAdvice.CHANGE_POINT)
            RouteCode.INTERMEDIATE_NOT_FOUND -> RouteFailure(RouteFailureKind.INTERMEDIATE_NOT_FOUND, RouteAdvice.CHANGE_POINT)
            RouteCode.ROUTE_NOT_FOUND, RouteCode.NO_ERROR, RouteCode.HAS_WARNINGS ->
                RouteFailure(RouteFailureKind.ROUTE_NOT_FOUND, RouteAdvice.CHANGE_OPTIONS, absent)
            RouteCode.CANCELLED -> RouteFailure(RouteFailureKind.TIMEOUT, RouteAdvice.RETRY)
            RouteCode.CORE_CRASHED -> RouteFailure(RouteFailureKind.CORE_CRASHED, RouteAdvice.RETRY)
            RouteCode.CORE_UNAVAILABLE -> RouteFailure(RouteFailureKind.CORE_UNAVAILABLE, RouteAdvice.RETRY)
            else -> RouteFailure(RouteFailureKind.INTERNAL, RouteAdvice.RETRY)
        }
    }

    /** How many extra automatic attempts a failure deserves, and the pause before each. */
    fun retries(kind: RouteFailureKind): Int = when (kind) {
        RouteFailureKind.CORE_CRASHED, RouteFailureKind.INTERNAL -> 1
        else -> 0
    }

    const val RETRY_DELAY_MILLIS = 1_500L

    /**
     * The catalog regions named by [absentCountryIds] (CoMaps ids such as `Spain_Catalonia_Barcelona`), by
     * [com.qtekfun.mapas.core.regions.Region.comapsId]. Ids the catalog does not know are skipped: the message then
     * lists only what can actually be downloaded. Empty when the router named nothing.
     */
    fun missingRegionNames(absentCountryIds: List<String>, catalog: RegionCatalog?): List<String> {
        if (catalog == null || absentCountryIds.isEmpty()) return emptyList()
        val byComaps = HashMap<String, String>()
        for (r in catalog.regions) r.comapsId?.let { byComaps[it] = r.name }
        return absentCountryIds.mapNotNull { byComaps[it] }.distinct()
    }
}

/** The result of [RouteRunner.run]: either a route or a classified failure; never an exception. */
class RouteRun(val plan: RoutePlan?, val failure: RouteFailure?, val attempts: Int, val millis: Long)

/**
 * Computes a route with a hard time limit that really ends the work, and the retry policy above. Never blocks the
 * caller's thread (suspends), never throws (except cancellation), never leaves a calculation running after it
 * gave up:
 *
 * - The blocking engine call runs in [runInterruptible] on [io]; when the limit passes or the coroutine is
 *   cancelled, the thread is interrupted. The isolated core answers an interrupt by killing the stuck `:core`
 *   process (the only way to stop a native calculation) and starting a clean one on the next call. With the
 *   in-process fallback the native call itself cannot be interrupted: only then can a calculation outlive its
 *   deadline, and the app's native mutex keeps it from overlapping the next one.
 * - [timeoutMillis] bounds the whole run including retries, so a failing core cannot keep the UI waiting.
 */
class RouteRunner(
    private val io: CoroutineDispatcher,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(request: RouteRequest, engine: () -> DetailedRoutingEngine?): RouteRun {
        val start = clock()
        var attempts = 0
        val finished = withTimeoutOrNull(timeoutMillis) {
            var result: RouteRun? = null
            while (result == null) {
                attempts++
                val e = engine()
                if (e == null) {
                    result = RouteRun(null, RouteFailure(RouteFailureKind.NO_REGIONS, RouteAdvice.DOWNLOAD_MAPS), attempts, clock() - start)
                    continue
                }
                val outcome = try {
                    runInterruptible(io) { e.routeDetailed(request) }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    RouteOutcome(RouteCode.INTERNAL_ERROR, null)
                }
                val plan = outcome.plan
                if (plan != null && plan.geometry.size >= 2) {
                    result = RouteRun(plan, null, attempts, clock() - start)
                    continue
                }
                val f = RouteFailurePolicy.classify(outcome)
                if (attempts > RouteFailurePolicy.retries(f.kind)) {
                    result = RouteRun(null, f, attempts, clock() - start)
                } else {
                    delay(RouteFailurePolicy.RETRY_DELAY_MILLIS)
                }
            }
            result
        }
        return finished ?: RouteRun(null, RouteFailure(RouteFailureKind.TIMEOUT, RouteAdvice.RETRY), attempts, clock() - start)
    }

    companion object {
        /** The spike measured about 18 s for Madrid-Barcelona with all regions; beyond this a driver would not wait. */
        const val DEFAULT_TIMEOUT_MILLIS = 40_000L
    }
}

/** The text for a [RouteFailure]; [catalog] lets it name the regions to download when the router said which. */
object RouteFailureMessages {
    fun of(context: android.content.Context, failure: RouteFailure, catalog: RegionCatalog?): String {
        val named = RouteFailurePolicy.missingRegionNames(failure.absentRegionIds, catalog)
        if (failure.kind == RouteFailureKind.NEED_MORE_MAPS && named.isNotEmpty()) {
            return context.getString(R.string.nav_route_err_need_maps_named, named.joinToString(", "))
        }
        val res = when (failure.kind) {
            RouteFailureKind.NO_REGIONS -> R.string.nav_route_err_no_regions
            RouteFailureKind.NEED_MORE_MAPS -> R.string.nav_route_err_need_maps
            RouteFailureKind.START_NOT_FOUND -> R.string.nav_route_err_start
            RouteFailureKind.END_NOT_FOUND -> R.string.nav_route_err_end
            RouteFailureKind.INTERMEDIATE_NOT_FOUND -> R.string.nav_route_err_stop
            RouteFailureKind.ROUTE_NOT_FOUND -> R.string.nav_route_err_not_found
            RouteFailureKind.TIMEOUT -> R.string.nav_route_err_timeout
            RouteFailureKind.CORE_CRASHED -> R.string.nav_route_err_core
            RouteFailureKind.CORE_UNAVAILABLE -> R.string.nav_route_err_core_unavailable
            RouteFailureKind.INTERNAL -> R.string.nav_route_err_internal
        }
        return context.getString(res)
    }
}
