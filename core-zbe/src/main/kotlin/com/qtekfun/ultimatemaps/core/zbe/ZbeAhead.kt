package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.cameras.AlertDelivery
import com.qtekfun.ultimatemaps.core.cameras.AlertDeliveryPolicy
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundPlayer
import com.qtekfun.ultimatemaps.core.cameras.ChimeKind
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.voice.DistanceUnits
import com.qtekfun.ultimatemaps.core.voice.InstructionText
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoicePriority
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** "A low-emission zone starts [distanceMeters] ahead on the route." Said once per crossing. */
class ZbeAhead(val crossing: ZbeCrossing, val distanceMeters: Int)

/**
 * Decides when to say "Low-emission zone ahead" during a navigation (pure; the time is not needed, only the distance along
 * the route and the speed). Each crossing is announced at most once per trip: when the zone starts within [lookAheadMeters]
 * of the vehicle and it is not already inside. A trip that starts inside a zone gets no prompt (the route summary already
 * showed the warning). A crossing the vehicle has passed without ever being announced (a jump in the position) is marked as
 * done silently. [onRoute] replaces the crossings (a recalculated route) and keeps what was already announced when the same
 * zone is entered again at about the same place.
 */
class ZbeAheadMachine {
    private var crossings: List<ZbeCrossing> = emptyList()
    private class Done(val zoneId: String, val at: LatLon)

    private val announced = ArrayList<Done>()

    /** Starts a trip or follows a new route: [list] are the crossings of the route being followed. */
    @Synchronized
    fun onRoute(list: List<ZbeCrossing>, newTrip: Boolean = false) {
        if (newTrip) announced.clear()
        crossings = list
    }

    /** The vehicle is at [traveledMeters] along the route at [speedMps]; returns the prompt to give now, if any. */
    @Synchronized
    fun onProgress(traveledMeters: Double, speedMps: Double): ZbeAhead? {
        if (crossings.isEmpty()) return null
        val look = lookAheadMeters(speedMps)
        for (c in crossings) {
            if (isDone(c)) continue
            val toEntry = c.entryMeters - traveledMeters
            if (c.startsInside || toEntry <= 0.0) {
                announced += Done(c.zone.id, c.entry) // already inside or past: nothing to announce
                continue
            }
            if (toEntry <= look) {
                announced += Done(c.zone.id, c.entry)
                return ZbeAhead(c, (Math.round(toEntry / 10.0) * 10).toInt().coerceAtLeast(10))
            }
        }
        return null
    }

    @Synchronized
    fun reset() {
        crossings = emptyList()
        announced.clear()
    }

    // The same zone entered at about the same place (the route was recalculated) is the same crossing.
    private fun isDone(c: ZbeCrossing): Boolean = announced.any { it.zoneId == c.zone.id && it.at.distanceTo(c.entry) <= SAME_PLACE_METERS }

    companion object {
        /** Seconds of driving the prompt should give, bounded below and above. Design values, not measured. */
        const val LOOK_AHEAD_SECONDS = 25.0
        const val MIN_LOOK_AHEAD_METERS = 400.0
        const val MAX_LOOK_AHEAD_METERS = 1_000.0

        /** Two entries of the same zone this close are the same crossing (a recalculated route). */
        const val SAME_PLACE_METERS = 300.0

        fun lookAheadMeters(speedMps: Double): Double =
            (speedMps.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0).times(LOOK_AHEAD_SECONDS)
                .coerceIn(MIN_LOOK_AHEAD_METERS, MAX_LOOK_AHEAD_METERS)
    }
}

/** The spoken sentence, Spanish and English. It never says anything about which vehicles are affected. */
object ZbePhrases {
    fun ahead(e: ZbeAhead, units: DistanceUnits, language: VoiceLanguage): String {
        val lead = InstructionText.lead(e.distanceMeters, units, language)
        return if (language == VoiceLanguage.ES) "$lead, zona de bajas emisiones. Consulta las normas de acceso" else "$lead, low-emission zone. Check the access rules"
    }
}

/**
 * Announces the prompt per the user's [mode]: a chime, the sentence through the navigation's voice queue, or nothing. The
 * decision is the same [AlertDeliveryPolicy] as the camera alerts use: the navigation Mute silences everything, and the voice
 * language, units and volume are the navigation's. A maneuver that is being announced wins (the on-screen banner still shows).
 */
class ZbeAheadSpeaker(
    private val guide: VoiceGuide,
    private val settings: StateFlow<NavSettings>,
    private val mode: () -> AlertSoundMode,
    private val maneuverImminent: () -> Boolean = { false },
    private val player: AlertSoundPlayer = AlertSoundPlayer { _, _ -> },
    private val locale: () -> Locale = Locale::getDefault,
) {
    fun onAhead(e: ZbeAhead) {
        val s = settings.value
        when (AlertDeliveryPolicy.decide(mode(), alertsMuted = false, navigationVoiceOn = s.voiceEnabled, maneuverImminent = maneuverImminent())) {
            AlertDelivery.NONE -> Unit
            // The incident chime (falling, low) is reused on purpose: a new chime would need tuning on a device.
            AlertDelivery.CHIME -> player.play(ChimeKind.INCIDENT, s.volumePercent)
            AlertDelivery.SPEAK -> {
                val lang = s.voiceLanguage.resolve(locale())
                guide.setVolume(s.volumePercent)
                guide.speak(
                    Utterance(
                        ZbePhrases.ahead(e, s.units.resolve(locale()), lang), VoicePriority.ADVISORY, lang,
                        key = "zbe:" + e.crossing.zone.id, maxAgeMillis = MAX_AGE_MILLIS,
                    ),
                )
            }
        }
    }

    private companion object {
        /** Said later than this, the prompt is about something the driver is already at. */
        const val MAX_AGE_MILLIS = 8_000L
    }
}
