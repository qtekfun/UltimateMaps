package com.qtekfun.mapas.cameras

import com.qtekfun.mapas.core.cameras.AlertBannerTracker
import com.qtekfun.mapas.core.cameras.AlertEvent
import com.qtekfun.mapas.core.cameras.AlertWarner
import com.qtekfun.mapas.core.cameras.CameraDataRepository
import com.qtekfun.mapas.core.cameras.CameraSettingsStore
import com.qtekfun.mapas.core.cameras.FreeDrivingFeed
import com.qtekfun.mapas.core.cameras.IncidentBannerFeed
import com.qtekfun.mapas.core.cameras.IncidentBannerMachine
import com.qtekfun.mapas.core.cameras.IncidentDataRepository
import com.qtekfun.mapas.core.cameras.NavAlertFeed
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.core.nav.NavigationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Wires the alert warner to the app: route-based alerts while a navigation runs ([NavAlertFeed], fed by the navigation
 * state) and free-driving alerts otherwise ([FreeDrivingFeed], on a location source of its own). The two never run at
 * once. Free driving runs only while the app is on screen, a category switch is on, no navigation is active and the
 * location permission is granted; with everything off no location listener exists at all.
 *
 * Alerts reach the driver through [banner] (the visual alert, always) and [onAlert] (the navigation voice, which may be
 * muted). Whether a navigation is running is read from the navigation controller itself, not only from the screen's
 * start/stop events, so a trip resumed by the foreground service after the system killed the process, or a switch turned
 * on in the middle of a trip, also get route-based alerts. Nothing here stores or logs a position.
 */
class CameraAlerts(
    private val scope: CoroutineScope,
    private val settings: CameraSettingsStore,
    cameras: CameraDataRepository,
    incidents: IncidentDataRepository,
    navigation: NavigationController,
    location: () -> LocationSource,
    private val hasLocationPermission: () -> Boolean,
    onAlert: (AlertEvent) -> Unit,
    /** The visual alert; one is shared with the screens. */
    val banner: AlertBannerTracker = AlertBannerTracker(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The temporary banner for incidents on the route ahead (navigation only); one is shared with the screens. */
    val incidentBanner: IncidentBannerMachine = IncidentBannerMachine({ incidents }, { settings.settings.value }, clock),
    /** Ticks of the banner's countdown, collected only while it shows; tests pass a hand-driven flow. */
    ticker: Flow<Unit> = flow { while (true) { delay(INCIDENT_TICK_MILLIS); emit(Unit) } },
) {
    private val navigationState = navigation.state
    private val warner = AlertWarner(
        listOf(cameras.alertSource { settings.settings.value }, incidents.alertSource { settings.settings.value.incidentKinds() }),
        { settings.settings.value },
    ) { e ->
        // On a trip the incidents have their own banner (route-based, five seconds); the chip stays for cameras.
        if (e.target.category.isCamera || !navigating) banner.onAlert(e)
        onAlert(e)
    }
    private val navFeed = NavAlertFeed(scope, navigation.state, navigation.route, warner, banner, clock)
    private val newLocation = location
    private val incidentFeed = IncidentBannerFeed(scope, navigation.state, navigation.route, incidentBanner, ticker)
    private var freeFeed: FreeDrivingFeed? = null

    private var foreground = false
    @Volatile private var navigating = false

    /** Follows the switches: turning a category on or off starts or stops the free-driving feed. Call once. */
    fun start() {
        scope.launch { settings.settings.collect { refreshFree() } }
        scope.launch {
            navigationState.map { it != null }.distinctUntilChanged().collect { active ->
                if (active) onNavigationStarted() else onNavigationEnded()
            }
        }
    }

    /** The first activity started or the last one stopped. */
    @Synchronized
    fun onForeground(value: Boolean) {
        foreground = value
        refreshFree()
    }

    /** A navigation (real or simulated) started: route-based alerts take over. */
    @Synchronized
    fun onNavigationStarted() {
        navigating = true
        refreshFree()
        navFeed.start()
        incidentFeed.start()
    }

    @Synchronized
    fun onNavigationEnded() {
        navigating = false
        navFeed.close()
        incidentFeed.close()
        refreshFree()
    }

    /** Call when the switches or the permission may have changed (settings closed, permission answered). */
    @Synchronized
    fun refreshFree() {
        val want = foreground && !navigating && settings.settings.value.anything && hasLocationPermission()
        if (want) {
            (freeFeed ?: FreeDrivingFeed(newLocation(), warner, banner, clock).also { freeFeed = it }).start()
        } else {
            freeFeed?.stop()
        }
    }

    private companion object {
        /** Redraw step of the banner's clock: four sweeps a second is smooth enough for a five-second ring. */
        const val INCIDENT_TICK_MILLIS = 250L
    }
}
