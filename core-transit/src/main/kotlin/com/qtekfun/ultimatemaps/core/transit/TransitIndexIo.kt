package com.qtekfun.ultimatemaps.core.transit

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Binary file format of [TransitIndex] ("UMTI", version 2). Big-endian, varint (LEB128) integers.
 *
 * ```
 * magic "UMTI", u8 version
 * sources:   n, then [label, version, calStart(zz), calEnd(zz), attribution, ignoredFlag]
 * headsigns: n, then UTF strings
 * stops:     n, then [name, lat(i32), lon(i32), group+1]
 * lines:     n, then [short, long, color+1, textColor+1, type]
 * services:  n, then [mask, start(zz), end(zz), nAdded, added(delta), nRemoved, removed(delta)]
 * patterns:  n, then [line, nStops, stops..., nTrips, nSched, nProfiles,
 *                     profiles: per profile 2*nStops varints (run time from previous departure, dwell),
 *                     per trip: service, headsign, profile, firstArrival, headway, runs; frequency trips last, see nSched]
 * transfers: n, then [from, to, type, min+1]
 * optional (a file may end after the transfers): flags (1 = stop ids, 2 = trip ids), then one UTF string per stop,
 *           then one per trip in index order (the feed's own ids, "" when not kept; see FeedOptions.keepIds)
 * ```
 *
 * Trips of one pattern are mostly the same shape shifted in time (and exactly so for frequency-expanded trips), so
 * the file stores each distinct *time profile* once and then one `(service, headsign, profile, departure)` tuple per
 * trip. The reader expands them back into the flat arrays the planner scans.
 */
object TransitIndexIo {
    private const val VERSION = 2

    fun write(index: TransitIndex, out: OutputStream) {
        val w = Writer(DataOutputStream(out.buffered(1 shl 16)))
        w.out.writeBytes("UMTI")
        w.out.writeByte(VERSION)
        w.varint(index.sources.size)
        for (s in index.sources) {
            w.utf(s.label)
            w.utf(s.version)
            w.zigzag(clampDay(s.calendarStartDay))
            w.zigzag(clampDay(s.calendarEndDay))
            w.utf(s.attribution)
            w.varint(if (s.calendarRangeIgnored) 1 else 0)
        }
        w.varint(index.headsigns.size)
        for (h in index.headsigns) w.utf(h)
        w.varint(index.stopCount)
        for (i in 0 until index.stopCount) {
            w.utf(index.stopName[i])
            w.out.writeInt(index.stopLat[i])
            w.out.writeInt(index.stopLon[i])
            w.varint(index.stopGroup[i] + 1)
        }
        w.varint(index.lineCount)
        for (i in 0 until index.lineCount) {
            w.utf(index.lineShortName[i])
            w.utf(index.lineLongName[i])
            w.varint(index.lineColor[i] + 1)
            w.varint(index.lineTextColor[i] + 1)
            w.varint(index.lineType[i])
        }
        w.varint(index.serviceMask.size)
        for (i in index.serviceMask.indices) {
            w.varint(index.serviceMask[i])
            w.zigzag(clampDay(index.serviceStart[i]))
            w.zigzag(clampDay(index.serviceEnd[i]))
            w.deltas(index.serviceAdded[i])
            w.deltas(index.serviceRemoved[i])
        }
        w.varint(index.patternCount)
        for (p in 0 until index.patternCount) {
            val n = index.patternStopCount(p)
            w.varint(index.patternLine[p])
            w.varint(n)
            for (i in 0 until n) w.varint(index.patternStops[index.patternStopOffset[p] + i])
            val nt = index.patternTrips(p)
            w.varint(nt)
            w.varint(index.patternFreqStart[p] - index.patternTripOffset[p])
            val profiles = LinkedHashMap<ProfileKey, Int>()
            val tripProfile = IntArray(nt)
            for (t in 0 until nt) {
                val base = index.patternTimeBase[p] + t * n
                val prof = IntArray(2 * n)
                var prev = index.arrivals[base]
                for (i in 0 until n) {
                    prof[2 * i] = index.arrivals[base + i] - prev
                    prof[2 * i + 1] = index.departures[base + i] - index.arrivals[base + i]
                    prev = index.departures[base + i]
                }
                tripProfile[t] = profiles.getOrPut(ProfileKey(prof)) { profiles.size }
            }
            w.varint(profiles.size)
            for (k in profiles.keys) for (v in k.values) w.varint(v)
            for (t in 0 until nt) {
                val ti = index.patternTripOffset[p] + t
                w.varint(index.tripService[ti])
                w.varint(index.tripHeadsign[ti])
                w.varint(tripProfile[t])
                w.varint(index.arrivals[index.patternTimeBase[p] + t * n])
                w.varint(index.tripHeadway[ti])
                w.varint(index.tripRuns[ti])
            }
        }
        w.varint(index.transferFrom.size)
        for (i in index.transferFrom.indices) {
            w.varint(index.transferFrom[i])
            w.varint(index.transferTo[i])
            w.varint(index.transferType[i])
            w.varint(index.transferMinSec[i] + 1)
        }
        // Optional trailing section (older files simply end after the transfers): feed ids for real-time matching.
        val stopIds = index.stopExtId
        val tripIds = index.tripExtId
        if (stopIds != null || tripIds != null) {
            w.varint((if (stopIds != null) 1 else 0) or (if (tripIds != null) 2 else 0))
            stopIds?.forEach { w.utf(it) }
            tripIds?.forEach { w.utf(it) }
        }
        w.out.flush()
    }

