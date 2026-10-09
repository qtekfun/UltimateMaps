package com.qtekfun.ultimatemaps.core.transit

/** One element of the badge row of an itinerary: a walking pill or a vehicle line badge. */
sealed interface JourneyBadge {
    /** A walk of [minutes] (at least 1, rounded up), taken from the legs' own depart/arrive times. */
    data class Walk(val minutes: Int) : JourneyBadge

    /** A vehicle leg; [rideIndex] counts the rides only (0 for the first), so it matches [Itinerary.rides]. */
    data class Line(val ride: ItineraryLeg.Ride, val rideIndex: Int) : JourneyBadge
}

/**
 * The badge row in travel order, Google Maps style: a walking pill for every walk of at least one minute (the first and last
 * ones, access and egress, included) and a line badge for every ride. Walks of zero length (a change between platforms of one
 * station) are left out; consecutive walks are merged defensively. A walk-only trip yields just its pill.
 */
fun Itinerary.badges(): List<JourneyBadge> {
    val out = ArrayList<JourneyBadge>()
    var pendingSec = 0L
    var rideIndex = 0
    fun flush() {
        if (pendingSec > 0) out += JourneyBadge.Walk(((pendingSec + 59) / 60).toInt())
        pendingSec = 0
    }
    for (leg in legs) {
        when (leg) {
            is ItineraryLeg.Walk -> pendingSec += maxOf(0L, leg.arriveAt - leg.departAt)
            is ItineraryLeg.Ride -> {
                flush()
                out += JourneyBadge.Line(leg, rideIndex++)
            }
        }
    }
    flush()
    return out
}
