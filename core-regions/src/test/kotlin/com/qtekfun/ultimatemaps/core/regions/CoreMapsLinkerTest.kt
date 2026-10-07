package com.qtekfun.ultimatemaps.core.regions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreMapsLinkerTest {
    @TempDir lateinit var tmp: File

    private fun file(path: String, text: String = path): File = File(tmp, path).also { it.parentFile.mkdirs(); it.writeText(text) }
    private val core get() = File(tmp, "maps-core")
    private fun linker() = CoreMapsLinker(core)

    private fun region(id: String, comaps: String?, version: String = "261004", dir: String = "regions") = InstalledRegion(
        id, version,
        mapOf(AssetKind.RENDER to file("$dir/$id/$version/$id.pmtiles"), AssetKind.SEARCH to file("$dir/$id/$version/$id.mwm", "mwm-$id-$version")),
        comaps,
    )

    private fun base(version: String = "261004", dir: String = "regions") =
        InstalledBase(version, file("$dir/.base/$version/World.mwm", "world-$version"), file("$dir/.base/$version/WorldCoasts.mwm", "coasts-$version"))

    private fun names(version: String) = File(core, version).list().orEmpty().sorted()

    @Test
    fun `builds the exact layout the core expects, spaces included`() {
        val r = linker().sync(listOf(region("spain_la-rioja", "Spain_La Rioja"), region("andorra", "Andorra")), listOf(base()))
        assertEquals(listOf("Andorra.mwm", "Spain_La Rioja.mwm", "World.mwm", "WorldCoasts.mwm"), names("261004"))
        assertEquals("mwm-spain_la-rioja-261004", File(core, "261004/Spain_La Rioja.mwm").readText()) // reads through the link
        assertTrue(Files.isSymbolicLink(File(core, "261004/World.mwm").toPath()))
        assertEquals(4, r.created.size)
        assertTrue(r.failed.isEmpty() && r.hardLinked.isEmpty() && r.droppedMaps.isEmpty())
    }

    @Test
    fun `is idempotent`() {
        val regions = listOf(region("a", "Spain_A"))
        linker().sync(regions, listOf(base()))
        val second = linker().sync(regions, listOf(base()))
        assertFalse(second.changed)
        assertEquals(LinkReport(), second)
    }

    @Test
    fun `removes orphan links, dangling ones and empty version directories`() {
        val a = region("a", "Spain_A")
        val b = region("b", "Spain_B")
        linker().sync(listOf(a, b), listOf(base()))
        a.files.values.forEach { it.delete() } // the real file vanished: the link dangles
        val r = linker().sync(listOf(b), listOf(base()))
        assertEquals(listOf("Spain_B.mwm", "World.mwm", "WorldCoasts.mwm"), names("261004"))
        assertEquals(listOf("261004/Spain_A.mwm"), r.removed)
        assertEquals(listOf("Spain_A.mwm"), r.droppedMaps) // the core cannot unregister it: restart needed
        linker().sync(emptyList(), listOf(base()))
        assertFalse(File(core, "261004").exists())
    }

    @Test
    fun `an update moves the link to the new version without asking for a restart`() {
        linker().sync(listOf(region("a", "Spain_A", "261004")), listOf(base("261004")))
        val r = linker().sync(listOf(region("a", "Spain_A", "261105")), listOf(base("261105")))
        assertFalse(File(core, "261004").exists())
        assertEquals(listOf("Spain_A.mwm", "World.mwm", "WorldCoasts.mwm"), names("261105"))
        assertTrue(r.droppedMaps.isEmpty()) // refreshMaps registers the newer version and drops the older one
        assertTrue(r.removed.contains("261004/Spain_A.mwm"))
    }

    @Test
    fun `regions on an older data version borrow the newest World`() {
        linker().sync(listOf(region("a", "Spain_A", "261004"), region("b", "Spain_B", "261105")), listOf(base("261105")))
        assertEquals("world-261105", File(core, "261004/World.mwm").readText())
        assertEquals("world-261105", File(core, "261105/World.mwm").readText())
    }

    @Test
    fun `retargets a link when the file moves to another storage`() {
        linker().sync(listOf(region("a", "Spain_A")), listOf(base()))
        val moved = region("a", "Spain_A", dir = "card")
        val r = linker().sync(listOf(moved), listOf(base(dir = "card")))
        assertEquals(3, r.retargeted.size)
        assertEquals("mwm-a-261004", File(core, "261004/Spain_A.mwm").readText())
        assertTrue(File(core, "261004/Spain_A.mwm").canonicalPath.contains("/card/"))
    }

    @Test
    fun `regions without comapsId are skipped, and nothing is linked without regions`() {
        val r = linker().sync(listOf(region("old", null)), listOf(base()))
        assertTrue(names("261004").isEmpty())
        assertFalse(r.changed)
    }

    @Test
    fun `never touches a real file that is not ours`() {
        File(core, "261004").mkdirs()
        File(core, "261004/Mine.mwm").writeText("pushed by hand")
        File(core, "261004/Spain_A.mwm").writeText("also by hand")
        val r = linker().sync(listOf(region("a", "Spain_A")), listOf(base()))
        assertEquals("pushed by hand", File(core, "261004/Mine.mwm").readText())
        assertEquals("also by hand", File(core, "261004/Spain_A.mwm").readText())
        assertEquals(listOf("261004/Spain_A.mwm"), r.failed)
    }

    @Test
    fun `hostile names cannot escape the core directory`() {
        val evil = region("evil", "../../evil")
        linker().sync(listOf(evil), listOf(base()))
        assertFalse(File(tmp, "evil.mwm").exists())
        assertTrue(names("261004").isEmpty())
    }
}
