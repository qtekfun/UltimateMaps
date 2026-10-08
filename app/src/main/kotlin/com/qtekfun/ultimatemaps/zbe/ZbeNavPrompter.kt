package com.qtekfun.ultimatemaps.zbe

import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.zbe.ZbeAhead
import com.qtekfun.ultimatemaps.core.zbe.ZbeAheadMachine
import com.qtekfun.ultimatemaps.core.zbe.ZbeAheadSpeaker
import com.qtekfun.ultimatemaps.core.zbe.ZbeRepository
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the navigation screen shows for the "Low-emission zone ahead" prompt. The distance counts down as the driver approaches. */
data class ZbeBannerState(val zoneLabel: String, val distanceMeters: Int)

/**
 * The one-time "Low-emission zone ahead" prompt of a car navigation. Follows the navigation's route and progress: when a
 * route is set (and the zone switch is on and the trip is a car trip) it finds the zones the route enters, and as the vehicle
 * approaches the first of them it shows the [banner] and gives the sound or voice per the user's choice ([ZbeAheadSpeaker],
 * which uses the same delivery rules as the camera alerts). Each zone is announced once per trip. The banner goes away when
 * the zone is reached, after [BANNER_MILLIS], or when the navigation ends. Nothing is stored or logged.
 */
class ZbePrompter(
    private val scope: CoroutineScope,
    private val settings: StateFlow<ZbeSettings>,
    private val repository: ZbeRepository,
    private val state: Flow<NavState?>,
    private val route: Flow<RoutePlan?>,
    private val profile: () -> RoutingProfile,
    private val speaker: ZbeAheadSpeaker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** The banner's state; the application owns it so the screens can observe it before the prompter exists. */
    private val bannerState: MutableStateFlow<ZbeBannerState?> = MutableStateFlow(null),
) {
    private val machine = ZbeAheadMachine()
    val banner: StateFlow<ZbeBannerState?> = bannerState.asStateFlow()

    private var jobs: List<Job> = emptyList()
    private var hadRoute = false
    @Volatile private var showing: ZbeAhead? = null
    @Volatile private var shownAt = 0L

    /** Starts following the navigation. Call once. */
    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch {
                route.collect { plan ->
                    if (plan == null) {
                        hadRoute = false
                        machine.reset()
                        clearBanner()
                    } else {
                        val newTrip = !hadRoute
                        hadRoute = true
                        clearBanner()
                        val crossings = if (settings.value.enabled && profile() == RoutingProfile.CAR) {
                            withContext(compute) { repository.index.crossings(plan.geometry) }
                        } else {
                            emptyList()
                        }
                        machine.onRoute(crossings, newTrip)
                    }
                }
            },
            scope.launch {
                state.collect { s ->
                    if (s == null) {
                        clearBanner()
                    } else if (s.status == NavStatus.ON_ROUTE && !s.estimated && settings.value.enabled) {
                        onProgress(s.traveledMeters, s.speedMps)
                    }
                }
            },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        machine.reset()
        hadRoute = false
        clearBanner()
    }

    private fun onProgress(traveled: Double, speedMps: Double) {
        showing?.let { a ->
            val left = a.crossing.entryMeters - traveled
            if (left <= 0 || clock() - shownAt > BANNER_MILLIS) {
                clearBanner()
            } else {
                bannerState.value = ZbeBannerState(a.crossing.zone.label, (Math.round(left / 10.0) * 10).toInt().coerceAtLeast(10))
            }
        }
        val ahead = machine.onProgress(traveled, speedMps) ?: return
        showing = ahead
        shownAt = clock()
        bannerState.value = ZbeBannerState(ahead.crossing.zone.label, ahead.distanceMeters)
        speaker.onAhead(ahead)
    }

    private fun clearBanner() {
        showing = null
        bannerState.value = null
    }

    companion object {
        /** The banner stays at most this long if the zone is not reached (a stop on the way). */
        const val BANNER_MILLIS = 20_000L
    }
}
