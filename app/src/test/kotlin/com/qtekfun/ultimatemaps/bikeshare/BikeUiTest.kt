package com.qtekfun.ultimatemaps.bikeshare

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemaps.core.bikeshare.BikeAvailability
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareDataManager
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareFile
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareRepository
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettings
import com.qtekfun.ultimatemaps.core.bikeshare.BikeStation
import com.qtekfun.ultimatemaps.core.bikeshare.InMemoryBikeShareSettingsStore
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.places.DefaultList
import com.qtekfun.ultimatemaps.places.LongSetting
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.places.PlacesService
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
import com.qtekfun.ultimatemaps.search.LogcatSearchLog
import com.qtekfun.ultimatemaps.search.PanelActions
import com.qtekfun.ultimatemaps.search.SearchCoordinator
import com.qtekfun.ultimatemaps.settings.BikeShareSection
import com.qtekfun.ultimatemaps.settings.BikeShareSettingsEnv
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BikeUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private val dataset = BikeShareFile.parse(javaClass.getResourceAsStream("/bikeshare/sample.bin")!!.readBytes())
    private val repo = BikeShareRepository().also { it.install(dataset) }
    private val gran = repo.data.stations.first { it.stationId == "1" && it.system.id == "bicing" }

    private val route = RoutePreviewController(
        scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) =
                    RouteOutcome(RouteCode.NO_ERROR, RoutePlan(listOf(request.from, request.to), 1000.0, 60.0))
                override fun route(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) = routeDetailed(request).plan
                override fun close() = Unit
            }
        },
        userLocation = { LatLon(41.0, 2.0) }, showRoute = {}, clearRoute = {}, clock = { 0L }, log = { _, _, _ -> },
    )

    private val places = PlacesController(
        scope, Dispatchers.IO,
        lazy {
            val db = SqlitePlacesRepository(AndroidSQLiteDriver(), ":memory:")
            val setting = object : LongSetting {
                var v: Long? = null
                override fun get() = v
                override fun set(value: Long) { v = value }
            }
            PlacesService(db, DefaultList(db, setting) { "Favorites" })
        },
        near = { null }, onMarkers = {},
    )

    private val search = SearchCoordinator(
        scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps(): CoreMaps? = null }, { error("not used") },
        near = { null }, clock = { 0L }, log = LogcatSearchLog,
    )

    private val actions = PanelActions(onPickResult = {}, onShowSaved = {}, onRoute = {}, onShare = {}, onImport = {}, onExport = {}, onFocusField = {})

    private var opened = 0

    private fun controller(live: suspend (BikeStation) -> BikeAvailability? = { null }, refresh: Long = 30_000L) = BikeCardController(
        repo, route, BikeCardState(), category = { "Bike-share station" }, onOpened = { opened++ },
        scope = CoroutineScope(Dispatchers.Unconfined), live = live, refreshMillis = refresh,
    )

    private fun show(c: BikeCardController) = rule.setContent {
        val host = BikeCardHost(c.card, { repo.generatedMillis.value }, c::go, c::addStop)
        MapasTheme(darkTheme = false) { com.qtekfun.ultimatemaps.search.SheetPanel(search, places, actions, route = route, bike = host) }
    }

    // ---------------------------------------------------------------------------------------------------- the card

    @Test fun tappingAStationOpensItsCardWithNameSystemDocksAttributionAndRouteButton() {
        val c = controller()
        c.onStationTap(gran.id)
        assertEquals(1, opened)
        show(c)
        rule.onNodeWithTag("bike_card").assertIsDisplayed()
        assertEquals("GRAN VIA CORTS CATALANES, 760", gran.name)
        rule.onNodeWithTag("bike_title").assertIsDisplayed()
        rule.onNodeWithTag("bike_system").assertIsDisplayed()
        rule.onNodeWithTag("bike_capacity").assertIsDisplayed()
        rule.onNodeWithTag("bike_card_attribution").assertIsDisplayed()
        rule.onNodeWithTag("bike_go").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("bike_live").fetchSemanticsNodes().size, "no live data: no live line")
        assertEquals(0, rule.onAllNodesWithTag("bike_add_stop").fetchSemanticsNodes().size, "no route active: no Add stop")
        assertEquals(gran.system.attribution, "Bicing: Ajuntament de Barcelona, Open Data BCN, CC BY 4.0")
    }

    @Test fun theLiveLineShowsBikesFreeDocksAndTheReportedTime() {
        val at = 1_791_471_428_249L
        val c = controller(live = { BikeAvailability(2, 43, at) })
        c.onStationTap(gran.id)
        assertEquals(BikeAvailability(2, 43, at), c.card.availability)
        show(c)
        rule.onNodeWithTag("bike_live").assertIsDisplayed()
        val clock = updatedClock(at, Locale.getDefault())
        assertTrue(Regex("\\d\\d:\\d\\d").matches(clock))
        assertEquals("14:57", updatedClock(at, Locale.ENGLISH, TimeZone.getTimeZone("UTC")))
        assertEquals("16:57", updatedClock(at, Locale.ENGLISH, TimeZone.getTimeZone("Europe/Madrid")))
    }

    @Test fun aFailedLiveLookupLeavesNoTraceOnTheCard() {
        val c = controller(live = { throw java.io.IOException("boom") })
        c.onStationTap(gran.id)
        assertNull(c.card.availability)
        show(c)
        rule.onNodeWithTag("bike_card").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("bike_live").fetchSemanticsNodes().size)
    }

    @Test fun liveCountsAreAskedForOnlyWhileTheCardIsOpen() {
        val calls = AtomicInteger()
        val c = controller(live = { calls.incrementAndGet(); BikeAvailability(1, 1, 0) }, refresh = 20)
        assertEquals(0, calls.get(), "nothing before a card opens")
        c.onStationTap(gran.id)
        val end = System.nanoTime() + 5_000_000_000L
        while (calls.get() < 2) { check(System.nanoTime() < end) { "timeout" }; Thread.sleep(10) }
        c.close()
        val after = calls.get()
        Thread.sleep(150)
        assertEquals(after, calls.get(), "closing the card stops the polling")
        assertNull(c.card.station)
        assertNull(c.card.availability)
    }

    @Test fun anotherStationReplacesTheLiveCountsOfTheFirst() {
        val other = repo.data.stations.first { it.stationId == "1406" }
        val c = controller(live = { s -> BikeAvailability(if (s.id == gran.id) 2 else 9, 1, 0) })
        c.onStationTap(gran.id)
        assertEquals(2, c.card.availability?.bikes)
        c.onStationTap(other.id)
        assertEquals(9, c.card.availability?.bikes)
        assertEquals(other.id, c.card.station?.id)
    }

    @Test fun goStartsARouteToTheStationAndClosesTheCard() {
        val c = controller()
        c.onStationTap(gran.id)
        c.go(gran)
        assertNull(c.card.station)
        assertTrue(route.state.active)
    }

    @Test fun anUnknownStationIdIsIgnored() {
        val c = controller()
        c.onStationTap("nope")
        assertNull(c.card.station)
        assertEquals(0, opened)
    }

    // ----------------------------------------------------------------------------------------------- the settings

    private val store = InMemoryBikeShareSettingsStore()
    private val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("127.0.0.1", ConnectionPurpose.MAP_DOWNLOAD, enabled = true)))
    private var assetCalls = 0
    private val data = BikeShareDataManager(
        store, policy, { assetCalls++; null }, Files.createTempDirectory("bikeui").toFile(),
        io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun showSettings() {
        data.start()
        val env = BikeShareSettingsEnv(store, data, locale = { Locale.ENGLISH })
        rule.setContent { MapasTheme(darkTheme = false) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { BikeShareSection(env) } } }
    }

    @Test fun everythingIsOffByDefaultAndNothingConnects() {
        showSettings()
        assertEquals(BikeShareSettings(), store.settings.value)
        rule.onNodeWithTag("bike_switch").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("bike_live_switch").fetchSemanticsNodes().size, "no options while the first switch is off")
        assertEquals(0, assetCalls)
        assertTrue(policy.recentConnections().isEmpty())
    }

    @Test fun turningTheSwitchOnShowsTheLiveSwitchWhichStaysOffAndTheAttribution() {
        showSettings()
        rule.onNodeWithTag("bike_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.enabled)
        assertFalse(store.settings.value.liveAvailability, "live availability is a separate opt-in")
        rule.onNodeWithTag("bike_live_switch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("bike_attribution").performScrollTo().assertIsDisplayed()
        assertTrue(assetCalls > 0, "the switch asked for the file")
        rule.onNodeWithTag("bike_live_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.liveActive)
    }

    @Test fun thePreferencesRoundTripAndDefaultsAreOff() {
        val prefs = ctx.getSharedPreferences("bike-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a = PrefsBikeShareSettingsStore(prefs)
        assertEquals(BikeShareSettings(), a.settings.value)
        a.update { it.copy(enabled = true, liveAvailability = true) }
        val b = PrefsBikeShareSettingsStore(prefs)
        assertEquals(BikeShareSettings(enabled = true, liveAvailability = true), b.settings.value)
        prefs.edit().putBoolean(PrefsBikeShareSettingsStore.KEY_LIVE_AVAILABILITY, false).commit()
        a.reload()
        assertEquals(BikeShareSettings(enabled = true, liveAvailability = false), a.settings.value)
        assertNotNull(PrefsBikeShareSettingsStore.PREFS)
    }
}
