package com.qtekfun.mapas.places

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.mapas.core.data.SqlitePlacesRepository
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.io.PathKind
import com.qtekfun.mapas.core.geo.io.TrackPoint
import com.qtekfun.mapas.core.map.TrackLine
import com.qtekfun.mapas.search.PanelActions
import com.qtekfun.mapas.search.SheetPanel
import com.qtekfun.mapas.search.SearchCoordinator
import com.qtekfun.mapas.search.InstalledRegions
import com.qtekfun.mapas.search.CoreMaps
import com.qtekfun.mapas.search.LogcatSearchLog
import com.qtekfun.mapas.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Everything runs on [Dispatchers.Unconfined]: no threads and no waiting on recomposition from another thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TrackLayerTest {
    @get:Rule
    val rule = createComposeRule()

    private val repo = SqlitePlacesRepository(AndroidSQLiteDriver(), ":memory:")
    private val service = PlacesService(repo, DefaultList(repo, object : LongSetting {
        var v: Long? = null
        override fun get() = v
        override fun set(value: Long) { v = value }
    }) { "Favorites" })

    private val rendered = mutableListOf<List<TrackLine>>()
    private val fitted = mutableListOf<List<LatLon>>()

    private val controller = TrackLayerController(
        CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, lazyOf(service),
        render = { rendered += it }, fit = { fitted += it },
    )

    private fun pts(vararg p: Pair<Double, Double>) = p.map { TrackPoint(LatLon(it.first, it.second)) }

    private val a = repo.addTrack("Walk", PathKind.TRACK, listOf(pts(40.0 to -3.0, 40.1 to -3.1), pts(40.2 to -3.2, 40.3 to -3.3)), createdAt = 1)
    private val b = repo.addTrack("Ride", PathKind.ROUTE, listOf(pts(41.0 to 2.0, 41.1 to 2.1)), color = 0xFF112233.toInt(), createdAt = 2)

    @Test
    fun togglingDrawsAndRemovesOnlyThatTrack() {
        controller.refresh()
        assertEquals(listOf("Ride", "Walk"), controller.state.tracks.map { it.name }) // newest first
        assertTrue(rendered.isEmpty())

        controller.toggle(a)
        assertEquals(listOf(a), rendered.last().map { it.id })
        assertEquals(2, rendered.last().single().segments.size)
        controller.toggle(b)
        assertEquals(setOf(a, b), rendered.last().map { it.id }.toSet())
        assertEquals(0xFF112233.toInt(), rendered.last().first { it.id == b }.color)
        assertEquals(TrackLayerController.colorOf(controller.state.tracks.first { it.id == a }), rendered.last().first { it.id == a }.color)

        controller.toggle(a)
        assertEquals(listOf(b), rendered.last().map { it.id })
        controller.toggle(b)
        assertEquals(emptyList(), rendered.last())
    }

    @Test
    fun fitShowsTheTrackAndFramesAllItsPoints() {
        controller.refresh()
        controller.fitTo(a)
        assertEquals(setOf(a), controller.state.visible)
        assertEquals(4, fitted.single().size)
        assertEquals(LatLon(40.3, -3.3), fitted.single().last())
    }

    @Test
    fun aDeletedTrackDisappearsFromTheMapOnRefresh() {
        controller.refresh()
        controller.toggle(a)
        repo.deleteTrack(a)
        controller.refresh()
        assertEquals(listOf("Ride"), controller.state.tracks.map { it.name })
        assertTrue(controller.state.visible.isEmpty())
        assertEquals(emptyList(), rendered.last())
    }

    @Test
    fun listsOverviewShowsOneRowPerTrackAndTheSwitchesWork() {
        val places = PlacesController(
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, lazyOf(service), near = { null }, onMarkers = {},
            onReloaded = controller::refresh,
        )
        val search = SearchCoordinator(
            CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
            object : InstalledRegions { override fun coreMaps(): CoreMaps? = null }, { error("not used") },
            near = { null }, clock = { 0L }, log = LogcatSearchLog,
        )
        val actions = PanelActions({}, {}, {}, {}, onImport = {}, onExport = {}, onFocusField = {})
        places.showMode(PanelMode.LISTS) // reloads, which refreshes the tracks
        rule.setContent { MapasTheme(darkTheme = false) { SheetPanel(search, places, actions, tracks = controller) } }

        rule.onAllNodesWithTag("track_row").assertCountEquals(2)
        rule.onAllNodesWithTag("track_toggle")[0].performClick()
        assertEquals(1, controller.state.visible.size)
        rule.onAllNodesWithTag("track_fit")[1].performClick()
        assertEquals(2, controller.state.visible.size)
        assertEquals(1, fitted.size)
        rule.onNodeWithTag("tracks_title").assertExists()
    }
}
