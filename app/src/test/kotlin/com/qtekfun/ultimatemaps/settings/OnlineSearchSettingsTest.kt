package com.qtekfun.ultimatemaps.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import com.qtekfun.ultimatemaps.core.search.InMemoryOnlineSearchSettings
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OnlineSearchSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val store = InMemoryOnlineSearchSettings()
    private var changes = 0

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) { OnlineSearchSection(OnlineSearchSettingsEnv(store) { changes++ }) }
    }

    @Test fun theSwitchIsOffByDefaultAndTheTextSaysWhatIsSent() {
        assertFalse(store.enabled)
        show()
        rule.onNodeWithTag("online_search_card").assertIsDisplayed()
        rule.onNodeWithText("Search online when the map has no result (Photon, OpenStreetMap data)").assertIsDisplayed()
        rule.onNodeWithTag("online_search_credit").assertIsDisplayed()
    }

    @Test fun turningTheSwitchOnStoresItAndTellsTheHost() {
        show()
        rule.onNodeWithTag("online_search_switch").performClick()
        assertTrue(store.enabled)
        assertEquals(1, changes)
        rule.onNodeWithTag("online_search_switch").performClick()
        assertFalse(store.enabled)
        assertEquals(2, changes)
    }

    @Test fun anHttpsAddressIsSavedAndResetGoesBackToTheDefault() {
        show()
        rule.onNodeWithTag("online_search_url_field").performTextInput("https://photon.example.org")
        rule.onNodeWithTag("online_search_url_save").performClick()
        assertEquals("https://photon.example.org", store.serverUrl)
        rule.onNodeWithTag("online_search_url_reset").performClick()
        assertEquals("", store.serverUrl)
    }

    @Test fun anInsecureAddressIsRefusedAndNotStored() {
        show()
        rule.onNodeWithTag("online_search_url_field").performTextInput("http://photon.example.org")
        rule.onNodeWithTag("online_search_url_save").performClick()
        assertEquals("", store.serverUrl)
        rule.onNodeWithTag("online_search_url_message").assertIsDisplayed()
        rule.onNodeWithTag("online_search_url_field").performTextClearance()
        rule.onNodeWithTag("online_search_url_save").performClick() // blank = default
        assertEquals("", store.serverUrl)
    }
}
