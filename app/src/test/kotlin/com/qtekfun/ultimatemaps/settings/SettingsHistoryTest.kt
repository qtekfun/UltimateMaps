package com.qtekfun.ultimatemaps.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.search.HistorySettings
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
class SettingsHistoryTest {
    @get:Rule
    val rule = createComposeRule()

    private class Memory(override var enabled: Boolean = true) : HistorySettings

    private val settings = Memory()
    private var clears = 0

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) { HistorySection(HistorySettingsEnv(settings, clear = { clears++ })) }
    }

    @Test fun theSwitchStartsOnAndTurningItOffClearsWhatWasStored() {
        show()
        rule.onNodeWithTag("history_switch").assertIsDisplayed()
        assertTrue(settings.enabled)
        rule.onNodeWithTag("history_switch").performClick()
        assertFalse(settings.enabled)
        assertEquals(1, clears)
        rule.onNodeWithTag("history_switch").performClick()
        assertTrue(settings.enabled)
        assertEquals(1, clears) // turning it on deletes nothing
    }

    @Test fun theClearButtonDeletesAndConfirms() {
        show()
        rule.onNodeWithTag("history_clear").performClick()
        assertEquals(1, clears)
        rule.onNodeWithTag("history_cleared").assertIsDisplayed()
        assertTrue(settings.enabled) // clearing does not turn the history off
    }
}
