package com.qtekfun.mapas.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.qtekfun.mapas.core.search.SearchEngine
import com.qtekfun.mapas.nativecomaps.CoMapsCore
import com.qtekfun.mapas.nativecomaps.CoreHandle
import com.qtekfun.mapas.nativecomaps.isolation.BinderCoreTransport
import com.qtekfun.mapas.nativecomaps.isolation.IsolatedCore
import com.qtekfun.mapas.regions.CoreLinks
import java.util.Locale

/**
 * The production [SearchBackend]. The native core runs in its own process (`:core`) behind [IsolatedCore], so a
 * native abort cannot take the app (or a navigation in progress) down; it is started lazily on the first search.
 * Setting the preference [PREF_ISOLATED] to false (a safety valve while the isolation is still untested on a
 * device) falls back to the old in-process core.
 */
class CoMapsSearchBackend(private val context: Context) : SearchBackend {
    private var engine: SearchEngine? = null

    @Synchronized
    override fun open(maps: CoreMaps): SearchEngine {
        val core = prepareCore(context, maps)
        return engine ?: core.searchEngine(locale()).also { engine = it }
    }

    companion object {
        const val PREFS = "core"
        const val PREF_ISOLATED = "isolated"

        @Volatile private var core: CoreHandle? = null

        /** One handle per process: the isolated client by default, the in-process core when the valve is off. */
        @Synchronized
        private fun handle(app: Context): CoreHandle = core ?: run {
            val isolated = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ISOLATED, true)
            (if (isolated) IsolatedCore(BinderCoreTransport(app)) else CoMapsCore()).also { core = it }
        }

        private fun locale() = if (Locale.getDefault().language == "es") "es" else "en"

        /** Starts the process-wide core if needed and re-scans [maps]; shared by search and routing. */
        fun prepareCore(context: Context, maps: CoreMaps): CoreHandle {
            val app = context.applicationContext
            val core = handle(app)
            // With the isolated core these do not start the process by themselves: it starts on the first real call.
            core.init(app.applicationInfo.sourceDir, maps.mapsDir.absolutePath, app.cacheDir.absolutePath, locale())
            CoreLinks.coreLoaded = true // from now on a deleted region needs an app restart (see CoreLinks)
            core.refreshMaps() // also picks up regions installed after the first start
            return core
        }
    }
}

/** Latency goes to logcat under UMSEARCH: counts and milliseconds only (no query text, no positions). */
object LogcatSearchLog : SearchLog {
    private const val TAG = "UMSEARCH"

    override fun engineReady(millis: Long, regionCount: Int) {
        Log.i(TAG, "engine_ready_ms=$millis regions=$regionCount")
    }

    override fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean) {
        Log.i(TAG, "search qlen=$queryLength results=$results ms=$millis first=$firstSinceReady")
    }

    override fun failed(kind: String) {
        Log.w(TAG, "search_failed kind=$kind")
    }
}

/** Monotonic milliseconds for the latency measurements. */
fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
