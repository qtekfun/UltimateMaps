package com.qtekfun.mapas.regions

import com.qtekfun.mapas.core.regions.AssetKind
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionAsset
import com.qtekfun.mapas.core.regions.RegionCatalog
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionSearchTest {
    private val hash = "c".repeat(64)
    private fun leaf(id: String, name: String, parent: String?) = Region(
        id, name, parent, "2",
        mapOf(
            AssetKind.RENDER to RegionAsset("https://x/$id.pmtiles", 30_000_000, hash, "$id.pmtiles"),
            AssetKind.SEARCH to RegionAsset("https://x/$id.mwm", 10_000_000, hash, "$id.mwm"),
        ),
    )

    private val catalog = RegionCatalog(
        "t",
        listOf(
            Region("france", "France", null, "2"),
            Region("andorra", "Andorra", null, "2"),
            Region("spain", "Spain", null, "2"),
            leaf("spain_catalonia_barcelona", "Catalonia - Provincia de Barcelona", "spain"),
            leaf("spain_catalonia_girona", "Catalonia - Provincia de Girona", "spain"),
            leaf("spain_balearic-islands", "Balearic Islands", "spain"),
            leaf("spain_basque-country", "Basque Country", "spain"),
            leaf("spain_la-rioja", "La Rioja", "spain"),
            Region("spain_catalonia", "Catalonia", "spain", "2"),
            leaf("spain_catalonia_sub", "Sub Barcelona", "spain_catalonia"),
        ),
    )
    private val index = RegionSearch.Index(catalog)
    private fun ids(q: String) = RegionSearch.filter(index, q).map { it.region.id }

    @Test
    fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("cataluna", RegionSearch.normalize("  CATALUÑA "))
        assertEquals("castilla la mancha", RegionSearch.normalize("Castilla-La Mancha"))
        assertEquals("cote d ivoire", RegionSearch.normalize("Côte d'Ivoire"))
    }

    @Test
    fun spanishNameFindsTheEnglishCatalogEntries() {
        val expected = setOf("spain_catalonia_barcelona", "spain_catalonia_girona", "spain_catalonia")
        assertEquals(expected, ids("cataluna").toSet())
        assertEquals(expected, ids("CATALUÑA").toSet())
        assertEquals(expected, ids("catalonia").toSet())
        assertEquals(listOf("spain_balearic-islands"), ids("baleares"))
        assertEquals(listOf("spain_basque-country"), ids("pais vasco"))
        assertEquals(listOf("spain"), ids("españa"))
        assertEquals(listOf("france"), ids("francia"))
    }

    @Test
    fun ancestorsCountAsLongAsOneWordIsInTheOwnName() {
        assertEquals(setOf("spain_catalonia_barcelona", "spain_catalonia_sub"), ids("cataluna barcelona").toSet())
        // "spain" alone only finds Spain itself, not its children.
        assertEquals(listOf("spain"), ids("spain"))
        assertEquals(emptyList(), ids("zzz"))
        assertEquals(emptyList(), ids("   "))
    }

    @Test
    fun downloadableLeavesComeFirstThenGroups() {
        val r = ids("catalonia")
        assertEquals("spain_catalonia", r.last(), "the group goes after the downloadable leaves")
        assertTrue(RegionSearch.filter(index, "catalonia").first().region.isDownloadable)
        // Spain has downloadable leaves, Andorra nothing: Spain before Andorra.
        val a = ids("a")
        assertTrue(a.indexOf("spain") < a.indexOf("andorra"))
        assertTrue(a.indexOf("spain_la-rioja") < a.indexOf("spain"))
    }

    @Test
    fun resultsCarryTheParentPath() {
        val e = RegionSearch.filter(index, "girona").single()
        assertEquals("Spain › Catalonia › Provincia de Girona", e.path)
        val row = RegionsModel.searchRows(index, "girona", mapOf("spain_catalonia_girona" to "1"), emptyMap()).single()
        assertEquals("Spain › Catalonia › Provincia de Girona", row.path)
        assertTrue(row.updateAvailable)
        assertEquals(listOf("spain", "spain_catalonia"), RegionsModel.ancestors(catalog, "spain_catalonia_sub"))
    }

    @Test
    fun filteringThirteenHundredNodesIsInstantaneous() {
        val big = ArrayList<Region>()
        for (c in 0 until 40) {
            big += Region("c$c", "Country $c", null, "2")
            for (p in 0 until 30) {
                big += Region("c${c}_p$p", "Province $p", "c$c", "2")
                if (p % 4 == 0) big += leaf("c${c}_p${p}_l", "Leaf $c $p", "c${c}_p$p")
            }
        }
        assertTrue(big.size >= 1300, "${big.size}")
        val idx = RegionSearch.Index(RegionCatalog("big", big))
        repeat(20) { RegionSearch.filter(idx, "pro") } // warm up the JIT
        val ms = measureNanoTime { repeat(20) { RegionSearch.filter(idx, "pro 12") } } / 20 / 1e6
        assertTrue(ms < 25, "filter took $ms ms")
    }
}
