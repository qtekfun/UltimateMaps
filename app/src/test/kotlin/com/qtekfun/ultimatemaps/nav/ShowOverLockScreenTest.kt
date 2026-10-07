package com.qtekfun.ultimatemaps.nav

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** While a trip runs the route stays visible over the lock screen; it goes back to normal when the trip ends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowOverLockScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shownOverTheLockScreenOnlyWhileNavigating() {
        val navigating = mutableStateOf(false)
        rule.setContent { ShowOverLockScreen(navigating.value) }
        rule.waitForIdle()
        assertEquals(false, shadowOf(rule.activity).showWhenLocked, "normal behaviour before navigating")

        navigating.value = true
        rule.waitForIdle()
        assertEquals(true, shadowOf(rule.activity).showWhenLocked, "visible over the lock screen")

        navigating.value = false
        rule.waitForIdle()
        assertEquals(false, shadowOf(rule.activity).showWhenLocked, "back to normal when the trip ends")
    }
}
