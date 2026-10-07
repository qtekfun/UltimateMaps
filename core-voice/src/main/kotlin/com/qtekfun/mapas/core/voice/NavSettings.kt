package com.qtekfun.mapas.core.voice

import com.qtekfun.mapas.core.routing.RouteOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Languages the spoken instructions exist in. [tag] is the BCP 47 language the TTS engine is asked for. */
enum class VoiceLanguage(val tag: String) {
    ES("es"), EN("en");

    val locale: Locale get() = Locale.forLanguageTag(tag)
}

/** What the user chose for the voice language; [AUTO] follows the language of the app (the system locale). */
enum class VoiceLanguagePref {
    AUTO, ES, EN;

    fun resolve(appLocale: Locale): VoiceLanguage = when (this) {
        ES -> VoiceLanguage.ES
        EN -> VoiceLanguage.EN
        AUTO -> if (appLocale.language.equals("es", ignoreCase = true)) VoiceLanguage.ES else VoiceLanguage.EN
    }
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
    /** The navigation screen follows the user with a tilted 3D camera (false: flat 2D, north or course up). */
    val view3d: Boolean = true,
    /** Extruded 3D buildings in the 3D view. Its cost on mid-range phones has not been measured. */
    val buildings3d: Boolean = true,
) {
    fun normalized(): NavSettings = copy(volumePercent = volumePercent.coerceIn(MIN_VOLUME, 100))

    /** The "avoid by default" settings as the options the routing engine understands. */
    fun routeOptions(): RouteOptions = RouteOptions(avoidMotorways, avoidTolls, avoidFerries, avoidUnpaved)

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
