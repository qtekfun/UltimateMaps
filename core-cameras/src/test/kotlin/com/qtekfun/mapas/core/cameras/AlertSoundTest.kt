package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.voice.InMemoryNavSettingsStore
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chime (pure PCM), the sound / speak / nothing decision and the dispatch in [AlertVoice] with a fake player. */
class AlertSoundTest {
    // ---- PCM ----

    @Test fun theChimeIsToneGapToneOfTheDocumentedLengthAndFormat() {
        for (k in ChimeKind.entries) {
            val pcm = ChimeSynth.pcm(k)
            assertEquals(ChimeSynth.length(), pcm.size)
            assertEquals(2 * 5_292 + 1_764, pcm.size, "120 ms + 40 ms + 120 ms at 44.1 kHz")
            assertEquals(280, ChimeSynth.durationMillis())
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(peak <= (ChimeSynth.AMPLITUDE * Short.MAX_VALUE).toInt() + 1, "peak $peak keeps the headroom")
            assertTrue(peak > 0.5 * Short.MAX_VALUE, "it is audible: peak $peak")
        }
    }

    @Test fun theGapIsSilentAndTheEdgesFadeSoThereAreNoClicks() {
        val pcm = ChimeSynth.pcm(ChimeKind.CAMERA)
        val tone = ChimeSynth.samplesPerTone()
        val gap = ChimeSynth.samplesPerGap()
        assertTrue((tone until tone + gap).all { pcm[it].toInt() == 0 }, "silence between the tones")
        assertTrue(abs(pcm[0].toInt()) < 0.05 * Short.MAX_VALUE, "starts near zero")
        assertTrue(abs(pcm[pcm.size - 1].toInt()) < 0.05 * Short.MAX_VALUE, "ends near zero")
        val fade = ChimeSynth.SAMPLE_RATE * ChimeSynth.FADE_MILLIS / 1000
        val first = (0 until fade / 4).maxOf { abs(pcm[it].toInt()) }
        val steady = (fade until 2 * fade).maxOf { abs(pcm[it].toInt()) }
        assertTrue(first < steady, "the first samples are quieter than the steady tone")
    }

    /** Frequency from zero crossings over [from, to): each full period has two sign changes. */
    private fun hz(pcm: ShortArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
        return crossings / 2.0 / ((to - from).toDouble() / ChimeSynth.SAMPLE_RATE)
    }

    @Test fun eachToneHasTheDeclaredFrequency() {
        for (k in ChimeKind.entries) {
            val pcm = ChimeSynth.pcm(k)
            val (a, b) = ChimeSynth.tonesHz(k)
            val tone = ChimeSynth.samplesPerTone()
            val second = tone + ChimeSynth.samplesPerGap()
            assertTrue(abs(hz(pcm, 0, tone) - a) < 0.03 * a, "$k first tone ${hz(pcm, 0, tone)} vs $a")
            assertTrue(abs(hz(pcm, second, second + tone) - b) < 0.03 * b, "$k second tone ${hz(pcm, second, second + tone)} vs $b")
        }
    }

    @Test fun camerasGoUpIncidentsGoDownAndTheyDiffer() {
        val (c1, c2) = ChimeSynth.tonesHz(ChimeKind.CAMERA)
        val (i1, i2) = ChimeSynth.tonesHz(ChimeKind.INCIDENT)
        assertTrue(c2 > c1, "camera chime rises")
        assertTrue(i2 < i1, "incident chime falls")
        assertTrue(listOf(c1, c2).none { it in listOf(i1, i2) })
        assertTrue(!ChimeSynth.pcm(ChimeKind.CAMERA).contentEquals(ChimeSynth.pcm(ChimeKind.INCIDENT)))
        assertTrue(ChimeSynth.pcm(ChimeKind.CAMERA).contentEquals(ChimeSynth.pcm(ChimeKind.CAMERA)), "deterministic")
    }

    @Test fun theTransitChimeRisesAndDiffersFromTheOtherTwo() {
        val (t1, t2) = ChimeSynth.tonesHz(ChimeKind.TRANSIT)
        assertTrue(t2 > t1, "transit chime rises")
        for (other in listOf(ChimeKind.CAMERA, ChimeKind.INCIDENT)) {
            val (o1, o2) = ChimeSynth.tonesHz(other)
            assertTrue(listOf(t1, t2).none { it in listOf(o1, o2) }, "no shared pitch with $other")
            assertTrue(!ChimeSynth.pcm(ChimeKind.TRANSIT).contentEquals(ChimeSynth.pcm(other)))
        }
    }

    @Test fun everyCategoryMapsToTheRightChime() {
        val cams = setOf(AlertCategory.FIXED_CAMERA, AlertCategory.SECTION, AlertCategory.MOBILE_ZONE)
        for (c in AlertCategory.entries) assertEquals(if (c in cams) ChimeKind.CAMERA else ChimeKind.INCIDENT, c.chimeKind, "$c")
    }

    // ---- decision table ----

