package com.qtekfun.ultimatemaps.search

import android.content.Context
import android.content.SharedPreferences

/**
 * The owner's switch for the optional system-geocoder fallback (the `play` flavor only uses it; the `foss` flavor never
 * reads it and shows no switch). OFF by default: turning it on lets the phone's geocoder service, which is Google's on a
 * phone with Play Services, receive the text of searches that the offline search could not answer.
 */
interface SystemGeocoderSettings {
    var enabled: Boolean
}

class PrefsSystemGeocoderSettings(private val prefs: SharedPreferences) : SystemGeocoderSettings {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var enabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    companion object {
        const val PREFS = "system_geocoder"
        const val KEY_ENABLED = "enabled"
    }
}
