package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlinx.coroutines.flow.StateFlow

/**
 * What a traffic record is, as far as the user cares. Derived from the DATEX II `causeType`, the detailed type and the
 * record's creation reference (see [DatexIncidentParser.classify]).
 */
enum class IncidentKind {
    /** A stopped vehicle that switched on a connected V16 beacon (record reference starts with `V16_`). */
    V16,
    ACCIDENT,

    /** A road or a carriageway closed. */
    CLOSURE,

    /** Slow or stopped traffic. */
    CONGESTION,

    /** An object, a stuck vehicle without beacon, rockfall, flooding, damaged road. */
    OBSTACLE,

    /** Rain, snow, ice, slippery road. */
    WEATHER,

    /** Roadworks (can be hundreds at any time: off unless the user asks). */
    ROADWORKS,
}

/**
 * One active record of the national traffic feed. [location] is the point, or the first end of a stretch; [end] the
 * other end of a stretch. [directionDeg] is the bearing of the travel direction the record applies to when the feed
 * names one (`northBound`...), else null (all directions).
 */
data class TrafficIncident(
    val id: String,
    val kind: IncidentKind,
    val road: String,
    val location: LatLon,
    val end: LatLon?,
    val directionDeg: Int?,
    val municipality: String?,
    val province: String?,
    val kmPoint: Double?,
    val startMillis: Long?,
    val endMillis: Long?,
)

/** Result of reading the whole feed. [skipped] counts records with no usable position or kind. */
data class IncidentFeed(val publishedMillis: Long?, val incidents: List<TrafficIncident>, val skipped: Int)

/** What was downloaded and when. */
data class IncidentData(val fetchedAtMillis: Long, val publishedMillis: Long?, val incidents: List<TrafficIncident>)

/** Read side for the map layer and the warner. Cheap to call from the UI thread (in-memory index). */
interface IncidentRepository {
    /** Active incidents of the given [kinds] inside [bounds], at most [limit]. */
    fun incidentsIn(bounds: LatLonBounds, kinds: Set<IncidentKind>, limit: Int = 500): List<TrafficIncident>

    fun incident(id: String): TrafficIncident?

    /** When the data was last downloaded (epoch millis), or null when there is none. */
    val lastUpdateMillis: StateFlow<Long?>
}

/** A visible map rectangle (no antimeridian crossing: the data is Spanish). */
data class LatLonBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
}