    fun read(input: InputStream): TransitIndex {
        val r = Reader(DataInputStream(input.buffered(1 shl 16)))
        val magic = ByteArray(4)
        r.inp.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == "UMTI") { "Not a transit index" }
        require(r.inp.readUnsignedByte() == VERSION) { "Unsupported transit index version" }
        val sources = List(r.varint()) {
            FeedSource(r.utf(), r.utf(), unclampDay(r.zigzag()), unclampDay(r.zigzag()), r.utf(), r.varint() == 1)
        }
        val headsigns = Array(r.varint()) { r.utf() }
        val nStops = r.varint()
        val names = arrayOfNulls<String>(nStops)
        val lat = IntArray(nStops)
        val lon = IntArray(nStops)
        val grp = IntArray(nStops)
        for (i in 0 until nStops) {
            names[i] = r.utf()
            lat[i] = r.inp.readInt()
            lon[i] = r.inp.readInt()
            grp[i] = r.varint() - 1
        }
        val nLines = r.varint()
        val ls = Array(nLines) { "" }
        val ll = Array(nLines) { "" }
        val lc = IntArray(nLines)
        val lt = IntArray(nLines)
        val lty = IntArray(nLines)
        for (i in 0 until nLines) {
            ls[i] = r.utf()
            ll[i] = r.utf()
            lc[i] = r.varint() - 1
            lt[i] = r.varint() - 1
            lty[i] = r.varint()
        }
        val nSvc = r.varint()
        val sm = IntArray(nSvc)
        val ss = IntArray(nSvc)
        val se = IntArray(nSvc)
        val sa = Array(nSvc) { IntArray(0) }
        val sr = Array(nSvc) { IntArray(0) }
        for (i in 0 until nSvc) {
            sm[i] = r.varint()
            ss[i] = unclampDay(r.zigzag())
            se[i] = unclampDay(r.zigzag())
            sa[i] = r.deltas()
            sr[i] = r.deltas()
        }
        val nP = r.varint()
        val pLine = IntArray(nP)
        val pStopOff = IntArray(nP + 1)
        val pTripOff = IntArray(nP + 1)
        val pTimeBase = IntArray(nP)
        val stopsList = IntList(1 shl 16)
        val tSvc = IntList(1 shl 16)
        val tHead = IntList(1 shl 16)
        val tHeadway = IntList(1 shl 16)
        val tRuns = IntList(1 shl 16)
        val pFreqStart = IntArray(nP)
        var arrivals = IntArray(1 shl 20)
        var departures = IntArray(1 shl 20)
        var timeFill = 0
        for (p in 0 until nP) {
            pLine[p] = r.varint()
            val n = r.varint()
            pStopOff[p] = stopsList.size
            for (i in 0 until n) stopsList.add(r.varint())
            val nt = r.varint()
            pTripOff[p] = tSvc.size
            pFreqStart[p] = tSvc.size + r.varint()
            pTimeBase[p] = timeFill
            val nProf = r.varint()
            val profiles = Array(nProf) { IntArray(2 * n) { r.varint() } }
            val need = timeFill + nt * n
            if (need > arrivals.size) {
                val cap = maxOf(need, arrivals.size * 2)
                arrivals = arrivals.copyOf(cap)
                departures = departures.copyOf(cap)
            }
            for (t in 0 until nt) {
                tSvc.add(r.varint())
                tHead.add(r.varint())
                val prof = profiles[r.varint()]
                var prev = r.varint()
                tHeadway.add(r.varint())
                tRuns.add(r.varint())
                val base = timeFill + t * n
                for (i in 0 until n) {
                    val a = prev + prof[2 * i]
                    val d = a + prof[2 * i + 1]
                    arrivals[base + i] = a
                    departures[base + i] = d
                    prev = d
                }
            }
            timeFill = need
        }
        pStopOff[nP] = stopsList.size
        pTripOff[nP] = tSvc.size
        val nTr = r.varint()
        val tf = IntArray(nTr)
        val tt = IntArray(nTr)
        val ty = IntArray(nTr)
        val tm = IntArray(nTr)
        for (i in 0 until nTr) {
            tf[i] = r.varint()
            tt[i] = r.varint()
            ty[i] = r.varint()
            tm[i] = r.varint() - 1
        }
        // Optional trailing section; its absence (end of file) is a valid older file.
        val flags = r.varintOrEnd()
        val stopExt = if (flags != null && flags and 1 != 0) Array(nStops) { r.utf() } else null
        val tripExt = if (flags != null && flags and 2 != 0) Array(tSvc.size) { r.utf() } else null
        return TransitIndex(
            sources = sources,
            stopLat = lat,
            stopLon = lon,
            stopName = Array(nStops) { names[it]!! },
            stopGroup = grp,
            lineShortName = ls,
            lineLongName = ll,
            lineColor = lc,
            lineTextColor = lt,
            lineType = lty,
            serviceMask = sm,
            serviceStart = ss,
            serviceEnd = se,
            serviceAdded = sa,
            serviceRemoved = sr,
            patternLine = pLine,
            patternStopOffset = pStopOff,
            patternStops = stopsList.toArray(),
            patternTripOffset = pTripOff,
            patternTimeBase = pTimeBase,
            patternFreqStart = pFreqStart,
            tripService = tSvc.toArray(),
            tripHeadway = tHeadway.toArray(),
            tripRuns = tRuns.toArray(),
            tripHeadsign = tHead.toArray(),
            headsigns = headsigns,
            arrivals = arrivals.copyOf(timeFill),
            departures = departures.copyOf(timeFill),
            transferFrom = tf,
            transferTo = tt,
            transferType = ty,
            transferMinSec = tm,
            stopExtId = stopExt,
            tripExtId = tripExt,
        )
    }

    // Int.MIN_VALUE / Int.MAX_VALUE mean "unbounded"; map them to small sentinels so varints stay short.
    private const val NO_START = -1
    private const val NO_END = -2
    private fun clampDay(d: Int): Int = when (d) {
        Int.MIN_VALUE -> NO_START
        Int.MAX_VALUE -> NO_END
        else -> d
    }

    private fun unclampDay(d: Int): Int = when (d) {
        NO_START -> Int.MIN_VALUE
        NO_END -> Int.MAX_VALUE
        else -> d
    }

    private class ProfileKey(val values: IntArray) {
        private val hash = values.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is ProfileKey && other.values.contentEquals(values)
    }

    private class Writer(val out: DataOutputStream) {
        fun varint(v: Int) {
            require(v >= 0) { "negative varint $v" }
            var x = v
            while (x >= 0x80) {
                out.writeByte((x and 0x7F) or 0x80)
                x = x ushr 7
            }
            out.writeByte(x)
        }

        fun zigzag(v: Int) = varint((v shl 1) xor (v shr 31))
        fun utf(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            varint(b.size)
            out.write(b)
        }

        fun deltas(a: IntArray) {
            varint(a.size)
            var prev = 0
            for (v in a) {
                zigzag(v - prev)
                prev = v
            }
        }
    }

    private class Reader(val inp: DataInputStream) {
        fun varint(): Int {
            var shift = 0
            var result = 0
            while (true) {
                val b = inp.readUnsignedByte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Like [varint] but null when the stream is already at its end. */
        fun varintOrEnd(): Int? {
            val first = inp.read()
            if (first < 0) return null
            if (first and 0x80 == 0) return first
            var result = first and 0x7F
            var shift = 7
            while (true) {
                val b = inp.readUnsignedByte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun zigzag(): Int {
            val v = varint()
            return (v ushr 1) xor -(v and 1)
        }

        fun utf(): String {
            val n = varint()
            val b = ByteArray(n)
            inp.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        fun deltas(): IntArray {
            val n = varint()
            var prev = 0
            return IntArray(n) {
                prev += zigzag()
                prev
            }
        }
    }
}
