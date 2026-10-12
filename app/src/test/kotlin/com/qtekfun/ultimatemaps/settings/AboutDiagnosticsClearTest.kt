package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Clearing the diagnostic notes is irreversible, so it sits behind a confirmation (Settings, About, Diagnostics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AboutDiagnosticsClearTest {
    @get:Rule
    val rule = createComposeRule()

    private var clears = 0

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AboutSettingsSection(
                    AboutSettingsEnv(diagnostics = { "note one\nnote two" }, clearDiagnostics = { clears++ }),
                )
            }
        }
    }

    private fun openNotes() {
        rule.onNodeWithTag("about_diag_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("about_diag_text").assertIsDisplayed()
    }

    @Test fun tappingClearAsksFirstAndDoesNotClear() {
        show()
        openNotes()
        rule.onNodeWithTag("about_diag_clear").performScrollTo().performClick()
        rule.onNodeWithTag("about_diag_clear_confirm_dialog").assertIsDisplayed()
        assertEquals(0, clears)
        rule.onNodeWithTag("about_diag_text").assertExists()
    }

    @Test fun cancelKeepsTheNotes() {
        show()
        openNotes()
        rule.onNodeWithTag("about_diag_clear").performScrollTo().performClick()
        rule.onNodeWithTag("about_diag_clear_confirm_no").performClick()
        rule.onNodeWithTag("about_diag_clear_confirm_dialog").assertDoesNotExist()
        assertEquals(0, clears)
        rule.onNodeWithTag("about_diag_text").assertIsDisplayed()
        rule.onNodeWithTag("about_diag_clear").assertExists()
    }

    @Test fun confirmClearsTheNotes() {
        show()
        openNotes()
        rule.onNodeWithTag("about_diag_clear").performScrollTo().performClick()
        rule.onNodeWithTag("about_diag_clear_confirm_yes").performClick()
        rule.onNodeWithTag("about_diag_clear_confirm_dialog").assertDoesNotExist()
        assertEquals(1, clears)
        rule.onNodeWithTag("about_diag_clear").assertDoesNotExist()
    }

    @Test @Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun theDialogButtonsAreReachableInLandscape() {
        show()
        openNotes()
        rule.onNodeWithTag("about_diag_clear").performScrollTo().performClick()
        rule.onNodeWithTag("about_diag_clear_confirm_yes").assertIsDisplayed()
        rule.onNodeWithTag("about_diag_clear_confirm_no").assertIsDisplayed()
        assertEquals(0, clears)
    }
}
