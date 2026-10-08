package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Result of [TransitService.plan]. */
sealed interface TransitPlan {
    data class Found(val itineraries: List<Itinerary>) : TransitPlan

    /** Valid data exists but nothing connects the two points at that time. */
    data object NoRoute : TransitPlan

    /** A route exists, but not with the allowed modes (the mode filter excluded it). */
    data object NoRouteWithModes : TransitPlan

    /** The requested day is after the last day the data covers. */
    data class Expired(val lastDay: LocalDate) : TransitPlan

    /** The requested day is before the first day the data covers. */
    data class NotYetValid(val firstDay: LocalDate) : TransitPlan
}

/**
 * Plans theoretical (timetable) trips over one city's [TransitIndex]. Pure: the departure time is a parameter, the
 * device clock is read by the caller. Refuses to plan outside the index's validity window instead of guessing.
 *
 * [zone] is the city's time zone: the schedule is local time, the itinerary carries absolute instants.
 */
class TransitService(val index: TransitIndex, val zone: ZoneId, config: PlannerConfig = PlannerConfig()) {
    private val planner = TransitPlanner(index, config)

    val validity: TransitValidity? = index.validity()

    val validFrom: LocalDate? get() = validity?.firstDay?.takeIf { it != Int.MIN_VALUE }?.let { LocalDate.ofEpochDay(it.toLong()) }
    val validTo: LocalDate? get() = validity?.lastDay?.takeIf { it != Int.MAX_VALUE }?.let { LocalDate.ofEpochDay(it.toLong()) }

    /** Attribution lines recorded in the index, without repeats. */
    val attributions: List<String> get() = index.sources.map { it.attribution }.filter { it.isNotBlank() }.distinct()

    fun isExpiredOn(date: LocalDate): Boolean = validTo?.let { date.isAfter(it) } == true

    /** Modes of the vehicles this city's index has (what a mode filter can act on). */
    val availableModes: Set<TransitMode> get() = index.availableModes

    /**
     * Plans from [origin] to [destination]. The options choose the allowed modes and override the walking limits of the
     * config. The walk-only itinerary is among the results whenever walking is short enough (see [TransitPlanner.plan]); when
     * nothing at all is left and the allowed modes are the reason, the answer is [TransitPlan.NoRouteWithModes].
     */
    fun plan(
        origin: LatLon,
        destination: LatLon,
        departAt: Instant,
        maxItineraries: Int = MAX_ITINERARIES,
        options: PlanOptions = PlanOptions(),
    ): TransitPlan {
        val localDate = departAt.atZone(zone).toLocalDate()
        validTo?.let { if (localDate.isAfter(it)) return TransitPlan.Expired(it) }
        validFrom?.let { if (localDate.isBefore(it)) return TransitPlan.NotYetValid(it) }
        val epochDay = localDate.toEpochDay().toInt()
        val dayStart = dayStartSeconds(localDate)
        val departSec = (departAt.epochSecond - dayStart).toInt()
        val first = planner.plan(origin, destination, epochDay, departSec, options)
        val walk = first.firstOrNull { it.rideCount == 0 }
        val journeys = ArrayList<Journey>(first.filter { it.rideCount > 0 })
        // Fewer than the wanted number of distinct options: add the next departures of the best one (same filters).
        if (journeys.size < maxItineraries) {
            val seen = journeys.map { signature(it) }.toHashSet()
            for (j in planner.planNextDepartures(origin, destination, epochDay, departSec, maxItineraries, options = options)) {
                if (j.rideCount > 0 && seen.add(signature(j))) journeys.add(j)
            }
        }
        if (journeys.isEmpty() && walk == null) {
            val excludedSomething = options.modes != TransitMode.ALL &&
                planner.plan(origin, destination, epochDay, departSec, options.copy(modes = TransitMode.ALL)).isNotEmpty()
            return if (excludedSomething) TransitPlan.NoRouteWithModes else TransitPlan.NoRoute
        }
        val sorted = journeys.sortedWith(compareBy({ it.arriveSec }, { it.transfers }, { it.departSec }))
        // Walking is always one of the options: first when it explains why no vehicle is offered, else by arrival time.
        val ordered = if (walk == null) {
            sorted.take(maxItineraries)
        } else if (walk.note != null) {
            (listOf(walk) + sorted).take(maxItineraries)
        } else {
            val pos = sorted.indexOfFirst { it.arriveSec > walk.arriveSec }.let { if (it < 0) sorted.size else it }
            val all = sorted.toMutableList().also { it.add(pos, walk) }
            // too many: drop the slowest vehicle journeys, never the walk
            while (all.size > maxItineraries) all.removeAt(all.indexOfLast { it !== walk })
            all
        }
        return TransitPlan.Found(ordered.map { toItinerary(it, dayStart, origin, destination) })
    }

    /** Epoch second that GTFS time 00:00:00 of [date] stands for: noon local minus 12 h (the GTFS definition, DST-safe). */
    private fun dayStartSeconds(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toEpochSecond() - 12 * 3600

    private fun signature(j: Journey): List<Int> =
        j.legs.filterIsInstance<Leg.Ride>().flatMap { listOf(it.line, it.fromStop, it.departSec) }

    private fun point(stop: Int) = LatLon(index.stopLat[stop] / 1e6, index.stopLon[stop] / 1e6)

    private fun toItinerary(j: Journey, dayStart: Long, origin: LatLon, destination: LatLon): Itinerary {
        fun at(sec: Int) = dayStart + sec
        val legs = j.legs.map { leg ->
            when (leg) {
                is Leg.Walk -> ItineraryLeg.Walk(
                    fromName = leg.fromStop.takeIf { it >= 0 }?.let { index.stopName[it] },
                    toName = leg.toStop.takeIf { it >= 0 }?.let { index.stopName[it] },
                    from = if (leg.fromStop >= 0) point(leg.fromStop) else origin,
                    to = if (leg.toStop >= 0) point(leg.toStop) else destination,
                    meters = leg.meters,
                    departAt = at(leg.departSec),
                    arriveAt = at(leg.arriveSec),
                )
                is Leg.Ride -> ItineraryLeg.Ride(
                    line = LineInfo.of(index, leg.line),
                    headsign = leg.headsign,
                    stops = leg.stops.map {
                        ItineraryStop(index.stopName[it.stop], point(it.stop), at(it.arriveSec), at(it.departSec), index.stopExtId?.get(it.stop)?.ifEmpty { null })
                    },
                    tripId = leg.trip.takeIf { it >= 0 }?.let { index.tripExtId?.get(it) }?.ifEmpty { null },
                )
            }
        }
        return Itinerary(legs, j.note)
    }

    companion object {
        const val MAX_ITINERARIES = 3
    }
}
