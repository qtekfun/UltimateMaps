package com.qtekfun.ultimatemaps.cameras

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.core.cameras.AlertBannerState
import com.qtekfun.ultimatemaps.core.cameras.AlertCategory
import com.qtekfun.ultimatemaps.nav.NavActions
import com.qtekfun.ultimatemaps.nav.NavPhase
import com.qtekfun.ultimatemaps.nav.NavScreen
import com.qtekfun.ultimatemaps.nav.NavUi
import com.qtekfun.ultimatemaps.nav.navState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The alert chip on the navigation screen: under the maneuver banner, independent of the Mute button, bigger in glove mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CameraAlertOnNavScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val flow = MutableStateFlow<AlertBannerState?>(null)

    private fun show(ui: NavUi) {
        rule.setContent { CompositionLocalProvider(LocalAlertBanner provides flow) { NavScreen(ui, NavActions(), dark = false) } }
    }

    private fun driving(voiceOn: Boolean = true, glove: Boolean = false) =
        NavUi(phase = NavPhase.ON_ROUTE, nav = navState(), voiceOn = voiceOn, glove = glove, etaMillis = 1_700_000_000_000L)

    @Test fun theChipAppearsOnTheNavigationScreenWhileTheVoiceIsMuted() {
        show(driving(voiceOn = false))
        rule.onNodeWithTag("camera_alert").assertDoesNotExist()
        flow.value = AlertBannerState(AlertCategory.FIXED_CAMERA, 500, 90)
        rule.waitForIdle()
        rule.onNodeWithTag("nav_banner").assertIsDisplayed()
        rule.onNodeWithTag("camera_alert").assertIsDisplayed()
        rule.onNodeWithTag("camera_alert_distance", useUnmergedTree = true).assertTextEquals("500 m")
        rule.onNodeWithTag("nav_mute").assertIsDisplayed() // muted, and the alert is still there
    }

    @Test fun gloveModeMakesItBig() {
        flow.value = AlertBannerState(AlertCategory.FIXED_CAMERA, 500, 90)
        show(driving(glove = true))
        rule.onNodeWithTag("camera_alert").assertHeightIsAtLeast(72.dp)
    }
}
