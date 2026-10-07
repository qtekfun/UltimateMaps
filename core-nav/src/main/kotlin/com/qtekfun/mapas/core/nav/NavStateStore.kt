package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RoutePlanCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What survives the death of the process: the route being followed and how far along it the user was. */
data class PersistedNav(val plan: RoutePlan, val progressMeters: Double, val savedAtMillis: Long)

/**
 * Saves the navigation in progress so it can be resumed after the system kills the process (low memory, battery
 * policy). One small file, written atomically (temporary file, `fsync`, rename): a crash in the middle leaves the
 * previous complete file, never a half-written one. Reading never trusts the file: a corrupt, truncated, foreign,
 * stale (older than [maxAgeMillis]) or inconsistent file is deleted and reported as "nothing to resume".
 *
 * Privacy: the file holds the route (positions), so it lives in the app's private storage, is removed when the
 * navigation ends, expires on its own, and nothing from it is ever logged by this class.
 */
class NavStateStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
    /** Returns false (and keeps the old file) when the disk write failed. */
    fun save(plan: RoutePlan, progressMeters: Double): Boolean = try {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(BufferedOutputStream(fos, 1 shl 16))
            out.writeInt(MAGIC)
            out.writeByte(FORMAT)
            out.writeLong(clock())
            out.writeDouble(progressMeters)
            RoutePlanCodec.write(out, plan)
            out.flush()
            fos.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun load(): PersistedNav? {
        if (!file.isFile) return null
        val loaded = try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT) throw IOException("not a navigation file")
                val savedAt = input.readLong()
                val progress = input.readDouble()
                val plan = RoutePlanCodec.read(input)
                PersistedNav(plan, progress, savedAt)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        val age = clock() - (loaded?.savedAtMillis ?: 0L)
        val valid = loaded != null && loaded.plan.isFollowable() && loaded.progressMeters.isFinite() &&
            loaded.progressMeters >= 0.0 && age in -CLOCK_SLACK_MILLIS..maxAgeMillis
        if (!valid) {
            clear()
            return null
        }
        return loaded
    }

    fun clear() {
        file.delete()
        File(file.absolutePath + ".tmp").delete()
    }

    companion object {
        private const val MAGIC = 0x554D4E56 // "UMNV"
        private const val FORMAT = 1
        private const val CLOCK_SLACK_MILLIS = 60_000L

        /** A trip is not resumed after this long: the user is surely somewhere else by then. */
        const val DEFAULT_MAX_AGE_MILLIS = 3 * 60 * 60 * 1000L
    }
}
