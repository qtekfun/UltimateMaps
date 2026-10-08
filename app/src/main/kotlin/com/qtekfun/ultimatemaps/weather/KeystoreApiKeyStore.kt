package com.qtekfun.ultimatemaps.weather

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.qtekfun.ultimatemaps.core.weather.ApiKeyStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts small secrets. The Android implementation keeps its key inside the Android Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** The plain bytes, or null when [blob] cannot be decrypted (a lost key, a damaged value). */
    fun decrypt(blob: ByteArray): ByteArray?
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. The blob is `iv (12 bytes) + ciphertext + tag`. */
class AndroidKeystoreCipher(private val alias: String = ALIAS) : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray? = try {
        if (blob.size <= IV_BYTES) null else {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "um_aemet_api_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The AEMET API key, encrypted with [cipher] and kept in the private preference file `mapas_weather_secret`. The key is never
 * written in clear, never logged, and the file is neither in the settings backup (see `SettingsSchema.excluded`) nor in an
 * Android backup (`allowBackup` is off). The Keystore key cannot leave the phone, so a copied file is useless elsewhere.
 */
class KeystoreApiKeyStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) : ApiKeyStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), AndroidKeystoreCipher())

    @Synchronized
    override fun read(): String? {
        val text = try { prefs.getString(KEY_API_KEY, null) } catch (_: ClassCastException) { null } ?: return null
        val blob = try { Base64.decode(text, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { return null }
        return cipher.decrypt(blob)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    override fun write(key: String) {
        val blob = cipher.encrypt(key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_API_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        const val PREFS = "mapas_weather_secret"
        const val KEY_API_KEY = "api_key_enc"
    }
}
