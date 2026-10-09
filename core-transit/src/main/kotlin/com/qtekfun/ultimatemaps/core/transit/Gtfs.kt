package com.qtekfun.ultimatemaps.core.transit

import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipFile

/** Where the GTFS text files come from (a directory or a zip). */
interface GtfsSource {
    /** Opens `name` (for example "stops.txt"), or null if the feed does not contain it. */
    fun open(name: String): InputStream?
}

class DirGtfsSource(private val dir: File) : GtfsSource {
    override fun open(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.inputStream()
}

class ZipGtfsSource(private val zip: File) : GtfsSource {
    override fun open(name: String): InputStream? {
        val z = ZipFile(zip)
        val e = z.getEntry(name)
        if (e == null) {
            z.close()
            return null
        }
        // Closing the stream closes the zip file too.
        return object : java.io.FilterInputStream(z.getInputStream(e)) {
            override fun close() {
                super.close()
                z.close()
            }
        }
    }
}

/** Calendar of one GTFS service. Days are epoch days (days since 1970-01-01). */
class ServiceDef(
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val mask: Int,
    val startDay: Int,
    val endDay: Int,
    /** Sorted epoch days with an exception_type 1 (service added). */
    val added: IntArray,
    /** Sorted epoch days with an exception_type 2 (service removed). */
    val removed: IntArray,
) {
    fun isActive(day: Int): Boolean {
        if (removed.binarySearch(day) >= 0) return false
        if (added.binarySearch(day) >= 0) return true
        if (day < startDay || day > endDay) return false
        return (mask shr Math.floorMod(day + 3, 7)) and 1 != 0
    }
}

/** Options applied while reading one feed. */
class GtfsReadOptions(
    /** Keeps only stops for which this returns true (lat, lon); trips are cut to the kept stops. */
    val stopFilter: ((Double, Double) -> Boolean)? = null,
    /**
     * Treat every calendar as valid on any date (weekday pattern only, calendar_dates still honoured).
     * Spike shortcut for feeds whose published validity has already expired.
     */
    val ignoreCalendarRange: Boolean = false,
)

/** Compact in-memory GTFS feed (only the fields a planner needs). */
class GtfsFeed(
    val stopIds: Array<String>,
    val stopNames: Array<String>,
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    /** Parent station id, or "" when the stop has none. */
    val stopParents: Array<String>,
    val stopLocationType: IntArray,
    val routeIds: Array<String>,
    val routeShort: Array<String>,
    val routeLong: Array<String>,
    val routeType: IntArray,
    /** 0xRRGGBB or -1 when absent. */
    val routeColor: IntArray,
    val routeTextColor: IntArray,
    val serviceIds: Array<String>,
    val services: Array<ServiceDef>,
    val tripIds: Array<String>,
    val tripRoute: IntArray,
    val tripService: IntArray,
    val tripHeadsign: Array<String>,
    /** Trip i owns stop times [tripStopStart[i], tripStopStart[i+1]). Sorted by stop_sequence. */
    val tripStopStart: IntArray,
    val stTimeStop: IntArray,
    val stTimeArr: IntArray,
    val stTimeDep: IntArray,
    val freqTrip: IntArray,
    val freqStart: IntArray,
    val freqEnd: IntArray,
    val freqHeadway: IntArray,
    val transferFrom: Array<String>,
    val transferTo: Array<String>,
    /** 0 recommended, 1 timed, 2 minimum time, 3 not possible. */
    val transferType: IntArray,
    val transferMinSec: IntArray,
    val feedVersion: String,
    val feedStartDay: Int,
    var feedEndDay: Int,
)

object GtfsReader {
    fun read(source: GtfsSource, options: GtfsReadOptions = GtfsReadOptions()): GtfsFeed {
        // ---- stops
        val stopIndex = HashMap<String, Int>()
        val stopIds = ArrayList<String>()
        val stopNames = ArrayList<String>()
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val parents = ArrayList<String>()
        val locTypes = IntList()
        val keptStop = ArrayList<Boolean>()
        source.open("stops.txt")!!.use { ins ->
            CsvTable(ins).use { t ->
                val cId = t.col("stop_id")
                val cName = t.col("stop_name")
                val cLat = t.col("stop_lat")
                val cLon = t.col("stop_lon")
                val cPar = t.col("parent_station")
                val cLoc = t.col("location_type")
                while (t.next()) {
                    val id = t.str(cId)
                    val la = t.double(cLat)
                    val lo = t.double(cLon)
                    stopIndex[id] = stopIds.size
                    stopIds.add(id)
                    stopNames.add(t.str(cName))
                    lat.add(la)
                    lon.add(lo)
                    parents.add(t.str(cPar))
                    locTypes.add(t.int(cLoc))
                    keptStop.add(!la.isNaN() && !lo.isNaN() && (options.stopFilter?.invoke(la, lo) ?: true))
                }
            }
        }

        // ---- routes
        val routeIndex = HashMap<String, Int>()
        val routeIds = ArrayList<String>()
        val routeShort = ArrayList<String>()
        val routeLong = ArrayList<String>()
        val routeType = IntList()
        val routeColor = IntList()
        val routeText = IntList()
        source.open("routes.txt")!!.use { ins ->
            CsvTable(ins).use { t ->
                val cId = t.col("route_id")
                val cS = t.col("route_short_name")
                val cL = t.col("route_long_name")
                val cT = t.col("route_type")
                val cC = t.col("route_color")
                val cTC = t.col("route_text_color")
                while (t.next()) {
                    routeIndex[t.str(cId)] = routeIds.size
                    routeIds.add(t.str(cId))
                    routeShort.add(t.str(cS))
                    routeLong.add(t.str(cL))
                    routeType.add(t.int(cT, 3))
                    routeColor.add(parseColor(t.str(cC)))
                    routeText.add(parseColor(t.str(cTC)))
                }
            }
        }

        // ---- calendars
        val serviceIndex = HashMap<String, Int>()
        val serviceIds = ArrayList<String>()
        val masks = IntList()
        val starts = IntList()
        val endsL = IntList()
        val addedLists = ArrayList<IntList>()
        val removedLists = ArrayList<IntList>()
        fun serviceOf(id: String): Int = serviceIndex.getOrPut(id) {
            serviceIds.add(id)
            masks.add(0)
            starts.add(Int.MAX_VALUE)
            endsL.add(Int.MIN_VALUE)
            addedLists.add(IntList(4))
            removedLists.add(IntList(4))
            serviceIds.size - 1
        }
        val cache = HashMap<Int, Int>()
        fun day(yyyymmdd: Int): Int = cache.getOrPut(yyyymmdd) {
            LocalDate.of(yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100).toEpochDay().toInt()
        }
        source.open("calendar.txt")?.use { ins ->
            CsvTable(ins).use { t ->
                val cId = t.col("service_id")
                val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map { t.col(it) }
                val cS = t.col("start_date")
                val cE = t.col("end_date")
                while (t.next()) {
                    val si = serviceOf(t.str(cId))
                    var m = 0
                    for (d in 0 until 7) if (t.int(days[d]) == 1) m = m or (1 shl d)
                    masks.data[si] = m
                    starts.data[si] = day(t.int(cS))
                    endsL.data[si] = day(t.int(cE))
                }
            }
        }
        source.open("calendar_dates.txt")?.use { ins ->
            CsvTable(ins).use { t ->
                val cId = t.col("service_id")
                val cD = t.col("date")
                val cT = t.col("exception_type")
                while (t.next()) {
                    val si = serviceOf(t.str(cId))
                    val d = day(t.int(cD))
                    if (t.int(cT) == 1) addedLists[si].add(d) else removedLists[si].add(d)
                }
            }
        }
        val services = Array(serviceIds.size) { i ->
            ServiceDef(
                mask = masks[i],
                startDay = if (options.ignoreCalendarRange) Int.MIN_VALUE else starts[i],
                endDay = if (options.ignoreCalendarRange) Int.MAX_VALUE else endsL[i],
                added = addedLists[i].toArray().also { it.sort() },
                removed = removedLists[i].toArray().also { it.sort() },
            )
        }

        // ---- trips
        val tripIndex = HashMap<String, Int>()
        val tripIds = ArrayList<String>()
        val tripRoute = IntList()
        val tripService = IntList()
        val tripHead = ArrayList<String>()
        source.open("trips.txt")!!.use { ins ->
            CsvTable(ins).use { t ->
                val cR = t.col("route_id")
                val cS = t.col("service_id")
                val cT = t.col("trip_id")
                val cH = t.col("trip_headsign")
                while (t.next()) {
                    val r = routeIndex[t.str(cR)] ?: continue
                    val s = serviceIndex[t.str(cS)] ?: continue
                    tripIndex[t.str(cT)] = tripIds.size
                    tripIds.add(t.str(cT))
                    tripRoute.add(r)
                    tripService.add(s)
                    tripHead.add(t.str(cH))
                }
            }
        }

        // ---- stop times (rows may be unsorted: collect, then counting-sort by trip)
        val rowTrip = IntList(1 shl 16)
        val rowSeq = IntList(1 shl 16)
        val rowStop = IntList(1 shl 16)
        val rowArr = IntList(1 shl 16)
        val rowDep = IntList(1 shl 16)
        source.open("stop_times.txt")!!.use { ins ->
            CsvTable(ins).use { t ->
                val cT = t.col("trip_id")
                val cA = t.col("arrival_time")
                val cD = t.col("departure_time")
                val cS = t.col("stop_id")
                val cQ = t.col("stop_sequence")
                var lastTripKey = ""
                var lastTrip = -1
                while (t.next()) {
                    val tk = t.str(cT)
                    val trip = if (tk == lastTripKey) lastTrip else (tripIndex[tk] ?: -1).also {
                        lastTripKey = tk
                        lastTrip = it
                    }
                    if (trip < 0) continue
                    val stop = stopIndex[t.str(cS)] ?: continue
                    if (!keptStop[stop]) continue
                    rowTrip.add(trip)
                    rowSeq.add(t.int(cQ))
                    rowStop.add(stop)
                    rowArr.add(t.timeSec(cA))
                    rowDep.add(t.timeSec(cD))
                }
            }
        }
        val nTrips = tripIds.size
        val nRows = rowTrip.size
        val tripStart = IntArray(nTrips + 1)
        for (i in 0 until nRows) tripStart[rowTrip[i] + 1]++
        for (i in 0 until nTrips) tripStart[i + 1] += tripStart[i]
        val fill = tripStart.copyOf(nTrips)
        val order = IntArray(nRows)
        for (i in 0 until nRows) order[fill[rowTrip[i]]++] = i
        val stStop = IntArray(nRows)
        val stArr = IntArray(nRows)
        val stDep = IntArray(nRows)
        for (trip in 0 until nTrips) {
            val a = tripStart[trip]
            val b = tripStart[trip + 1]
            if (b - a > 1) {
                var sorted = true
                for (k in a + 1 until b) if (rowSeq[order[k]] < rowSeq[order[k - 1]]) {
                    sorted = false
                    break
                }
                if (!sorted) {
                    val seg = order.copyOfRange(a, b).toTypedArray()
                    seg.sortBy { rowSeq[it] }
                    for (k in seg.indices) order[a + k] = seg[k]
                }
            }
            for (k in a until b) {
                val r = order[k]
                stStop[k] = rowStop[r]
                stArr[k] = rowArr[r]
                stDep[k] = rowDep[r]
            }
            interpolate(stArr, stDep, a, b)
        }

        // ---- frequencies
        val fTrip = IntList()
        val fStart = IntList()
        val fEnd = IntList()
        val fHead = IntList()
        source.open("frequencies.txt")?.use { ins ->
            CsvTable(ins).use { t ->
                val cT = t.col("trip_id")
                val cS = t.col("start_time")
                val cE = t.col("end_time")
                val cH = t.col("headway_secs")
                var lastKey = ""
                var lastTrip = -1
                while (t.next()) {
                    val tk = t.str(cT)
                    val trip = if (tk == lastKey) lastTrip else (tripIndex[tk] ?: -1).also {
                        lastKey = tk
                        lastTrip = it
                    }
                    val h = t.int(cH)
                    if (trip < 0 || h <= 0) continue
                    fTrip.add(trip)
                    fStart.add(t.timeSec(cS))
                    fEnd.add(t.timeSec(cE))
                    fHead.add(h)
                }
            }
        }

        // ---- transfers
        val trFrom = ArrayList<String>()
        val trTo = ArrayList<String>()
        val trType = IntList()
        val trMin = IntList()
        source.open("transfers.txt")?.use { ins ->
            CsvTable(ins).use { t ->
                val cF = t.col("from_stop_id")
                val cT = t.col("to_stop_id")
                val cY = t.col("transfer_type")
                val cM = t.col("min_transfer_time")
                while (t.next()) {
                    trFrom.add(t.str(cF))
                    trTo.add(t.str(cT))
                    trType.add(t.int(cY))
                    trMin.add(t.int(cM, -1))
                }
            }
        }

        // ---- feed info
        var version = ""
        var fs = Int.MIN_VALUE
        var fe = Int.MAX_VALUE
        source.open("feed_info.txt")?.use { ins ->
            CsvTable(ins).use { t ->
                val cV = t.col("feed_version")
                val cS = t.col("feed_start_date")
                val cE = t.col("feed_end_date")
                if (t.next()) {
                    version = t.str(cV)
                    if (t.int(cS) > 0) fs = day(t.int(cS))
                    if (t.int(cE) > 0) fe = day(t.int(cE))
                }
            }
        }

        return GtfsFeed(
            stopIds = stopIds.toTypedArray(),
            stopNames = stopNames.toTypedArray(),
            stopLat = lat.toDoubleArray(),
            stopLon = lon.toDoubleArray(),
            stopParents = parents.toTypedArray(),
            stopLocationType = locTypes.toArray(),
            routeIds = routeIds.toTypedArray(),
            routeShort = routeShort.toTypedArray(),
            routeLong = routeLong.toTypedArray(),
            routeType = routeType.toArray(),
            routeColor = routeColor.toArray(),
            routeTextColor = routeText.toArray(),
            serviceIds = serviceIds.toTypedArray(),
            services = services,
            tripIds = tripIds.toTypedArray(),
            tripRoute = tripRoute.toArray(),
            tripService = tripService.toArray(),
            tripHeadsign = tripHead.toTypedArray(),
            tripStopStart = tripStart,
            stTimeStop = stStop,
            stTimeArr = stArr,
            stTimeDep = stDep,
            freqTrip = fTrip.toArray(),
            freqStart = fStart.toArray(),
            freqEnd = fEnd.toArray(),
            freqHeadway = fHead.toArray(),
            transferFrom = trFrom.toTypedArray(),
            transferTo = trTo.toTypedArray(),
            transferType = trType.toArray(),
            transferMinSec = trMin.toArray(),
            feedVersion = version,
            feedStartDay = fs,
            feedEndDay = fe,
        )
    }

