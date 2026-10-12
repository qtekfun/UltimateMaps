package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.cameras.DownloadException
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.core.search.OnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.OnlineOutcome
import com.qtekfun.ultimatemaps.core.search.OnlineSearch
import com.qtekfun.ultimatemaps.core.search.OnlineSearchSettingsStore
import com.qtekfun.ultimatemaps.core.search.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToLong

/** One GET of a URL, blocking; throws [DownloadException] for every failure. Replaced by a fake in tests. */
fun interface PhotonFetch {
    fun get(url: String): String
}

/**
 * `play` flavor: the optional online fallback of the offline search, over a Photon server (OpenStreetMap data, ODbL).
 *
 * What it sends: `q` (the typed text), `lat` and `lon` rounded to 0.01 degree from the map centre (never the live position),
 * `lang` when the place language is one the public instance knows, and `limit`; plus a `User-Agent` with the app name and
 * version, which the public instance's fair-use policy asks for. Rules it enforces itself: the switch must be on, offline mode
 * blocks it, requests are at least [OnlineSearch.MIN_INTERVAL_MS] apart (a later one waits), https only, the answer is capped
 * and nothing is cached or stored.
 */
class PhotonGeocoder(
    private val settings: OnlineSearchSettingsStore,
    private val policy: NetworkPolicy,
    private val fetch: PhotonFetch,
    /** Photon `lang` for the owner's place-language choice (null: leave it out). */
    private val language: () -> String? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Milliseconds, monotonic; virtual in tests. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) : OnlineGeocoder {

    private val gate = Mutex()
    private var lastRequestAt: Long? = null

    override val enabled: Boolean get() = settings.enabled && !policy.offlineMode

    override suspend fun search(query: String, near: LatLon?, limit: Int): OnlineOutcome {
        if (!settings.enabled) return OnlineOutcome.Disabled
        if (policy.offlineMode) return OnlineOutcome.Offline
        val text = query.trim().take(OnlineSearch.MAX_QUERY_CHARS)
        if (text.length < OnlineSearch.MIN_QUERY_CHARS) return OnlineOutcome.Found(emptyList())
        val url = requestUrl(OnlineSearch.effectiveUrl(settings.serverUrl), text, near?.let(OnlineSearch::roundBias), language(), limit)
        val body = gate.withLock {
            // Fair use: one request a second at most, counted from the start of the previous one.
            lastRequestAt?.let { last ->
                val wait = last + OnlineSearch.MIN_INTERVAL_MS - clock()
                if (wait > 0) delay(wait)
            }
            // Offline mode or the switch may have changed while waiting.
            if (!settings.enabled) return OnlineOutcome.Disabled
            if (policy.offlineMode) return OnlineOutcome.Offline
            lastRequestAt = clock()
            try {
                withContext(io) { fetch.get(url) }
            } catch (e: DownloadException) {
                return when (e.failure) {
                    DownloadFailure.OFFLINE_MODE -> OnlineOutcome.Offline
                    DownloadFailure.NOT_ALLOWED -> OnlineOutcome.Disabled
                    else -> OnlineOutcome.Failed
                }
            } catch (e: java.io.IOException) {
                return OnlineOutcome.Failed
            }
        }
        val parsed = parse(body, limit) ?: return OnlineOutcome.Failed
        return OnlineOutcome.Found(parsed)
    }

    companion object {
        /** `http`: the real fetch, through [policy] under [ConnectionPurpose.ONLINE_SEARCH]. */
        fun fetchOver(policy: NetworkPolicy, userAgent: String, allowInsecure: Boolean = false): PhotonFetch {
            val http = BoundedHttp(
                policy, ConnectionPurpose.ONLINE_SEARCH, allowInsecure = allowInsecure, maxBytes = 512L * 1024,
                connectTimeoutMs = 8_000, readTimeoutMs = 10_000, totalTimeoutMs = 20_000, userAgent = userAgent,
            )
            return PhotonFetch { url -> http.get(url, "application/json") { it.readBytes().toString(Charsets.UTF_8) } }
        }

        /** `<root>/api/?q=...&lat=..&lon=..&lang=..&limit=..`; [root] may already end in `/api`. */
        fun requestUrl(root: String, query: String, bias: LatLon?, lang: String?, limit: Int): String {
            val base = root.trimEnd('/').removeSuffix("/api")
            return buildString {
                append(base).append("/api/?q=").append(URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
                if (bias != null) append("&lat=").append(coordinate(bias.lat)).append("&lon=").append(coordinate(bias.lon))
                if (lang != null) append("&lang=").append(lang)
                append("&limit=").append(limit.coerceIn(1, 20))
            }
        }

        private fun coordinate(v: Double): String = ((v * 100.0).roundToLong() / 100.0).toString()

        /** The user agent the public instance asks for: the application name and version, and where to find the project. */
        fun userAgent(versionName: String?): String =
            "UltimateMaps/${versionName?.takeIf { it.isNotBlank() } ?: "unknown"} (+https://github.com/qtekfun/UltimateMaps)"

        /** The results of a Photon GeoJSON answer, or null when it is not one. Features without a name or a point are skipped. */
        fun parse(body: String, limit: Int): List<SearchResult>? {
            val features: JSONArray = try {
                JSONObject(body).optJSONArray("features") ?: return null
            } catch (_: JSONException) {
                return null
            }
            val out = ArrayList<SearchResult>()
            val seen = HashSet<String>()
            for (i in 0 until features.length()) {
                val f = features.optJSONObject(i) ?: continue
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                val point = LatLon.ofOrNull(lat, lon) ?: continue
                val props = f.optJSONObject("properties") ?: continue
                val result = result(props, point) ?: continue
                // The same place twice (a node and its building): same name within a ~100 m cell.
                if (!seen.add("${result.name.lowercase()}|${(lat * 1000).toLong()}|${(lon * 1000).toLong()}")) continue
                out += result
                if (out.size >= limit) break
            }
            return out
        }

        private fun result(p: JSONObject, point: LatLon): SearchResult? {
            fun s(key: String): String? = p.optString(key, "").trim().takeIf { it.isNotEmpty() }
            val street = s("street")
            val number = s("housenumber")
            val streetLine = listOfNotNull(street, number).joinToString(" ").ifEmpty { null }
            val name = s("name") ?: streetLine ?: return null
            val locality = s("city") ?: s("town") ?: s("village") ?: s("locality") ?: s("district") ?: s("county")
            val place = listOfNotNull(s("postcode"), locality).joinToString(" ").ifEmpty { null }
            val address = listOfNotNull(streetLine?.takeIf { it != name }, place, s("state")?.takeIf { it != locality }, s("country"))
                .joinToString(", ").ifEmpty { null }
            val category = s("osm_value")?.takeIf { it != "yes" }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
            return SearchResult(name = name, point = point, address = address, category = category, online = true)
        }
    }
}
