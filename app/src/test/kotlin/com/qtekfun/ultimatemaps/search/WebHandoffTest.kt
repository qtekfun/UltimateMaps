package com.qtekfun.ultimatemaps.search

import android.content.ComponentName
import android.content.Intent
import com.qtekfun.ultimatemaps.link.LinkHandler
import com.qtekfun.ultimatemaps.link.LinkOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebHandoffTest {
    @Test
    fun urlIsTheGoogleMapsSearchUrlWithTheQueryEncoded() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=calle%20mayor%2012%2C%20%C3%81vila",
            WebHandoff.searchUrl("  calle mayor 12, Ávila "),
        )
        assertEquals("https://www.google.com/maps/search/?api=1&query=a%26b%3Dc%23d", WebHandoff.searchUrl("a&b=c#d"))
    }

    @Test
    fun blankTextHasNoUrlAndLongTextIsCapped() {
        assertNull(WebHandoff.searchUrl("   "))
        val url = WebHandoff.searchUrl("x".repeat(1000))!!
        assertEquals(WebHandoff.MAX_QUERY, url.substringAfter("query=").length)
    }

    @Test
    fun theUrlIsReadBackByOurOwnLinkHandlerAsTheSameSearch() {
        val outcome = assertIs<LinkOutcome.Search>(LinkHandler.handle(WebHandoff.searchUrl("Plaza de España, Sevilla")))
        assertEquals("Plaza de España, Sevilla", outcome.query)
    }

    @Test
    fun browserIntentUsesTheBrowserSelectorSoItNeverLoopsBackToThisApp() {
        val intent = WebHandoff.browserIntent("https://www.google.com/maps/search/?api=1&query=x")
        assertEquals("https://www.google.com/maps/search/?api=1&query=x", intent.dataString)
        assertEquals(Intent.ACTION_MAIN, intent.selector?.action ?: intent.action)
        assertTrue(intent.selector?.categories?.contains(Intent.CATEGORY_APP_BROWSER) == true || intent.categories?.contains(Intent.CATEGORY_APP_BROWSER) == true)
    }

    @Test
    fun fallbackChooserExcludesThisActivity() {
        val own = ComponentName("com.qtekfun.ultimatemaps", "com.qtekfun.ultimatemaps.MainActivity")
        val chooser = WebHandoff.chooserIntent("https://www.google.com/maps/search/?api=1&query=x", own)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
        assertEquals(own, excluded?.single())
    }
}
