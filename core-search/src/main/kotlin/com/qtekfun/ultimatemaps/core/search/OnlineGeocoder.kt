package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.cos

/**
 * An OPTIONAL online fallback for address and place lookup, asked only when the offline search has no result.
 *
 * Minimal local contract: if the Photon fallback lands with its own interface, the lead should unify the two (the shape
 * here is deliberately the smallest that works: a query, an optional bias box, a limit and one callback).
 * Nothing in the default search path calls this; see docs/phase2/system-geocoder.md.
 */
interface OnlineGeocoder {
    /** Short stable name for logs and the debug bench ("system-geocoder"). */
    val name: String

    /**
     * Looks [query] up. [onDone] is called exactly once, on an unspecified thread (the platform geocoder answers on a
     * binder thread), also when the lookup is skipped or fails. Never throws for a runtime failure.
     */
    fun search(query: String, near: GeocodeBounds?, limit: Int, onDone: (OnlineGeocodeOutcome) -> Unit)
}

/** The "lower left" and "upper right" corners the Android geocoder takes as a bias box. */
data class GeocodeBounds(val lowerLeft: LatLon, val upperRight: LatLon) {
    companion object {
        /** A box of about [radiusMeters] around [center], clamped to valid coordinates. */
        fun around(center: LatLon, radiusMeters: Double): GeocodeBounds {
            require(radiusMeters > 0 && radiusMeters.isFinite()) { "radius must be positive" }
            val dLat = radiusMeters / METERS_PER_DEGREE
            val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(MIN_COS)
            val dLon = radiusMeters / (METERS_PER_DEGREE * cosLat)
            return GeocodeBounds(
                LatLon((center.lat - dLat).coerceIn(-90.0, 90.0), (center.lon - dLon).coerceIn(-180.0, 180.0)),
                LatLon((center.lat + dLat).coerceIn(-90.0, 90.0), (center.lon + dLon).coerceIn(-180.0, 180.0)),
            )
        }

        private const val METERS_PER_DEGREE = 111_320.0
        private const val MIN_COS = 0.01
    }
}

enum class SkipReason { DISABLED, NOT_ALLOWED, QUERY_TOO_SHORT }

enum class FailureReason { NO_BACKEND, BACKEND_ERROR, TIMEOUT }

sealed interface OnlineGeocodeOutcome {
    /** At least one usable result (with coordinates). Category and extras are never filled: the platform API has none. */
    data class Found(val results: List<SearchResult>, val latencyMs: Long) : OnlineGeocodeOutcome

    /** The backend answered, with nothing (or nothing with coordinates). */
    data class NoMatch(val latencyMs: Long) : OnlineGeocodeOutcome

    data class Failed(val reason: FailureReason, val message: String?, val latencyMs: Long) : OnlineGeocodeOutcome

    /** Nothing was sent anywhere. */
    data class Skipped(val reason: SkipReason) : OnlineGeocodeOutcome
}

