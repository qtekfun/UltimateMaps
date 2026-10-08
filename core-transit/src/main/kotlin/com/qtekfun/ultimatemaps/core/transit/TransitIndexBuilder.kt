package com.qtekfun.ultimatemaps.core.transit

/** How one feed is merged into the index. */
class FeedOptions(
    /** Human label kept in the index provenance. */
    val label: String,
    /**
     * Stops are merged across feeds by `namespace:stop_id`. Feeds that share one stop-id space (all CRTM feeds
     * except EMT) use the same namespace so a stop served by several of them becomes a single stop.
     */
    val stopNamespace: String,
    val attribution: String,
    /**
     * Drops trips whose last arrival is not after their first departure (circular or untimed trips in some CRTM
     * feeds): they would teleport riders. Counted in [TransitIndexBuilder.droppedTrips].
     */
    val dropNonPositiveDuration: Boolean = false,
    /** Keep the feed's stop and trip ids in the index (for matching a GTFS-RT feed). Costs about 13 bytes per trip. */
    val keepIds: Boolean = false,
)

/**
 * Merges one or more [GtfsFeed]s into a single [TransitIndex].
 *
 * - Only stops that have at least one trip are kept.
 * - Trips are grouped into patterns (same line, same ordered stop list) and sorted by first departure.
 * - `frequencies.txt` windows are kept as frequency trips (not expanded): the template trip's stop offsets are
 *   anchored at `start_time` and repeat every `headway_secs` while the departure is `< end_time`.
 *   `exact_times` is not distinguished (treated as 1). Expanding them instead was measured to turn the EMT feed
 *   into millions of explicit trips (see docs/phase2/transit.md).
 */
class TransitIndexBuilder {
    private val stopKeys = HashMap<String, Int>()
    private val stopLat = IntList()
    private val stopLon = IntList()
    private val stopName = ArrayList<String>()
    private val stopGroupKey = ArrayList<String>()
    private val stopExt = ArrayList<String>()
    private var anyKeepIds = false

    private val lineShort = ArrayList<String>()
    private val lineLong = ArrayList<String>()
    private val lineColor = IntList()
    private val lineText = IntList()
    private val lineType = IntList()

    private val svcMask = IntList()
    private val svcStart = IntList()
    private val svcEnd = IntList()
    private val svcAdded = ArrayList<IntArray>()
    private val svcRemoved = ArrayList<IntArray>()

    private val headsignIdx = HashMap<String, Int>()
    private val headsigns = ArrayList<String>()

    private val patterns = LinkedHashMap<PatternKey, PatternAcc>()
    private val sources = ArrayList<FeedSource>()

    /** Trips left out by [FeedOptions.dropNonPositiveDuration], summed over the feeds added so far. */
    var droppedTrips = 0
        private set

    private val trFrom = IntList()
    private val trTo = IntList()
    private val trType = IntList()
    private val trMin = IntList()

