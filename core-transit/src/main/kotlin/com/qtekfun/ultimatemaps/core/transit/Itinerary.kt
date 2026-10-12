package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon

/** How a line is drawn: names, ARGB colours (never absent) and the GTFS route_type. */
data class LineInfo(val shortName: String, val longName: String, val color: Int, val textColor: Int, val routeType: Int) {
    companion object {
        // Fallbacks when the feed has no route_color: one fixed colour per mode (a design choice, not data).
        private const val TRAM = 0xFF2E7D32.toInt()
        private const val METRO = 0xFFC62828.toInt()
        private const val RAIL = 0xFF00695C.toInt()
        private const val BUS = 0xFF1565C0.toInt()
        private const val OTHER = 0xFF546E7A.toInt()

        fun defaultColor(routeType: Int): Int = when (routeType) {
            0 -> TRAM
            1 -> METRO
            2 -> RAIL
            3 -> BUS
            else -> OTHER
        }

        /** Black or white, whichever reads better on [argb] (perceived luminance). */
        fun contrastText(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return if (0.299 * r + 0.587 * g + 0.114 * b > 150) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        fun of(index: TransitIndex, line: Int): LineInfo {
            val raw = index.lineColor[line]
            val color = if (raw >= 0) 0xFF000000.toInt() or raw else defaultColor(index.lineType[line])
            val rawText = index.lineTextColor[line]
            val text = if (rawText >= 0) 0xFF000000.toInt() or rawText else contrastText(color)
            val short = index.lineShortName[line].ifEmpty { index.lineLongName[line] }
            return LineInfo(short, index.lineLongName[line], color, text, index.lineType[line])
        }
    }
}

/** A stop of a ride with its scheduled times as absolute epoch seconds. */
data class ItineraryStop(
    val name: String,
    val point: LatLon,
    val arriveAt: Long,
    val departAt: Long,
    /** The feed's own stop id when the index kept it (real-time matching); null otherwise. */
    val feedId: String? = null,
)

/**
 * One part of an itinerary. All times are absolute epoch seconds (the schedule is converted with the city's time zone,
 * see [TransitService]). Designed so that a live follower can read it without the index: every ride lists its stops in
 * order with times and coordinates.
 */
sealed interface ItineraryLeg {
    val departAt: Long
    val arriveAt: Long

    /** Where the leg is drawn, in travel order. */
    val path: List<LatLon>

    /** [fromName] / [toName] are null for the origin / destination point chosen by the user. */
    data class Walk(
        val fromName: String?,
        val toName: String?,
        val from: LatLon,
        val to: LatLon,
        val meters: Int,
        override val departAt: Long,
        override val arriveAt: Long,
    ) : ItineraryLeg {
        override val path: List<LatLon> get() = listOf(from, to)
    }

    /**
     * A ride. [stops] holds every stop from boarding to alighting, both included. [geometry] is the line geometry when the
     * index carries it (rail patterns of feeds that publish shapes); without it [path] is the stop-to-stop line.
     */
    data class Ride(
        val line: LineInfo,
        val headsign: String,
        val stops: List<ItineraryStop>,
        val geometry: RideShape? = null,
        /** The feed's own trip id when the index kept it (real-time matching); null otherwise. */
        val tripId: String? = null,
    ) : ItineraryLeg {
        val boarding: ItineraryStop get() = stops.first()
        val alighting: ItineraryStop get() = stops.last()

        /** Number of stops after the boarding one, up to and including the alighting one. */
        val stopCount: Int get() = stops.size - 1
        override val departAt: Long get() = boarding.departAt
        override val arriveAt: Long get() = alighting.arriveAt

        /** The line geometry from boarding to alighting, or null when only the stop-to-stop line is known. */
        val shape: List<LatLon>? get() = geometry?.points
        override val path: List<LatLon> get() = geometry?.points ?: stops.map { it.point }

        /** The ride restricted to stops [from]..[to] (indices into [stops], both included), with its geometry cut to match. */
        fun slice(from: Int, to: Int): Ride =
            copy(stops = stops.subList(from, to + 1), geometry = geometry?.takeIf { it.stopVertex.size == stops.size }?.slice(from, to))
    }
}

/**
 * The polyline of a ride, from the boarding stop to the alighting one. Stop `i` of the ride is at vertex [stopVertex]`[i]` of
 * [points]; [onShape]`[i]` says whether the hop from stop `i` to stop `i + 1` follows the line's real geometry (true) or is
 * the straight chord because the data did not cover it. [stopVertex] is non-decreasing and has one entry per stop.
 */
class RideShape(val points: List<LatLon>, val stopVertex: IntArray, val onShape: BooleanArray) {
    init {
        require(points.size >= 2) { "a shape needs two points" }
        require(stopVertex.size >= 2 && onShape.size == stopVertex.size - 1) { "one entry per stop and per hop" }
    }

    /** The shape of stops [from]..[to] only. */
    fun slice(from: Int, to: Int): RideShape {
        val v0 = stopVertex[from]
        val v1 = stopVertex[to]
        return RideShape(
            points.subList(v0, v1 + 1),
            IntArray(to - from + 1) { stopVertex[from + it] - v0 },
            onShape.copyOfRange(from, to),
        )
    }

    /** This shape followed by [next], which must start at the stop where this one ends (that stop is not repeated). */
    fun then(next: RideShape): RideShape {
        val shift = points.size - 1
        return RideShape(
            points + next.points.drop(1),
            stopVertex + IntArray(next.stopVertex.size - 1) { next.stopVertex[it + 1] + shift },
            onShape + next.onShape,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is RideShape && points == other.points && stopVertex.contentEquals(other.stopVertex) && onShape.contentEquals(other.onShape)

    override fun hashCode(): Int = 31 * (31 * points.hashCode() + stopVertex.contentHashCode()) + onShape.contentHashCode()
}

/** A complete theoretical (schedule-based) trip. */
data class Itinerary(val legs: List<ItineraryLeg>, val note: JourneyNote? = null) {
    val departAt: Long get() = legs.first().departAt
    val arriveAt: Long get() = legs.last().arriveAt
    val durationSec: Long get() = arriveAt - departAt
    val rides: List<ItineraryLeg.Ride> get() = legs.filterIsInstance<ItineraryLeg.Ride>()
    val transfers: Int get() = maxOf(0, rides.size - 1)
    val walkMeters: Int get() = legs.filterIsInstance<ItineraryLeg.Walk>().sumOf { it.meters }
    val isWalkOnly: Boolean get() = rides.isEmpty()
}
