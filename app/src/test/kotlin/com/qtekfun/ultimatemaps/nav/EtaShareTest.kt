package com.qtekfun.ultimatemaps.nav

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sharing the ETA as plain text: the text, the share button of the navigation screen, and what is left out of it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EtaShareTest {
    @get:Rule
    val rule = createComposeRule()

    private val utc = TimeZone.getTimeZone("UTC")

    // 2023-11-14 22:13:20 UTC; navState() is 12.4 km and 1100 s (18 min) away.
    private val eta = 1_700_000_000_000L

    private fun ui(etaMillis: Long = eta) = NavUi(phase = NavPhase.ON_ROUTE, nav = navState(), etaMillis = etaMillis)

    private fun resources() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test fun `the text has the arrival, the distance and the time left, and nothing else`() {
        // Newer ICU writes a narrow no-break space before "PM": compare with plain spaces.
        val text = EtaShare.text(resources(), ui(), Locale.US, utc)?.replace('\u202f', ' ')?.replace('\u00a0', ' ')
        assertEquals("I will arrive at about 10:13 PM (12.4 km, 18 min to go).", text)
        // No place, no position and no link: the navigation state holds a position (40.0, -3.0) that must not appear.
        assertFalse("40.0" in text!! || "-3.0" in text || "http" in text || "geo:" in text)
    }

    @Test @Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
    fun `in Spanish it reads naturally`() {
        val text = EtaShare.text(resources(), ui(), Locale("es", "ES"), utc)
        assertEquals("Llegaré hacia las 22:13 (12,4 km, faltan 18 min).", text)
    }

    @Test fun `there is nothing to share without an estimate`() {
        assertNull(EtaShare.text(resources(), ui(etaMillis = 0L), Locale.US, utc))
        assertNull(EtaShare.text(resources(), NavUi(phase = NavPhase.ON_ROUTE, etaMillis = eta), Locale.US, utc))
    }

    @Test fun `the intent is a plain text chooser`() {
        val chooser = EtaShare.intent("hello")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("hello", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun `the navigation screen offers Share ETA when there is an estimate and calls back`() {
        var shared = 0
        rule.setContent { NavScreen(ui(), NavActions(onShareEta = { shared++ }), dark = false) }
        rule.onNodeWithTag("nav_share_eta").assertIsDisplayed()
        val text = rule.onNodeWithTag("nav_share_eta").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        assertEquals("Share ETA", text)
        rule.onNodeWithTag("nav_share_eta").performClick()
        assertEquals(1, shared)
        rule.onNodeWithTag("nav_attribution").assertIsDisplayed() // the attribution stays next to it
    }

    @Test fun `the button is hidden while the estimate is unknown`() {
        rule.setContent { NavScreen(ui(etaMillis = 0L), NavActions(), dark = false) }
        rule.onNodeWithTag("nav_share_eta").assertDoesNotExist()
        assertTrue(true)
    }
}
