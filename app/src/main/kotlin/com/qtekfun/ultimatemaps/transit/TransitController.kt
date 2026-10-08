package com.qtekfun.ultimatemaps.transit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.TransitMapLeg
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.PlanOptions
import com.qtekfun.ultimatemaps.core.transit.TransitMode
import com.qtekfun.ultimatemaps.core.transit.TransitPlan
import com.qtekfun.ultimatemaps.core.transit.TransitService
import com.qtekfun.ultimatemaps.transit.follow.TransitPlanningDefaults
import com.qtekfun.ultimatemaps.transit.follow.TransitTripSettingsStore
import com.qtekfun.ultimatemaps.transit.follow.planOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The device clock, injectable so that tests never depend on real time. */
interface TransitClock {
    fun now(): Instant
    fun zone(): ZoneId

    object System : TransitClock {
        override fun now(): Instant = Instant.now()
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }
}

/** What is available for a trip, decided without touching the network. */
sealed interface TransitLookup {
    /** An installed index covers both points. */
    data class Ready(val service: TransitService, val city: String) : TransitLookup

    /** The catalog offers a city that covers the trip but it is not installed. */
    data class NotDownloaded(val city: String) : TransitLookup

    /**
     * The two ends are served by two different indexes (for example Barcelona and Valencia) and none serves both.
     * Trips across indexes are not planned.
     */
    data class AcrossIndexes(val originCity: String, val destinationCity: String) : TransitLookup

    /** Data exists somewhere but not for this trip. */
    data object OutsideCoverage : TransitLookup

    /** No installed index and the catalog lists none. */
    data object NoData : TransitLookup

    /** The installed file could not be read. */
    data object Unreadable : TransitLookup
}

/** Where the controller finds the planner for a trip. Blocking: called on the io dispatcher. */
fun interface TransitSource {
    fun lookup(origin: LatLon, destination: LatLon): TransitLookup
}

enum class TransitPhase { IDLE, NEEDS_ORIGIN, COMPUTING, DONE, ERROR }

enum class TransitError { NO_DATA, NOT_DOWNLOADED, ACROSS_INDEXES, OUTSIDE_COVERAGE, EXPIRED, NOT_YET_VALID, NO_ROUTE, NO_ROUTE_MODES, INTERNAL }

/** Observable state of the transit mode of the route panel. Written from the main thread only. */
class TransitState {
    var phase by mutableStateOf(TransitPhase.IDLE)
    var error by mutableStateOf<TransitError?>(null)

    /** City named by [TransitError.NOT_DOWNLOADED]. */
    var errorCity by mutableStateOf<String?>(null)

    /** Second city named by [TransitError.ACROSS_INDEXES] ([errorCity] is the origin's). */
    var errorCity2 by mutableStateOf<String?>(null)

    /** Last / first valid day named by [TransitError.EXPIRED] / [TransitError.NOT_YET_VALID]. */
    var errorDate by mutableStateOf<LocalDate?>(null)

    /** Null means "leave now"; otherwise the chosen local date and time in [zone]. */
    var departure by mutableStateOf<LocalDateTime?>(null)
    var zone by mutableStateOf(ZoneId.systemDefault())
    var itineraries by mutableStateOf<List<Itinerary>>(emptyList())

    /** The modes the traveller allows (the chips); only [TransitMode.FILTERABLE] ones. */
    var modes by mutableStateOf<Set<TransitMode>>(TransitPlanningDefaults.ALL_MODES)

    /** Modes of the city's index (from the last answer); the chips shown are the filterable ones in it. */
    var availableModes by mutableStateOf<Set<TransitMode>>(emptySet())

    /** The chip row is useful only when the index has two or more switchable modes. */
    val chipModes: List<TransitMode> get() = TransitMode.FILTERABLE.filter { it in availableModes }
    val showModeChips: Boolean get() = chipModes.size >= 2

    /** The itinerary drawn on the map (and shown in the card when [detail]). */
    var selected by mutableStateOf(0)
    var detail by mutableStateOf(false)

    // facts about the data behind the result, for the footer
    var city by mutableStateOf<String?>(null)
    var attribution by mutableStateOf<List<String>>(emptyList())
    var validFrom by mutableStateOf<LocalDate?>(null)
    var validTo by mutableStateOf<LocalDate?>(null)
    var unverifiedFeeds by mutableStateOf(0)

