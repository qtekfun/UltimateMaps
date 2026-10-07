package com.qtekfun.ultimatemaps.search

import java.io.File

/**
 * Offline data of the CoMaps core: [mapsDir] holds `<version>/<Region>.mwm` and each version has `World.mwm`.
 * [regionCount] counts region files only (not `World`/`WorldCoasts`).
 */
data class CoreMaps(val mapsDir: File, val regionCount: Int)

/**
 * What the search needs to know about the installed regions. The regions module (catalogue, downloads)
 * implements this on top of its own manifest; [DirectoryInstalledRegions] is the simple scanning version.
 * Called off the main thread (it may touch the disk).
 */
interface InstalledRegions {
    /** The installed core maps, or null when there is no usable `World.mwm` or no region at all. */
    fun coreMaps(): CoreMaps?
}

/** Scans `filesDir/maps-core/<version>/` for `.mwm` files. */
class DirectoryInstalledRegions(private val mapsCoreDir: File) : InstalledRegions {
    override fun coreMaps(): CoreMaps? {
        val versions = mapsCoreDir.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { File(it, WORLD).isFile }
        if (versions.isEmpty()) return null
        val regions = versions.sumOf { v ->
            v.listFiles { f -> f.isFile && f.extension == "mwm" && f.nameWithoutExtension !in GLOBAL }.orEmpty().size
        }
        return if (regions == 0) null else CoreMaps(mapsCoreDir, regions)
    }

    private companion object {
        const val WORLD = "World.mwm"
        val GLOBAL = setOf("World", "WorldCoasts")
    }
}
