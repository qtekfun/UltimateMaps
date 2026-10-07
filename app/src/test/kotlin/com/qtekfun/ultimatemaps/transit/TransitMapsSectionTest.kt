package com.qtekfun.ultimatemaps.transit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitMapsSectionTest {
    @get:Rule val rule = createComposeRule()

    private fun row(id: String, installed: Boolean = false, expired: Boolean = false, update: Boolean = false, validTo: String = "2026-11-05") =
        TransitCityRow(id, "City $id", "2026-10-07", validTo, 3_000_000, installed, update, expired, false, null, listOf("Powered by Test"))

    @Test
    fun listsCitiesWithValidityAndDownloadAndDeleteActions() {
        val downloads = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        rule.setContent {
            MapasTheme(darkTheme = false) {
                TransitMapsSection(listOf(row("a"), row("b", installed = true)), offline = false, onDownload = { downloads += it }, onDelete = { deletes += it })
            }
        }
        rule.onNodeWithTag("transit_maps").assertIsDisplayed()
        rule.onNodeWithTag("transit_city_a_validity").assertExists()
        rule.onNodeWithTag("transit_city_a_download").performClick()
        rule.onNodeWithTag("transit_city_b_delete").performClick()
        rule.onNodeWithTag("transit_city_b_download").assertDoesNotExist() // installed and current
        assertEquals(listOf("a"), downloads)
        assertEquals(listOf("b"), deletes)
    }

    @Test
    fun anExpiredEntryCannotBeDownloadedAndSaysItIsOutOfDate() {
        rule.setContent {
            MapasTheme(darkTheme = false) { TransitMapsSection(listOf(row("old", expired = true, validTo = "2026-05-27")), false, {}, {}) }
        }
        rule.onNodeWithTag("transit_city_old_download").assertDoesNotExist()
        rule.onNodeWithTag("transit_city_old_validity").assertExists()
    }

    @Test
    fun anUpdateIsOfferedWhenTheCatalogHasANewerFileAndNothingShowsWithoutRows() {
        val downloads = mutableListOf<String>()
        rule.setContent {
            MapasTheme(darkTheme = false) { TransitMapsSection(listOf(row("a", installed = true, update = true)), false, { downloads += it }, {}) }
        }
        rule.onNodeWithTag("transit_city_a_download").performClick()
        assertEquals(listOf("a"), downloads)
    }

    @Test
    fun emptyCatalogShowsNoSectionAndAboutBlockShowsAttributionOnlyWithData() {
        rule.setContent {
            MapasTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Column {
                    TransitMapsSection(emptyList(), false, {}, {})
                    TransitAboutBlock(listOf("Powered by CRTM (https://www.crtm.es/). Processed data."))
                }
            }
        }
        rule.onNodeWithTag("transit_maps").assertDoesNotExist()
        rule.onNodeWithTag("about_transit").assertIsDisplayed()
        rule.onNodeWithTag("about_transit_line_0").assertExists()
        rule.onNodeWithTag("about_transit_line_0_link_0").assertExists()
    }

    @Test
    fun aboutBlockIsAbsentWithoutInstalledData() {
        rule.setContent { MapasTheme(darkTheme = false) { TransitAboutBlock(emptyList()) } }
        rule.onNodeWithTag("about_transit").assertDoesNotExist()
    }
}
