package com.qtekfun.mapas.core.transit.follow

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.transit.Itinerary
import com.qtekfun.mapas.core.transit.ItineraryLeg

/**
 * Plays an itinerary back as a traveller who follows the timetable exactly (optionally shifted): walks in a straight line over
 * the leg's scheduled time, waits at stops, and moves between stops linearly between departure and the next arrival. Fixes are
 * produced at a fixed step, with no real time anywhere: the clock is a variable the simulator moves.
 */
class TripSimulator(val itinerary: Itinerary, private val stepSec: Int = 5, private val accuracy: Float = 10f) {
    /** Epoch seconds "now"; the follower's clock reads it. */
    var now: Long = itinerary.departAt - 60

    val clockMillis: () -> Long = { now * 1000 }

    /** Where a traveller running [lateSec] behind the timetable is at epoch second [t]. */
    fun positionAt(t: Long, lateSec: Long = 0): LatLon {
        val s = t - lateSec
        val legs = itinerary.legs
        if (s <= legs.first().departAt) return start(legs.first())
        for ((i, leg) in legs.withIndex()) {
            if (s <= leg.arriveAt) return inside(leg, s)
            val next = legs.getOrNull(i + 1)
            if (next != null && s < next.departAt) return end(leg)
        }
        return end(legs.last())
    }

    private fun start(leg: ItineraryLeg): LatLon = when (leg) {
        is ItineraryLeg.Walk -> leg.from
        is ItineraryLeg.Ride -> leg.boarding.point
    }

    private fun end(leg: ItineraryLeg): LatLon = when (leg) {
        is ItineraryLeg.Walk -> leg.to
        is ItineraryLeg.Ride -> leg.alighting.point
    }

    private fun inside(leg: ItineraryLeg, s: Long): LatLon = when (leg) {
        is ItineraryLeg.Walk -> lerp(leg.from, leg.to, frac(s, leg.departAt, leg.arriveAt))
        is ItineraryLeg.Ride -> {
            var p = leg.alighting.point
            for (k in 0 until leg.stops.size - 1) {
                val a = leg.stops[k]
                val b = leg.stops[k + 1]
                if (s < a.departAt) {
                    p = a.point
                    break
                }
                if (s <= b.arriveAt) {
                    p = lerp(a.point, b.point, frac(s, a.departAt, b.arriveAt))
                    break
                }
            }
            p
        }
    }

    private fun frac(s: Long, a: Long, b: Long): Double = if (b <= a) 1.0 else ((s - a).toDouble() / (b - a)).coerceIn(0.0, 1.0)

    private fun lerp(a: LatLon, b: LatLon, f: Double) = LatLon(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))

    /**
     * Runs the trip to [untilSec] (default: a few minutes after the scheduled arrival), feeding [follower] one fix per step,
     * except while [blackout] says the signal is lost (then it only ticks). Returns every distinct state the follower went
     * through, in order, with the prompts it produced.
     */
    fun run(
        follower: ItineraryFollower,
        lateSec: Long = 0,
        untilSec: Long = itinerary.arriveAt + lateSec + 600,
        blackout: (Long) -> Boolean = { false },
        onUpdate: (Long, FollowUpdate) -> Unit = { _, _ -> },
    ) {
        while (now <= untilSec) {
            val update = if (blackout(now)) follower.tick() else follower.onFix(LocationFix(positionAt(now, lateSec), accuracyMeters = accuracy))
            onUpdate(now, update)
            now += stepSec
        }
    }
}
