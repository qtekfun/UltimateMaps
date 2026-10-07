package com.qtekfun.ultimatemaps.settings.backup

import android.content.Context
import android.content.SharedPreferences

/** Implemented by the live stores that keep their settings in memory, so a restore can make them re-read the files. */
interface SettingsReloadable {
    fun reload()
}

/**
 * [SettingsStorage] over the app's SharedPreferences files. Reads go straight to the files (a missing key or a value of
 * another type reads as the default). Writes go to the file too, except for keys in [overrides]: those belong to a live
 * object that applies the value itself (and persists it), for instance the offline switch that also configures the
 * network policy. [finish] flushes and then calls [onFinish] so in-memory stores re-read.
 */
class AndroidSettingsStorage(
    private val prefsFor: (String) -> SharedPreferences,
    private val overrides: Map<String, (Any) -> Unit> = emptyMap(),
    private val onFinish: () -> Unit = {},
) : SettingsStorage {
    constructor(context: Context, overrides: Map<String, (Any) -> Unit> = emptyMap(), onFinish: () -> Unit = {}) :
        this({ name -> context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE) }, overrides, onFinish)

    private val editors = HashMap<String, SharedPreferences.Editor>()

    override fun read(spec: SettingSpec): Any {
        val prefs = prefsFor(spec.prefsName)
        if (!prefs.contains(spec.key)) return spec.default
        return runCatching {
            when (spec.type) {
                SettingType.BOOLEAN -> prefs.getBoolean(spec.key, spec.default as Boolean)
                SettingType.INT -> prefs.getInt(spec.key, spec.default as Int)
                SettingType.STRING -> prefs.getString(spec.key, null) ?: spec.default
                SettingType.STRING_SET -> prefs.getStringSet(spec.key, null)?.toSet() ?: spec.default
            }
        }.getOrDefault(spec.default)
    }

    override fun write(spec: SettingSpec, value: Any) {
        overrides[key(spec)]?.let { it(value); return }
        val editor = editors.getOrPut(spec.prefsName) { prefsFor(spec.prefsName).edit() }
        when (spec.type) {
            SettingType.BOOLEAN -> editor.putBoolean(spec.key, value as Boolean)
            SettingType.INT -> editor.putInt(spec.key, value as Int)
            SettingType.STRING -> editor.putString(spec.key, value as String)
            SettingType.STRING_SET -> editor.putStringSet(spec.key, (value as Set<*>).filterIsInstance<String>().toSet())
        }
    }

    override fun finish() {
        editors.values.forEach { it.apply() }
        editors.clear()
        onFinish()
    }

    companion object {
        /** The key of [overrides] for [spec]. */
        fun key(spec: SettingSpec) = spec.id
    }
}
