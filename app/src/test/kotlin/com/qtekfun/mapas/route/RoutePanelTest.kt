package com.qtekfun.mapas.route

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.search.CoreMaps
import com.qtekfun.mapas.search.InstalledRegions
import com.qtekfun.mapas.ui.theme.MapasTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val line = listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1))
    private var outcome = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 12_345.0, 1_500.0))
    private val profiles = mutableListOf<RoutingProfile>()

    private val route = RoutePreviewController(
        scope, Dispatchers.IO,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine {
                override fun routeDetailed(request: com.qtekfun.mapas.core.routing.RouteRequest): RouteOutcome {
                    profiles += request.profile
                    return outcome
                }
                override fun route(request: com.qtekfun.mapas.core.routing.RouteRequest) = outcome.plan
                override fun close() = Unit
            }
        },
        userLocation = { LatLon(40.0, -3.0) }, showRoute = {}, clearRoute = {}, clock = { 0L },
        log = { _, _, _ -> },
    )

    @After fun tearDown() = scope.cancel()

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) { RoutePanel(route, onUseLocation = {}, originSearch = {}) }
    }

    /**
     * Espera (sin tocar la interfaz) a que el controlador termine el cálculo en su hilo de fondo. Los tests componen la
     * pantalla DESPUÉS, para que la primera composición lea ya el estado final: esperar a una recomposición provocada
     * desde otro hilo hacía que el test fallase 1 de cada 2 veces en la suite completa (diagnóstico: el estado era
     * ERROR pero la pantalla seguía en «Calculando…»).
     */
    private fun settle(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "el cálculo no terminó en 5 s: ${route.state.status}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun showsSummaryProfilesAndClosesTheRoute() {
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        settle { route.state.status == RouteStatus.DONE }
        show()
        rule.onNodeWithText("To Plaza Mayor").assertIsDisplayed()
        rule.onNodeWithTag("route_summary").assertIsDisplayed()
        rule.onNodeWithText("12.3 km · 25 min").assertIsDisplayed()

        // Cambiar de perfil: el clic y el perfil están en el hilo principal (determinista); el cálculo, en segundo plano.
        rule.onNodeWithTag("profile_foot").performClick()
        settle { profiles.size == 2 && route.state.status == RouteStatus.DONE }
        assertEquals(listOf(RoutingProfile.CAR, RoutingProfile.FOOT), profiles)
        rule.onNodeWithTag("avoid_motorways").assertIsNotEnabled() // car-only option

        rule.onNodeWithTag("route_close").performClick()
        assertEquals(false, route.state.active)
    }

    @Test
    fun explainsNeedMoreMaps() {
        outcome = RouteOutcome(RouteCode.NEED_MORE_MAPS, null)
        route.start(PlaceInfo("Far", LatLon(10.0, 10.0)))
        settle { route.state.status == RouteStatus.ERROR }
        show()
        rule.onNodeWithText("Maps are missing for this route. Download the regions it crosses.").assertIsDisplayed()
    }
}
