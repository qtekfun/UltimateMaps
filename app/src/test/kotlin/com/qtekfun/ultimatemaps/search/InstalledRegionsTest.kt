package com.qtekfun.ultimatemaps.search

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledRegionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String) = tmp.root.resolve(path).also { it.parentFile.mkdirs(); it.writeText("x") }

    @Test
    fun missingDirectoryMeansNoRegions() {
        assertNull(DirectoryInstalledRegions(tmp.root.resolve("nope")).coreMaps())
    }

    @Test
    fun worldAloneIsNotARegion() {
        file("261004/World.mwm"); file("261004/WorldCoasts.mwm")
        assertNull(DirectoryInstalledRegions(tmp.root).coreMaps())
    }

    @Test
    fun regionsWithoutWorldAreNotUsable() {
        file("261004/Spain_Madrid.mwm")
        assertNull(DirectoryInstalledRegions(tmp.root).coreMaps())
    }

    @Test
    fun countsRegionFilesOfVersionsThatHaveWorld() {
        file("261004/World.mwm"); file("261004/WorldCoasts.mwm")
        file("261004/Spain_Madrid.mwm"); file("261004/Spain_Aragon.mwm"); file("261004/notes.txt")
        file("old/Spain_Galicia.mwm") // an incomplete version is ignored
        val maps = DirectoryInstalledRegions(tmp.root).coreMaps()
        assertEquals(2, maps?.regionCount)
        assertEquals(tmp.root, maps?.mapsDir)
    }
}
