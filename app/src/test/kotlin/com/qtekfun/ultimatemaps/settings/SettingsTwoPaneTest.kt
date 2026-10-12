package com.qtekfun.ultimatemaps.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Unfolded foldable (about 850 dp wide): the category list and the open category are on screen together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w852dp-h883dp-xxhdpi")
class SettingsTwoPaneTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val f = HubFixture()

    private fun show() {
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(f.env, onBack = { f.exits++ }) } }
    }

    @Test fun theFirstCategoryIsOpenBesideTheListWithoutATap() {
        show()
        rule.onNodeWithTag("settings_hub").assertIsDisplayed()
        rule.onNodeWithTag("nav_voice_card").assertIsDisplayed()
    }

    @Test fun tappingARowSwitchesThePaneAndTheListStaysVisible() {
        show()
        rule.onNodeWithTag("settings_row_network").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings_hub").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag("nav_voice_card")).fetchSemanticsNodes().isEmpty())
    }
}
