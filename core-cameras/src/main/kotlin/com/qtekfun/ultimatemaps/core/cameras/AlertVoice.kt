package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.voice.DistanceUnits
import com.qtekfun.ultimatemaps.core.voice.InstructionText
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.AlertPromptPhrases
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoicePacks
import com.qtekfun.ultimatemaps.core.voice.VoicePriority
import com.qtekfun.ultimatemaps.core.voice.fill
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The sentences of the alerts, Spanish and English here and the other languages from their voice packs (pure; the cases are in `AlertPhrasesTest`). Wording is cautious on
 * purpose: the data says where a camera may be, not that it is switched on ("posible radar", "possible camera"), and a
 * mobile-radar zone is only "a stretch where mobile radars may operate".
 */
object AlertPhrases {
    fun of(e: AlertEvent, units: DistanceUnits, language: VoiceLanguage): String {
        val lead = InstructionText.lead(e.distanceMeters, units, language)
        if (language != VoiceLanguage.ES && language != VoiceLanguage.EN) VoicePacks.of(language)?.let { return fromPack(e, lead, it.alerts) }
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

    /** The same sentence for a language that is a [VoicePack]. */
    private fun fromPack(e: AlertEvent, lead: String, t: AlertPromptPhrases): String {
        val what = when (e.target.category) {
            AlertCategory.FIXED_CAMERA -> t.fixedCamera
            AlertCategory.SECTION -> t.section
            AlertCategory.MOBILE_ZONE -> t.mobileZone
            AlertCategory.V16 -> t.v16
            AlertCategory.ACCIDENT -> t.accident
            AlertCategory.CLOSURE -> t.closure
            AlertCategory.CONGESTION -> t.congestion
            AlertCategory.OBSTACLE -> t.obstacle
        }
        val limit = e.limitKmh
        return buildString {
            append(lead).append(", ").append(what)
            if (limit != null && (e.target.category == AlertCategory.FIXED_CAMERA || e.target.category == AlertCategory.SECTION)) {
                append(t.limit.fill("n" to limit.toString()))
            }
            if (e.stage == AlertStage.NEAR && e.speeding) append(t.slowDown)
        }
    }
}

/**
 * Announces the alerts audibly, per category [AlertSoundMode]: a chime through [player] or a sentence through the
 * navigation's [VoiceGuide] (honouring the navigation voice settings: on/off, language, units, volume). The decision is
 * [AlertDeliveryPolicy]. The visual alert is not this class's business and always shows.
 */
class AlertVoice(
    private val guide: VoiceGuide,
    private val settings: StateFlow<NavSettings>,
    /** True while a maneuver is about to be announced: the alert is then not spoken (see [ManeuverGuard]). */
    private val maneuverImminent: () -> Boolean = { false },
    /** The mode of the category an alert belongs to ([CameraSettings.modeFor]). */
    private val modeFor: (AlertCategory) -> AlertSoundMode = { AlertSoundMode.VOICE },
    /** The quick mute ([CameraSettings.alertsMuted]). */
    private val alertsMuted: () -> Boolean = { false },
    private val player: AlertSoundPlayer = AlertSoundPlayer { _, _ -> },
    private val locale: () -> Locale = Locale::getDefault,
) {
    /**
     * Chimes or speaks [e] as its category's mode says, unless the alerts are quick-muted or a maneuver is imminent. The
     * navigation voice switch (the guidance Mute button) does NOT silence the alerts: a driver who mutes the spoken
     * directions still wants the warning about a camera, and has the alerts' own mute for that. It does not depend on "important prompts only",
     * which is about maneuvers. Spoken alerts use [VoicePriority.ADVISORY]: they wait behind every driving instruction and
     * never interrupt or discard one.
     */
    fun onAlert(e: AlertEvent) {
        val s = settings.value
        val delivery = AlertDeliveryPolicy.decide(modeFor(e.target.category), alertsMuted(), navigationVoiceOn = true, maneuverImminent())
        if (delivery == AlertDelivery.NONE) return
        if (delivery == AlertDelivery.CHIME) {
            player.play(e.target.category.chimeKind, s.volumePercent)
            return
        }
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
