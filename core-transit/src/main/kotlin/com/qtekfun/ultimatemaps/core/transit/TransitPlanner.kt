package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Modelling constants. They are assumptions, not measurements; see docs/phase2/transit.md.
 */
data class PlannerConfig(
    /** Walking speed in m/s (1.25 m/s = 4.5 km/h). */
    val walkSpeedMps: Double = 1.25,
    /** Straight-line distance is multiplied by this to approximate the street network. */
    val detourFactor: Double = 1.3,
    /** Origin/destination to stop walking radius, in metres of straight-line distance. */
    val accessRadiusM: Int = 800,
    /** At most this many nearest stops are used as access (and as egress) points. */
    val maxAccessStops: Int = 30,
    /** Stop-to-stop walking transfers are generated up to this straight-line distance. */
    val transferRadiusM: Int = 300,
    /** Safety margin added when changing vehicles (arrival to earliest boarding). */
    val transferSlackSec: Int = 60,
    /** Minimum walking time between two platforms of the same station (shared parent_station). */
    val stationTransferSec: Int = 120,
    /** Maximum number of vehicle changes (rounds = maxTransfers + 1). */
    val maxTransfers: Int = 4,
    /** The walk-only itinerary is always offered when walking (with the detour factor) takes at most this long. */
    val walkAlternativeMaxSec: Int = 1200,
    /** A vehicle journey is dropped when it saves less than this over walking (when walking is offered). */
    val minTransitSavingSec: Int = 300,
    /** ... or less than this fraction of the walking time. */
    val minTransitSavingFraction: Double = 0.2,
    /** Cap on the walking of one journey (access, transfers and egress); 0 = no cap. */
    val maxTotalWalkSec: Int = 900,
    /**
     * Longest walking of an "extra walking" alternative (access, transfers and egress), seconds. Such alternatives are shown
     * beyond [maxTotalWalkSec] only when nothing within the cap does as well, and are marked [JourneyNote.WALK_THE_REST].
     */
    val extendedWalkSec: Int = 3600,
    /** Alternatives within the walking cap that [TransitPlanner.plan] returns, at most. */
    val maxAlternatives: Int = 4,
    /** "More walking" alternatives (beyond the cap) that [TransitPlanner.plan] returns, at most. */
    val maxExtendedAlternatives: Int = 2,
    /** An alternative may arrive later than the fastest by at most this, or the fastest trip duration when longer (seconds). */
    val alternativeSlackSec: Int = 1800,
    /** When the fastest journey walks more than this, a second search with a smaller access radius looks for a calmer one. */
    val lowWalkPassMinWalkSec: Int = 300,
    /**
     * When no vehicle journey exists with the normal [accessRadiusM] (for example because the allowed modes serve no stop
     * nearby), the access radius grows through these straight-line steps, in metres, up to the walking cap of the request (and [maxAccessWalkSec]).
     */
    val accessWidenRadiiM: List<Int> = listOf(1500, 2500, 3500),
    /**
     * Absolute ceiling, seconds, of the walk from the origin to the first stop of a journey found with a widened access
     * radius (1 h). The widening also never exceeds the request's walking cap (the "max walking per trip" setting).
     */
    val maxAccessWalkSec: Int = 3600,
)

/** One stop of a ride with its scheduled times (seconds since midnight of the query service day). */
data class RideStop(val stop: Int, val arriveSec: Int, val departSec: Int)

sealed interface Leg {
    /** Seconds since midnight of the query service day (can exceed 86400). */
    val departSec: Int
    val arriveSec: Int

    /** [fromStop] / [toStop] are stop ids of the index, or -1 for the origin / destination point. */
    data class Walk(val fromStop: Int, val toStop: Int, override val departSec: Int, override val arriveSec: Int, val meters: Int) : Leg

    /** [stopCount] is the number of stops after the boarding one, up to and including the alighting one. */
    data class Ride(
        val line: Int,
        val headsign: String,
        val fromStop: Int,
        val toStop: Int,
        override val departSec: Int,
        override val arriveSec: Int,
        val stopCount: Int,
        /** Every stop from boarding to alighting (both included) with its scheduled times; empty when not recorded. */
        val stops: List<RideStop> = emptyList(),
        /** Absolute trip number in the index (for its feed id), or -1. */
        val trip: Int = -1,
    ) : Leg
}

data class Journey(val legs: List<Leg>, val note: JourneyNote? = null) {
    /** Seconds spent walking (all walking legs). */
    val walkSec: Int get() = legs.sumOf { if (it is Leg.Walk) it.arriveSec - it.departSec else 0 }

    val departSec: Int get() = legs.first().departSec
    val arriveSec: Int get() = legs.last().arriveSec
    val rideCount: Int get() = legs.count { it is Leg.Ride }
    val transfers: Int get() = max(0, rideCount - 1)

