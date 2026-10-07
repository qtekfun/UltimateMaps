package com.qtekfun.mapas.regions

import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionCatalog
import java.util.Locale

/** One visible line of the hierarchical list. */
data class RegionRow(
    val region: Region,
    val depth: Int,
    /** A group (continent, country) that can be opened. */
    val isGroup: Boolean,
    val expanded: Boolean,
    /** Downloadable regions below this node (groups) or 1 (a downloadable leaf); 0 = nothing to download here yet. */
    val downloadableCount: Int,
    val installedCount: Int,
    /** Bytes to download: the leaf's own size, or for groups the sum of the downloadable leaves. */
    val totalBytes: Long,
    /** Version of the installed copy (leaves only). */
    val installedVersion: String?,
    val updateAvailable: Boolean,
    val download: DownloadState?,
    /** Search results only: "Spain › Catalonia › Provincia de Barcelona". */
    val path: String? = null,
)

/** Pure transformation catalog + installed + downloads -> visible rows. JVM-testable. */
object RegionsModel {
    fun rows(
        catalog: RegionCatalog,
        expanded: Set<String>,
        installedVersions: Map<String, String>,
        downloads: Map<String, DownloadState>,
    ): List<RegionRow> {
        val out = ArrayList<RegionRow>()
        fun walk(parentId: String?, depth: Int) {
            for (r in catalog.children(parentId)) {
                val leaves = catalog.downloadableUnder(r.id)
                val isGroup = catalog.children(r.id).isNotEmpty()
                val open = isGroup && r.id in expanded
                val iv = installedVersions[r.id]
                out += RegionRow(
                    region = r, depth = depth, isGroup = isGroup, expanded = open,
                    downloadableCount = leaves.size,
                    installedCount = leaves.count { it.id in installedVersions },
                    totalBytes = leaves.sumOf { it.totalBytes },
                    installedVersion = iv,
                    updateAvailable = iv != null && r.isDownloadable && iv != r.version,
                    download = downloads[r.id],
                )
                if (open) walk(r.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    /** Flat, ranked rows for a search [query] (see [RegionSearch.filter]); each carries its parent path. */
    fun searchRows(
        index: RegionSearch.Index,
        query: String,
        installedVersions: Map<String, String>,
        downloads: Map<String, DownloadState>,
    ): List<RegionRow> = RegionSearch.filter(index, query).map { e ->
        val r = e.region
        val iv = installedVersions[r.id]
        RegionRow(
            region = r, depth = 0, isGroup = e.isGroup, expanded = false,
            downloadableCount = e.leaves.size,
            installedCount = e.leaves.count { it.id in installedVersions },
            totalBytes = e.leaves.sumOf { it.totalBytes },
            installedVersion = iv,
            updateAvailable = iv != null && r.isDownloadable && iv != r.version,
            download = downloads[r.id],
            path = e.path,
        )
    }

    /** Ids of the ancestors of [id], root first (to reveal a search result in the tree). */
    fun ancestors(catalog: RegionCatalog, id: String): List<String> {
        val out = ArrayList<String>()
        var cur = catalog[id]?.parentId
        while (cur != null) { out.add(0, cur); cur = catalog[cur]?.parentId }
        return out
    }

    /** Installed region ids that the (possibly stale) catalog no longer lists; they can still be deleted. */
    fun orphans(catalog: RegionCatalog?, installedVersions: Map<String, String>): List<String> =
        installedVersions.keys.filter { catalog == null || catalog[it] == null }.sorted()

    /** "12.3 MB", "1.4 GB"; 1 MB = 10^6 bytes as storage settings show it. */
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes < 1_000L -> String.format(locale, "%d B", bytes)
        bytes < 1_000_000L -> String.format(locale, "%.0f kB", bytes / 1e3)
        bytes < 1_000_000_000L -> String.format(locale, "%.1f MB", bytes / 1e6)
        else -> String.format(locale, "%.2f GB", bytes / 1e9)
    }

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
}
