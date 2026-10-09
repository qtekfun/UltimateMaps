package com.qtekfun.ultimatemaps.core.transit

/**
 * The kinds of vehicle a traveller can include or exclude, derived from the GTFS `route_type` of a line. Walking is not a
 * mode: a walking transfer is never excluded.
 */
enum class TransitMode {
    BUS, METRO, TRAM, TRAIN, FERRY,

    /** Cable cars, funiculars, monorails, taxis and every code that is not one of the five above. Always allowed. */
    OTHER,
    ;

    companion object {
        /** Every mode: no filter. */
        val ALL: Set<TransitMode> = entries.toSet()

        /** The modes the user can switch off (everything but [OTHER]), in display order. */
        val FILTERABLE: List<TransitMode> = listOf(BUS, METRO, TRAM, TRAIN, FERRY)

        /**
         * Maps a GTFS route type (basic 0-12 and the extended hierarchy of Google's transit extension) to a mode. The ranges
         * follow the extended spec: 100-199 railway, 200-299 coach, 300-399 suburban railway, 400-499 urban railway (metro),
         * 700-799 bus, 800-899 trolleybus, 900-999 tram, 1000-1299 water (ferry); the rest is [OTHER].
         */
        fun ofRouteType(routeType: Int): TransitMode = when (routeType) {
            0 -> TRAM
            1 -> METRO
            2 -> TRAIN
            3, 11 -> BUS
            4 -> FERRY
            in 100..199 -> TRAIN
            in 200..299 -> BUS
            in 300..399 -> TRAIN
            in 400..499 -> METRO
            in 700..899 -> BUS
            in 900..999 -> TRAM
            in 1000..1299 -> FERRY
            else -> OTHER
        }
    }
}

/** Why a journey is shown the way it is. */
enum class JourneyNote {
    /** Walking is first because every vehicle journey saves too little time compared with it. */
    WALK_ABOUT_AS_FAST,

    /** An alternative that walks clearly less than the fastest option (and arrives a little later). */
    LESS_WALKING,

    /** An alternative with fewer vehicle changes than the fastest option. */
    FEWER_CHANGES,

    /** An alternative that rides less and walks the rest, beyond the "maximum walking per trip" setting. */
    WALK_THE_REST,

    /** The only way found starts with a walk to a more distant station or stop, longer than the normal access radius. */
    LONG_WALK_TO_STATION,
}

/**
 * Per-request choices on top of the [PlannerConfig] defaults; a null number means "use the config". [modes] are the allowed
 * modes: a line of any other mode is never boarded.
 */
data class PlanOptions(
    val modes: Set<TransitMode> = TransitMode.ALL,
    /** The walk-only itinerary is offered when walking takes at most this long (seconds). */
    val walkAlternativeMaxSec: Int? = null,
    /** A vehicle journey must save at least this many seconds over walking (and 20 % of the walk) or it is dropped. */
    val minTransitSavingSec: Int? = null,
    /** Cap on the walking of one journey in seconds (0 = no cap). */
    val maxTotalWalkSec: Int? = null,
)
