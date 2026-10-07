package com.qtekfun.ultimatemaps.core.data.record

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.io.TrackPoint
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream

/** What a journal holds: when the recording began and its points, one list per segment. */
class RecordedJournal(val startMillis: Long, val segments: List<List<TrackPoint>>) {
    val pointCount: Int get() = segments.sumOf { it.size }
}

/**
 * Crash-safe storage of a recording in progress. Points are appended one by one, so a killed process loses at most
 * the point being written; the journal is turned into a stored track by [TrackRecorder] and then deleted.
 */
interface TrackJournal {
    /** Starts a new journal, discarding any previous one. */
    fun begin(startMillis: Long)

    fun append(point: TrackPoint)

    /** The next point starts a new segment (a pause, or a gap in the signal). */
    fun breakSegment()

    /** The journal as stored, or null when there is none or it has no valid header. A damaged line is skipped. */
    fun read(): RecordedJournal?

    /** Closes the writer and removes the journal. */
    fun delete()

    /** Closes the writer, keeping the stored data. */
    fun close()
}

/**
 * [TrackJournal] in one private text file: `V1 <startMillis>` then one line per point `P <lat> <lon> <timeMillis>` or
 * `S` for a segment break. Every append is flushed to the operating system (it survives the process dying, not
 * necessarily a power cut; no `fsync` per point, to spare the battery). A truncated last line is ignored on read.
 * Never logged. Callers serialise access (the recorder is synchronised).
 */
class FileTrackJournal(private val file: File) : TrackJournal {
    private var writer: BufferedWriter? = null

    override fun begin(startMillis: Long) {
        close()
        file.parentFile?.mkdirs()
        val w = FileOutputStream(file, false).bufferedWriter(Charsets.UTF_8)
        w.write("V1 $startMillis\n")
        w.flush()
        writer = w
    }

    private fun out(): BufferedWriter = writer ?: FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).also { writer = it }

    override fun append(point: TrackPoint) {
        val w = out()
        w.write("P ${point.point.lat} ${point.point.lon} ${point.timeMillis ?: 0L}\n")
        w.flush()
    }

    override fun breakSegment() {
        val w = out()
        w.write("S\n")
        w.flush()
    }

    override fun read(): RecordedJournal? {
        if (!file.isFile) return null
        var start: Long? = null
        val segments = ArrayList<MutableList<TrackPoint>>()
        var current: MutableList<TrackPoint>? = null
        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val parts = line.split(' ')
                    when {
                        parts.size == 2 && parts[0] == "V1" && start == null -> start = parts[1].toLongOrNull()
                        start == null -> return null
                        parts.size == 1 && parts[0] == "S" -> current = null
                        parts.size == 4 && parts[0] == "P" -> {
                            val p = LatLon.ofOrNull(parts[1].toDoubleOrNull() ?: continue, parts[2].toDoubleOrNull() ?: continue) ?: continue
                            val t = parts[3].toLongOrNull() ?: continue
                            val seg = current ?: ArrayList<TrackPoint>().also { segments += it; current = it }
                            seg += TrackPoint(p, null, t.takeIf { it > 0 })
                        }
                    }
                }
            }
        } catch (_: java.io.IOException) {
            return null
        }
        return start?.let { RecordedJournal(it, segments) }
    }

    override fun delete() {
        close()
        file.delete()
    }

    override fun close() {
        try {
            writer?.close()
        } catch (_: java.io.IOException) {
        }
        writer = null
    }
}
