package com.qtekfun.mapas.core.data.record

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.distanceTo
import com.qtekfun.mapas.core.geo.io.PathKind
import com.qtekfun.mapas.core.geo.io.TrackPoint

/**
 * When to keep a fix. These are design choices (a balance between detail and size), not measured values: they
 * are collected here so they can be tuned in one place.
 */
data class RecordingConfig(
    /** Fixes less accurate than this are ignored. */
    val maxAccuracyMeters: Float = 50f,
    /** A point is kept once the user is `speed * distanceSeconds` from the last one, clamped to the two limits below. */
    val distanceSeconds: Double = 3.0,
    /** Walking pace: the closest two kept points may be. */
    val minDistanceMeters: Double = 5.0,
    /** Motorway speed: the farthest apart two kept points may be. */
    val maxDistanceMeters: Double = 50.0,
    /** Fixes closer in time than this to the last kept point are ignored (bursts, two feeds of the same fix). */
    val minIntervalMillis: Long = 2_000L,
    /** Staying within [stopRadiusMeters] of one spot for this long pauses the recording (no points while stopped). */
    val pauseAfterMillis: Long = 5 * 60_000L,
    val stopRadiusMeters: Double = 25.0,
    /** No kept point for this long (signal lost, app in the background) starts a new segment instead of a straight line. */
    val segmentGapMillis: Long = 10 * 60_000L,
    /** A recording with fewer kept points than this is not stored (a tap on Start and Stop is not a track). */
    val minPoints: Int = 2,
)

enum class RecordingStatus { IDLE, RECORDING, PAUSED }

/** What the UI shows. [distanceMeters] is the length of the kept points, [startedMillis] is epoch millis. */
data class RecordingState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val points: Int = 0,
    val distanceMeters: Double = 0.0,
    val startedMillis: Long = 0L,
) {
    val active: Boolean get() = status != RecordingStatus.IDLE
}

/** Where a finished recording is stored; implemented over `PlacesRepository` by [asTrackStore]. */
fun interface TrackStore {
    /** Stores a track and returns its id. */
    fun save(name: String, notes: String?, segments: List<List<TrackPoint>>, createdAt: Long): Long
}

fun com.qtekfun.mapas.core.data.PlacesRepository.asTrackStore() = TrackStore { name, notes, segments, createdAt ->
    addTrack(name, PathKind.TRACK, segments, notes = notes, createdAt = createdAt)
}

sealed interface StopResult {
    data class Saved(val trackId: Long, val points: Int) : StopResult
    data object TooShort : StopResult
    data object NotRecording : StopResult

    /** The track could not be stored; the points stay in the journal and are stored at the next start. */
    data object Failed : StopResult
}

/** Marker stored as the notes of every recorded track, so "delete recorded tracks" can tell them from imported ones. */
const val RECORDED_TRACK_NOTES = "mapas:recorded"

/**
 * Records the user's own trip as a track, on the device only. Callers feed it every fix ([onFix], any thread: it is
 * synchronised); it keeps the sparse subset described by [RecordingConfig] and appends each kept point to [journal]
 * at once, so a crash loses almost nothing. [stop] turns the journal into a stored track through [store]. The time
 * base is the fixes' own (pausing and gaps use fix time), so tests with simulated fixes need no clock; fixes without
 * a time are stamped with [clock]. Positions are never logged.
 *
 * @param name builds the track's name from the start time (the app localises it).
 */
