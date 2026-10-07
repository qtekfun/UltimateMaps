package com.qtekfun.mapas.core.search

import java.util.Locale

/**
 * The language of place information (names, the region and country of an address, categories) the owner chose in Settings.
 * [AUTO] follows the app language; [LOCAL] asks for the name as it is written locally (Catalan, Basque or Galician names in
 * their own regions). The map labels on the tiles are generated separately and do not follow this choice.
 */
enum class PlaceLanguagePref {
    AUTO, ES, EN, LOCAL;

    /** The language the app's own texts use for [appLocale]: Spanish on a Spanish phone, English otherwise. */
    private fun appLanguage(appLocale: Locale): String = if (appLocale.language.equals("es", ignoreCase = true)) "es" else "en"

    /**
     * The language of translated texts (category, catalog region names). [LOCAL] has no translated form, so it uses the app
     * language, which is also the fallback the core uses for it.
     */
    fun textLanguage(appLocale: Locale): String = when (this) {
        ES -> "es"
        EN -> "en"
        AUTO, LOCAL -> appLanguage(appLocale)
    }

    /**
     * The `locale` string the native core takes (its wire format did not change): `es`, or `es;local` when the name as written
     * locally must come first. An old core that does not know the suffix would read `es;local` as an unknown language and fall
     * back to English, so the suffix is only ever added by a client that ships with the matching core.
     */
    fun coreLocale(appLocale: Locale): String = textLanguage(appLocale) + if (this == LOCAL) LOCAL_SUFFIX else ""

    companion object {
        const val LOCAL_SUFFIX = ";local"

        /** An unknown or missing stored name is [AUTO]. */
        fun fromName(name: String?): PlaceLanguagePref = entries.firstOrNull { it.name == name } ?: AUTO

        /** The language part of a core locale string (what is matched against), without the `;local` suffix. */
        fun languageOf(coreLocale: String): String = coreLocale.substringBefore(';').ifEmpty { "en" }

        fun isLocal(coreLocale: String): Boolean = coreLocale.substringAfter(';', "") == "local"
    }
}

/** Where the choice is kept. The app backs it with SharedPreferences. */
interface PlaceLanguageStore {
    var preference: PlaceLanguagePref
}
