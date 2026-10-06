package com.qtekfun.mapas.core.regions

import java.io.File

/** A place where regions can live: internal storage or a removable card. Supplied by the platform layer. */
data class StorageLocation(val id: String, val label: String, val dir: File, val removable: Boolean)

object StorageSelector {
    private const val DEFAULT_RESERVE = 100L shl 20

    /**
     * Picks where to put [requiredBytes] of data: the user's [preferredId] if it is available and has
     * room (plus [reserveBytes] spare); otherwise null so the UI can ask. Never silently falls back.
     */
    fun choose(
        locations: List<StorageLocation>, preferredId: String?, requiredBytes: Long, reserveBytes: Long = DEFAULT_RESERVE,
    ): StorageLocation? {
        val loc = locations.firstOrNull { it.id == preferredId } ?: return null
        if (!loc.dir.isDirectory && !loc.dir.mkdirs()) return null
        return loc.takeIf { it.dir.usableSpace >= requiredBytes + reserveBytes }
    }

    /** Locations with enough room, most free space first. */
    fun candidates(locations: List<StorageLocation>, requiredBytes: Long, reserveBytes: Long = DEFAULT_RESERVE) =
        locations.filter { it.dir.isDirectory && it.dir.usableSpace >= requiredBytes + reserveBytes }
            .sortedByDescending { it.dir.usableSpace }
}
