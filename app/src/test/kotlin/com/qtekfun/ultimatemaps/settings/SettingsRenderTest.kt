package com.qtekfun.ultimatemaps.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguagePref
import com.qtekfun.ultimatemaps.ui.MapScreen
import com.qtekfun.ultimatemaps.ui.MapScreenState
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
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
 * Renders the Settings hub, some category screens and the map buttons to PNG files (Robolectric native graphics) so the
 * design can be reviewed without a phone. Runs only when RENDER_SETTINGS_HUB names the output directory; otherwise it is
 * skipped. File names start with RENDER_PREFIX (default "after").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val outDir: File? = System.getenv("RENDER_SETTINGS_HUB")?.takeIf { it.isNotBlank() }?.let(::File)
    private val prefix = System.getenv("RENDER_PREFIX")?.takeIf { it.isNotBlank() } ?: "after"
    private val dark = mutableStateOf(false)
    private val f = HubFixture()

    private fun shoot(name: String) {
        for (isDark in listOf(false, true)) {
            dark.value = isDark
            rule.waitForIdle()
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue(bmp.width > 100 && bmp.height > 100)
            File(outDir!!, "$prefix-$name-${if (isDark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun showSettings(initial: String? = null) {
        rule.setContent { MapasTheme(darkTheme = dark.value) { SettingsScreen(f.env, onBack = {}, initialCategory = initial) } }
    }

    private var mapState by mutableStateOf(MapScreenState())
    private var glove by mutableStateOf(false)

    private fun showMap() {
        rule.setContent {
            MapasTheme(darkTheme = dark.value, gloveMode = glove) {
                MapScreen(mapState, onLocate = {}, onResetNorth = {}) { Box(Modifier.fillMaxSize().background(Color(0xFFCFE3C2))) }
            }
        }
    }

    @Test fun renderAll() {
        assumeTrue(outDir != null)
        outDir!!.mkdirs()
        f.navStore.update { it.copy(voiceEnabled = true, voiceLanguage = VoiceLanguagePref.ES, volumePercent = 100) }
        f.fuelStore.update { it.copy(enabled = true, downloadedFuels = setOf("glp", "gnc", "gasoleo_a"), mapFuel = "glp") }
        showSettings()
        shoot("hub")
        rule.onNodeWithTag("settings_row_navigation").performClick()
        shoot("category-navigation")
        rule.onNodeWithTag("settings_back").performClick()
        rule.onNodeWithTag("settings_row_fuel").performClick()
        rule.onNodeWithTag("fuel_advanced_header").performScrollTo().performClick()
        shoot("category-fuel-advanced-open")
        rule.onNodeWithTag("settings_back").performClick()
        rule.onNodeWithTag("settings_row_data").performClick()
        shoot("category-data")
    }

    @Test fun renderMapButtons() {
        assumeTrue(outDir != null)
        outDir!!.mkdirs()
        mapState = MapScreenState().apply { onCamera(CameraState(LatLon(40.4, -3.7), 14.0)) }
        showMap()
        shoot("map-buttons-north-up")
        mapState = MapScreenState().apply { onCamera(CameraState(LatLon(40.4, -3.7), 14.0, bearing = 40.0)) }
        shoot("map-buttons-rotated")
        glove = true
        shoot("map-buttons-rotated-glove")
    }
}
