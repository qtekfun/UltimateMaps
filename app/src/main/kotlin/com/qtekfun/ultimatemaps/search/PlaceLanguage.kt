package com.qtekfun.ultimatemaps.search

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.PlaceLanguagePref
import com.qtekfun.ultimatemaps.core.search.PlaceLanguageStore
import com.qtekfun.ultimatemaps.core.search.QueryParticles
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.nativecomaps.CoreHandle
import java.util.Locale

/** [PlaceLanguageStore] over SharedPreferences. An unknown stored value reads as [PlaceLanguagePref.AUTO]. */
class PrefsPlaceLanguageStore(private val prefs: SharedPreferences) : PlaceLanguageStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var preference: PlaceLanguagePref
        get() = PlaceLanguagePref.fromName(runCatching { prefs.getString(KEY_PREFERENCE, null) }.getOrNull())
        set(value) { prefs.edit().putString(KEY_PREFERENCE, value.name).apply() }

    companion object {
        const val PREFS = "place_language"
        const val KEY_PREFERENCE = "preference"
    }
}

/**
 * A [SearchEngine] over the core that reads the language of place information at every call, so a change in Settings applies to
 * the next search without recreating anything. Engines of the core are cheap handles; the locale string selects the language
 * of the names, the address and the category of the results (see [PlaceLanguagePref.coreLocale]).
 */
internal class PlaceLocaleSearchEngine(
    private val core: CoreHandle,
    private val coreLocale: () -> String,
) : SearchEngine {
    /**
     * A text with linking words ("plaza de espana") is searched without them, which the engine answers 5 to 10 times faster, and
     * the answer is put in the order of the typed phrase; asking for more results costs nothing, so the best of a wider list is kept.
     */
    override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> {
        val engine = core.searchEngine(coreLocale())
        val fast = QueryParticles.withoutParticles(query) ?: return engine.search(query, near, limit)
        return QueryParticles.rerank(query, engine.search(fast, near, maxOf(limit, (limit * 2).coerceAtMost(MAX_WIDE)))).take(limit)
    }

    override fun searchCategory(categoryName: String, near: LatLon?, limit: Int): List<SearchResult> =
        core.searchEngine(coreLocale()).searchCategory(categoryName, near, limit)

    override fun close() = Unit

    private companion object {
        const val MAX_WIDE = 40
    }
}

/** The core locale string for the stored choice on this phone. */
internal fun currentCoreLocale(store: PlaceLanguageStore, appLocale: Locale = Locale.getDefault()): String =
    store.preference.coreLocale(appLocale)