    val current: Itinerary? get() = itineraries.getOrNull(selected)
}

/**
 * The transit mode of the route preview: plans theoretical (timetable) trips with the installed city index and draws the
 * selected itinerary. The planner runs on [io]; the device clock is only read through [clock]. Nothing here touches the
 * network and no position is logged.
 */
class TransitController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val source: TransitSource,
    private val clock: TransitClock = TransitClock.System,
    private val showItinerary: (List<TransitMapLeg>) -> Unit = {},
    /** Starts the step-by-step follower on an itinerary; null hides the Start button (no follower available). */
    private val onStartTrip: ((Itinerary, ZoneId) -> Boolean)? = null,
    /** Cercanías real time for the cards; null hides it (tests, or no source). */
    val realTime: TransitRealTime? = null,
    /** The planner settings (allowed modes and walking limits); null plans with the defaults and does not remember the chips. */
    private val settings: TransitTripSettingsStore? = null,
) {
    val state = TransitState().also { st -> settings?.let { st.modes = it.allowedModes.value } }

    /** Sets one mode chip and plans again; remembered across runs. */
    fun setMode(mode: TransitMode, on: Boolean) {
        if (mode !in TransitMode.FILTERABLE) return
        applyModes(if (on) state.modes + mode else state.modes - mode)
    }

    /** All modes on again. */
    fun resetModes() = applyModes(TransitPlanningDefaults.ALL_MODES)

    private fun applyModes(modes: Set<TransitMode>) {
        if (modes == state.modes) return
        state.modes = modes
        settings?.setAllowedModes(modes)
        replanIfActive()
    }

    /** The planner options for the next query: the settings' limits and the chips' modes. */
    private fun options(): PlanOptions = (settings?.planOptions() ?: PlanOptions()).copy(modes = state.modes + TransitMode.OTHER)

    /** The itinerary card offers a Start button. */
    val canStartTrip: Boolean get() = onStartTrip != null

    /** Starts following the selected itinerary step by step. False when there is none or it has no vehicle leg. */
    fun startTrip(): Boolean {
        val trip = state.current ?: return false
        if (trip.isWalkOnly) return false
        return onStartTrip?.invoke(trip, state.zone) ?: false
    }

    private var job: Job? = null
    private var lastOrigin: LatLon? = null
    private var lastDestination: LatLon? = null

    /** Plans from [origin] (null: the position is not known yet) to [destination]. */
    fun plan(origin: LatLon?, destination: LatLon) {
        lastOrigin = origin
        lastDestination = destination
        replan()
    }

    private fun replan() {
        job?.cancel()
        clearResult()
        val from = lastOrigin
        val to = lastDestination ?: return
        if (from == null) {
            state.phase = TransitPhase.NEEDS_ORIGIN
            return
        }
        state.phase = TransitPhase.COMPUTING
        val chosen = state.departure
        val zone = state.zone
        val options = options()
        job = scope.launch {
            val outcome: Outcome = try {
                withContext(io) { compute(from, to, chosen, zone, options) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(TransitError.INTERNAL)
            }
            apply(outcome)
        }
    }

    private sealed interface Outcome {
        data class Found(val itineraries: List<Itinerary>, val service: TransitService, val city: String) : Outcome
        data class Failed(
            val error: TransitError,
            val city: String? = null,
            val date: LocalDate? = null,
            val zone: ZoneId? = null,
            val city2: String? = null,
            val available: Set<TransitMode>? = null,
        ) : Outcome
    }

    private fun compute(from: LatLon, to: LatLon, chosen: LocalDateTime?, zone: ZoneId, options: PlanOptions): Outcome {
        return when (val lookup = source.lookup(from, to)) {
            TransitLookup.NoData -> Outcome.Failed(TransitError.NO_DATA)
            TransitLookup.OutsideCoverage -> Outcome.Failed(TransitError.OUTSIDE_COVERAGE)
            TransitLookup.Unreadable -> Outcome.Failed(TransitError.INTERNAL)
            is TransitLookup.AcrossIndexes -> Outcome.Failed(TransitError.ACROSS_INDEXES, city = lookup.originCity, city2 = lookup.destinationCity)
            is TransitLookup.NotDownloaded -> Outcome.Failed(TransitError.NOT_DOWNLOADED, city = lookup.city)
            is TransitLookup.Ready -> {
                val service = lookup.service
                val whenAt = chosen?.atZone(service.zone)?.toInstant() ?: clock.now()
                when (val plan = service.plan(from, to, whenAt, options = options)) {
                    is TransitPlan.Found -> Outcome.Found(plan.itineraries, service, lookup.city)
                    TransitPlan.NoRoute -> Outcome.Failed(TransitError.NO_ROUTE, lookup.city, zone = service.zone, available = service.availableModes)
                    TransitPlan.NoRouteWithModes -> Outcome.Failed(TransitError.NO_ROUTE_MODES, lookup.city, zone = service.zone, available = service.availableModes)
                    is TransitPlan.Expired -> Outcome.Failed(TransitError.EXPIRED, lookup.city, plan.lastDay, service.zone)
                    is TransitPlan.NotYetValid -> Outcome.Failed(TransitError.NOT_YET_VALID, lookup.city, plan.firstDay, service.zone)
                }
            }
        }
    }

    private fun apply(outcome: Outcome) {
        when (outcome) {
            is Outcome.Found -> {
                state.zone = outcome.service.zone
                state.availableModes = outcome.service.availableModes
                state.itineraries = outcome.itineraries
                state.selected = 0
                state.detail = false
                state.city = outcome.city
                state.attribution = outcome.service.attributions
                state.validFrom = outcome.service.validFrom
                state.validTo = outcome.service.validTo
                state.unverifiedFeeds = outcome.service.validity?.unverifiedFeeds ?: outcome.service.index.sources.count { it.calendarRangeIgnored }
                state.phase = TransitPhase.DONE
                draw()
            }
            is Outcome.Failed -> {
                outcome.zone?.let { state.zone = it }
                outcome.available?.let { state.availableModes = it }
                state.error = outcome.error
                state.errorCity = outcome.city
                state.errorCity2 = outcome.city2
                state.errorDate = outcome.date
                state.phase = TransitPhase.ERROR
                showItinerary(emptyList())
            }
        }
    }

    private fun clearResult() {
        state.error = null
        state.errorCity = null
        state.errorCity2 = null
        state.errorDate = null
        state.itineraries = emptyList()
        state.selected = 0
        state.detail = false
        showItinerary(emptyList())
    }

    /** Leave now (the device clock at planning time). */
    fun departNow() {
        if (state.departure == null) return
        state.departure = null
        replanIfActive()
    }

    /** Leave at [at], local time of the city. */
    fun departAt(at: LocalDateTime) {
        state.departure = at.truncatedTo(ChronoUnit.MINUTES)
        replanIfActive()
    }

    /** Switches to "leave at", starting from the current time of the city rounded down to the minute. */
    fun departLater() {
        if (state.departure != null) return
        departAt(LocalDateTime.ofInstant(clock.now(), state.zone))
    }

    fun shiftMinutes(delta: Long) {
        val d = state.departure ?: return
        departAt(d.plusMinutes(delta))
    }

    fun shiftDays(delta: Long) {
        val d = state.departure ?: return
        departAt(d.plusDays(delta))
    }

    private fun replanIfActive() {
        if (lastDestination != null) replan()
    }

    fun select(index: Int) {
        if (index !in state.itineraries.indices) return
        state.selected = index
        state.detail = true
        draw()
    }

    /** Back from the itinerary card to the list. */
    fun back() {
        state.detail = false
    }

    private fun draw() {
        val it = state.current
        showItinerary(it?.let(::mapLegs).orEmpty())
    }

    /** Forgets the trip and clears the map (the route panel closed or left the transit mode). */
    fun clear() {
        job?.cancel()
        lastOrigin = null
        lastDestination = null
        clearResult()
        state.phase = TransitPhase.IDLE
    }

    companion object {
        /** Grey of the walking legs. */
        const val WALK_COLOR = 0xFF607D8B.toInt()

        fun mapLegs(itinerary: Itinerary): List<TransitMapLeg> = itinerary.legs.map { leg ->
            when (leg) {
                is ItineraryLeg.Walk -> TransitMapLeg(leg.path, WALK_COLOR, dashed = true)
                is ItineraryLeg.Ride -> TransitMapLeg(leg.path, leg.line.color, dashed = false)
            }
        }
    }
}
