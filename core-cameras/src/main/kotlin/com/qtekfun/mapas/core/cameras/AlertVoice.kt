package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.voice.DistanceUnits
import com.qtekfun.mapas.core.voice.InstructionText
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoicePriority
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The sentences of the alerts, Spanish and English (pure; the cases are in `AlertPhrasesTest`). Wording is cautious on
 * purpose: the data says where a camera may be, not that it is switched on ("posible radar", "possible camera"), and a
 * mobile-radar zone is only "a stretch where mobile radars may operate".
 */
object AlertPhrases {
    fun of(e: AlertEvent, units: DistanceUnits, language: VoiceLanguage): String {
        val lead = InstructionText.lead(e.distanceMeters, units, language)
        val es = language == VoiceLanguage.ES
        val what = when (e.target.category) {
            AlertCategory.FIXED_CAMERA -> if (es) "posible radar fijo" else "possible fixed speed camera"
            AlertCategory.SECTION -> if (es) "tramo con control de velocidad media" else "average-speed section"
            AlertCategory.MOBILE_ZONE -> if (es) "zona con posibles radares móviles" else "stretch where mobile speed cameras may operate"
            AlertCategory.V16 -> if (es) "vehículo detenido con baliza V16" else "stopped vehicle with a V16 beacon"
            AlertCategory.ACCIDENT -> if (es) "accidente" else "accident"
            AlertCategory.CLOSURE -> if (es) "corte de carretera" else "road closure"
            AlertCategory.CONGESTION -> if (es) "tráfico lento" else "slow traffic"
            AlertCategory.OBSTACLE -> if (es) "obstáculo en la vía" else "obstacle on the road"
        }
        val sentence = "$lead, $what"
        val limit = e.limitKmh
        val tail = buildString {
            if (limit != null && (e.target.category == AlertCategory.FIXED_CAMERA || e.target.category == AlertCategory.SECTION)) {
                append(if (es) ". Límite $limit" else ". Limit $limit")
            }
            if (e.stage == AlertStage.NEAR && e.speeding) append(if (es) ". Reduce la velocidad" else ". Slow down")
        }
        return sentence + tail
    }
}

/**
 * Speaks the alerts through the navigation's [VoiceGuide], honouring the navigation voice settings (voice on/off,
 * language, units). Without voice the alert is not spoken (the visual alert still shows).
 */
class AlertVoice(
    private val guide: VoiceGuide,
    private val settings: StateFlow<NavSettings>,
    /** True while a maneuver is about to be announced: the alert is then not spoken (see [ManeuverGuard]). */
    private val maneuverImminent: () -> Boolean = { false },
    private val locale: () -> Locale = Locale::getDefault,
) {
    /**
     * Speaks [e] unless the navigation voice is off or muted (the same switch as the Mute button; it does not depend on
     * "important prompts only", which is about maneuvers) or a maneuver is imminent. Alerts use [VoicePriority.ADVISORY]:
     * they wait behind every driving instruction and never interrupt or discard one.
     */
    fun onAlert(e: AlertEvent) {
        val s = settings.value
        if (!s.voiceEnabled) return
        if (maneuverImminent()) return
        val lang = s.voiceLanguage.resolve(locale())
        guide.setVolume(s.volumePercent) // free driving has no navigation controller to have set it
        guide.speak(
            Utterance(
                AlertPhrases.of(e, s.units.resolve(locale()), lang), VoicePriority.ADVISORY, lang,
                key = "alert:" + e.target.group, maxAgeMillis = MAX_AGE_MILLIS,
            ),
        )
    }

    private companion object {
        /** A warning said later than this is about something the driver has already passed. */
        const val MAX_AGE_MILLIS = 6_000L
    }
}