    fun format(index: TransitIndex): String = buildString {
        fun name(s: Int, fallback: String) = if (s < 0) fallback else index.stopName[s]
        fun hm(t: Int) = "%02d:%02d".format(t / 3600 % 24, t / 60 % 60) + if (t >= 86400) "+1" else ""
        append("${hm(departSec)}-${hm(arriveSec)} (${(arriveSec - departSec) / 60} min, $transfers transfers)")
        for (l in legs) {
            append("\n  ")
            when (l) {
                is Leg.Walk -> append("walk ${l.meters} m ${name(l.fromStop, "origin")} -> ${name(l.toStop, "destination")} ${hm(l.departSec)}-${hm(l.arriveSec)}")
                is Leg.Ride -> append(
                    "ride ${index.lineShortName[l.line]} to '${l.headsign}' ${index.stopName[l.fromStop]} ${hm(l.departSec)} -> " +
                        "${index.stopName[l.toStop]} ${hm(l.arriveSec)} (${l.stopCount} stops)",
                )
            }
        }
    }
}

/**
 * Earliest-arrival public transport planner (RAPTOR) over a [TransitIndex], fully in memory.
 *
 * - Rounds: round k holds the best arrival using exactly k vehicle rides; results are the Pareto set over
 *   (arrival time, number of rides).
 * - Walking: access/egress from arbitrary coordinates to the nearest stops, and stop-to-stop transfers, all by
 *   straight-line distance x [PlannerConfig.detourFactor] at [PlannerConfig.walkSpeedMps].
 * - Calendar: trips of the previous, current and next service day are considered, so trips running past midnight
 *   and journeys that cross midnight work.
 * - Not modelled: real time, pickup/drop-off restrictions, fares, accessibility, route-specific GTFS transfers.
 *
 * Instances are not thread-safe for concurrent queries (a shared workspace is reused); [plan] is synchronized.
 */
class TransitPlanner(val index: TransitIndex, val config: PlannerConfig = PlannerConfig()) {
    private val nStops = index.stopCount
    private val nPatterns = index.patternCount
    private val rounds = config.maxTransfers + 1

    // ---- spatial grid of stops
    private val cellLat = 4000 // micro-degrees (~445 m)
    private val cellLon = 5000
    private val grid = HashMap<Long, IntArray>()

    // ---- stop -> (pattern, position) CSR
    private val spStart = IntArray(nStops + 1)
    private val spPattern: IntArray
    private val spPos: IntArray

    // ---- walking transfers CSR
    private val fpStart = IntArray(nStops + 1)
    private val fpTo: IntArray
    private val fpSec: IntArray

    init {
        val tmp = HashMap<Long, IntList>()
        for (s in 0 until nStops) tmp.getOrPut(cellKeyOf(index.stopLat[s], index.stopLon[s])) { IntList(4) }.add(s)
        for ((k, v) in tmp) grid[k] = v.toArray()

        val counts = IntArray(nStops + 1)
        for (p in 0 until nPatterns) for (i in index.patternStopOffset[p] until index.patternStopOffset[p + 1]) counts[index.patternStops[i] + 1]++
        for (s in 0 until nStops) counts[s + 1] += counts[s]
        System.arraycopy(counts, 0, spStart, 0, nStops + 1)
        spPattern = IntArray(counts[nStops])
        spPos = IntArray(counts[nStops])
        val fill = counts.copyOf(nStops)
        for (p in 0 until nPatterns) {
            val off = index.patternStopOffset[p]
            for (i in 0 until index.patternStopCount(p)) {
                val s = index.patternStops[off + i]
                spPattern[fill[s]] = p
                spPos[fill[s]] = i
                fill[s]++
            }
        }

        // Footpaths: radius neighbours, station platforms and GTFS transfers.
        val blocked = HashSet<Long>()
        val minTime = HashMap<Long, Int>()
        for (i in index.transferFrom.indices) {
            val key = index.transferFrom[i].toLong() * nStops + index.transferTo[i]
            if (index.transferType[i] == 3) blocked.add(key) else if (index.transferMinSec[i] > 0) minTime[key] = index.transferMinSec[i]
        }
        val toList = IntList(1 shl 16)
        val secList = IntList(1 shl 16)
        for (s in 0 until nStops) {
            fpStart[s] = toList.size
            val seen = HashSet<Int>()
            forEachNear(index.stopLat[s], index.stopLon[s], config.transferRadiusM) { q, meters ->
                if (q == s || blocked.contains(s.toLong() * nStops + q)) return@forEachNear
                var sec = walkSec(meters)
                if (index.stopGroup[s] >= 0 && index.stopGroup[s] == index.stopGroup[q]) sec = max(sec, config.stationTransferSec)
                minTime[s.toLong() * nStops + q]?.let { sec = max(sec, it) }
                seen.add(q)
                toList.add(q)
                secList.add(sec)
            }
            for (i in index.transferFrom.indices) {
                if (index.transferFrom[i] != s || index.transferType[i] == 3) continue
                val q = index.transferTo[i]
                if (q in seen) continue
                val meters = distanceM(index.stopLat[s], index.stopLon[s], index.stopLat[q], index.stopLon[q])
                toList.add(q)
                secList.add(max(walkSec(meters), index.transferMinSec[i]))
            }
        }
        fpStart[nStops] = toList.size
        fpTo = toList.toArray()
        fpSec = secList.toArray()
    }

