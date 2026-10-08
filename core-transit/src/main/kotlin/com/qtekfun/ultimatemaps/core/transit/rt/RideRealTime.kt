package com.qtekfun.ultimatemaps.core.transit.rt

import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg

/** What real time knows about a ride right now. Reads memory only; never the network. */
fun interface RideRealTime {
    fun forRide(ride: ItineraryLeg.Ride): LegRealTime?

    companion object {
        /** No real time at all (the switch is off, or the app has no source). */
        val NONE = RideRealTime { null }
    }
}

/**
 * Matches a ride of an itinerary to Renfe's feed.
 *
 * **Identity.** The index of a Renfe city keeps the feed's own trip and stop ids (see `FeedOptions.keepIds`), so a ride
 * carries its GTFS `trip_id` and the GTFS-RT trip update is found by that id. This was checked on 2026-10-08 against the
 * live `trip_updates.json` and Renfe's `fomento_transit.zip`: 228 of 296 trip ids and 346 of 346 stop ids of the feed exist
 * in the static file (the rest are trips the static file does not list, outside the Madrid index anyway).
 * An index built before the ids were kept has none: the ride has no [ItineraryLeg.Ride.tripId] and nothing is matched
 * (the label then stays "scheduled times"). There is no looser fallback by line, stop and time: the feed carries no
 * coordinates or names, so without stop ids it cannot be tied to a stop at all.
 *
 * **Guards.** A trip id repeats every day, and the feed only describes today. So a ride is matched only if it is scheduled
 * within [windowBeforeSec] before to [windowAfterSec] after [nowSec], and, when the feed gives a stop update for one of
 * the ride's own stops, the schedule it implies (predicted time minus delay) must agree with the planned time within
 * [scheduleToleranceSec]; otherwise the train found is another day's instance and is ignored.
 */
object RideMatcher {
    private const val windowBeforeSec = 3 * 3600L
    private const val windowAfterSec = 4 * 3600L
    private const val scheduleToleranceSec = 600L
    private const val MAX_ALERTS = 3

    fun match(ride: ItineraryLeg.Ride, snap: RtSnapshot, nowSec: Long): LegRealTime? {
        val tripId = ride.tripId ?: return null
        if (ride.arriveAt < nowSec - windowBeforeSec || ride.departAt > nowSec + windowAfterSec) return null
        val alerts = alertsFor(ride, snap.alerts, nowSec)
        val update = snap.tripUpdates[tripId]
        if (update == null) return LegRealTime(alerts = alerts).takeIf { it.hasInfo }
        val byFeedId = ride.stops.filter { it.feedId != null }.associateBy { it.feedId!! }
        val relevant = update.stops.filter { it.stopId in byFeedId }
        // A stop update that disagrees with the plan belongs to another day's run of the same trip id.
        for (su in relevant) {
            val sched = su.scheduledAt() ?: continue
            val planned = byFeedId.getValue(su.stopId)
            val close = minOf(kotlin.math.abs(sched - planned.arriveAt), kotlin.math.abs(sched - planned.departAt))
            if (close > scheduleToleranceSec) return LegRealTime(alerts = alerts).takeIf { it.hasInfo }
        }
        if (update.cancelled) return LegRealTime(cancelled = true, alerts = alerts)
        val delay = relevant.firstNotNullOfOrNull { it.delay() } ?: update.delaySec
        val skipped = relevant.filter { it.skipped && (it.stopId == ride.boarding.feedId || it.stopId == ride.alighting.feedId) }
            .map { byFeedId.getValue(it.stopId).name }
        return LegRealTime(delaySec = delay, alerts = alerts, skippedStops = skipped).takeIf { it.hasInfo }
    }

    /**
     * Alerts active now that name the ride's trip, one of its stops, or its line. A line is named by a route id such as
     * `10T0014C4a`: the part after the area digits, `T` and the 4-digit route number (`C4a`) is the line's short name.
     */
    internal fun alertsFor(ride: ItineraryLeg.Ride, alerts: List<RtAlert>, nowSec: Long): List<String> {
        val stopIds = ride.stops.mapNotNull { it.feedId }.toSet()
        val short = ride.line.shortName.trim()
        return alerts.asSequence()
            .filter { it.activeAt(nowSec) }
            .filter { a ->
                (ride.tripId != null && ride.tripId in a.tripIds) ||
                    a.stopIds.any { it in stopIds } ||
                    (short.isNotEmpty() && a.routeIds.any { routeLine(it).equals(short, ignoreCase = true) })
            }
            .map { it.text }
            .distinct()
            .take(MAX_ALERTS)
            .toList()
    }

    private val routeTail = Regex("""^\d+T\d+(.+)$""")

    /** `10T0014C4a` -> `C4a`; anything that does not look like a Renfe route id -> "". */
    internal fun routeLine(routeId: String): String = routeTail.matchEntire(routeId.trim())?.groupValues?.get(1)?.trim().orEmpty()
}

/**
 * The [RideRealTime] of the app: Renfe's data through a [RealTimeRepository], only while [enabled] says the user switched
 * it on. With the switch off it answers null for everything and never touches the repository. It does not fetch: the app
 * calls [RealTimeRepository.refresh] (through [refreshIfEnabled]) at the moments real time is useful.
 */
class RenfeRideRealTime(
    private val repository: RealTimeRepository,
    private val enabled: () -> Boolean,
    private val clockSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : RideRealTime {
    override fun forRide(ride: ItineraryLeg.Ride): LegRealTime? {
        if (!enabled() || ride.tripId == null) return null
        val snap = repository.current() ?: return null
        return RideMatcher.match(ride, snap, clockSec())
    }

    /** True when [ride] could have real time at all (the index kept its trip id). */
    fun supports(ride: ItineraryLeg.Ride): Boolean = ride.tripId != null

    /** Refreshes when the switch is on; blocking, call off the main thread. Returns what happened, or null when off. */
    fun refreshIfEnabled(): RefreshResult? = if (enabled()) repository.refresh() else null
}
