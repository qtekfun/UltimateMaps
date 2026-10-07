package com.qtekfun.mapas.settings.backup

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * What a picked file holds. A settings file is a plain JSON document; the "everything" file is the places backup ZIP
 * (`mapas-backup.json`, unchanged) with one more entry, [ENTRY], so it is still a valid places backup.
 */
class SettingsFileContent(val snapshot: SettingsSnapshot?, val hasPlaces: Boolean)

object SettingsFile {
    /** The settings entry inside the combined ZIP. */
    const val ENTRY = "mapas-settings.json"

    /** The places entry written by `BackupService` (same name as there). */
    const val PLACES_ENTRY = "mapas-backup.json"

    const val MIME_JSON = "application/json"
    const val MIME_ZIP = "application/zip"

    fun settingsName(yyyymmdd: String) = "ultimatemaps-settings-$yyyymmdd.json"
    fun everythingName(yyyymmdd: String) = "ultimatemaps-backup-$yyyymmdd.zip"

    /**
     * Reads a settings JSON or a combined ZIP. Throws [SettingsBackupException] (a file with neither settings nor
     * places is [RejectReason.NOT_A_SETTINGS_FILE]). Nothing is applied here.
     */
    fun read(input: InputStream): SettingsFileContent {
        try {
            val stream = BufferedInputStream(input)
            stream.mark(4)
            val head = ByteArray(2)
            var n = 0
            while (n < 2) { val r = stream.read(head, n, 2 - n); if (r < 0) break; n += r }
            stream.reset()
            if (n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return readZip(stream)
            return SettingsFileContent(SettingsBackup.parse(readBounded(stream)), hasPlaces = false)
        } catch (e: ZipException) {
            throw SettingsBackupException(RejectReason.CORRUPT, "corrupt ZIP: ${e.message}")
        } catch (e: IOException) {
            throw SettingsBackupException(RejectReason.CORRUPT, "cannot read the file: ${e.message}")
        }
    }

    private fun readZip(input: InputStream): SettingsFileContent {
        var snapshot: SettingsSnapshot? = null
        var places = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    ENTRY -> snapshot = SettingsBackup.parse(readBounded(zip))
                    PLACES_ENTRY -> places = true
                }
            }
        }
        if (snapshot == null && !places) throw SettingsBackupException(RejectReason.NOT_A_SETTINGS_FILE, "the ZIP has no settings and no places")
        return SettingsFileContent(snapshot, places)
    }

    private fun readBounded(stream: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val r = stream.read(buffer)
            if (r < 0) break
            out.write(buffer, 0, r)
            if (out.size() > SettingsBackup.MAX_BYTES) throw SettingsBackupException(RejectReason.TOO_LARGE, "settings file too large")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
