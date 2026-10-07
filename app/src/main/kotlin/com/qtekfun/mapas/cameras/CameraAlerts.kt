package com.qtekfun.mapas.cameras

import com.qtekfun.mapas.core.cameras.AlertEvent
import com.qtekfun.mapas.core.cameras.AlertWarner
import com.qtekfun.mapas.core.cameras.CameraDataRepository
import com.qtekfun.mapas.core.cameras.CameraSettingsStore
import com.qtekfun.mapas.core.cameras.FreeDrivingFeed
import com.qtekfun.mapas.core.cameras.IncidentDataRepository
import com.qtekfun.mapas.core.cameras.NavAlertFeed
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.core.nav.NavigationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wires the alert warner to the app: route-based alerts while a navigation runs ([NavAlertFeed], fed by the navigation
 * state) and free-driving alerts otherwise ([FreeDrivingFeed], on a location source of its own). The two never run at
 * once. Free driving runs only while the app is on screen, a category switch is on, no navigation is active and the
 * location permission is granted; with everything off no location listener exists at all.
 *
 * Alerts reach the driver through [onAlert] (the navigation voice). Nothing here stores or logs a position.
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
) {
    private val warner = AlertWarner(
        listOf(cameras.alertSource { settings.settings.value }, incidents.alertSource { settings.settings.value.incidentKinds() }),
        { settings.settings.value }, onAlert,
    )
    private val navFeed = NavAlertFeed(scope, navigation.state, navigation.route, warner)
    private val newLocation = location
    private var freeFeed: FreeDrivingFeed? = null

    private var foreground = false
    private var navigating = false

    /** Follows the switches: turning a category on or off starts or stops the free-driving feed. Call once. */
    fun start() {
        scope.launch { settings.settings.collect { refreshFree() } }
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
    }

    @Synchronized
    fun onNavigationEnded() {
        navigating = false
        navFeed.close()
        refreshFree()
    }

    /** Call when the switches or the permission may have changed (settings closed, permission answered). */
    @Synchronized
    fun refreshFree() {
        val want = foreground && !navigating && settings.settings.value.anything && hasLocationPermission()
        if (want) {
            (freeFeed ?: FreeDrivingFeed(newLocation(), warner).also { freeFeed = it }).start()
        } else {
            freeFeed?.stop()
        }
    }
}
