package com.qtekfun.ultimatemaps.core.weather

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface WeatherRefresh {
    /** A new national bundle was fetched. */
    data object Updated : WeatherRefresh

    /** Nothing was asked: the cache is younger than the TTL (automatic refresh only). */
    data object Fresh : WeatherRefresh

    /** Nothing was asked: the minimum interval since the last attempt (or a server-imposed wait) has not passed. */
    data object TooSoon : WeatherRefresh

    /** The switch is off. */
    data object Disabled : WeatherRefresh

    /** No usable key is stored. */
    data object NoKey : WeatherRefresh

    /** AEMET refused this key before; nothing is asked again until the key changes. */
    data object KeyRejected : WeatherRefresh

    data class Failed(val failure: WeatherFailure) : WeatherRefresh
}

/** What the settings screen shows about the last check. */
data class WeatherStatus(val lastCheckMillis: Long? = null, val lastResult: WeatherRefresh? = null)

/**
 * The last national bundle of AEMET warnings and the rules that keep AEMET and the user safe:
 *  - NOTHING happens (no policy call, no request) while the switch is off or the key is empty;
 *  - warnings are issued about every 30 minutes, so the cache lives [ttlMillis] (30 min) and the automatic refresh
 *    ([refresh] with `force = false`) does nothing before that;
 *  - "Check now" (`force = true`) skips the TTL but never the [minIntervalMillis] (15 min) between two requests, whoever asks
 *    and whatever the outcome (failures count), so AEMET's 40 requests per minute limit is nowhere near;
 *  - a 429 doubles the wait to [rateLimitedWaitMillis]; a 401/403 stops all requests until the key is changed;
 *  - data older than [staleAfterMillis] is not shown (a warning from yesterday is worse than none); every failure is silent
 *    and keeps the previous data until then.
 * The same single request serves everybody: no position, no area, nothing but the key (in a header) is sent. Thread-safe;
 * [refresh] blocks.
 */
class WeatherAlertRepository(
    private val source: AemetSource,
    private val enabled: () -> Boolean,
    private val apiKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val language: () -> String = { "es" },
    private val ttlMillis: Long = TTL_MILLIS,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
    private val rateLimitedWaitMillis: Long = RATE_LIMITED_WAIT_MILLIS,
    private val staleAfterMillis: Long = STALE_AFTER_MILLIS,
) {
    private val lock = Any()
    private var bundle: List<CapAlert> = emptyList()
    private var fetchedAt: Long? = null
    private var lastAttemptAt: Long? = null
    private var blockedUntil = Long.MIN_VALUE
    private var rejectedKey: String? = null
    private var cachedIndex: WeatherAlertIndex? = null
    private var cachedIndexKey: Pair<Long?, String>? = null

    private val statusFlow = MutableStateFlow(WeatherStatus())
    val status: StateFlow<WeatherStatus> = statusFlow

    private val versionFlow = MutableStateFlow(0)

    /** Changes whenever the data behind [index] may have changed (a refresh or a [clear]); for UI refresh. */
    val version: StateFlow<Int> = versionFlow

    /** The warnings to match against, or [WeatherAlertIndex.EMPTY]. Never touches the network. */
    fun index(): WeatherAlertIndex = synchronized(lock) {
        if (!enabled()) return WeatherAlertIndex.EMPTY
        val at = fetchedAt ?: return WeatherAlertIndex.EMPTY
        val now = clock()
        if (now - at > staleAfterMillis || now < at) return WeatherAlertIndex.EMPTY
        val lang = language()
        val cacheKey = at to lang
        cachedIndex?.takeIf { cachedIndexKey == cacheKey }
            ?: WeatherAlertIndex(CapParser.toWarnings(bundle, lang, now)).also { cachedIndex = it; cachedIndexKey = cacheKey }
    }

    /** Forgets the warnings (the switch went off, or the key was removed), but not the wait before the next request. */
    fun clear() {
        synchronized(lock) {
            // The wait since the last request is kept on purpose: switching off and on again must not skip the floor.
            bundle = emptyList(); fetchedAt = null
            cachedIndex = null; cachedIndexKey = null
        }
        statusFlow.value = WeatherStatus()
        versionFlow.value++
    }

    /** Asks AEMET if the rules above allow it. Call off the main thread. */
    fun refresh(force: Boolean): WeatherRefresh {
        if (!enabled()) return WeatherRefresh.Disabled
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return WeatherRefresh.NoKey
        val now: Long
        synchronized(lock) {
            now = clock()
            if (rejectedKey == key) return WeatherRefresh.KeyRejected
            if (!force) {
                val at = fetchedAt
                if (at != null && now >= at && now - at < ttlMillis) return WeatherRefresh.Fresh
            }
            val last = lastAttemptAt
            if ((last != null && now >= last && now - last < minIntervalMillis) || now < blockedUntil) return WeatherRefresh.TooSoon
            lastAttemptAt = now
        }
        val result = try {
            val docs = source.fetchNational(key)
            val parsed = docs.mapNotNull { runCatching { CapParser.parse(it) }.getOrNull() }
            if (docs.isNotEmpty() && parsed.isEmpty()) throw AemetException(WeatherFailure.INVALID, "no readable CAP document")
            synchronized(lock) {
                bundle = parsed; fetchedAt = now; cachedIndex = null; cachedIndexKey = null
            }
            WeatherRefresh.Updated
        } catch (e: AemetException) {
            synchronized(lock) {
                if (e.failure == WeatherFailure.UNAUTHORIZED) rejectedKey = key
                if (e.failure == WeatherFailure.RATE_LIMITED) blockedUntil = now + rateLimitedWaitMillis
            }
            if (e.failure == WeatherFailure.UNAUTHORIZED) WeatherRefresh.KeyRejected else WeatherRefresh.Failed(e.failure)
        } catch (_: RuntimeException) {
            WeatherRefresh.Failed(WeatherFailure.INVALID)
        }
        statusFlow.value = WeatherStatus(now, result)
        if (result == WeatherRefresh.Updated) versionFlow.value++
        return result
    }

    /** The user typed a new key: whatever AEMET said about the old one no longer applies. */
    fun keyChanged() {
        synchronized(lock) {
            rejectedKey = null; lastAttemptAt = null; blockedUntil = Long.MIN_VALUE
        }
        statusFlow.value = WeatherStatus()
    }

    companion object {
        const val TTL_MILLIS = 30 * 60_000L
        const val MIN_INTERVAL_MILLIS = 15 * 60_000L
        const val RATE_LIMITED_WAIT_MILLIS = 60 * 60_000L
        const val STALE_AFTER_MILLIS = 3 * 60 * 60_000L
    }
}
