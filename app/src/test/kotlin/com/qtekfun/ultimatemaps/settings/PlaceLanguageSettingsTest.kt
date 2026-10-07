package com.qtekfun.ultimatemaps.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.search.PlaceLanguagePref
import com.qtekfun.ultimatemaps.search.MemoryPlaceLanguageStore
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlaceLanguageSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val store = MemoryPlaceLanguageStore()

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) { PlaceLanguageSection(PlaceLanguageSettingsEnv(store)) }
    }

    @Test fun automaticIsSelectedByDefault() {
        show()
        rule.onNodeWithTag("place_language_card").assertIsDisplayed()
        rule.onNodeWithTag("place_language_auto").assertIsSelected()
        rule.onNodeWithTag("place_language_local").assertIsNotSelected()
    }

    @Test fun choosingARowStoresItAndMovesTheSelection() {
        show()
        rule.onNodeWithTag("place_language_en").performClick()
        assertEquals(PlaceLanguagePref.EN, store.preference)
        rule.onNodeWithTag("place_language_en").assertIsSelected()
        rule.onNodeWithTag("place_language_auto").assertIsNotSelected()
        rule.onNodeWithTag("place_language_local").performClick()
        assertEquals(PlaceLanguagePref.LOCAL, store.preference)
        rule.onNodeWithTag("place_language_local").assertIsSelected()
    }

    @Test fun theStoredChoiceIsShownOnOpening() {
        store.preference = PlaceLanguagePref.ES
        show()
        rule.onNodeWithTag("place_language_es").assertIsSelected()
    }
}
