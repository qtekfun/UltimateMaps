package com.qtekfun.ultimatemaps.core.transit.rt

import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose

/** Where Renfe publishes its Cercanias GTFS-RT feeds (verified 2026-10-08 on data.renfe.com: JSON and protobuf, CC BY 4.0, refreshed about every 20 s). */
object RenfeFeeds {
    const val HOST = "gtfsrt.renfe.com"
    const val TRIP_UPDATES = "https://$HOST/trip_updates.json"
    const val VEHICLES = "https://$HOST/vehicle_positions.json"
    const val ALERTS = "https://$HOST/alerts.json"

    /** The purpose under which [HOST] is listed in the network policy and its local log. */
    val PURPOSE = ConnectionPurpose.TRANSIT_REALTIME

    /** Shown wherever real-time data appears (Renfe asks for no specific text; this names the source). */
    const val ATTRIBUTION = "Renfe"
}

enum class RealTimeFailure { OFFLINE, NOT_ALLOWED, NETWORK, TIMEOUT, SERVER, TOO_LARGE, INVALID }

class RealTimeException(val failure: RealTimeFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** One plain GET of [url]. Implementations send nothing about the user. Blocking. */
fun interface RealTimeFetcher {
    @Throws(RealTimeException::class)
    fun get(url: String): ByteArray
}

sealed interface RefreshResult {
    /** New data was fetched and is now [RealTimeRepository.current]. */
    data object Updated : RefreshResult

    /** Nothing was asked of the server: the data is still fresh, or the minimum interval since the last attempt has not passed. */
    data object Skipped : RefreshResult

    /** The attempt failed; the previous data (if any) is kept until it goes stale. */
    data class Failed(val failure: RealTimeFailure) : RefreshResult
}

/**
 * The last answer of Renfe's real-time feeds, with the rules that keep the server and the user safe:
 *  - [refresh] never asks more often than [minIntervalMillis] (failures count, and back off: the wait doubles up to
 *    [maxBackoffMillis]), whoever calls it and however often;
 *  - the answer is kept in memory only and [current] hides it once it is older than [staleAfterMillis] (a delay from ten
 *    minutes ago is worse than none);
 *  - a failure is never an error for the caller: offline, a server error or a malformed body just leave [current] as it was;
 *  - the trip updates are required, alerts and vehicle positions are best effort; they are fetched only after the trip
 *    updates succeeded, so a failing server is asked once per attempt.
 * Nothing is sent but a plain GET of a fixed URL: no position, no identifier. Thread-safe; [refresh] blocks.
 */
class RealTimeRepository(
    private val fetcher: RealTimeFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = 30_000L,
    private val staleAfterMillis: Long = 3 * 60_000L,
    private val maxBackoffMillis: Long = 5 * 60_000L,
    private val withVehicles: Boolean = false,
    private val tripUpdatesUrl: String = RenfeFeeds.TRIP_UPDATES,
    private val alertsUrl: String = RenfeFeeds.ALERTS,
    private val vehiclesUrl: String = RenfeFeeds.VEHICLES,
) {
    private val lock = Any()
    private var snapshot: RtSnapshot? = null
    private var lastAttemptAt = Long.MIN_VALUE / 2
    private var failures = 0

    /** The last good answer, or null when there is none or it is stale. Never touches the network. */
    fun current(): RtSnapshot? = synchronized(lock) {
        snapshot?.takeIf { clock() - it.fetchedAtMillis <= staleAfterMillis }
    }

    /** Forgets everything (the switch went off). */
    fun clear() = synchronized(lock) {
        snapshot = null
        failures = 0
        lastAttemptAt = Long.MIN_VALUE / 2
    }

    /** Asks the server if the minimum interval allows it. Blocking; call off the main thread. */
    fun refresh(): RefreshResult {
        val now: Long
        synchronized(lock) {
            now = clock()
            val wait = minOf(minIntervalMillis shl minOf(failures, 10), maxOf(maxBackoffMillis, minIntervalMillis))
            if (now - lastAttemptAt < wait) return RefreshResult.Skipped
            lastAttemptAt = now
        }
        return try {
            val trips = GtfsRtJson.parseTripUpdates(text(tripUpdatesUrl))
            val alerts = bestEffort { GtfsRtJson.parseAlerts(text(alertsUrl)).entities }
            val vehicles = if (withVehicles) bestEffort { GtfsRtJson.parseVehicles(text(vehiclesUrl)).entities } else null
            synchronized(lock) {
                val old = snapshot
                snapshot = RtSnapshot(
                    tripUpdates = trips.entities.associateBy { it.tripId },
                    alerts = alerts ?: old?.alerts.orEmpty(),
                    vehicles = vehicles?.mapNotNull { v -> v.tripId?.let { it to v } }?.toMap() ?: old?.vehicles.orEmpty(),
                    feedTimestampSec = trips.timestampSec,
                    fetchedAtMillis = clock(),
                )
                failures = 0
            }
            RefreshResult.Updated
        } catch (e: RealTimeException) {
            fail(e.failure)
        } catch (_: RtParseException) {
            fail(RealTimeFailure.INVALID)
        } catch (_: RuntimeException) {
            fail(RealTimeFailure.INVALID)
        }
    }

    private fun fail(f: RealTimeFailure): RefreshResult {
        synchronized(lock) { failures++ }
        return RefreshResult.Failed(f)
    }

    private fun text(url: String): String = fetcher.get(url).toString(Charsets.UTF_8)

    private inline fun <T> bestEffort(block: () -> T): T? = try {
        block()
    } catch (_: RealTimeException) {
        null
    } catch (_: RtParseException) {
        null
    }
}
