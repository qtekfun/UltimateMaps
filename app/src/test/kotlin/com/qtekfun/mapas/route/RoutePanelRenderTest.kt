package com.qtekfun.mapas.route

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteOptions
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import com.qtekfun.mapas.nav.NavLaunchState
import com.qtekfun.mapas.nav.NavStartHost
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.search.CoreMaps
import com.qtekfun.mapas.search.InstalledRegions
import com.qtekfun.mapas.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * Renders the route panel to PNG files (Robolectric native graphics) so the visual hierarchy can be reviewed without a
 * device. It runs only when the RENDER_ROUTE_PANEL environment variable names the output directory; otherwise it is
 * skipped. File names start with RENDER_PREFIX (default "after").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RoutePanelRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val line = listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1))

    private val route = RoutePreviewController(
        scope, Dispatchers.IO,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                override fun routeDetailed(request: RouteRequest) = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(line, 12_345.0, 1_500.0))
                override fun route(request: RouteRequest) = routeDetailed(request).plan
                override fun close() = Unit
            }
        },
        userLocation = { LatLon(40.0, -3.0) }, showRoute = {}, clearRoute = {}, clock = { 0L },
        log = { _, _, _ -> },
    )

    @After fun tearDown() = scope.cancel()

    /** Waits (off the UI) for the background computation; the panel is composed only afterwards. */
    private fun settle() {
        val end = System.nanoTime() + 5_000_000_000L
        while (route.state.status == RouteStatus.COMPUTING || route.state.status == RouteStatus.IDLE) {
            check(System.nanoTime() < end) { "the route did not finish" }
            Thread.sleep(10)
        }
    }

    private val outDir get() = System.getenv("RENDER_ROUTE_PANEL")
    private val prefix get() = System.getenv("RENDER_PREFIX") ?: "after"

    private val darkTheme = mutableStateOf(false)

    private fun render(name: String, expandOptions: Boolean = false) {
        for (dark in listOf(false, true)) {
            darkTheme.value = dark
            rule.waitForIdle()
            if (expandOptions && !dark && rule.onAllNodesWithTag("route_options_toggle").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("route_options_toggle").performClick()
            }
            rule.waitForIdle()
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bmp.width > 100 && bmp.height > 100)
            File(outDir, "$prefix-$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Back to the default (collapsed) so that the next states show it.
        if (expandOptions && rule.onAllNodesWithTag("route_options_toggle").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("route_options_toggle").performClick()
            rule.waitForIdle()
        }
    }

    @Test fun renderAll() {
        assumeTrue(outDir != null)
        File(outDir!!).mkdirs()
        rule.setContent {
            val dark = darkTheme.value
            MapasTheme(darkTheme = dark) {
                Box(Modifier.background(if (dark) Color(0xFF1C1C1E) else Color.White).padding(16.dp)) {
                    RoutePanel(
                        route, onUseLocation = {}, originSearch = { Box(Modifier.padding(vertical = 8.dp)) },
                        navStart = NavStartHost(NavLaunchState(), {}, {}),
                    )
                }
            }
        }
        route.start(PlaceInfo("Plaza Mayor", LatLon(40.1, -3.1)))
        settle()
        render("ready-car")
        render("ready-car-options-open", expandOptions = true)
        route.setOptions(RouteOptions(avoidTolls = true, avoidFerries = true)); settle()
        render("ready-car-avoiding")
        route.setOptions(RouteOptions()); settle()
        route.addStop(PlaceInfo("Museo del Prado", LatLon(40.02, -3.02)))
        route.addStop(PlaceInfo("Parque del Retiro", LatLon(40.05, -3.05)))
        settle()
        render("ready-two-stops")
        route.setProfile(RoutingProfile.FOOT); settle()
        route.setOptions(RouteOptions(avoidMotorways = true, avoidFerries = true)); settle()
        render("walking-options-open", expandOptions = true)
        route.state.status = RouteStatus.COMPUTING
        render("computing")
        route.state.status = RouteStatus.ERROR
        route.state.error = RouteError.ROUTE_NOT_FOUND
        render("error")
        route.state.status = RouteStatus.NEEDS_ORIGIN
        render("needs-origin")
        route.beginPickOrigin()
        render("picking-origin")
    }
}