class TrackRecorder(
    private val journal: TrackJournal,
    private val store: TrackStore,
    private val name: (startMillis: Long) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: RecordingConfig = RecordingConfig(),
    private val onState: (RecordingState) -> Unit = {},
) {
    private var state = RecordingState()
    private var last: LatLon? = null
    private var lastTime = 0L
    private var needSegment = true
    private var anchor: LatLon? = null
    private var anchorTime = 0L

    val current: RecordingState @Synchronized get() = state

    /**
     * Begins a new recording. Returns false if one is already running, or if points left by an interrupted recording
     * could not be stored (they are kept; starting would overwrite them). Leftover points are stored first.
     */
    @Synchronized
    fun start(): Boolean {
        if (state.active) return false
        if (finalizeJournal() == StopResult.Failed) return false
        val now = clock()
        journal.begin(now)
        last = null
        anchor = null
        needSegment = true
        publish(RecordingState(RecordingStatus.RECORDING, 0, 0.0, now))
        return true
    }

    /** Offers a fix. Ignored unless recording. [timeMillis] 0 means "now". */
    @Synchronized
    fun onFix(point: LatLon, accuracyMeters: Float? = null, speedMps: Float? = null, timeMillis: Long = 0L) {
        if (!state.active) return
        if (accuracyMeters != null && accuracyMeters > config.maxAccuracyMeters) return
        val time = if (timeMillis > 0L) timeMillis else clock()
        val prev = last
        if (prev != null && time <= lastTime) return
        // Standing still: remember where it began; moving beyond the radius starts over.
        val a = anchor
        if (a == null || point.distanceTo(a) > config.stopRadiusMeters) {
            anchor = point
            anchorTime = time
            if (state.status == RecordingStatus.PAUSED) {
                // Moving again after a long stop: a new segment, no line across the stop.
                needSegment = true
                publish(state.copy(status = RecordingStatus.RECORDING))
                accept(point, time)
                return
            }
        } else if (state.status == RecordingStatus.PAUSED) {
            return
        } else if (time - anchorTime >= config.pauseAfterMillis) {
            publish(state.copy(status = RecordingStatus.PAUSED))
            return
        }
        if (prev == null) {
            accept(point, time)
            return
        }
        if (time - lastTime < config.minIntervalMillis) return
        if (time - lastTime >= config.segmentGapMillis) needSegment = true
        val moved = point.distanceTo(prev)
        val speed = speedMps?.toDouble()?.takeIf { it >= 0 } ?: (moved / ((time - lastTime) / 1000.0))
        val threshold = (speed * config.distanceSeconds).coerceIn(config.minDistanceMeters, config.maxDistanceMeters)
        if (needSegment || moved >= threshold) accept(point, time)
    }

    private fun accept(point: LatLon, time: Long) {
        val prev = last
        if (needSegment && prev != null) journal.breakSegment()
        val distance = if (prev == null || needSegment) state.distanceMeters else state.distanceMeters + point.distanceTo(prev)
        needSegment = false
        journal.append(TrackPoint(point, null, time))
        last = point
        lastTime = time
        publish(state.copy(points = state.points + 1, distanceMeters = distance))
    }

    /** Ends the recording and stores it (when it has enough points). */
    @Synchronized
    fun stop(): StopResult {
        if (!state.active) return StopResult.NotRecording
        journal.close()
        val result = finalizeJournal()
        publish(RecordingState())
        return result
    }

    /** Throws the recording away without storing it. */
    @Synchronized
    fun discard() {
        journal.delete()
        publish(RecordingState())
    }

    /**
     * Stores what a previous run left in the journal (the process died while recording). Returns the new track's
     * id, or null when there was nothing worth storing. Does nothing while recording.
     */
    @Synchronized
    fun recoverInterrupted(): Long? {
        if (state.active) return null
        val result = finalizeJournal()
        return (result as? StopResult.Saved)?.trackId
    }

    private fun finalizeJournal(): StopResult {
        val data = journal.read() ?: return StopResult.TooShort.also { journal.delete() }
        if (data.pointCount < config.minPoints) {
            journal.delete()
            return StopResult.TooShort
        }
        val id = try {
            store.save(name(data.startMillis), RECORDED_TRACK_NOTES, data.segments, data.startMillis)
        } catch (_: Exception) {
            return StopResult.Failed
        }
        journal.delete()
        return StopResult.Saved(id, data.pointCount)
    }

    private fun publish(next: RecordingState) {
        state = next
        onState(next)
    }
}
