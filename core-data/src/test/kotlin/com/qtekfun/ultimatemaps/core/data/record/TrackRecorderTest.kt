package com.qtekfun.ultimatemaps.core.data.record

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qtekfun.ultimatemaps.core.data.GeoDataTransfer
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.io.GpxExporter
import com.qtekfun.ultimatemaps.core.geo.io.GpxImporter
import com.qtekfun.ultimatemaps.core.geo.io.PathKind
import com.qtekfun.ultimatemaps.core.geo.io.TrackPoint
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.cos
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recorder is driven with simulated fixes and a fake clock: no real time, no threads, no Android. */
class TrackRecorderTest {
    private val files = ArrayList<File>()
    private var now = 1_700_000_000_000L

    private class Saved(val name: String, val notes: String?, val segments: List<List<TrackPoint>>, val createdAt: Long)

    private val saved = ArrayList<Saved>()
    private var failStore = false

    private fun journalFile() = File.createTempFile("rec", ".journal").also { it.delete(); files += it }

    private fun recorder(
        file: File = journalFile(), config: RecordingConfig = RecordingConfig(), states: MutableList<RecordingState>? = null,
    ) = TrackRecorder(
        FileTrackJournal(file),
        { name, notes, segments, createdAt ->
            if (failStore) error("database unavailable")
            saved += Saved(name, notes, segments, createdAt)
            saved.size.toLong()
        },
        name = { "Recorded $it" },
        clock = { now },
        config = config,
        onState = { states?.add(it) },
    )

    @AfterTest fun tearDown() = files.forEach { it.delete() }

    private val m = 111_194.9266
    private fun at(east: Double, north: Double) = LatLon(40.0 + north / m, -3.0 + east / (m * cos(Math.toRadians(40.0))))

    /** [seconds] fixes, one per second, moving north at [speed] m/s from [startNorth]; returns the next second to use. */
    private fun TrackRecorder.walk(t0: Long, startNorth: Double, speed: Double, seconds: Int, accuracy: Float? = 5f): Long {
        for (i in 0 until seconds) onFix(at(0.0, startNorth + speed * i), accuracy, speed.toFloat(), t0 + i * 1_000L)
        return t0 + seconds * 1_000L
    }

    @Test fun `walking keeps a point about every five metres, driving about every fifty`() {
        val walk = recorder()
        walk.start()
        walk.walk(now + 1_000, 0.0, 1.4, 120) // 168 m
        val walkPoints = walk.current.points
        assertTrue(walkPoints in 28..40, "walking kept $walkPoints points for 168 m")

        val drive = recorder()
        drive.start()
        drive.walk(now + 1_000, 0.0, 25.0, 120) // 3 km
        val drivePoints = drive.current.points
        assertTrue(drivePoints in 55..65, "driving kept $drivePoints points for 3 km")
        assertEquals(2_975.0, drive.current.distanceMeters, 100.0)
    }

    @Test fun `inaccurate, repeated and out-of-order fixes are ignored`() {
        val r = recorder()
        r.start()
        r.onFix(at(0.0, 0.0), 5f, null, now + 1_000)
        r.onFix(at(0.0, 100.0), 80f, null, now + 4_000) // too inaccurate
        r.onFix(at(0.0, 100.0), 5f, null, now + 1_000) // same time as the kept point
        r.onFix(at(0.0, 100.0), 5f, null, now + 500) // older
        r.onFix(at(0.0, 100.0), 5f, null, now + 2_000) // closer than the minimum interval
        assertEquals(1, r.current.points)
        r.onFix(at(0.0, 100.0), 5f, null, now + 5_000)
        assertEquals(2, r.current.points)
    }

    @Test fun `a long stop pauses the recording and moving again starts a new segment`() {
        val states = ArrayList<RecordingState>()
        val r = recorder(states = states)
        r.start()
        var t = r.walk(now + 1_000, 0.0, 10.0, 30) // 300 m
        val before = r.current.points
        // Stopped for 8 minutes with GPS jitter of a few metres.
        for (i in 0 until 480) r.onFix(at((i % 4) * 2.0, 300.0 + (i % 3) * 2.0), 5f, 0f, t + i * 1_000L)
        assertEquals(RecordingStatus.PAUSED, r.current.status)
        assertTrue(states.any { it.status == RecordingStatus.PAUSED })
        assertTrue(r.current.points - before <= 60, "kept ${r.current.points - before} points while stopping")
        val pausedPoints = r.current.points
        for (i in 0 until 60) r.onFix(at(1.0, 301.0), 5f, 0f, t + (500 + i) * 1_000L)
        assertEquals(pausedPoints, r.current.points, "nothing is kept while paused")
        t += 600_000L
        r.walk(t, 300.0, 10.0, 30)
        assertEquals(RecordingStatus.RECORDING, r.current.status)
        val result = r.stop() as StopResult.Saved
        assertEquals(1L, result.trackId)
        assertEquals(2, saved.single().segments.size, "no line is drawn across the stop")
    }

    @Test fun `a gap in the signal starts a new segment instead of a straight line`() {
        val r = recorder()
        r.start()
        r.walk(now + 1_000, 0.0, 10.0, 20)
        r.walk(now + 1_000 + 30 * 60_000L, 5_000.0, 10.0, 20) // half an hour later, 5 km away
        r.stop()
        val segs = saved.single().segments
        assertEquals(2, segs.size)
        assertTrue(segs.all { it.size >= 2 })
    }

