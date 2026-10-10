package com.qtekfun.ultimatemaps.diagnostics

import android.content.Context
import java.io.File

/**
 * The app's local diagnostic notes (see [CrashNotes]) and how they are shown. Two files because two processes write: the
 * main one and the isolated `:core` one never share a file, so there is no cross-process locking to get wrong.
 */
object Diagnostics {
    private const val DIR = "diagnostics"
    private const val MAIN_FILE = "notes-main.txt"
    private const val CORE_FILE = "notes-core.txt"

    @Volatile private var main: CrashNotes? = null

    /** The notes of the main process (also where the core's death is noted, as the client sees it). */
    fun notes(context: Context): CrashNotes = main ?: synchronized(this) {
        main ?: CrashNotes(File(context.applicationContext.noBackupFilesDir, "$DIR/$MAIN_FILE")).also { main = it }
    }

    /** The notes written by the `:core` process itself. */
    fun coreNotes(context: Context): CrashNotes =
        CrashNotes(File(context.applicationContext.noBackupFilesDir, "$DIR/$CORE_FILE"))

    /** Everything the About screen shows: how the processes ended recently, then both note files. Empty text when there is nothing. */
    fun report(context: Context, alerts: List<String> = emptyList()): String = buildString {
        if (alerts.isNotEmpty()) {
            append("Alerts (cameras, zones, incidents)\n")
            alerts.forEach { append("  ").append(it).append('\n') }
            append('\n')
        }
        val exits = ExitReasons.lines(context)
        if (exits.isNotEmpty()) {
            append("Recent process exits (newest first)\n")
            exits.forEach { append("  ").append(it).append('\n') }
            append('\n')
        }
        val main = notes(context).read()
        if (main.isNotBlank()) append("Notes, app process\n").append(main).append('\n')
        val core = coreNotes(context).read()
        if (core.isNotBlank()) append("Notes, core process\n").append(core).append('\n')
    }.trimEnd()

    fun clear(context: Context) {
        notes(context).clear()
        coreNotes(context).clear()
    }
}