    private fun parseColor(s: String): Int {
        if (s.length != 6) return -1
        return s.toIntOrNull(16) ?: -1
    }

    /**
     * Fills missing arrival/departure times (GTFS allows blanks at non-timepoint stops) by linear
     * interpolation over the stop index, and makes the times non-decreasing.
     */
    private fun interpolate(arr: IntArray, dep: IntArray, a: Int, b: Int) {
        for (k in a until b) {
            if (arr[k] < 0 && dep[k] >= 0) arr[k] = dep[k]
            if (dep[k] < 0 && arr[k] >= 0) dep[k] = arr[k]
        }
        var k = a
        while (k < b) {
            if (dep[k] >= 0) {
                k++
                continue
            }
            var prev = k - 1
            var next = k
            while (next < b && dep[next] < 0) next++
            if (prev < a || next >= b) {
                // Cannot interpolate at the ends: copy the nearest known time.
                val v = if (prev >= a) dep[prev] else if (next < b) arr[next] else 0
                for (j in k until next) {
                    arr[j] = v
                    dep[j] = v
                }
            } else {
                val t0 = dep[prev]
                val t1 = arr[next]
                val span = next - prev
                for (j in k until next) {
                    val v = t0 + (t1 - t0) * (j - prev) / span
                    arr[j] = v
                    dep[j] = v
                }
            }
            k = next
        }
        for (j in a until b) {
            if (j > a && arr[j] < dep[j - 1]) arr[j] = dep[j - 1]
            if (dep[j] < arr[j]) dep[j] = arr[j]
        }
    }
}
