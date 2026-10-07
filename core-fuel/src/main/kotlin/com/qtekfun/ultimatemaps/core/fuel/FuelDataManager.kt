package com.qtekfun.ultimatemaps.core.fuel

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
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

/** Why a refresh was asked for. Only [ENABLED], [USER] and [FOREGROUND] ever touch the network. */
enum class FuelTrigger { ENABLED, USER, FOREGROUND, FUELS_CHANGED }

/** Result for one fuel in one refresh: [failure] is null on success; the previous data is kept on failure. */
data class FuelOutcome(val fuelId: String, val failure: FuelFailure?, val stations: Int = 0)

sealed interface FuelUpdateState {
    data object Idle : FuelUpdateState
    data class Running(val done: Int, val total: Int, val currentFuelId: String?) : FuelUpdateState
    data class Finished(val atMillis: Long, val outcomes: List<FuelOutcome>) : FuelUpdateState
}

/**
 * Brings the pieces together: settings, [NetworkPolicy], downloads, cache and the in-memory index.
 *
 * Network rules (RF-15, RF-17):
 *  - Nothing is downloaded when the manager is created. [start] only reads the local cache and registers the
 *    host in the policy when the feature is enabled.
 *  - Downloads happen only through [refresh]: when the user enables the feature ([FuelTrigger.ENABLED]), presses
 *    "Update now" ([FuelTrigger.USER]), picks a fuel not yet downloaded ([FuelTrigger.FUELS_CHANGED]) or the app
 *    comes to the foreground with data older than the TTL ([FuelTrigger.FOREGROUND], see [onForeground]).
 *  - One request per configured fuel, in sequence; a failing fuel does not stop the others and keeps its last good data.
 *  - The host is in the policy whitelist and the visible connection list only while the feature is enabled.
 */
