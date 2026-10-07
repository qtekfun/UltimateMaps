package com.qtekfun.mapas.nav

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The dark navigation banner needs white status-bar icons, and the previous look must come back afterwards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusBarIconsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun lightIcons(): Boolean {
        val window = rule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }

    @Test
    fun whiteIconsWhileNavigatingAndRestoredAfterwards() {
        val window = rule.activity.window
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true // light map theme
        val navigating = mutableStateOf(false)
        rule.setContent { if (navigating.value) LightStatusBarIcons() }
        rule.waitForIdle()
        assertEquals(true, lightIcons(), "untouched before navigating")

        navigating.value = true
        rule.waitForIdle()
        assertEquals(false, lightIcons(), "white icons over the dark banner")

        navigating.value = false
        rule.waitForIdle()
        assertEquals(true, lightIcons(), "restored when navigation ends")
    }
}
