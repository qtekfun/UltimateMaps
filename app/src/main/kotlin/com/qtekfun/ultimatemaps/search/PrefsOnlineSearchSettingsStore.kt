package com.qtekfun.ultimatemaps.search

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.search.OnlineSearchSettingsStore

/**
 * [OnlineSearchSettingsStore] over SharedPreferences (`online_search`). Read on every use, so a change in Settings (or a
 * restored backup) applies to the next search. The switch is off by default and a blank address means the default server.
 */
class PrefsOnlineSearchSettingsStore(private val prefs: SharedPreferences) : OnlineSearchSettingsStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var enabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    override var serverUrl: String
        get() = runCatching { prefs.getString(KEY_SERVER_URL, "").orEmpty() }.getOrDefault("")
        set(value) { prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply() }

    companion object {
        const val PREFS = "online_search"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER_URL = "server_url"
    }
}