class FuelDataManager(
    private val settingsStore: FuelSettingsStore,
    private val policy: NetworkPolicy,
    private val addEndpoint: (AllowedEndpoint) -> Unit,
    private val removeEndpoint: (host: String) -> Unit,
    private val cache: FuelCache,
    private val client: FuelClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /** Do not retry a fuel that failed less than this long ago when the trigger is the app coming to the foreground. */
    private val foregroundRetryMillis: Long = 5 * 60_000L,
    /** Host of a source URL; tests inject one that accepts http. */
    private val hostOf: (String) -> String? = ::sourceHost,
) {
    private val repo = FuelDataRepository()
    val repository: FuelRepository get() = repo

    private val state = MutableStateFlow<FuelUpdateState>(FuelUpdateState.Idle)
    val updateState: StateFlow<FuelUpdateState> get() = state

    private val lock = Mutex()
    private var loaded: Map<String, FuelData> = emptyMap() // only touched under [lock] or before start
    private val lastAttempt = HashMap<String, Long>()
    private var whitelistedHost: String? = null

    /** Applies the current settings (policy + local cache, no network) and keeps doing so when they change. */
    fun start() {
        scope.launch {
            applySettings(settingsStore.settings.value)
            settingsStore.settings.drop(1).collect { applySettings(it) }
        }
    }

    private suspend fun applySettings(s: FuelSettings) = lock.withLock {
        syncPolicy(s)
        withContext(io) { reloadFromDisk(s) }
    }

    private fun syncPolicy(s: FuelSettings) {
        val want = if (s.enabled) hostOf(s.sourceUrl) else null
        if (want == whitelistedHost) return
        whitelistedHost?.let(removeEndpoint)
        if (want != null) addEndpoint(AllowedEndpoint(want, FUEL_PURPOSE, enabled = true))
        whitelistedHost = want
    }

    /** Serves the cached data of the configured fuels (and nothing when the feature is off). Deletes cache of fuels no longer configured. */
    private fun reloadFromDisk(s: FuelSettings) {
        val fuels = if (s.enabled) s.downloadedFuels.mapNotNull { FuelTypes.byId(it) } else emptyList()
        val data = LinkedHashMap<String, FuelData>()
        for (f in fuels) {
            loaded[f.id]?.let { data[f.id] = it } ?: cache.read(f.id)?.let { data[f.id] = it }
        }
        if (s.enabled) for (f in FuelTypes.all) if (f.id !in s.downloadedFuels) cache.delete(f.id)
        loaded = data
        repo.install(FuelSnapshot.build(data.values))
    }

    /** The app came to the foreground: refresh only if the feature is on, online, and the data is older than the TTL. */
    fun onForeground() {
        val s = settingsStore.settings.value
        if (!s.enabled || policy.offlineMode) return
        scope.launch { refresh(FuelTrigger.FOREGROUND) }
    }

    /** Runs [refresh] in the manager's own scope, so leaving a screen does not abandon a download half-way. */
    fun refreshAsync(trigger: FuelTrigger) {
        scope.launch { refresh(trigger) }
    }

    /** Minutes of validity of a download: the user's choice, never below the service's own 30 minutes. */
    private fun ttlMillis(s: FuelSettings) = s.refreshMinutes.coerceAtLeast(MIN_REFRESH_MINUTES) * 60_000L

    /**
     * Downloads what is needed, one file per configured fuel. [FuelTrigger.ENABLED] and [FuelTrigger.USER] fetch every
     * configured fuel; the other triggers only those with no data or data older than the TTL. Returns the outcomes
     * (also published in [updateState]). A refresh already running makes this one wait for it.
     */
    suspend fun refresh(trigger: FuelTrigger): List<FuelOutcome> = lock.withLock {
        val s = settingsStore.settings.value
        if (!s.enabled) return emptyList()
        syncPolicy(s)
        val force = trigger == FuelTrigger.ENABLED || trigger == FuelTrigger.USER
        val now = clock()
        val ttl = ttlMillis(s)
        val todo = s.downloadedFuels.mapNotNull { FuelTypes.byId(it) }.filter { f ->
            val have = loaded[f.id]
            when {
                force -> true
                have == null || now - have.fetchedAtMillis >= ttl -> {
                    trigger != FuelTrigger.FOREGROUND || now - (lastAttempt[f.id] ?: Long.MIN_VALUE / 2) >= foregroundRetryMillis
                }
                else -> false
            }
        }
        if (todo.isEmpty()) return emptyList()
        val outcomes = ArrayList<FuelOutcome>()
        withContext(io) {
            todo.forEachIndexed { i, fuel ->
                state.value = FuelUpdateState.Running(i, todo.size, fuel.id)
                lastAttempt[fuel.id] = clock()
                outcomes += fetchOne(s, fuel)
            }
        }
        state.value = FuelUpdateState.Finished(clock(), outcomes)
        outcomes
    }

    private fun fetchOne(s: FuelSettings, fuel: FuelType): FuelOutcome = try {
        val feed = client.fetch(s.sourceUrl, fuel)
        val data = FuelData(fuel.id, clock(), feed.serviceDate, feed.stations)
        cache.write(data)
        loaded = loaded + (fuel.id to data)
        repo.install(FuelSnapshot.build(configuredData(s)))
        FuelOutcome(fuel.id, null, feed.stations.size)
    } catch (e: FuelException) {
        FuelOutcome(fuel.id, e.failure)
    } catch (e: java.io.IOException) {
        FuelOutcome(fuel.id, FuelFailure.NETWORK)
    } catch (e: RuntimeException) {
        FuelOutcome(fuel.id, FuelFailure.INVALID_DATA)
    }

    private fun configuredData(s: FuelSettings): Collection<FuelData> =
        s.downloadedFuels.mapNotNull { loaded[it] }

    /** When [fuelId] was last downloaded successfully (from memory or the cache), for the settings screen. */
    fun fetchedAt(fuelId: String): Long? = repo.fetchedAt[fuelId]
}
