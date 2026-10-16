package com.qtekfun.ultimatemaps.traffic

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.traffic.TrafficKeyStore
import com.qtekfun.ultimatemaps.core.traffic.TrafficSettingsStore
import com.qtekfun.ultimatemaps.weather.AndroidKeystoreCipher
import com.qtekfun.ultimatemaps.weather.KeystoreApiKeyStore
import com.qtekfun.ultimatemaps.weather.SecretCipher

/** The live-traffic switch over SharedPreferences (`traffic`). Off by default; read on every use. */
class PrefsTrafficSettingsStore(private val prefs: SharedPreferences) : TrafficSettingsStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var enabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    companion object {
        const val PREFS = "traffic"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * The user's own TomTom API key, encrypted with a Keystore key of its own (same mechanism as the AEMET key) in the private file
 * `mapas_traffic_secret`. Never written in clear, logged, exported or backed up (see `SettingsSchema.excluded`).
 */
class TomTomKeyStore(private val delegate: KeystoreApiKeyStore) : TrafficKeyStore {
    constructor(prefs: SharedPreferences, cipher: SecretCipher) : this(KeystoreApiKeyStore(prefs, cipher))
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), AndroidKeystoreCipher(ALIAS),
    )

    override fun read(): String? = delegate.read()
    override fun write(key: String) = delegate.write(key)
    override fun clear() = delegate.clear()

    companion object {
        const val PREFS = "mapas_traffic_secret"
        const val KEY_API_KEY = KeystoreApiKeyStore.KEY_API_KEY
        private const val ALIAS = "um_tomtom_api_key"

        /** Keys are letters, digits and a few separators; anything else is a paste mistake. */
        fun clean(raw: String): String? = raw.trim().takeIf { it.length in 16..128 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
    }
}
