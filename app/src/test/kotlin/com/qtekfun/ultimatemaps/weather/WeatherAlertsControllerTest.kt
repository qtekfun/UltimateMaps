package com.qtekfun.ultimatemaps.weather

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.weather.AemetEndpoints
import com.qtekfun.ultimatemaps.core.weather.AemetSource
import com.qtekfun.ultimatemaps.core.weather.AlertLevel
import com.qtekfun.ultimatemaps.core.weather.CapParser
import com.qtekfun.ultimatemaps.core.weather.InMemoryApiKeyStore
import com.qtekfun.ultimatemaps.core.weather.InMemoryWeatherAlertSettings
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertRepository
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettings
import com.qtekfun.ultimatemaps.core.weather.WeatherRefresh
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "eyJhbGciOiJIUzI1NiJ9.aaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbb"

private fun cap(id: String, event: String, severity: String, level: String) = """<?xml version="1.0" encoding="UTF-8"?>
<alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
  <identifier>$id</identifier><sent>2026-10-08T09:00:00+02:00</sent><status>Actual</status><msgType>Alert</msgType>
  <info><language>es-ES</language><event>$event</event><severity>$severity</severity>
    <onset>2026-10-08T08:00:00+02:00</onset><expires>2026-10-08T18:00:00+02:00</expires>
    <parameter><valueName>AEMET-Meteoalerta nivel</valueName><value>$level</value></parameter>
    <area><areaDesc>Valencia</areaDesc><polygon>39.40,-0.50 39.40,-0.30 39.60,-0.30 39.60,-0.50 39.40,-0.50</polygon></area>
  </info>
</alert>""".toByteArray()

private val CAPS = listOf(cap("x.1", "Vientos", "Severe", "naranja"), cap("x.2", "Lluvia", "Moderate", "amarillo"))

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherAlertsControllerTest {
    private val now = OffsetDateTime.parse("2026-10-08T10:00:00+02:00").toInstant().toEpochMilli()
    private val requests = CopyOnWriteArrayList<String>()
    private val added = CopyOnWriteArrayList<AllowedEndpoint>()
    private val removed = CopyOnWriteArrayList<String>()
    private val settings = InMemoryWeatherAlertSettings()
    private val keys = InMemoryApiKeyStore()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest fun stop() = scopes.forEach { it.cancel() }

    private fun TestScope.controller(): WeatherAlertsController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob()).also { scopes += it }
        // The source is the only thing that could reach the network: it records every call.
        val source = AemetSource { k -> requests += k; CAPS }
        val repository = WeatherAlertRepository(source, { settings.settings.value.enabled }, keys::read, { now }, { "es" })
        return WeatherAlertsController(settings, keys, repository, added::add, removed::add, scope, dispatcher, { now })
    }

    @Test fun `off by default - nothing is listed, nothing is asked`() = runTest {
        val c = controller()
        c.start(); advanceUntilIdle()
        c.ensureFresh(); c.checkNow(); advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertTrue(added.isEmpty())
        assertTrue(c.forRoute(listOf(LatLon(39.5, -0.4))).isEmpty())
        assertTrue(c.chipAt(LatLon(39.5, -0.4)).isEmpty())
        assertFalse(WeatherAlertSettings().enabled)
    }

    @Test fun `on without a key - still nothing is listed or asked`() = runTest {
        val c = controller()
        c.start(); advanceUntilIdle()
        settings.update { it.copy(enabled = true) }
        advanceUntilIdle()
        c.ensureFresh(); c.checkNow(); advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertTrue(added.isEmpty())
        assertFalse(c.active)
    }

    @Test fun `on with a key - the host is listed for the weather purpose and one request is made`() = runTest {
        keys.write(KEY)
        val c = controller()
        c.start(); advanceUntilIdle()
        settings.update { it.copy(enabled = true) }
        advanceUntilIdle()
        val e = added.single()
        assertEquals(AemetEndpoints.HOST, e.host)
        assertEquals(ConnectionPurpose.WEATHER_ALERTS, e.purpose)
        assertTrue(e.enabled)
        assertEquals(listOf(KEY), requests)
        // matching on the phone: orange at the place, nothing elsewhere, yellow only when asked
        assertEquals(listOf("Vientos"), c.chipAt(LatLon(39.5, -0.4)).map { it.event })
        assertTrue(c.chipAt(LatLon(41.0, 2.0)).isEmpty())
        assertEquals(listOf("Vientos"), c.detailsAt(LatLon(39.5, -0.4)).map { it.event })
        settings.update { it.copy(showYellow = true) }
        assertEquals(listOf("Vientos", "Lluvia"), c.detailsAt(LatLon(39.5, -0.4)).map { it.event })
        assertEquals(listOf("Vientos"), c.chipAt(LatLon(39.5, -0.4)).map { it.event }, "the chip never shows yellow")
        assertEquals(1, c.forRoute(listOf(LatLon(39.5, -1.0), LatLon(39.5, 0.2))).size)
    }

    @Test fun `Check now twice in a row asks once and says too soon`() = runTest {
        keys.write(KEY)
        val c = controller()
        c.start(); advanceUntilIdle()
        settings.update { it.copy(enabled = true) }
        advanceUntilIdle()
        c.checkNow(); advanceUntilIdle()
        assertEquals(WeatherRefresh.TooSoon, c.checkResult.value)
        assertEquals(1, requests.size)
    }

    @Test fun `switching off unlists the host and forgets the warnings`() = runTest {
        keys.write(KEY)
        val c = controller()
        c.start(); advanceUntilIdle()
        settings.update { it.copy(enabled = true) }
        advanceUntilIdle()
        settings.update { it.copy(enabled = false) }
        advanceUntilIdle()
        assertEquals(AemetEndpoints.HOST, removed.last())
        assertTrue(c.chipAt(LatLon(39.5, -0.4)).isEmpty())
    }

    @Test fun `removing the key stops it the same way`() = runTest {
        keys.write(KEY)
        val c = controller()
        c.start(); advanceUntilIdle()
        settings.update { it.copy(enabled = true) }
        advanceUntilIdle()
        c.removeKey(); advanceUntilIdle()
        assertNull(keys.read())
        assertEquals(AemetEndpoints.HOST, removed.last())
        assertFalse(c.active)
    }

    @Test fun `a pasted key is cleaned and stored, junk is refused`() = runTest {
        val c = controller()
        c.start(); advanceUntilIdle()
        assertFalse(c.saveKey("nope"))
        assertNull(keys.read())
        assertTrue(c.saveKey("  $KEY \n"))
        assertEquals(KEY, keys.read())
        assertTrue(c.hasKey.value)
    }

    @Test fun `the levels the chip may show`() {
        val parsed = CapParser.toWarnings(CAPS.map { CapParser.parse(it) }, "es", now)
        assertEquals(listOf(AlertLevel.ORANGE, AlertLevel.YELLOW), parsed.map { it.level })
    }
}
