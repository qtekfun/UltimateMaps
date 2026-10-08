package com.qtekfun.ultimatemaps.transit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
import com.qtekfun.ultimatemaps.core.transit.rt.RealTimeRepository
import com.qtekfun.ultimatemaps.core.transit.rt.RefreshResult
import com.qtekfun.ultimatemaps.core.transit.rt.RenfeFeeds
import com.qtekfun.ultimatemaps.core.transit.rt.RenfeRideRealTime
import com.qtekfun.ultimatemaps.core.transit.rt.RideRealTime
import com.qtekfun.ultimatemaps.transit.follow.TransitTripSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the itinerary card needs from real time. Observable: [enabled] and [version] are Compose state. */
interface TransitRealTime {
    /** The user's "Cercanías real time" switch. */
    val enabled: Boolean

    /** Changes whenever the data behind [forRide] may have changed (a refresh, or the switch going off). */
    val version: Int

    fun forRide(ride: ItineraryLeg.Ride): LegRealTime?

    /** True when [ride] could have real time at all (its trip id is known). */
    fun supports(ride: ItineraryLeg.Ride): Boolean

    /** Asks for fresh data now if the switch is on (rate limited; returns at once). */
    fun refresh()
}

/**
 * The app side of the opt-in Cercanías real time. While the switch is OFF this class does nothing: the Renfe host is not in the
 * network policy, [forRide] answers null and no request is ever made. While it is ON the host is added to the policy (listed
 * in the connection list, logged on every attempt) and data is fetched only
 *  - once when an itinerary with a Renfe train is shown ([refresh]), and
 *  - every [pollMillis] while a transit trip is being followed ([acquire] / [release]).
 * [RealTimeRepository] enforces the 30 s minimum between requests whatever happens here. The request carries nothing about the
 * user: no position, no identifier (see `HttpRealTimeFetcher`).
 */
class CercaniasRealTime(
    private val settings: TransitTripSettingsStore,
    private val addEndpoint: (AllowedEndpoint) -> Unit,
    private val removeEndpoint: (host: String) -> Unit,
    private val repository: RealTimeRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMillis: Long = 30_000L,
    clockSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : TransitRealTime, RideRealTime {
    private val inner = RenfeRideRealTime(repository, { settings.realTimeEnabled.value }, clockSec)

    override var enabled by mutableStateOf(settings.realTimeEnabled.value)
        private set
    override var version by mutableIntStateOf(0)
        private set

    private val lock = Any()
    private var users = 0
    private var poll: Job? = null

    /** Follows the switch. Makes no connection. Call once, from the application. */
    fun start() {
        apply(settings.realTimeEnabled.value)
        scope.launch { settings.realTimeEnabled.drop(1).collect { apply(it) } }
    }

    private fun apply(on: Boolean) {
        enabled = on
        if (on) {
            addEndpoint(AllowedEndpoint(RenfeFeeds.HOST, RenfeFeeds.PURPOSE, enabled = true))
        } else {
            removeEndpoint(RenfeFeeds.HOST)
            repository.clear()
            version++
        }
        synchronized(lock) { updatePolling() }
    }

    override fun forRide(ride: ItineraryLeg.Ride): LegRealTime? = inner.forRide(ride)

    override fun supports(ride: ItineraryLeg.Ride): Boolean = inner.supports(ride)

    override fun refresh() {
        if (!enabled) return
        scope.launch { refreshNow() }
    }

    /** A transit trip is being followed: keep the data fresh while the switch is on. Balanced by [release]. */
    fun acquire() = synchronized(lock) {
        users++
        updatePolling()
    }

    fun release() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        updatePolling()
    }

    private fun updatePolling() {
        val want = users > 0 && enabled
        if (want && poll?.isActive != true) {
            poll = scope.launch {
                while (isActive) {
                    refreshNow()
                    delay(pollMillis)
                }
            }
        } else if (!want) {
            poll?.cancel()
            poll = null
        }
    }

    private suspend fun refreshNow() {
        val result = withContext(io) { inner.refreshIfEnabled() }
        if (result == RefreshResult.Updated) version++
    }
}