    // ---- per-query workspace (reused)
    private val arr = Array(rounds + 1) { IntArray(nStops) }
    private val pPat = Array(rounds + 1) { IntArray(nStops) }
    private val pInst = Array(rounds + 1) { IntArray(nStops) }
    private val pRun = Array(rounds + 1) { IntArray(nStops) }
    private val tArr = Array(rounds + 1) { IntArray(nStops) }
    private val pBoard = Array(rounds + 1) { IntArray(nStops) }
    private val pAlight = Array(rounds + 1) { IntArray(nStops) }
    private val pFrom = Array(rounds + 1) { IntArray(nStops) }
    private val best = IntArray(nStops)
    private val marked = BooleanArray(nStops)
    private val patEarliest = IntArray(nPatterns)
    private val accessSec = IntArray(nStops)
    private val egressSec = IntArray(nStops)

    private val patternMode = Array(nPatterns) { TransitMode.ofRouteType(index.lineType[index.patternLine[it]]) }
    private val patAllowed = BooleanArray(nPatterns)

    /** Whether at least one allowed pattern calls at the stop; only such stops can start or end a journey. */
    private val stopUsable = BooleanArray(nStops)
    private var allowedModes: Set<TransitMode>? = null

    private var activeDay = Int.MIN_VALUE
    private var active: Array<BooleanArray> = emptyArray()

    // result of findTrip
    private var foundTime = 0
    private var foundRun = 0

    /** Number of footpath edges (for diagnostics). */
    val footpathCount: Int get() = fpTo.size

    /**
     * Plans one query. The result holds the vehicle journeys and, when walking is a reasonable alternative, the walk-only
     * journey (no ride legs).
     *
     * - Walking is offered when it takes at most `walkAlternativeMaxSec`. Then a vehicle journey must beat it by
     *   `max(minTransitSavingSec, minTransitSavingFraction x walking)`; if some vehicle journey beat walking but not by
     *   enough, the walk-only journey carries [JourneyNote.WALK_ABOUT_AS_FAST].
     * - With [alternatives] (the default) the vehicle journeys are a bounded set of trade-offs, see [alternativesFor]: the
     *   fastest, the least walking, the fewest changes and, beyond `maxTotalWalkSec`, up to
     *   [PlannerConfig.maxExtendedAlternatives] that ride less and walk the rest (marked [JourneyNote.WALK_THE_REST]).
     *   None is dominated by another in (arrival, walking, changes). The search is not cut at the earliest arrival but a
     *   margin after it, and the egress covers every stop within [PlannerConfig.extendedWalkSec] of walking.
     * - Without it, only the earliest arrival per number of rides is searched and `maxTotalWalkSec` is a filter on those
     *   (access and egress stops limited to the cap); used for the "next departures" fallback.
     * - Lines whose mode is not in `options.modes` are never boarded; walking transfers are always allowed.
     *
     * [walkReferenceDepartSec] is the time walking would have started; [planNextDepartures] passes the original query time so
     * that a later bus is compared with leaving on foot now.
     */
    @Synchronized
    fun plan(
        origin: LatLon,
        destination: LatLon,
        epochDay: Int,
        departSec: Int,
        options: PlanOptions = PlanOptions(),
        walkReferenceDepartSec: Int = departSec,
        alternatives: Boolean = true,
    ): List<Journey> {
        loadActive(epochDay)
        applyModes(options.modes)
        val walkAltMax = options.walkAlternativeMaxSec ?: config.walkAlternativeMaxSec
        val minSaving = options.minTransitSavingSec ?: config.minTransitSavingSec
        val walkCap = options.maxTotalWalkSec ?: config.maxTotalWalkSec
        val oLat = micro(origin.lat)
        val oLon = micro(origin.lon)
        val dLat = micro(destination.lat)
        val dLon = micro(destination.lon)

        val direct = walkSec(distanceM(oLat, oLon, dLat, dLon))
        val walkOffered = direct <= walkAltMax
        val walkMeters = (distanceM(oLat, oLon, dLat, dLon) * config.detourFactor).toInt()
        // The walk leaves when the traveller asked to leave, even if a later bus is being looked for.
        val walkArrive = walkReferenceDepartSec + direct
        val requiredSaving = if (walkOffered) max(minSaving, ceil(direct * config.minTransitSavingFraction).toInt()) else 0
        // Vehicle journeys that do not even beat walking are not searched; those that beat it by too little are found
        // and then dropped, which is how the "walking is about as fast" note is known.
        val startBound = if (walkOffered) walkArrive else INF
        val limit = if (walkOffered) walkArrive - requiredSaving else INF

        val out = ArrayList<Journey>()
        var droppedForSaving = false
        if (!alternatives) {
            searchRounds(oLat, oLon, dLat, dLon, departSec, config.accessRadiusM, config.maxAccessStops, walkCap, config.accessRadiusM, config.maxAccessStops, walkCap, startBound, false)
            for (k in 1..rounds) {
                if (roundDestStop[k] < 0) continue
                val j = reconstruct(k, roundDestStop[k], oLat, oLon, dLat, dLon)
                if (walkCap > 0 && j.walkSec > walkCap) continue
                if (walkOffered && j.arriveSec > limit) {
                    droppedForSaving = true
                    continue
                }
                out.add(j)
            }
        } else {
            val poolCap = if (walkCap > 0) walkCap else config.maxTotalWalkSec
            val extCap = max(config.extendedWalkSec, poolCap)
            val extRadius = (extCap * config.walkSpeedMps / config.detourFactor).toInt()
            val pool = ArrayList<Journey>()
            fun collect(accessR: Int, accessMax: Int, egressR: Int, egressCap: Int, accessCap: Int = walkCap) {
                searchRounds(oLat, oLon, dLat, dLon, departSec, accessR, accessMax, accessCap, egressR, EGRESS_UNLIMITED, egressCap, startBound, true)
                for (c in nonDominated(candidates(extCap))) {
                    if (walkOffered && c.arrive > limit) {
                        if (c.arrive < walkArrive) droppedForSaving = true
                        continue
                    }
                    pool.add(reconstruct(c.k, c.stop, oLat, oLon, dLat, dLon))
                }
            }
            collect(config.accessRadiusM, config.maxAccessStops, extRadius, extCap)
            val fastestWalk = pool.minWithOrNull(compareBy({ it.arriveSec }, { it.walkSec }))?.walkSec ?: 0
            if (fastestWalk >= config.lowWalkPassMinWalkSec) {
                // A calmer search: small access and egress radii find the journeys that start and end next to a stop.
                collect(config.accessRadiusM / 2, config.maxAccessStops / 3, config.accessRadiusM / 2, extCap)
            }
            var widened = false
            if (pool.isEmpty() && !droppedForSaving) {
                // Nothing within the normal access radius: widen it step by step, never beyond the walking cap of the request
                // (no cap: maxAccessWalkSec) nor beyond maxAccessWalkSec.
                val accessCapSec = if (walkCap > 0) min(walkCap, config.maxAccessWalkSec) else config.maxAccessWalkSec
                val maxR = (accessCapSec * config.walkSpeedMps / config.detourFactor).toInt()
                for (r in config.accessWidenRadiiM.map { min(it, maxR) }.distinct().filter { it > config.accessRadiusM }) {
                    collect(r, config.maxAccessStops, extRadius, extCap, accessCapSec)
                    if (pool.isNotEmpty()) {
                        widened = true
                        break
                    }
                }
            }
            val picked = alternativesFor(pool, poolCap)
            if (widened) {
                // every journey here starts with a walk longer than the normal radius allows: say so
                val normalAccessSec = walkSec(config.accessRadiusM.toDouble())
                out.addAll(
                    picked.map { j ->
                        val first = j.legs.first()
                        if (first is Leg.Walk && first.arriveSec - first.departSec > normalAccessSec) j.copy(note = JourneyNote.LONG_WALK_TO_STATION) else j
                    },
                )
            } else {
                out.addAll(picked)
            }
        }
        if (walkOffered) {
            val leg = Leg.Walk(-1, -1, walkReferenceDepartSec, walkArrive, walkMeters)
            out.add(Journey(listOf(leg), if (droppedForSaving) JourneyNote.WALK_ABOUT_AS_FAST else null))
        }
        return out
    }

