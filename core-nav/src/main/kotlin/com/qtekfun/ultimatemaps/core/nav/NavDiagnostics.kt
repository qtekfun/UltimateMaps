package com.qtekfun.ultimatemaps.core.nav

/**
 * Timings of the last reroutes for the About screen's diagnostics, to tell where the seconds go when a new line takes long
 * to appear: leaving the route to the start of the reroute is the detection (set by the tracker's rules), the calculation
 * is the native core's, and "painted" is the app handing the new line to the map. Numbers only: no position is kept.
 * Thread-safe; the last [KEEP] reroutes are listed.
 */
object NavDiagnostics {
    const val KEEP = 5

    private class Entry(val startedAtMs: Long) {
        @Volatile var computeMs = -1L
        @Volatile var found: Boolean? = null
        @Volatile var adoptedAtMs = -1L
        @Volatile var revision = -1
        @Volatile var paintDelayMs = -1L
        @Volatile var drawMs = -1L
    }

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun rerouteStarted(atMs: Long) {
        entries += Entry(atMs)
        while (entries.size > KEEP) entries.removeAt(0)
    }

    /** The core answered ([found] false: no usable route) [computeMs] after the start. */
    @Synchronized
    fun rerouteFinished(atMs: Long, found: Boolean) {
        val e = entries.lastOrNull() ?: return
        e.computeMs = atMs - e.startedAtMs
        e.found = found
    }

    /** A new route became the one followed, with [revision]. */
    @Synchronized
    fun routeAdopted(revision: Int, atMs: Long) {
        val e = entries.lastOrNull() ?: return
        e.adoptedAtMs = atMs
        e.revision = revision
    }

    /** The map layer got the line of [revision] at [atMs]; [drawMs] is how long handing it over took. */
    @Synchronized
    fun routePainted(revision: Int, atMs: Long, drawMs: Long) {
        val e = entries.lastOrNull { it.revision == revision } ?: return
        e.paintDelayMs = atMs - e.adoptedAtMs
        e.drawMs = drawMs
    }

    @Synchronized
    fun describe(): List<String> {
        if (entries.isEmpty()) return listOf("No reroute since the app started")
        return entries.reversed().map { e ->
            val compute = if (e.computeMs < 0) "calculating or abandoned" else "calculated in ${e.computeMs} ms" + if (e.found == false) " (no route)" else ""
            val paint = if (e.paintDelayMs < 0) "" else "; the screen got the line ${e.paintDelayMs} ms later, handing it to the map took ${e.drawMs} ms"
            "Reroute: $compute$paint"
        }
    }

    @Synchronized
    fun clear() = entries.clear()
}
