package com.qtekfun.ultimatemaps.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettings
import com.qtekfun.ultimatemaps.settings.backup.MemoryStorage
import com.qtekfun.ultimatemaps.settings.backup.SettingsBackup
import com.qtekfun.ultimatemaps.settings.backup.SettingsSchema
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "eyJhbGciOiJIUzI1NiJ9.aaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbb"

/** Reversible stand-in for the Keystore cipher (the real one needs a device). */
private class XorCipher(var broken: Boolean = false) : SecretCipher {
    override fun encrypt(plain: ByteArray) = byteArrayOf(1, 2, 3) + plain.map { (it.toInt() xor 0x5A).toByte() }
    override fun decrypt(blob: ByteArray): ByteArray? = if (broken) null else blob.drop(3).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherStoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun `settings are off by default and the choices are stored`() {
        val p = prefs("test_weather")
        val s = PrefsWeatherAlertSettings(p)
        assertEquals(WeatherAlertSettings(enabled = false, showYellow = false), s.settings.value)
        s.update { it.copy(enabled = true, showYellow = true) }
        assertTrue(p.getBoolean(PrefsWeatherAlertSettings.KEY_ENABLED, false))
        assertEquals(WeatherAlertSettings(true, true), PrefsWeatherAlertSettings(p).settings.value)
    }

    @Test fun `a damaged value falls back to off`() {
        val p = prefs("test_weather2")
        p.edit().putString(PrefsWeatherAlertSettings.KEY_ENABLED, "yes").commit()
        assertFalse(PrefsWeatherAlertSettings(p).settings.value.enabled)
    }

    @Test fun `the key is stored encrypted, round-trips, and can be removed`() {
        val p = prefs("test_weather_secret")
        val store = KeystoreApiKeyStore(p, XorCipher())
        assertNull(store.read())
        store.write(KEY)
        val raw = p.getString(KeystoreApiKeyStore.KEY_API_KEY, null)
        assertNotNull(raw)
        assertFalse(raw.contains(KEY), "never in clear")
        assertFalse(raw.contains("eyJhbGci"), "not even a recognizable part")
        assertEquals(KEY, KeystoreApiKeyStore(p, XorCipher()).read())
        store.clear()
        assertNull(store.read())
        assertTrue(p.all.isEmpty())
    }

    @Test fun `a key that cannot be decrypted is simply missing`() {
        val p = prefs("test_weather_secret2")
        KeystoreApiKeyStore(p, XorCipher()).write(KEY)
        assertNull(KeystoreApiKeyStore(p, XorCipher(broken = true)).read())
        p.edit().putString(KeystoreApiKeyStore.KEY_API_KEY, "%%% not base64 %%%").commit()
        assertNull(KeystoreApiKeyStore(p, XorCipher()).read())
    }

    @Test fun `the key is never in the settings backup`() {
        // not a whitelisted setting, and listed as an exclusion with a reason
        assertTrue(SettingsSchema.specs.none { it.prefsName == KeystoreApiKeyStore.PREFS || it.key == KeystoreApiKeyStore.KEY_API_KEY })
        assertTrue("${KeystoreApiKeyStore.PREFS}/${KeystoreApiKeyStore.KEY_API_KEY}" in SettingsSchema.excluded)
        // and an export of a phone with every weather setting changed carries the switches but no key text
        val storage = MemoryStorage(mapOf("weather/enabled" to true, "weather/show_yellow" to true))
        val text = SettingsBackup.export(storage, emptyList(), "0.1.0", 1L, emptySet())
        assertTrue(text.contains("show_yellow"))
        assertFalse(text.contains("api_key"))
        assertFalse(text.contains(KeystoreApiKeyStore.KEY_API_KEY))
    }

    @Test fun `the weather switch is a consent setting and off by default in the schema`() {
        val spec = SettingsSchema.byId("weather/enabled")
        assertNotNull(spec)
        assertEquals(false, spec.default)
        assertEquals(com.qtekfun.ultimatemaps.settings.backup.RestorePolicy.NEEDS_CONSENT, spec.policy)
        assertEquals(false, SettingsSchema.byId("weather/show_yellow")!!.default)
    }

    @Test fun `times are short for today and carry the weekday for another day`() {
        val zone = ZoneId.of("Europe/Madrid")
        val now = java.time.ZonedDateTime.of(2026, 10, 8, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sameDay = java.time.ZonedDateTime.of(2026, 10, 8, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val nextDay = java.time.ZonedDateTime.of(2026, 10, 9, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("18:00", WeatherTimes.text(sameDay, now, zone, Locale.GERMANY))
        assertTrue(WeatherTimes.text(nextDay, now, zone, Locale.GERMANY).endsWith("06:30"))
        assertTrue(WeatherTimes.text(nextDay, now, zone, Locale.GERMANY).length > 5)
    }
}