    private val roundDest = IntArray(rounds + 1)
    private val roundDestStop = IntArray(rounds + 1)

    /**
     * The RAPTOR rounds. Access: the [accessMax] nearest stops within [accessR] metres whose walk is at most [accessCapSec]
     * (0 = any); egress likewise. [startBound] is the arrival that a journey must beat. In [wide] mode the search is not cut
     * at the earliest destination arrival but a margin after it, so that slower journeys with less walking or fewer rides are
     * found too; the labels are then read with [candidates]. Otherwise [roundDest] / [roundDestStop] hold the best
     * destination arrival of each round.
     */
    private fun searchRounds(
        oLat: Int, oLon: Int, dLat: Int, dLon: Int, departSec: Int,
        accessR: Int, accessMax: Int, accessCapSec: Int,
        egressR: Int, egressMax: Int, egressCapSec: Int,
        startBound: Int, wide: Boolean,
    ) {
        for (k in 0..rounds) {
            arr[k].fill(INF)
            pPat[k].fill(NONE)
            pFrom[k].fill(-1)
        }
        best.fill(INF)
        marked.fill(false)
        accessSec.fill(-1)
        egressSec.fill(-1)
        roundDest.fill(INF)
        roundDestStop.fill(-1)

        // access
        var markedList = IntList()
        for (e in nearest(oLat, oLon, accessR, accessMax, accessCapSec)) {
            val s = (e shr 32).toInt() // packed below
            val sec = (e and 0xFFFFFFFFL).toInt()
            accessSec[s] = sec
            arr[0][s] = departSec + sec
            best[s] = departSec + sec
            pPat[0][s] = ACCESS
            markedList.add(s)
            marked[s] = true
        }
        // egress
        for (e in nearest(dLat, dLon, egressR, egressMax, egressCapSec)) egressSec[(e shr 32).toInt()] = (e and 0xFFFFFFFFL).toInt()

        // The search is cut at bestDest: the best destination arrival (narrow) or a margin after it (wide).
        var bestDest = startBound
        var bestFound = INF
        val queue = IntList()
        patEarliest.fill(-1)

        for (k in 1..rounds) {
            if (markedList.size == 0) break
            // collect patterns reachable from the stops improved in the previous round
            queue.clear()
            for (m in 0 until markedList.size) {
                val s = markedList[m]
                marked[s] = false
                for (e in spStart[s] until spStart[s + 1]) {
                    val p = spPattern[e]
                    if (!patAllowed[p]) continue
                    val pos = spPos[e]
                    if (patEarliest[p] < 0) {
                        queue.add(p)
                        patEarliest[p] = pos
                    } else if (pos < patEarliest[p]) {
                        patEarliest[p] = pos
                    }
                }
            }
            val newMarked = IntList()
            val prevArr = arr[k - 1]
            val prevPat = pPat[k - 1]
            val curArr = arr[k]
            for (qi in 0 until queue.size) {
                val p = queue[qi]
                val pos0 = patEarliest[p]
                patEarliest[p] = -1
                val n = index.patternStopCount(p)
                val stopsOff = index.patternStopOffset[p]
                val base = index.patternTimeBase[p]
                var curInst = -1
                var curRun = 0
                var curDep = INF
                var curBoard = 0
                for (i in pos0 until n) {
                    val s = index.patternStops[stopsOff + i]
                    if (curInst >= 0) {
                        val tl = curInst / 3
                        val shift = (curInst % 3 - 1) * DAY + curRun * index.tripHeadway[index.patternTripOffset[p] + tl]
                        val a = index.arrivals[base + tl * n + i] + shift
                        if (a < best[s] && a < bestDest) {
                            curArr[s] = a
                            tArr[k][s] = a
                            best[s] = a
                            pPat[k][s] = p
                            pInst[k][s] = curInst
                            pRun[k][s] = curRun
                            pBoard[k][s] = curBoard
                            pAlight[k][s] = i
                            if (!marked[s]) {
                                marked[s] = true
                                newMarked.add(s)
                            }
                        }
                    }
                    if (i < n - 1) {
                        val pa = prevArr[s]
                        if (pa != INF) {
                            val ready = pa + if (prevPat[s] == ACCESS) 0 else config.transferSlackSec
                            if (curInst < 0 || ready <= curDep) {
                                val cand = findTrip(p, i, ready)
                                if (cand >= 0 && foundTime < curDep) {
                                    curInst = cand
                                    curRun = foundRun
                                    curDep = foundTime
                                    curBoard = i
                                }
                            }
                        }
                    }
                }
            }
            // walking transfers from stops improved by a vehicle in this round
            val transitCount = newMarked.size
            for (m in 0 until transitCount) {
                val s = newMarked[m]
                val base = tArr[k][s]
                for (e in fpStart[s] until fpStart[s + 1]) {
                    val q = fpTo[e]
                    val t = base + fpSec[e]
                    if (t < best[q] && t < bestDest) {
                        curArr[q] = t
                        best[q] = t
                        pFrom[k][q] = s
                        if (!marked[q]) {
                            marked[q] = true
                            newMarked.add(q)
                        }
                    }
                }
            }
            // destination reached through egress walking
            for (m in 0 until newMarked.size) {
                val s = newMarked[m]
                val eg = egressSec[s]
                if (eg < 0) continue
                val total = curArr[s] + eg
                if (wide) {
                    if (total < bestFound) {
                        bestFound = total
                        bestDest = min(startBound, bestFound + 2 * max(config.alternativeSlackSec, bestFound - departSec))
                    }
                } else if (total < bestDest) {
                    bestDest = total
                    roundDest[k] = total
                    roundDestStop[k] = s
                }
            }
            markedList = newMarked
        }
        for (m in 0 until markedList.size) marked[markedList[m]] = false
    }