    @Test fun decisionTable() {
        fun d(m: AlertSoundMode, muted: Boolean = false, nav: Boolean = true, imminent: Boolean = false) = AlertDeliveryPolicy.decide(m, muted, nav, imminent)
        assertEquals(AlertDelivery.CHIME, d(AlertSoundMode.SOUND))
        assertEquals(AlertDelivery.SPEAK, d(AlertSoundMode.VOICE))
        assertEquals(AlertDelivery.NONE, d(AlertSoundMode.SILENT))
        for (m in AlertSoundMode.entries) {
            assertEquals(AlertDelivery.NONE, d(m, muted = true), "quick mute silences $m")
            assertEquals(AlertDelivery.NONE, d(m, nav = false), "navigation Mute silences $m")
            assertEquals(AlertDelivery.NONE, d(m, imminent = true), "a maneuver wins over $m")
            assertEquals(AlertDelivery.NONE, d(m, muted = true, nav = false, imminent = true))
        }
    }

    // ---- dispatch ----

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

    private class FakePlayer : AlertSoundPlayer {
        val played = ArrayList<Pair<ChimeKind, Int>>()
        override fun play(kind: ChimeKind, volumePercent: Int) { played += kind to volumePercent }
    }

    private fun event(c: AlertCategory) =
        AlertEvent(AlertTarget("t", "t", c, 40.0, -3.0, null, AxisSense.BOTH, 0, null), AlertStage.FAR, 500, null, 20, false)

    private class Rig(settings: CameraSettings, nav: NavSettings, imminent: () -> Boolean) {
        val store = InMemoryCameraSettingsStore(settings)
        val guide = FakeGuide()
        val player = FakePlayer()
        val voice = AlertVoice(
            guide, InMemoryNavSettingsStore(nav).settings, maneuverImminent = imminent,
            modeFor = { store.settings.value.modeFor(it) }, alertsMuted = { store.settings.value.alertsMuted }, player = player,
        ) { Locale.ENGLISH }
    }

    private val all = CameraSettings(fixedEnabled = true, incidentsEnabled = true, acknowledged = true)

    @Test fun soundModeChimesWithTheNavigationVolumeAndSaysNothing() {
        val r = Rig(all, NavSettings(volumePercent = 50), { false })
        r.voice.onAlert(event(AlertCategory.FIXED_CAMERA))
        r.voice.onAlert(event(AlertCategory.V16))
        r.voice.onAlert(event(AlertCategory.ACCIDENT))
        assertEquals(listOf(ChimeKind.CAMERA to 50, ChimeKind.INCIDENT to 50, ChimeKind.INCIDENT to 50), r.player.played)
        assertTrue(r.guide.spoken.isEmpty())
    }

    @Test fun eachCategoryFollowsItsOwnMode() {
        val r = Rig(all.copy(cameraAlertMode = AlertSoundMode.VOICE, incidentAlertMode = AlertSoundMode.SILENT), NavSettings(), { false })
        r.voice.onAlert(event(AlertCategory.SECTION))
        r.voice.onAlert(event(AlertCategory.CLOSURE))
        assertEquals(1, r.guide.spoken.size, "the camera was spoken")
        assertTrue(r.player.played.isEmpty(), "no chime in VOICE or SILENT")
        r.store.update { it.copy(cameraAlertMode = AlertSoundMode.SOUND, incidentAlertMode = AlertSoundMode.VOICE) }
        r.voice.onAlert(event(AlertCategory.MOBILE_ZONE))
        r.voice.onAlert(event(AlertCategory.CONGESTION))
        assertEquals(listOf(ChimeKind.CAMERA to 100), r.player.played)
        assertEquals(2, r.guide.spoken.size, "the incident is now spoken")
    }

    @Test fun quickMuteSilencesBothCategoriesAndRestoresTheModes() {
        val r = Rig(all.copy(cameraAlertMode = AlertSoundMode.VOICE), NavSettings(), { false })
        r.store.update { it.copy(alertsMuted = true) }
        r.voice.onAlert(event(AlertCategory.FIXED_CAMERA))
        r.voice.onAlert(event(AlertCategory.ACCIDENT))
        assertTrue(r.guide.spoken.isEmpty() && r.player.played.isEmpty())
        r.store.update { it.copy(alertsMuted = false) }
        r.voice.onAlert(event(AlertCategory.FIXED_CAMERA))
        r.voice.onAlert(event(AlertCategory.ACCIDENT))
        assertEquals(1, r.guide.spoken.size)
        assertEquals(1, r.player.played.size)
    }

    @Test fun navigationMuteAndAnImminentManeuverSkipTheChime() {
        var imminent = true
        val r = Rig(all, NavSettings(), { imminent })
        r.voice.onAlert(event(AlertCategory.FIXED_CAMERA))
        assertTrue(r.player.played.isEmpty(), "no chime over a maneuver prompt")
        imminent = false
        r.voice.onAlert(event(AlertCategory.FIXED_CAMERA))
        assertEquals(1, r.player.played.size)
        val muted = Rig(all, NavSettings(voiceEnabled = false), { false })
        muted.voice.onAlert(event(AlertCategory.FIXED_CAMERA))
        assertTrue(muted.player.played.isEmpty() && muted.guide.spoken.isEmpty(), "the navigation Mute silences the chime too")
    }
}
