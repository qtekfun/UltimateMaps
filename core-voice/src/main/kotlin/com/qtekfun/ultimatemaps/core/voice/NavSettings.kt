package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.routing.BikeCycleways
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Languages the spoken instructions exist in. [tag] is the BCP 47 language the TTS engine is asked for. Spanish and English
 * are written as code, the others are [VoicePacks]; Basque has no spoken guidance yet.
 */
enum class VoiceLanguage(val tag: String) {
    ES("es"), EN("en"), CA("ca"), GL("gl"), FR("fr"), DE("de"), PT("pt"), IT("it");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        /** The voice language for the language of the app; English when there is no guidance in it (Basque, Dutch, ...). */
        fun forAppLocale(appLocale: Locale): VoiceLanguage =
            entries.firstOrNull { it.tag.equals(appLocale.language, ignoreCase = true) } ?: EN
    }
}

/** What the user chose for the voice language; [AUTO] follows the language of the app (the system locale). */
enum class VoiceLanguagePref {
    AUTO, ES, EN, CA, GL, FR, DE, PT, IT;

    /** The language this choice names, or null for [AUTO]. */
    val language: VoiceLanguage? get() = if (this == AUTO) null else VoiceLanguage.valueOf(name)

    fun resolve(appLocale: Locale): VoiceLanguage = language ?: VoiceLanguage.forAppLocale(appLocale)
}

enum class DistanceUnits { METRIC, IMPERIAL }

/** What the user chose for distances; [AUTO] depends on the region of the device. */
enum class UnitsPref {
    AUTO, METRIC, IMPERIAL;

    fun resolve(appLocale: Locale): DistanceUnits = when (this) {
        METRIC -> DistanceUnits.METRIC
        IMPERIAL -> DistanceUnits.IMPERIAL
        AUTO -> if (appLocale.country.uppercase() in IMPERIAL_REGIONS) DistanceUnits.IMPERIAL else DistanceUnits.METRIC
    }

    private companion object {
        /** Regions whose road signs use miles: the United States (and territories), the United Kingdom, Liberia, Myanmar. */
        val IMPERIAL_REGIONS = setOf("US", "GB", "LR", "MM", "PR", "GU", "VI", "AS", "MP")
    }
}

/**
 * The persistent navigation settings (section "Navigation" of the Settings screen). The "avoid" flags are the
 * defaults the route screen starts from; the user can still change them for one trip.
 */
data class NavSettings(
    val voiceEnabled: Boolean = true,
    /** Speak only the prompts that matter (see [isImportant]). */
    val importantOnly: Boolean = false,
    /** Volume of the voice relative to the stream, 10..100. */
    val volumePercent: Int = 100,
    val units: UnitsPref = UnitsPref.AUTO,
    val voiceLanguage: VoiceLanguagePref = VoiceLanguagePref.AUTO,
    val avoidMotorways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    /** Default cycle-infrastructure level for bike routes (the route screen can change it for one trip). */
    val bikeCycleways: BikeCycleways = BikeCycleways.OFF,
    /** The navigation screen follows the user with a tilted 3D camera (false: flat 2D, north or course up). */
    val view3d: Boolean = true,
    /** Extruded 3D buildings in the 3D view. Its cost on mid-range phones has not been measured. */
    val buildings3d: Boolean = true,
    /** Android 16+: show the distance to the next turn as a chip in the status bar (promoted Live Update). */
    val liveUpdateChip: Boolean = true,
    /** While the GPS is lost in a known tunnel, read the accelerometer to tell a stopped car from a moving one. Nothing is stored. */
    val motionSensorsInTunnels: Boolean = true,
    /** Hide the system status bar (other apps' notification icons) while a navigation is active; a swipe from the top shows it briefly. */
    val hideStatusBar: Boolean = false,
    /**
     * Package of the text-to-speech engine the guidance uses; empty means the system default. Some phones' own engine speaks
     * Spanish with a voice of another language: choosing another installed engine fixes it.
     */
    val voiceEngine: String = "",
) {
    fun normalized(): NavSettings = copy(volumePercent = volumePercent.coerceIn(MIN_VOLUME, 100))

    /** The "avoid by default" settings as the options the routing engine understands. */
    fun routeOptions(): RouteOptions = RouteOptions(avoidMotorways, avoidTolls, avoidFerries, avoidUnpaved, bikeCycleways)

    companion object {
        const val MIN_VOLUME = 10
        val VOLUME_CHOICES = listOf(25, 50, 75, 100)
    }
}

/** Where [NavSettings] live. The Android implementation keeps them in SharedPreferences. */
interface NavSettingsStore {
    val settings: StateFlow<NavSettings>
    fun update(transform: (NavSettings) -> NavSettings)
}

class InMemoryNavSettingsStore(initial: NavSettings = NavSettings()) : NavSettingsStore {
    private val state = MutableStateFlow(initial.normalized())
    override val settings: StateFlow<NavSettings> = state

    override fun update(transform: (NavSettings) -> NavSettings) {
        state.value = transform(state.value).normalized()
    }
}
