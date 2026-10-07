package com.qtekfun.mapas.core.transit

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
)

/**
 * Merges one or more [GtfsFeed]s into a single [TransitIndex].
 *
 * - Only stops that have at least one trip are kept.
 * - Trips are grouped into patterns (same line, same ordered stop list) and sorted by first departure.
 * - `frequencies.txt` entries are expanded into explicit trips (a RAPTOR scan needs concrete departures):
 *   for each window, one trip every `headway_secs` from `start_time` while `< end_time`, with the template trip's
 *   relative stop offsets. `exact_times` is not distinguished (treated as 1).
 */
class TransitIndexBuilder {
    private val stopKeys = HashMap<String, Int>()
    private val stopLat = IntList()
    private val stopLon = IntList()
    private val stopName = ArrayList<String>()
    private val stopGroupKey = ArrayList<String>()

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
        val arr = IntList(64)
        val dep = IntList(64)
        val tripCount get() = svc.size
    }

    private fun headsign(s: String): Int = headsignIdx.getOrPut(s) {
        headsigns.add(s)
        headsigns.size - 1
    }

    fun addFeed(feed: GtfsFeed, options: FeedOptions, ignoredCalendarRange: Boolean = false) {
        val ns = options.stopNamespace
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

        for (trip in feed.tripIds.indices) {
            val a = feed.tripStopStart[trip]
            val b = feed.tripStopStart[trip + 1]
            val n = b - a
            if (n < 2) continue
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
                for (k in a until b) {
                    acc.arr.add(feed.stTimeArr[k])
                    acc.dep.add(feed.stTimeDep[k])
                }
            } else {
                val first = feed.stTimeDep[a]
                for (w in windows) {
                    val end = feed.freqEnd[w]
                    val step = feed.freqHeadway[w]
                    var t = feed.freqStart[w]
                    while (t < end) {
                        val shift = t - first
                        acc.svc.add(svc)
                        acc.head.add(head)
                        for (k in a until b) {
                            acc.arr.add(feed.stTimeArr[k] + shift)
                            acc.dep.add(feed.stTimeDep[k] + shift)
                        }
                        t += step
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

        sources.add(
            FeedSource(options.label, feed.feedVersion, feed.feedStartDay, feed.feedEndDay, options.attribution, ignoredCalendarRange),
        )
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
            totalTrips += acc.tripCount
            totalTimes += acc.tripCount * acc.stops.size
        }
        patStopOff[nP] = totalStops
        patTripOff[nP] = totalTrips
        val pStops = IntArray(totalStops)
        val tSvc = IntArray(totalTrips)
        val tHead = IntArray(totalTrips)
        val arrivals = IntArray(totalTimes)
        val departures = IntArray(totalTimes)
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
                val dst = patTimeBase[p] + newT * n
                System.arraycopy(acc.arr.data, oldT * n, arrivals, dst, n)
                System.arraycopy(acc.dep.data, oldT * n, departures, dst, n)
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
            tripService = tSvc,
            tripHeadsign = tHead,
            headsigns = headsigns.toTypedArray(),
            arrivals = arrivals,
            departures = departures,
            transferFrom = trFrom.toArray(),
            transferTo = trTo.toArray(),
            transferType = trType.toArray(),
            transferMinSec = trMin.toArray(),
        )
    }
}
