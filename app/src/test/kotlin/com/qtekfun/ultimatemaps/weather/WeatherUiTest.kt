package com.qtekfun.ultimatemaps.weather

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.qtekfun.ultimatemaps.core.weather.AlertLevel
import com.qtekfun.ultimatemaps.core.weather.WeatherWarning
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WeatherUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private fun warning(level: AlertLevel, event: String = "Wind") = WeatherWarning(
        id = "w-$level", event = event, level = level, onsetMillis = now - 3_600_000, expiresMillis = now + 3_600_000, sentMillis = now - 7_200_000,
        areaDesc = "Litoral norte de Valencia", headline = "", description = "Gusts of 80 km/h.", instruction = "Avoid exposed places.", polygons = emptyList(),
    )

    @Test fun theCardShowsTheTextTheValidityTheAttributionAndTheHonestyLine() {
        rule.setContent { MapasTheme(darkTheme = false) { WeatherCard(listOf(warning(AlertLevel.ORANGE), warning(AlertLevel.YELLOW, "Rain")), now) {} } }
        assertEquals(2, rule.onAllNodesWithTag("weather_card_item").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithTag("weather_card_level").fetchSemanticsNodes().size)
        rule.onNodeWithTag("weather_card_attribution").assertIsDisplayed()
        rule.onNodeWithTag("weather_card_honesty").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("weather_card_validity").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theRouteWarningShowsOneLinePerWarning() {
        rule.setContent { MapasTheme(darkTheme = false) { WeatherRouteWarning(listOf(warning(AlertLevel.RED), warning(AlertLevel.ORANGE))) } }
        assertEquals(2, rule.onAllNodesWithTag("route_weather_line").fetchSemanticsNodes().size)
        rule.onNodeWithTag("route_weather_notice").assertIsDisplayed()
    }

    @Test fun theRouteWarningDrawsNothingWithoutWarnings() {
        rule.setContent { MapasTheme(darkTheme = false) { WeatherRouteWarning(emptyList()) } }
        assertEquals(0, rule.onAllNodesWithTag("route_weather_warning").fetchSemanticsNodes().size)
    }
}
