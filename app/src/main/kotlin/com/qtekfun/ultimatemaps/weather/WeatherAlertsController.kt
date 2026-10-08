package com.qtekfun.ultimatemaps.weather

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.weather.AemetEndpoints
import com.qtekfun.ultimatemaps.core.weather.AlertLevel
import com.qtekfun.ultimatemaps.core.weather.ApiKeyStore
import com.qtekfun.ultimatemaps.core.weather.ApiKeys
import com.qtekfun.ultimatemaps.core.weather.WEATHER_ALERTS_PURPOSE
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertRepository
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettingsStore
import com.qtekfun.ultimatemaps.core.weather.WeatherRefresh
import com.qtekfun.ultimatemaps.core.weather.WeatherStatus
import com.qtekfun.ultimatemaps.core.weather.WeatherWarning
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app side of the opt-in AEMET weather warnings. While the switch is OFF, or no key is stored, this class does nothing:
 * the AEMET host is not in the network policy, every answer is empty and no request is ever made. While both are in place the
 * host is added to the policy (listed to the user, logged on every attempt) and the repository is asked
 *  - when the app comes to the foreground or a route is shown ([ensureFresh]: only if the 30 min cache is older), and
 *  - when the user presses "Check now" ([checkNow]).
 * There is no polling. [WeatherAlertRepository] enforces the 15 min floor between requests whatever happens here. The request
 * is the same for everybody and carries nothing but the key (in a header).
 */
class WeatherAlertsController(
    private val settings: WeatherAlertSettingsStore,
    private val keys: ApiKeyStore,
    private val repository: WeatherAlertRepository,
    private val addEndpoint: (AllowedEndpoint) -> Unit,
    private val removeEndpoint: (host: String) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hasKeyFlow = MutableStateFlow(false)

    /** True when a key is stored. */
    val hasKey: StateFlow<Boolean> = hasKeyFlow

    private val checkingFlow = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = checkingFlow

    val status: StateFlow<WeatherStatus> get() = repository.status

    private val checkResultFlow = MutableStateFlow<WeatherRefresh?>(null)

    /** The outcome of the last "Check now" (including "too soon", which makes no request), or null before the first. */
    val checkResult: StateFlow<WeatherRefresh?> = checkResultFlow

    /** Changes whenever the warnings may have changed (observe it from Compose to redraw). */
    val version: StateFlow<Int> get() = repository.version

    val settingsStore: WeatherAlertSettingsStore get() = settings

    /** True when warnings are on and a key is present. */
    val active: Boolean get() = settings.settings.value.enabled && hasKeyFlow.value

    /** Follows the switch and the key. Makes no connection by itself except the first [ensureFresh] when both are in place. */
    fun start() {
        scope.launch {
            hasKeyFlow.value = withContext(io) { keys.read() != null }
            combine(settings.settings, hasKeyFlow) { s, k -> s.enabled && k }.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun apply(on: Boolean) {
        if (on) {
            addEndpoint(AllowedEndpoint(AemetEndpoints.HOST, WEATHER_ALERTS_PURPOSE, enabled = true))
            ensureFresh()
        } else {
            removeEndpoint(AemetEndpoints.HOST)
            repository.clear()
        }
    }

    /** Validates and stores a pasted key; false when it cannot be an AEMET key. */
    fun saveKey(input: String): Boolean {
        val key = ApiKeys.clean(input) ?: return false
        keys.write(key)
        repository.keyChanged()
        checkResultFlow.value = null
        hasKeyFlow.value = true
        return true
    }

    fun removeKey() {
        keys.clear()
        hasKeyFlow.value = false
        repository.keyChanged()
        checkResultFlow.value = null
    }

    /** Refreshes in the background if the cache is older than 30 minutes. A no-op while off. */
    fun ensureFresh() {
        if (!active) return
        scope.launch { withContext(io) { repository.refresh(force = false) } }
    }

    /** "Check now": skips the 30 min cache, never the 15 min floor. */
    fun checkNow() {
        if (!active || checkingFlow.value) return
        checkingFlow.value = true
        scope.launch {
            try { checkResultFlow.value = withContext(io) { repository.refresh(force = true) } } finally { checkingFlow.value = false }
        }
    }

    /** Orange and red warnings in effect, or starting within [LOOKAHEAD_MILLIS], along [geometry]. Never touches the network. */
    fun forRoute(geometry: List<LatLon>): List<WeatherWarning> {
        if (!active) return emptyList()
        ensureFresh() // a route is being shown: renew the cache in the background if it is older than 30 minutes
        return repository.index().along(geometry, clock(), AlertLevel.ORANGE, ROUTE_LOOKAHEAD_MILLIS)
    }

    /** The warnings behind the map chip at [point]: orange and red, in effect or starting soon. */
    fun chipAt(point: LatLon): List<WeatherWarning> =
        if (!active) emptyList() else repository.index().at(point, clock(), AlertLevel.ORANGE, CHIP_LOOKAHEAD_MILLIS)

    /** What the detail card lists at [point]: the chip's warnings plus the yellow ones when the user asked for them. */
    fun detailsAt(point: LatLon): List<WeatherWarning> {
        if (!active) return emptyList()
        val min = if (settings.settings.value.showYellow) AlertLevel.YELLOW else AlertLevel.ORANGE
        return repository.index().at(point, clock(), min, CHIP_LOOKAHEAD_MILLIS)
    }

    companion object {
        const val CHIP_LOOKAHEAD_MILLIS = 3 * 60 * 60_000L
        const val ROUTE_LOOKAHEAD_MILLIS = 12 * 60 * 60_000L
    }
}
