package com.qtekfun.ultimatemaps.fuel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository
import com.qtekfun.ultimatemaps.core.fuel.FuelStation
import com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelRepository
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.AddStopResult
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.places.DefaultList
import com.qtekfun.ultimatemaps.places.LongSetting
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.places.PlacesService
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.route.RouteStatus
import com.qtekfun.ultimatemaps.route.StopResult
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.FuelCardHost
import com.qtekfun.ultimatemaps.search.InstalledRegions
import com.qtekfun.ultimatemaps.search.PanelActions
import com.qtekfun.ultimatemaps.search.SearchCoordinator
import com.qtekfun.ultimatemaps.search.LogcatSearchLog
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FuelStationCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_000_000_000_000L
    private val updated = now - 5 * 60_000L

    private val repsol = FuelStation(
        "1", "Repsol", "Calle Mayor 1", "Madrid", "Madrid", LatLon(40.42, -3.70), "L-D: 24H",
        prices = mapOf("g95" to 1.154, "diesel" to 1.249, "g98" to 1.399),
    )
    private val cepsa = FuelStation("2", "Cepsa", "Av. Sur 5", "Getafe", "Madrid", LatLon(40.30, -3.73), null, mapOf("g95" to 1.2))
    private val repo = InMemoryFuelRepository(listOf(repsol, cepsa), updated)
    private val names = mapOf("g95" to "Gasolina 95", "diesel" to "Diésel", "g98" to "Gasolina 98")

    private val dest = PlaceInfo("Destino", LatLon(41.38, 2.17))
    private val routeProfiles = mutableListOf<List<LatLon>>()
    private val route = RoutePreviewController(
        scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest): RouteOutcome {
                    routeProfiles += request.via
                    return RouteOutcome(RouteCode.NO_ERROR, RoutePlan(listOf(request.from, request.to), 1000.0, 60.0))
                }
                override fun route(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) = routeDetailed(request).plan
                override fun close() = Unit
            }
        },
        userLocation = { LatLon(40.0, -3.0) }, showRoute = {}, clearRoute = {}, clock = { 0L }, log = { _, _, _ -> },
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

    private var opened = 0
    private val controller = FuelCardController(repo, route, places, FuelCardState(), category = { "Gasolinera" }, onOpened = { opened++ })

    private val actions = PanelActions(
        onPickResult = {}, onShowSaved = {}, onRoute = {}, onShare = {}, onImport = {}, onExport = {}, onFocusField = {},
    )

    private fun show(mapFuel: String? = "diesel", navigating: Boolean = false) = rule.setContent {
        val host = FuelCardHost(
            state = controller.card, mapFuelId = { mapFuel }, fuelName = { names[it] ?: it },
            updatedMillis = { repo.lastUpdateMillis.value }, now = { now },
            onGo = controller::go, onAddStop = controller::addStop, onSave = controller::save,
            navigating = { navigating },
        )
        MapasTheme(darkTheme = false) { com.qtekfun.ultimatemaps.search.SheetPanel(search, places, actions, route = route, fuel = host) }
    }

    private fun settle(cond: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout" }
            Thread.sleep(10)
        }
    }

    private fun startRoute() {
        route.start(dest)
        settle { route.state.status == RouteStatus.DONE }
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun tappingAStationOpensItsCardWithEveryPriceAndTheAttribution() {
        controller.onStationTap("1")
        assertEquals(1, opened)
        show()
        rule.onNodeWithTag("fuel_brand").assertIsDisplayed()
        rule.onNodeWithText("Repsol").assertIsDisplayed()
        rule.onNodeWithText("Calle Mayor 1, Madrid").assertIsDisplayed()
        rule.onNodeWithText("Hours: L-D: 24H").assertIsDisplayed()
        // every downloaded fuel; the chosen one is marked
        rule.onNodeWithTag("fuel_price_g95").assertIsDisplayed()
        rule.onNodeWithTag("fuel_price_g98").assertIsDisplayed()
        rule.onNodeWithText("Diésel (on the map)").assertIsDisplayed()
        rule.onNodeWithText("1.249 €").assertIsDisplayed()
        // attribution under the prices, with the age of the data, and the "not official" note
        rule.onNodeWithText("Source: Ministry for the Ecological Transition and the Demographic Challenge · updated 5 min ago").assertIsDisplayed()
        rule.onNodeWithText("Price published by the Ministry; unofficial. Check it at the pump.").assertIsDisplayed()
    }

    @Test
    fun whileNavigatingTheCardOffersGoAddStopAndSaveEvenWithoutAPreview() {
        controller.onStationTap("1")
        show(navigating = true) // a trip is running: no route preview is open behind it
        rule.onNodeWithTag("fuel_go").assertIsDisplayed()
        rule.onNodeWithTag("fuel_save").assertIsDisplayed()
        rule.onNodeWithTag("fuel_add_stop").assertIsDisplayed()
    }

    private fun navController(result: suspend (LatLon) -> AddStopResult, asked: MutableList<LatLon>) = FuelCardController(
        repo, route, places, FuelCardState(), navigating = { true },
        navStops = { p -> asked += p; result(p) }, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun addStopWhileNavigatingReplansTheTripAndClosesTheCardWithoutTouchingThePreview() {
        val asked = mutableListOf<LatLon>()
        val nav = navController({ AddStopResult.ADDED }, asked)
        nav.onStationTap("1")
        nav.addStop(repsol)
        assertEquals(listOf(repsol.location), asked)
        assertNull(nav.card.station)
        assertFalse(nav.card.adding)
        assertFalse(route.state.active, "the route preview is not involved")
    }

    @Test
    fun aFailedAddStopWhileNavigatingKeepsTheCardAndSaysTheTripGoesOn() {
        val nav = navController({ AddStopResult.NO_ROUTE }, mutableListOf())
        nav.onStationTap("1")
        nav.addStop(repsol)
        assertEquals(AddStopResult.NO_ROUTE, nav.card.navNotice)
        assertEquals("1", nav.card.station?.id)
        val host = FuelCardHost(
            state = nav.card, mapFuelId = { null }, fuelName = { it }, updatedMillis = { null }, now = { now },
            onGo = nav::go, onAddStop = nav::addStop, onSave = nav::save, navigating = { true },
        )
        rule.setContent { MapasTheme(darkTheme = false) { com.qtekfun.ultimatemaps.search.SheetPanel(search, places, actions, route = route, fuel = host) } }
        rule.onNodeWithText("No route found through this stop. Your trip goes on as before.").assertIsDisplayed()
    }

    @Test
    fun onlyOneNavAddStopRunsAtATimeAndTheButtonSaysAdding() {
        val gate = kotlinx.coroutines.CompletableDeferred<AddStopResult>()
        val asked = mutableListOf<LatLon>()
        val nav = navController({ gate.await() }, asked)
        nav.onStationTap("1")
        nav.addStop(repsol)
        assertTrue(nav.card.adding)
        nav.addStop(repsol) // ignored while the first is running
        assertEquals(1, asked.size)
        val host = FuelCardHost(
            state = nav.card, mapFuelId = { null }, fuelName = { it }, updatedMillis = { null }, now = { now },
            onGo = nav::go, onAddStop = nav::addStop, onSave = nav::save, navigating = { true },
        )
        rule.setContent { MapasTheme(darkTheme = false) { com.qtekfun.ultimatemaps.search.SheetPanel(search, places, actions, route = route, fuel = host) } }
        rule.onNodeWithText("Adding stop…").assertIsDisplayed()
        gate.complete(AddStopResult.ADDED)
        assertFalse(nav.card.adding)
        assertNull(nav.card.station)
    }

    @Test
    fun goEndsTheRunningNavigationBeforeStartingTheRoute() {
        var ended = 0
        val navAware = FuelCardController(repo, route, places, FuelCardState(), beforeGo = { ended++ })
        navAware.onStationTap("1")
        navAware.go(repsol)
        assertEquals(1, ended)
    }

    @Test
    fun theChosenFuelIsListedFirst() {
        val rows = repsol.prices.entries.sortedWith(compareBy({ it.key != "diesel" }, { names[it.key] }))
        assertEquals("diesel", rows.first().key)
        controller.onStationTap("1")
        show()
        rule.onNodeWithTag("fuel_price_diesel").assertIsDisplayed()
    }

    @Test
    fun touchTargetsAreAtLeast48dp() {
        controller.onStationTap("1")
        startRoute()
        show()
        for (tag in listOf("fuel_go", "fuel_add_stop", "fuel_save", "fuel_close")) {
            rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun goStartsARouteToTheStationAndClosesTheCard() {
        controller.onStationTap("1")
        show()
        rule.onNodeWithTag("fuel_add_stop").assertDoesNotExist() // no active route: no "Add stop"
        rule.onNodeWithTag("fuel_go").performClick()
        assertTrue(route.state.active)
        assertEquals("Repsol", route.state.destination?.name)
        assertEquals(repsol.location, route.state.destination?.point)
        assertNull(controller.card.station)
        settle { route.state.status == RouteStatus.DONE }
        assertEquals(listOf(emptyList()), routeProfiles.toList()) // origin = current location, no via
    }

    @Test
    fun addStopInsertsItBeforeTheDestinationWhileARouteIsActive() {
        startRoute()
        controller.onStationTap("1")
        show()
        rule.onNodeWithTag("fuel_add_stop").performClick()
        assertEquals(listOf("Repsol"), route.state.stops.map { it.name })
        assertEquals("Destino", route.state.destination?.name)
        assertNull(controller.card.station)
        settle { routeProfiles.size == 2 && route.state.status == RouteStatus.DONE }
        assertEquals(listOf(repsol.location), routeProfiles.last())
    }

    @Test
    fun addingTheSameStationTwiceOrTheDestinationShowsWhyAndKeepsTheCard() {
        startRoute()
        controller.onStationTap("1")
        controller.addStop(repsol)
        controller.onStationTap("1")
        show()
        rule.onNodeWithTag("fuel_add_stop").performClick()
        assertEquals(StopResult.DUPLICATE, controller.card.notice)
        rule.onNodeWithText("This station is already a stop on the route.").assertIsDisplayed()
        assertEquals(1, route.state.stops.size)

        controller.card.close()
        route.start(cepsa.let { PlaceInfo("Cepsa", it.location) }) // new route: destination is Cepsa
        settle { route.state.status == RouteStatus.DONE }
        controller.onStationTap("2")
        controller.addStop(cepsa)
        assertEquals(StopResult.SAME_AS_DESTINATION, controller.card.notice)
        assertTrue(route.state.stops.isEmpty())
    }

    @Test
    fun saveReusesTheSavedPlacesAndTogglesTheLabel() {
        controller.onStationTap("1")
        show()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithTag("fuel_save").performClick()
        rule.waitUntil(30_000) { controller.card.saved }
        rule.onNodeWithText("Saved").assertIsDisplayed()
        rule.onNodeWithText("Saved in Favorites").assertIsDisplayed()
        // the saved place is the station (name, address, category)
        settle { places.state.rows.isNotEmpty() }
        val saved = places.state.rows.single().place
        assertEquals("Repsol", saved.name)
        rule.onNodeWithTag("fuel_save").performClick()
        rule.waitUntil(30_000) { !controller.card.saved }
    }

    @Test
    fun aTapWhilePickingTheOriginPicksTheStationInsteadOfOpeningTheCard() {
        startRoute()
        route.beginPickOrigin()
        controller.onStationTap("2")
        assertNull(controller.card.station)
        assertEquals("Cepsa", (route.state.origin as com.qtekfun.ultimatemaps.route.RouteOrigin.Picked).label)
        assertFalse(route.state.pickingOrigin)
    }

    @Test
    fun anUnknownStationIsIgnoredAndCloseDismissesTheCard() {
        controller.onStationTap("nope")
        assertNull(controller.card.station)
        assertEquals(0, opened)
        controller.onStationTap("2")
        show()
        rule.onNodeWithTag("fuel_close").performClick()
        rule.onNodeWithTag("fuel_card").assertDoesNotExist()
    }

    @Test
    fun agoIsReportedInTheRightUnit() {
        assertEquals(Ago(AgoUnit.UNKNOWN), agoOf(null, now))
        assertEquals(Ago(AgoUnit.NOW), agoOf(now - 30_000, now))
        assertEquals(Ago(AgoUnit.NOW), agoOf(now + 10_000, now)) // clock skew never goes negative
        assertEquals(Ago(AgoUnit.MINUTES, 59), agoOf(now - 59 * 60_000L, now))
        assertEquals(Ago(AgoUnit.HOURS, 2), agoOf(now - 150 * 60_000L, now))
        assertEquals(Ago(AgoUnit.DAYS, 3), agoOf(now - 3 * 24 * 3_600_000L - 5, now))
    }
}
