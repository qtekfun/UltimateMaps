package com.qtekfun.ultimatemaps.core.weather

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherAlertIndexTest {
    private val now = OffsetDateTime.parse("2026-10-08T10:00:00+02:00").toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun warning(
        level: AlertLevel = AlertLevel.ORANGE,
        onset: Long? = now - hour,
        expires: Long? = now + 3 * hour,
        polygon: String = CapFixtures.SQUARE,
        id: String = "w",
    ) = WeatherWarning(id, "Wind", level, onset, expires, now, "Area", "", "", "", listOfNotNull(CapParser.polygon(polygon)))

    @Test fun `a point inside is matched, outside is not`() {
        val index = WeatherAlertIndex(listOf(warning()))
        assertEquals(1, index.at(LatLon(39.5, -0.4), now).size)
        assertTrue(index.at(LatLon(40.5, -0.4), now).isEmpty())
        assertTrue(index.at(LatLon(39.5, -0.2), now).isEmpty())
    }

    @Test fun `yellow is below the default level and shown when asked`() {
        val index = WeatherAlertIndex(listOf(warning(AlertLevel.YELLOW)))
        assertTrue(index.at(LatLon(39.5, -0.4), now).isEmpty())
        assertEquals(1, index.at(LatLon(39.5, -0.4), now, AlertLevel.YELLOW).size)
    }

    @Test fun `time window - not started, ended, and lookahead`() {
        val upcoming = WeatherAlertIndex(listOf(warning(onset = now + 2 * hour)))
        assertTrue(upcoming.at(LatLon(39.5, -0.4), now).isEmpty())
        assertEquals(1, upcoming.at(LatLon(39.5, -0.4), now, lookaheadMillis = 3 * hour).size)
        val ended = WeatherAlertIndex(listOf(warning(expires = now - 1)))
        assertTrue(ended.at(LatLon(39.5, -0.4), now, lookaheadMillis = 99 * hour).isEmpty())
    }

    @Test fun `most serious first, then earliest`() {
        val index = WeatherAlertIndex(
            listOf(
                warning(AlertLevel.ORANGE, onset = now - hour, id = "o-late"),
                warning(AlertLevel.RED, onset = now - hour, id = "red"),
                warning(AlertLevel.ORANGE, onset = now - 2 * hour, id = "o-early"),
            ),
        )
        assertEquals(listOf("red", "o-early", "o-late"), index.at(LatLon(39.5, -0.4), now).map { it.id })
    }

    @Test fun `a route that crosses the area between two far apart vertices is found`() {
        val index = WeatherAlertIndex(listOf(warning()))
        // two vertices 60 km apart on the same parallel, the area lies between them and neither vertex is inside
        val route = listOf(LatLon(39.5, -1.0), LatLon(39.5, 0.2))
        assertEquals(1, index.along(route, now).size)
        assertTrue(index.along(listOf(LatLon(41.0, -1.0), LatLon(41.0, 0.2)), now).isEmpty())
        assertTrue(index.along(emptyList(), now).isEmpty())
        assertTrue(index.along(listOf(LatLon(39.5, -1.0)), now).isEmpty())
        assertEquals(1, index.along(listOf(LatLon(39.5, -0.4)), now).size, "a one-point route is a position")
    }

    @Test fun `a warning without polygons never matches a route`() {
        val w = WeatherWarning("x", "e", AlertLevel.RED, null, null, null, "Somewhere", "", "", "", emptyList())
        assertTrue(WeatherAlertIndex(listOf(w)).along(listOf(LatLon(39.5, -1.0), LatLon(39.5, 0.2)), now).isEmpty())
        assertEquals(1, WeatherAlertIndex(listOf(w)).all(now, AlertLevel.YELLOW).size, "but it is listed")
    }
}
