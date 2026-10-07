package com.qtekfun.ultimatemaps.transit.follow

import com.qtekfun.ultimatemaps.core.cameras.AlertDelivery
import com.qtekfun.ultimatemaps.core.cameras.AlertDeliveryPolicy
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundPlayer
import com.qtekfun.ultimatemaps.core.cameras.ChimeKind
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPrompt
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.TransitPhrases
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoicePriority
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Says or sounds the prompts of the transit trip, per the user's [mode]: a chime ([ChimeKind.TRANSIT]), the sentence of
 * [TransitPhrases] through the navigation's voice queue, or nothing. The decision is the same [AlertDeliveryPolicy] as the
 * camera alerts use: the navigation Mute silences everything, the voice language, units and volume are the navigation's. With
 * "only important prompts" on, only the urgent ones are spoken. The on-screen banner is not this class's business.
 */
class TransitTripSpeaker(
    private val guide: VoiceGuide,
    private val settings: StateFlow<NavSettings>,
    private val mode: () -> AlertSoundMode,
    private val player: AlertSoundPlayer = AlertSoundPlayer { _, _ -> },
    private val locale: () -> Locale = Locale::getDefault,
) {
    /** Warms the voice engine up when a trip starts so the first prompt is not late. */
    fun prepare() {
        val s = settings.value
        if (s.voiceEnabled && mode() == AlertSoundMode.VOICE) guide.prepare(s.voiceLanguage.resolve(locale()))
        guide.setVolume(s.volumePercent)
    }

    fun onPrompt(p: FollowPrompt) {
        val s = settings.value
        val delivery = AlertDeliveryPolicy.decide(mode(), alertsMuted = false, navigationVoiceOn = s.voiceEnabled, maneuverImminent = false)
        when (delivery) {
            AlertDelivery.NONE -> Unit
            AlertDelivery.CHIME -> player.play(ChimeKind.TRANSIT, s.volumePercent)
            AlertDelivery.SPEAK -> {
                val priority = TransitPhrases.priority(p.kind)
                if (s.importantOnly && priority != VoicePriority.URGENT) return
                val lang = s.voiceLanguage.resolve(locale())
                guide.setVolume(s.volumePercent)
                guide.speak(Utterance(TransitPhrases.of(p, lang), priority, lang, key = TransitPhrases.key(p.kind)))
            }
        }
    }
}