    private class PatternKey(val line: Int, val stops: IntArray) {
        private val hash = 31 * line + stops.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) =
            other is PatternKey && other.line == line && other.stops.contentEquals(stops)
    }

    private class PatternAcc(val line: Int, val stops: IntArray) {
        val svc = IntList()
        val head = IntList()
        val ext = ArrayList<String>()
        val arr = IntList(64)
        val dep = IntList(64)
        val tripCount get() = svc.size

        // frequency-based trips
        val fSvc = IntList()
        val fHead = IntList()
        val fExt = ArrayList<String>()
        val fHeadway = IntList()
        val fRuns = IntList()
        val fArr = IntList()
        val fDep = IntList()
        val freqCount get() = fSvc.size
    }

    private fun headsign(s: String): Int = headsignIdx.getOrPut(s) {
        headsigns.add(s)
        headsigns.size - 1
    }

    fun addFeed(feed: GtfsFeed, options: FeedOptions, ignoredCalendarRange: Boolean = false) {
        val ns = options.stopNamespace
        if (options.keepIds) anyKeepIds = true
        val localStop = IntArray(feed.stopIds.size) { -1 }
        fun stopOf(i: Int): Int {
            var g = localStop[i]
            if (g >= 0) return g
            val key = ns + ":" + feed.stopIds[i]
            g = stopKeys.getOrPut(key) {
                stopLat.add(Math.round(feed.stopLat[i] * 1e6).toInt())
                stopLon.add(Math.round(feed.stopLon[i] * 1e6).toInt())
                stopName.add(feed.stopNames[i])
                stopGroupKey.add(if (feed.stopParents[i].isEmpty()) "" else ns + ":" + feed.stopParents[i])
                stopExt.add(if (options.keepIds) feed.stopIds[i] else "")
                stopName.size - 1
            }
            localStop[i] = g
            return g
        }

        val localLine = IntArray(feed.routeIds.size) { -1 }
        fun lineOf(r: Int): Int {
            if (localLine[r] >= 0) return localLine[r]
            lineShort.add(feed.routeShort[r])
            lineLong.add(feed.routeLong[r])
            lineColor.add(feed.routeColor[r])
            lineText.add(feed.routeTextColor[r])
            lineType.add(feed.routeType[r])
            localLine[r] = lineShort.size - 1
            return localLine[r]
        }

        val svcBase = svcMask.size
        for (s in feed.services) {
            svcMask.add(s.mask)
            svcStart.add(s.startDay)
            svcEnd.add(s.endDay)
            svcAdded.add(s.added)
            svcRemoved.add(s.removed)
        }

        // trip -> frequency windows
        val freqByTrip = HashMap<Int, ArrayList<Int>>()
        for (i in feed.freqTrip.indices) freqByTrip.getOrPut(feed.freqTrip[i]) { ArrayList(2) }.add(i)

        val usedServices = HashSet<Int>()
        for (trip in feed.tripIds.indices) {
            val a = feed.tripStopStart[trip]
            val b = feed.tripStopStart[trip + 1]
            val n = b - a
            if (n < 2) continue
            if (options.dropNonPositiveDuration && feed.stTimeArr[b - 1] <= feed.stTimeDep[a]) {
                droppedTrips++
                continue
            }
            usedServices.add(feed.tripService[trip])
            val stops = IntArray(n) { stopOf(feed.stTimeStop[a + it]) }
            val line = lineOf(feed.tripRoute[trip])
            val key = PatternKey(line, stops)
            val acc = patterns.getOrPut(key) { PatternAcc(line, stops) }
            val svc = svcBase + feed.tripService[trip]
            val head = headsign(feed.tripHeadsign[trip])
            val windows = freqByTrip[trip]
            if (windows == null) {
                acc.svc.add(svc)
                acc.head.add(head)
                acc.ext.add(if (options.keepIds) feed.tripIds[trip] else "")
                for (k in a until b) {
                    acc.arr.add(feed.stTimeArr[k])
                    acc.dep.add(feed.stTimeDep[k])
                }
            } else {
                val first = feed.stTimeDep[a]
                for (w in windows) {
                    val start = feed.freqStart[w]
                    val end = feed.freqEnd[w]
                    val step = feed.freqHeadway[w]
                    if (start < 0 || end <= start) continue
                    val shift = start - first
                    acc.fSvc.add(svc)
                    acc.fHead.add(head)
                    acc.fExt.add("")
                    acc.fHeadway.add(step)
                    acc.fRuns.add((end - start + step - 1) / step)
                    for (k in a until b) {
                        acc.fArr.add(feed.stTimeArr[k] + shift)
                        acc.fDep.add(feed.stTimeDep[k] + shift)
                    }
                }
            }
        }

        // GTFS transfers between stops that are in the index
        for (i in feed.transferFrom.indices) {
            val f = stopKeys[ns + ":" + feed.transferFrom[i]] ?: continue
            val t = stopKeys[ns + ":" + feed.transferTo[i]] ?: continue
            if (f == t) continue
            trFrom.add(f)
            trTo.add(t)
            trType.add(feed.transferType[i])
            trMin.add(feed.transferMinSec[i])
        }

        val window = if (ignoredCalendarRange) null else calendarWindow(feed, usedServices)
        val start = window?.first ?: feed.feedStartDay
        val end = window?.last ?: feed.feedEndDay
        sources.add(FeedSource(options.label, feed.feedVersion, start, end, options.attribution, ignoredCalendarRange))
    }

    fun build(): TransitIndex {
        val pats = patterns.values.toList()
        val nP = pats.size
        val patternLine = IntArray(nP)
        val patStopOff = IntArray(nP + 1)
        val patTripOff = IntArray(nP + 1)
        val patTimeBase = IntArray(nP)
        var totalStops = 0
        var totalTrips = 0
        var totalTimes = 0
        for ((p, acc) in pats.withIndex()) {
            patternLine[p] = acc.line
            patStopOff[p] = totalStops
            patTripOff[p] = totalTrips
            patTimeBase[p] = totalTimes
            totalStops += acc.stops.size
            totalTrips += acc.tripCount + acc.freqCount
            totalTimes += (acc.tripCount + acc.freqCount) * acc.stops.size
        }
        patStopOff[nP] = totalStops
        patTripOff[nP] = totalTrips
        val pStops = IntArray(totalStops)
        val tSvc = IntArray(totalTrips)
        val tHead = IntArray(totalTrips)
        val tHeadway = IntArray(totalTrips)
        val tRuns = IntArray(totalTrips) { 1 }
        val patFreqStart = IntArray(nP)
        val arrivals = IntArray(totalTimes)
        val departures = IntArray(totalTimes)
        val tExt = if (anyKeepIds) Array(totalTrips) { "" } else null
        for ((p, acc) in pats.withIndex()) {
            val n = acc.stops.size
            System.arraycopy(acc.stops, 0, pStops, patStopOff[p], n)
            val nt = acc.tripCount
            val order = (0 until nt).sortedWith(
                compareBy<Int> { acc.dep[it * n] }.thenBy { acc.arr[it * n + n - 1] },
            )
            for ((newT, oldT) in order.withIndex()) {
                tSvc[patTripOff[p] + newT] = acc.svc[oldT]
                tHead[patTripOff[p] + newT] = acc.head[oldT]
                tExt?.set(patTripOff[p] + newT, acc.ext[oldT])
                val dst = patTimeBase[p] + newT * n
                System.arraycopy(acc.arr.data, oldT * n, arrivals, dst, n)
                System.arraycopy(acc.dep.data, oldT * n, departures, dst, n)
            }
            patFreqStart[p] = patTripOff[p] + nt
            val nf = acc.freqCount
            val forder = (0 until nf).sortedBy { acc.fDep[it * n] }
            for ((newT, oldT) in forder.withIndex()) {
                val ti = patTripOff[p] + nt + newT
                tSvc[ti] = acc.fSvc[oldT]
                tHead[ti] = acc.fHead[oldT]
                tHeadway[ti] = acc.fHeadway[oldT]
                tRuns[ti] = acc.fRuns[oldT]
                val dst = patTimeBase[p] + (nt + newT) * n
                System.arraycopy(acc.fArr.data, oldT * n, arrivals, dst, n)
                System.arraycopy(acc.fDep.data, oldT * n, departures, dst, n)
            }
        }
        val groupIds = HashMap<String, Int>()
        val group = IntArray(stopName.size) { i ->
            val k = stopGroupKey[i]
            if (k.isEmpty()) -1 else groupIds.getOrPut(k) { groupIds.size }
        }
        return TransitIndex(
            sources = sources.toList(),
            stopLat = stopLat.toArray(),
            stopLon = stopLon.toArray(),
            stopName = stopName.toTypedArray(),
            stopGroup = group,
            lineShortName = lineShort.toTypedArray(),
            lineLongName = lineLong.toTypedArray(),
            lineColor = lineColor.toArray(),
            lineTextColor = lineText.toArray(),
            lineType = lineType.toArray(),
            serviceMask = svcMask.toArray(),
            serviceStart = svcStart.toArray(),
            serviceEnd = svcEnd.toArray(),
            serviceAdded = svcAdded.toTypedArray(),
            serviceRemoved = svcRemoved.toTypedArray(),
            patternLine = patternLine,
            patternStopOffset = patStopOff,
            patternStops = pStops,
            patternTripOffset = patTripOff,
            patternTimeBase = patTimeBase,
            patternFreqStart = patFreqStart,
            tripService = tSvc,
            tripHeadway = tHeadway,
            tripRuns = tRuns,
            tripHeadsign = tHead,
            headsigns = headsigns.toTypedArray(),
            arrivals = arrivals,
            departures = departures,
            transferFrom = trFrom.toArray(),
            transferTo = trTo.toArray(),
            transferType = trType.toArray(),
            transferMinSec = trMin.toArray(),
            stopExtId = if (anyKeepIds) stopExt.toTypedArray() else null,
            tripExtId = tExt,
        )
    }
}

/**
 * The days the services [used] of [feed] can run (epoch days, inclusive), narrowed by `feed_info` when it declares a
 * range; null when no service has a bounded calendar. Feeds without `feed_info` (EMT) would otherwise look unbounded.
 */
fun calendarWindow(feed: GtfsFeed, used: Collection<Int> = feed.services.indices.toList()): IntRange? {
    var from = Int.MAX_VALUE
    var to = Int.MIN_VALUE
    for (i in used) {
        val sv = feed.services[i]
        if (sv.mask != 0 && sv.startDay != Int.MIN_VALUE && sv.endDay != Int.MAX_VALUE) {
            from = minOf(from, sv.startDay)
            to = maxOf(to, sv.endDay)
        }
        if (sv.added.isNotEmpty()) {
            from = minOf(from, sv.added.first())
            to = maxOf(to, sv.added.last())
        }
    }
    if (from > to) return null
    return maxOf(from, feed.feedStartDay)..minOf(to, feed.feedEndDay)
}
