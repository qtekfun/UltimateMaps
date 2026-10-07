package com.qtekfun.ultimatemaps.nav

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.nav.NavTrip
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.route.RouteBackend
import com.qtekfun.ultimatemaps.search.CoMapsSearchBackend
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LaunchStatus { IDLE, COMPUTING, FAILED }

/** Observable state of "Start" / "Simulate": written from the main thread only. */
class NavLaunchState {
    var status by mutableStateOf(LaunchStatus.IDLE)
    var failure by mutableStateOf<RouteFailure?>(null)
    var simulate by mutableStateOf(false)
}

/**
 * Production [RouteBackend] for navigation: the same shared core as the search and the preview, but asking for
 * GUIDANCE (maneuvers, lanes, speed limits). With the isolated core (the default) the request and the guided
 * route cross the `:core` process boundary through [com.qtekfun.ultimatemaps.nativecomaps.isolation.IsolatedCore]:
 * the plan, guidance and stops are carried by `RoutePlanCodec` and big answers are chunked under the Binder
 * limit (both covered by `IsolatedCoreTest`).
 */
class CoMapsGuidedRouteBackend(private val context: Context) : RouteBackend {
    @Synchronized
    override fun open(maps: CoreMaps, timeoutSec: Int): DetailedRoutingEngine =
        CoMapsSearchBackend.prepareCore(context, maps).routingEngine(timeoutSec, withGuidance = true)
}

/**
 * The "Start" and "Simulate" buttons of the route card: asks the engine for the same route WITH guidance (the
 * preview has none), under the retry and deadline policy of [RouteRunner], and hands the result to
 * [NavScreenController.begin] together with the intermediate stops (so they become `RoutePlan.withStops`). On
 * failure the reason stays in [state] for the card to show; the preview is left as it was.
 *
 * [mutex] is the app's one native lock, shared with search and preview.
 */
class NavLauncher(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val regions: InstalledRegions,
    private val backend: RouteBackend,
    private val runner: RouteRunner,
    private val screen: NavScreenController,
    private val mutex: Mutex,
    private val request: () -> RouteRequest?,
    private val onStarted: () -> Unit = {},
) {
    val state = NavLaunchState()

    private var job: Job? = null

    /** Calculates the guided route and starts navigating it, for real or [simulate]d. Ignored while one is being calculated. */
    fun start(simulate: Boolean) {
        if (state.status == LaunchStatus.COMPUTING) return
        val req = request() ?: return
        state.status = LaunchStatus.COMPUTING
        state.failure = null
        state.simulate = simulate
        job = scope.launch {
            val timeoutSec = ((runner.timeoutMillis + 999) / 1000).toInt()
            val engine: DetailedRoutingEngine? = try {
                withContext(io) { regions.coreMaps()?.let { backend.open(it, timeoutSec) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val run = mutex.withLock { runner.run(req) { engine } }
            val plan = run.plan
            if (plan == null) {
                state.failure = run.failure ?: RouteFailure(RouteFailureKind.INTERNAL, RouteAdvice.RETRY)
                state.status = LaunchStatus.FAILED
                return@launch
            }
            if (screen.begin(plan, req.via, NavTrip(req.profile, req.options), simulate)) {
                state.status = LaunchStatus.IDLE
                onStarted()
            } else {
                state.failure = RouteFailure(RouteFailureKind.INTERNAL, RouteAdvice.RETRY)
                state.status = LaunchStatus.FAILED
            }
        }
    }

    /** Forgets a previous failure (the card changed) and abandons a calculation in progress. */
    fun reset() {
        job?.cancel()
        state.status = LaunchStatus.IDLE
        state.failure = null
    }
}

/** What the route card needs to show "Start" and "Simulate": the state of the launch and the two actions. */
class NavStartHost(val state: NavLaunchState, val onStart: () -> Unit, val onSimulate: () -> Unit)
