package com.qtekfun.ultimatemaps.cameras

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.core.cameras.AlertBannerState
import com.qtekfun.ultimatemaps.core.cameras.AlertCategory
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The visual alert: icon, text, distance, limit, accessibility, glove size, and the English and Spanish strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CameraAlertBannerTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf<AlertBannerState?>(null)
    private var glove by mutableStateOf(false)

    private fun show() {
        rule.setContent { MapasTheme(darkTheme = false) { CameraAlertChip(state, glove = glove) } }
    }

    private fun description(): String? =
        rule.onNodeWithTag("camera_alert").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    @Test fun drawsNothingWithoutAnAlert() {
        show()
        rule.onNodeWithTag("camera_alert").assertDoesNotExist()
    }

    @Test fun showsTheCameraIconTheDistanceAndTheLimit() {
        state = AlertBannerState(AlertCategory.FIXED_CAMERA, 400, 70)
        show()
        rule.onNodeWithTag("camera_alert").assertIsDisplayed()
        rule.onNodeWithTag("camera_alert_icon", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("camera_alert_title", useUnmergedTree = true).assertTextEquals("Speed camera ahead")
        rule.onNodeWithTag("camera_alert_distance", useUnmergedTree = true).assertTextEquals("400 m")
        rule.onNodeWithTag("camera_alert_limit", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("Speed camera ahead, in 400 m, limit 70 km/h", description(), "one spoken description for screen readers")
    }

    @Test fun withoutAKnownLimitThereIsNoSignAndTheDescriptionSaysNothingAboutIt() {
        state = AlertBannerState(AlertCategory.FIXED_CAMERA, 1200, null)
        show()
        rule.onNodeWithTag("camera_alert_limit", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("camera_alert_distance", useUnmergedTree = true).assertTextEquals("1.2 km")
        assertEquals("Speed camera ahead, in 1.2 km", description())
    }

    @Test fun followsTheStateAndGoesAway() {
        state = AlertBannerState(AlertCategory.FIXED_CAMERA, 400, null)
        show()
        state = AlertBannerState(AlertCategory.FIXED_CAMERA, 300, null)
        rule.waitForIdle()
        rule.onNodeWithTag("camera_alert_distance", useUnmergedTree = true).assertTextEquals("300 m")
        state = null
        rule.waitForIdle()
        rule.onNodeWithTag("camera_alert").assertDoesNotExist()
    }

    @Test fun otherCategoriesUseTheirOwnWording() {
        state = AlertBannerState(AlertCategory.MOBILE_ZONE, 800, null)
        show()
        rule.onNodeWithTag("camera_alert_title", useUnmergedTree = true).assertTextEquals("Mobile radars may operate ahead")
        state = AlertBannerState(AlertCategory.V16, 500, null)
        rule.waitForIdle()
        rule.onNodeWithTag("camera_alert_title", useUnmergedTree = true).assertTextEquals("Stopped vehicle ahead (V16)")
    }

    @Test fun gloveModeIsBigger() {
        state = AlertBannerState(AlertCategory.FIXED_CAMERA, 400, 70)
        glove = true
        show()
        rule.onNodeWithTag("camera_alert").assertHeightIsAtLeast(72.dp)
        glove = false
        rule.waitForIdle()
        rule.onNodeWithTag("camera_alert").assertHeightIsAtLeast(52.dp)
    }

    @Test fun theHostVersionReadsTheProvidedFlowAndDrawsNothingWithoutOne() {
        val flow = MutableStateFlow<AlertBannerState?>(null)
        rule.setContent {
            MapasTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAlertBanner provides flow) { CameraAlertBanner() }
            }
        }
        rule.onNodeWithTag("camera_alert").assertDoesNotExist()
        flow.value = AlertBannerState(AlertCategory.SECTION, 600, 90)
        rule.waitForIdle()
        rule.onNodeWithTag("camera_alert_title", useUnmergedTree = true).assertTextEquals("Average-speed section ahead")
    }

    @Test fun theStringsExistInEnglishAndSpanishWithTheSameKeys() {
        fun keys(f: String) = Regex("<string name=\"([^\"]+)\"").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        val en = keys("src/main/res/values/strings_camera_alerts.xml")
        val es = keys("src/main/res/values-es/strings_camera_alerts.xml")
        assertEquals(en, es)
        assertTrue(en.size >= 10)
    }
}
