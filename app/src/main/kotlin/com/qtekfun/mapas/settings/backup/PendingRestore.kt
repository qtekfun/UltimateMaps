package com.qtekfun.mapas.settings.backup

import android.content.Context
import android.content.SharedPreferences

/**
 * What a restore left for the owner to confirm. Nothing here changes any behaviour by itself: it only drives hints.
 *
 * - [consent]: ids ([SettingSpec.id]) of switches that were on in the backup but must be accepted again on this phone.
 * - [regions]: ids of regions installed on the old phone; the Maps screen offers to download them (never automatically).
 */
interface PendingRestore {
    var consent: Set<String>
    var regions: Set<String>
}

class InMemoryPendingRestore(override var consent: Set<String> = emptySet(), override var regions: Set<String> = emptySet()) : PendingRestore

/** [PendingRestore] over SharedPreferences (`mapas_restore_pending`). */
class PrefsPendingRestore(private val prefs: SharedPreferences) : PendingRestore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var consent: Set<String>
        get() = read(KEY_CONSENT)
        set(value) = write(KEY_CONSENT, value)

    override var regions: Set<String>
        get() = read(KEY_REGIONS)
        set(value) = write(KEY_REGIONS, value)

    private fun read(key: String): Set<String> =
        runCatching { prefs.getStringSet(key, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())

    private fun write(key: String, value: Set<String>) {
        prefs.edit().apply { if (value.isEmpty()) remove(key) else putStringSet(key, value.toSet()) }.apply()
    }

    companion object {
        const val PREFS = "mapas_restore_pending"
        const val KEY_CONSENT = "consent"
        const val KEY_REGIONS = "regions"
    }
}
