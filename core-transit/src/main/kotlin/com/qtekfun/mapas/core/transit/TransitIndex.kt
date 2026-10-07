package com.qtekfun.mapas.core.transit

/** Provenance of one merged feed. Kept in the index to honour the attribution / update-date terms. */
data class FeedSource(
    val label: String,
    val version: String,
    /** Epoch days; Int.MIN_VALUE / Int.MAX_VALUE when the feed does not declare it. */
    val calendarStartDay: Int,
    val calendarEndDay: Int,
    val attribution: String,
    /** True when the calendar range was ignored at build time (expired feed, spike shortcut). */
    val calendarRangeIgnored: Boolean,
)

/**
 * Immutable, planner-ready transit timetable (RAPTOR layout).
 *
 * A *pattern* is the set of trips of one line that serve the same ordered list of stops. Trips of a pattern are
 * stored back to back, sorted by departure from the first stop, in two flat arrays ([arrivals], [departures]) so a
 * scan touches contiguous memory and allocates nothing. Trip `t` (0-based inside pattern `p`) stop `i` lives at
 * `patternTimeBase[p] + t * patternStopCount(p) + i`.
 *
 * Times are seconds since midnight of the service day and may exceed 86400 (trips running past midnight).
 *
 * Each pattern lists its scheduled trips first (sorted by first departure, binary-searchable) and then its
 * frequency-based trips (`frequencies.txt` windows kept as they are, never expanded into explicit trips):
 * such a trip stores the times of its first run and repeats it [tripRuns] times every [tripHeadway] seconds.
 */
class TransitIndex(
    val sources: List<FeedSource>,
    // stops (coordinates in micro-degrees)
    val stopLat: IntArray,
    val stopLon: IntArray,
    val stopName: Array<String>,
    /** Parent-station group id (stops sharing a value are the same station), or -1. */
    val stopGroup: IntArray,
    // lines
    val lineShortName: Array<String>,
    val lineLongName: Array<String>,
    /** 0xRRGGBB or -1. */
    val lineColor: IntArray,
    val lineTextColor: IntArray,
    /** GTFS route_type (0 tram, 1 metro, 2 rail, 3 bus ...). */
    val lineType: IntArray,
    // services
    val serviceMask: IntArray,
    val serviceStart: IntArray,
    val serviceEnd: IntArray,
    val serviceAdded: Array<IntArray>,
    val serviceRemoved: Array<IntArray>,
    // patterns
    val patternLine: IntArray,
    val patternStopOffset: IntArray,
    val patternStops: IntArray,
    val patternTripOffset: IntArray,
    val patternTimeBase: IntArray,
    /** Index (absolute trip number) of the first frequency-based trip of each pattern; scheduled trips come first. */
    val patternFreqStart: IntArray,
    val tripService: IntArray,
    /** 0 for a scheduled trip; for a frequency trip the headway in seconds between consecutive runs. */
    val tripHeadway: IntArray,
    /** Number of runs of the trip (1 for scheduled trips): run k leaves `k * headway` after the stored times. */
    val tripRuns: IntArray,
    val tripHeadsign: IntArray,
    val headsigns: Array<String>,
    val arrivals: IntArray,
    val departures: IntArray,
    // GTFS transfers (stop pairs)
    val transferFrom: IntArray,
    val transferTo: IntArray,
    val transferType: IntArray,
    val transferMinSec: IntArray,
) {
    val stopCount: Int get() = stopLat.size
    val patternCount: Int get() = patternLine.size
    val tripCount: Int get() = tripService.size
    val stopTimeCount: Int get() = arrivals.size
    val lineCount: Int get() = lineShortName.size

    fun patternStopCount(p: Int): Int = patternStopOffset[p + 1] - patternStopOffset[p]
    fun patternTrips(p: Int): Int = patternTripOffset[p + 1] - patternTripOffset[p]

    /** Whether service [s] runs on epoch day [day]. */
    fun serviceActive(s: Int, day: Int): Boolean {
        if (serviceRemoved[s].binarySearch(day) >= 0) return false
        if (serviceAdded[s].binarySearch(day) >= 0) return true
        if (day < serviceStart[s] || day > serviceEnd[s]) return false
        return (serviceMask[s] shr Math.floorMod(day + 3, 7)) and 1 != 0
    }

    fun describe(): String =
        "stops=$stopCount lines=$lineCount patterns=$patternCount trips=$tripCount stopTimes=$stopTimeCount"
}
