package com.qtekfun.ultimatemaps.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.PlaceLanguagePref
import com.qtekfun.ultimatemaps.core.search.PlaceLanguageStore
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.nativecomaps.CoreHandle
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

class MemoryPlaceLanguageStore(override var preference: PlaceLanguagePref = PlaceLanguagePref.AUTO) : PlaceLanguageStore

/** Records the locale string of every engine it hands out and answers with a result named after it. */
private class RecordingCore : CoreHandle {
    val locales = mutableListOf<String>()

    override fun init(apkPath: String, mapsDir: String, tmpDir: String, locale: String) = Unit
    override fun refreshMaps(): Int = 0
    override fun routingEngine(timeoutSec: Int, withGuidance: Boolean): DetailedRoutingEngine = error("not used")

    override fun searchEngine(locale: String, timeoutMs: Int): SearchEngine {
        locales += locale
        return object : SearchEngine {
            override fun search(query: String, near: LatLon?, limit: Int) = listOf(SearchResult("$query@$locale", LatLon(0.0, 0.0)))
            override fun searchCategory(categoryName: String, near: LatLon?, limit: Int) =
                listOf(SearchResult("category:$categoryName@$locale", LatLon(0.0, 0.0)))
            override fun close() = Unit
        }
    }
}

class PlaceLocaleSearchEngineTest {
    private val spanish = Locale.forLanguageTag("es-ES")

    @Test fun everySearchReadsTheCurrentChoice() {
        val store = MemoryPlaceLanguageStore()
        val core = RecordingCore()
        val engine = PlaceLocaleSearchEngine(core) { currentCoreLocale(store, spanish) }

        assertEquals("farmacia@es", engine.search("farmacia").single().name)
        store.preference = PlaceLanguagePref.LOCAL
        assertEquals("Lleida@es;local", engine.search("Lleida").single().name)
        store.preference = PlaceLanguagePref.EN
        assertEquals("category:pharmacy@en", engine.searchCategory("pharmacy").single().name)
        assertEquals(listOf("es", "es;local", "en"), core.locales)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsPlaceLanguageStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun defaultsToAutoAndRemembersTheChoice() {
        assertEquals(PlaceLanguagePref.AUTO, PrefsPlaceLanguageStore(context).preference)
        PrefsPlaceLanguageStore(context).preference = PlaceLanguagePref.LOCAL
        assertEquals(PlaceLanguagePref.LOCAL, PrefsPlaceLanguageStore(context).preference)
    }

    @Test fun aDamagedValueReadsAsAuto() {
        context.getSharedPreferences(PrefsPlaceLanguageStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString(PrefsPlaceLanguageStore.KEY_PREFERENCE, "klingon").commit()
        assertEquals(PlaceLanguagePref.AUTO, PrefsPlaceLanguageStore(context).preference)
    }
}
