package com.qtekfun.ultimatemaps.core.transit.rt

/** One stop of a GTFS-RT trip update. Times are epoch seconds, delays seconds (negative: early). */
data class RtStopUpdate(
    val stopId: String,
    val arrivalTime: Long? = null,
    val arrivalDelaySec: Int? = null,
    val departureTime: Long? = null,
    val departureDelaySec: Int? = null,
    /** The train does not stop here (`SKIPPED`). */
    val skipped: Boolean = false,
) {
    /** The scheduled arrival (or departure) implied by the update: the predicted time minus the delay. */
    fun scheduledAt(): Long? = when {
        arrivalTime != null && arrivalDelaySec != null -> arrivalTime - arrivalDelaySec
        departureTime != null && departureDelaySec != null -> departureTime - departureDelaySec
        else -> null
    }

    fun delay(): Int? = arrivalDelaySec ?: departureDelaySec
}

/** A GTFS-RT trip update: the whole trip cancelled, or a delay for the trip and for the next stop(s). */
data class RtTripUpdate(
    val tripId: String,
    val cancelled: Boolean,
    /** Delay of the trip as a whole (seconds), when the feed says one. */
    val delaySec: Int?,
    val stops: List<RtStopUpdate>,
)

data class RtVehicle(val tripId: String?, val stopId: String?, val lat: Double?, val lon: Double?, val timestamp: Long?)

/** A service alert. [routeIds], [stopIds] and [tripIds] say what it is about; empty lists mean "not restricted by that". */
data class RtAlert(
    val id: String,
    val routeIds: List<String>,
    val stopIds: List<String>,
    val tripIds: List<String>,
    val startSec: Long?,
    val endSec: Long?,
    val text: String,
) {
    fun activeAt(nowSec: Long): Boolean = (startSec == null || nowSec >= startSec) && (endSec == null || nowSec <= endSec)
}

/** What one successful refresh brought. [feedTimestampSec] is the feed header's own time (null if absent). */
class RtSnapshot(
    val tripUpdates: Map<String, RtTripUpdate>,
    val alerts: List<RtAlert>,
    val vehicles: Map<String, RtVehicle>,
    val feedTimestampSec: Long?,
    val fetchedAtMillis: Long,
)

/** What real time says about one ride of an itinerary. Absent fields mean "nothing known", never "on time". */
data class LegRealTime(
    /** Seconds late (negative: early) when the train was found in the feed. */
    val delaySec: Int? = null,
    val cancelled: Boolean = false,
    /** Alert texts that apply to this line or stops, at most a few. */
    val alerts: List<String> = emptyList(),
    /** Names of the ride's stops (boarding or alighting) that the train skips. */
    val skippedStops: List<String> = emptyList(),
) {
    /** True when there is something to show. */
    val hasInfo: Boolean get() = delaySec != null || cancelled || alerts.isNotEmpty() || skippedStops.isNotEmpty()

    /** True when the train itself was found in the feed (so its times are real, not scheduled). */
    val trainFound: Boolean get() = delaySec != null || cancelled
}
