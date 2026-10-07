package com.qtekfun.mapas.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.qtekfun.mapas.core.search.SearchEngine
import com.qtekfun.mapas.nativecomaps.CoMapsCore
import java.util.Locale

/** The production [SearchBackend]: one [CoMapsCore] per process, started lazily on the first search. */
class CoMapsSearchBackend(private val context: Context) : SearchBackend {
    private var engine: SearchEngine? = null

    @Synchronized
    override fun open(maps: CoreMaps): SearchEngine {
        val app = context.applicationContext
        CORE.init(app.applicationInfo.sourceDir, maps.mapsDir.absolutePath, app.cacheDir.absolutePath, locale())
        CORE.refreshMaps() // also picks up regions installed after the first start
        return engine ?: CORE.searchEngine(locale()).also { engine = it }
    }

    private fun locale() = if (Locale.getDefault().language == "es") "es" else "en"

    private companion object {
        val CORE = CoMapsCore()
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