    /** One end-of-search candidate: the destination reached from [stop], where [k] rides ended, plus the egress walk. */
    private class Cand(val k: Int, val stop: Int, val arrive: Int, val walk: Int)

    /** Drops the candidates that another one beats or equals in arrival, walking and rides. */
    private fun nonDominated(items: List<Cand>): List<Cand> {
        val kept = ArrayList<Cand>()
        for (c in items.sortedWith(compareBy({ it.arrive }, { it.walk }, { it.k }))) {
            // the kept ones arrive no later: c is dominated when one of them also walks no more and rides no more
            if (kept.none { it.walk <= c.walk && it.k <= c.k }) kept.add(c)
        }
        return kept
    }

    /** Every (rides, stop) pair that ends at a stop with an egress walk, read after a wide [searchRounds]. */
    private fun candidates(maxWalkSec: Int): List<Cand> {
        val list = ArrayList<Cand>()
        for (k in 1..rounds) for (s in 0 until nStops) {
            if (egressSec[s] < 0 || arr[k][s] == INF) continue
            val walk = walkOfLabel(k, s)
            if (walk > maxWalkSec) continue
            list.add(Cand(k, s, arr[k][s] + egressSec[s], walk))
        }
        return list
    }

    /** Walking seconds of the journey that ends at stop [s] after [k] rides: access, transfers and egress. */
    private fun walkOfLabel(k: Int, s: Int): Int {
        var w = egressSec[s]
        var kk = k
        var cur = s
        while (kk >= 1) {
            val f = pFrom[kk][cur]
            if (f >= 0) {
                w += arr[kk][cur] - tArr[kk][f]
                cur = f
            }
            val p = pPat[kk][cur]
            cur = index.patternStops[index.patternStopOffset[p] + pBoard[kk][cur]]
            kk--
        }
        return w + accessSec[cur]
    }

