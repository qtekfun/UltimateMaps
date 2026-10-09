package com.qtekfun.ultimatemaps.diagnostics

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashNotesTest {
    private val dir = Files.createTempDirectory("crash-notes-test").toFile().also { it.deleteOnExit() }
    private var now = 1_791_000_000_000L

    private fun notes(maxBytes: Int = 48 * 1024) = CrashNotes(File(dir, "sub/notes.txt"), clock = { now }, maxBytes = maxBytes)

    @Test fun `a note has the time, where and the exception classes and frames but never the message`() {
        val n = notes()
        val secret = "Calle Mayor 1, 40.4168,-3.7038"
        n.record("route", IllegalStateException(secret, java.io.IOException("also $secret")))
        val text = n.read()
        assertTrue(text.startsWith("2026-"), text)
        assertTrue(text.contains(" route\n"), text)
        assertTrue(text.contains("java.lang.IllegalStateException"), text)
        assertTrue(text.contains("caused by java.io.IOException"), text)
        assertTrue(text.contains("CrashNotesTest"), "the stack frames are there: $text")
        assertFalse(text.contains("Calle Mayor") || text.contains("40.4168"), "no message, no position: $text")
    }

    @Test fun `plain notes are indented under their header and read back in order`() {
        val n = notes()
        n.note("core", "core process died")
        now += 1_000
        n.note("core", "core process killed")
        val text = n.read()
        assertTrue(text.indexOf("died") < text.indexOf("killed"))
        assertTrue(text.contains("\n  core process died\n"), text)
    }

    @Test fun `the file stays bounded and keeps whole recent notes`() {
        val n = notes(maxBytes = 2_000)
        repeat(200) { i -> n.note("w", "entry number $i") }
        val text = n.read()
        assertTrue(text.length <= 2_000 + 200, "size ${text.length}")
        assertTrue(text.contains("entry number 199"))
        assertFalse(text.contains("entry number 0\n"))
        assertTrue(text.trimStart().startsWith("20"), "starts at a whole note: ${text.take(40)}")
    }

    @Test fun `clear empties it and a missing or unwritable file never throws`() {
        val n = notes()
        n.note("w", "x")
        n.clear()
        assertEquals("", n.read())
        // A path under a regular file cannot be created.
        val blocker = File(dir, "blocker").also { it.writeText("x") }
        val bad = CrashNotes(File(blocker, "notes.txt"))
        bad.note("w", "x")
        bad.record("w", RuntimeException())
        assertEquals("", bad.read())
        bad.clear()
    }

    @Test fun `the uncaught handler notes the exception and then calls the previous handler`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val n = notes()
        var delegated: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated = e }
            n.installAsUncaughtHandler()
            val boom = IllegalArgumentException("secret place")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(boom, delegated)
            assertTrue(n.read().contains("java.lang.IllegalArgumentException"))
            assertFalse(n.read().contains("secret place"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
