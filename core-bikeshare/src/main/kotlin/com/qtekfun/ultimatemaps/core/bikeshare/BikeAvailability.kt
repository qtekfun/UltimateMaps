package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

/** The purpose under which the live-availability hosts are listed in [NetworkPolicy]. */
val BIKE_AVAILABILITY_PURPOSE = ConnectionPurpose.BIKE_AVAILABILITY

/** How many bikes and free docks a station has, and when the feed says it was reported. */
data class BikeAvailability(val bikes: Int, val freeDocks: Int, val updatedAtMillis: Long)

/**
 * The public GBFS `station_status` feed of each system that has one the app may read, keyed by the system id of the data
 * file. A system without an entry never gets a live request. These are fixed URLs compiled into the app: the data file
 * (which could be replaced on the data server) can never make the app contact a host chosen by it.
 */
object BikeShareLiveFeeds {
    private val feeds = mapOf(
        "bicing" to "https://barcelona.publicbikesystem.net/customer/gbfs/v3.0/station_status",
        "bicimad" to "https://madrid.publicbikesystem.net/customer/gbfs/v3.0/station_status",
    )

    fun statusUrl(systemId: String): String? = feeds[systemId]

    /** Every host that may ever be contacted, for the list of possible connections. */
    fun hosts(): List<String> = feeds.values.mapNotNull { hostOf(it) }.distinct()

    internal fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}

/**
 * Optional live bike and dock counts, fetched ONLY while a station card is open and ONLY when both bike-share switches are
 * on. Rules, all enforced here and tested:
 *  - Nothing at all happens (no policy call, no request) while the switches are off.
 *  - Every request goes through [NetworkPolicy] under [BIKE_AVAILABILITY_PURPOSE]; a host is registered in the policy only
 *    while the live switch is on (so it is listed to the user only then) and removed when it goes off.
 *  - A plain GET of the system's whole `station_status` file: no query, no station id, no position, no cookie, a generic
 *    `User-Agent`. The station is looked up in the downloaded file on the device.
 *  - At most one request per system every [ttlMillis] (30 s), failures included; inside that window the last answer is
 *    returned without a request. Data older than [STALE_AFTER_MILLIS] is not shown.
 *  - Every failure (offline mode, policy denial, network, server, bad data) is silent: the result is simply null.
 */
class BikeAvailabilityRepository(
    private val settings: kotlinx.coroutines.flow.StateFlow<BikeShareSettings>,
    private val policy: NetworkPolicy,
    private val addEndpoint: (AllowedEndpoint) -> Unit,
    private val removeEndpoint: (host: String) -> Unit,
    private val http: BoundedHttp = BoundedHttp(
        policy, BIKE_AVAILABILITY_PURPOSE, maxBytes = MAX_BYTES, userAgent = GENERIC_USER_AGENT,
    ),
    private val feedUrl: (systemId: String) -> String? = BikeShareLiveFeeds::statusUrl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = TTL_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class SystemState(var attemptAt: Long = Long.MIN_VALUE / 2, var fetchedAt: Long = 0, var stations: Map<String, BikeAvailability> = emptyMap())

    private val lock = Mutex()
    private val systems = HashMap<String, SystemState>()
    private val registered = HashSet<String>()

    /** Keeps the policy's host list in step with the live switch (removes the hosts when it goes off). Call once. */
    fun start() {
        scope.launch {
            settings.map { it.liveActive }.distinctUntilChanged().collect { active ->
                lock.withLock { if (!active) unregisterAll() }
            }
        }
    }

    /**
     * Availability of [station], or null when live data is off, unavailable or failed. Suspends while a request runs; safe
     * to call again and again (the 30 s window answers from memory).
     */
    suspend fun availability(station: BikeStation): BikeAvailability? {
        if (!settings.value.liveActive) return null
        val url = feedUrl(station.system.id) ?: return null
        val host = BikeShareLiveFeeds.hostOf(url) ?: return null
        return lock.withLock {
            if (!settings.value.liveActive) {
                unregisterAll()
                return@withLock null
            }
            val st = systems.getOrPut(station.system.id) { SystemState() }
            val now = clock()
            if (now - st.attemptAt >= ttlMillis || now < st.attemptAt) {
                register(host)
                st.attemptAt = now
                val parsed = withContext(io) { fetch(url, now) }
                if (parsed != null) {
                    st.stations = parsed
                    st.fetchedAt = now
                }
            }
            if (st.fetchedAt == 0L || now - st.fetchedAt > STALE_AFTER_MILLIS) null else st.stations[station.stationId]
        }
    }

    private fun fetch(url: String, now: Long): Map<String, BikeAvailability>? = try {
        val text = http.get(url, "application/json") { String(it.readBytes(), Charsets.UTF_8) }
        parseStatus(text, now)
    } catch (e: IOException) {
        null // includes DownloadException: offline mode, not allowed, network, server, too large
    } catch (e: RuntimeException) {
        null // a document that is not what GBFS says
    }

    private fun register(host: String) {
        if (registered.add(host)) addEndpoint(AllowedEndpoint(host, BIKE_AVAILABILITY_PURPOSE, enabled = true))
    }

    private fun unregisterAll() {
        registered.forEach(removeEndpoint)
        registered.clear()
        systems.clear()
    }

    companion object {
        const val TTL_MILLIS = 30_000L
        const val STALE_AFTER_MILLIS = 10 * 60_000L
        const val MAX_BYTES = 3L shl 20

        /** Looks like an ordinary browser request: it says nothing about the app or its version. */
        const val GENERIC_USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile"

        private val json = Json { isLenient = false }

        /**
         * Reads a GBFS 2.x / 3.x `station_status` document into station id -> availability. Counts come from
         * `num_vehicles_available` (3.x) or `num_bikes_available` (2.x) and `num_docks_available`; a station that is not
         * installed is skipped. The time is the station's `last_reported`, else the feed's `last_updated` (ISO 8601 in 3.x,
         * epoch seconds in 2.x, never later than [now]). Throws on a document that is not an object with `data.stations`.
         */
        fun parseStatus(text: String, now: Long): Map<String, BikeAvailability> {
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not an object")
            val stations = ((root["data"] as? JsonObject)?.get("stations") as? JsonArray) ?: throw IllegalArgumentException("no stations")
            val feedTime = timeOf(root["last_updated"])
            val out = HashMap<String, BikeAvailability>(stations.size * 2)
            for (e in stations) {
                val s = e as? JsonObject ?: continue
                val id = (s["station_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                if ((s["is_installed"] as? JsonPrimitive)?.contentOrNull == "false") continue
                val bikes = intOf(s["num_vehicles_available"]) ?: intOf(s["num_bikes_available"]) ?: continue
                val docks = intOf(s["num_docks_available"]) ?: continue
                val at = (timeOf(s["last_reported"]) ?: feedTime ?: now).coerceAtMost(now)
                out[id] = BikeAvailability(bikes.coerceAtLeast(0), docks.coerceAtLeast(0), at)
            }
            return out
        }

        private fun intOf(e: JsonElement?): Int? = (e as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

        private fun timeOf(e: JsonElement?): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) {
                return try { Instant.parse(p.content).toEpochMilli() } catch (_: DateTimeParseException) { null }
            }
            val seconds = p.longOrNull ?: return null
            return if (seconds > 0) seconds * 1000 else null
        }

        /** The generic user agent must not identify the app. */
        internal fun userAgentIsGeneric(ua: String): Boolean = !ua.contains("ultimate", ignoreCase = true) && !ua.contains("qtekfun", ignoreCase = true)
    }
}