    /**
     * Picks the alternatives from every journey found: drops those dominated in (arrival, walking, rides), merges those that
     * board the same lines at the same stops (earliest arrival wins) and keeps the ones that arrive within
     * [PlannerConfig.alternativeSlackSec] (or the fastest trip duration, when longer) of the fastest; the least-walking and
     * fewest-changes picks may arrive twice that late.
     *
     * Within [walkCap]: the fastest, the one with the least walking, the one with the fewest changes, then the next fastest,
     * [PlannerConfig.maxAlternatives] in all. Beyond it: those with the fewest rides, at most
     * [PlannerConfig.maxExtendedAlternatives], marked [JourneyNote.WALK_THE_REST]. Sorted by arrival; the fastest has no note.
     */
    internal fun alternativesFor(found: List<Journey>, walkCap: Int): List<Journey> {
        val bySignature = LinkedHashMap<List<Int>, Journey>()
        for (j in found.sortedWith(compareBy({ it.arriveSec }, { it.walkSec }))) {
            bySignature.putIfAbsent(j.legs.filterIsInstance<Leg.Ride>().flatMap { listOf(it.line, it.fromStop) }, j)
        }
        val kept = ArrayList<Journey>()
        for (j in bySignature.values.sortedWith(compareBy({ it.arriveSec }, { it.walkSec }, { it.rideCount }))) {
            if (kept.none { it.walkSec <= j.walkSec && it.rideCount <= j.rideCount }) kept.add(j)
        }
        val inside = kept.filter { it.walkSec <= walkCap }
        val beyond = kept.filter { it.walkSec > walkCap }
        val base = inside.firstOrNull() ?: beyond.firstOrNull() ?: return emptyList()
        val slack = max(config.alternativeSlackSec, base.arriveSec - base.departSec)
        val near = inside.filter { it.arriveSec <= base.arriveSec + slack }
        // the least walking and the fewest changes may take up to twice as long: that is the point of choosing them
        val far = inside.filter { it.arriveSec <= base.arriveSec + 2 * slack }
        val picked = LinkedHashSet<Journey>()
        near.firstOrNull()?.let { picked.add(it) }
        far.minWithOrNull(compareBy({ it.walkSec }, { it.arriveSec }))?.let { picked.add(it) }
        far.minWithOrNull(compareBy({ it.rideCount }, { it.arriveSec }))?.let { picked.add(it) }
        for (j in near) if (picked.size < config.maxAlternatives) picked.add(j)
        val extras = beyond.filter { it.arriveSec <= base.arriveSec + slack }
            .sortedWith(compareBy({ it.rideCount }, { it.arriveSec }))
            .take(config.maxExtendedAlternatives)
        // the journeys within the walking cap first, by arrival; the "walk the rest" ones after them
        val order = compareBy<Journey>({ it.arriveSec }, { it.walkSec }, { it.rideCount })
        val main = picked.take(config.maxAlternatives).sortedWith(order)
        val all = main + extras.sortedWith(order)
        val fastest = all.first()
        val minWalk = main.minOfOrNull { it.walkSec } ?: fastest.walkSec
        val minRides = main.minOfOrNull { it.rideCount } ?: fastest.rideCount
        return all.map { j ->
            val lessWalk = j.walkSec + LESS_WALKING_MARGIN_SEC <= fastest.walkSec
            val note = when {
                j === fastest -> null
                j.walkSec > walkCap -> JourneyNote.WALK_THE_REST
                j.walkSec == minWalk && lessWalk -> JourneyNote.LESS_WALKING
                j.rideCount == minRides && j.rideCount < fastest.rideCount -> JourneyNote.FEWER_CHANGES
                lessWalk -> JourneyNote.LESS_WALKING
                else -> null
            }
            if (note == null) j else j.copy(note = note)
        }
    }

    /** Rebuilds the per-pattern "may be boarded" flags when the allowed modes changed since the last query. */
    private fun applyModes(modes: Set<TransitMode>) {
        if (modes == allowedModes) return
        for (p in 0 until nPatterns) patAllowed[p] = patternMode[p] in modes
        for (s in 0 until nStops) {
            var usable = false
            for (e in spStart[s] until spStart[s + 1]) if (patAllowed[spPattern[e]]) {
                usable = true
                break
            }
            stopUsable[s] = usable
        }
        allowedModes = modes
    }

