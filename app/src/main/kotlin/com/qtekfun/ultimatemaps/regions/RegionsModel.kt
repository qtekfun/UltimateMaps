package com.qtekfun.ultimatemaps.regions

import com.qtekfun.ultimatemaps.core.regions.Region
import com.qtekfun.ultimatemaps.core.regions.RegionCatalog
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
                if (r.isBaseFile) continue // World / WorldCoasts of older catalogs are base files, not regions
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

    /**
     * Flat, ranked rows for a search [query] (see [RegionSearch.filter]); each carries its parent path. A matching
     * group that is in [expanded] shows its whole subtree right below it (children do not have to match the query:
     * "Spain" matches, "Andalusia" does not contain it), and a result already inside such a subtree is not repeated.
     */
    fun searchRows(
        index: RegionSearch.Index,
        query: String,
        installedVersions: Map<String, String>,
        downloads: Map<String, DownloadState>,
        expanded: Set<String> = emptySet(),
    ): List<RegionRow> {
        val catalog = index.catalog
        val hits = RegionSearch.filter(index, query)
        val openHits = hits.mapTo(HashSet()) { it.region.id }.filterTo(HashSet()) { it in expanded }
        val out = ArrayList<RegionRow>()
        fun row(r: Region, depth: Int, leaves: List<Region>, isGroup: Boolean, path: String?): RegionRow {
            val iv = installedVersions[r.id]
            return RegionRow(
                region = r, depth = depth, isGroup = isGroup, expanded = isGroup && r.id in expanded,
                downloadableCount = leaves.size,
                installedCount = leaves.count { it.id in installedVersions },
                totalBytes = leaves.sumOf { it.totalBytes },
                installedVersion = iv,
                updateAvailable = iv != null && r.isDownloadable && iv != r.version,
                download = downloads[r.id],
                path = path,
            )
        }
        fun subtree(parentId: String, depth: Int) {
            for (c in catalog.children(parentId)) {
                if (c.isBaseFile) continue
                val isGroup = catalog.children(c.id).isNotEmpty()
                out += row(c, depth, catalog.downloadableUnder(c.id), isGroup, null)
                if (isGroup && c.id in expanded) subtree(c.id, depth + 1)
            }
        }
        for (e in hits) {
            if (openHits.isNotEmpty() && ancestors(catalog, e.region.id).any { it in openHits }) continue
            out += row(e.region, 0, e.leaves, e.isGroup, e.path)
            if (e.isGroup && e.region.id in expanded) subtree(e.region.id, 1)
        }
        return out
    }

    /** Installed leaves the catalog lists, by name: the "Installed" block at the top of the Maps tab. */
    fun installedRows(catalog: RegionCatalog, installedVersions: Map<String, String>, downloads: Map<String, DownloadState>): List<RegionRow> =
        installedVersions.mapNotNull { (id, iv) ->
            val r = catalog[id]?.takeIf { !it.isBaseFile } ?: return@mapNotNull null
            RegionRow(
                region = r, depth = 0, isGroup = false, expanded = false,
                downloadableCount = if (r.isDownloadable) 1 else 0, installedCount = 1,
                totalBytes = r.totalBytes, installedVersion = iv,
                updateAvailable = r.isDownloadable && iv != r.version, download = downloads[r.id],
            )
        }.sortedBy { it.region.name.lowercase() }

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