/** One address as the Android `Address` carries it, copied into plain values so JVM tests need no framework. */
data class GeocodedAddress(
    val featureName: String? = null,
    val addressLines: List<String> = emptyList(),
    val thoroughfare: String? = null,
    val subThoroughfare: String? = null,
    val locality: String? = null,
    val adminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null,
    /** Null when the address has no latitude (`hasLatitude()` false): such results are dropped. */
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Raw access to a geocoding backend (the Android `Geocoder` in the play flavor, a fake in tests). */
interface GeocoderBackend {
    /**
     * Asks for [query]; exactly one of the two callbacks is invoked, on any thread. Throws [BackendUnavailableException]
     * when the device has no geocoder implementation at all.
     */
    fun forwardGeocode(query: String, bounds: GeocodeBounds?, maxResults: Int, callback: Callback)

    interface Callback {
        fun onResults(addresses: List<GeocodedAddress>)
        fun onError(message: String?)
    }
}

/** Thrown by a backend when the device has no geocoder implementation at all. */
class BackendUnavailableException(message: String? = null) : RuntimeException(message)

/** Time source and one-shot timer, so tests run in virtual time with no threads. */
interface GeocodeScheduler {
    fun now(): Long
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/** Address -> [SearchResult]: name, address line and coordinates; a result without usable coordinates is dropped. */
object GeocodedAddressMapper {
    fun map(a: GeocodedAddress): SearchResult? {
        val point = LatLon.ofOrNull(a.latitude ?: return null, a.longitude ?: return null) ?: return null
        val name = nameOf(a) ?: return null
        return SearchResult(name = name, point = point, address = addressOf(a, name))
    }

    /** Maps every address, drops those without coordinates and collapses repeats (same name and place to about 1 m). */
    fun mapAll(list: List<GeocodedAddress>): List<SearchResult> {
        val seen = HashSet<Triple<String, Long, Long>>()
        return list.mapNotNull(::map).filter { r ->
            seen.add(Triple(r.name.lowercase(), Math.round(r.point.lat * 1e5), Math.round(r.point.lon * 1e5)))
        }
    }

    /**
     * Platform backends often put only the house number in `featureName` for a street address, so a name that is just
     * digits (or equals the sub-thoroughfare) is not a name: the street and number are used instead.
     */
    private fun nameOf(a: GeocodedAddress): String? {
        val feature = a.featureName?.trim().orEmpty()
        val number = a.subThoroughfare?.trim().orEmpty()
        val street = a.thoroughfare?.trim().orEmpty()
        val featureIsNumber = feature.isEmpty() || feature == number || feature.all { it.isDigit() || it in "-/, " }
        return when {
            !featureIsNumber -> feature
            street.isNotEmpty() -> if (number.isNotEmpty()) "$street $number" else street
            else -> a.addressLines.map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: listOf(a.locality, a.adminArea, a.countryName).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        }
    }

    private fun addressOf(a: GeocodedAddress, name: String): String? {
        val lines = a.addressLines.map { it.trim() }.filter { it.isNotEmpty() }
        val parts = if (lines.isNotEmpty()) {
            if (lines.first().equals(name, ignoreCase = true)) lines.drop(1) else lines
        } else {
            val street = listOf(a.thoroughfare, a.subThoroughfare).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ")
            val town = listOf(a.postalCode, a.locality).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ")
            listOf(street, town, a.adminArea?.trim().orEmpty(), a.countryName?.trim().orEmpty())
                .filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
        }
        return parts.joinToString(", ").takeIf { it.isNotEmpty() }
    }
}

/**
 * [OnlineGeocoder] over a platform [GeocoderBackend]. Asks only when [enabled] (the owner's switch, default off) and
 * [allowed] (offline mode and the network policy) are both true; nothing is sent otherwise. One timeout, one delivery.
 */
class SystemGeocoderFallback(
    private val backend: GeocoderBackend,
    private val scheduler: GeocodeScheduler,
    private val enabled: () -> Boolean,
    private val allowed: () -> Boolean = { true },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val minQueryLength: Int = MIN_QUERY_LENGTH,
) : OnlineGeocoder {
    override val name: String = "system-geocoder"

    override fun search(query: String, near: GeocodeBounds?, limit: Int, onDone: (OnlineGeocodeOutcome) -> Unit) {
        val q = query.trim()
        when {
            !enabled() -> return onDone(OnlineGeocodeOutcome.Skipped(SkipReason.DISABLED))
            !allowed() -> return onDone(OnlineGeocodeOutcome.Skipped(SkipReason.NOT_ALLOWED))
            q.length < minQueryLength -> return onDone(OnlineGeocodeOutcome.Skipped(SkipReason.QUERY_TOO_SHORT))
        }
        val started = scheduler.now()
        val lock = Any()
        var done = false
        var timer: Cancellable? = null
        fun finish(build: (Long) -> OnlineGeocodeOutcome) {
            synchronized(lock) {
                if (done) return
                done = true
                timer?.cancel()
            }
            onDone(build(scheduler.now() - started))
        }
        timer = scheduler.schedule(timeoutMs) {
            finish { OnlineGeocodeOutcome.Failed(FailureReason.TIMEOUT, null, it) }
        }
        val callback = object : GeocoderBackend.Callback {
            override fun onResults(addresses: List<GeocodedAddress>) = finish { ms ->
                val mapped = GeocodedAddressMapper.mapAll(addresses).take(limit)
                if (mapped.isEmpty()) OnlineGeocodeOutcome.NoMatch(ms) else OnlineGeocodeOutcome.Found(mapped, ms)
            }

            override fun onError(message: String?) = finish { OnlineGeocodeOutcome.Failed(FailureReason.BACKEND_ERROR, message, it) }
        }
        try {
            backend.forwardGeocode(q, near, limit.coerceAtLeast(1), callback)
        } catch (e: BackendUnavailableException) {
            finish { OnlineGeocodeOutcome.Failed(FailureReason.NO_BACKEND, e.message, it) }
        } catch (e: RuntimeException) {
            finish { OnlineGeocodeOutcome.Failed(FailureReason.BACKEND_ERROR, e.toString(), it) }
        }
    }

    companion object {
        /** The platform's own synchronous wrapper waits 15 s; a search box cannot. */
        const val DEFAULT_TIMEOUT_MS = 4_000L
        const val MIN_QUERY_LENGTH = 3
    }
}

/**
 * The reliable "does a working backend exist" probe: `Geocoder.isPresent()` only says a provider service resolves. This
 * asks for a fixed, non-personal query ([QUERY]) and calls the backend working only if it answers with a usable result.
 * Run it once (after the owner turned the switch on), cache the answer for a while and re-probe after a failure.
 */
class GeocoderProbe(private val geocoder: OnlineGeocoder) {
    fun run(onDone: (Boolean) -> Unit) {
        geocoder.search(QUERY, null, 1) { onDone(it is OnlineGeocodeOutcome.Found) }
    }

    companion object {
        const val QUERY = "Madrid, Spain"
    }
}