    /**
     * The best itinerary for each of the next [count] distinct departures: after each result the search is
     * restarted one minute after that itinerary's first boarding. Used as the "next departures" fallback.
     */
    fun planNextDepartures(
        origin: LatLon,
        destination: LatLon,
        epochDay: Int,
        departSec: Int,
        count: Int,
        horizonSec: Int = 6 * 3600,
        options: PlanOptions = PlanOptions(),
    ): List<Journey> {
        val results = ArrayList<Journey>()
        var t = departSec
        while (results.size < count && t <= departSec + horizonSec) {
            // Only vehicle journeys: the walk-only option belongs to the query time, not to a later departure.
            val best = plan(origin, destination, epochDay, t, options, walkReferenceDepartSec = departSec, alternatives = false)
                .filter { it.rideCount > 0 }
                .minWithOrNull(compareBy({ it.arriveSec }, { it.transfers })) ?: break
            results.add(best)
            t = best.legs.first { it is Leg.Ride }.departSec - accessWalkBefore(best) + 60
        }
        return results
    }

    private fun accessWalkBefore(j: Journey): Int {
        val first = j.legs.first()
        return if (first is Leg.Walk) first.arriveSec - first.departSec else 0
    }

    // ------------------------------------------------------------------ internals

    private fun reconstruct(round: Int, destStop: Int, oLat: Int, oLon: Int, dLat: Int, dLon: Int): Journey {
        val legs = ArrayList<Leg>()
        var k = round
        var s = destStop
        val finalArr = arr[round][destStop]
        legs.add(
            Leg.Walk(
                s, -1, finalArr, finalArr + egressSec[s],
                (distanceM(index.stopLat[s], index.stopLon[s], dLat, dLon) * config.detourFactor).toInt(),
            ),
        )
        while (k >= 1) {
            if (pFrom[k][s] >= 0) {
                val f = pFrom[k][s]
                legs.add(
                    Leg.Walk(
                        f, s, tArr[k][f], arr[k][s],
                        (distanceM(index.stopLat[f], index.stopLon[f], index.stopLat[s], index.stopLon[s]) * config.detourFactor).toInt(),
                    ),
                )
                s = f
            }
            val p = pPat[k][s]
            check(p >= 0) { "broken parent chain" }
            val inst = pInst[k][s]
            val tl = inst / 3
            val shift = (inst % 3 - 1) * DAY + pRun[k][s] * index.tripHeadway[index.patternTripOffset[p] + tl]
            val n = index.patternStopCount(p)
            val off = index.patternStopOffset[p]
            val boardPos = pBoard[k][s]
            val alightPos = pAlight[k][s]
            val tBase = index.patternTimeBase[p] + tl * n
            val boardStop = index.patternStops[off + boardPos]
            legs.add(
                Leg.Ride(
                    line = index.patternLine[p],
                    headsign = index.headsigns[index.tripHeadsign[index.patternTripOffset[p] + tl]],
                    fromStop = boardStop,
                    toStop = index.patternStops[off + alightPos],
                    departSec = index.departures[tBase + boardPos] + shift,
                    arriveSec = index.arrivals[tBase + alightPos] + shift,
                    stopCount = alightPos - boardPos,
                    trip = index.patternTripOffset[p] + tl,
                    stops = (boardPos..alightPos).map { i ->
                        RideStop(index.patternStops[off + i], index.arrivals[tBase + i] + shift, index.departures[tBase + i] + shift)
                    },
                ),
            )
            s = boardStop
            k--
        }
        // k == 0: the entry at s is an access arrival
        val firstRide = legs.last() as Leg.Ride
        val sec = accessSec[s]
        legs.add(
            Leg.Walk(
                -1, s, firstRide.departSec - sec, firstRide.departSec,
                (distanceM(oLat, oLon, index.stopLat[s], index.stopLon[s]) * config.detourFactor).toInt(),
            ),
        )
        legs.reverse()
        // a zero-length walk is noise; two consecutive walks (transfer walk + egress walk) are shown as one
        val merged = ArrayList<Leg>()
        for (l in legs) {
            if (l is Leg.Walk && l.arriveSec == l.departSec && l.meters == 0) continue
            val prev = merged.lastOrNull()
            if (l is Leg.Walk && prev is Leg.Walk) {
                merged[merged.size - 1] = Leg.Walk(prev.fromStop, l.toStop, prev.departSec, l.arriveSec, prev.meters + l.meters)
            } else {
                merged.add(l)
            }
        }
        return Journey(merged)
    }

