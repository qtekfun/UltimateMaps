package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The alerts' quick mute ([CameraSettings.alertsMuted]) and VOICE mode: the chip is unaffected, the navigation Mute still wins. */
class AlertVoiceMuteTest {
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

    private val lat0 = 40.0
    private val lon0 = -3.0
    private val perMeter = 1.0 / 111_320.0

    private class Rig(settings: CameraSettings, nav: NavSettings, targetNorthMeters: Double, perMeter: Double, lat0: Double, lon0: Double) {
        val store = InMemoryCameraSettingsStore(settings)
        val navSettings = InMemoryNavSettingsStore(nav)
        val guide = FakeGuide()
        val banner = AlertBannerTracker()
        val voice = AlertVoice(
            guide, navSettings.settings, modeFor = { store.settings.value.modeFor(it) }, alertsMuted = { store.settings.value.alertsMuted },
        ) { Locale.ENGLISH }
        val warner = AlertWarner(
            listOf(TargetGrid(listOf(AlertTarget("c", "c", AlertCategory.FIXED_CAMERA, lat0 + targetNorthMeters * perMeter, lon0, 0, AxisSense.BOTH, 50, 120)))),
            { store.settings.value },
        ) { banner.onAlert(it); voice.onAlert(it) }
    }

    private val on = CameraSettings(fixedEnabled = true, acknowledged = true, cameraAlertMode = AlertSoundMode.VOICE)

    private fun rig(cs: CameraSettings, nav: NavSettings = NavSettings()) = Rig(cs, nav, 700.0, perMeter, lat0, lon0)

    private fun Rig.approach() = warner.onFreeFix(lat0, lon0, 0f, 25f, 100_000L)

    @Test fun theDefaultIsASoundNotMutedAndNormalizingKeepsTheModes() {
        assertEquals(AlertSoundMode.SOUND, CameraSettings().cameraAlertMode)
        assertEquals(AlertSoundMode.SOUND, CameraSettings().incidentAlertMode)
        assertFalse(CameraSettings().alertsMuted)
        assertEquals(AlertSoundMode.VOICE, on.normalized().cameraAlertMode)
    }

    @Test fun unmutedAlertsAreSpokenAndShown() {
        val r = rig(on)
        r.approach()
        assertEquals(1, r.guide.spoken.size)
        assertNotNull(r.banner.state.value)
    }

    @Test fun mutedAlertsStillShowTheChipButSayNothing() {
        val r = rig(on.copy(alertsMuted = true))
        r.approach()
        assertTrue(r.guide.spoken.isEmpty(), "the alerts' own mute silences the voice")
        assertNotNull(r.banner.state.value, "the visual alert keeps showing")
    }

    @Test fun theNavigationMuteStillSilencesEverythingEvenWhenAlertVoiceIsOn() {
        val r = rig(on, NavSettings(voiceEnabled = false))
        r.approach()
        assertTrue(r.guide.spoken.isEmpty(), "navigation Mute overrides the alerts' voice switch")
        assertNotNull(r.banner.state.value)
    }

    @Test fun theAlertsMuteDoesNotTouchNavigationVoice() {
        val r = rig(on.copy(alertsMuted = true))
        assertTrue(r.navSettings.settings.value.voiceEnabled, "the navigation voice stays on")
        r.store.update { it.copy(alertsMuted = false) }
        r.approach()
        assertEquals(1, r.guide.spoken.size, "toggling the alerts' voice back on speaks the next alert")
    }

    @Test fun mutingMidApproachSilencesTheNextAlertOnly() {
        val r = rig(on)
        r.store.update { it.copy(alertsMuted = true) }
        r.approach()
        assertNull(r.guide.spoken.firstOrNull())
    }
}
