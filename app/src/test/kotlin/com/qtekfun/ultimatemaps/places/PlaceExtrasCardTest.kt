package com.qtekfun.ultimatemaps.places

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.PlaceExtras
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.core.search.Wheelchair
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The place card: Plus Code always, phone / website / wheelchair / hours only when the data has them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlaceExtrasCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val point = LatLon(47.0000625, 8.0000625) // the Plus Code 8FVC2222+22 (published encoding vector)

    // 2026-10-05 is a Monday; fixed so nothing reads the clock.
    private val mondayNoon = LocalDateTime.of(2026, 10, 5, 12, 0)
    private val mondayNight = LocalDateTime.of(2026, 10, 5, 23, 0)

    private val dialed = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun show(extras: PlaceExtras?, now: LocalDateTime = mondayNoon) {
        rule.setContent {
            MapasTheme(darkTheme = false) {
                PlaceCard(
                    info = PlaceInfo("Cafe", point, "Main street", "cafe", extras),
                    saved = false, onSave = {}, onRoute = {}, onShare = {}, onClose = {},
                    onDial = { dialed += it }, onOpenWebsite = { opened += it }, now = { now },
                )
            }
        }
    }

    private fun text(tag: String): String? =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun absent(tag: String) = rule.onNodeWithTag(tag).assertDoesNotExist()

    @Test fun thePlusCodeIsShownOnEveryCardAndNothingElseWithoutExtras() {
        show(null)
        assertEquals("Plus Code: 8FVC2222+22", text("place_plus_code"))
        for (tag in listOf("place_phone", "place_website", "place_wheelchair", "place_hours", "place_open_state")) absent(tag)
    }

    @Test fun anEmptyExtrasObjectShowsNoRows() {
        show(PlaceExtras(phone = " ", website = "", openingHours = "  "))
        absent("place_phone"); absent("place_website"); absent("place_hours")
    }

    @Test @Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
    fun theSpanishCardSaysPlusCodeToo() {
        show(null)
        assertEquals("Plus Code: 8FVC2222+22", text("place_plus_code"))
    }

    @Test fun tappingThePhoneOpensTheDialerWithoutDialling() {
        show(PlaceExtras(phone = "+34 912 345 678; +34 600 000 000"))
        assertEquals("+34 912 345 678", text("place_phone"))
        assertEquals(
            "Call +34 912 345 678",
            rule.onNodeWithTag("place_phone").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
        )
        rule.onNodeWithTag("place_phone").performClick()
        assertEquals(listOf("tel:+34912345678"), dialed)
    }

    @Test fun aPhoneWithoutDigitsIsNotShown() {
        show(PlaceExtras(phone = "ask at the bar"))
        absent("place_phone")
    }

    @Test fun theWebsiteOpensAsHttpsAndOnlyForSafeSchemes() {
        show(PlaceExtras(website = "www.example.com/menu"))
        assertEquals("example.com/menu", text("place_website"))
        rule.onNodeWithTag("place_website").performClick()
        assertEquals(listOf("https://www.example.com/menu"), opened)
    }

    @Test fun aHostileWebsiteTagIsNotShown() {
        show(PlaceExtras(website = "javascript:alert(1)"))
        absent("place_website")
        assertTrue(opened.isEmpty())
    }

    @Test fun wheelchairYes() {
        show(PlaceExtras(wheelchair = Wheelchair.YES))
        assertEquals("Wheelchair accessible", text("place_wheelchair"))
    }

    @Test fun wheelchairLimited() {
        show(PlaceExtras(wheelchair = Wheelchair.LIMITED))
        assertEquals("Partly wheelchair accessible", text("place_wheelchair"))
    }

    @Test fun wheelchairNo() {
        show(PlaceExtras(wheelchair = Wheelchair.NO))
        assertEquals("Not wheelchair accessible", text("place_wheelchair"))
    }

    @Test fun simpleHoursShowTheTextAndOpenNow() {
        show(PlaceExtras(openingHours = "Mo-Fr 08:00-20:00; Sa 09:00-14:00"))
        assertEquals("Hours: Mo-Fr 08:00-20:00; Sa 09:00-14:00", text("place_hours"))
        assertEquals("Open now", text("place_open_state"))
    }

    @Test fun closedOutsideTheHours() {
        show(PlaceExtras(openingHours = "Mo-Fr 08:00-20:00"), now = mondayNight)
        assertEquals("Closed now", text("place_open_state"))
    }

    @Test fun hoursBeyondTheParserSayUnknownAndStillShowTheText() {
        show(PlaceExtras(openingHours = "Mo-Fr 08:00-20:00; PH off"))
        assertEquals("Hours: Mo-Fr 08:00-20:00; PH off", text("place_hours"))
        assertEquals("Open now: unknown", text("place_open_state"))
    }

    @Test @Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
    fun theSpanishHoursLines() {
        show(PlaceExtras(openingHours = "24/7"))
        assertEquals("Horario: 24/7", text("place_hours"))
        assertEquals("Abierto ahora", text("place_open_state"))
    }

    @Test fun searchResultExtrasReachTheCard() {
        val extras = PlaceExtras(phone = "+34 912 345 678")
        val info = SearchResult("Cafe", point, extras = extras).toPlaceInfo()
        assertEquals(extras, info.extras)
        assertNull(SearchResult("Cafe", point).toPlaceInfo().extras)
        show(info.extras)
        rule.onNodeWithTag("place_phone").assertIsDisplayed()
    }
}
