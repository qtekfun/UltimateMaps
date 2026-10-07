package com.qtekfun.mapas.core.transit

import com.qtekfun.mapas.core.geo.LatLon
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
    /** A walk-only itinerary is offered when it takes at most this long. */
    val maxWalkOnlySec: Int = 1800,
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
    ) : Leg
}

data class Journey(val legs: List<Leg>) {
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

    private var activeDay = Int.MIN_VALUE
    private var active: Array<BooleanArray> = emptyArray()

    // result of findTrip
    private var foundTime = 0
    private var foundRun = 0

    /** Number of footpath edges (for diagnostics). */
    val footpathCount: Int get() = fpTo.size

    @Synchronized
    fun plan(origin: LatLon, destination: LatLon, epochDay: Int, departSec: Int): List<Journey> {
        loadActive(epochDay)
        val oLat = micro(origin.lat)
        val oLon = micro(origin.lon)
        val dLat = micro(destination.lat)
        val dLon = micro(destination.lon)

        for (k in 0..rounds) {
            arr[k].fill(INF)
            pPat[k].fill(NONE)
            pFrom[k].fill(-1)
        }
        best.fill(INF)
        marked.fill(false)
        accessSec.fill(-1)
        egressSec.fill(-1)

        // access
        val accessStops = nearest(oLat, oLon, config.accessRadiusM, config.maxAccessStops)
        var markedList = IntList()
        for (e in accessStops) {
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
        for (e in nearest(dLat, dLon, config.accessRadiusM, config.maxAccessStops)) egressSec[(e shr 32).toInt()] = (e and 0xFFFFFFFFL).toInt()

        val direct = walkSec(distanceM(oLat, oLon, dLat, dLon))
        val walkOnly = if (direct <= config.maxWalkOnlySec) {
            Journey(listOf(Leg.Walk(-1, -1, departSec, departSec + direct, (distanceM(oLat, oLon, dLat, dLon) * config.detourFactor).toInt())))
        } else {
            null
        }
        var bestDest = if (walkOnly != null) departSec + direct else INF

        val roundDest = IntArray(rounds + 1) { INF }
        val roundDestStop = IntArray(rounds + 1) { -1 }
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
                if (eg >= 0 && curArr[s] + eg < bestDest) {
                    bestDest = curArr[s] + eg
                    roundDest[k] = bestDest
                    roundDestStop[k] = s
                }
            }
            markedList = newMarked
        }
        for (m in 0 until markedList.size) marked[markedList[m]] = false

        val out = ArrayList<Journey>()
        for (k in 1..rounds) if (roundDestStop[k] >= 0) out.add(reconstruct(k, roundDestStop[k], oLat, oLon, dLat, dLon))
        // transit results all beat the walk-only time (it seeded the pruning bound), so keep walking only when alone
        if (out.isEmpty() && walkOnly != null) out.add(walkOnly)
        return out
    }

    /**
     * The best itinerary for each of the next [count] distinct departures: after each result the search is
     * restarted one minute after that itinerary's first boarding. Used as the "next departures" fallback.
     */
    fun planNextDepartures(origin: LatLon, destination: LatLon, epochDay: Int, departSec: Int, count: Int, horizonSec: Int = 6 * 3600): List<Journey> {
        val results = ArrayList<Journey>()
        var t = departSec
        while (results.size < count && t <= departSec + horizonSec) {
            val best = plan(origin, destination, epochDay, t).minWithOrNull(compareBy({ it.arriveSec }, { it.transfers })) ?: break
            if (best.rideCount == 0) {
                results.add(best)
                break
            }
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

    /** Nearest stops within [radiusM] as `stop shl 32 | walkSeconds`, nearest first, at most [limit]. */
    private fun nearest(lat: Int, lon: Int, radiusM: Int, limit: Int): LongArray {
        val found = ArrayList<Pair<Double, Int>>()
        forEachNear(lat, lon, radiusM) { s, d -> found.add(d to s) }
        found.sortBy { it.first }
        val m = min(limit, found.size)
        return LongArray(m) { (found[it].second.toLong() shl 32) or walkSec(found[it].first).toLong() }
    }

    private companion object {
        const val INF = Int.MAX_VALUE
        const val DAY = 86400
        const val M_PER_MICRO = 0.111195 // metres per micro-degree of latitude (mean Earth radius 6 371 km)
        const val NONE = -3
        const val ACCESS = -2
        const val WALK = -1
        val DAY_ORDER = intArrayOf(1, 0, 2)
    }
}
