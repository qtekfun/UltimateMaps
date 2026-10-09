package com.qtekfun.ultimatemaps.diagnostics

import java.io.File
import java.time.Instant

/**
 * A small local file with what went wrong, to make the next bug report possible without a debugger: the time, where it
 * happened (a fixed word such as "route"), and for an exception ONLY its class names and stack frames (class, method,
 * file and line). Never an exception message (it could echo a place name or a coordinate), never an argument, never a
 * position. The file stays on the phone; the user can read it in Settings, About, and copy it into a report if they want.
 *
 * Thread-safe, never throws (a diagnostic must not become the next crash), and bounded: when the file passes [maxBytes]
 * the oldest notes are dropped.
 */
class CrashNotes(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = 48 * 1024,
    private val maxFrames: Int = 24,
) {
    private val lock = Any()

    /** Notes [t] as happened at [where] (a fixed word, not user data). */
    fun record(where: String, t: Throwable) {
        note(where, describe(t))
    }

    /** Notes a plain fixed [text] (for example that the core process died). */
    fun note(where: String, text: String) {
        try {
            synchronized(lock) {
                val entry = buildString {
                    append(Instant.ofEpochMilli(clock())).append(' ').append(where.take(MAX_WHERE)).append('\n')
                    append(text.lineSequence().joinToString("\n") { "  $it" }).append("\n\n")
                }
                file.parentFile?.mkdirs()
                file.appendText(entry)
                trimLocked()
            }
        } catch (_: Throwable) {
        }
    }

    /** The whole text (oldest first); empty when there is nothing. */
    fun read(): String = try {
        synchronized(lock) { if (file.isFile) file.readText() else "" }
    } catch (_: Throwable) {
        ""
    }

    fun clear() {
        try {
            synchronized(lock) { file.delete() }
        } catch (_: Throwable) {
        }
    }

    /**
     * Makes this the app's last-resort recorder of uncaught exceptions on any thread: notes the crash, then hands over to the
     * handler that was there before (so the system still ends the process as it would have).
     */
    fun installAsUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record("uncaught on thread ${threadKind(thread.name)}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    private fun trimLocked() {
        if (file.length() <= maxBytes) return
        val text = file.readText()
        // Cut at the start of an entry so no note is left half-written.
        val keepFrom = text.length - maxBytes / 2
        val cut = text.indexOf("\n\n", keepFrom.coerceAtLeast(0))
        file.writeText(if (cut < 0) "" else text.substring(cut + 2))
    }

    private fun describe(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur) && depth < MAX_CAUSES) {
            append(if (depth == 0) "" else "caused by ").append(cur.javaClass.name).append('\n')
            val frames = cur.stackTrace
            for (f in frames.take(maxFrames)) {
                append("  at ").append(f.className).append('.').append(f.methodName)
                append('(').append(f.fileName ?: "?").append(':').append(f.lineNumber).append(")\n")
            }
            if (frames.size > maxFrames) append("  ... ").append(frames.size - maxFrames).append(" more\n")
            cur = cur.cause
            depth++
        }
    }.trimEnd()

    /** Keeps only the family of a thread name ("main", "DefaultDispatcher", "core"...), never anything longer. */
    private fun threadKind(name: String) = name.takeWhile { it != '-' && it != ' ' }.take(MAX_WHERE)

    companion object {
        private const val MAX_WHERE = 48
        private const val MAX_CAUSES = 4
    }
}