    /**
     * Earliest trip instance of pattern [p] departing stop position [pos] at or after [ready]. Returns
     * `tripLocalIndex * 3 + dayIdx` (dayIdx 0 = previous service day, 1 = today, 2 = tomorrow) or -1.
     * The departure time (seconds from today's midnight) is left in [foundTime] and, for frequency trips, the run
     * number in [foundRun].
     */
    private fun findTrip(p: Int, pos: Int, ready: Int): Int {
        val n = index.patternStopCount(p)
        val tOff = index.patternTripOffset[p]
        val nSched = index.patternFreqStart[p] - tOff
        val nt = index.patternTrips(p)
        val base = index.patternTimeBase[p]
        var bestInst = -1
        var bestTime = INF
        var bestRun = 0
        for (d in DAY_ORDER) {
            // order is today, yesterday, tomorrow: tomorrow's trips can only win when nothing else was found
            if (d == 2 && bestInst >= 0) break
            val shift = (d - 1) * DAY
            val act = active[d]
            // scheduled trips: sorted by first departure, binary search then first active one
            var lo = 0
            var hi = nSched
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (index.departures[base + mid * n + pos] + shift < ready) lo = mid + 1 else hi = mid
            }
            var tl = lo
            while (tl < nSched) {
                if (act[index.tripService[tOff + tl]]) {
                    val t = index.departures[base + tl * n + pos] + shift
                    if (t < bestTime) {
                        bestTime = t
                        bestInst = tl * 3 + d
                        bestRun = 0
                    }
                    break
                }
                tl++
            }
            // frequency trips: closed-form next run of every active window
            for (f in nSched until nt) {
                if (!act[index.tripService[tOff + f]]) continue
                val h = index.tripHeadway[tOff + f]
                val first = index.departures[base + f * n + pos] + shift
                val runs = index.tripRuns[tOff + f]
                val k = if (ready <= first) 0 else (ready - first + h - 1) / h
                if (k >= runs) continue
                val t = first + k * h
                if (t < bestTime) {
                    bestTime = t
                    bestInst = f * 3 + d
                    bestRun = k
                }
            }
        }
        foundTime = bestTime
        foundRun = bestRun
        return bestInst
    }

    private fun loadActive(epochDay: Int) {
        if (epochDay == activeDay) return
        val nSvc = index.serviceMask.size
        active = Array(3) { d -> BooleanArray(nSvc) { index.serviceActive(it, epochDay + d - 1) } }
        activeDay = epochDay
    }

    private fun walkSec(meters: Double): Int = ceil(meters * config.detourFactor / config.walkSpeedMps).toInt()

    private fun micro(deg: Double): Int = Math.round(deg * 1e6).toInt()

    private fun cellKeyOf(lat: Int, lon: Int): Long = cellKey(Math.floorDiv(lat, cellLat), Math.floorDiv(lon, cellLon))
    private fun cellKey(cy: Int, cx: Int): Long = (cy.toLong() shl 32) xor (cx.toLong() and 0xFFFFFFFFL)

    /** Straight-line distance in metres (equirectangular, accurate enough at city scale). */
    private fun distanceM(lat1: Int, lon1: Int, lat2: Int, lon2: Int): Double {
        val mLat = (lat2 - lat1) * M_PER_MICRO
        val mLon = (lon2 - lon1) * M_PER_MICRO * cos((lat1 + lat2) * 0.5e-6 * PI / 180.0)
        return sqrt(mLat * mLat + mLon * mLon)
    }

    private inline fun forEachNear(lat: Int, lon: Int, radiusM: Int, action: (Int, Double) -> Unit) {
        val dLatCells = ceil(radiusM / (M_PER_MICRO * cellLat)).toInt()
        val dLonCells = ceil(radiusM / (M_PER_MICRO * cellLon * cos(lat * 1e-6 * PI / 180.0))).toInt()
        val cy = Math.floorDiv(lat, cellLat)
        val cx = Math.floorDiv(lon, cellLon)
        for (y in cy - dLatCells..cy + dLatCells) for (x in cx - dLonCells..cx + dLonCells) {
            val cell = grid[cellKey(y, x)] ?: continue
            for (s in cell) {
                val d = distanceM(lat, lon, index.stopLat[s], index.stopLon[s])
                if (d <= radiusM) action(s, d)
            }
        }
    }

    /** Nearest stops within [radiusM] served by an allowed mode, as `stop shl 32 | walkSeconds`, nearest first, at most [limit]. */
    private fun nearest(lat: Int, lon: Int, radiusM: Int, limit: Int, maxWalkSec: Int = 0): LongArray {
        val found = ArrayList<Pair<Double, Int>>()
        forEachNear(lat, lon, radiusM) { s, d -> if (stopUsable[s] && (maxWalkSec <= 0 || walkSec(d) <= maxWalkSec)) found.add(d to s) }
        found.sortBy { it.first }
        val m = min(limit, found.size)
        return LongArray(m) { (found[it].second.toLong() shl 32) or walkSec(found[it].first).toLong() }
    }

    private companion object {
        const val INF = Int.MAX_VALUE
        const val DAY = 86400
        const val M_PER_MICRO = 0.111195 // metres per micro-degree of latitude (mean Earth radius 6 371 km)
        const val EGRESS_UNLIMITED = 100_000
        const val LESS_WALKING_MARGIN_SEC = 120
        const val NONE = -3
        const val ACCESS = -2
        const val WALK = -1
        val DAY_ORDER = intArrayOf(1, 0, 2)
    }
}
