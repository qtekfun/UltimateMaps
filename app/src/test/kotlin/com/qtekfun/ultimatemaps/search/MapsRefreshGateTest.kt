package com.qtekfun.ultimatemaps.search

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MapsRefreshGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun map(path: String, text: String = "x", modified: Long = 1_000L) =
        tmp.root.resolve(path).also { it.parentFile.mkdirs(); it.writeText(text); it.setLastModified(modified) }

    @Test
    fun theFirstCallAlwaysRefreshes() {
        var runs = 0
        assertTrue(MapsRefreshGate().refreshIfChanged("a") { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun theSameMapsDoNotRefreshAgain() {
        val gate = MapsRefreshGate()
        var runs = 0
        gate.refreshIfChanged("a") { runs++ }
        assertFalse(gate.refreshIfChanged("a") { runs++ })
        assertFalse(gate.refreshIfChanged("a") { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun changedMapsRefreshAgain() {
        val gate = MapsRefreshGate()
        var runs = 0
        gate.refreshIfChanged("a") { runs++ }
        assertTrue(gate.refreshIfChanged("b") { runs++ })
        assertFalse(gate.refreshIfChanged("b") { runs++ })
        assertEquals(2, runs)
    }

    @Test
    fun aFailedRefreshIsNotRemembered() {
        val gate = MapsRefreshGate()
        assertFailsWith<IllegalStateException> { gate.refreshIfChanged("a") { error("core down") } }
        var runs = 0
        assertTrue(gate.refreshIfChanged("a") { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun theFingerprintIsStableForUnchangedFiles() {
        map("261004/World.mwm"); map("261004/Spain_Madrid.mwm")
        assertEquals(MapsRefreshGate.fingerprint(tmp.root), MapsRefreshGate.fingerprint(tmp.root))
    }

    @Test
    fun anAddedRemovedOrReplacedMapChangesTheFingerprint() {
        map("261004/World.mwm"); map("261004/Spain_Madrid.mwm")
        val before = MapsRefreshGate.fingerprint(tmp.root)

        val aragon = map("261004/Spain_Aragon.mwm")
        val added = MapsRefreshGate.fingerprint(tmp.root)
        assertNotEquals(before, added)

        aragon.delete()
        assertEquals(before, MapsRefreshGate.fingerprint(tmp.root))

        map("261004/Spain_Madrid.mwm", text = "a longer map")
        assertNotEquals(before, MapsRefreshGate.fingerprint(tmp.root))

        map("261004/Spain_Madrid.mwm", text = "x", modified = 9_000L)
        assertNotEquals(before, MapsRefreshGate.fingerprint(tmp.root))
    }

    @Test
    fun aNewVersionFolderChangesTheFingerprint() {
        map("261004/World.mwm")
        val before = MapsRefreshGate.fingerprint(tmp.root)
        map("261011/World.mwm")
        assertNotEquals(before, MapsRefreshGate.fingerprint(tmp.root))
    }

    @Test
    fun filesThatAreNotMapsDoNotChangeIt() {
        map("261004/World.mwm")
        val before = MapsRefreshGate.fingerprint(tmp.root)
        map("261004/Spain_Madrid.mwm.part"); map("261004/notes.txt"); map("stray.mwm")
        assertEquals(before, MapsRefreshGate.fingerprint(tmp.root))
    }

    @Test
    fun aMissingDirectoryHasAnEmptyFingerprint() {
        assertEquals("", MapsRefreshGate.fingerprint(tmp.root.resolve("nope")))
    }
}
