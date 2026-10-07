package com.qtekfun.mapas.transit.follow

import com.qtekfun.mapas.core.cameras.AlertSoundMode
import com.qtekfun.mapas.core.cameras.AlertSoundPlayer
import com.qtekfun.mapas.core.cameras.ChimeKind
import com.qtekfun.mapas.core.transit.follow.FollowPrompt
import com.qtekfun.mapas.core.transit.follow.PromptKind
import com.qtekfun.mapas.core.voice.InMemoryNavSettingsStore
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoiceLanguagePref
import com.qtekfun.mapas.core.voice.VoicePriority
import com.qtekfun.mapas.core.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeGuide : VoiceGuide {
    val spoken = mutableListOf<Utterance>()
    val prepared = mutableListOf<VoiceLanguage>()
    var lastVolume = -1
    override val status: StateFlow<VoiceStatus> = MutableStateFlow(VoiceStatus.Idle)
    override fun prepare(language: VoiceLanguage) { prepared += language }
    override fun speak(utterance: Utterance) { spoken += utterance }
    override fun stop() = Unit
    override fun setVolume(percent: Int) { lastVolume = percent }
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
}

class TransitTripSpeakerTest {
    private val guide = FakeGuide()
    private val settings = InMemoryNavSettingsStore(NavSettings(volumePercent = 50, voiceLanguage = VoiceLanguagePref.EN))
    private var mode = AlertSoundMode.VOICE
    private val chimes = mutableListOf<Pair<ChimeKind, Int>>()
    private val speaker = TransitTripSpeaker(guide, settings.settings, { mode }, AlertSoundPlayer { k, v -> chimes += k to v }, { Locale.ENGLISH })

    private val board = FollowPrompt(PromptKind.BOARD_NOW, "27", "Hospital", "Alpha")
    private val ready = FollowPrompt(PromptKind.GET_READY, "5", stop = "Sol")

    @Test fun voiceModeSpeaksTheSentenceThroughTheNavigationVoiceWithItsVolumeAndLanguage() {
        speaker.onPrompt(board)
        assertEquals(1, guide.spoken.size)
        assertEquals("Board line 27 towards Hospital now", guide.spoken[0].text)
        assertEquals(VoiceLanguage.EN, guide.spoken[0].language)
        assertEquals(VoicePriority.URGENT, guide.spoken[0].priority)
        assertEquals("transit:BOARD_NOW", guide.spoken[0].key)
        assertEquals(50, guide.lastVolume)
        assertTrue(chimes.isEmpty())
    }

    @Test fun theSpanishLanguageChoiceIsHonoured() {
        settings.update { it.copy(voiceLanguage = VoiceLanguagePref.ES) }
        speaker.onPrompt(board)
        assertEquals("Sube ahora a la línea 27 hacia Hospital", guide.spoken[0].text)
    }

    @Test fun soundModePlaysTheTransitChimeAtTheNavigationVolumeAndSaysNothing() {
        mode = AlertSoundMode.SOUND
        speaker.onPrompt(ready)
        assertEquals(listOf(ChimeKind.TRANSIT to 50), chimes)
        assertTrue(guide.spoken.isEmpty())
    }

    @Test fun silentModeDoesNothing() {
        mode = AlertSoundMode.SILENT
        speaker.onPrompt(board)
        assertTrue(guide.spoken.isEmpty() && chimes.isEmpty())
    }

    @Test fun theNavigationMuteSilencesEveryMode() {
        settings.update { it.copy(voiceEnabled = false) }
        for (m in AlertSoundMode.entries) {
            mode = m
            speaker.onPrompt(board)
        }
        assertTrue(guide.spoken.isEmpty() && chimes.isEmpty())
    }

    @Test fun onlyImportantPromptsAreSpokenWhenThatSettingIsOn() {
        settings.update { it.copy(importantOnly = true) }
        speaker.onPrompt(ready)
        assertTrue(guide.spoken.isEmpty(), "get ready is not urgent")
        speaker.onPrompt(board)
        assertEquals(1, guide.spoken.size)
    }

    @Test fun preparingWarmsTheEngineOnlyWhenAVoiceCouldBeSpoken() {
        speaker.prepare()
        assertEquals(listOf(VoiceLanguage.EN), guide.prepared)
        mode = AlertSoundMode.SOUND
        speaker.prepare()
        mode = AlertSoundMode.VOICE
        settings.update { it.copy(voiceEnabled = false) }
        speaker.prepare()
        assertEquals(1, guide.prepared.size)
    }
}
