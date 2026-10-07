package com.qtekfun.ultimatemaps.recording

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository
import com.qtekfun.ultimatemaps.core.data.record.FileTrackJournal
import com.qtekfun.ultimatemaps.core.data.record.RECORDED_TRACK_NOTES
import com.qtekfun.ultimatemaps.core.data.record.RecordingStatus
import com.qtekfun.ultimatemaps.core.data.record.TrackRecorder
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.io.GpxExporter
import com.qtekfun.ultimatemaps.core.geo.io.PathKind
import com.qtekfun.ultimatemaps.core.geo.io.TrackPoint
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.map.TrackLine
import com.qtekfun.ultimatemaps.places.DefaultList
import com.qtekfun.ultimatemaps.places.LongSetting
import com.qtekfun.ultimatemaps.places.PanelMode
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.places.PlacesService
import com.qtekfun.ultimatemaps.places.TrackLayerController
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
import com.qtekfun.ultimatemaps.search.LogcatSearchLog
import com.qtekfun.ultimatemaps.search.PanelActions
import com.qtekfun.ultimatemaps.search.SearchCoordinator
import com.qtekfun.ultimatemaps.search.SheetPanel
import com.qtekfun.ultimatemaps.settings.RecordingSection
import com.qtekfun.ultimatemaps.settings.RecordingSettingsEnv
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Track recording end to end without a device: a simulated location source, a fake clock, the real recorder,
 * journal and database. Everything runs on [Dispatchers.Unconfined] (no threads, no waiting for recomposition from
 * another thread).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RecordingTest {
    @get:Rule
    val rule = createComposeRule()

    private val journal = File.createTempFile("recording", ".journal").also { it.delete(); it.deleteOnExit() }
    private var now = 1_700_000_000_000L

    private val repo = SqlitePlacesRepository(AndroidSQLiteDriver(), ":memory:")
    private val service = PlacesService(repo, DefaultList(repo, object : LongSetting {
        var v: Long? = null
        override fun get() = v
        override fun set(value: Long) { v = value }
    }) { "Favorites" })

    private val settings = InMemoryRecordingSettings()
    private var adminDeletes = 0

    private val controller = RecordingController(
        CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
        TrackRecorder(FileTrackJournal(journal), service.trackStore(), { "Trip $it" }, { now }),
        settings,
        admin = { adminDeletes++; service.deleteRecordedTracks() },
    )

    @After fun tearDown() {
        journal.delete()
        repo.close()
    }

    private val m = 111_194.9266
    private fun at(north: Double) = LatLon(40.0 + north / m, -3.0)

    /** [seconds] fixes, one per second, 10 m/s north, starting after [from] seconds. */
    private fun feed(source: SimulatedLocationSource, from: Int, seconds: Int) {
        for (i in from until from + seconds) source.emit(LocationFix(at(10.0 * i), 5f, null, 10f, now + i * 1_000L))
    }

    @Test fun `recording is off by default, ignores Start and stores nothing`() {
        assertFalse(controller.enabled.value)
        controller.start()
        assertFalse(controller.state.value.active)
        controller.onFix(LocationFix(at(0.0), 5f, null, 5f, now + 1_000))
        assertTrue(repo.tracks().isEmpty())
        assertFalse(journal.exists(), "nothing is written while the feature is off")
    }

    @Test fun `a trip recorded from a simulated location is stored, drawn on the map and exported as GPX`() {
        controller.setEnabled(true)
        assertTrue(settings.enabled, "the switch is persisted")
        controller.start()
        assertEquals(RecordingStatus.RECORDING, controller.state.value.status)
        val source = SimulatedLocationSource()
        // The same wiring as the application: the tap sees every fix before the listener.
        val tap = TapLocationSource(source, controller::onFix)
        val listened = mutableListOf<LocationFix>()
        tap.start { listened += it }
        feed(source, 1, 90)
        assertEquals(90, listened.size, "the listener (navigation) still gets every fix")
        assertTrue(controller.state.value.points in 10..40, "sparse points, not every fix: ${controller.state.value.points}")
        assertEquals(890.0, controller.state.value.distanceMeters, 120.0)
        assertTrue(journal.exists(), "points are appended to the journal while recording")

        val stored = ArrayList<Unit>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.stored.collect { stored += Unit } }
        controller.stop()
        assertEquals(RecordingNotice.SAVED, controller.notice.value)
        assertEquals(1, stored.size)
        assertFalse(controller.state.value.active)
        assertFalse(journal.exists(), "the journal is gone once the track is stored")

        val info = repo.tracks().single()
        assertEquals(PathKind.TRACK, info.kind)
        assertEquals(RECORDED_TRACK_NOTES, info.notes)
        assertEquals("Trip $now", info.name)

        // The "tracks on the map" layer reads it like an imported one.
        val rendered = mutableListOf<List<TrackLine>>()
        val layer = TrackLayerController(CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, lazyOf(service), render = { rendered += it }, fit = {})
        layer.refresh()
        layer.toggle(info.id)
        assertEquals(info.pointCount, rendered.last().single().segments.flatten().size)

        val out = ByteArrayOutputStream()
        GpxExporter.write(com.qtekfun.ultimatemaps.core.data.GeoDataTransfer(repo).toDocument(), out)
        assertTrue(out.toString(Charsets.UTF_8).contains("<time>"), "the GPX export carries the recorded times")
    }

    @Test fun `a failing tap never reaches the navigation`() {
        val source = SimulatedLocationSource()
        val got = mutableListOf<LocationFix>()
        TapLocationSource(source) { error("recorder broke") }.start { got += it }
        source.emit(LocationFix(at(0.0), 5f, null, 5f, now + 1_000))
        assertEquals(1, got.size)
    }

    @Test fun `switching the feature off while recording saves the recording`() {
        controller.setEnabled(true)
        controller.start()
        val source = SimulatedLocationSource()
        TapLocationSource(source, controller::onFix).start { }
        feed(source, 1, 60)
        controller.setEnabled(false)
        assertFalse(controller.state.value.active)
        assertEquals(1, repo.tracks().size)
    }

    @Test fun `too short a recording is not stored and says so`() {
        controller.setEnabled(true)
        controller.start()
        controller.onFix(LocationFix(at(0.0), 5f, null, 5f, now + 1_000))
        controller.stop()
        assertEquals(RecordingNotice.TOO_SHORT, controller.notice.value)
        assertTrue(repo.tracks().isEmpty())
        controller.dismissNotice()
        assertEquals(null, controller.notice.value)
    }

    @Test fun `a crash leaves the journal and the next start stores it as a track`() {
        controller.setEnabled(true)
        controller.start()
        val source = SimulatedLocationSource()
        TapLocationSource(source, controller::onFix).start { }
        feed(source, 1, 60)
        // The process dies: no stop(). A new application instance (new controller, same journal) starts up.
        val reborn = RecordingController(
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
            TrackRecorder(FileTrackJournal(journal), service.trackStore(), { "Recovered" }, { now }),
            settings, admin = { 0 },
        )
        reborn.recoverInterrupted()
        assertEquals(listOf("Recovered"), repo.tracks().map { it.name })
        assertFalse(journal.exists())
    }

    // --- UI ---

    private fun panel(requests: MutableList<Unit>? = null) = RecordingPanel(controller) { requests?.add(Unit) }

    @Test fun `the controls are hidden until the switch is on, then Start asks for the location and Stop saves`() {
        val requests = mutableListOf<Unit>()
        rule.setContent { MapasTheme(darkTheme = false) { RecordingControls(panel(requests)) } }
        rule.onNodeWithTag("recording_controls").assertDoesNotExist()
        controller.setEnabled(true)
        rule.onNodeWithTag("recording_controls").assertIsDisplayed()
        rule.onNodeWithTag("record_start").performClick()
        assertTrue(controller.state.value.active)
        assertEquals(1, requests.size, "starting asks the screen for the location")
        rule.onNodeWithTag("record_stop").assertIsDisplayed()
        rule.onNodeWithTag("recording_note").assertIsDisplayed()
        val source = SimulatedLocationSource()
        TapLocationSource(source, controller::onFix).start { }
        feed(source, 1, 40)
        rule.onNodeWithTag("recording_status").assertIsDisplayed()
        rule.onNodeWithTag("record_stop").performClick()
        assertFalse(controller.state.value.active)
        rule.onNodeWithTag("recording_notice").assertIsDisplayed()
        assertEquals(1, repo.tracks().size)
    }

    @Test fun `the settings switch starts off and the delete button removes only recorded tracks in one tap`() {
        val imported = repo.addTrack("Imported", PathKind.TRACK, listOf(listOf(TrackPoint(LatLon(40.0, -3.0)), TrackPoint(LatLon(40.1, -3.1)))))
        controller.setEnabled(true)
        controller.start()
        val source = SimulatedLocationSource()
        TapLocationSource(source, controller::onFix).start { }
        feed(source, 1, 40)
        controller.stop()
        assertEquals(2, repo.tracks().size)
        controller.setEnabled(false)

        rule.setContent { MapasTheme(darkTheme = false) { RecordingSection(RecordingSettingsEnv(controller)) } }
        assertFalse(controller.enabled.value)
        rule.onNodeWithTag("recording_switch").performClick()
        assertTrue(controller.enabled.value)
        rule.onNodeWithTag("recording_delete_all").performClick()
        assertEquals(1, adminDeletes)
        assertEquals(listOf(imported), repo.tracks().map { it.id }, "the imported track is untouched")
        rule.onNodeWithTag("recording_deleted").assertIsDisplayed()
        rule.onNodeWithTag("recording_switch").performClick()
        assertFalse(controller.enabled.value)
    }

    @Test fun `the tracks list offers one-tap delete only on recorded tracks`() {
        repo.addTrack("Imported", PathKind.TRACK, listOf(listOf(TrackPoint(LatLon(40.0, -3.0)), TrackPoint(LatLon(40.1, -3.1)))), createdAt = 1)
        val recorded = repo.addTrack(
            "Mine", PathKind.TRACK, listOf(listOf(TrackPoint(LatLon(41.0, 2.0)), TrackPoint(LatLon(41.1, 2.1)))), notes = RECORDED_TRACK_NOTES, createdAt = 2,
        )
        val layer = TrackLayerController(
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, lazyOf(service), render = {}, fit = {}, recording = panel(),
        )
        val places = PlacesController(
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, lazyOf(service), near = { null }, onMarkers = {}, onReloaded = layer::refresh,
        )
        val search = SearchCoordinator(
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
            object : InstalledRegions { override fun coreMaps(): CoreMaps? = null }, { error("not used") },
            near = { null }, clock = { 0L }, log = LogcatSearchLog,
        )
        val actions = PanelActions({}, {}, {}, {}, onImport = {}, onExport = {}, onFocusField = {})
        places.showMode(PanelMode.LISTS)
        controller.setEnabled(true)
        rule.setContent { MapasTheme(darkTheme = false) { SheetPanel(search, places, actions, tracks = layer) } }
        rule.onAllNodesWithTag("track_row").assertCountEquals(2)
        rule.onAllNodesWithTag("track_delete").assertCountEquals(1)
        rule.onNodeWithTag("record_start").assertIsDisplayed()
        layer.toggle(recorded)
        rule.onNodeWithTag("track_delete").performClick()
        assertEquals(listOf("Imported"), repo.tracks().map { it.name })
        assertTrue(layer.state.visible.isEmpty(), "a deleted track leaves the map")
        rule.onAllNodesWithTag("track_row").assertCountEquals(1)
    }
}
