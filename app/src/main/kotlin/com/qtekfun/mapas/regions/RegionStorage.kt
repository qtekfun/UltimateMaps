package com.qtekfun.mapas.regions

import android.content.Context
import android.os.Environment
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.regions.AssetKind
import com.qtekfun.mapas.core.regions.InstalledRegion
import com.qtekfun.mapas.core.regions.RegionManager
import com.qtekfun.mapas.core.regions.ResumableDownloader
import com.qtekfun.mapas.core.regions.StorageLocation
import java.io.File

/** Where regions live on this device and what is installed. Pure file access: no network, no state. */
object RegionStorage {
    const val INTERNAL_ID = "internal"
    private const val DIR = "regions"

    /**
     * Internal storage first (always works, and is the only place the native map engine is known to read),
     * then each removable card that Android exposes as an app-specific directory.
     */
    fun locations(context: Context, internalLabel: String = "Internal storage", cardLabel: String = "SD card"): List<StorageLocation> {
        val internal = StorageLocation(INTERNAL_ID, internalLabel, File(context.filesDir, DIR), removable = false)
        val cards = context.getExternalFilesDirs(null).orEmpty().filterNotNull().mapIndexedNotNull { i, dir ->
            val removable = runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(false)
            if (removable) StorageLocation("card$i", if (i <= 1) cardLabel else "$cardLabel $i", File(dir, DIR), removable = true) else null
        }
        return listOf(internal) + cards
    }

    /** A read-only view: the downloader it carries is bound to a policy that denies every connection. */
    fun readOnlyManager(root: File) = RegionManager(root, ResumableDownloader(DefaultNetworkPolicy()))

    /** Every installed region on every storage, with the id of the storage it lives on. */
    fun installedEverywhere(locations: List<StorageLocation>): List<Pair<StorageLocation, InstalledRegion>> =
        locations.flatMap { loc -> readOnlyManager(loc.dir).installed().map { loc to it } }

    /** The PMTiles render assets of every installed region (all storages), sorted by region id. */
    fun installedRenders(context: Context): List<File> =
        installedEverywhere(locations(context)).map { it.second }.sortedBy { it.id }
            .mapNotNull { it.files[AssetKind.RENDER]?.takeIf(File::isFile) }
}
