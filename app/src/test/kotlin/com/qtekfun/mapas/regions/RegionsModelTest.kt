package com.qtekfun.mapas.regions

import com.qtekfun.mapas.core.regions.AssetKind
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionAsset
import com.qtekfun.mapas.core.regions.RegionCatalog
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegionsModelTest {
    private val hash = "b".repeat(64)

    private fun leaf(id: String, parent: String, size: Long, version: String = "2") = Region(
        id, id.uppercase(), parent, version,
        mapOf(
            AssetKind.RENDER to RegionAsset("https://x/$id.pmtiles", size, hash, "$id.pmtiles"),
            AssetKind.SEARCH to RegionAsset("https://x/$id.mwm", size, hash, "$id.mwm"),
        ),
    )

    private val catalog = RegionCatalog(
        "t",
        listOf(
            Region("europe", "Europe", null, "2"),
            Region("spain", "Spain", "europe", "2"),
            leaf("madrid", "spain", 10),
            leaf("galicia", "spain", 20),
            Region("andorra", "Andorra", "europe", "2"), // no files yet
        ),
    )

    @Test
    fun `collapsed list shows only the roots, sizes sum the downloadable leaves`() {
        val rows = RegionsModel.rows(catalog, emptySet(), emptyMap(), emptyMap())
        assertEquals(listOf("europe"), rows.map { it.region.id })
        assertEquals(2 * 10 + 2 * 20L, rows[0].totalBytes)
        assertEquals(2, rows[0].downloadableCount)
        assertTrue(rows[0].isGroup)
    }

    @Test
    fun `expanding opens the hierarchy with depth`() {
        val rows = RegionsModel.rows(catalog, setOf("europe", "spain"), mapOf("madrid" to "2"), emptyMap())
        assertEquals(listOf("europe", "spain", "madrid", "galicia", "andorra"), rows.map { it.region.id })
        assertEquals(listOf(0, 1, 2, 2, 1), rows.map { it.depth })
        assertEquals(1, rows[1].installedCount)
        assertEquals("2", rows[2].installedVersion)
        assertFalse(rows[4].region.isDownloadable)
        assertEquals(0, rows[4].downloadableCount)
    }

    @Test
    fun `update is offered only when the installed version differs`() {
        val rows = RegionsModel.rows(catalog, setOf("europe", "spain"), mapOf("madrid" to "1", "galicia" to "2"), emptyMap())
        assertTrue(rows.first { it.region.id == "madrid" }.updateAvailable)
        assertFalse(rows.first { it.region.id == "galicia" }.updateAvailable)
    }

    @Test
    fun `download state is attached to its row`() {
        val st = DownloadState.Running(5, 40)
        val rows = RegionsModel.rows(catalog, setOf("europe", "spain"), emptyMap(), mapOf("galicia" to st))
        assertEquals(st, rows.first { it.region.id == "galicia" }.download)
    }

    @Test
    fun `installed regions missing from the catalog are orphans`() {
        assertEquals(listOf("zz"), RegionsModel.orphans(catalog, mapOf("madrid" to "2", "zz" to "1")))
        assertEquals(listOf("madrid"), RegionsModel.orphans(null, mapOf("madrid" to "2")))
    }

    @Test
    fun `sizes and percentages format`() {
        assertEquals("999 B", RegionsModel.formatBytes(999, Locale.US))
        assertEquals("12.3 MB", RegionsModel.formatBytes(12_345_678, Locale.US))
        assertEquals("1.40 GB", RegionsModel.formatBytes(1_400_000_000, Locale.US))
        assertEquals("12,3 MB", RegionsModel.formatBytes(12_345_678, Locale.forLanguageTag("es")))
        assertEquals(50, RegionsModel.percent(5, 10))
        assertEquals(0, RegionsModel.percent(5, 0))
        assertEquals(100, RegionsModel.percent(20, 10))
    }
}
