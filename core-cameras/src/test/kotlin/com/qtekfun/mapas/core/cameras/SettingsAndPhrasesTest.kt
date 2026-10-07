package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.voice.DistanceUnits
import com.qtekfun.mapas.core.voice.InMemoryNavSettingsStore
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceFailure
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoiceLanguagePref
import com.qtekfun.mapas.core.voice.VoicePriority
import com.qtekfun.mapas.core.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsAndPhrasesTest {
    // ---- settings ----

    @Test fun everythingIsOffByDefault() {
        val d = CameraSettings()
        assertFalse(d.fixedEnabled || d.mobileZonesEnabled || d.incidentsEnabled || d.v16Enabled || d.roadworksEnabled || d.warnOnlyIfSpeeding || d.acknowledged)
        assertFalse(d.anything)
        assertTrue(d.incidentKinds().isEmpty())
    }

    @Test fun cameraSwitchesNeedTheAcknowledgementAndRoadworksNeedIncidents() {
        val s = InMemoryCameraSettingsStore()
        s.update { it.copy(fixedEnabled = true, mobileZonesEnabled = true) }
        assertFalse(s.settings.value.fixedEnabled, "no acknowledgement, no camera layer")
        assertFalse(s.settings.value.mobileZonesEnabled)
        s.update { it.copy(acknowledged = true, fixedEnabled = true) }
        assertTrue(s.settings.value.fixedEnabled)
        s.update { it.copy(roadworksEnabled = true) }
        assertFalse(s.settings.value.roadworksEnabled, "roadworks only exist inside the incidents switch")
        s.update { it.copy(incidentsEnabled = true, roadworksEnabled = true, incidentRefreshMinutes = 1) }
        assertEquals(MIN_INCIDENT_REFRESH_MINUTES, s.settings.value.incidentRefreshMinutes)
        assertTrue(IncidentKind.ROADWORKS in s.settings.value.incidentKinds())
        s.update { it.copy(acknowledged = false) }
        assertFalse(s.settings.value.fixedEnabled, "withdrawing the acknowledgement turns the camera layers off")
    }

    @Test fun incidentKindsFollowTheSwitches() {
        assertEquals(setOf(IncidentKind.V16), CameraSettings(v16Enabled = true).incidentKinds())
        val k = CameraSettings(incidentsEnabled = true).incidentKinds()
        assertTrue(IncidentKind.V16 !in k && IncidentKind.ACCIDENT in k && IncidentKind.ROADWORKS !in k)
    }

    // ---- phrases ----

    private fun target(c: AlertCategory) = AlertTarget("t", "t", c, 40.0, -3.0, null, AxisSense.BOTH, 50, null)
    private fun event(c: AlertCategory, meters: Int, stage: AlertStage = AlertStage.FAR, limit: Int? = null, speeding: Boolean = false) =
        AlertEvent(target(c), stage, meters, limit, 100, speeding)

    @Test fun spanishPhrases() {
        fun es(e: AlertEvent) = AlertPhrases.of(e, DistanceUnits.METRIC, VoiceLanguage.ES)
        assertEquals("En 800 metros, posible radar fijo", es(event(AlertCategory.FIXED_CAMERA, 790)))
        assertEquals("En 800 metros, posible radar fijo. Límite 90", es(event(AlertCategory.FIXED_CAMERA, 790, limit = 90)))
        assertEquals("En 300 metros, posible radar fijo. Límite 90. Reduce la velocidad", es(event(AlertCategory.FIXED_CAMERA, 260, AlertStage.NEAR, 90, true)))
        assertEquals("En 1 kilómetro, tramo con control de velocidad media", es(event(AlertCategory.SECTION, 1000)))
        assertEquals("En 800 metros, zona con posibles radares móviles", es(event(AlertCategory.MOBILE_ZONE, 800)))
        assertEquals("En 800 metros, vehículo detenido con baliza V16", es(event(AlertCategory.V16, 800)))
        assertEquals("En 1,5 kilómetros, accidente", es(event(AlertCategory.ACCIDENT, 1500)))
        assertEquals("En 400 metros, corte de carretera", es(event(AlertCategory.CLOSURE, 400)))
        assertEquals("En 400 metros, tráfico lento", es(event(AlertCategory.CONGESTION, 400)))
        assertEquals("En 400 metros, obstáculo en la vía", es(event(AlertCategory.OBSTACLE, 400)))
    }

    @Test fun englishPhrasesAndLimitOnlyForCameras() {
        fun en(e: AlertEvent, u: DistanceUnits = DistanceUnits.METRIC) = AlertPhrases.of(e, u, VoiceLanguage.EN)
        assertEquals("In 800 meters, possible fixed speed camera. Limit 70", en(event(AlertCategory.FIXED_CAMERA, 800, limit = 70)))
        assertEquals("In 800 meters, stopped vehicle with a V16 beacon", en(event(AlertCategory.V16, 800, limit = 70)))
        assertEquals("In 300 meters, average-speed section. Slow down", en(event(AlertCategory.SECTION, 300, AlertStage.NEAR, null, true)))
        assertEquals("In 0.5 miles, accident", en(event(AlertCategory.ACCIDENT, 800), DistanceUnits.IMPERIAL))
    }

    private class FakeGuide : VoiceGuide {
        val spoken = ArrayList<Utterance>()
        override val status: StateFlow<VoiceStatus> = MutableStateFlow(VoiceStatus.Idle)
        override fun prepare(language: VoiceLanguage) {}
        override fun speak(utterance: Utterance) { spoken += utterance }
        override fun stop() {}
        override fun setVolume(percent: Int) {}
        override fun retry(language: VoiceLanguage) {}
        override fun shutdown() {}
    }

    @Test fun theVoiceHonoursTheNavigationVoiceSettings() {
        val guide = FakeGuide()
        val settings = InMemoryNavSettingsStore(NavSettings(voiceLanguage = VoiceLanguagePref.EN))
        val voice = AlertVoice(guide, settings.settings) { Locale.forLanguageTag("es-ES") }
        voice.onAlert(event(AlertCategory.ACCIDENT, 800))
        assertEquals("In 800 meters, accident", guide.spoken.single().text)
        assertEquals(VoicePriority.NORMAL, guide.spoken.single().priority)
        assertEquals("alert:t", guide.spoken.single().key)
        settings.update { it.copy(voiceEnabled = false) }
        voice.onAlert(event(AlertCategory.ACCIDENT, 800))
        assertEquals(1, guide.spoken.size, "voice off: informational alerts are dropped")
        settings.update { it.copy(voiceEnabled = true, voiceLanguage = VoiceLanguagePref.AUTO) }
        voice.onAlert(event(AlertCategory.V16, 800))
        assertEquals("En 800 metros, vehículo detenido con baliza V16", guide.spoken.last().text)
    }

    // ---- attribution ----

    @Test fun attributionNamesTheSourcesPresentInTheData() {
        assertTrue(CameraAttribution.forCameras(CameraSources.DGT, true).contains("Dirección General de Tráfico"))
        assertFalse(CameraAttribution.forCameras(CameraSources.DGT, true).contains("OpenStreetMap"))
        val both = CameraAttribution.forCameras(CameraSources.DGT or CameraSources.OSM, false)
        assertTrue(both.contains("CC BY") && both.contains("ODbL"))
        assertEquals("", CameraAttribution.forCameras(0, true))
        assertTrue(CameraAttribution.forIncidents(true).contains("CC BY"))
        assertEquals("2026-10-07 12:30", CameraAttribution.dateText(1_791_376_200_000L, true, ZoneId.of("UTC")))
    }

    @Test fun zonesAreOnlyAlertTargetsWhenTheyHaveALine() {
        val line = listOf(LatLon(40.0, -3.0), LatLon(40.01, -3.0))
        val data = CameraDataset(
            1L, CameraSources.DGT, emptyList(), emptyList(),
            listOf(MobileZone("z1", "A-2", "X", 1000, 2000, line), MobileZone("z2", "CM-9", "X", 5000, 9000, emptyList())),
        )
        val on = CameraTargets.of(data, CameraSettings(mobileZonesEnabled = true, acknowledged = true))
        assertEquals(listOf("z1a", "z1b"), on.map { it.id })
        assertEquals(setOf("z1"), on.map { it.group }.toSet())
        assertTrue(CameraTargets.of(data, CameraSettings()).isEmpty())
        assertEquals(VoiceFailure.INIT_FAILED, VoiceFailure.INIT_FAILED) // keeps the import honest if the voice module changes
    }
}