    @Test fun `stopping stores a track marked as recorded, and one point is not a track`() {
        val r = recorder()
        assertTrue(r.start())
        assertFalse(r.start(), "already recording")
        r.walk(now + 1_000, 0.0, 10.0, 30)
        val result = r.stop()
        assertTrue(result is StopResult.Saved)
        val s = saved.single()
        assertEquals("Recorded $now", s.name)
        assertEquals(RECORDED_TRACK_NOTES, s.notes)
        assertEquals(now, s.createdAt)
        assertTrue(s.segments.single().all { it.timeMillis != null })
        assertFalse(r.current.active)
        assertEquals(StopResult.NotRecording, r.stop())

        val single = recorder()
        single.start()
        single.onFix(at(0.0, 0.0), 5f, null, now + 1_000)
        assertEquals(StopResult.TooShort, single.stop())
        assertEquals(1, saved.size, "nothing new was stored")
    }

    @Test fun `fixes are ignored when not recording and without a time they are stamped by the clock`() {
        val r = recorder()
        r.onFix(at(0.0, 0.0), 5f, null, now)
        assertEquals(0, r.current.points)
        r.start()
        r.onFix(at(0.0, 0.0))
        now += 10_000
        r.onFix(at(0.0, 200.0))
        r.stop()
        val times = saved.single().segments.single().map { it.timeMillis }
        assertEquals(listOf(now - 10_000, now), times)
    }

    @Test fun `after a crash the points are stored on the next start, even with a damaged last line`() {
        val file = journalFile()
        val first = recorder(file)
        first.start()
        first.walk(now + 1_000, 0.0, 10.0, 40)
        val kept = first.current.points
        // The process dies: no stop(). The last write was cut short.
        file.appendText("P 40.12")
        val second = recorder(file)
        val id = second.recoverInterrupted()
        assertNotNull(id)
        assertEquals(kept, saved.single().segments.sumOf { it.size })
        assertFalse(file.exists(), "the journal is gone once stored")
        assertNull(second.recoverInterrupted())
    }

    @Test fun `starting again stores the leftovers first and a failing store keeps them`() {
        val file = journalFile()
        val first = recorder(file)
        first.start()
        first.walk(now + 1_000, 0.0, 10.0, 40)

        failStore = true
        val second = recorder(file)
        assertFalse(second.start(), "the leftover points could not be stored: do not overwrite them")
        assertTrue(file.exists())
        failStore = false
        assertTrue(second.start())
        assertEquals(1, saved.size, "the leftovers became a track")
        second.discard()
        assertFalse(file.exists())
        assertFalse(second.current.active)
    }

    @Test fun `a failed stop keeps the journal for later`() {
        val file = journalFile()
        val r = recorder(file)
        r.start()
        r.walk(now + 1_000, 0.0, 10.0, 40)
        failStore = true
        assertEquals(StopResult.Failed, r.stop())
        assertTrue(file.exists())
        failStore = false
        assertNotNull(recorder(file).recoverInterrupted())
    }

    @Test fun `a recorded track is a normal track for storage, the map layer and the GPX export`() {
        val repo = SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:")
        try {
            val r = TrackRecorder(FileTrackJournal(journalFile()), repo.asTrackStore(), { "Trip" }, { now })
            r.start()
            r.walk(now + 1_000, 0.0, 10.0, 60)
            val id = (r.stop() as StopResult.Saved).trackId
            val info = repo.tracks().single()
            assertEquals(PathKind.TRACK, info.kind)
            assertEquals(RECORDED_TRACK_NOTES, info.notes)
            assertEquals(id, info.id)
            val track = repo.track(id)!!
            assertEquals(info.pointCount, track.segments.sumOf { it.size })
            // What the "tracks on the map" layer reads: plain segments of points.
            assertTrue(track.segments.single().size > 10)

            val out = ByteArrayOutputStream()
            GeoDataTransfer(repo).export(GpxExporter, out)
            val xml = out.toString(Charsets.UTF_8)
            assertTrue(xml.contains("<trk><name>Trip</name>"))
            assertTrue(xml.contains("<time>"))
            // ... and the export reads back as the same track.
            val back = GpxImporter.parse(xml.byteInputStream())
            assertEquals(info.pointCount, back.paths.single().pointCount)

            assertTrue(repo.deleteTrack(id), "deleting it is one call")
            assertTrue(repo.tracks().isEmpty())
        } finally {
            repo.close()
        }
    }

    @Test fun `the journal round trips points and segment breaks`() {
        val file = journalFile()
        val j = FileTrackJournal(file)
        j.begin(42L)
        j.append(TrackPoint(LatLon(40.1, -3.2), null, 1_000L))
        j.append(TrackPoint(LatLon(40.2, -3.3), null, 2_000L))
        j.breakSegment()
        j.append(TrackPoint(LatLon(41.0, -3.0), null, 9_000L))
        j.close()
        val back = FileTrackJournal(file).read()!!
        assertEquals(42L, back.startMillis)
        assertEquals(listOf(2, 1), back.segments.map { it.size })
        assertEquals(LatLon(40.2, -3.3), back.segments[0][1].point)
        assertEquals(9_000L, back.segments[1][0].timeMillis)
        // A file without a header is not a journal.
        file.writeText("P 1.0 2.0 3\n")
        assertNull(FileTrackJournal(file).read())
        assertNull(FileTrackJournal(File(file.path + ".missing")).read())
    }
}
