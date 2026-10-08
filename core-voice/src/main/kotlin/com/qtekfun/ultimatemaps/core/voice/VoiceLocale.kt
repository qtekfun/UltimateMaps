package com.qtekfun.ultimatemaps.core.voice

import java.util.Locale

/**
 * Which voice to ask the text-to-speech engine for. Asking for the bare language ("es") makes the engine use its own
 * default voice for that language, which for Spanish is usually the Latin American one even on a phone set to Spain.
 * So the region is asked for first (the phone's own, or Spain), then the bare language as the last resort.
 */
object VoiceLocale {
    /** Locales to try, best first; the last one is always the bare language. Pure: tested on the JVM. */
    fun candidates(language: VoiceLanguage, system: Locale): List<Locale> {
        val base = Locale.forLanguageTag(language.tag)
        val out = LinkedHashSet<Locale>()
        if (system.language.equals(base.language, ignoreCase = true) && system.country.isNotEmpty()) {
            out += Locale(base.language, system.country.uppercase(Locale.ROOT))
        }
        // The project's first users are in Spain: a Spanish app on a phone with a foreign locale still gets Spain's voice.
        if (language == VoiceLanguage.ES) out += Locale("es", "ES")
        out += base
        return out.toList()
    }

    /** What the engine says about one of its voices (a plain copy so the choice does not need Android types). */
    data class VoiceOption(
        val name: String,
        val locale: Locale,
        val quality: Int,
        val needsNetwork: Boolean,
        val installed: Boolean,
    )

    /**
     * The best voice for [wanted]: same language and, when [wanted] has a region, the same region; installed ones
     * first, then offline ones (guidance must work without a connection), then the higher quality. Null when none fits.
     */
    fun pickVoice(voices: List<VoiceOption>, wanted: Locale): VoiceOption? {
        val matching = voices.filter {
            it.locale.language.equals(wanted.language, ignoreCase = true) &&
                (wanted.country.isEmpty() || it.locale.country.equals(wanted.country, ignoreCase = true))
        }
        return matching.maxWithOrNull(
            compareBy<VoiceOption> { it.installed }.thenBy { !it.needsNetwork }.thenBy { it.quality }.thenBy { it.name },
        )
    }
}
