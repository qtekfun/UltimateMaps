package com.qtekfun.ultimatemaps.core.weather

import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The purpose under which the AEMET host is listed in the network policy and its local log. */
val WEATHER_ALERTS_PURPOSE = ConnectionPurpose.WEATHER_ALERTS

/** What the user chose. Everything is off by default: no switch, no request. */
data class WeatherAlertSettings(
    /** "Weather alerts (AEMET)". While off nothing is ever asked and the host is not even in the policy. */
    val enabled: Boolean = false,
    /** Yellow warnings are only listed in the detail card when this is on; the map banner never shows yellow. */
    val showYellow: Boolean = false,
)

interface WeatherAlertSettingsStore {
    val settings: StateFlow<WeatherAlertSettings>
    fun update(transform: (WeatherAlertSettings) -> WeatherAlertSettings)
}

class InMemoryWeatherAlertSettings(initial: WeatherAlertSettings = WeatherAlertSettings()) : WeatherAlertSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<WeatherAlertSettings> = state
    override fun update(transform: (WeatherAlertSettings) -> WeatherAlertSettings) {
        state.value = transform(state.value)
    }
}

/**
 * Where the user's AEMET API key lives. Implementations keep it encrypted (Android Keystore) and out of every backup and log.
 * The key identifies the user to AEMET, which is why the settings text says so.
 */
interface ApiKeyStore {
    /** The key, or null when none is stored or it cannot be decrypted. */
    fun read(): String?
    fun write(key: String)
    fun clear()
}

class InMemoryApiKeyStore(private var key: String? = null) : ApiKeyStore {
    override fun read(): String? = key
    override fun write(key: String) {
        this.key = key
    }
    override fun clear() {
        key = null
    }
}

object ApiKeys {
    private val shape = Regex("[A-Za-z0-9._\\-]{20,2048}")

    /** The key from what the user typed or pasted (all whitespace removed), or null when it cannot be an AEMET key (a JWT). */
    fun clean(input: String): String? = input.filterNot { it.isWhitespace() }.takeIf { shape.matches(it) }
}
