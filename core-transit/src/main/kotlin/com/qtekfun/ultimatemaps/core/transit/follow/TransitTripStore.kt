package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The trip being followed, as saved: the itinerary, how far along it the traveller was, and the city's time zone. */
data class PersistedTransitTrip(val itinerary: Itinerary, val snapshot: FollowerSnapshot, val zoneId: String, val savedAtMillis: Long)

/**
 * Saves the transit trip in progress so it can be resumed after the system kills the process. Same rules as the car
 * navigation's `NavStateStore`: one small private file, written atomically (temporary file, `fsync`, rename), never trusted
 * when read (corrupt, truncated, foreign, inconsistent or older than [maxAgeMillis]: deleted and reported as "nothing to
 * resume"). The file holds stops and the progress along them, no position of the traveller. Nothing is ever logged.
 */
class TransitTripStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
    /** Returns false (and keeps the old file) when the disk write failed. */
    fun save(itinerary: Itinerary, snapshot: FollowerSnapshot, zoneId: String): Boolean = try {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(BufferedOutputStream(fos, 1 shl 14))
            out.writeInt(MAGIC)
            out.writeByte(FORMAT)
            out.writeLong(clock())
            out.writeUTF(zoneId)
            out.writeInt(snapshot.legIndex)
            out.writeBoolean(snapshot.boarded)
            out.writeDouble(snapshot.progress)
            out.writeBoolean(snapshot.delaySec != null)
            out.writeInt(snapshot.delaySec ?: 0)
            writeItinerary(out, itinerary)
            out.flush()
            fos.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun load(): PersistedTransitTrip? {
        if (!file.isFile) return null
        val loaded = try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 14)).use { input ->
                if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT) throw IOException("not a transit trip file")
                val savedAt = input.readLong()
                val zone = input.readUTF()
                val leg = input.readInt()
                val boarded = input.readBoolean()
                val progress = input.readDouble()
                val hasDelay = input.readBoolean()
                val delay = input.readInt()
                val itinerary = readItinerary(input)
                PersistedTransitTrip(itinerary, FollowerSnapshot(leg, boarded, progress, delay.takeIf { hasDelay }), zone, savedAt)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        val age = clock() - (loaded?.savedAtMillis ?: 0L)
        val valid = loaded != null && loaded.snapshot.legIndex in 0..loaded.itinerary.legs.size && loaded.snapshot.progress.isFinite() &&
            loaded.snapshot.progress >= 0.0 && age in -CLOCK_SLACK_MILLIS..maxAgeMillis
        if (!valid) {
            clear()
            return null
        }
        return loaded
    }

    fun clear() {
        file.delete()
        File(file.absolutePath + ".tmp").delete()
    }

    private fun writeItinerary(out: DataOutputStream, it: Itinerary) {
        out.writeInt(it.legs.size)
        for (leg in it.legs) {
            when (leg) {
                is ItineraryLeg.Walk -> {
                    out.writeByte(WALK)
                    writeNullable(out, leg.fromName)
                    writeNullable(out, leg.toName)
                    writePoint(out, leg.from)
                    writePoint(out, leg.to)
                    out.writeInt(leg.meters)
                    out.writeLong(leg.departAt)
                    out.writeLong(leg.arriveAt)
                }
                is ItineraryLeg.Ride -> {
                    out.writeByte(RIDE)
                    out.writeUTF(leg.line.shortName)
                    out.writeUTF(leg.line.longName)
                    out.writeInt(leg.line.color)
                    out.writeInt(leg.line.textColor)
                    out.writeInt(leg.line.routeType)
                    out.writeUTF(leg.headsign)
                    out.writeInt(leg.stops.size)
                    for (s in leg.stops) {
                        out.writeUTF(s.name)
                        writePoint(out, s.point)
                        out.writeLong(s.arriveAt)
                        out.writeLong(s.departAt)
                    }
                }
            }
        }
    }

    private fun readItinerary(input: DataInputStream): Itinerary {
        val n = input.readInt()
        if (n !in 1..MAX_LEGS) throw IOException("bad leg count")
        val legs = ArrayList<ItineraryLeg>(n)
        repeat(n) {
            when (input.readUnsignedByte()) {
                WALK -> legs.add(
                    ItineraryLeg.Walk(
                        readNullable(input), readNullable(input), readPoint(input), readPoint(input),
                        input.readInt(), input.readLong(), input.readLong(),
                    ),
                )
                RIDE -> {
                    val line = LineInfo(input.readUTF(), input.readUTF(), input.readInt(), input.readInt(), input.readInt())
                    val headsign = input.readUTF()
                    val count = input.readInt()
                    if (count !in 2..MAX_STOPS) throw IOException("bad stop count")
                    val stops = List(count) { ItineraryStop(input.readUTF(), readPoint(input), input.readLong(), input.readLong()) }
                    legs.add(ItineraryLeg.Ride(line, headsign, stops))
                }
                else -> throw IOException("bad leg type")
            }
        }
        return Itinerary(legs)
    }

    private fun writePoint(out: DataOutputStream, p: LatLon) {
        out.writeDouble(p.lat)
        out.writeDouble(p.lon)
    }

    private fun readPoint(input: DataInputStream): LatLon =
        LatLon.ofOrNull(input.readDouble(), input.readDouble()) ?: throw IOException("bad coordinate")

    private fun writeNullable(out: DataOutputStream, s: String?) {
        out.writeBoolean(s != null)
        if (s != null) out.writeUTF(s)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    companion object {
        private const val MAGIC = 0x554D5452 // "UMTR"
        private const val FORMAT = 1
        private const val WALK = 0
        private const val RIDE = 1
        private const val MAX_LEGS = 64
        private const val MAX_STOPS = 2000
        private const val CLOCK_SLACK_MILLIS = 60_000L

        /** A trip is not resumed after this long: the traveller is surely somewhere else by then (same as the car navigation). */
        const val DEFAULT_MAX_AGE_MILLIS = 3 * 60 * 60 * 1000L
    }
}
