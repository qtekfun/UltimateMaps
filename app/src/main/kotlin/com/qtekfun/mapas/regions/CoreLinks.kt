package com.qtekfun.mapas.regions

import android.content.Context
import android.util.Log
import com.qtekfun.mapas.core.regions.CoreMapsLinker
import com.qtekfun.mapas.core.regions.LinkReport
import java.io.File

/**
 * Keeps `filesDir/maps-core/<version>/` (what the CoMaps core reads) in step with the installed regions on
 * every storage: see [CoreMapsLinker]. Pure file work, no network. Call [sync] off the main thread after an
 * install, update or delete, and at app start.
 *
 * The native core is a process-wide singleton: a map file it registered stays registered until the process
 * dies (CoMaps has no unregister on the path we use), so [refreshMaps][com.qtekfun.mapas.nativecomaps.CoMapsCore.refreshMaps]
 * picks up new regions and newer versions of a region, but a **deleted** region keeps answering searches
 * until the app is restarted. [restartNeeded] becomes true when that can happen (a map was unlinked after the
 * core had been loaded in this process) and stays true until the process restarts; the Maps screen shows it.
 */
object CoreLinks {
    private const val TAG = "UMLINK"

    /** Set by the search backend once the native core has been initialised in this process. */
    @Volatile var coreLoaded: Boolean = false

    /** True when the loaded core may still serve a region that was deleted. Never reset within a process. */
    @Volatile var restartNeeded: Boolean = false
        private set

    fun coreDir(context: Context) = File(context.filesDir, "maps-core")

    @Synchronized
    fun sync(context: Context): LinkReport {
        val managers = RegionStorage.locations(context).map { RegionStorage.readOnlyManager(it.dir) }
        val report = CoreMapsLinker(coreDir(context)).sync(
            managers.flatMap { it.installed() }, managers.flatMap { it.installedBases() },
        )
        if (coreLoaded && report.droppedMaps.isNotEmpty()) restartNeeded = true
        // Counts only: no file names, no positions.
        Log.i(TAG, "created=${report.created.size} removed=${report.removed.size} retargeted=${report.retargeted.size} " +
            "hard=${report.hardLinked.size} failed=${report.failed.size} dropped=${report.droppedMaps.size}")
        return report
    }

    /** Test hook. */
    internal fun resetForTests() {
        coreLoaded = false
        restartNeeded = false
    }
}
