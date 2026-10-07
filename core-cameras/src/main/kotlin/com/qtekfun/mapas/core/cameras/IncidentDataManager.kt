package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.NetworkPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI

/** The purpose under which the incident feed host is listed in [NetworkPolicy]. */
val INCIDENT_PURPOSE = ConnectionPurpose.TRAFFIC_INCIDENTS

/**
 * The DGT National Access Point feed of active incidents (DATEX II v3.7, one national file, CC BY, refreshed every
 * minute; verified 2026-10-07). It is a single URL with no parameters: the whole country is downloaded and filtered
 * on the device, so the server can never learn the user's position or route.
 */
const val DGT_INCIDENTS_URL = "https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v37.xml"

/** Why a refresh was asked for. Only [ENABLED], [USER] and [FOREGROUND] ever touch the network. */
enum class IncidentTrigger { ENABLED, USER, FOREGROUND }

sealed interface IncidentUpdateState {
    data object Idle : IncidentUpdateState
    data object Running : IncidentUpdateState
    data class Finished(val atMillis: Long, val failure: DownloadFailure?, val incidents: Int) : IncidentUpdateState
}

/**
 * Brings the incident pieces together: settings, [NetworkPolicy], download, parse, cache with a TTL and the in-memory
 * [repository].
 *
 * Network rules (explicit opt-in, offline mode, no position):
 *  - Nothing is downloaded when the manager is created or started; [start] only reads the local cache and, when an
 *    incident switch is on, registers the host in the policy (and removes it when the switches go off).
 *  - A download happens only on [IncidentTrigger.ENABLED], [IncidentTrigger.USER] or [IncidentTrigger.FOREGROUND] with
 *    data older than the TTL (the user's refresh interval, never below [MIN_INCIDENT_REFRESH_MINUTES]).
 *  - Offline mode and a denied policy stop it before any connection ([DownloadFailure.OFFLINE_MODE] / [DownloadFailure.NOT_ALLOWED]);
 *    the previous data is kept on every failure.
 */
class IncidentDataManager(
    private val settingsStore: CameraSettingsStore,
    private val policy: NetworkPolicy,
    private val addEndpoint: (AllowedEndpoint) -> Unit,
    private val removeEndpoint: (host: String) -> Unit,
    private val cache: IncidentCache,
    private val http: BoundedHttp = BoundedHttp(policy, INCIDENT_PURPOSE, maxBytes = 40L shl 20),
    private val url: String = DGT_INCIDENTS_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val foregroundRetryMillis: Long = 5 * 60_000L,
    private val hostOf: (String) -> String? = { runCatching { URI(it).host?.lowercase() }.getOrNull() },
) {
    val repository = IncidentDataRepository()

    private val state = MutableStateFlow<IncidentUpdateState>(IncidentUpdateState.Idle)
    val updateState: StateFlow<IncidentUpdateState> get() = state

    private val lock = Mutex()
    private var whitelistedHost: String? = null
    private var lastAttempt = Long.MIN_VALUE / 2

    fun start() {
        scope.launch {
            apply(settingsStore.settings.value)
            settingsStore.settings.drop(1).collect { apply(it) }
        }
    }

    private suspend fun apply(s: CameraSettings) = lock.withLock {
        syncPolicy(s)
        withContext(io) {
            if (!s.anyIncident) repository.install(null)
            else if (repository.data == null) repository.install(cache.read())
        }
    }

    private fun syncPolicy(s: CameraSettings) {
        val want = if (s.anyIncident) hostOf(url) else null
        if (want == whitelistedHost) return
        whitelistedHost?.let(removeEndpoint)
        if (want != null) addEndpoint(AllowedEndpoint(want, INCIDENT_PURPOSE, enabled = true))
        whitelistedHost = want
    }

    fun refreshAsync(trigger: IncidentTrigger) {
        scope.launch { refresh(trigger) }
    }

    /** The app came to the foreground: refresh only when a switch is on, online, and the data is older than the TTL. */
    fun onForeground() {
        val s = settingsStore.settings.value
        if (!s.anyIncident || policy.offlineMode) return
        refreshAsync(IncidentTrigger.FOREGROUND)
    }

    /** Returns the failure (null on success or when nothing needed doing). */
    suspend fun refresh(trigger: IncidentTrigger): DownloadFailure? = lock.withLock {
        val s = settingsStore.settings.value
        if (!s.anyIncident) return null
        syncPolicy(s)
        val now = clock()
        val have = repository.data
        val fresh = have != null && now - have.fetchedAtMillis < s.incidentRefreshMinutes * 60_000L
        val wanted = when (trigger) {
            IncidentTrigger.USER -> true
            IncidentTrigger.ENABLED -> !fresh
            IncidentTrigger.FOREGROUND -> !fresh && now - lastAttempt >= foregroundRetryMillis
        }
        if (!wanted) return null
        state.value = IncidentUpdateState.Running
        lastAttempt = now
        val result = withContext(io) { fetch(now) }
        state.value = IncidentUpdateState.Finished(clock(), result.first, result.second)
        result.first
    }

    private fun fetch(now: Long): Pair<DownloadFailure?, Int> = try {
        val feed = http.get(url, "application/xml") { DatexIncidentParser.parse(it, now) }
        val data = IncidentData(now, feed.publishedMillis, feed.incidents)
        cache.write(data)
        repository.install(data)
        null to feed.incidents.size
    } catch (e: DownloadException) {
        e.failure to 0
    } catch (e: IOException) {
        DownloadFailure.NETWORK to 0
    } catch (e: RuntimeException) {
        DownloadFailure.INVALID_DATA to 0
    }
}
